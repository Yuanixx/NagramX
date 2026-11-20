package org.telegram.ui.Components.seek;

/** Plain-JVM tests for one-shot, owner-bound deferred seek replay. */
public final class PendingSeekRulesTest {

    private static int passed;
    private static final Object A = new Object();
    private static final Object B = new Object();

    public static void main(String[] args) {
        testDeferGate();
        testOwnerBinding();
        testReplayIsOneShotByCaller();
        System.out.println("PENDING SEEK: " + passed + " checks passed");
    }

    private static void testDeferGate() {
        check(PendingSeekRules.shouldDefer(true, 1000L, false), "uncovered streaming seek defers");
        check(!PendingSeekRules.shouldDefer(true, 1000L, true), "covered streaming seek does not defer");
        check(!PendingSeekRules.shouldDefer(false, 1000L, false), "local seek does not defer");
        check(!PendingSeekRules.shouldDefer(true, 0L, false), "zero target does not defer");
    }

    private static void testOwnerBinding() {
        check(PendingSeekRules.canConsume(true, A, A, 3, 3, 7L, 7L),
                "rebuilt owner with matching generation consumes");
        check(!PendingSeekRules.canConsume(false, A, A, 3, 3, 7L, 7L),
                "premature flag does not consume");
        check(!PendingSeekRules.canConsume(true, null, A, 3, 3, 7L, 7L),
                "missing owner does not consume");
        check(!PendingSeekRules.canConsume(true, B, A, 3, 3, 7L, 7L),
                "old player instance does not consume");
        check(!PendingSeekRules.canConsume(true, A, A, 2, 3, 7L, 7L),
                "stale generation does not consume");
        check(!PendingSeekRules.canConsume(true, A, A, 3, 3, 8L, 7L),
                "different document does not consume");
    }

    private static void testReplayIsOneShotByCaller() {
        // The rule is pure; the caller clears the pending fields after consuming. These
        // assertions make a "make the rule mutate state" regression visible.
        check(PendingSeekRules.canConsume(true, A, A, 1, 1, 1L, 1L), "first read allowed");
        check(PendingSeekRules.canConsume(true, A, A, 1, 1, 1L, 1L), "rule itself does not mutate");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
