package org.telegram.ui.Components.seek;

/** Plain-JVM tests for the three distinct seek time ranges. No JUnit, no Android. */
public final class SeekCoverageRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testLocalCoverage();
        testRemoteCoverageIsAnInterval();
        testNoFixedTolerance();
        testProbeValidation();
        testCoveredBytesIsNotCoveredTime();
        System.out.println("SEEK COVERAGE: " + passed + " checks passed");
    }

    private static void testLocalCoverage() {
        long[] times = {0L, 10_000_000L, 20_000_000L};
        check(SeekCoverageRules.coveredUs(times, 3) == 20_000_000L, "covered end is last indexed time");
        check(SeekCoverageRules.coversLocal(times, 3, 0L), "start is covered");
        check(SeekCoverageRules.coversLocal(times, 3, 20_000_000L), "boundary is covered");
        check(!SeekCoverageRules.coversLocal(times, 3, 20_000_001L), "beyond covered end is not local");
        check(SeekCoverageRules.coveredUs(times, 0) == -1L, "empty index has no covered end");
        check(!SeekCoverageRules.coversLocal(null, 0, 1L), "null index covers nothing");
    }

    private static void testRemoteCoverageIsAnInterval() {
        check(SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 20_000_000L), "interval start");
        check(SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 25_000_000L), "interval middle");
        check(SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 30_000_000L), "interval end");
        check(!SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 30_000_001L), "past interval");
        check(!SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 19_999_999L), "before interval");
        check(!SeekCoverageRules.coversRemote(0L, 0L, 1L), "empty interval covers nothing");
    }

    private static void testNoFixedTolerance() {
        // The old +-20s rule accepted this; interval coverage must reject it.
        check(!SeekCoverageRules.coversRemote(0L, 5_000_000L, 19_000_000L),
                "fixed 20s tolerance is not a coverage proof");
        check(SeekCoverageRules.coversRemote(0L, 25_000_000L, 19_000_000L),
                "same target is valid when the proved fragment really covers it");
    }

    private static void testProbeValidation() {
        check(SeekCoverageRules.validProbe(20L, 30L, 100L, 50L, 1000L, 80L),
                "valid forward fragment inside file");
        check(!SeekCoverageRules.validProbe(20L, 30L, 70L, 50L, 1000L, 80L),
                "fragment before local coverage is not progress");
        check(!SeekCoverageRules.validProbe(20L, 30L, 100L, 0L, 1000L, 80L),
                "zero-size fragment is invalid");
        check(!SeekCoverageRules.validProbe(20L, 30L, 100L, 950L, 1000L, 80L),
                "fragment past end of file is invalid");
        check(!SeekCoverageRules.validProbe(30L, 20L, 100L, 50L, 1000L, 80L),
                "reversed interval is invalid");
    }

    private static void testCoveredBytesIsNotCoveredTime() {
        int[] sizes = {100, 200};
        long[] offsets = {1000L, 1300L};
        check(SeekCoverageRules.coveredBytes(sizes, offsets, 2) == 1500L,
                "covered bytes is last offset plus size");
        check(SeekCoverageRules.coveredBytes(sizes, offsets, 0) == -1L,
                "no index means no covered bytes");
        check(SeekCoverageRules.coveredUs(new long[]{0L, 20_000_000L}, 2) == 20_000_000L,
                "covered time stays in microseconds, a different unit from bytes");
        check(!SeekCoverageRules.validProbe(20_000_000L, 30_000_000L, 1400L, 50L, 100_000_000L, 1500L),
                "a fragment before the covered byte boundary is rejected");
        check(SeekCoverageRules.validProbe(20_000_000L, 30_000_000L, 1600L, 50L, 100_000_000L, 1500L),
                "a fragment after the covered byte boundary is accepted");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
