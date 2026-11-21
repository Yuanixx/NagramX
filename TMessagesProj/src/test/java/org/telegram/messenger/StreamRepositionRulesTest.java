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
        testMultiDimensionalRestart();
        System.out.println("STREAM REPOSITION RULES: " + passed + " checks passed");
    }

    private static void testMultiDimensionalRestart() {
        final long slack = 4L * 1024 * 1024;
        // 完成文件永不重启。
        check(!StreamRepositionRules.needsRestart(100, 0, 100, false, true, slack),
                "completed file never restarts");
        // 后退到窗口之前：零洞回归，必须重启。
        check(StreamRepositionRules.needsRestart(100, 500_000, 10_000, true, false, slack),
                "backward seek into freed area restarts");
        // 远超 slack 的向前跳：重启。
        check(StreamRepositionRules.needsRestart(500_000_000, 0, 1000, true, false, slack),
                "far forward seek restarts");
        // 前方 slack 内、下载进行中：不重启，让在途下载追上。
        check(!StreamRepositionRules.needsRestart(1_000_000 + 1000, 0, 1_000_000, true, false, slack),
                "small forward nudge inside slack does not restart");
        // 窗口内连续读：不重启。
        check(!StreamRepositionRules.needsRestart(400_000, 0, 500_000, true, false, slack),
                "read inside window never restarts");
        // 边缘停滞：重启自救。
        check(StreamRepositionRules.needsRestart(500_000, 0, 500_000, false, false, slack),
                "stalled download at window edge restarts");
        // 恰好在窗口末端但下载仍活跃：不重启（下一字节即将到来）。
        check(!StreamRepositionRules.needsRestart(500_000, 0, 500_000, true, false, slack),
                "active download at window edge does not restart");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAIL: " + message);
        }
        passed++;
    }
}
