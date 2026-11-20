/**
 * ZH-COMMENT-BY-AGENT
 * 文件：RemoteSeekProbe.java
 * 模块：TMessagesProj/src/main/java/org/telegram/ui/Components
 * 类型：RemoteSeekProbe（RemoteSeekProbe）、MoovBox（MoovBox）、Fragment（Fragment）、ProbeResult（Probe结果）
 * 主要成员：RemoteSeekProbe（RemoteSeekProbe）、ProbeResult（Probe结果）、find（查找）、currentTimeMillis（currentTimeMillis）、readMoov（读取Moov）、parseTrackConfig（解析TrackConfig）、min（min）、max（max）、alignDown（alignDown）、nearestFragment（nearestFragment）
 *          d（d）、e（e）、readFully（读取Fully）、findMoov（查找Moov）
 * 说明：本文件由中文注释引擎自动生成结构化说明；业务语义详见各成员处注释。
 */
/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 */
package org.telegram.ui.Components;

import androidx.media3.extractor.SeekPoint;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.seek.RemoteFragmentRules;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 远端 fMP4 片段定位器。
 *
 * <p>为什么需要它：Telegram 的长视频通常是分片 MP4，但文件里没有 sidx/mfra 索引。
 * media3 遇到没有索引的文件会把所有 seek 解析成 t=0，于是用户拖到未下载位置时
 * 看到“跳回开头”。本地索引只能覆盖已经下载的前缀，所以要到远端去读 moof 头，
 * 找到目标时间所属的片段字节位置，再把这个位置交给 media3 去按范围拉取数据。
 *
 * <p>核心不变量：本类只返回“经过证明的区间”，不返回猜测点。
 * 区间来自同一个视频轨道上相邻两个 moof 的 tfdt：设当前片段起始时间为 tStart，
 * 下一片段起始时间为 tNext，则本片段证明覆盖 [tStart, tNext)。
 * 只有 target 落在这个区间内，才可能返回 (tStart, moof 文件偏移)。
 * media3 拿到偏移后会从该 moof 开始读，并丢弃目标之前的数据；
 * 因此片段起点早于目标不会导致解错帧，但晚于目标一定会导致播放位置错乱。
 *
 * <p>失败语义：限流、超时、moov 解析失败、窗口内没有完整 moof、没有后继片段
 * （无法证明区间终点）、达到探测上限仍未收敛，统统返回 空值。调用方必须保留
 * “不归零”的兜底行为，绝不能把一次低精度探测当成成功。
 */
/**
 * 类：Remote定位到Probe，封装了相关的业务逻辑与数据处理。
 */
public final class RemoteSeekProbe {

    /** 读取 moov 时最多看文件头部这么多字节；moov 通常只有几千字节。 */
    private static final int LOCAL_MOOV_LIMIT = 256 * 1024;
    /** 每次探测抓取的远端窗口大小；1MB 足以覆盖常见片段的 moof + mdat 头部。 */
    private static final int PROBE_BYTES = 1024 * 1024;
    /**
     * 探测次数上限。它只是资源护栏，不是“成功条件”：
     * 次数用完仍未证明区间就必须失败，不能把最后一次最佳猜测返回出去。
     */
    private static final int MAX_PROBES = 6;
    /** 整个探测（不含调用方后续动作）的墙钟预算。 */
    private static final long TOTAL_BUDGET_MS = 12000L;
    /** 单次阻塞读取的超时。 */
    private static final long FETCH_TIMEOUT_MS = 2000L;
    /** 单次多块抓取的总预算。 */
    private static final long FETCH_BUDGET_MS = 3000L;

    /**
     * Remote定位到Probe。
     * @return private 类型的结果
     */
    private RemoteSeekProbe() {
    }

    /**
     * moov 盒子内容以及它在文件中的绝对偏移。
     * 偏移很重要：本地临时文件开头还有 ftyp，moov 不一定从 0 开始。
     */
    /**
     * 类：MoovBox，封装了相关的业务逻辑与数据处理。
     */
    private static final class MoovBox {
        byte[] data;
        long offset;
    }

    /**
     * 从远端窗口里解析出来的一个完整 moof 片段。
     * position 是 moof 盒子的文件偏移（ExoPlayer 要的就是这个位置），
     * 大小 是 moof 盒子长度，timeUs 是 tfdt 换算出的视频时间，
     * spanUs 是到同一轨道下一个片段的时间跨度（证明区间用），
     * end 是 moof 盒子结束位置，仅用于推进二分下界。
     */
    /**
     * 类：片段，封装了相关的业务逻辑与数据处理。
     */
    private static final class Fragment {
        long position;
        long end;
        long timeUs;
        long spanUs;
        int size;
    }

