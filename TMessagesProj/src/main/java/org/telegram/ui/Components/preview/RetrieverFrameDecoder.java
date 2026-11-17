/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.telegram.ui.Components.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;

import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TelegramMediaDataSource;

import java.io.File;

/**
 * MediaMetadataRetriever fallback. It covers everything the stage 1 MediaCodec
 * path deliberately does not: fragmented MP4, partially downloaded files and
 * content:// sources.
 *
 * When the local file is not complete the retriever reads through
 * {@link TelegramMediaDataSource}, which serves only bytes the downloader says
 * are actually present. That keeps an unbuffered range from being mistaken for
 * a hole full of zeros.
 */
public final class RetrieverFrameDecoder implements FrameDecoder {

    private static final long RETRY_OPEN_MS = 3000L;

    private final Context context;
    private final File file;
    private final long declaredSize;
    private final TLRPC.Document document;
    private final int account;
    private final Uri uri;

    private MediaMetadataRetriever retriever;
    private TelegramMediaDataSource dataSource;
    private long lastOpenAttempt;
    private long durationMs;
    private int videoWidth;
    private int videoHeight;
    private int rotation;
    /** True when the whole file is local, so every timestamp may be decoded. */
    private boolean sourceComplete;
    private int attemptCount;
    private int unavailableSkips;
    private volatile boolean lastDataMissing;
    private boolean closed;
    private volatile boolean cancelRequested;
    private volatile boolean cancelled;

    public RetrieverFrameDecoder(Context context, File file, long declaredSize,
                                 TLRPC.Document document, int account) {
        this.context = context;
        this.file = file;
        this.declaredSize = declaredSize;
        this.document = document;
        this.account = account;
        this.uri = null;
    }

    public RetrieverFrameDecoder(Context context, Uri uri) {
        this.context = context;
        this.file = null;
        this.declaredSize = 0;
        this.document = null;
        this.account = -1;
        this.uri = uri;
    }

    /**
     * True when every byte of the declared media is locally available.
     */
    public static boolean isFileComplete(File file, long declaredSize,
                                         TLRPC.Document document, int account) {
        if (file == null || !file.exists()) {
            return false;
        }
        if (document == null && file.getName().endsWith(".temp")) {
            // A .temp is the downloader's sparse working file. Without the
            // document there is no way to ask which ranges are really there,
            // so its length proves nothing and the retriever must stream it.
            return false;
        }
        if (declaredSize <= 0) {
            return file.length() > 0;
        }
        if (document != null && account >= 0) {
            try {
                final FileLoader loader = FileLoader.getInstance(account);
                final long downloaded = loader.getDownloadedLengthFast(
                        document, 0, declaredSize);
                if (downloaded >= declaredSize) {
                    return true;
                }
                if (loader.getLoadOperation(document) != null) {
                    return false;
                }
            } catch (Throwable ignore) {
            }
        }
        return file.length() >= declaredSize;
    }

    /**
     * Opens the retriever and reads duration/rotation. Safe to call more than
     * once; a failed attempt is throttled so a streaming file whose moov has
     * not arrived yet does not pay for a full parse on every drag tick.
     */
    public long prepare() {
        if (closed) {
            return 0;
        }
        if (ensureOpen()) {
            return durationMs;
        }
        return 0;
    }

