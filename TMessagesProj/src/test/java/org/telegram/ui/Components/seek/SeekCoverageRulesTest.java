package org.telegram.ui.Components.seek;

/** 纯 JVM 测试：区分媒体声明时长、本地索引覆盖和远端片段覆盖，不依赖 Android。 */
public final class SeekCoverageRulesTest {

    private static int passed;

    public static void main(String[] args) {
        testLocalCoverage();
        testRemoteCoverageIsAnInterval();
        testNoFixedTolerance();
        testProbeValidation();
        testCoveredBytesIsNotCoveredTime();
        testInvalidIndexRejected();
        System.out.println("SEEK COVERAGE: " + passed + " checks passed");
    }

    private static void testLocalCoverage() {
        // 覆盖终点 = 最后一个 chunk 的起始时间 + 该 chunk 的时长，而不是单纯的起始时间。
        long[] times = {0L, 10_000_000L, 20_000_000L};
        long[] durations = {10_000_000L, 10_000_000L, 5_000_000L};
        check(SeekCoverageRules.lastChunkStartUs(times, 3) == 20_000_000L, "最后 chunk 起始时间");
        check(SeekCoverageRules.coveredEndUs(times, durations, 3) == 25_000_000L, "覆盖终点包含最后 chunk 时长");
        check(SeekCoverageRules.coversLocal(times, durations, 3, 0L), "起点被覆盖");
        check(SeekCoverageRules.coversLocal(times, durations, 3, 25_000_000L), "覆盖终点边界内");
        check(!SeekCoverageRules.coversLocal(times, durations, 3, 25_000_001L), "超出覆盖终点不被本地承担");
    }

    private static void testRemoteCoverageIsAnInterval() {
        // 远端定位必须是一个左闭右开区间，不能当成无限有效的单点。
        check(SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 20_000_000L), "区间起点");
        check(SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 25_000_000L), "区间内部");
        check(SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 29_999_999L), "终点前一微秒");
        check(!SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 30_000_000L), "右开区间不含终点");
        check(!SeekCoverageRules.coversRemote(20_000_000L, 30_000_000L, 19_999_999L), "早于区间");
        check(!SeekCoverageRules.coversRemote(0L, 0L, 1L), "空区间不覆盖任何时间");
    }

    private static void testNoFixedTolerance() {
        // 旧实现的 ±20s 常量会错误接受这个目标；区间覆盖必须拒绝。
        check(!SeekCoverageRules.coversRemote(0L, 5_000_000L, 19_000_000L),
                "固定 20 秒容差不是覆盖证明");
        check(SeekCoverageRules.coversRemote(0L, 25_000_000L, 19_000_000L),
                "当片段区间确实覆盖时同一目标才有效");
    }

    private static void testProbeValidation() {
        check(SeekCoverageRules.validProbe(20L, 30L, 100L, 50L, 1000L, 80L),
                "有效的前向片段");
        check(!SeekCoverageRules.validProbe(20L, 30L, 70L, 50L, 1000L, 80L),
                "本地字节覆盖之前的片段不算前进");
        check(!SeekCoverageRules.validProbe(20L, 30L, 100L, 0L, 1000L, 80L),
                "零长度片段无效");
        check(!SeekCoverageRules.validProbe(20L, 30L, 100L, 950L, 1000L, 80L),
                "超过文件末尾的片段无效");
    }

    private static void testCoveredBytesIsNotCoveredTime() {
        int[] sizes = {100, 200};
        long[] offsets = {1000L, 1300L};
        check(SeekCoverageRules.coveredBytes(sizes, offsets, 2) == 1500L, "字节覆盖终点 = 偏移 + 大小");
        check(SeekCoverageRules.coveredBytes(sizes, offsets, 0) == -1L, "无索引则无字节覆盖");
        check(!SeekCoverageRules.validProbe(20_000_000L, 30_000_000L, 1400L, 50L, 100_000_000L, 1500L),
                "字节边界之前的片段被拒绝");
        check(SeekCoverageRules.validProbe(20_000_000L, 30_000_000L, 1600L, 50L, 100_000_000L, 1500L),
                "字节边界之后的片段被接受");
    }

    private static void testInvalidIndexRejected() {
        check(SeekCoverageRules.coveredEndUs(null, null, 0) == -1L, "空索引无效");
        check(SeekCoverageRules.coveredEndUs(new long[]{0L, 5L}, new long[]{1L, 1L}, 3) == -1L,
                "长度越界无效");
        check(SeekCoverageRules.coveredEndUs(new long[]{5L, 1L}, new long[]{1L, 1L}, 2) == -1L,
                "时间戳乱序拒绝");
        check(SeekCoverageRules.coveredEndUs(new long[]{0L, Long.MAX_VALUE},
                new long[]{1L, 1L}, 2) == -1L, "溢出拒绝");
        check(SeekCoverageRules.coveredBytes(new int[]{10, 10}, new long[]{100L, 50L}, 2) == -1L,
                "字节偏移乱序拒绝");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) {
            throw new AssertionError("FAIL: " + msg);
        }
        passed++;
    }
}