    /**
     * 探测成功的结果：一个 SeekPoint（片段起点）+ 该片段被证明的时间跨度 + 字节长度。
     * 调用方会再次校验区间是否包含目标、字节位置是否越过已下载区域，
     * 任一条件不满足就拒绝这个结果。
     */
    /**
     * 类：Probe结果，封装了相关的业务逻辑与数据处理。
     */
    public static final class ProbeResult {
        public final SeekPoint point;
        public final long spanUs;
        public final int size;

        ProbeResult(SeekPoint point, long spanUs, int size) {
            this.point = point;
            this.spanUs = spanUs;
            this.size = size;
        }
    }

    /**
     * 只能在后台线程调用：内部有阻塞的网络请求。
     *
     * @param 文档      文档，用于 FileRangeFetcher 取得带有效 file_reference 的定位
     * @param localPath     本地 .temp 路径；若存在则优先从它读 moov，省一次网络请求
     * @param fileLength    文档声明的完整长度，用来校验片段字节范围不越界
     * @param targetTimeUs  用户目标时间（微秒）
     * @param durationSec   媒体声明时长，仅用于参数合法性检查，绝不用来推算片段时间
     * @返回 证明成功的区间结果；任何不确定情况都返回 空值
     */
    public static ProbeResult find(int account, TLRPC.Document document,
                                   String localPath, long fileLength,
                                   long targetTimeUs, double durationSec) {
        // NaN/Infinity 会通过普通的 <=0 检查，然后在 (long) 转换时变成 0 或极值，
        // 造成“看起来合法的 0 时长”。这里必须显式拒绝。
        if (document == null || fileLength <= 0 || targetTimeUs <= 0
                || Double.isNaN(durationSec) || Double.isInfinite(durationSec)
                || durationSec <= 0) {
            return null;
        }
        final long started = System.currentTimeMillis();
        final long deadline = started + TOTAL_BUDGET_MS;
        try {
            // 第一步：拿到 moov，解析视频轨道的 timescale 与 trackId。
            // 没有这两个值就无法把 tfdt ticks 换算成时间，也无法区分视频/音频片段。
            MoovBox moov = readMoov(localPath, account, document, deadline);
            if (moov == null || System.currentTimeMillis() >= deadline) {
                return null;
            }
            MoovKeyframeParser.TrackInfo track = MoovKeyframeParser.parseTrackConfig(moov.data);
            if (track == null || track.timescale <= 0) {
                return null;
            }
            final long moovEnd = moov.offset + moov.data.length;
            final long trackId = track.trackId;
            final long timescale = track.timescale;

            // 第二步：在 [moovEnd, fileLength) 上按字节二分。
            // 注意：字节位置和时间不是线性关系（片段大小差异很大），
            // 所以二分只用来缩小字节搜索范围，是否命中靠每个窗口里的真实 tfdt 判断。
            long lo = Math.min(moovEnd, fileLength - 1);
            long hi = fileLength - 1;
            Fragment converged = null;
            int requests = 0;
            for (int probe = 0; probe < MAX_PROBES && lo < hi; probe++) {
                // 每次请求前重新计算剩余预算；rate limit 的等待也可能吃掉时间。
                final long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                final long guess = lo + (hi - lo) / 2L;
                final long start = Math.max(moovEnd, guess - PROBE_BYTES / 2L);
                final FileRangeFetcher.FetchResult result = FileRangeFetcher.fetchSyncUntil(
                        account, document, alignDown(start), PROBE_BYTES,
                        Math.min(FETCH_TIMEOUT_MS, remaining), deadline, null);
                requests++;
                if (result == null || !result.ok() || result.data == null) {
                    // 限流开关关闭、420、超时等：立即放弃，不做任何近似推断。
                    FileLog.d("FMPIDX: remote probe stopped status="
                            + (result != null ? result.status : "null")
                            + (result != null && result.status == FileRangeFetcher.FetchResult.Status.DISABLED
                                    ? " (network fetch off, using local index)" : ""));
                    return null;
                }
                final long dataOffset = alignDown(start);
                Fragment found = nearestFragment(result.data, dataOffset, fileLength,
                        timescale, trackId, targetTimeUs);
                if (found == null) {
                    // 窗口落在 mdat 中间或没有完整 moof：向前挪一个窗口继续找。
                    lo = Math.min(hi, dataOffset + result.data.length);
                    continue;
                }
                // 只有目标落在已证明区间 [timeUs, timeUs+spanUs) 内才算收敛。
                if (RemoteFragmentRules.containsTarget(found.timeUs, found.spanUs, targetTimeUs)) {
                    converged = found;
                    break;
                }
                if (found.timeUs <= targetTimeUs) {
                    // 当前片段早于目标（或区间不够长）：从片段尾部之后继续找。
                    lo = Math.min(hi, found.end);
                } else {
                    // 当前片段已经晚于目标：向左收窄上界。
                    hi = Math.max(lo, found.position - 1);
                }
            }

            if (converged == null) {
                // 未收敛：明确失败。绝不把“离目标最近的片段”当成成功结果。
                FileLog.d("FMPIDX: remote probe not converged target=" + targetTimeUs
                        + " probes=" + requests);
                return null;
            }
            FileLog.d("FMPIDX: remote probe pos=" + converged.position
                    + " t=" + converged.timeUs + " span=" + converged.spanUs
                    + " target=" + targetTimeUs + " probes=" + requests
                    + " ms=" + (System.currentTimeMillis() - started));
            return new ProbeResult(
                    new SeekPoint(converged.timeUs, converged.position),
                    converged.spanUs, converged.size);
        } catch (Throwable e) {
            // 任何解析异常都按“不可靠”处理，不能让异常路径返回半截结果。
            FileLog.e("FMPIDX: remote probe failed", e);
            return null;
        }
    }