    private boolean ensureOpen() {
        if (closed) {
            return false;
        }
        if (retriever != null) {
            return true;
        }
        final long now = android.os.SystemClock.uptimeMillis();
        if (lastOpenAttempt > 0 && now - lastOpenAttempt < RETRY_OPEN_MS) {
            return false;
        }
        lastOpenAttempt = now;
        releaseInternal();
        try {
            retriever = new MediaMetadataRetriever();
            sourceComplete = true;
            if (uri != null) {
                retriever.setDataSource(context, uri);
            } else if (file != null) {
                sourceComplete = isFileComplete(file, declaredSize, document, account);
                if (sourceComplete) {
                    retriever.setDataSource(file.getAbsolutePath());
                } else {
                    dataSource = new TelegramMediaDataSource(file, declaredSize,
                            (offset, length) -> {
                                if (document == null || account < 0) {
                                    return 0;
                                }
                                try {
                                    return FileLoader.getInstance(account)
                                            .getDownloadedLengthFast(document, offset, length);
                                } catch (Throwable e) {
                                    return 0;
                                }
                            });
                    retriever.setDataSource(dataSource);
                }
            } else {
                return false;
            }
            extractMetadata();
            PreviewLog.d("SeekPreview: retriever ready duration=" + durationMs
                    + " size=" + videoWidth + "x" + videoHeight + " rot=" + rotation
                    + " source=" + (uri != null ? "uri" : sourceComplete ? "file" : "stream")
                    + " fileLen=" + (file != null ? file.length() : 0)
                    + " declared=" + declaredSize
                    + " availableMs=" + availableDurationMs());
            return true;
        } catch (Throwable e) {
            PreviewLog.e("SeekPreview: retriever setDataSource failed", e);
            releaseInternal();
            return false;
        }
    }

