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

public class FileStreamLoadOperation extends BaseDataSource implements FileLoadOperationStream {

    public static final ConcurrentHashMap<Long, FileStreamLoadOperation> allStreams = new ConcurrentHashMap<>();

    private FileLoadOperation loadOperation;

    private Uri uri;
    private long bytesRemaining;
    private long bytesTransferred;
    private long requestedLength;
    private volatile boolean opened;
    private boolean transferStarted;
    private long currentOffset;
    private final Object dataLock = new Object();
    private long dataRevision;
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
        // A DataSource instance may be reopened after close(). Drop every piece of
        // state belonging to the previous request before publishing the new one.
        synchronized (dataLock) {
            opened = false;
            dataRevision++;
            dataLock.notifyAll();
        }
        FileLoadOperation previous = loadOperation;
        loadOperation = null;
        if (previous != null) {
            previous.removeStreamListener(this);
        }
        closeFileHandle();
        currentFile = null;
        if (transferStarted) {
            transferStarted = false;
            transferEnded();
        }

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
        if (document.mime_type != null && document.mime_type.startsWith("video")) {
            document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        } else if (document.mime_type != null && document.mime_type.startsWith("audio")) {
            document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        }

        currentOffset = dataSpec.position;
        requestedLength = dataSpec.length;
        bytesTransferred = 0;
        bytesRemaining = Math.max(0L, document.size - currentOffset);
        if (requestedLength != C.LENGTH_UNSET) {
            bytesRemaining = Math.min(bytesRemaining, Math.max(0L, requestedLength));
        }
        synchronized (dataLock) {
            dataRevision++;
            opened = true;
        }
        allStreams.put(document.id, this);
        loadOperation = FileLoader.getInstance(currentAccount).loadStreamFile(
                this, document, null, parentObject, currentOffset, false, getCurrentPriority());

