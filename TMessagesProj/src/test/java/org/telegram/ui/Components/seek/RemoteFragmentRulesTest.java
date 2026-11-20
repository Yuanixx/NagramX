package org.telegram.ui.Components.seek;

/** Plain-JVM tests for fragment selection and explicit non-convergence. */
public final class RemoteFragmentRulesTest {

    private static int passed;
    private static final long S = 1_000_000L;

    public static void main(String[] args) {
        testAtOrBefore();
        testSpan();
        testContainment();
        testDecodeTimeOverflow();
        System.out.println("REMOTE FRAGMENT: " + passed + " checks passed");
    }

    private static void testAtOrBefore() {
        long[] times = {0L, 10 * S, 20 * S, 30 * S};
        check(RemoteFragmentRules.bestAtOrBefore(times, 4, 25 * S) == 2,
                "latest fragment at/before target");
        check(RemoteFragmentRules.bestAtOrBefore(times, 4, 20 * S) == 2,
                "exact fragment start is included");
        check(RemoteFragmentRules.bestAtOrBefore(times, 4, 5 * S) == -1,
                "no fragment at/before target is reported as -1");
    }

    private static void testSpan() {
        long[] times = {0L, 10 * S, 20 * S, 30 * S};
        check(RemoteFragmentRules.spanAfter(times, 4, 2) == 10 * S,
                "span is the gap to the next fragment");
        check(RemoteFragmentRules.spanAfter(times, 4, 3) == -1L,
                "no later fragment means span is unknown, not zero");
        check(RemoteFragmentRules.spanAfter(times, 4, -1) == -1L,
                "invalid index has no span");
    }

    private static void testContainment() {
        check(RemoteFragmentRules.containsTarget(20 * S, 10 * S, 25 * S),
                "target inside proved interval");
        check(!RemoteFragmentRules.containsTarget(20 * S, 10 * S, 31 * S),
                "target after proved interval must fail the probe");
        check(!RemoteFragmentRules.containsTarget(20 * S, 0L, 20 * S),
                "unknown span must fail the probe");
        check(!RemoteFragmentRules.containsTarget(20 * S, -1L, 20 * S),
                "negative span must fail the probe");
    }

    private static void testDecodeTimeOverflow() {
        check(RemoteFragmentRules.validDecodeTime(0L), "zero tfdt is valid");
        check(RemoteFragmentRules.validDecodeTime(Long.MAX_VALUE), "large positive tfdt is valid");
        check(!RemoteFragmentRules.validDecodeTime(-1L), "u64 overflow to negative is rejected");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
