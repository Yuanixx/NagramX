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

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Local, exact-time decoder for short videos with few keyframes.
 *
 * MediaMetadataRetriever with OPTION_CLOSEST decodes from the preceding
 * keyframe up to the requested moment, so every position gets its own picture.
 * The file must be complete first: a sparse .temp has holes that would make the
 * decode fail or return garbage. Until then this decoder simply has no frame,
 * and the bubble shows only the time.
 */
public final class ShortVideoFrameDecoder implements FrameDecoder {

    private static final long WAIT_SLICE_MS = 200L;
    private static final long WAIT_LIMIT_MS = 60_000L;
    private static final int FRAME_WIDTH = 160;
    private static final int FRAME_HEIGHT = 90;

    private final File file;
    private final long declaredSize;
    private final TLRPC.Document document;
    private final int account;
    private final long fallbackDurationMs;

    private MediaMetadataRetriever retriever;
    private int rotation;
    private boolean closed;
    private volatile boolean cancelRequested;
    private volatile boolean cancelled;
    private long lastWaitLog;

    private int frames;
    private long totalMs;
    private final ArrayList<Long> costs = new ArrayList<>();

    public ShortVideoFrameDecoder(File file, long declaredSize, TLRPC.Document document,
                                  int account, long fallbackDurationMs) {
        this.file = file;
        this.declaredSize = declaredSize;
        this.document = document;
        this.account = account;
        this.fallbackDurationMs = fallbackDurationMs;
    }

    @Override
    public DecodedFrame decodeAt(long timeMs, int maxWidth, int maxHeight) throws Exception {
        if (closed) {
            throw new IllegalStateException("short decoder is closed");
        }
        cancelRequested = false;
        cancelled = false;
        final long waitStarted = SystemClock.uptimeMillis();
        while (!RetrieverFrameDecoder.isFileComplete(file, declaredSize, document, account)) {
            if (cancelRequested) {
                cancelled = true;
                return null;
            }
            final long now = SystemClock.uptimeMillis();
            if (now - waitStarted > WAIT_LIMIT_MS) {
                if (lastWaitLog == 0L || now - lastWaitLog >= 5000L) {
                    lastWaitLog = now;
                    PreviewLog.d("SeekPreview: short video still downloading "
                            + downloadedPercent() + "%");
                }
                return null;
            }
            SystemClock.sleep(WAIT_SLICE_MS);
        }
        if (!ensureOpen()) {
            return null;
        }
        final boolean swap = rotation == 90 || rotation == 270;
        final int boxW = swap ? FRAME_HEIGHT : FRAME_WIDTH;
        final int boxH = swap ? FRAME_WIDTH : FRAME_HEIGHT;
        final long decodeStarted = SystemClock.uptimeMillis();
        Bitmap bitmap = RetrieverFrameDecoder.fetchScaledFrame(retriever,
                Math.max(0L, timeMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST,
                boxW, boxH);
        if (cancelRequested) {
            cancelled = true;
            if (bitmap != null) {
                bitmap.recycle();
            }
            return null;
        }
        if (bitmap == null) {
            return null;
        }
        bitmap = RetrieverFrameDecoder.toRgb565(bitmap);
        final long cost = SystemClock.uptimeMillis() - decodeStarted;
        frames++;
        totalMs += cost;
        costs.add(cost);
        if (frames <= 3 || frames % 10 == 0) {
            PreviewLog.d("SeekPreview: short frame #" + frames
                    + " t=" + timeMs + " cost=" + cost + "ms");
        }
        return new DecodedFrame(bitmap, timeMs, -1);
    }

    private boolean ensureOpen() {
        if (retriever != null) {
            return true;
        }
        MediaMetadataRetriever opened = null;
        try {
            opened = new MediaMetadataRetriever();
            opened.setDataSource(file.getAbsolutePath());
            final String value = opened.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            try {
                rotation = value == null ? 0 : Integer.parseInt(value);
            } catch (Throwable ignore) {
                rotation = 0;
            }
            retriever = opened;
            opened = null;
            return true;
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: short retriever open failed", e);
            return false;
        } finally {
            if (opened != null) {
                try {
                    opened.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private int downloadedPercent() {
        if (declaredSize <= 0) {
            return 100;
        }
        try {
            final long got = FileLoader.getInstance(account)
                    .getDownloadedLengthFast(document, 0, declaredSize);
            return (int) Math.max(0L, Math.min(100L, got * 100L / declaredSize));
        } catch (Throwable e) {
            return 0;
        }
    }

    @Override
    public long getDurationMs() {
        return fallbackDurationMs;
    }

    @Override
    public String name() {
        return "short-local";
    }

    @Override
    public void cancel() {
        cancelRequested = true;
    }

    @Override
    public boolean wasCancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        closed = true;
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Throwable ignore) {
            }
            retriever = null;
        }
        if (frames > 0) {
            final ArrayList<Long> sorted = new ArrayList<>(costs);
            Collections.sort(sorted);
            final long median = sorted.get(sorted.size() / 2);
            PreviewLog.d("SeekPreview: short video frames=" + frames
                    + " median=" + median + "ms total=" + totalMs + "ms");
        }
    }
}
