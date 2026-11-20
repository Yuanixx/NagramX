package org.telegram.ui.Components.seek;

/**
 * Pure rules that distinguish the three different time ranges involved in a seek:
 * the declared media duration, the range covered by a local partial index, and the
 * range proved by a remote fragment probe. No Android or media3 dependency here so
 * the exact production logic can be exercised by plain JVM tests.
 */
public final class SeekCoverageRules {

    private SeekCoverageRules() {
    }

    /** Start time of the last indexed chunk; -1 when no valid index exists. */
    public static long lastChunkStartUs(long[] timesUs, int length) {
        if (!validTimes(timesUs, length)) {
            return -1L;
        }
        return timesUs[length - 1];
    }

    /**
     * End of the locally covered time range: last chunk start plus its duration.
     * A chunk start alone is NOT the covered end. -1 means no valid coverage.
     */
    public static long coveredEndUs(long[] timesUs, long[] durationsUs, int length) {
        if (!validTimes(timesUs, length) || durationsUs == null
                || durationsUs.length < length || length <= 0) {
            return -1L;
        }
        long last = timesUs[length - 1];
        long duration = durationsUs[length - 1];
        if (duration < 0L || last > Long.MAX_VALUE - duration) {
            return -1L;
        }
        return last + duration;
    }

    /** Local index can resolve a time only inside its proved covered range. */
    public static boolean coversLocal(long[] timesUs, long[] durationsUs, int length,
                                      long timeUs) {
        long end = coveredEndUs(timesUs, durationsUs, length);
        return end >= 0L && timeUs >= 0L && timeUs <= end;
    }

    /** End of the covered bytes: last chunk offset plus its size; -1 when no valid index. */
    public static long coveredBytes(int[] sizes, long[] offsets, int length) {
        if (sizes == null || offsets == null || length <= 0
                || length > sizes.length || length > offsets.length) {
            return -1L;
        }
        long previous = -1L;
        for (int i = 0; i < length; i++) {
            if (offsets[i] < 0L || sizes[i] <= 0 || offsets[i] < previous) {
                return -1L;
            }
            if (offsets[i] > Long.MAX_VALUE - sizes[i]) {
                return -1L;
            }
            previous = offsets[i] + sizes[i];
        }
        return previous;
    }

    private static boolean validTimes(long[] timesUs, int length) {
        if (timesUs == null || length <= 0 || length > timesUs.length) {
            return false;
        }
        long previous = -1L;
        for (int i = 0; i < length; i++) {
            if (timesUs[i] < 0L || timesUs[i] < previous) {
                return false;
            }
            previous = timesUs[i];
        }
        return true;
    }

    /** A remote point proves a single fragment, never the whole remaining timeline. */
    public static boolean coversRemote(long startUs, long endUs, long timeUs) {
        return endUs > startUs && startUs >= 0L && timeUs >= startUs && timeUs < endUs;
    }

    /**
     * A probed fragment is usable only if it is inside the file and ahead of the locally
     * covered BYTE range. Never pass a microsecond value as the last argument.
     */
    public static boolean validProbe(long startUs, long endUs, long position, long size,
                                     long fileLength, long coveredEndBytes) {
        return endUs > startUs
                && startUs >= 0L
                && size > 0L
                && position >= coveredEndBytes
                && position + size <= fileLength;
    }
}
