package org.telegram.ui.Components.seek;

/** 纯规则：远端片段选择、时间区间证明与拒绝条件。 */
public final class RemoteFragmentRules {

    private RemoteFragmentRules() {
    }

    /**
     * 选择“起始时间不晚于目标”的最后一个片段。
     * 返回 -1 表示窗口里没有可用片段；晚于目标的片段只能作为二分边界，不能作为落点。
     */
    public static int bestAtOrBefore(long[] timesUs, int count, long targetUs) {
        int best = -1;
        for (int i = 0; i < count; i++) {
            if (timesUs[i] <= targetUs && (best < 0 || timesUs[i] > timesUs[best])) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 用同一视频轨道的下一个片段时间推导区间跨度。
     * 窗口内没有后继、时间重复或乱序时返回 -1：跨度无法证明，探测必须明确失败。
     */
    public static long spanAfter(long[] timesUs, int count, int index) {
        if (index < 0 || index >= count || count <= 0) {
            return -1L;
        }
        long next = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (timesUs[i] <= timesUs[index]) {
                continue;
            }
            if (next == Long.MAX_VALUE || timesUs[i] < next) {
                next = timesUs[i];
            }
        }
        if (next == Long.MAX_VALUE || next <= timesUs[index]) {
            return -1L;
        }
        return next - timesUs[index];
    }

    /** 目标必须落在已证明的左闭右开区间内，不能只靠“离起点不太远”。 */
    public static boolean containsTarget(long startUs, long spanUs, long targetUs) {
        return spanUs > 0L && targetUs >= startUs && targetUs < startUs + spanUs;
    }

    /** u64 解析出的 tfdt 若溢出成负数，必须拒绝而不是当成有效解码时间。 */
    public static boolean validDecodeTime(long ticks) {
        return ticks >= 0L;
    }
}
