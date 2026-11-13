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

        public Plan(long durationMs, long[] keyframeTimesMs) {
            this.durationMs = durationMs;
            this.keyframeTimesMs = keyframeTimesMs;
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

        Task(int priority, int tier, long timeMs, int keyframeIndex) {
            this.priority = priority;
            this.tier = tier;
            this.timeMs = timeMs;
            this.keyframeIndex = keyframeIndex;
        }
    }

    private final Object lock = new Object();
    private final PriorityQueue<Task> queue = new PriorityQueue<>((a, b) -> {
        if (a.priority != b.priority) {
            return Integer.compare(a.priority, b.priority);
        }
        return Long.compare(a.timeMs, b.timeMs);
    });
    private final Plan plan;
    private final FrameDecoder decoder;
    private final ThumbnailStore store;
    private final Callback callback;
    private final IndexRefill indexRefill;
    private final int maxWidth;
    private final int maxHeight;

    private boolean stopped;
    private boolean dragging;
    private boolean buffering;
    private long desiredMs = -1;
    private long lastHoverKey = -1;
    private long lastHoverAt;
    private long durationMs;
    private long lastRefillAt;
    private int failures;
    private final long startedAt = SystemClock.uptimeMillis();
    private int loggedMilestone;
    private boolean loggedFirst;
    private boolean loggedDone;

    public ThumbnailScheduler(Plan plan, FrameDecoder decoder, ThumbnailStore store,
                              int maxWidth, int maxHeight, Callback callback,
                              IndexRefill refill) {
        this.plan = plan;
        this.decoder = decoder;
        this.store = store;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.callback = callback;
        this.indexRefill = refill;
        this.durationMs = plan != null ? plan.durationMs : 0;
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
            lastHoverKey = -1;
            lock.notifyAll();
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
        decoder.cancel();
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
                    waitLocked(150L);
                    continue;
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
                decode(task);
            }
        }
    }

    private void decode(Task task) {
        try {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
            }
            if (store.containsTime(task.timeMs)) {
                return;
            }
            final FrameDecoder.DecodedFrame frame = decoder.decodeAt(
                    task.timeMs, maxWidth, maxHeight);
            if (frame == null || frame.bitmap == null) {
                if (!decoder.wasCancelled()) {
                    synchronized (lock) {
                        failures++;
                    }
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
            final Callback cb;
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                failures = 0;
                final int count = store.size();
                if (!loggedFirst) {
                    loggedFirst = true;
                    PreviewLog.d("SeekPreview: first thumbnail in "
                            + (SystemClock.uptimeMillis() - startedAt) + "ms"
                            + " decoder=" + decoder.name());
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
            key = desiredMs;
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
        queue.add(new Task(PRIORITY_HOVER, Thumbnail.TIER_FINE, key, index));
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
        if (keyframes != null && keyframes.length > 0) {
            final boolean[] used = new boolean[keyframes.length];
            int chosen = addEvenKeyframesLocked(PRIORITY_COARSE, Thumbnail.TIER_COARSE,
                    keyframes, used, COARSE_COUNT);
            chosen += addEvenKeyframesLocked(PRIORITY_MID, Thumbnail.TIER_MID,
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
                final int[] picks = evenIndices(unchosen.length, remaining);
                for (int pick : picks) {
                    final int index = unchosen[pick];
                    queue.add(new Task(PRIORITY_FINE, Thumbnail.TIER_FINE,
                            keyframes[index], index));
                }
            }
        } else if (durationMs > 0) {
            addEvenTimesLocked(PRIORITY_COARSE, Thumbnail.TIER_COARSE, COARSE_COUNT);
            addEvenTimesLocked(PRIORITY_MID, Thumbnail.TIER_MID, MID_COUNT);
            addEvenTimesLocked(PRIORITY_FINE, Thumbnail.TIER_FINE, RETRIEVER_FINE_COUNT);
        }
        queue.addAll(hovers);
        PreviewLog.d("SeekPreview: plan duration=" + durationMs
                + " keyframes=" + (keyframes != null ? keyframes.length : 0)
                + " tasks=" + queue.size());
    }

    private int addEvenKeyframesLocked(int priority, int tier, long[] keyframes,
                                       boolean[] used, int desiredCount) {
        final int[] picks = evenIndices(keyframes.length,
                Math.min(desiredCount, keyframes.length));
        int added = 0;
        for (int index : picks) {
            if (used[index]) {
                continue;
            }
            used[index] = true;
            queue.add(new Task(priority, tier, keyframes[index], index));
            added++;
        }
        return added;
    }

    private void addEvenTimesLocked(int priority, int tier, int desiredCount) {
        final long span = Math.max(1L, durationMs - 1L);
        final int count = (int) Math.min(desiredCount, span + 1L);
        final Set<Long> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            final long time = count <= 1 ? 0L
                    : Math.round(i * (double) span / (count - 1));
            if (seen.add(time)) {
                queue.add(new Task(priority, tier, time, -1));
            }
        }
    }

    private static int[] evenIndices(int count, int desired) {
        final int n = Math.max(1, Math.min(desired, count));
        final int[] out = new int[n];
        if (n == 1) {
            out[0] = 0;
            return out;
        }
        for (int i = 0; i < n; i++) {
            out[i] = (int) Math.round(i * (count - 1.0) / (n - 1.0));
        }
        return out;
    }
}
