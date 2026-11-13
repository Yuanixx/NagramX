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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.MoovKeyframeParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns everything that belongs to one media: the decoder choice, the
 * background scheduler and the thumbnail store.
 *
 * Opening another video creates a new session, so no state (and no picture)
 * can leak from the previous one. The view only calls {@link #nearest(long)}
 * on the drag path; that is a binary search in an immutable array.
 */
public final class PreviewSession {

    public interface Listener {
        /** Called on the main thread when a new thumbnail enters the store. */
        void onThumbnailAvailable();
    }

    /** Preview pictures are 240x136 in landscape and 136x240 in portrait. */
    public static final int THUMB_MAX_WIDTH = 240;
    public static final int THUMB_MAX_HEIGHT = 136;

    private static final class Prepared {
        final FrameDecoder decoder;
        final ThumbnailScheduler.Plan plan;
        /** True when the file is still downloading and its index can grow. */
        final boolean refillable;
        /** Range probe for a sparse .temp, or null for a complete file. */
        final RangeAvailability availability;

        Prepared(FrameDecoder decoder, ThumbnailScheduler.Plan plan,
                 boolean refillable, RangeAvailability availability) {
            this.decoder = decoder;
            this.plan = plan;
            this.refillable = refillable;
            this.availability = availability;
        }
    }

    private final Context context;
    private final File file;
    private final long declaredSize;
    private final TLRPC.Document document;
    private final int account;
    private final Uri uri;
    private Listener listener;
    private final ThumbnailStore store = new ThumbnailStore();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean notifyPosted = new AtomicBoolean();
    private final Runnable notifyRunnable = () -> {
        notifyPosted.set(false);
        final Listener target = listener;
        if (!isStopped() && target != null) {
            target.onThumbnailAvailable();
        }
    };

    private volatile long desiredMs = -1;
    private volatile boolean dragging;
    private volatile boolean buffering;
    private volatile boolean stopped;
    private volatile long planDurationMs;
    private volatile ThumbnailScheduler scheduler;

    public PreviewSession(Context context, File file, long declaredSize,
                          TLRPC.Document document, int account, Listener listener) {
        this.context = context;
        this.file = file;
        this.declaredSize = declaredSize;
        this.document = document;
        this.account = account;
        this.uri = null;
        this.listener = listener;
        start();
    }

    public PreviewSession(Context context, Uri uri, Listener listener) {
        this.context = context;
        this.file = null;
        this.declaredSize = 0;
        this.document = null;
        this.account = -1;
        this.uri = uri;
        this.listener = listener;
        start();
    }

    private void start() {
        final Thread thread = new Thread(this::run, "seek-preview");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Binary search only; safe on the UI thread. A frame farther away than the
     * plan limit is refused: painting it under the requested timestamp would
     * show a wrong moment, and a bubble with just the time is the honest state.
     */
    public Thumbnail nearest(long timeMs) {
        final long duration = planDurationMs;
        final long limit = Math.max(30_000L, duration > 0 ? duration / 50L : 30_000L);
        return store.nearest(timeMs, limit);
    }

    public int thumbnailCount() {
        return store.size();
    }

    public void setDesiredPosition(long timeMs) {
        if (stopped) {
            return;
        }
        desiredMs = timeMs;
        final ThumbnailScheduler target = scheduler;
        if (target != null) {
            target.setDesiredPosition(timeMs);
        }
    }

    public void setDragging(boolean value) {
        if (stopped) {
            return;
        }
        dragging = value;
        final ThumbnailScheduler target = scheduler;
        if (target != null) {
            target.setDragging(value);
        }
    }

    public void setBuffering(boolean value) {
        if (stopped) {
            return;
        }
        buffering = value;
        final ThumbnailScheduler target = scheduler;
        if (target != null) {
            target.setBuffering(value);
        }
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        mainHandler.removeCallbacks(notifyRunnable);
        store.clear();
        final ThumbnailScheduler target = scheduler;
        if (target != null) {
            target.stop();
        }
    }

    private void run() {
        FrameDecoder decoder = null;
        try {
            final Prepared prepared = prepare();
            if (prepared == null || stopped) {
                if (prepared != null) {
                    prepared.decoder.close();
                }
                return;
            }
            decoder = prepared.decoder;
            planDurationMs = prepared.plan.durationMs;
            final ThumbnailScheduler worker = new ThumbnailScheduler(
                    prepared.plan, decoder, store,
                    THUMB_MAX_WIDTH, THUMB_MAX_HEIGHT, this::onThumbnail,
                    makeRefill(prepared));
            worker.setDesiredPosition(desiredMs);
            worker.setDragging(dragging);
            worker.setBuffering(buffering);
            scheduler = worker;
            if (stopped) {
                worker.stop();
                return;
            }
            PreviewLog.d("SeekPreview: session decoder=" + decoder.name()
                    + " duration=" + prepared.plan.durationMs
                    + " keyframes=" + (prepared.plan.keyframeTimesMs != null
                    ? prepared.plan.keyframeTimesMs.length : 0));
            worker.run();
        } catch (Throwable e) {
            PreviewLog.e("SeekPreview: session failed", e);
        } finally {
            scheduler = null;
            if (decoder != null) {
                decoder.close();
            }
            store.clear();
        }
    }

    /**
     * Re-reads the index of a file that is still downloading, so fragments
     * that arrive after the session started still produce thumbnails. Runs on
     * the scheduler thread only.
     */
    private ThumbnailScheduler.IndexRefill makeRefill(final Prepared prepared) {
        if (!prepared.refillable
                || !(prepared.decoder instanceof MediaCodecFrameDecoder)) {
            return null;
        }
        final MediaCodecFrameDecoder mediaDecoder = (MediaCodecFrameDecoder) prepared.decoder;
        final RangeAvailability availability = prepared.availability;
        final HashSet<Long> known = new HashSet<>();
        final long[] initial = prepared.plan.keyframeTimesMs;
        if (initial != null) {
            for (long time : initial) {
                known.add(time);
            }
        }
        return () -> {
            if (stopped || declaredSize <= 0) {
                return false;
            }
            try {
                final KeyframeSource.Result fresh = KeyframeSource.read(file);
                if (fresh == null || fresh.track == null
                        || fresh.track.keyframes.isEmpty()) {
                    return false;
                }
                final ArrayList<MoovKeyframeParser.Keyframe> extra = new ArrayList<>();
                for (MoovKeyframeParser.Keyframe keyframe : fresh.track.keyframes) {
                    if (keyframe != null && known.add(keyframe.timeMs)) {
                        extra.add(keyframe);
                    }
                }
                if (!extra.isEmpty()) {
                    mediaDecoder.appendKeyframes(extra);
                    final long[] times = new long[fresh.track.keyframes.size()];
                    for (int i = 0; i < times.length; i++) {
                        times[i] = fresh.track.keyframes.get(i).timeMs;
                    }
                    prepared.plan.updateKeyframeTimes(times);
                    PreviewLog.d("SeekPreview: index refill +" + extra.size()
                            + " keyframes=" + times.length);
                    return true;
                }
                // The index itself does not grow any more, but keyframes that
                // were skipped because their bytes had not arrived may have
                // become decodable since. Rebuild so they are retried; the
                // store filters out everything that already has a picture,
                // and the memory budget keeps every thumbnail, so this cannot
                // turn into a decode loop.
                if (availability != null) {
                    for (MoovKeyframeParser.Keyframe keyframe : fresh.track.keyframes) {
                        if (!store.containsTime(keyframe.timeMs)
                                && availability.isAvailable(keyframe.offset, keyframe.size)) {
                            return true;
                        }
                    }
                }
                return false;
            } catch (Throwable e) {
                PreviewLog.e("SeekPreview: index refill failed", e);
                return false;
            }
        };
    }

    private boolean isStopped() {
        return stopped;
    }

    private long documentDurationMs() {
        if (document == null || document.attributes == null) {
            return 0;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                final double seconds = ((TLRPC.TL_documentAttributeVideo) attribute).duration;
                if (seconds > 0) {
                    return (long) (seconds * 1000.0);
                }
            }
        }
        return 0;
    }

    private Prepared prepare() {
        final long documentDuration = documentDurationMs();
        if (file != null) {
            final boolean complete = RetrieverFrameDecoder.isFileComplete(
                    file, declaredSize, document, account);
            KeyframeSource.Result source = null;
            // Probe the bounded moov even during download for diagnostics. Both
            // a plain and a fragmented file carry a usable index here: a
            // fragmented .temp has its keyframes in the moof chain and the
            // codec configuration in moov, so the direct decoder can serve
            // whatever ranges the downloader already filled.
            if (declaredSize > 0) {
                source = KeyframeSource.read(file);
            }
            final boolean indexUsable = source != null && source.track != null
                    && source.track.isAvcOrHevc() && source.track.hasCodecConfig()
                    && !source.track.keyframes.isEmpty();
            if (indexUsable) {
                // A complete file needs no per-range check. A preallocated
                // .temp is sparse: bytes the downloader has not written read
                // as zeros, and zeros fed to a codec are what used to kill
                // whole sessions on long streaming videos. Ask the downloader
                // instead; a range that is still missing is skipped cheaply
                // and the index refill retries it once the bytes arrive, so
                // the codec path is taken even when nothing is cached yet.
                final RangeAvailability availability =
                        complete ? null : makeAvailability(document, account);
                try {
                    final MediaCodecFrameDecoder decoder =
                            new MediaCodecFrameDecoder(file, source.track, availability);
                    decoder.open();
                    if (stopped) {
                        decoder.close();
                        return null;
                    }
                    final long duration = Math.max(source.track.durationMs,
                            documentDuration);
                    PreviewLog.d("SeekPreview: MediaCodec path fragmented="
                            + source.fragmented
                            + " complete=" + complete
                            + " keyframes=" + source.track.keyframes.size()
                            + " decoder=" + decoder.name());
                    return new Prepared(decoder, buildPlan(duration, source.track),
                            !complete, availability);
                } catch (Throwable e) {
                    PreviewLog.e("SeekPreview: MediaCodec path failed, using retriever", e);
                }
            }
            if (!indexUsable || !complete) {
                PreviewLog.d("SeekPreview: retriever source complete=" + complete
                        + " file=" + file.getName()
                        + " len=" + file.length() + "/" + declaredSize
                        + " moov=" + (source != null)
                        + " fragmented=" + (source != null && source.fragmented)
                        + " codec=" + (source != null && source.track != null
                        ? source.track.mime : "none")
                        + " keyframes=" + (source != null && source.track != null
                        ? source.track.keyframes.size() : 0)
                        + " activeLoad=" + hasActiveLoad(document, account));
            }
            final RetrieverFrameDecoder decoder = new RetrieverFrameDecoder(
                    context, file, declaredSize, document, account);
            final long duration = decoder.prepare();
            if (stopped) {
                decoder.close();
                return null;
            }
            return new Prepared(decoder, new ThumbnailScheduler.Plan(
                    duration > 0 ? duration : documentDuration, null), false, null);
        }
        if (uri != null) {
            PreviewLog.d("SeekPreview: retriever source uri=" + uri
                    + " activeLoad=false");
            final RetrieverFrameDecoder decoder = new RetrieverFrameDecoder(context, uri);
            final long duration = decoder.prepare();
            if (stopped) {
                decoder.close();
                return null;
            }
            return new Prepared(decoder, new ThumbnailScheduler.Plan(duration, null), false, null);
        }
        return null;
    }

    /**
     * Asks the downloader about one exact byte range. Failure means "not
     * local"; a hole must never be read as zeros.
     */
    private static RangeAvailability makeAvailability(TLRPC.Document document, int account) {
        return (offset, size) -> {
            if (document == null || account < 0 || size <= 0) {
                return false;
            }
            try {
                return FileLoader.getInstance(account)
                        .getDownloadedLengthFast(document, offset, size) >= size;
            } catch (Throwable e) {
                return false;
            }
        };
    }

    private static boolean hasActiveLoad(TLRPC.Document document, int account) {
        if (document == null || account < 0) {
            return false;
        }
        try {
            return FileLoader.getInstance(account).getLoadOperation(document) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private static ThumbnailScheduler.Plan buildPlan(
            long durationMs, MoovKeyframeParser.TrackInfo track) {
        if (track != null && track.keyframes != null && !track.keyframes.isEmpty()) {
            final long[] times = new long[track.keyframes.size()];
            for (int i = 0; i < times.length; i++) {
                times[i] = track.keyframes.get(i).timeMs;
            }
            if (durationMs <= times[times.length - 1]) {
                durationMs = times[times.length - 1] + 1L;
            }
            return new ThumbnailScheduler.Plan(durationMs, times);
        }
        return new ThumbnailScheduler.Plan(durationMs, null);
    }

    private void onThumbnail(Thumbnail thumbnail) {
        if (stopped || listener == null) {
            return;
        }
        if (notifyPosted.compareAndSet(false, true)) {
            mainHandler.post(notifyRunnable);
        }
    }
}