    /** upload.getFile 要求偏移按 4096 对齐。 */
    private static long alignDown(long value) {
        return value - (value % 4096L);
    }

    /**
     * 读取 moov：本地临时文件存在且可读时优先读本地，省一次网络请求；
     * 否则用 FileRangeFetcher 抓头部块。两条路径都只返回“完整 moov 盒子”，
     * 半截盒子不能用于解析 track 配置。
     */
    /**
     * readMoov。
     * @param localPath 字符串类型的参数 localPath
     * @param account 整数类型的参数 account
     * @param document TLRPC.Document类型的参数 document
     * @return MoovBox 类型的结果
     */
    private static MoovBox readMoov(String localPath, int account, TLRPC.Document document,
                                    long deadlineMs) {
        if (localPath != null) {
            try {
                RandomAccessFile raf = new RandomAccessFile(new File(localPath), "r");
                try {
                    int len = (int) Math.min(LOCAL_MOOV_LIMIT, raf.length());
                    byte[] head = new byte[len];
                    raf.readFully(head);
                    MoovBox box = findMoov(head, 0);
                    if (box != null) {
                        return box;
                    }
                } finally {
                    raf.close();
                }
            } catch (Throwable ignore) {
                // 本地不可读不是错误：继续走远端分支。
            }
        }
        long remaining = deadlineMs - System.currentTimeMillis();
        if (remaining <= 0L) {
            return null;
        }
        FileRangeFetcher.FetchResult result = FileRangeFetcher.fetchSyncUntil(
                account, document, 0, LOCAL_MOOV_LIMIT,
                Math.min(FETCH_TIMEOUT_MS, remaining), deadlineMs, null);
        if (result == null || !result.ok() || result.data == null) {
            return null;
        }
        return findMoov(result.data, 0);
    }

    /**
     * 在头部字节里顺序走顶层 box，找到 moov 并返回其完整字节。
     * 大小==1 表示 64 位长度；大小==0 表示到文件末尾，这里不接受（无法证明完整）。
     */
    /**
     * 查找Moov。
     * @param data 数据（byte[]）
     * @param dataOffset 长整型类型的参数 dataOffset
     * @return MoovBox 类型的结果
     */
    private static MoovBox findMoov(byte[] data, long dataOffset) {
        int position = 0;
        while (position + 8 <= data.length) {
            long size = u32(data, position);
            int headerSize = 8;
            if (size == 1) {
                if (position + 16 > data.length) {
                    return null;
                }
                size = u64(data, position + 8);
                headerSize = 16;
            }
            if (size < headerSize || size > data.length - position) {
                return null;
            }
            if (isType(data, position + 4, "moov")) {
                MoovBox moov = new MoovBox();
                moov.data = new byte[(int) size];
                System.arraycopy(data, position, moov.data, 0, moov.data.length);
                moov.offset = dataOffset + position;
                return moov;
            }
            position += (int) size;
        }
        return null;
    }

