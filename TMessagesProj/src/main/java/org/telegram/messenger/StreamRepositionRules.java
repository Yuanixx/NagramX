/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.telegram.messenger;

/** Pure decision rule for moving an active, sparse-file Telegram stream. */
final class StreamRepositionRules {

    private StreamRepositionRules() {
    }

    static boolean shouldCancelInFlightRequests(boolean alreadyStarted, boolean hasStream,
                                                boolean streamPriority, boolean hasSparseRanges,
                                                long previousAlignedOffset, long nextAlignedOffset) {
        return alreadyStarted && hasStream && !streamPriority && hasSparseRanges
                && previousAlignedOffset != nextAlignedOffset;
    }

    /**
     * 多维度下的"是否需要把下载重新瞄准到目标字节"纯判定，可 JVM 单测。
     *
     * 借鉴 TMPlayer 的 DownloadWindow.needsRestart 判据（GPL-3.0，干净室重写，
     * 仅采用判据本身，未复制代码），把分散在对齐网格、停滞超时和完成态里的
     * 重启条件收敛成一个可测试的不变量：
     * - 已完成整文件永不重启；
     * - 目标落在下载窗口之前（后退 seek）必须重启，否则读到被释放区域的零洞
     *   （TMPlayer 历史故障 a6de327）；
     * - 目标超出窗口末端超过 forwardSlack（运行中的下载追不上）则重启；
     * - 目标卡在窗口边缘且下载停滞（active=false）则重启自救；
     * - 其余（窗口内、或前方 slack 内可被追上）不重启，避免丢弃在途分片。
     * 所有字节用同一坐标系传入，调用方负责对齐。
     */
    static boolean needsRestart(long position, long windowStart, long availableLength,
                                boolean active, boolean completed, long forwardSlack) {
        if (completed) {
            return false;
        }
        if (position < windowStart) {
            return true;
        }
        final long windowEnd = windowStart + Math.max(0L, availableLength);
        if (position > windowEnd + Math.max(0L, forwardSlack)) {
            return true;
        }
        if (!active && position >= windowEnd) {
            return true;
        }
        return false;
    }
}
