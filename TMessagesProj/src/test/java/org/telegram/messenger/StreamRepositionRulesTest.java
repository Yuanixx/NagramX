package org.telegram.messenger;

/** Plain-JVM regression tests for active stream reposition decisions. */
public final class StreamRepositionRulesTest {

    private static int passed;

    public static void main(String[] args) {
        check(StreamRepositionRules.shouldCancelInFlightRequests(
                true, true, false, true, 0, 1024), "changed seek cancels stale sparse requests");
        check(StreamRepositionRules.shouldCancelInFlightRequests(
                true, true, false, true, 1024, 0), "backward seek also re-aims");
        check(!StreamRepositionRules.shouldCancelInFlightRequests(
                true, true, false, true, 1024, 1024), "same aligned part does not restart");
        check(!StreamRepositionRules.shouldCancelInFlightRequests(
                false, true, false, true, 0, 1024), "initial start is handled by normal startup");
        check(!StreamRepositionRules.shouldCancelInFlightRequests(
                true, false, false, true, 0, 1024), "detached source cannot reposition");
        check(!StreamRepositionRules.shouldCancelInFlightRequests(
                true, true, true, true, 0, 1024), "priority frame requests keep their own path");
        check(!StreamRepositionRules.shouldCancelInFlightRequests(
                true, true, false, false, 0, 1024), "sequential files are not range-reset");
        System.out.println("STREAM REPOSITION RULES: " + passed + " checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