    /**
     * 从一个探测窗口里解析所有“完整且属于视频轨道”的 moof。
     * 完整性判据：box 的 大小 字段必须完全落在本窗口内，且绝对位置 + 大小 不超过文件长度。
     * 这样可以避免把跨窗口被截断的 moof 当成有效片段，进而推导出错误跨度。
     */
    private static Fragment nearestFragment(byte[] data, long dataOffset, long fileLength,
                                             long timescale, long trackId, long targetTimeUs) {
        List<Fragment> fragments = new ArrayList<>();
        int position = 0;
        while (position + 8 <= data.length) {
            if (!isType(data, position + 4, "moof")) {
                position++;
                continue;
            }
            long size = u32(data, position);
            if (size < 8 || size > data.length - position) {
                position++;
                continue;
            }
            long absolute = dataOffset + position;
            if (absolute + size > fileLength) {
                position++;
                continue;
            }
            byte[] moof = new byte[(int) size];
            System.arraycopy(data, position, moof, 0, moof.length);
            long timeUs = fragmentTimeUs(moof, timescale, trackId);
            if (timeUs >= 0L) {
                Fragment f = new Fragment();
                f.position = absolute;
                f.end = absolute + size;
                f.size = (int) size;
                f.timeUs = timeUs;
                fragments.add(f);
            }
            position += (int) size;
        }
        if (fragments.isEmpty()) {
            return null;
        }
        long[] times = new long[fragments.size()];
        for (int i = 0; i < fragments.size(); i++) {
            times[i] = fragments.get(i).timeUs;
        }
        // 优先取不晚于目标的最后一个片段；窗口里全是更晚片段时，取最早的那个作为二分边界。
        int best = RemoteFragmentRules.bestAtOrBefore(times, times.length, targetTimeUs);
        if (best < 0) {
            best = 0;
            for (int i = 1; i < times.length; i++) {
                if (times[i] < times[best]) {
                    best = i;
                }
            }
        }
        Fragment chosen = fragments.get(best);
        // 跨度必须来自同一轨道的后继片段；没有后继或时间乱序时返回 -1，区间无法证明。
        chosen.spanUs = RemoteFragmentRules.spanAfter(times, times.length, best);
        return chosen;
    }

    /**
     * 解析 moof 中 traf 的 tfdt，换算成视频时间（微秒）。
     * 只接受 track_ID 与视频轨道匹配的 traf，避免把音频片段时间当成视频时间；
     * u64 解析出的 ticks 若为负（超过 Long.MAX_VALUE）则拒绝。
     */
    /**
     * 片段时间Us。
     * @param moof byte[]类型的参数 moof
     * @param timescale 长整型类型的参数 timescale
     * @param trackId 长整型类型的参数 trackId
     * @return 整型结果值
     */
    private static long fragmentTimeUs(byte[] moof, long timescale, long trackId) {
        int i = 0;
        while (i + 8 <= moof.length) {
            if (!isType(moof, i + 4, "traf")) {
                i++;
                continue;
            }
            long trafSize = u32(moof, i);
            if (trafSize < 8 || i + trafSize > moof.length) {
                i++;
                continue;
            }
            int end = (int) (i + trafSize);
            int p = i + 8;
            long foundTrackId = 0;
            long ticks = -1L;
            while (p + 8 <= end) {
                long childSize = u32(moof, p);
                if (childSize < 8 || p + childSize > end) {
                    break;
                }
                if (isType(moof, p + 4, "tfhd") && childSize >= 16) {
                    foundTrackId = u32(moof, p + 12);
                } else if (isType(moof, p + 4, "tfdt") && childSize >= 16) {
                    int version = moof[p + 8] & 0xff;
                    if (version == 1 && childSize >= 20) {
                        ticks = u64(moof, p + 12);
                    } else {
                        ticks = u32(moof, p + 12);
                    }
                }
                p += (int) childSize;
            }
            if (RemoteFragmentRules.validDecodeTime(ticks)
                    && (trackId <= 0 || foundTrackId == trackId)) {
                return (long) (ticks * 1000000.0 / timescale);
            }
            i = end;
        }
        return -1L;
    }

    /** 比较 box 类型字段（大小字段在前 4 字节，类型在随后 4 字节）。 */
    private static boolean isType(byte[] data, int offset, String type) {
        if (offset < 0 || offset + 4 > data.length) {
            return false;
        }
        return (data[offset] & 0xff) == type.charAt(0)
                && (data[offset + 1] & 0xff) == type.charAt(1)
                && (data[offset + 2] & 0xff) == type.charAt(2)
                && (data[offset + 3] & 0xff) == type.charAt(3);
    }

    /** 读取 32 位无符号整数，返回 long 以避免 Java 有符号 int 导致负值。 */
    private static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    /**
     * 读取 64 位无符号整数。若最高位为 1，返回的 long 会是负数；
     * 调用方必须用 RemoteFragmentRules.validDecodeTime 拒绝它。
     */
    /**
     * u64。
     * @param data 数据（byte[]）
     * @param offset 偏移（整数）
     * @return 整型结果值
     */
    private static long u64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (long) (data[offset + i] & 0xff);
        }
        return value;
    }
}
