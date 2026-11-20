package org.telegram.ui.Components.seek;

/** Pure selection/validation rules for remote fragmented-MP4 probes. */
public final class RemoteFragmentRules {

    private RemoteFragmentRules() {
    }

    /**
     * Index of the latest fragment starting at or before the target, or -1 when every
     * fragment starts after the target. A later fragment is only a search bound; it is
     * never a seek position.
     */
    public static int bestAtOrBefore(long[] timesUs, int count, long targetUs) {
        int best = -1;
        for (int i = 0; i < count; i++) {
            if (timesUs[i] <= targetUs && (best < 0 || timesUs[i] > timesUs[best])) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Span of the chosen fragment derived from the next fragment's tfdt. Returns -1 when
     * no later fragment exists in the window: the probe must then fail explicitly instead
     * of inventing a duration.
     */
    public static long spanAfter(long[] timesUs, int count, int index) {
        if (index < 0 || index >= count) {
            return -1L;
        }
        long next = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (timesUs[i] > timesUs[index] && timesUs[i] < next) {
                next = timesUs[i];
            }
        }
        return next == Long.MAX_VALUE ? -1L : next - timesUs[index];
    }

    /** The target must lie inside the proved fragment, not merely near its start. */
    public static boolean containsTarget(long startUs, long spanUs, long targetUs) {
        return spanUs > 0L && targetUs >= startUs && targetUs <= startUs + spanUs;
    }

    /** tfdt at or beyond Long.MAX_VALUE is not a usable decode time. */
    public static boolean validDecodeTime(long ticks) {
        return ticks >= 0L;
    }
}
