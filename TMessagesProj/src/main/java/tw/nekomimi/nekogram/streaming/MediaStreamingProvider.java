package tw.nekomimi.nekogram.streaming;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.secretmedia.ExtendedDefaultDataSourceFactory;
import org.telegram.tgnet.TLRPC;

import java.io.FileNotFoundException;
import java.io.IOException;

public class MediaStreamingProvider extends ContentProvider {

    private HandlerThread callbackThread;
    private Handler callbackHandler;

    @Override
    public boolean onCreate() {
        callbackThread = new HandlerThread("MediaStreamingProvider");
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
        return true;
    }

    @Override
    public void shutdown() {
        callbackThread.quit();
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Nullable
    @Override
    public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
        if (mimeTypeFilter.startsWith("*/") || mimeTypeFilter.startsWith("video/")) {
            return new String[]{"video/mp4"};
        }
        return null;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        var context = getContext();
        if (context == null) {
            return null;
        }
        if (!"r".equals(mode)) {
            throw new SecurityException("Can only open files for read");
        }
        var callback = new ProxyFileDescriptorCallback(uri);
        var storageManager = StorageManagerCompat.from(getContext());
        try {
            return storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, callbackHandler);
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to open file");
        }
    }

    @Nullable
    /**
     * Builds a {@code content://} URI that streams the given document through
     * this provider. Returns null when the media already exists as a local
     * file (the {@code tg://} form is only produced for missing files).
     *
     * Exposed so in-app consumers (e.g. seek-preview frame extraction) can read
     * the media through the same proxy-fd path an external player would use.
     */
    public static Uri getStreamingUri(int currentAccount, TLRPC.Document document, Object parent) {
        var uri = FileStreamLoadOperation.prepareUri(currentAccount, document, parent);
        if (uri == null || !"tg".equals(uri.getScheme())) {
            return null;
        }

        var builder = uri.buildUpon();
        builder.scheme("content");
        builder.authority(ApplicationLoader.getApplicationId() + ".streaming");
        builder.path(FileLoader.getDocumentFileName(document));

        return builder.build();
    }

    public static boolean openForStreaming(Activity activity, int currentAccount, TLRPC.Document document, Object parent) {
        var uri = getStreamingUri(currentAccount, document, parent);
        if (uri == null) {
            return false;
        }
        var intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, document.mime_type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivityForResult(intent, 500);
        return true;
    }

    private static class ProxyFileDescriptorCallback extends StorageManagerCompat.ProxyFileDescriptorCallbackCompat {
        private long size;
        private DataSource dataSource;
        private final DataSource.Factory dataSourceFactory;
        private final DataSpec.Builder dataSpecBuilder;
        /** Offset the currently open stream is positioned at, -1 when closed. */
        private long streamPosition = -1;
        private boolean streamEof;

        public ProxyFileDescriptorCallback(Uri uri) {
            var tgUri = uri.buildUpon().scheme("tg").build();
            var mediaDataSourceFactory = new ExtendedDefaultDataSourceFactory(ApplicationLoader.applicationContext, "Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)");
            dataSourceFactory = mediaDataSourceFactory;
            dataSource = mediaDataSourceFactory.createDataSource();
            dataSpecBuilder = new DataSpec.Builder().setUri(tgUri);
            try {
                size = dataSource.open(dataSpecBuilder.build());
                dataSource.close();
            } catch (IOException e) {
                FileLog.e(e);
            }
            dataSource = null;
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            try {
                // The retriever reads in bursts; reopening the DataSource for
                // every single read used to be a fresh network request per
                // burst, which is why previewing an uncached video appeared to
                // hang. Keep one stream open while the reads stay sequential
                // and only reopen when the reader seeks.
                if (dataSource == null || streamEof || offset != streamPosition) {
                    closeStream();
                    dataSource = dataSourceFactory.createDataSource();
                    dataSpecBuilder.setPosition(offset);
                    dataSpecBuilder.setLength(androidx.media3.common.C.LENGTH_UNSET);
                    dataSource.open(dataSpecBuilder.build());
                    streamPosition = offset;
                    streamEof = false;
                }
                var bytesRead = dataSource.read(data, 0, size);
                if (bytesRead <= 0) {
                    streamEof = true;
                    return 0;
                }
                streamPosition += bytesRead;
                return bytesRead;
            } catch (IOException e) {
                streamEof = true;
                FileLog.e(e);
                throw new ErrnoException("onRead", OsConstants.EBADF);
            }
        }

        private void closeStream() {
            final DataSource old = dataSource;
            dataSource = null;
            streamPosition = -1;
            streamEof = false;
            if (old != null) {
                try {
                    old.close();
                } catch (Throwable ignore) {
                }
            }
        }

        @Override
        public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            throw new ErrnoException("onWrite", OsConstants.EOPNOTSUPP);
        }

        @Override
        public void onFsync() {

        }

        @Override
        public long onGetSize() {
            return size;
        }

        @Override
        public void onRelease() {
            closeStream();
        }
    }
}