        transferStarted(dataSpec);
        transferStarted = true;
        if (loadOperation != null) {
            refreshFileHandle(loadOperation);
            if (loadOperation.isFinished() && currentFile != null) {
                long actualRemaining = Math.max(0L, currentFile.length() - currentOffset);
                bytesRemaining = requestedLength == C.LENGTH_UNSET
                        ? actualRemaining
                        : Math.min(actualRemaining, Math.max(0L, requestedLength));
            }
        }
        FileLog.e("FileStreamLoadOperation " + document.id + " open operation=" + loadOperation
                + " currentFile=" + currentFile + " file=" + file
                + " bytesRemaining=" + bytesRemaining + " me=" + this);
        FileLog.e("FileStreamLoadOperation " + document.id + " "
                + MessageObject.getVideoWidth(document) + "x" + MessageObject.getVideoWidth(document)
                + " mime_type=" + document.mime_type + " codec=" + MessageObject.getVideoCodec(document)
                + " size=" + document.size);
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
            return 0;
        }
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        readLength = (int) Math.min((long) readLength, bytesRemaining);

        int staleReadAttempts = 0;
        try {
            while (opened) {
                // Capture the revision before inspecting the range. If a download callback lands
                // during the inspection/request, the revision changes and the wait below is skipped.
                final long observedRevision = getDataRevision();
                FileLoadOperation active = loadOperation;
                if (active == null) {
                    active = requestStreamAt(currentOffset);
                    if (active == null) {
                        throw new IOException("Unable to create stream operation at " + currentOffset);
                    }
                }

                long availableLength = active.getKnownDownloadedLengthFast(currentOffset, readLength);
                if (availableLength <= 0 && active.isFinished()) {
                    // Once finished, the range bitmap may be gone. The final file length is then
                    // the authoritative readable boundary.
                    File finishedFile = active.getCurrentFileFast();
                    if (finishedFile != null && finishedFile.length() > currentOffset) {
                        availableLength = Math.min((long) readLength, finishedFile.length() - currentOffset);
                    }
                }

                refreshFileHandle(active);
                if (active.isFinished() && currentFile != null) {
                    long actualRemaining = Math.max(0L, currentFile.length() - currentOffset);
                    bytesRemaining = requestedLength == C.LENGTH_UNSET
                            ? actualRemaining
                            : Math.min(actualRemaining, Math.max(0L, requestedLength - bytesTransferred));
                    if (bytesRemaining <= 0) {
                        return C.RESULT_END_OF_INPUT;
                    }
                    availableLength = Math.min(availableLength > 0 ? availableLength : actualRemaining,
                            Math.min((long) readLength, bytesRemaining));
                }

                if (availableLength > 0 && file != null) {
                    int wanted = (int) Math.min(availableLength, readLength);
                    int bytesRead = file.read(buffer, offset, wanted);
                    if (bytesRead > 0) {
                        currentOffset += bytesRead;
                        bytesRemaining -= bytesRead;
                        bytesTransferred += bytesRead;
                        bytesTransferred(bytesRead);
                        return bytesRead;
                    }

                    // The downloader's range snapshot and the file handle can cross a file
                    // replacement. Never turn this into RESULT_END_OF_INPUT: retry against a
                    // freshly queried range and a freshly opened file.
                    closeFileHandle();
                    if (++staleReadAttempts >= 3 || !awaitDataChange(observedRevision)) {
                        throw new EOFException("Downloaded range was not readable at " + currentOffset);
                    }
                    continue;
                }

                if (!opened) {
                    break;
                }

                // Re-aim the existing FileLoadOperation at this exact byte. Its setStream()
                // updates the stream priority offset; it does not require a full-file download.
                active = requestStreamAt(currentOffset);
                if (active == null) {
                    throw new IOException("Unable to resume stream at " + currentOffset);
                }

                if (!awaitDataChange(observedRevision)) {
                    if (!opened) {
                        break;
                    }
                    throw new IOException("Timed out waiting for stream bytes at " + currentOffset
                            + " length=" + readLength);
                }
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            java.io.InterruptedIOException interrupted = new java.io.InterruptedIOException(
                    "Stream read interrupted at " + currentOffset);
            interrupted.initCause(e);
            throw interrupted;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private long getDataRevision() {
        synchronized (dataLock) {
            return dataRevision;
        }
    }

    /**
     * Waits for a newer download notification. The revision check and wait share one monitor,
     * preventing a callback between the availability check and wait from being lost.
     */
    private boolean awaitDataChange(long observedRevision) throws InterruptedException {
        synchronized (dataLock) {
            long deadline = System.currentTimeMillis() + 15000L;
            while (opened && dataRevision == observedRevision) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                dataLock.wait(remaining);
            }
            return !opened || dataRevision != observedRevision;
        }
    }

    private FileLoadOperation requestStreamAt(long offset) {
        FileLoadOperation requested = FileLoader.getInstance(currentAccount).loadStreamFile(
                this, document, null, parentObject, offset, false, getCurrentPriority());
        if (!opened) {
            if (requested != null) {
                requested.removeStreamListener(this);
            }
            return null;
        }
        FileLoadOperation previous = loadOperation;
        if (previous != requested) {
            loadOperation = requested;
            if (previous != null) {
                previous.removeStreamListener(this);
            }
        }
        return requested;
    }

    private void refreshFileHandle(FileLoadOperation active) {
        File nextFile = active.getCurrentFileFast();
        if (file != null && Objects.equals(currentFile, nextFile)) {
            return;
        }
        closeFileHandle();
        currentFile = nextFile;
        if (nextFile != null) {
            try {
                file = new RandomAccessFile(nextFile, "r");
                file.seek(currentOffset);
            } catch (Exception e) {
                closeFileHandle();
            }
        }
    }

    private void closeFileHandle() {
        if (file != null) {
            try {
                file.close();
            } catch (Exception ignore) {
            }
            file = null;
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        synchronized (dataLock) {
            opened = false;
            dataRevision++;
            dataLock.notifyAll();
        }
        FileLog.e("FileStreamLoadOperation " + (document != null ? document.id : 0) + " close me=" + this);
        FileLoadOperation active = loadOperation;
        loadOperation = null;
        if (active != null) {
            active.removeStreamListener(this);
        }
        closeFileHandle();
        currentFile = null;
        uri = null;
        if (document != null) {
            allStreams.remove(document.id, this);
        }
        if (transferStarted) {
            transferStarted = false;
            transferEnded();
        }
    }

    @Override
    public void newDataAvailable() {
        synchronized (dataLock) {
            dataRevision++;
            dataLock.notifyAll();
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
