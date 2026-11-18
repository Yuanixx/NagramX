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
import android.graphics.BitmapFactory;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Disk persistence for preview thumbnails, so a video that was previewed once
 * starts its next session with the coarse layer already on screen.
 *
 * Layout: {@code cache/seek_preview/{dialogId}_{messageId}/} holds one JPEG per
 * decoded keyframe, named {@code {documentId}_{declaredSize}_{posMs}.jpg}. The
 * session identity follows the message (not the document): a multi-quality
 * video swaps documents as qualities become cached, and the directory
 * intentionally survives that swap so old-quality frames keep serving the
 * timeline. The parser reports the actual presentation time of the decoded
 * keyframe, which is what the file name records.
 *
 * All writes go through one shared daemon thread with a bounded queue: the
 * decode thread only hands off the bitmap and never touches the disk. The
 * bitmap stays referenced until the JPEG is encoded, which is safe because
 * {@link ThumbnailStore} never recycles bitmaps. A full queue drops the
 * frame and logs it once; a stuck disk must not grow memory forever.
 */
public final class PreviewDiskCache {

    private static final String DIR_NAME = "seek_preview";
    /** Total budget across all sessions; evicted oldest-session first. */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
    /** How many persisted frames a reopening session loads back. */
    private static final int LOAD_FRAMES = 60;
    private static final int WRITE_QUEUE_CAPACITY = 64;
    private static final int JPEG_QUALITY = 70;
    private static final long TRIM_INTERVAL_MS = 30_000;

