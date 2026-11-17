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

/**
 * Decision rules that do not touch Android.
 *
 * Keeping them here lets the JVM tests exercise the exact code the app runs:
 * the short-video gate, the display distance limit, the hover grid and the
 * "did the blocks really fill the range" check.
 */
public final class PreviewRules {

    /** A video at or below this length may use exact-time local decoding. */
    public static final long SHORT_VIDEO_MAX_MS = 60_000L;
    /** Fewer known keyframes than this cannot paint a short timeline. */
    public static final int SHORT_INDEX_MAX_KEYFRAMES = 12;
    /** Hover steps for timelines with no usable index. */
    public static final long HOVER_GRID_MS = 750L;

    private PreviewRules() {
    }

    /**
     * Short mode is only allowed when the index was actually read. A failed
     * or absent moov must not look like "zero keyframes": that sent complete
     * files with a tail moov into local decoding where nothing could be
     * decoded yet.
     */
    public static boolean shouldUseShortMode(boolean indexKnown, int keyframeCount,
                                             long durationMs) {
        return indexKnown
                && keyframeCount < SHORT_INDEX_MAX_KEYFRAMES
                && durationMs > 0L
                && durationMs <= SHORT_VIDEO_MAX_MS;
    }

    /** max(1.5s, 5% of duration), widened for very short videos. */
    public static long displayLimitMs(long durationMs) {
        if (durationMs <= 0L) {
            return 1500L;
        }
        long limit = Math.max(1500L, durationMs / 20L);
        if (durationMs < 10_000L) {
            limit = Math.max(limit, durationMs / 2L);
        }
        return limit;
    }

    /**
     * How far from the finger a cached frame may still be shown. Two local GOPs
     * are enough for a sparse stretch, but the window never grows past 10% of
     * the video: a wider net would put an unrelated picture under the timestamp.
     */
    public static long displayLimitMs(long durationMs, long localKeyframeGapMs) {
        final long base = displayLimitMs(durationMs);
        if (durationMs <= 0L || localKeyframeGapMs <= 0L) {
            return base;
        }
        final long doubled = localKeyframeGapMs > Long.MAX_VALUE / 2L
                ? Long.MAX_VALUE : localKeyframeGapMs * 2L;
        final long cap = Math.max(base, durationMs / 10L);
        return Math.min(cap, Math.max(base, doubled));
    }

    /**
     * Playback buffering pauses the bulk pass, but the finger's own frame must
     * stay responsive and a completely empty store must get one cheap frame.
     */
    public static boolean decodeWhileBuffering(boolean hoverAtHead, boolean storeEmpty) {
        return hoverAtHead || storeEmpty;
    }

    /** A clear while the seek bar is still held must not end the drag state. */
    public static boolean keepDraggingOnClear(boolean pressed) {
        return pressed;
    }

    /** A follow-up block reuses the probe slot its own fetch already claimed. */
    public static boolean probeGateAllows(boolean probePending, boolean probeInFlight,
                                          boolean probeHeldByThisFetch) {
        return probeHeldByThisFetch || !probePending || !probeInFlight;
    }

    /** Retry limits for a frame whose bytes have not arrived yet. */
    public static final int MAX_MISSING_FRAME_RETRIES = 6;
    public static final long MISSING_FRAME_RETRY_STEP_MS = 300L;
    public static final long MISSING_FRAME_RETRY_MAX_DELAY_MS = 1500L;
    public static final long MISSING_FRAME_RETRY_WINDOW_MS = 6000L;

    /**
     * Backoff for a frame that is still downloading. Negative means the budget
     * is spent and the task must be dropped; both the count and the total wait
     * are bounded so a stalled file cannot spin the worker forever.
     */
    public static long missingFrameRetryDelayMs(int attempt, long nowMs, long firstMissingAtMs) {
        if (attempt < 1 || attempt > MAX_MISSING_FRAME_RETRIES) {
            return -1L;
        }
        if (nowMs - firstMissingAtMs >= MISSING_FRAME_RETRY_WINDOW_MS) {
            return -1L;
        }
        return Math.min(MISSING_FRAME_RETRY_MAX_DELAY_MS,
                MISSING_FRAME_RETRY_STEP_MS * attempt);
    }

    /**
     * Hover time for the next decode. With a real index the finger position
     * is used as is, because the nearest keyframe is already a file-defined
     * step. Without an index, snapping to the short grid avoids decoding a
     * new picture for every pixel of finger travel.
     */
    public static long hoverTime(long timeMs, boolean indexEmpty, long stepMs) {
        if (timeMs <= 0L) {
            return 0L;
        }
        if (!indexEmpty || stepMs <= 0L) {
            return timeMs;
        }
        return ((timeMs + stepMs / 2L) / stepMs) * stepMs;
    }

    /** True when a stitched multi-block range actually holds every byte. */
    public static boolean rangeComplete(int filled, int wanted) {
        return wanted > 0 && filled >= wanted;
    }
}
