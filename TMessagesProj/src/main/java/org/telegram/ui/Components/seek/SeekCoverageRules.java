/**
 * ZH-COMMENT-BY-AGENT
 * 文件：SeekCoverageRules.java
 * 模块：TMessagesProj/src/main/java/org/telegram/ui/Components/seek
 * 类型：SeekCoverageRules（SeekCoverageRules）
 * 主要成员：SeekCoverageRules（SeekCoverageRules）、lastChunkStartUs（lastChunk启动Us）、coveredEndUs（coveredEndUs）、coversLocal（coversLocal）、coveredBytes（coveredBytes）、validTimes（validTimes）、coversRemote（coversRemote）、validProbe（validProbe）
 * 说明：本文件由中文注释引擎自动生成结构化说明；业务语义详见各成员处注释。
 */
package org.telegram.ui.Components.seek;

/**
 * Pure rules 该 distinguish  three different time ranges involved 在  seek:
 *  declared 媒体 duration,  range covered 由  local partial 索引, 和 
 * range proved 由  remote fragment probe. No Android 或 media3 dependency here so
 *  exact production logic can 为 exercised 由 plain JVM tests.
 */
public final class SeekCoverageRules {

    private SeekCoverageRules() {
    }

    /** 启动 time 的  last indexed chunk; -1 when no valid 索引 exists. */
    public static long lastChunkStartUs(long[] timesUs, int length) {
        if (!validTimes(timesUs, length)) {
            return -1L;
        }
        return timesUs[length - 1];
    }

    /**
     * End 的  locally covered time range: last chunk 启动 plus 其 duration.
     *  chunk 启动 alone 是 不  covered end. -1 means no valid coverage.
     */
    public static long coveredEndUs(long[] timesUs, long[] durationsUs, int length) {
        if (!validTimes(timesUs, length) || durationsUs == null
                || durationsUs.length < length || length <= 0) {
            return -1L;
        }
        long last = timesUs[length - 1];
        long duration = durationsUs[length - 1];
        if (duration < 0L || last > Long.MAX_VALUE - duration) {
            return -1L;
        }
        return last + duration;
    }

    /** Local 索引 can resolve  time only inside 其 proved covered range. */
    public static boolean coversLocal(long[] timesUs, long[] durationsUs, int length,
                                      long timeUs) {
        long end = coveredEndUs(timesUs, durationsUs, length);
        return end >= 0L && timeUs >= 0L && timeUs <= end;
    }

    /** End 的  covered bytes: last chunk offset plus 其 大小; -1 when no valid 索引. */
    public static long coveredBytes(int[] sizes, long[] offsets, int length) {
        if (sizes == null || offsets == null || length <= 0
                || length > sizes.length || length > offsets.length) {
            return -1L;
        }
        long previous = -1L;
        for (int i = 0; i < length; i++) {
            if (offsets[i] < 0L || sizes[i] <= 0 || offsets[i] < previous) {
                return -1L;
            }
            if (offsets[i] > Long.MAX_VALUE - sizes[i]) {
                return -1L;
            }
            previous = offsets[i] + sizes[i];
        }
        return previous;
    }

    private static boolean validTimes(long[] timesUs, int length) {
        if (timesUs == null || length <= 0 || length > timesUs.length) {
            return false;
        }
        long previous = -1L;
        for (int i = 0; i < length; i++) {
            if (timesUs[i] < 0L || timesUs[i] < previous) {
                return false;
            }
            previous = timesUs[i];
        }
        return true;
    }

    /**  remote point proves  single fragment, never  whole remaining timeline. */
    public static boolean coversRemote(long startUs, long endUs, long timeUs) {
        return endUs > startUs && startUs >= 0L && timeUs >= startUs && timeUs < endUs;
    }

    /**
     *  probed fragment 是 usable only 如果 它 是 inside  文件 和 ahead 的  locally
     * covered BYTE range. Never pass  microsecond 值 作为  last 参数.
     */
    public static boolean validProbe(long startUs, long endUs, long position, long size,
                                     long fileLength, long coveredEndBytes) {
        return endUs > startUs
                && startUs >= 0L
                && size > 0L
                && position >= coveredEndBytes
                // 先检查加法溢出，再和文件长度比较，避免超大 size 绕过边界检查。
                && position <= Long.MAX_VALUE - size
                && position + size <= fileLength;
    }
}
