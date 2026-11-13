/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.net.Uri;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.util.Log;

import org.telegram.tgnet.TLRPC;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class FileStreamLoadOperation extends BaseDataSource implements FileLoadOperationStream {

    public static final ConcurrentHashMap<Long, FileStreamLoadOperation> allStreams = new ConcurrentHashMap<>();

    private FileLoadOperation loadOperation;

    private Uri uri;
    private long bytesRemaining;
    private long bytesTransferred;
    private long requestedLength;
    private boolean opened;
    private long currentOffset;
    private CountDownLatch countDownLatch;
    private boolean debugWaitLogged;
    private RandomAccessFile file;
    private TLRPC.Document document;
    private Object parentObject;
    private int currentAccount;
    File currentFile;

    private static final ConcurrentHashMap<Long, Integer> priorityMap = new ConcurrentHashMap<>();

    public FileStreamLoadOperation() {
        super(/* isNetwork= */ true);
    }

    @Deprecated
    public FileStreamLoadOperation(@Nullable TransferListener listener) {
        this();
        if (listener != null) {
            addTransferListener(listener);
        }
    }

    public static int getStreamPrioriy(TLRPC.Document document) {
        if (document == null) {
            return FileLoader.PRIORITY_HIGH;
        }
        Integer integer = priorityMap.get(document.id);
        if (integer == null) {
            return FileLoader.PRIORITY_HIGH;
        }
        return integer;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        currentAccount = Utilities.parseInt(uri.getQueryParameter("account"));
        parentObject = FileLoader.getInstance(currentAccount).getParentObject(Utilities.parseInt(uri.getQueryParameter("rid")));
        document = new TLRPC.TL_document();
        document.access_hash = Utilities.parseLong(uri.getQueryParameter("hash"));
        document.id = Utilities.parseLong(uri.getQueryParameter("id"));
        document.size = Utilities.parseLong(uri.getQueryParameter("size"));
        document.dc_id = Utilities.parseInt(uri.getQueryParameter("dc"));
        document.mime_type = uri.getQueryParameter("mime");
        document.file_reference = Utilities.hexToBytes(uri.getQueryParameter("reference"));
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = uri.getQueryParameter("name");
        document.attributes.add(filename);
        if (document.mime_type.startsWith("video")) {
            document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        } else if (document.mime_type.startsWith("audio")) {
            document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        }
        allStreams.put(document.id, this);
        currentOffset = dataSpec.position;
        requestedLength = dataSpec.length;
        loadOperation = FileLoader.getInstance(currentAccount).loadStreamFile(this, document, null, parentObject, currentOffset, false, getCurrentPriority());
        bytesTransferred = 0;
        bytesRemaining = document.size - dataSpec.position;
        if (requestedLength != C.LENGTH_UNSET) {
            bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
        }
        opened = true;
        android.util.Log.w("STREAMDBG", "open id=" + document.id + " mime=" + document.mime_type + " size=" + document.size + " pos=" + dataSpec.position + " len=" + dataSpec.length);
        transferStarted(dataSpec);
        if (loadOperation != null) {
            currentFile = loadOperation.getCurrentFile();
            if (currentFile != null) {
                try {
                    file = new RandomAccessFile(currentFile, "r");
                    file.seek(currentOffset);
                    if (loadOperation.isFinished()) {
                        bytesRemaining = currentFile.length() - currentOffset;
                        if (requestedLength != C.LENGTH_UNSET) {
                            bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
                        }
                    }
                } catch (Throwable e) {
                }
            }
        }
        FileLog.e("FileStreamLoadOperation " + document.id + " open operation=" + loadOperation + " currentFile=" + currentFile + " file=" + file + " bytesRemaining=" + bytesRemaining + " me=" + this);
        FileLog.e("FileStreamLoadOperation " + document.id + " " + MessageObject.getVideoWidth(document) + "x" + MessageObject.getVideoWidth(document) + " mime_type="+document.mime_type+" codec="+MessageObject.getVideoCodec(document)+" size="+ document.size);
        return bytesRemaining;
    }

    private int getCurrentPriority() {
        Integer priority = priorityMap.getOrDefault(document.id, null);
        if (priority != null) {
            return priority;
        }
        return FileLoader.PRIORITY_HIGH;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
//            FileLog.e("FileStreamLoadOperation " + document.id + " read 0 return");
            return 0;
        } else if (bytesRemaining == 0) {
//            FileLog.e("FileStreamLoadOperation " + document.id + " read RESULT_END_OF_INPUT");
            return C.RESULT_END_OF_INPUT;
        } else {
            int availableLength = 0;
            int bytesRead;
            try {
                if (bytesRemaining < readLength) {
                    readLength = (int) bytesRemaining;
                }
                while ((availableLength == 0 && opened) || file == null) {
                    availableLength = (int) loadOperation.getDownloadedLengthFromOffset(currentOffset, readLength)[0];
                    if (availableLength == 0 && loadOperation.isFinished()) {
                        // Once the download has finished the parts bitmap is gone and
                        // getDownloadedLengthFromOffset() keeps reporting 0. The bytes
                        // are already on disk, so read them directly instead of waiting
                        // for a download notification that will never arrive.
                        File finishedFile = loadOperation.getCurrentFileFast();
                        if (finishedFile != null && finishedFile.length() > currentOffset) {
                            availableLength = (int) Math.min(readLength, finishedFile.length() - currentOffset);
                        }
                    }
                    if (availableLength == 0) {
                        countDownLatch = new CountDownLatch(1);
                        FileLoadOperation loadOperation = FileLoader.getInstance(currentAccount).loadStreamFile(this, document, null, parentObject, currentOffset, false, getCurrentPriority());
                        if (this.loadOperation != loadOperation) {
//                            FileLog.e("FileStreamLoadOperation " + document.id + " read: changed operation!");
                            this.loadOperation.removeStreamListener(this);
                            this.loadOperation = loadOperation;
                        }
//                        FileLog.e("FileStreamLoadOperation " + document.id + " read sleeping.... Zzz");
                        final CountDownLatch latch = countDownLatch;
                        if (latch != null) {
                            if (!debugWaitLogged) {
                                debugWaitLogged = true;
                                android.util.Log.w("STREAMDBG", "read wait id=" + document.id + " offset=" + currentOffset + " len=" + readLength + " available=0");
                            }
                            // The original unbounded await() could hang forever when a
                            // tail (moov) request never completed, which looked exactly
                            // like "the video must be downloaded first". Bail out so
                            // ExoPlayer reports an error and PhotoViewer falls back.
                            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IOException("STREAMDBG wait timeout id=" + document.id + " offset=" + currentOffset + " len=" + readLength);
                            }
                            countDownLatch = null;
                        }
                    }
//                    FileLog.e("FileStreamLoadOperation " + document.id + " read availableLength=" + availableLength);
                    File currentFileFast = loadOperation.getCurrentFileFast();
                    if (file == null || !Objects.equals(currentFile, currentFileFast)) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("check stream file " + currentFileFast);
                        }
                        if (file != null) {
                            try {
                                file.close();
                            } catch (Exception ignore) {

                            }
                        }
//                        FileLog.e("FileStreamLoadOperation " + document.id + " read update file from " + currentFile + " to " + currentFileFast + " me=" + this);
                        currentFile = currentFileFast;
                        if (currentFile != null) {
                            try {
                                file = new RandomAccessFile(currentFile, "r");
                                file.seek(currentOffset);
                                if (loadOperation.isFinished()) {
                                    bytesRemaining = currentFile.length() - currentOffset;
                                    if (requestedLength != C.LENGTH_UNSET) {
                                        bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
                                    }
                                }
                            } catch (Throwable e) {
                                if (loadOperation.isFinished() && !currentFile.exists()) {
                                    FileLoader.getInstance(currentAccount).cancelLoadFile(loadOperation.getFileName());
                                    FileLoadOperation newLoadOperation = FileLoader.getInstance(currentAccount).loadStreamFile(this, document, null, parentObject, currentOffset, false, getCurrentPriority());
                                    if (this.loadOperation != newLoadOperation) {
//                            FileLog.e("FileStreamLoadOperation " + document.id + " read: changed operation!");
                                        this.loadOperation.removeStreamListener(this);
                                        this.loadOperation = newLoadOperation;
                                    }
                                }
                            }
                        }
                    } else {
//                        FileLog.e("FileStreamLoadOperation " + document.id + " read have exact same file");
                    }
                }
                if (!opened) {
//                    FileLog.e("FileStreamLoadOperation " + document.id + " read return, not opened");
                    return 0;
                }
                bytesRead = file.read(buffer, offset, availableLength);
                if (bytesRead > 0) {
                    currentOffset += bytesRead;
                    bytesRemaining -= bytesRead;
                    bytesTransferred += bytesRead;
                    bytesTransferred(bytesRead);
                }
            } catch (InterruptedException e) {
                FileLog.e(e);
                return C.RESULT_NOTHING_READ;
            } catch (Exception e) {
                throw new IOException(e);
            }
            return bytesRead;
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        FileLog.e("FileStreamLoadOperation " + document.id + " close me=" + this);
        if (loadOperation != null) {
            loadOperation.removeStreamListener(this);
        }
        if (file != null) {
            try {
                file.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
            file = null;
        }
        uri = null;
        allStreams.remove(document.id);
        if (opened) {
            opened = false;
            transferEnded();
        }
        if (countDownLatch != null) {
         //   FileLog.d("FileStreamLoadOperation count down");
            countDownLatch.countDown();
            countDownLatch = null;
        }
    }

    @Override
    public void newDataAvailable() {
//        FileLog.e("FileStreamLoadOperation " + document.id + " newDataAvailable me=" + this);
        CountDownLatch latch = countDownLatch;
        countDownLatch = null;
        if (latch != null) {
            latch.countDown();
        }
    }

    public static void setPriorityForDocument(TLRPC.Document document, int priority) {
        if (document != null) {
            priorityMap.put(document.id, priority);
        }
    }

    @Nullable
    public static Uri prepareUri(int currentAccount, TLRPC.Document document, Object parent) {
        String attachFileName = FileLoader.getAttachFileName(document);
        File file = FileLoader.getInstance(currentAccount).getPathToAttach(document);

        if (file != null && file.exists()) {
            return Uri.fromFile(file);
        }
        try {
            String params =
                "?account=" + currentAccount +
                "&id=" + document.id +
                "&hash=" + document.access_hash +
                "&dc=" + document.dc_id +
                "&size=" + document.size +
                "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                "&rid=" + FileLoader.getInstance(currentAccount).getFileReference(parent) +
                "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
            return Uri.parse("tg://" + attachFileName + params);
        } catch (UnsupportedEncodingException e) {
            FileLog.e(e);
        }
        return null;
    }
}
