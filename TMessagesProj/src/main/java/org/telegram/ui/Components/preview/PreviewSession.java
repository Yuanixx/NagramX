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
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.FileLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.FileRangeFetcher;
import org.telegram.ui.Components.MoovKeyframeParser;

import tw.nekomimi.nekogram.NekoConfig;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Hard cap on bytes the network layer may fetch for one session. */
    private static final long REMOTE_BUDGET_BYTES = 8L * 1024 * 1024;
    /** Metered networks get half: previews must not eat someone's data plan. */
    private static final long REMOTE_BUDGET_METERED_BYTES = 4L * 1024 * 1024;
    /** One range fetch must never hold the scheduler longer than this. */
    private static final long REMOTE_FETCH_TIMEOUT_MS = 2500;
    /** How often the prefetch thread re-checks the drag position. */
    private static final long REMOTE_PREFETCH_WAIT_MS = 200;
    /** Aggressive tier: the buffer must cover the next 8 seconds of playback. */
    private static final long AGGRESSIVE_MIN_BUFFERED_AHEAD_MS = 8000;
    /** Aggressive tier: playback must have been stall-free for 3 seconds. */
    private static final long AGGRESSIVE_MIN_STABLE_MS = 3000;
    /** Bound on how long the fetch gate may hold a request before letting it through. */
    private static final long FETCH_GATE_MAX_WAIT_MS = 2000;
    /**
     * Frames farther than this from the finger are not shown by the bubble,
     * so the network layer does not fetch them while dragging either.
     */
    private static final long REMOTE_WINDOW_MIN_MS = 30_000L;
    /** Largest block the chain scan may request when a moof refuses to fit. */
    private static final int MAX_SCAN_FETCH_BYTES = 4 * 1024 * 1024;
    /** Consecutive real failures after which this session goes local-only. */
    private static final int REMOTE_MAX_FAILURES = 3;
    /** Server flood waits after which this session parks the network layer. */
    private static final int REMOTE_MAX_FLOODS = 2;
    /** How long the network layer stays dark after a flood streak. */
    private static final long REMOTE_FLOOD_COOLDOWN_MS = 60_000L;
    /** How long it stays dark after a plain failure streak. */
    private static final long REMOTE_FAILURE_COOLDOWN_MS = 30_000L;

    private static final class Prepared {
        final FrameDecoder decoder;
        final ThumbnailScheduler.Plan plan;
        /** True when the file is still downloading and its index can grow. */
        final boolean refillable;
        /** Range probe for a sparse .temp, or null for a complete file. */
        final RangeAvailability availability;
        /**
         * The moov probe of a fragmented streaming file, or null for a
         * complete file or the retriever path. The block scan needs its
         * moov boundary and track configuration to walk the chain.
         */
        final KeyframeSource.Result source;

        Prepared(FrameDecoder decoder, ThumbnailScheduler.Plan plan,
                 boolean refillable, RangeAvailability availability,
                 KeyframeSource.Result source) {
            this.decoder = decoder;
            this.plan = plan;
            this.refillable = refillable;
            this.availability = availability;
            this.source = source;
        }
    }

    private final Context context;
    private final File file;
    private final long declaredSize;
    private final TLRPC.Document document;
    private final int account;
    private final Uri uri;
    private final PreviewDiskCache.Writer diskSink;
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
    /** Playback health hints pushed by the UI poll; read by the fetch gate. */
    private volatile long bufferedAheadMs;
    private volatile long playbackStableMs;
    private volatile boolean stopped;
    private volatile long planDurationMs;
    private volatile ThumbnailScheduler scheduler;
    /** Set once in prepare() on the scheduler thread; null means local only. */
    private RangeSupplier remoteSupplier;
    /**
     * Wall-clock deadline of the network-layer cooldown; 0 means allowed.
     * A flood or a failure streak parks the layer instead of disabling it for
     * the whole session, so a free account can recover once the server calms
     * down.
     */
    private volatile long remoteDisabledUntilMs;
    /**
     * True once a cooldown was set. The next request after the cooldown acts
     * as the recovery probe: success clears the streak, failure parks again.
     */
    private volatile boolean remoteProbePending;
    private volatile int remoteFloodWaits;
    private volatile boolean remoteBudgetLogged;
    private long remoteBudget = REMOTE_BUDGET_BYTES;
    private final AtomicLong remoteBytesFetched = new AtomicLong();
    private final AtomicInteger remoteFetchCount = new AtomicInteger();
    private final AtomicInteger remotePrefetchCount = new AtomicInteger();
    private final AtomicInteger remoteCacheHits = new AtomicInteger();

    /**
     * Every keyframe the session knows about: the ones parsed from the local
     * file plus the ones the block scan discovered beyond the download
     * boundary. Sorted by time. Both the display rule and the prefetch
     * target are derived from this list.
     */
    private final ArrayList<MoovKeyframeParser.Keyframe> remoteKeyframes =
            new ArrayList<>();
    /**
     * Guards {@link #remoteKeyframes}. Held for microseconds only; never
     * waited on, so it cannot stall the UI thread that reads the nearest
     * keyframe during a drag.
     */
    private final Object keyframeLock = new Object();
    /** Guards the cache, the in-flight offsets and the failure counters. */
    private final Object remoteLock = new Object();
    /**
     * Bytes already fetched from the network, keyed by the offset they start
     * at. One request returns a whole 256KB block for a single-block sample,
     * so several neighbouring keyframes can be served from one entry.
     */
    private final LinkedHashMap<Long, byte[]> remoteCache = new LinkedHashMap<>();
    private long remoteCacheBytes;
    private final HashSet<Long> remoteInFlight = new HashSet<>();
    /** Guarded by remoteLock. */
    private int remoteFailures;
    private volatile FileRangeFetcher.FetchHandle prefetchHandle;
    private volatile long prefetchTimeMs = -1;
    private volatile FileRangeFetcher.FetchHandle decoderFetchHandle;
    private volatile long decoderFetchTimeMs = -1;
    /** Incremented on every drag-position change; stale requests give up. */
    private volatile int demandGeneration;
    private volatile boolean prefetchStopped;
    /**
     * Chain position the block scan continues at; -1 until the local walk
     * computed it once. Guarded by remoteLock.
     */
    private long remoteScanOffset = -1;
    /** True when the scan learned the chain cannot continue. Guarded by remoteLock. */
    private boolean remoteScanExhausted;
    /** True while a scan block is on the wire. Guarded by remoteLock. */
    private boolean remoteScanActive;
    /** Bytes to fetch for one scan step; grows when a moof refuses to fit. */
    private int remoteScanBytes = FileRangeFetcher.BLOCK;
    /** Upper bound for the local box walk that finds the scan start. */
    private static final int MAX_SCAN_BOXES = 4096;

    public PreviewSession(Context context, File file, long declaredSize,
                          TLRPC.Document document, int account, Listener listener,
                          PreviewDiskCache.Writer diskSink) {
        this.context = context;
        this.file = file;
        this.declaredSize = declaredSize;
        this.document = document;
        this.account = account;
        this.uri = null;
        this.listener = listener;
        this.diskSink = diskSink;
        start();
    }

    public PreviewSession(Context context, Uri uri, Listener listener,
                          PreviewDiskCache.Writer diskSink) {
        this.context = context;
        this.file = null;
        this.declaredSize = 0;
        this.document = null;
        this.account = -1;
        this.uri = uri;
        this.listener = listener;
        this.diskSink = diskSink;
        start();
    }

    private void start() {
        final Thread thread = new Thread(this::run, "seek-preview");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Binary search only; safe on the UI thread.
     *
     * How far the painted moment may sit from the requested time follows the
     * video itself: a frame is admitted when it is within the video's own
     * keyframe distance, not within a fixed number of seconds. A video with
     * a keyframe every two minutes therefore paints a frame that far away,
     * and one with a keyframe every second paints one second away. The old
     * fixed limit stays as the floor, so sessions without a measurable
     * index behave exactly as before. Painting anything farther would show
     * a wrong moment, so the bubble falls back to just the time.
     */
    public Thumbnail nearest(long timeMs) {
        final long duration = planDurationMs;
        long limit = Math.max(REMOTE_WINDOW_MIN_MS,
                duration > 0 ? duration / 50L : REMOTE_WINDOW_MIN_MS);
        final long gap = indexTypicalGapMs();
        if (gap > limit) {
            limit = gap;
        }
        return store.nearest(timeMs, limit);
    }

    public int thumbnailCount() {
        return store.size();
    }

    public void setDesiredPosition(long timeMs) {
        if (stopped) {
            return;
        }
        final long previous = desiredMs;
        desiredMs = timeMs;
        if (timeMs != previous) {
            demandGeneration++;
            // The request for the old position is not cancelled here. A drag
            // changes the desired position many times per second, while one
            // range request needs hundreds of milliseconds: cancelling on
            // every tick meant no request ever reached the server. The handle
            // carries a staleness probe instead (see FetchHandle.stale), which
            // drops the request only if it has not been sent yet; an in-flight
            // block is allowed to finish and its bytes land in the cache for
            // the next time the finger visits this position.
            synchronized (remoteLock) {
                remoteLock.notifyAll();
            }
        }
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
        synchronized (remoteLock) {
            remoteLock.notifyAll();
        }
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
        stopRemotePrefetch();
        store.clear();
        final ThumbnailScheduler target = scheduler;
        if (target != null) {
            target.stop();
        }
        PreviewLog.d("SeekPreview: remote summary fetches=" + remoteFetchCount.get()
                + " prefetched=" + remotePrefetchCount.get()
                + " cacheHits=" + remoteCacheHits.get()
                + " bytes=" + remoteBytesFetched.get()
                + " floods=" + remoteFloodWaits
                + " cooling=" + isRemoteCoolingDown());
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
                    makeRefill(prepared), diskSink);
            worker.setDesiredPosition(desiredMs);
            worker.setDragging(dragging);
            worker.setBuffering(buffering);
            scheduler = worker;
            if (stopped) {
                worker.stop();
                return;
            }
            if (diskSink != null) {
                diskSink.loadInto(store);
            }
            PreviewLog.d("SeekPreview: session decoder=" + decoder.name()
                    + " duration=" + prepared.plan.durationMs
                    + " keyframes=" + (prepared.plan.keyframeTimesMs != null
                    ? prepared.plan.keyframeTimesMs.length : 0)
                    + " remote=" + (remoteSupplier != null && !isRemoteCoolingDown()));
            startRemotePrefetch(prepared);
            worker.run();
        } catch (Throwable e) {
            PreviewLog.e("SeekPreview: session failed", e);
        } finally {
            scheduler = null;
            stopRemotePrefetch();
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
                // Frames the block scan registered beyond the download
                // boundary must not be counted as new here, and the plan
                // published below has to keep them.
                synchronized (keyframeLock) {
                    for (MoovKeyframeParser.Keyframe keyframe : remoteKeyframes) {
                        known.add(keyframe.timeMs);
                    }
                }
                final ArrayList<MoovKeyframeParser.Keyframe> extra = new ArrayList<>();
                for (MoovKeyframeParser.Keyframe keyframe : fresh.track.keyframes) {
                    if (keyframe != null && known.add(keyframe.timeMs)) {
                        extra.add(keyframe);
                    }
                }
                if (!extra.isEmpty()) {
                    mediaDecoder.appendKeyframes(extra);
                    addRemoteKeyframes(extra);
                    publishPlanTimes(prepared.plan);
                    PreviewLog.d("SeekPreview: index refill +" + extra.size()
                            + " keyframes=" + remoteKeyframeCount());
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
                // With the network layer alive a missing keyframe can still
                // arrive even though the file did not grow. Rebuild so it is
                // retried; the supplier disables itself after repeated
                // failures, budget exhaustion or flood waits, so this cannot
                // turn into an endless loop.
                if (remoteSupplier != null && !isRemoteCoolingDown()
                        && remoteBytesFetched.get() < remoteBudget) {
                    for (MoovKeyframeParser.Keyframe keyframe : fresh.track.keyframes) {
                        if (!store.containsTime(keyframe.timeMs)) {
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
                if (source == null && !stopped && !isRemoteCoolingDown()
                        && declaredSize > file.length()
                        && NekoConfig.videoPreviewTailMoov.Bool()) {
                    // The directory sits past the downloaded prefix (a tail
                    // moov file). Probe the last blocks once so the range
                    // fetcher has a keyframe table to work with; a failed
                    // probe keeps the retriever path.
                    source = TailMoovFetcher.fetch(document, account, declaredSize);
                }
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
                remoteBudget = complete ? 0 : computeRemoteBudget();
                final RangeSupplier supplier = complete ? null : makeSupplier();
                try {
                    final MediaCodecFrameDecoder decoder =
                            new MediaCodecFrameDecoder(file, source.track, availability, supplier);
                    decoder.open();
                    if (stopped) {
                        decoder.close();
                        return null;
                    }
                    remoteKeyframes.clear();
                    addRemoteKeyframes(source.track.keyframes);
                    final long duration = Math.max(source.track.durationMs,
                            documentDuration);
                    PreviewLog.d("SeekPreview: MediaCodec path fragmented="
                            + source.fragmented
                            + " complete=" + complete
                            + " keyframes=" + source.track.keyframes.size()
                            + " decoder=" + decoder.name()
                            + " remote=" + (supplier != null));
                    remoteSupplier = supplier;
                    return new Prepared(decoder, buildPlan(duration, source.track),
                            !complete, availability, source);
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
                    duration > 0 ? duration : documentDuration, null), false, null, null);
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
            return new Prepared(decoder, new ThumbnailScheduler.Plan(duration, null), false, null, null);
        }
        return null;
    }

    /**
     * Builds the network side of the preview. It is called on the scheduler
     * thread, inside the decoder, only after the local range probe said the
     * bytes are not on disk. Returning null is always safe: the frame is
     * skipped exactly as before.
     *
     * Budget, strike and flood counters live for one session, so opening
     * another video starts from a clean slate and a misbehaving network can
     * never keep the preview layer disabled forever.
     */
    private RangeSupplier makeSupplier() {
        if (document == null || account < 0 || stopped) {
            return null;
        }
        if (document.key != null && document.key.length > 0) {
            PreviewLog.d("SeekPreview: remote off (encrypted document)");
            return null;
        }
        if (!tw.nekomimi.nekogram.NekoConfig.videoPreviewNetworkFetch.Bool()) {
            PreviewLog.d("SeekPreview: remote off (setting)");
            return null;
        }
        if (remoteBudget <= 0) {
            PreviewLog.d("SeekPreview: remote off (no traffic allowed)");
            return null;
        }
        return (offset, size, timeMs) -> fetchRemoteSync(offset, (int) size, timeMs);
    }

    /**
     * Finds the one keyframe worth downloading ahead of the decoder: the
     * frame the bubble would paint right now. That is the keyframe the index
     * holds nearest to the finger — no fixed distance, the video's own frame
     * spacing decides. Null when that frame is already decoded, cached, in
     * flight or fully on disk.
     */
    private MoovKeyframeParser.Keyframe choosePrefetchTargetLocked(Prepared prepared) {
        final long desired = desiredMs;
        if (desired < 0) {
            return null;
        }
        final long nearestTime = nearestKeyframeTime(desired);
        if (nearestTime < 0) {
            return null;
        }
        final MoovKeyframeParser.Keyframe keyframe = keyframeAtTime(nearestTime);
        if (keyframe == null || keyframe.size <= 0) {
            return null;
        }
        if (store.containsTime(keyframe.timeMs)
                || remoteInFlight.contains(keyframe.offset)
                || cacheContainsLocked(keyframe.offset, keyframe.size)) {
            return null;
        }
        if (prepared.availability != null
                && prepared.availability.isAvailable(keyframe.offset, keyframe.size)) {
            // The downloader already has it; the decoder reads the file.
            return null;
        }
        return keyframe;
    }

    private void startRemotePrefetch(final Prepared prepared) {
        if (remoteSupplier == null || remoteBudget <= 0 || stopped || prefetchStopped
                || remoteKeyframeCount() == 0) {
            return;
        }
        final Thread thread = new Thread(() -> prefetchLoop(prepared), "seek-prefetch");
        thread.setDaemon(true);
        thread.start();
        PreviewLog.d("SeekPreview: prefetch thread started keyframes="
                + remoteKeyframeCount() + " budget=" + remoteBudget);
    }

    /**
     * Downloads keyframe bytes ahead of the decoder while the finger is down.
     * The decoder stays on its own thread, so it can decode cached frames while
     * the next range is already on the wire. Every fetched block stays in the
     * cache for later drags, which is what makes going back and forth cheap.
     *
     * When the finger sits in a hole of the index — farther from the nearest
     * known keyframe than the video's own frame distance — the thread also
     * walks the fragment chain beyond the download boundary: one fetched
     * block reveals the next fragment header and usually carries that
     * keyframe's picture with it. The finger lifting stops the walk.
     */
    private void prefetchLoop(final Prepared prepared) {
        while (!stopped && !prefetchStopped) {
            final MoovKeyframeParser.Keyframe target;
            final boolean wantScan;
            synchronized (remoteLock) {
                if (stopped || prefetchStopped) {
                    return;
                }
                if (!dragging || desiredMs < 0 || isRemoteCoolingDown()
                        || previewFetchBlocked()) {
                    waitRemoteLocked(REMOTE_PREFETCH_WAIT_MS);
                    continue;
                }
                if (decoderFetchHandle != null) {
                    // The frame the bubble needs right now wins the network
                    // slot; prefetch and scan only fill the time between
                    // decodes.
                    waitRemoteLocked(50L);
                    continue;
                }
                target = choosePrefetchTargetLocked(prepared);
                if (target != null) {
                    if (!budgetAllowsLocked(expectedFetchBytes(target.offset, target.size))) {
                        waitRemoteLocked(1000L);
                        continue;
                    }
                    if (!remoteInFlight.add(target.offset)) {
                        waitRemoteLocked(100L);
                        continue;
                    }
                    wantScan = false;
                } else {
                    wantScan = scanEligibleLocked(prepared);
                    if (!wantScan) {
                        waitRemoteLocked(REMOTE_PREFETCH_WAIT_MS);
                        continue;
                    }
                }
            }

            long scanOffset = -1;
            int scanLength = 0;
            if (wantScan) {
                // The local walk that finds the scan start does file I/O, so
                // it runs with no lock held; the result is re-validated here.
                final long frontier = ensureScanFrontier(prepared);
                synchronized (remoteLock) {
                    if (stopped || prefetchStopped) {
                        return;
                    }
                    if (frontier < 0 || !dragging || desiredMs < 0 || isRemoteCoolingDown()
                            || !scanEligibleLocked(prepared) || previewFetchBlocked()) {
                        continue;
                    }
                    if (!budgetAllowsLocked(expectedFetchBytes(frontier, remoteScanBytes))) {
                        waitRemoteLocked(1000L);
                        continue;
                    }
                    if (!remoteInFlight.add(frontier)) {
                        waitRemoteLocked(100L);
                        continue;
                    }
                    remoteScanActive = true;
                    scanOffset = frontier;
                    scanLength = remoteScanBytes;
                }
            }

            final long fetchOffset = target != null ? target.offset : scanOffset;
            final int fetchLength = target != null ? target.size : scanLength;
            final long fetchTimeMs = target != null ? target.timeMs : -1;
            final FileRangeFetcher.FetchHandle handle = new FileRangeFetcher.FetchHandle();
            if (target != null) {
                // A drag can move dozens of times per second. Instead of
                // cancelling the in-flight request, mark it stale: if it has
                // not been sent yet it is dropped without counting as a
                // failure, and if it is already on the wire it finishes and
                // its bytes serve the timeline later.
                final long targetTime = target.timeMs;
                handle.stale = () -> nearestKeyframeTime(desiredMs) != targetTime;
            }
            prefetchHandle = handle;
            prefetchTimeMs = fetchTimeMs;
            byte[] data = null;
            FileRangeFetcher.FetchResult result = null;
            final long started = SystemClock.elapsedRealtime();
            try {
                result = FileRangeFetcher.fetchSync(account, document,
                        fetchOffset, fetchLength, REMOTE_FETCH_TIMEOUT_MS, handle);
                data = result.data;
            } catch (Throwable e) {
                PreviewLog.w("SeekPreview: prefetch failed off=" + fetchOffset, e);
            } finally {
                synchronized (remoteLock) {
                    remoteInFlight.remove(fetchOffset);
                    if (target == null) {
                        remoteScanActive = false;
                    }
                    if (prefetchHandle == handle) {
                        prefetchHandle = null;
                        prefetchTimeMs = -1;
                    }
                    if (data != null && !stopped && !prefetchStopped) {
                        recordRemoteSuccessLocked();
                        remoteBytesFetched.addAndGet(data.length);
                        remoteFetchCount.incrementAndGet();
                        remotePrefetchCount.incrementAndGet();
                        if (target != null) {
                            cachePutLocked(target.offset, data);
                            PreviewLog.d("SeekPreview: prefetched keyframe t="
                                    + target.timeMs + " off=" + target.offset
                                    + " got=" + data.length
                                    + " total=" + remoteBytesFetched.get()
                                    + " ms=" + (SystemClock.elapsedRealtime() - started));
                        }
                    } else if (!handle.isCancelled() && !stopped && !prefetchStopped) {
                        recordRemoteFailureLocked(result);
                    }
                    remoteLock.notifyAll();
                }
            }
            if (target == null && data != null && !stopped && !prefetchStopped) {
                applyScanBlock(prepared, scanOffset, scanLength, data, started);
            }
        }
    }

    /**
     * True when the finger sits in a hole of the index: farther from the
     * nearest known keyframe than the video's own frame distance. Only a
     * fragmented streaming file has a chain to walk, and only a pressed
     * finger may spend traffic on it.
     */
    private boolean scanEligibleLocked(Prepared prepared) {
        if (remoteScanActive || remoteScanExhausted) {
            return false;
        }
        final KeyframeSource.Result source = prepared.source;
        if (source == null || !source.fragmented
                || source.track == null || source.track.timescale <= 0) {
            return false;
        }
        final long desired = desiredMs;
        if (desired < 0) {
            return false;
        }
        final long nearest = nearestKeyframeTime(desired);
        if (nearest < 0) {
            return false;
        }
        return Math.abs(desired - nearest) > indexTypicalGapMs();
    }

    /**
     * The chain position the scan continues at. Computed once from the local
     * file: the first top-level box the local walk could not finish marks
     * where what the download revealed ends.
     */
    private long ensureScanFrontier(Prepared prepared) {
        synchronized (remoteLock) {
            if (remoteScanExhausted) {
                return -1;
            }
            if (remoteScanOffset >= 0) {
                return remoteScanOffset;
            }
        }
        final long frontier = computeLocalFrontier(prepared);
        if (frontier == -2) {
            // Transient read failure; leave the frontier unknown and let the
            // next cycle retry the walk.
            return -1;
        }
        synchronized (remoteLock) {
            if (frontier < 0) {
                remoteScanExhausted = true;
                PreviewLog.d("SeekPreview: scan end (no chain left)");
                return -1;
            }
            if (remoteScanOffset < 0) {
                remoteScanOffset = frontier;
                PreviewLog.d("SeekPreview: scan start frontier=" + frontier
                        + " gap=" + indexTypicalGapMs());
            }
            return remoteScanOffset;
        }
    }

    /**
     * Walks the top-level boxes of the local file after the moov and returns
     * where the readable part ends. Returns -1 when the chain is over (a
     * random access index was reached) and -2 when the walk failed and may
     * be retried.
     */
    private long computeLocalFrontier(Prepared prepared) {
        final KeyframeSource.Result source = prepared.source;
        if (source == null || file == null) {
            return -1;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            final long length = raf.length();
            final long limit = declaredSize > 0 ? Math.min(length, declaredSize) : length;
            long position = source.moovOffset + source.moovSize;
            final byte[] header = new byte[16];
            int boxes = 0;
            while (position + 8 <= limit && boxes++ < MAX_SCAN_BOXES) {
                raf.seek(position);
                if (raf.read(header, 0, 8) < 8) {
                    return position;
                }
                long size = ((long) (header[0] & 0xff) << 24)
                        | ((long) (header[1] & 0xff) << 16)
                        | ((long) (header[2] & 0xff) << 8)
                        | (header[3] & 0xff);
                int headerSize = 8;
                if (size == 1) {
                    if (position + 16 > limit) {
                        return position;
                    }
                    raf.readFully(header, 8, 8);
                    size = ((long) (header[8] & 0xff) << 56)
                            | ((long) (header[9] & 0xff) << 48)
                            | ((long) (header[10] & 0xff) << 40)
                            | ((long) (header[11] & 0xff) << 32)
                            | ((long) (header[12] & 0xff) << 24)
                            | ((long) (header[13] & 0xff) << 16)
                            | ((long) (header[14] & 0xff) << 8)
                            | (header[15] & 0xff);
                    headerSize = 16;
                } else if (size == 0) {
                    // Zeros the downloader has not written yet: the chain
                    // continues remotely at this position.
                    return position;
                }
                if (size < headerSize || size > limit - position) {
                    // The box is cut by the download boundary.
                    return position;
                }
                final String type = new String(new char[]{
                        (char) (header[4] & 0xff), (char) (header[5] & 0xff),
                        (char) (header[6] & 0xff), (char) (header[7] & 0xff)});
                if ("mfra".equals(type)) {
                    // The random access index ends the chain.
                    return -1;
                }
                position += size;
            }
            return position;
        } catch (Throwable e) {
            return -2;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Turns one fetched block into chain progress: registers the keyframes
     * the moof headers inside it describe, hands the picture bytes to the
     * cache, and moves the scan to where the chain continues.
     */
    private void applyScanBlock(Prepared prepared, long blockOffset, int requested,
                                byte[] data, long startedMs) {
        final KeyframeSource.Result source = prepared.source;
        if (source == null || source.track == null
                || !(prepared.decoder instanceof MediaCodecFrameDecoder)) {
            return;
        }
        final RemoteMoofScanner.Fragment fragment = RemoteMoofScanner.scan(data,
                blockOffset, declaredSize > 0 ? declaredSize : source.fileSize,
                source.track.timescale, source.track.trackId);
        boolean registered = false;
        boolean sampleInside = false;
        for (MoovKeyframeParser.Keyframe keyframe : fragment.keyframes) {
            if (keyframe == null) {
                continue;
            }
            if (keyframe.offset >= blockOffset
                    && keyframe.offset + keyframe.size <= blockOffset + data.length) {
                sampleInside = true;
            }
            if (registerRemoteKeyframe(keyframe)) {
                registered = true;
                ((MediaCodecFrameDecoder) prepared.decoder).appendKeyframes(
                        Collections.singletonList(keyframe));
            }
        }
        if (sampleInside) {
            synchronized (remoteLock) {
                cachePutLocked(blockOffset, data);
            }
        }
        if (registered) {
            publishPlanTimes(prepared.plan);
            synchronized (remoteLock) {
                remoteLock.notifyAll();
            }
        }
        synchronized (remoteLock) {
            if (fragment.exhausted) {
                remoteScanExhausted = true;
            } else if (fragment.truncatedMoof) {
                if (remoteScanBytes < MAX_SCAN_FETCH_BYTES) {
                    remoteScanBytes = Math.min(MAX_SCAN_FETCH_BYTES, remoteScanBytes * 4);
                } else {
                    remoteScanExhausted = true;
                }
            } else if (fragment.nextOffset > blockOffset) {
                remoteScanBytes = FileRangeFetcher.BLOCK;
                remoteScanOffset = fragment.nextOffset;
                final long end = declaredSize > 0 ? declaredSize : source.fileSize;
                if (remoteScanOffset >= end) {
                    // The chain reached the declared end of the file.
                    remoteScanExhausted = true;
                }
            } else {
                // The scan made no progress; do not spin on the same block.
                remoteScanExhausted = true;
            }
        }
        PreviewLog.d("SeekPreview: scan step frontier=" + blockOffset
                + " got=" + data.length + " want=" + requested
                + " found=" + fragment.keyframes.size()
                + " next=" + fragment.nextOffset
                + " exhausted=" + fragment.exhausted
                + " truncated=" + fragment.truncatedMoof
                + " registered=" + registered
                + " inside=" + sampleInside
                + " keyframes=" + remoteKeyframeCount()
                + " total=" + remoteBytesFetched.get()
                + " ms=" + (SystemClock.elapsedRealtime() - startedMs));
        if (remoteScanExhausted && (fragment.exhausted || fragment.truncatedMoof
                || fragment.nextOffset <= blockOffset)) {
            PreviewLog.d("SeekPreview: scan end frontier=" + blockOffset);
        }
    }

    private void waitRemoteLocked(long timeoutMs) {
        try {
            remoteLock.wait(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            prefetchStopped = true;
        }
    }

    /**
     * Synchronous path used by the decoder. It first reuses anything the
     * prefetch thread already downloaded; when that exact sample is still in
     * flight it waits for it instead of issuing a duplicate request.
     */
    private byte[] fetchRemoteSync(long offset, int size, long timeMs) {
        if (size <= 0 || offset < 0 || stopped || prefetchStopped || isRemoteCoolingDown()
                || previewFetchBlocked()) {
            return null;
        }
        if (!withinRemoteWindow(timeMs)) {
            return null;
        }
        final byte[] cached = cacheLookup(offset, size);
        if (cached != null) {
            remoteCacheHits.incrementAndGet();
            return cached;
        }

        final int generation = demandGeneration;
        FileRangeFetcher.FetchHandle handle = null;
        synchronized (remoteLock) {
            if (remoteInFlight.contains(offset)) {
                // The prefetch thread is already downloading this exact
                // keyframe. Wait for its result instead of paying twice.
                final long deadline = SystemClock.elapsedRealtime() + REMOTE_FETCH_TIMEOUT_MS;
                while (!stopped && !prefetchStopped && !isRemoteCoolingDown()
                        && generation == demandGeneration
                        && withinRemoteWindow(timeMs)) {
                    final byte[] ready = cacheLookupLocked(offset, size);
                    if (ready != null) {
                        remoteCacheHits.incrementAndGet();
                        return ready;
                    }
                    if (!remoteInFlight.contains(offset)) {
                        break;
                    }
                    final long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0L) {
                        return null;
                    }
                    try {
                        remoteLock.wait(Math.min(50L, remaining));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                if (generation != demandGeneration || !withinRemoteWindow(timeMs)) {
                    return null;
                }
                if (!remoteInFlight.contains(offset)) {
                    final byte[] ready = cacheLookupLocked(offset, size);
                    if (ready != null) {
                        remoteCacheHits.incrementAndGet();
                        return ready;
                    }
                } else {
                    return null;
                }
            }
            if (isRemoteCoolingDown() || stopped || prefetchStopped
                    || previewFetchBlocked()) {
                return null;
            }
            if (!budgetAllowsLocked(expectedFetchBytes(offset, size))) {
                return null;
            }
            if (!remoteInFlight.add(offset)) {
                return null;
            }
            handle = new FileRangeFetcher.FetchHandle();
            // Drop the request only if it has not been sent yet; a sent block
            // finishes and its bytes stay in the cache for the next visit.
            handle.stale = () -> generation != demandGeneration;
            decoderFetchTimeMs = timeMs;
            decoderFetchHandle = handle;
        }

        byte[] data = null;
        FileRangeFetcher.FetchResult result = null;
        final long started = SystemClock.elapsedRealtime();
        try {
            result = FileRangeFetcher.fetchSync(account, document,
                    offset, size, REMOTE_FETCH_TIMEOUT_MS, handle);
            data = result.data;
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: remote keyframe failed off=" + offset
                    + " size=" + size, e);
        } finally {
            synchronized (remoteLock) {
                remoteInFlight.remove(offset);
                if (decoderFetchHandle == handle) {
                    decoderFetchHandle = null;
                    decoderFetchTimeMs = -1;
                }
                if (data != null && !stopped && !prefetchStopped) {
                    cachePutLocked(offset, data);
                    recordRemoteSuccessLocked();
                    remoteBytesFetched.addAndGet(data.length);
                    remoteFetchCount.incrementAndGet();
                    PreviewLog.d("SeekPreview: remote keyframe off=" + offset
                            + " size=" + size + " got=" + data.length
                            + " total=" + remoteBytesFetched.get()
                            + " ms=" + (SystemClock.elapsedRealtime() - started));
                } else if (!handle.isCancelled() && !stopped) {
                    recordRemoteFailureLocked(result);
                }
                remoteLock.notifyAll();
            }
        }
        return data;
    }

    /**
     * Guarded by remoteLock.
     *
     * Classification comes from the result status, never from its text: a
     * cancelled request and a local cooldown are not failures, while a timeout
     * and a malformed response are.
     */
    private void recordRemoteFailureLocked(FileRangeFetcher.FetchResult result) {
        final long now = System.currentTimeMillis();
        if (result != null && result.status == FileRangeFetcher.FetchResult.Status.FLOOD) {
            remoteFloodWaits++;
            if (remoteFloodWaits >= REMOTE_MAX_FLOODS || remoteProbePending) {
                // Second flood, or the recovery probe came back flooded:
                // park the layer and wait for a clean probe later.
                remoteDisabledUntilMs = now + REMOTE_FLOOD_COOLDOWN_MS;
                remoteFloodWaits = 0;
                remoteProbePending = true;
                PreviewLog.d("SeekPreview: remote cooldown "
                        + REMOTE_FLOOD_COOLDOWN_MS + "ms after flood");
            }
        } else if (result == null || result.countsAsFailure) {
            remoteFailures++;
            if (remoteFailures >= REMOTE_MAX_FAILURES || remoteProbePending) {
                remoteDisabledUntilMs = now + REMOTE_FAILURE_COOLDOWN_MS;
                remoteFailures = 0;
                remoteProbePending = true;
                PreviewLog.d("SeekPreview: remote cooldown "
                        + REMOTE_FAILURE_COOLDOWN_MS + "ms after failures");
            }
        }
    }

    /** Guarded by remoteLock. Clears the streak and confirms a recovery probe. */
    private void recordRemoteSuccessLocked() {
        if (remoteProbePending) {
            remoteProbePending = false;
            PreviewLog.d("SeekPreview: remote probe ok");
        }
        remoteFloodWaits = 0;
        remoteFailures = 0;
    }

    /** True while the network layer is parked after a flood or failure streak. */
    private boolean isRemoteCoolingDown() {
        final long until = remoteDisabledUntilMs;
        return until != 0L && System.currentTimeMillis() < until;
    }

    /**
     * True while the frame is the one the bubble would paint: the keyframe
     * the index holds nearest to the finger. With no index the old fixed
     * distance rule applies.
     */
    private boolean withinRemoteWindow(long timeMs) {
        if (!dragging) {
            return true;
        }
        final long desired = desiredMs;
        if (desired < 0) {
            return true;
        }
        final long nearest = nearestKeyframeTime(desired);
        if (nearest < 0) {
            return Math.abs(timeMs - desired) <= remoteWindowLimit();
        }
        return timeMs == nearest;
    }

    private long remoteWindowLimit() {
        final long duration = planDurationMs;
        return Math.max(REMOTE_WINDOW_MIN_MS,
                duration > 0 ? duration / 50L : REMOTE_WINDOW_MIN_MS);
    }

    /**
     * Presentation time of the keyframe the bubble would show for this
     * position, or -1 when no index exists. Used by the display rule, the
     * prefetch target and the staleness checks.
     */
    private long nearestKeyframeTime(long timeMs) {
        synchronized (keyframeLock) {
            final int n = remoteKeyframes.size();
            if (n == 0) {
                return -1;
            }
            int lo = 0;
            int hi = n - 1;
            while (lo < hi) {
                final int mid = (lo + hi) >>> 1;
                if (remoteKeyframes.get(mid).timeMs < timeMs) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            if (lo > 0) {
                final long before = timeMs - remoteKeyframes.get(lo - 1).timeMs;
                final long after = remoteKeyframes.get(lo).timeMs - timeMs;
                if (before <= after) {
                    return remoteKeyframes.get(lo - 1).timeMs;
                }
            }
            return remoteKeyframes.get(lo).timeMs;
        }
    }

    /** The keyframe with exactly this presentation time, or null. */
    private MoovKeyframeParser.Keyframe keyframeAtTime(long timeMs) {
        synchronized (keyframeLock) {
            int lo = 0;
            int hi = remoteKeyframes.size() - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                final long t = remoteKeyframes.get(mid).timeMs;
                if (t < timeMs) {
                    lo = mid + 1;
                } else if (t > timeMs) {
                    hi = mid - 1;
                } else {
                    return remoteKeyframes.get(mid);
                }
            }
            return null;
        }
    }

    /** Sorted insert keyed by time; true when the frame is new to the session. */
    private boolean registerRemoteKeyframe(MoovKeyframeParser.Keyframe keyframe) {
        if (keyframe == null || keyframe.size <= 0) {
            return false;
        }
        synchronized (keyframeLock) {
            final int n = remoteKeyframes.size();
            int index = 0;
            while (index < n && remoteKeyframes.get(index).timeMs < keyframe.timeMs) {
                index++;
            }
            if (index < n && remoteKeyframes.get(index).timeMs == keyframe.timeMs) {
                return false;
            }
            remoteKeyframes.add(index, keyframe);
            return true;
        }
    }

    private void addRemoteKeyframes(List<MoovKeyframeParser.Keyframe> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (MoovKeyframeParser.Keyframe keyframe : list) {
            registerRemoteKeyframe(keyframe);
        }
    }

    private int remoteKeyframeCount() {
        synchronized (keyframeLock) {
            return remoteKeyframes.size();
        }
    }

    /**
     * The video's own keyframe distance, taken as the median gap of the
     * known index. This is what decides whether the finger sits in a hole
     * the scan must fill: the rule follows the file, not a fixed number.
     */
    private long indexTypicalGapMs() {
        synchronized (keyframeLock) {
            final int n = remoteKeyframes.size();
            if (n < 2) {
                return Math.max(REMOTE_WINDOW_MIN_MS, planDurationMs / 50L);
            }
            final long[] gaps = new long[n - 1];
            for (int i = 1; i < n; i++) {
                gaps[i - 1] = remoteKeyframes.get(i).timeMs
                        - remoteKeyframes.get(i - 1).timeMs;
            }
            Arrays.sort(gaps);
            return Math.max(1000L, gaps[gaps.length / 2]);
        }
    }

    /**
     * Rebuilds the plan's keyframe times as the union of what the plan
     * already holds and every keyframe the session knows. Both the file
     * refill and the block scan publish through here, so neither can erase
     * the other's frames.
     */
    private void publishPlanTimes(ThumbnailScheduler.Plan plan) {
        if (plan == null) {
            return;
        }
        final long[] current = plan.keyframeTimesMs;
        final ArrayList<Long> merged = new ArrayList<>();
        synchronized (keyframeLock) {
            for (MoovKeyframeParser.Keyframe keyframe : remoteKeyframes) {
                merged.add(keyframe.timeMs);
            }
        }
        if (current != null && current.length > 0) {
            final HashSet<Long> seen = new HashSet<>(merged);
            for (long time : current) {
                if (seen.add(time)) {
                    merged.add(time);
                }
            }
        }
        Collections.sort(merged);
        final long[] out = new long[merged.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = merged.get(i);
        }
        plan.updateKeyframeTimes(out);
    }

    /**
     * One range request returns the keyframe plus the rest of its 256KB block
     * for single-block samples. Counting the whole block keeps the budget
     * honest even when keyframes are small.
     */
    private static long expectedFetchBytes(long offset, long size) {
        final long aligned = (offset / FileRangeFetcher.BLOCK) * FileRangeFetcher.BLOCK;
        final long delta = offset - aligned;
        final long blocks = (delta + size + FileRangeFetcher.BLOCK - 1L)
                / FileRangeFetcher.BLOCK;
        return blocks * FileRangeFetcher.BLOCK;
    }

    /** Guarded by remoteLock. */
    private boolean budgetAllowsLocked(long expected) {
        if (remoteBytesFetched.get() + expected > remoteBudget) {
            if (!remoteBudgetLogged) {
                remoteBudgetLogged = true;
                PreviewLog.d("SeekPreview: remote budget reached bytes="
                        + remoteBytesFetched.get() + " budget=" + remoteBudget);
            }
            return false;
        }
        return true;
    }

    private byte[] cacheLookup(long offset, int size) {
        synchronized (remoteLock) {
            return cacheLookupLocked(offset, size);
        }
    }

    /** Guarded by remoteLock. Returns a copy the decoder may keep. */
    private byte[] cacheLookupLocked(long offset, int size) {
        byte[] best = null;
        long bestStart = -1;
        for (Map.Entry<Long, byte[]> entry : remoteCache.entrySet()) {
            final long start = entry.getKey();
            final byte[] data = entry.getValue();
            if (start <= offset && start > bestStart
                    && offset + size <= start + data.length) {
                best = data;
                bestStart = start;
            }
        }
        if (best == null) {
            return null;
        }
        final byte[] out = new byte[size];
        System.arraycopy(best, (int) (offset - bestStart), out, 0, size);
        return out;
    }

    /** Guarded by remoteLock. */
    private boolean cacheContainsLocked(long offset, int size) {
        for (Map.Entry<Long, byte[]> entry : remoteCache.entrySet()) {
            final long start = entry.getKey();
            final byte[] data = entry.getValue();
            if (start <= offset && offset + size <= start + data.length) {
                return true;
            }
        }
        return false;
    }

    /** Guarded by remoteLock. */
    private void cachePutLocked(long offset, byte[] data) {
        if (data == null || data.length == 0 || remoteBudget <= 0) {
            return;
        }
        final byte[] existing = remoteCache.put(offset, data);
        if (existing != null) {
            remoteCacheBytes -= existing.length;
        }
        remoteCacheBytes += data.length;
        while (remoteCacheBytes > remoteBudget && !remoteCache.isEmpty()) {
            final Iterator<Map.Entry<Long, byte[]>> iterator = remoteCache.entrySet().iterator();
            final Map.Entry<Long, byte[]> eldest = iterator.next();
            remoteCacheBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    private void stopRemotePrefetch() {
        prefetchStopped = true;
        final FileRangeFetcher.FetchHandle sync = decoderFetchHandle;
        if (sync != null) {
            sync.cancel();
        }
        decoderFetchTimeMs = -1;
        final FileRangeFetcher.FetchHandle ahead = prefetchHandle;
        if (ahead != null) {
            ahead.cancel();
        }
        prefetchTimeMs = -1;
        synchronized (remoteLock) {
            remoteCache.clear();
            remoteCacheBytes = 0;
            remoteInFlight.clear();
            remoteScanActive = false;
            remoteLock.notifyAll();
        }
    }

    /**
     * Traffic the network layer may spend on this session. A metered network
     * gets half and roaming gets nothing: previews are a convenience and must
     * never turn into a background download on someone else's data plan. Any
     * failure to read the network state falls back to the full budget, which
     * is still bounded.
     */
    @SuppressWarnings("deprecation")
    private long computeRemoteBudget() {
        try {
            final ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return REMOTE_BUDGET_BYTES;
            }
            final NetworkInfo info = manager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return REMOTE_BUDGET_BYTES;
            }
            if (info.isRoaming()) {
                PreviewLog.d("SeekPreview: remote off (roaming)");
                return 0;
            }
            return manager.isActiveNetworkMetered()
                    ? REMOTE_BUDGET_METERED_BYTES : REMOTE_BUDGET_BYTES;
        } catch (Throwable e) {
            return REMOTE_BUDGET_BYTES;
        }
    }

    /** Called from the UI poll; the aggressiveness gate reads these hints. */
    public void setPlaybackHint(long bufferedAhead, long stableMs) {
        bufferedAheadMs = bufferedAhead;
        playbackStableMs = stableMs;
    }

    /**
     * Aggressiveness gate on top of the rate/flood/budget limits already
     * enforced by FileRangeFetcher. 0 = the network layer stays dark for this
     * session, 1 = fetches pause while playback buffers, 2 = a healthy buffer
     * lets fetches continue.
     */
    private boolean previewFetchBlocked() {
        final int mode = NekoConfig.videoPreviewFetchAggressiveness.Int();
        if (mode == 0) {
            return true;
        }
        if (!buffering) {
            return false;
        }
        if (mode == 1) {
            return true;
        }
        return bufferedAheadMs < AGGRESSIVE_MIN_BUFFERED_AHEAD_MS
                || playbackStableMs < AGGRESSIVE_MIN_STABLE_MS
                || FileRangeFetcher.isCoolingDown();
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
