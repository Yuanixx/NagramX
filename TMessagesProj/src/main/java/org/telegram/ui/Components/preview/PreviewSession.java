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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
    /**
     * Frames farther than this from the finger are not shown by the bubble,
     * so the network layer does not fetch them while dragging either.
     */
    private static final long REMOTE_WINDOW_MIN_MS = 30_000L;
    /** Consecutive real failures after which this session goes local-only. */
    private static final int REMOTE_MAX_FAILURES = 3;
    /** Server flood waits after which this session goes local-only. */
    private static final int REMOTE_MAX_FLOODS = 2;

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
    /** Set once in prepare() on the scheduler thread; null means local only. */
    private RangeSupplier remoteSupplier;
    private volatile boolean remoteDisabled;
    private volatile int remoteFloodWaits;
    private volatile boolean remoteBudgetLogged;
    private long remoteBudget = REMOTE_BUDGET_BYTES;
    private final AtomicLong remoteBytesFetched = new AtomicLong();
    private final AtomicInteger remoteFetchCount = new AtomicInteger();
    private final AtomicInteger remotePrefetchCount = new AtomicInteger();
    private final AtomicInteger remoteCacheHits = new AtomicInteger();

    /**
     * Keyframes the prefetch thread may download. A copy is kept because the
     * decoder's list is appended on the scheduler thread; this one is safe to
     * iterate from the network thread.
     */
    private final CopyOnWriteArrayList<MoovKeyframeParser.Keyframe> remoteKeyframes =
            new CopyOnWriteArrayList<>();
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
        final long previous = desiredMs;
        desiredMs = timeMs;
        if (timeMs != previous) {
            demandGeneration++;
            // A request for the old position must not hold the decoder for
            // its full timeout: the finger already moved somewhere else. Keep
            // it while the nearest keyframe is still the one being fetched,
            // otherwise a slow drag would cancel its own request every tick.
            final FileRangeFetcher.FetchHandle sync = decoderFetchHandle;
            final long syncTime = decoderFetchTimeMs;
            if (sync != null && timeMs >= 0 && syncTime >= 0) {
                final long nearest = nearestKeyframeTime(timeMs);
                final boolean stale = Math.abs(syncTime - timeMs) > remoteWindowLimit()
                        || (nearest >= 0 && nearest != syncTime);
                if (stale) {
                    sync.cancel();
                }
            }
            final FileRangeFetcher.FetchHandle ahead = prefetchHandle;
            final long aheadTime = prefetchTimeMs;
            if (ahead != null && timeMs >= 0 && aheadTime >= 0) {
                final long nearest = nearestKeyframeTime(timeMs);
                final boolean stale = Math.abs(aheadTime - timeMs) > remoteWindowLimit()
                        || (nearest >= 0 && nearest != aheadTime);
                if (stale) {
                    // The finger wants another frame now; let the prefetch
                    // thread pick the new nearest instead of finishing this.
                    ahead.cancel();
                }
            }
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
                + " disabled=" + remoteDisabled);
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
                    ? prepared.plan.keyframeTimesMs.length : 0)
                    + " remote=" + (remoteSupplier != null && !remoteDisabled));
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
                final ArrayList<MoovKeyframeParser.Keyframe> extra = new ArrayList<>();
                for (MoovKeyframeParser.Keyframe keyframe : fresh.track.keyframes) {
                    if (keyframe != null && known.add(keyframe.timeMs)) {
                        extra.add(keyframe);
                    }
                }
                if (!extra.isEmpty()) {
                    mediaDecoder.appendKeyframes(extra);
                    remoteKeyframes.addAll(extra);
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
                // With the network layer alive a missing keyframe can still
                // arrive even though the file did not grow. Rebuild so it is
                // retried; the supplier disables itself after repeated
                // failures, budget exhaustion or flood waits, so this cannot
                // turn into an endless loop.
                if (remoteSupplier != null && !remoteDisabled
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
                    remoteKeyframes.addAll(source.track.keyframes);
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
     * Finds one keyframe worth downloading ahead of the decoder. The closest
     * missing frame to the finger wins; frames the bubble would never show are
     * ignored. This is intentionally not a fixed count: the thread keeps
     * picking the next useful frame while the finger is down, and stops when
     * the window is covered, the budget is spent or the finger leaves.
     */
    private MoovKeyframeParser.Keyframe choosePrefetchTargetLocked(Prepared prepared) {
        final long desired = desiredMs;
        if (desired < 0) {
            return null;
        }
        final long window = remoteWindowLimit();
        MoovKeyframeParser.Keyframe best = null;
        long bestDistance = Long.MAX_VALUE;
        for (MoovKeyframeParser.Keyframe keyframe : remoteKeyframes) {
            if (keyframe == null || keyframe.size <= 0) {
                continue;
            }
            final long distance = Math.abs(keyframe.timeMs - desired);
            if (distance > window || distance >= bestDistance) {
                continue;
            }
            if (store.containsTime(keyframe.timeMs)
                    || remoteInFlight.contains(keyframe.offset)
                    || cacheContainsLocked(keyframe.offset, keyframe.size)) {
                continue;
            }
            if (prepared.availability != null
                    && prepared.availability.isAvailable(keyframe.offset, keyframe.size)) {
                // The downloader already has it; the decoder reads the file.
                continue;
            }
            best = keyframe;
            bestDistance = distance;
        }
        return best;
    }

    private void startRemotePrefetch(final Prepared prepared) {
        if (remoteSupplier == null || remoteBudget <= 0 || stopped || prefetchStopped
                || remoteKeyframes.isEmpty()) {
            return;
        }
        final Thread thread = new Thread(() -> prefetchLoop(prepared), "seek-prefetch");
        thread.setDaemon(true);
        thread.start();
        PreviewLog.d("SeekPreview: prefetch thread started keyframes="
                + remoteKeyframes.size() + " budget=" + remoteBudget);
    }

    /**
     * Downloads keyframe bytes ahead of the decoder while the finger is down.
     * The decoder stays on its own thread, so it can decode cached frames while
     * the next range is already on the wire. Every fetched block stays in the
     * cache for later drags, which is what makes going back and forth cheap.
     */
    private void prefetchLoop(final Prepared prepared) {
        while (!stopped && !prefetchStopped) {
            final MoovKeyframeParser.Keyframe target;
            synchronized (remoteLock) {
                if (stopped || prefetchStopped) {
                    return;
                }
                if (!dragging || desiredMs < 0 || remoteDisabled) {
                    waitRemoteLocked(REMOTE_PREFETCH_WAIT_MS);
                    continue;
                }
                target = choosePrefetchTargetLocked(prepared);
                if (target == null) {
                    waitRemoteLocked(REMOTE_PREFETCH_WAIT_MS);
                    continue;
                }
                if (decoderFetchHandle != null) {
                    // The frame the bubble needs right now wins the network
                    // slot; prefetch only fills the time between decodes.
                    waitRemoteLocked(50L);
                    continue;
                }
                if (!budgetAllowsLocked(expectedFetchBytes(target.offset, target.size))) {
                    waitRemoteLocked(1000L);
                    continue;
                }
                if (!remoteInFlight.add(target.offset)) {
                    waitRemoteLocked(100L);
                    continue;
                }
            }

            final FileRangeFetcher.FetchHandle handle = new FileRangeFetcher.FetchHandle();
            prefetchHandle = handle;
            prefetchTimeMs = target.timeMs;
            byte[] data = null;
            String failure = null;
            final long started = SystemClock.elapsedRealtime();
            try {
                data = FileRangeFetcher.fetchSync(account, document,
                        target.offset, target.size, REMOTE_FETCH_TIMEOUT_MS, handle);
                failure = FileRangeFetcher.failureText();
            } catch (Throwable e) {
                PreviewLog.w("SeekPreview: prefetch failed off=" + target.offset, e);
            } finally {
                synchronized (remoteLock) {
                    remoteInFlight.remove(target.offset);
                    if (prefetchHandle == handle) {
                        prefetchHandle = null;
                        prefetchTimeMs = -1;
                    }
                    if (data != null && !stopped && !prefetchStopped) {
                        cachePutLocked(target.offset, data);
                        remoteFailures = 0;
                        remoteBytesFetched.addAndGet(data.length);
                        remoteFetchCount.incrementAndGet();
                        remotePrefetchCount.incrementAndGet();
                        PreviewLog.d("SeekPreview: prefetched keyframe t="
                                + target.timeMs + " off=" + target.offset
                                + " got=" + data.length
                                + " total=" + remoteBytesFetched.get()
                                + " ms=" + (SystemClock.elapsedRealtime() - started));
                    } else if (!handle.isCancelled() && !stopped && !prefetchStopped) {
                        recordRemoteFailureLocked(failure);
                    }
                    remoteLock.notifyAll();
                }
            }
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
        if (size <= 0 || offset < 0 || stopped || prefetchStopped || remoteDisabled) {
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
                while (!stopped && !prefetchStopped && !remoteDisabled
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
            if (remoteDisabled || stopped || prefetchStopped) {
                return null;
            }
            if (!budgetAllowsLocked(expectedFetchBytes(offset, size))) {
                return null;
            }
            if (!remoteInFlight.add(offset)) {
                return null;
            }
            handle = new FileRangeFetcher.FetchHandle();
            decoderFetchTimeMs = timeMs;
            decoderFetchHandle = handle;
        }

        byte[] data = null;
        String failure = null;
        final long started = SystemClock.elapsedRealtime();
        try {
            data = FileRangeFetcher.fetchSync(account, document,
                    offset, size, REMOTE_FETCH_TIMEOUT_MS, handle);
            failure = FileRangeFetcher.failureText();
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
                    remoteFailures = 0;
                    remoteBytesFetched.addAndGet(data.length);
                    remoteFetchCount.incrementAndGet();
                    PreviewLog.d("SeekPreview: remote keyframe off=" + offset
                            + " size=" + size + " got=" + data.length
                            + " total=" + remoteBytesFetched.get()
                            + " ms=" + (SystemClock.elapsedRealtime() - started));
                } else if (!handle.isCancelled() && !stopped) {
                    recordRemoteFailureLocked(failure);
                }
                remoteLock.notifyAll();
            }
        }
        return data;
    }

    /** Guarded by remoteLock. */
    private void recordRemoteFailureLocked(String text) {
        if (FileRangeFetcher.isFloodWait(text)) {
            remoteFloodWaits++;
            if (remoteFloodWaits >= REMOTE_MAX_FLOODS) {
                remoteDisabled = true;
                PreviewLog.d("SeekPreview: remote off after flood wait #"
                        + remoteFloodWaits);
            }
        } else if (text == null || (!text.contains("cancel")
                && !text.contains("interrupt"))) {
            remoteFailures++;
            if (remoteFailures >= REMOTE_MAX_FAILURES) {
                remoteDisabled = true;
                PreviewLog.d("SeekPreview: remote off after "
                        + remoteFailures + " failures");
            }
        }
    }

    /** True while the frame is close enough to the finger to be displayed. */
    private boolean withinRemoteWindow(long timeMs) {
        if (!dragging) {
            return true;
        }
        final long desired = desiredMs;
        if (desired < 0) {
            return true;
        }
        return Math.abs(timeMs - desired) <= remoteWindowLimit();
    }

    private long remoteWindowLimit() {
        final long duration = planDurationMs;
        return Math.max(REMOTE_WINDOW_MIN_MS,
                duration > 0 ? duration / 50L : REMOTE_WINDOW_MIN_MS);
    }

    /**
     * Presentation time of the keyframe the bubble would show for this
     * position, or -1 when the index is not ready yet. Used to tell whether
     * an in-flight fetch is still worth keeping.
     */
    private long nearestKeyframeTime(long timeMs) {
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
