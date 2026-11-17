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

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Single worker that fills the thumbnail store from coarse to fine.
 *
 * The UI never talks to this class except through volatile setters. Dragging
 * pauses the bulk pass and inserts the nearest missing keyframe for the latest
 * requested position at the head of the queue. A decode already in flight is
 * cancelled; the next iteration picks up the newest position instead of
 * showing the first frame for the whole gesture.
 */
public final class ThumbnailScheduler {

    public interface Callback {
        void onThumbnail(Thumbnail thumbnail);
    }

    /** Called from the scheduler thread when the queue runs dry. */
    public interface IndexRefill {
        /**
         * Re-reads whatever index the file can offer now (a streaming .temp may
         * have grown), appends new keyframes to the decoder and updates the
         * plan.
         *
         * @return true when new keyframes appeared and the queue should be
         *         rebuilt
         */
        boolean refillIndex();
    }

    /** What the scheduler should cover. */
    public static final class Plan {
        public final long durationMs;
        /** Sorted keyframe presentation times, or null when unknown. */
        volatile long[] keyframeTimesMs;
        /** >0 for short local videos: evenly spaced frames, no keyframe tiers. */
        long uniformStepMs;

        public Plan(long durationMs, long[] keyframeTimesMs) {
            this.durationMs = durationMs;
            this.keyframeTimesMs = keyframeTimesMs;
        }

        public void setUniformStep(long stepMs) {
            uniformStepMs = stepMs > 0 ? stepMs : 0L;
        }

        /** Scheduler thread only; the array is replaced wholesale. */
        public void updateKeyframeTimes(long[] updated) {
            if (updated != null && updated.length > 0) {
                keyframeTimesMs = updated;
            }
        }
    }

    private static final int PRIORITY_HOVER = 1;
    private static final int PRIORITY_COARSE = 2;
    private static final int PRIORITY_MID = 3;
    private static final int PRIORITY_FINE = 4;

    private static final int COARSE_COUNT = 24;
    private static final int MID_COUNT = 60;
    private static final int RETRIEVER_FINE_COUNT = 120;
    /** 240 RGB_565 thumbnails at 240x136 fit in the 16 MB budget. */
    private static final int MAX_THUMBNAILS = 240;

    private static final long HOVER_RETRY_MS = 5000L;
    /** How often a streaming session re-reads its index while idle. */
    private static final long REFILL_INTERVAL_MS = 4000L;

    private static final class Task {
        final int priority;
        final int tier;
        final long timeMs;
        final int keyframeIndex;
        /** Insertion order inside a priority; binary subdivision relies on it. */
        final long order;
        /** Bounded retries for a frame whose bytes have not arrived yet. */
        int missingRetries;
        long firstMissingAtMs;
        /** Earliest time this task may be decoded again. */
        long retryAtMs;

        Task(int priority, int tier, long timeMs, int keyframeIndex, long order) {
            this.priority = priority;
            this.tier = tier;
            this.timeMs = timeMs;
            this.keyframeIndex = keyframeIndex;
            this.order = order;
        }
    }

