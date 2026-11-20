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

    /** Last indexed fragment start; -1 when no index exists. */
    public static long coveredUs(long[] timesUs, int length) {
        if (timesUs == null || length <= 0 || length > timesUs.length) {
            return -1L;
        }
        return timesUs[length - 1];
    }

    /** Local index can resolve a time only inside its own covered range. */
    public static boolean coversLocal(long[] timesUs, int length, long timeUs) {
        long covered = coveredUs(timesUs, length);
        return covered >= 0L && timeUs >= 0L && timeUs <= covered;
    }

    /** A remote point proves a single fragment, never the whole remaining timeline. */
    public static boolean coversRemote(long startUs, long endUs, long timeUs) {
        return endUs > startUs && startUs >= 0L && timeUs >= startUs && timeUs <= endUs;
    }

    /** A probed fragment is usable only if it is inside the file and ahead of local coverage. */
    public static boolean validProbe(long startUs, long endUs, long position, long size,
                                     long fileLength, long coveredEndUs) {
        return endUs > startUs
                && startUs >= 0L
                && size > 0L
                && position >= coveredEndUs
                && position + size <= fileLength;
    }
}
