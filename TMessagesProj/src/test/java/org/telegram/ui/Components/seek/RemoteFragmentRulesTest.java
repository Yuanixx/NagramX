package org.telegram.ui.Components.seek;

/**
 * 纯 JVM 测试：远端片段选择与区间证明。
 * 验证“目标必须落在已证明区间内”以及“无后继片段时不得伪造跨度”这两条核心规则。
 */
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
        // 目标落在 20s~30s 片段之间，应返回 20s 的片段起点，而不是最近的 30s。
        check(RemoteFragmentRules.bestAtOrBefore(times, 4, 25 * S) == 2,
                "选择不晚于目标的最后一个片段");
        check(RemoteFragmentRules.bestAtOrBefore(times, 4, 20 * S) == 2,
                "目标正好等于片段起点时该片段可用");
        // 时间点 0 也是“目标之前”的片段，不能错误返回 -1。
        check(RemoteFragmentRules.bestAtOrBefore(times, 4, 5 * S) == 0,
                "起始时间为 0 的片段仍然在目标之前");
        long[] laterOnly = {10 * S, 20 * S, 30 * S};
        check(RemoteFragmentRules.bestAtOrBefore(laterOnly, 3, 5 * S) == -1,
                "所有片段都晚于目标时返回 -1");
    }

    private static void testSpan() {
        long[] times = {0L, 10 * S, 20 * S, 30 * S};
        check(RemoteFragmentRules.spanAfter(times, 4, 2) == 10 * S,
                "跨度取同一轨道下一个片段的时间差");
        check(RemoteFragmentRules.spanAfter(times, 4, 3) == -1L,
                "窗口内没有后继片段时跨度未知，必须失败");
        long[] duplicated = {0L, 10 * S, 10 * S, 20 * S};
        check(RemoteFragmentRules.spanAfter(duplicated, 4, 1) == 10 * S,
                "重复时间戳不得被当成更近的后继，跳过重复的 10s 取 20s");
        check(RemoteFragmentRules.spanAfter(times, 4, -1) == -1L,
                "无效索引没有跨度");
    }

    private static void testContainment() {
        // 区间是左闭右开：[start, start+span)。
        check(RemoteFragmentRules.containsTarget(20 * S, 10 * S, 20 * S),
                "目标等于起点时属于区间");
        check(RemoteFragmentRules.containsTarget(20 * S, 10 * S, 25 * S),
                "目标在区间内部");
        check(!RemoteFragmentRules.containsTarget(20 * S, 10 * S, 30 * S),
                "目标等于右端点不属于区间");
        check(!RemoteFragmentRules.containsTarget(20 * S, 10 * S, 31 * S),
                "目标晚于区间必须失败");
        check(!RemoteFragmentRules.containsTarget(20 * S, 0L, 20 * S),
                "跨度未知时不能证明覆盖");
        check(!RemoteFragmentRules.containsTarget(20 * S, -1L, 20 * S),
                "负跨度必须失败");
    }

    private static void testDecodeTimeOverflow() {
        check(RemoteFragmentRules.validDecodeTime(0L), "零 tfdt 有效");
        check(RemoteFragmentRules.validDecodeTime(Long.MAX_VALUE), "正常大 tfdt 有效");
        check(!RemoteFragmentRules.validDecodeTime(-1L),
                "u64 溢出成负数的 tfdt 必须拒绝");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