    private final Object lock = new Object();
    private final DecodeGate decodeGate = new DecodeGate();
    private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) -> {
        if (a.priority != b.priority) {
            return Integer.compare(a.priority, b.priority);
        }
        // Same priority: respect the order the layers were laid out in. The
        // coarse layer is built middle-first, quarter points next, and a time
        // sort would silently turn it back into a left-to-right sweep.
        if (a.order != b.order) {
            return Long.compare(a.order, b.order);
        }
        return Long.compare(a.timeMs, b.timeMs);
    });
    private final Plan plan;
    private final FrameDecoder decoder;
    private final ThumbnailStore store;
    private final Callback callback;
    private final IndexRefill indexRefill;
    private final PreviewDiskCache.Writer diskSink;
    private final int maxWidth;
    private final int maxHeight;

    private boolean stopped;
    private boolean dragging;
    private boolean buffering;
    private long desiredMs = -1;
    private long desiredKeyframeTime = -1;
    private long lastHoverKey = -1;
    private long lastHoverAt;
    private long durationMs;
    private long lastRefillAt;
    private long nextTaskOrder;
    private long decodingTimeMs = -1;
    private long firstTaskWaitMs = -1;
    private long firstDecodeStartedAt;
    private int failures;
    private final long startedAt = SystemClock.uptimeMillis();
    private int loggedMilestone;
    private boolean loggedFirstTask;
    private boolean loggedFirst;
    private boolean loggedDone;
    private LongSupplier downloadPercent;
    private long lastSummaryAt;

    public ThumbnailScheduler(Plan plan, FrameDecoder decoder, ThumbnailStore store,
                              int maxWidth, int maxHeight, Callback callback,
                              IndexRefill refill, PreviewDiskCache.Writer diskSink) {
        this.plan = plan;
        this.decoder = decoder;
        this.store = store;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.callback = callback;
        this.indexRefill = refill;
        this.diskSink = diskSink;
        this.durationMs = plan != null ? plan.durationMs : 0;
    }

    /** Diagnostic: download progress used by the periodic store summary. */
    public void setDownloadPercent(LongSupplier supplier) {
        downloadPercent = supplier;
    }

    public void setDesiredPosition(long timeMs) {
        synchronized (lock) {
            if (stopped || timeMs < 0) {
                return;
            }
            if (timeMs == desiredMs) {
                return;
            }
            desiredMs = timeMs;
            final long key = nearestKeyframeTimeLocked();
            if (key != desiredKeyframeTime) {
                desiredKeyframeTime = key;
                lastHoverKey = -1;
                // There is only one finger. Keeping hover tasks for positions
                // it already left behind made the first picture come from an
                // old moment while the current one waited in the same queue.
                queue.removeIf(task -> task.priority == PRIORITY_HOVER
                        && task.timeMs != key);
            }
            lock.notifyAll();
        }
    }

    /**
     * True when a task for the keyframe this position would show is queued or
     * being decoded right now. Read by the bubble to decide whether a spinner
     * is honest; it never blocks on the worker.
     */
    public boolean isPendingFrameFor(long timeMs) {
        synchronized (lock) {
            final long[] keyframes = plan != null ? plan.keyframeTimesMs : null;
            if (keyframes == null || keyframes.length == 0) {
                return false;
            }
            final long key = keyframes[nearestIndex(keyframes, timeMs)];
            if (store.containsTime(key)) {
                return false;
            }
            if (decodingTimeMs == key) {
                return true;
            }
            for (Task task : queue) {
                if (task.timeMs == key) {
                    return true;
                }
            }
            return false;
        }
    }

    public void setDragging(boolean value) {
        boolean cancel = false;
        synchronized (lock) {
            if (stopped || dragging == value) {
                return;
            }
            dragging = value;
            if (value) {
                cancel = true;
                lastHoverKey = -1;
            }
            lock.notifyAll();
        }
        if (cancel) {
            // Stop a bulk decode immediately: the finger has priority.
            decoder.cancel();
        }
    }

    public void setBuffering(boolean value) {
        boolean cancel = false;
        synchronized (lock) {
            if (stopped || buffering == value) {
                return;
            }
            buffering = value;
            if (value) {
                cancel = true;
            }
            lock.notifyAll();
        }
        if (cancel) {
            decoder.cancel();
        }
    }

    public void stop() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            queue.clear();
            lock.notifyAll();
        }
        // The gate orders this cancel against decodeAt's reset, so a stop can
        // never be erased by a decode that starts at the same moment.
        decodeGate.stop(decoder::cancel);
    }

    /**
     * Runs until {@link #stop()} is called. The owning session closes the
     * decoder afterwards, so an in-flight native call is never released from
     * another thread.
     */
    public void run() {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            buildQueueLocked();
        }
        runLoop();
    }

    private void runLoop() {
        while (true) {
            Task task = null;
            boolean refillDue = false;
            boolean summaryDue = false;
            // Log the periodic summary before any branch can wait: the drag
            // and buffering paths used to continue before the old check, so
            // the 5s line went missing exactly while a finger was down.
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                final long now = SystemClock.uptimeMillis();
                if (now - lastSummaryAt >= 5000L) {
                    lastSummaryAt = now;
                    summaryDue = true;
                }
            }
            if (summaryDue) {
                logStoreSummary();
            }
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                final long now = SystemClock.uptimeMillis();
                if (dragging) {
                    maybeScheduleHoverLocked(now);
                    final Task head = queue.peek();
                    if (head == null || head.priority != PRIORITY_HOVER) {
                        // The finger is moving, so hover never becomes eligible:
                        // every setDesiredPosition pushes desiredChangedAt out
                        // again. If the store is still empty the bubble has
                        // nothing at all to show, so spend one coarse decode
                        // instead of waiting for a still finger that may never
                        // come. Bounded by COARSE_COUNT and it stops as soon as
                        // a single picture exists.
                        if (head != null && head.priority == PRIORITY_COARSE
                                && store.size() == 0) {
                            task = queue.poll();
                        } else {
                            waitLocked(hoverWaitLocked(now));
                            continue;
                        }
                    } else {
                        task = queue.poll();
                    }
                } else if (buffering) {
                    // Playback stalls are common while a streamed file fills.
                    // Pause the bulk layers, but never the finger's own frame
                    // and never leave an empty bubble with nothing to decode.
                    final Task head = queue.peek();
                    final boolean hoverAtHead = head != null
                            && head.priority == PRIORITY_HOVER;
                    if (PreviewRules.decodeWhileBuffering(hoverAtHead, store.size() == 0)) {
                        task = queue.poll();
                    } else {
                        waitLocked(150L);
                        continue;
                    }
                } else {
                    if (queue.isEmpty()) {
                        if (durationMs <= 0) {
                            final long known = decoder.getDurationMs();
                            if (known > 0) {
                                durationMs = known;
                                buildQueueLocked();
                            }
                        }
                        if (queue.isEmpty()) {
                            if (!loggedDone && durationMs > 0) {
                                loggedDone = true;
                                PreviewLog.d("SeekPreview: fill complete thumbnails="
                                        + store.size() + " in "
                                        + (SystemClock.uptimeMillis() - startedAt) + "ms");
                            }
                            if (indexRefill != null && durationMs > 0) {
                                // A streaming file keeps growing: fragments
                                // that arrived after this session started are
                                // not in the plan yet. Decide here, run the
                                // file scan outside the lock; done tasks are
                                // filtered by the store on rebuild.
                                final long sinceRefill = now - lastRefillAt;
                                if (sinceRefill < REFILL_INTERVAL_MS) {
                                    waitLocked(Math.max(1L, REFILL_INTERVAL_MS - sinceRefill));
                                } else {
                                    refillDue = true;
                                    lastRefillAt = now;
                                }
                                continue;
                            }
                            waitLocked(durationMs <= 0 ? 1000L : 0L);
                            continue;
                        }
                    }
                    task = queue.poll();
                }
            }
            if (refillDue) {
                boolean grew = false;
                try {
                    grew = indexRefill != null && indexRefill.refillIndex();
                } catch (Throwable e) {
                    PreviewLog.e("SeekPreview: index refill failed", e);
                }
                if (grew) {
                    synchronized (lock) {
                        if (!stopped) {
                            buildQueueLocked();
                        }
                    }
                }
                continue;
            }
            if (task != null) {
                synchronized (lock) {
                    final long remaining = task.retryAtMs - SystemClock.uptimeMillis();
                    if (remaining > 0L && !stopped) {
                        // Not due yet. Put it back and wait in short slices so
                        // a newly queued finger frame can wake us immediately.
                        queue.add(task);
                        task = null;
                        waitLocked(Math.min(remaining, 150L));
                    }
                }
            }
            if (task != null) {
                decode(task);
            }
        }
    }

    /** Periodic diagnostic: tells a coverage gap from duplicate decoding. */
    private void logStoreSummary() {
        final ThumbnailStore.Snapshot snapshot = store.snapshot();
        final Thumbnail[] items = snapshot.items;
        final java.util.HashSet<Long> fingerprints = new java.util.HashSet<>();
        for (Thumbnail item : items) {
            fingerprints.add(item.fingerprint);
        }
        final long first = items.length > 0 ? items[0].timeMs : -1L;
        final long last = items.length > 0 ? items[items.length - 1].timeMs : -1L;
        final int percent = downloadPercent != null ? (int) downloadPercent.getAsLong() : 100;
        PreviewLog.d("SeekPreview: store summary frames=" + items.length
                + " distinct=" + fingerprints.size()
                + " range=" + first + ".." + last
                + " downloaded=" + percent + "%");
    }

    private void decode(Task task) {
        synchronized (lock) {
            if (stopped) {
                return;
            }
            decodingTimeMs = task.timeMs;
            if (!loggedFirstTask) {
                loggedFirstTask = true;
                firstTaskWaitMs = SystemClock.uptimeMillis() - startedAt;
                PreviewLog.d("SeekPreview: first task t=" + task.timeMs
                        + " tier=" + task.tier
                        + " wait=" + firstTaskWaitMs + "ms");
            }
        }
        try {
            decodeTask(task);
        } finally {
            synchronized (lock) {
                if (decodingTimeMs == task.timeMs) {
                    decodingTimeMs = -1;
                }
            }
        }
    }

    private void decodeTask(Task task) {
        try {
            if (!decodeGate.begin(decoder::resetCancel)) {
                return;
            }
            if (store.containsTime(task.timeMs)) {
                return;
            }
            synchronized (lock) {
                if (firstDecodeStartedAt == 0) {
                    firstDecodeStartedAt = SystemClock.uptimeMillis();
                }
            }
            final FrameDecoder.DecodedFrame frame = decoder.decodeAt(
                    task.timeMs, maxWidth, maxHeight);
            if (frame == null || frame.bitmap == null) {
                if (decoder.wasCancelled()) {
                    // A short decoder waiting for a download can be interrupted
                    // by a drag. Put the task back so the frame is not lost.
                    synchronized (lock) {
                        if (!stopped && !(dragging && task.priority == PRIORITY_HOVER)) {
                            queue.add(task);
                        }
                    }
                    return;
                }
                if (decoder.wasDataMissing()) {
                    // Not a decode error: the bytes are still downloading or a
                    // cooldown refused their fetch. Retry a bounded number of
                    // times instead of dropping the frame forever, and never
                    // pollute the real-failure streak with it.
                    final long now = SystemClock.uptimeMillis();
                    final long delay;
                    synchronized (lock) {
                        if (task.firstMissingAtMs <= 0L) {
                            task.firstMissingAtMs = now;
                        }
                        task.missingRetries++;
                        delay = PreviewRules.missingFrameRetryDelayMs(
                                task.missingRetries, now, task.firstMissingAtMs);
                    }
                    if (delay >= 0L) {
                        // Keep it in the queue with a due time. Sleeping the
                        // single worker here would stall a newer finger frame.
                        synchronized (lock) {
                            if (!stopped && (task.priority != PRIORITY_HOVER
                                    || task.timeMs == desiredKeyframeTime)) {
                                task.retryAtMs = now + delay;
                                queue.add(task);
                            }
                        }
                        return;
                    }
                    PreviewLog.d("SeekPreview: frame retries exhausted at " + task.timeMs + "ms");
                    return;
                }
                synchronized (lock) {
                    failures++;
                }
                return;
            }
            if (frame.bitmap.isRecycled()) {
                return;
            }
            final long desired;
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                desired = desiredMs;
                if (durationMs <= 0) {
                    final long known = decoder.getDurationMs();
                    if (known > 0) {
                        durationMs = known;
                        buildQueueLocked();
                    }
                }
            }
            final Thumbnail thumbnail = new Thumbnail(frame.bitmap, frame.timeMs,
                    frame.keyframeIndex, task.tier);
            store.put(thumbnail, desired >= 0 ? desired : task.timeMs);
            PreviewLog.d("SeekPreview: frame idx=" + frame.keyframeIndex
                    + " t=" + frame.timeMs + " fp=" + thumbnail.fingerprint);
            if (diskSink != null) {
                diskSink.enqueue(frame.timeMs, frame.bitmap);
            }
            final Callback cb;
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                failures = 0;
                final int count = store.size();
                if (!loggedFirst) {
                    loggedFirst = true;
                    final long now = SystemClock.uptimeMillis();
                    final long decodeMs = firstDecodeStartedAt > 0
                            ? now - firstDecodeStartedAt : 0L;
                    PreviewLog.d("SeekPreview: first thumbnail in "
                            + (now - startedAt) + "ms"
                            + " (queueWait=" + firstTaskWaitMs + "ms"
                            + " decode=" + decodeMs + "ms"
                            + " t=" + thumbnail.timeMs
                            + " tier=" + task.tier
                            + " decoder=" + decoder.name() + ")");
                }
                if (count >= 24 && loggedMilestone < 24) {
                    loggedMilestone = 24;
                    PreviewLog.d("SeekPreview: coarse 24 thumbnails in "
                            + (SystemClock.uptimeMillis() - startedAt) + "ms" + timingStats());
                }
                if (count >= 60 && loggedMilestone < 60) {
                    loggedMilestone = 60;
                    PreviewLog.d("SeekPreview: 60 thumbnails in "
                            + (SystemClock.uptimeMillis() - startedAt) + "ms" + timingStats());
                }
                if (count >= 120 && loggedMilestone < 120) {
                    loggedMilestone = 120;
                    PreviewLog.d("SeekPreview: 120 thumbnails in "
                            + (SystemClock.uptimeMillis() - startedAt) + "ms");
                }
                if (count >= 240 && loggedMilestone < 240) {
                    loggedMilestone = 240;
                    PreviewLog.d("SeekPreview: 240 thumbnails in "
                            + (SystemClock.uptimeMillis() - startedAt) + "ms");
                }
                cb = callback;
            }
            if (cb != null) {
                cb.onThumbnail(thumbnail);
            }
        } catch (Throwable e) {
            if (isStopped()) {
                return;
            }
            if (decoder.wasCancelled()) {
                synchronized (lock) {
                    if (!stopped && !(dragging && task.priority == PRIORITY_HOVER)) {
                        queue.add(task);
                    }
                }
                return;
            }
            final int failureCount;
            synchronized (lock) {
                failureCount = ++failures;
            }
            if (failureCount <= 5) {
                PreviewLog.e("SeekPreview: decode failed at " + task.timeMs + "ms", e);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                synchronized (lock) {
                    stopped = true;
                    lock.notifyAll();
                }
            }
        }
    }

    private String timingStats() {
        if (decoder instanceof MediaCodecFrameDecoder) {
            return ((MediaCodecFrameDecoder) decoder).timingStats();
        }
        return "";
    }

    private boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    private void waitLocked(long timeoutMs) {
        try {
            if (timeoutMs <= 0) {
                lock.wait();
            } else {
                lock.wait(timeoutMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }

    private void maybeScheduleHoverLocked(long now) {
        if (desiredMs < 0) {
            return;
        }
        final long key;
        final int index;
        final long[] keyframes = plan != null ? plan.keyframeTimesMs : null;
        if (keyframes != null && keyframes.length > 0) {
            index = nearestIndex(keyframes, desiredMs);
            key = keyframes[index];
        } else {
            index = -1;
            // No index means the picture is decoded at the finger position.
            // Snapping keeps one decode per 750ms of travel instead of one
            // per pixel; with real keyframes the file already defines the
            // step, so those keep the exact position.
            final long step = plan != null && plan.uniformStepMs > 0
                    ? plan.uniformStepMs : PreviewRules.HOVER_GRID_MS;
            key = PreviewRules.hoverTime(desiredMs, true, step);
        }
        if (key != desiredKeyframeTime) {
            desiredKeyframeTime = key;
            lastHoverKey = -1;
            queue.removeIf(task -> task.priority == PRIORITY_HOVER
                    && task.timeMs != key);
        }
        if (store.containsTime(key)) {
            lastHoverKey = key;
            return;
        }
        if (lastHoverKey == key && now - lastHoverAt < HOVER_RETRY_MS) {
            return;
        }
        for (Task task : queue) {
            if (task.priority == PRIORITY_HOVER && task.timeMs == key) {
                lastHoverKey = key;
                lastHoverAt = now;
                return;
            }
        }
        queue.add(new Task(PRIORITY_HOVER, Thumbnail.TIER_FINE, key, index,
                nextTaskOrder++));
        lastHoverKey = key;
        lastHoverAt = now;
    }

    private long hoverWaitLocked(long now) {
        if (desiredMs < 0) {
            return 0;
        }
        if (lastHoverKey >= 0) {
            final long retry = HOVER_RETRY_MS - (now - lastHoverAt);
            if (retry > 0) {
                return retry;
            }
        }
        return 0;
    }

    private int nearestIndex(long[] times, long timeMs) {
        int lo = 0;
        int hi = times.length - 1;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (times[mid] < timeMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo > 0) {
            final long before = timeMs - times[lo - 1];
            final long after = times[lo] - timeMs;
            if (before <= after) {
                return lo - 1;
            }
        }
        return lo;
    }

    /** Locked helper: the keyframe the finger currently wants, or -1. */
    private long nearestKeyframeTimeLocked() {
        final long[] keyframes = plan != null ? plan.keyframeTimesMs : null;
        if (keyframes == null || keyframes.length == 0 || desiredMs < 0) {
            return -1;
        }
        return keyframes[nearestIndex(keyframes, desiredMs)];
    }

    private void buildQueueLocked() {
        // A refill rebuild must not drop an in-flight hover task: a finger
        // holding still would silently lose its priority decode until the
        // hover re-arm window passed.
        final ArrayList<Task> hovers = new ArrayList<>();
        for (Task task : queue) {
            if (task.priority == PRIORITY_HOVER) {
                hovers.add(task);
            }
        }
        queue.clear();
        final long[] keyframes = plan != null ? plan.keyframeTimesMs : null;
        if (plan != null && plan.uniformStepMs > 0) {
            addUniformTimesLocked();
        } else if (keyframes != null && keyframes.length > 0) {
            final boolean[] used = new boolean[keyframes.length];
            int chosen = addBisectedKeyframesLocked(PRIORITY_COARSE, Thumbnail.TIER_COARSE,
                    keyframes, used, COARSE_COUNT);
            chosen += addBisectedKeyframesLocked(PRIORITY_MID, Thumbnail.TIER_MID,
                    keyframes, used, MID_COUNT);
            final int total = Math.min(MAX_THUMBNAILS, keyframes.length);
            final int remaining = Math.max(0, total - chosen);
            if (remaining > 0) {
                final int[] unchosen = new int[keyframes.length - chosen];
                int cursor = 0;
                for (int i = 0; i < keyframes.length; i++) {
                    if (!used[i]) {
                        unchosen[cursor++] = i;
                    }
                }
                final int[] picks = bisectIndices(unchosen.length, remaining);
                for (int pick : picks) {
                    final int index = unchosen[pick];
                    queue.add(new Task(PRIORITY_FINE, Thumbnail.TIER_FINE,
                            keyframes[index], index, nextTaskOrder++));
                }
            }
        } else if (durationMs > 0) {
            addBisectedTimesLocked(PRIORITY_COARSE, Thumbnail.TIER_COARSE, COARSE_COUNT);
            addBisectedTimesLocked(PRIORITY_MID, Thumbnail.TIER_MID, MID_COUNT);
            addBisectedTimesLocked(PRIORITY_FINE, Thumbnail.TIER_FINE, RETRIEVER_FINE_COUNT);
        }
        queue.addAll(hovers);
        PreviewLog.d("SeekPreview: plan duration=" + durationMs
                + " keyframes=" + (keyframes != null ? keyframes.length : 0)
                + " tasks=" + queue.size());
    }

    private int addBisectedKeyframesLocked(int priority, int tier, long[] keyframes,
                                           boolean[] used, int desiredCount) {
        final int[] picks = bisectIndices(keyframes.length,
                Math.min(desiredCount, keyframes.length));
        int added = 0;
        for (int index : picks) {
            if (used[index]) {
                continue;
            }
            used[index] = true;
            queue.add(new Task(priority, tier, keyframes[index], index,
                    nextTaskOrder++));
            added++;
        }
        return added;
    }

    /** Evenly spaced local frames for a short video; no keyframe tiers. */
    private void addUniformTimesLocked() {
        if (durationMs <= 0) {
            return;
        }
        final long end = Math.max(0L, durationMs - 100L);
        long step = Math.max(1L, plan.uniformStepMs);
        long count = end / step + 1L;
        if (count > 80L) {
            step = Math.max(step, (end + 79L) / 80L);
            count = end / step + 1L;
        }
        for (long i = 0; i < count; i++) {
            final long time = Math.min(end, i * step);
            queue.add(new Task(PRIORITY_COARSE, Thumbnail.TIER_COARSE, time, -1,
                    nextTaskOrder++));
        }
    }

    private void addBisectedTimesLocked(int priority, int tier, int desiredCount) {
        final long span = Math.max(1L, durationMs - 1L);
        final int count = (int) Math.min(desiredCount, span + 1L);
        final long[] times = new long[count];
        for (int i = 0; i < count; i++) {
            times[i] = count <= 1 ? 0L
                    : Math.round(i * (double) span / (count - 1));
        }
        final int[] picks = bisectIndices(count, count);
        final Set<Long> seen = new HashSet<>();
        for (int index : picks) {
            final long time = times[index];
            if (seen.add(time)) {
                queue.add(new Task(priority, tier, time, -1, nextTaskOrder++));
            }
        }
    }

    /**
     * Middle first, then the two quarter points, then eighths, and so on.
     * The old helper walked the index from left to right, so a finger at
     * minute 3 still got minute 0 painted first.
     */
    private static int[] bisectIndices(int count, int desired) {
        final int n = Math.max(1, Math.min(desired, count));
        final int[] out = new int[n];
        if (n == 1) {
            out[0] = count / 2;
            return out;
        }
        final boolean[] used = new boolean[count];
        final java.util.ArrayDeque<int[]> ranges = new java.util.ArrayDeque<>();
        ranges.add(new int[]{0, count - 1});
        int position = 0;
        while (!ranges.isEmpty() && position < n) {
            final int[] range = ranges.poll();
            final int lo = range[0];
            final int hi = range[1];
            if (lo > hi) {
                continue;
            }
            final int mid = (lo + hi) >>> 1;
            if (!used[mid]) {
                used[mid] = true;
                out[position++] = mid;
            }
            if (lo < mid) {
                ranges.add(new int[]{lo, mid - 1});
            }
            if (mid < hi) {
                ranges.add(new int[]{mid + 1, hi});
            }
        }
        for (int i = 0; position < n && i < count; i++) {
            if (!used[i]) {
                out[position++] = i;
            }
        }
        return out;
    }
}
