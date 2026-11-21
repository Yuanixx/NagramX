package org.telegram.ui.Components.seek;

/** Plain-JVM regression tests for native Media3 SeekMap trust decisions. */
public final class NativeSeekRulesTest {

    private static int passed;
    private static final long T = NativeSeekRules.TIME_UNSET;

    public static void main(String[] args) {
        testZeroTargetAlwaysResolves();
        testResolveToZeroIsRejected();
        testBeyondDeclaredDurationIsRejected();
        testWithinCoverageResolves();
        testUnknownDurationStillResolvesWhenNotPinnedToZero();
        System.out.println("NATIVE SEEK RULES: " + passed + " checks passed");
    }

    private static void testZeroTargetAlwaysResolves() {
        check(NativeSeekRules.canResolve(100L, 0L, 0L), "start of stream always resolves");
        check(NativeSeekRules.canResolve(T, 0L, 0L), "start resolves even with unknown duration");
    }

    private static void testResolveToZeroIsRejected() {
        // media3 MfraSeekMap returns SeekPoint(0, ...) for a target before the first tfra entry.
        check(!NativeSeekRules.canResolve(1_000_000L, 0L, 1_200_000L),
                "a positive target pinned to time zero is the jump-to-start bug: reject");
        check(!NativeSeekRules.canResolve(T, 0L, 600_000L),
                "pinned-to-zero is rejected even when duration is unknown");
    }

    private static void testBeyondDeclaredDurationIsRejected() {
        check(!NativeSeekRules.canResolve(1_000_000L, 900_000L, 2_000_000L),
                "target beyond declared duration is not trusted");
    }

    private static void testWithinCoverageResolves() {
        check(NativeSeekRules.canResolve(1_000_000L, 600_000L, 650_000L),
                "target inside declared duration that maps to a real sync point resolves");
    }

    private static void testUnknownDurationStillResolvesWhenNotPinnedToZero() {
        // #3524: tfra underestimates duration but still locates covered targets by byte.
        check(NativeSeekRules.canResolve(T, 280_000L, 290_000L),
                "unknown duration with a real (non-zero) sync point is trusted");
        check(NativeSeekRules.canResolve(T, 280_000L, 400_000L),
                "unknown duration cannot reject an in-map target; let the map resolve it");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