    private void extractMetadata() {
        durationMs = 0;
        videoWidth = 0;
        videoHeight = 0;
        rotation = 0;
        if (retriever == null) {
            return;
        }
        try {
            final String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                durationMs = Long.parseLong(duration);
            }
        } catch (Throwable ignore) {
        }
        try {
            final String width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            if (width != null) {
                videoWidth = Integer.parseInt(width);
            }
        } catch (Throwable ignore) {
        }
        try {
            final String height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (height != null) {
                videoHeight = Integer.parseInt(height);
            }
        } catch (Throwable ignore) {
        }
        try {
            final String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (value != null) {
                rotation = Integer.parseInt(value) % 360;
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * How many bytes are present as one contiguous prefix from offset zero.
     * Telegram downloads sequentially in the common streaming case, so this is
     * a good approximation of the part of the timeline that can be decoded
     * without touching the network.
     */
    private long downloadedPrefixBytes() {
        if (file == null) {
            return 0;
        }
        final long length = file.length();
        if (declaredSize > 0 && document != null && account >= 0) {
            try {
                final long downloaded = FileLoader.getInstance(account)
                        .getDownloadedLengthFast(document, 0, declaredSize);
                if (downloaded > 0) {
                    return Math.min(downloaded, length);
                }
            } catch (Throwable ignore) {
            }
        }
        return length;
    }

    /**
     * Last timestamp that is backed by local bytes. A decoder seek into an
     * unbuffered range can stall for a long time (the platform retriever keeps
     * retrying instead of failing fast), which used to freeze the whole fill
     * queue. Skipping those tasks is the strict no-network behaviour.
     */
    private long availableDurationMs() {
        if (sourceComplete) {
            return durationMs > 0 ? durationMs : -1;
        }
        if (file == null || declaredSize <= 0 || durationMs <= 0) {
            // Unknown source length: do not guess, let the retriever try.
            return -1;
        }
        final long prefix = downloadedPrefixBytes();
        if (prefix <= 0) {
            return 0;
        }
        if (prefix >= declaredSize) {
            return durationMs;
        }
        // Keep a small margin so a sample that straddles the current end of
        // the file is not attempted either.
        final long margin = Math.min(prefix / 50, 2L * 1024 * 1024);
        final long usable = Math.max(0, prefix - margin);
        return (long) (durationMs * (double) usable / declaredSize);
    }

    @Override
    public DecodedFrame decodeAt(long timeMs, int maxWidth, int maxHeight) throws Exception {
        if (closed) {
            throw new IllegalStateException("retriever is closed");
        }
        lastDataMissing = false;
        if (!ensureOpen()) {
            lastDataMissing = true;
            return null;
        }
        final long available = availableDurationMs();
        if (available >= 0 && timeMs > available) {
            if (unavailableSkips < 5 || (unavailableSkips & 31) == 0) {
                PreviewLog.d("SeekPreview: retriever skip t=" + timeMs
                        + " availableMs=" + available
                        + " prefix=" + downloadedPrefixBytes()
                        + "/" + declaredSize);
            }
            unavailableSkips++;
            lastDataMissing = true;
            return null;
        }
        final long decodeStarted = SystemClock.uptimeMillis();
        final boolean swap = rotation == 90 || rotation == 270;
        int displayW = videoWidth;
        int displayH = videoHeight;
        if (swap) {
            final int tmp = displayW;
            displayW = displayH;
            displayH = tmp;
        }
        final boolean portrait = displayW > 0 && displayH > displayW;
        final int boxW = portrait ? maxHeight : maxWidth;
        final int boxH = portrait ? maxWidth : maxHeight;
        final long timeUs = Math.max(0, timeMs) * 1000L;

        Bitmap bitmap = fetchScaledFrame(retriever, timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC, boxW, boxH);
        if (bitmap == null) {
            bitmap = fetchScaledFrame(retriever, timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST, boxW, boxH);
        }
        if (cancelRequested) {
            cancelled = true;
            if (bitmap != null) {
                bitmap.recycle();
            }
            return null;
        }
        final long cost = SystemClock.uptimeMillis() - decodeStarted;
        attemptCount++;
        if (bitmap == null) {
            if (attemptCount <= 5) {
                PreviewLog.d("SeekPreview: retriever no frame t=" + timeMs
                        + " cost=" + cost + "ms");
            }
            return null;
        }
        bitmap = toRgb565(bitmap);
        if (attemptCount <= 3 || cost >= 500 || attemptCount % 24 == 0) {
            PreviewLog.d("SeekPreview: retriever decode #" + attemptCount
                    + " t=" + timeMs + " cost=" + cost + "ms"
                    + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " availableMs=" + available);
        }
        return new DecodedFrame(bitmap, timeMs, -1);
    }

    static Bitmap fetchScaledFrame(MediaMetadataRetriever retriever, long timeUs,
                                   int option, int targetW, int targetH) {
        if (retriever == null || targetW <= 0 || targetH <= 0) {
            return null;
        }
        try {
            final Bitmap scaled = retriever.getScaledFrameAtTime(
                    timeUs, option, targetW, targetH);
            if (scaled != null) {
                return scaled;
            }
        } catch (Throwable ignore) {
            // Some OEM builds advertise this method but throw; fall through.
        }
        try {
            final Bitmap raw = retriever.getFrameAtTime(timeUs, option);
            if (raw == null) {
                return null;
            }
            final int width = raw.getWidth();
            final int height = raw.getHeight();
            if (width <= 0 || height <= 0) {
                raw.recycle();
                return null;
            }
            final float scale = Math.min(targetW / (float) width, targetH / (float) height);
            final int outW = Math.max(1, Math.round(width * scale));
            final int outH = Math.max(1, Math.round(height * scale));
            if (outW == width && outH == height) {
                return raw;
            }
            final Bitmap scaled = Bitmap.createScaledBitmap(raw, outW, outH, true);
            if (scaled != raw) {
                raw.recycle();
            }
            return scaled;
        } catch (Throwable ignore) {
            return null;
        }
    }

    static Bitmap toRgb565(Bitmap bitmap) {
        if (bitmap == null || bitmap.getConfig() == Bitmap.Config.RGB_565) {
            return bitmap;
        }
        try {
            final Bitmap copy = bitmap.copy(Bitmap.Config.RGB_565, false);
            if (copy != null) {
                bitmap.recycle();
                return copy;
            }
        } catch (Throwable ignore) {
        }
        return bitmap;
    }

    @Override
    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String name() {
        return uri != null ? "retriever-uri" : "retriever";
    }

    @Override
    public void cancel() {
        cancelRequested = true;
    }

    @Override
    public void resetCancel() {
        cancelRequested = false;
        cancelled = false;
    }

    @Override
    public boolean wasCancelled() {
        return cancelled;
    }

    @Override
    public boolean wasDataMissing() {
        return lastDataMissing;
    }

    @Override
    public void close() {
        closed = true;
        cancelRequested = true;
        releaseInternal();
    }

    private void releaseInternal() {
        final MediaMetadataRetriever oldRetriever = retriever;
        final TelegramMediaDataSource oldSource = dataSource;
        retriever = null;
        dataSource = null;
        if (oldRetriever != null) {
            try {
                oldRetriever.release();
            } catch (Throwable ignore) {
            }
        }
        if (oldSource != null) {
            try {
                oldSource.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
