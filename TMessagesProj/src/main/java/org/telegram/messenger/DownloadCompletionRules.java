/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.telegram.messenger;

/**
 * 下载完成态的纯判定规则（与下载源、Android、文件系统都无关，可在 JVM 单测）。
 *
 * 为什么需要它：
 * 下载计数器（downloadedBytes）和"缺口区间列表为空"都只是下载器的账面状态，
 * 它们可能和磁盘上真实存在的字节数不一致。Telegram 的临时文件是稀疏写入的，
 * 下载被中断、文件被裁剪或重建、缓存移动之后，账面"完成"但磁盘实际变短的情况
 * 是真实存在的。此时如果把 complete 标成 true，可读区间会被放开到整个文件，
 * 播放器据此去读那些从未落盘的字节，读到的全是零值洞，解码器拿到零数据就会
 * 报错并从头开始播放——这正是"拖到没下载好的位置又跳回开头"的一类根因。
 *
 * 因此"完成"必须同时满足：账面没有缺口、账面字节数达到声明大小、
 * 以及磁盘实际长度不小于声明大小。任一项不满足就宁可判为"未完成"，
 * 让读取走保守的缺口/等待路径。
 *
 * 该规则借鉴 TMPlayer 的 LocalFilePolicy 思路（GPL-3.0，干净室重写，
 * 仅采用"实际长度必须达到声明全尺寸才认完成"这一判据，未复制其任何代码）。
 */
final class DownloadCompletionRules {

    private DownloadCompletionRules() {
    }

    /**
     * 是否在磁盘上真正完整。
     *
     * @param noMissingRanges      账面缺口区间列表是否为空
     * @param downloadedBytes      下载器账面已下载字节数
     * @param declaredBytes        文件声明大小（totalBytesCount），<=0 视为未知
     * @param diskLength           磁盘上临时/正式文件的实际长度，<0 表示无法读取
     */
    static boolean isComplete(boolean noMissingRanges, long downloadedBytes,
                              long declaredBytes, long diskLength) {
        if (!noMissingRanges) {
            return false;
        }
        // 连声明大小都不知道，就没有依据宣布完成。
        if (declaredBytes <= 0) {
            return false;
        }
        // 账面字节数没达到声明大小，不算完成。
        if (downloadedBytes < declaredBytes) {
            return false;
        }
        // 关键交叉校验：磁盘长度未知或短于声明大小，一律不算完成。
        // 这正是防止"账面完成、磁盘是空洞"被当成可读整段的那道闸。
        // Telegram 原生模型里声明大小就是可信全尺寸，没有第二个 expectedSize。
        if (diskLength < declaredBytes) {
            return false;
        }
        return true;
    }
}