    private static final AtomicBoolean WRITE_DROP_LOGGED = new AtomicBoolean();
    private static final ThreadPoolExecutor WRITE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY),
            runnable -> {
                final Thread thread = new Thread(runnable, "seek-preview-disk-write");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            },
            new RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable runnable,
                                              ThreadPoolExecutor executor) {
                    if (WRITE_DROP_LOGGED.compareAndSet(false, true)) {
                        PreviewLog.d("SeekPreview: disk write queue full, dropping frames");
                    }
                }
            });

    private static final Object trimLock = new Object();
    private static volatile long lastTrimAt;

    private PreviewDiskCache() {
    }

    private static File baseDir() {
        return new File(ApplicationLoader.applicationContext.getCacheDir(), DIR_NAME);
    }

    public static File sessionDir(long dialogId, long messageId) {
        return new File(baseDir(), dialogId + "_" + messageId);
    }

    /**
     * Returns a writer for the session, or null when the disk cache is
     * disabled in settings.
     */
    public static Writer writerFor(long dialogId, long messageId,
                                   long documentId, long declaredSize) {
        if (!tw.nekomimi.nekogram.NekoConfig.videoPreviewDiskCache.Bool()) {
            return null;
        }
        return new Writer(sessionDir(dialogId, messageId), documentId, declaredSize);
    }

    private static long posMsOf(File file) {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        final int sep = name.lastIndexOf('_');
        if (dot < 0 || sep < 0 || sep > dot) {
            return -1;
        }
        try {
            return Long.parseLong(name.substring(sep + 1, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void maybeTrim() {
        final long now = System.currentTimeMillis();
        synchronized (trimLock) {
            if (now - lastTrimAt < TRIM_INTERVAL_MS) {
                return;
            }
            lastTrimAt = now;
        }
        try {
            final File base = baseDir();
            final File[] sessions = base.listFiles();
            if (sessions == null) {
                return;
            }
            long total = 0;
            for (File dir : sessions) {
                if (!dir.isDirectory()) {
                    continue;
                }
                final File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    total += file.length();
                }
            }
            if (total <= MAX_TOTAL_BYTES) {
                return;
            }
            Arrays.sort(sessions, Comparator.comparingLong(File::lastModified));
            for (File dir : sessions) {
                if (total <= MAX_TOTAL_BYTES) {
                    break;
                }
                if (!dir.isDirectory()) {
                    continue;
                }
                final File[] files = dir.listFiles();
                long size = 0;
                if (files != null) {
                    for (File file : files) {
                        size += file.length();
                    }
                }
                deleteRecursive(dir);
                total -= size;
                PreviewLog.d("SeekPreview: disk cache evicted "
                        + dir.getName() + " (" + size + " bytes)");
            }
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: disk trim failed", e);
        }
    }

    private static void deleteRecursive(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteRecursive(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /** Per-session writer; one instance per PreviewSession. */
    public static final class Writer {

        private final File dir;
        private final long documentId;
        private final long declaredSize;

        Writer(File dir, long documentId, long declaredSize) {
            this.dir = dir;
            this.documentId = documentId;
            this.declaredSize = declaredSize;
            this.dir.mkdirs();
        }

        /** Hands a decoded frame off to the shared disk thread. Never blocks. */
        public void enqueue(long timeMs, Bitmap bitmap) {
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            WRITE_EXECUTOR.execute(() -> writeOne(timeMs, bitmap));
        }

        /**
         * Reads the persisted frames of this session into the store on a
         * low-priority background thread. Frames land in the fine tier so the
         * memory budget can evict them, and the sixty nearest the playback
         * position are loaded first, so reopening a long video does not fill
         * the store with frames around minute zero.
         */
        public void loadInto(ThumbnailStore store, long desiredMs, Runnable onLoaded) {
            final File[] all = dir.listFiles();
            if (all == null || all.length == 0) {
                return;
            }
            final ArrayList<File> files = new ArrayList<>(all.length);
            for (File file : all) {
                if (posMsOf(file) >= 0) {
                    files.add(file);
                }
            }
            if (files.isEmpty()) {
                return;
            }
            final long focus = desiredMs >= 0 ? desiredMs : 0L;
            files.sort(Comparator.comparingLong(
                    file -> Math.abs(posMsOf(file) - focus)));
            final int count = Math.min(LOAD_FRAMES, files.size());
            final ArrayList<File> load = new ArrayList<>(files.subList(0, count));
            final Thread reader = new Thread(() -> {
                int loaded = 0;
                for (File file : load) {
                    final long posMs = posMsOf(file);
                    if (posMs < 0) {
                        continue;
                    }
                    final Bitmap bitmap = decodeForStore(file);
                    if (bitmap == null) {
                        file.delete();
                        continue;
                    }
                    store.put(new Thumbnail(bitmap, posMs, -1, Thumbnail.TIER_FINE),
                            focus);
                    loaded++;
                }
                PreviewLog.d("SeekPreview: disk cache loaded " + loaded
                        + " of " + all.length + " frames near=" + focus + "ms");
                // The store has no on-put listener, so notify the UI that new frames arrived.
                // Otherwise a finger held still while seeding finishes would never repaint.
                if (onLoaded != null) {
                    onLoaded.run();
                }
            }, "seek-preview-disk");
            reader.setPriority(Thread.MIN_PRIORITY);
            reader.start();
        }

        /**
         * RGB_565 halves the memory of the coarse layer. Legacy frames that
         * were stored larger than the preview box are downsampled while
         * decoding instead of being shrunk after a full-size allocation.
         */
        private static Bitmap decodeForStore(File file) {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                while (bounds.outWidth / (sample * 2) >= PreviewSession.THUMB_MAX_WIDTH
                        && bounds.outHeight / (sample * 2) >= PreviewSession.THUMB_MAX_HEIGHT) {
                    sample *= 2;
                }
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }

        private void writeOne(long timeMs, Bitmap bitmap) {
            try {
                final File out = new File(dir,
                        documentId + "_" + declaredSize + "_" + timeMs + ".jpg");
                if (out.exists()) {
                    return;
                }
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
                }
                maybeTrim();
            } catch (Throwable e) {
                PreviewLog.w("SeekPreview: disk write failed", e);
            }
        }
    }
}
