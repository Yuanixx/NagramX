/**
 * ZH-COMMENT-BY-AGENT
 * 文件：Mp4IndexExtractorsFactory.java
 * 模块：TMessagesProj/src/main/java/org/telegram/ui/Components
 * 类型：Mp4IndexExtractorsFactory（Mp4索引ExtractorsFactory）、ChunkIndexInjectingExtractor（Chunk索引InjectingExtractor）、RemoteExtendedSeekMap（RemoteExtendedSeekMap）
 * 主要成员：DefaultExtractorsFactory（DefaultExtractorsFactory）、setSeekTarget（设置SeekTarget）、Mp4IndexExtractorsFactory（Mp4索引ExtractorsFactory）、createExtractors（创建Extractors）、hasIndex（是否含有索引）、canSeekTo（能否SeekTo）、coversRemote（coversRemote）、index（索引）、synchronized（synchronized）、lookup（lookup）
 *          w（w）、build（构建）、coveredEndUs（coveredEndUs）、find（查找）
 * 说明：本文件由中文注释引擎自动生成结构化说明；业务语义详见各成员处注释。
 */
package org.telegram.ui.Components;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.extractor.ChunkIndex;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackAwareSeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;

import org.telegram.messenger.FragmentIndexBuilder;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.seek.NativeSeekMapRules;
import org.telegram.ui.Components.seek.SeekCoverageRules;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 每个播放器实例一个的 extractors 工厂。
 *
 * 为什么包装 ExtractorOutput：media3 没有旧 fork 的 setChunkIndex 钩子，
 * 只能在 extractor 提交 seekMap 时，把库的 fallback（不可 seek）替换成我们准备好的索引。
 * 文件自带真实 sidx 或 mfra/tfra 时优先保留 Media3 的原生 SeekMap，因为它比推导索引更权威。
 *
 * 三个时间概念必须严格分开，混用就会重现“拖到未下载位置跳回开头”：
 * 1) declaredDurationUs：媒体声明的完整时长，只用于显示和时间轴，不代表能 seek 到任意位置；
 * 2) localCoveredEndUs：本地分片索引实际覆盖的时间终点（最后 chunk 起点 + 时长）；
 * 3) remoteProbeInterval：远端探测证明过的 [起点, 终点) 区间，只对目标所在的那个片段有效。
 *
 * 索引在 ExoPlayer 的 loader 线程（createExtractors）里惰性构建，绝不在 UI 线程扫描或联网。
 * PhotoViewer 每次 prepare 新建一个工厂，探测结果只属于这一次 prepare 的目标。
 */
public final class Mp4IndexExtractorsFactory implements ExtractorsFactory {

    private static final String TAG = "FMPIDX";

    private final DefaultExtractorsFactory delegate = new DefaultExtractorsFactory();

    @Nullable
    private final ChunkIndex prebuiltIndex;
    @Nullable
    private final String path;
    private final long docId;
    private final long docSize;
    private final double expectedDurationSec;
    private final boolean notLoading;

    @Nullable
    private volatile ChunkIndex resolvedIndex;
    private volatile boolean attempted;

    /** Native SIDX/MFRA maps emitted by Media3 outrank inferred fallbacks. */
    @Nullable
    private volatile SeekMap nativeSeekMap;

    @Nullable
    private TLRPC.Document seekDocument;
    private int seekAccount;
    private long seekTargetTimeUs;
    private double seekDurationSec;
    @Nullable
    private volatile SeekPoint remotePoint;
    @Nullable
    private volatile SeekPoint remoteNextPoint;
    private volatile long remoteSpanUs;

    /**
     * 为本次 prepare 设置需要探测的远端目标。必须在第一次 createExtractors 之前调用。
     *
     * 为什么由 PhotoViewer 传入而不是工厂自己猜：目标来自用户拖动/续播恢复，
     * 属于 PhotoViewer 的待重放 seek；工厂只在 loader 线程使用它发探测请求。
     * 所有网络工作都留在 索引() 里，UI 线程不会阻塞。
     */
    public void setSeekTarget(@Nullable TLRPC.Document document, int account,
                              long targetTimeUs, double durationSec) {
        this.seekDocument = document;
        this.seekAccount = account;
        this.seekTargetTimeUs = targetTimeUs;
        this.seekDurationSec = durationSec;
    }

    /** 立即模式：调用方已经构建好索引时直接传入，不再扫描或探测。 */
    public Mp4IndexExtractorsFactory(@Nullable ChunkIndex prebuiltIndex) {
        this.prebuiltIndex = prebuiltIndex;
        this.path = null;
        this.docId = 0;
        this.docSize = 0;
        this.expectedDurationSec = 0;
        this.notLoading = false;
        this.resolvedIndex = prebuiltIndex;
        this.attempted = true;
    }

    /** Lazy mode:  scan runs 在  loader 线程 inside createExtractors. */
    public Mp4IndexExtractorsFactory(@Nullable String path, long docId, long docSize,
                                     double expectedDurationSec, boolean notLoading) {
        this.prebuiltIndex = null;
        this.path = path;
        this.docId = docId;
        this.docSize = docSize;
        this.expectedDurationSec = expectedDurationSec;
        this.notLoading = notLoading;
    }

    @Override
    public Extractor[] createExtractors() {
        return stamp(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return stamp(delegate.createExtractors(uri, responseHeaders));
    }

    /**
     * 无副作用的查询：本次 prepare 是否已经拿到可用的 seek 映射。
     * 不能在这里触发探测，否则 UI 线程会被网络请求阻塞。
     */
    public boolean hasIndex() {
        // Native SIDX and MFRA maps are both real time-to-byte mappings. MFRA is
        // TrackAwareSeekMap; its default getSeekPoints() selects the primary video track.
        SeekMap nativeMap = nativeSeekMap;
        boolean hasNative = nativeMap instanceof TrackAwareSeekMap && nativeMap.isSeekable();
        return NativeSeekMapRules.hasManagedIndex(attempted, resolvedIndex != null,
                remotePoint != null, hasNative);
    }

    /**
     * 当前映射能否把目标时间解析成真实字节位置。
     * 优先接受 Media3 原生完整索引；没有原生索引时，本地只认实际覆盖区间，
     * 远端只认探测证明过的左闭右开区间，不使用固定秒数容差。
     */
    public boolean canSeekTo(long timeUs) {
        if (!attempted) {
            return false;
        }
        // A passed-through native map is usable only when it resolves this target
        // to non-negative byte positions. MFRA's timeUs=0 before its first TFRA entry
        // is a timestamp; the actual byte position is firstMediaDataPosition.
        SeekMap nativeMap = nativeSeekMap;
        if (nativeMap instanceof TrackAwareSeekMap && nativeMap.isSeekable()) {
            SeekMap.SeekPoints points = nativeMap.getSeekPoints(timeUs);
            if (NativeSeekMapRules.hasUsableNativeSeekPoints(
                    true, true, true, points.first.position, points.second.position)) {
                return true;
            }
        }
        ChunkIndex local = resolvedIndex;
        if (SeekCoverageRules.coversLocal(local != null ? local.timesUs : null,
                local != null ? local.durationsUs : null,
                local != null ? local.length : 0, timeUs)) {
            return true;
        }
        SeekPoint remote = remotePoint;
        return remote != null && SeekCoverageRules.coversRemote(
                remote.timeUs, remote.timeUs + remoteSpanUs, timeUs);
    }

    /**
     * 惰性解析索引：只在 loader 线程执行一次（attempted 保证幂等）。
     *
     * 顺序：先按文档 id 查持久化索引（文件删掉后仍可用），再扫描本地临时文件；
     * 都拿不到可用覆盖时才联网探测目标片段。
     *
     * 注意 resolvedIndex 可能为 空值（没有任何索引），此时不能把“没有索引”缓存成
     * “可 seek”——canSeekTo 会如实返回 假，调用方因此延迟 seek 而不是交给 media3 归零。
     */
    @Nullable
    private ChunkIndex index() {
        if (prebuiltIndex != null) {
            return prebuiltIndex;
        }
        if (!attempted) {
            synchronized (this) {
                if (!attempted) {
                    // 1) 先按文档身份查持久化索引。
                    // 只要文档 id 不变，即使本地文件被删掉，这份索引仍然可用；
                    // 此时 seek 只是把时间映射到文件字节偏移，不依赖本地是否还有数据。
                    ChunkIndex idx = FragmentIndexBuilder.lookup(docId, docSize, expectedDurationSec);
                    if (idx != null) {
                        Log.w(TAG, "index source=persisted chunks=" + idx.length + " doc=" + docId);
                    } else if (path != null) {
                        // 2) 没有持久化索引时扫描本地文件。
                        // 对已下载完整的文件会构建完整索引并落盘，
                        // 这样之后同一个文档再播放时可以直接查到，不必重复扫描。
                        idx = FragmentIndexBuilder.build(path, docId, docSize,
                                expectedDurationSec, notLoading);
                        Log.w(TAG, "index source=scan result="
                                + (idx != null ? "chunks=" + idx.length : "null") + " doc=" + docId);
                    } else {
                        Log.w(TAG, "index missing doc=" + docId + " size=" + docSize
                                + " (no persisted copy, no local file)");
                    }
                    // 本地索引的时间覆盖终点与字节覆盖边界必须分别计算，绝不能混用单位。
                    // 目标超出时间覆盖才启动远端探测；探测结果还要通过字节边界校验。
                    final long coveredEndUs = SeekCoverageRules.coveredEndUs(
                            idx != null ? idx.timesUs : null,
                            idx != null ? idx.durationsUs : null,
                            idx != null ? idx.length : 0);
                    if (seekTargetTimeUs > 0 && seekDocument != null
                            && (idx == null || seekTargetTimeUs > coveredEndUs)) {
                        RemoteSeekProbe.ProbeResult probed = RemoteSeekProbe.find(
                                seekAccount, seekDocument, path, docSize,
                                seekTargetTimeUs, seekDurationSec);
                        // 只有“目标落在证明区间内 + 片段在文件内且位于本地已覆盖字节之后”
                        // 才接受远端结果；否则明确拒绝，绝不把低精度猜测伪装成成功。
                        if (probed != null && probed.point != null) {
                            long start = probed.point.timeUs;
                            long end = start + probed.spanUs;
                            final long coveredEndBytes = SeekCoverageRules.coveredBytes(
                                    idx != null ? idx.sizes : null,
                                    idx != null ? idx.offsets : null,
                                    idx != null ? idx.length : 0);
                            if (SeekCoverageRules.coversRemote(start, end, seekTargetTimeUs)
                                    && SeekCoverageRules.validProbe(start, end,
                                            probed.point.position, probed.size,
                                            docSize, Math.max(0L, coveredEndBytes))) {
                                remotePoint = probed.point;
                                remoteNextPoint = probed.nextPoint;
                                remoteSpanUs = probed.spanUs;
                                Log.w(TAG, "remote probe accepted start=" + start
                                        + " span=" + probed.spanUs + " pos=" + probed.point.position);
                            } else {
                                Log.w(TAG, "remote probe rejected (no reliable coverage) target="
                                        + seekTargetTimeUs + " start=" + start + " span=" + probed.spanUs);
                            }
                        }
                    }
                    resolvedIndex = idx;
                    attempted = true;
                }
            }
        }
        return resolvedIndex;
    }

    /**
     * 给DefaultExtractorsFactory 产出的每个 FragmentedMp4Extractor 套一层包装。
     * 即使 索引 和远端点都为空也要包装，这样有索引和无索引走完全相同的代码路径，
     * 避免“某条路径忘了注入”导致静默归零。
     */
    private Extractor[] stamp(Extractor[] extractors) {
        ChunkIndex idx = index();
        int n = 0;
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof FragmentedMp4Extractor) {
                // Always wrap, even 使用  空值 索引: keeps  path identical 用于 both cases.
                extractors[i] = new ChunkIndexInjectingExtractor(extractors[i], idx, remotePoint,
                        remoteNextPoint, remoteSpanUs, expectedDurationSec);
                n++;
            }
        }
        Log.w(TAG, "stamp fmp4=" + n + " idx="
                + (idx != null ? "chunks=" + idx.length : "null"));
        return extractors;
    }

    private final class ChunkIndexInjectingExtractor implements Extractor {

        private final Extractor delegate;
        @Nullable
        private final ChunkIndex chunkIndex;
        @Nullable
        private final SeekPoint remotePoint;
        @Nullable
        private final SeekPoint remoteNextPoint;
        private final long remoteSpanUs;
        private final double durationSec;

        ChunkIndexInjectingExtractor(Extractor delegate, @Nullable ChunkIndex chunkIndex,
                                     @Nullable SeekPoint remotePoint, @Nullable SeekPoint remoteNextPoint,
                                     long remoteSpanUs, double durationSec) {
            this.delegate = delegate;
            this.chunkIndex = chunkIndex;
            this.remotePoint = remotePoint;
            this.remoteNextPoint = remoteNextPoint;
            this.remoteSpanUs = remoteSpanUs;
            this.durationSec = durationSec;
        }

        @Override
        public void init(ExtractorOutput output) {
            // Always intercept the callback: Media3 may emit a usable MFRA map even
            // when StreamX has no local index and remote probing did not run.
            delegate.init(new ExtractorOutput() {
                @Override
                public TrackOutput track(int id, int type) {
                    return output.track(id, type);
                }

                @Override
                public void endTracks() {
                    output.endTracks();
                }

                /**
                 * extractor 提交 seekMap 时做分流：
                 * - 真实 sidx 的 ChunkIndex 原样放行；
                 * - MFRA 的 MfraSeekMap 也原样放行：它实现 TrackAwareSeekMap，
                 *   getSeekPoints() 返回所选主轨道的实际随机访问 moof 偏移；
                 * - 其他未知的 seekable map 仍然拦截，避免未经验证的偏移进入 DataSource。
                 */
                @Override
                public void seekMap(SeekMap seekMap) {
                    final boolean passThrough = NativeSeekMapRules.shouldPassThrough(
                            seekMap instanceof ChunkIndex,
                            seekMap instanceof TrackAwareSeekMap,
                            seekMap.isSeekable());
                    if (passThrough) {
                        // 真实 sidx ChunkIndex：原样放行。
                        nativeSeekMap = seekMap;
                        // 状态一致性：canSeekTo/hasIndex 只看 resolvedIndex/remotePoint，
                        // 若不同步，真实索引已交给播放器却仍被判"不可 seek"，会无谓重建。
                        // 放行的可信 ChunkIndex 同步为自管状态源（与本地扫描索引同字段）。
                        if (NativeSeekMapRules.syncResolvedFromNative(
                                passThrough, seekMap instanceof ChunkIndex)
                                && seekMap instanceof ChunkIndex) {
                            resolvedIndex = (ChunkIndex) seekMap;
                        }
                        output.seekMap(seekMap);
                        return;
                    }
                    // This callback replaces the previous map. Never let a stale native
                    // mapping authorize a seek after a different map was emitted.
                    nativeSeekMap = null;
                    if (remotePoint != null) {
                        output.seekMap(new RemoteExtendedSeekMap(chunkIndex, remotePoint,
                                remoteNextPoint, remoteSpanUs, (long) (durationSec * 1000000.0)));
                    } else if (chunkIndex != null) {
                        output.seekMap(chunkIndex);
                    } else if (seekMap.isSeekable()) {
                        // Unknown seekable map: do not pass through an unclassified
                        // implementation. Use a managed map if available, otherwise defer.
                        output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET, 0L));
                    } else {
                        // 原本就不可 seek 的 fallback：保持原行为。
                        output.seekMap(seekMap);
                    }
                }
            });
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            return delegate.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
            delegate.seek(position, timeUs);
        }

        @Override
        public void release() {
            delegate.release();
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return delegate.sniff(input);
        }
    }

    /**
     * 组合 seek 映射：本地索引负责已下载范围，远端探测结果只负责它自己证明过的区间。
     * 两者都不覆盖的时间不得伪造可用 SeekPoint。
     */
    private static final class RemoteExtendedSeekMap implements SeekMap {

        @Nullable
        private final ChunkIndex local;
        private final SeekPoint remote;
        @Nullable
        private final SeekPoint remoteNext;
        private final long remoteSpanUs;
        private final long durationUs;

        RemoteExtendedSeekMap(@Nullable ChunkIndex local, SeekPoint remote,
                              @Nullable SeekPoint remoteNext, long remoteSpanUs, long durationUs) {
            this.local = local;
            this.remote = remote;
            this.remoteNext = remoteNext;
            this.remoteSpanUs = remoteSpanUs;
            this.durationUs = durationUs;
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public long getDurationUs() {
            return durationUs;
        }

        @Override
        /**
         * 本地区间走本地索引；远端区间走探测到的片段起点；
         * 都不覆盖时没有有效映射，调用方的 canSeekTo 会挡住这次 seek。
         */
        public SeekPoints getSeekPoints(long timeUs) {
            if (SeekCoverageRules.coversLocal(local != null ? local.timesUs : null,
                    local != null ? local.durationsUs : null,
                    local != null ? local.length : 0, timeUs)) {
                return local.getSeekPoints(timeUs);
            }
            if (SeekCoverageRules.coversRemote(remote.timeUs,
                    remote.timeUs + remoteSpanUs, timeUs)) {
                if (remoteNext != null && remoteNext.timeUs > remote.timeUs
                        && remoteNext.timeUs == remote.timeUs + remoteSpanUs
                        && remoteNext.position > remote.position) {
                    return new SeekPoints(remote, remoteNext);
                }
                return new SeekPoints(remote);
            }
            // Never present  stale remote point 作为 valid 用于  unrelated time. When there 是
            // no local 索引 either, there 是 no valid mapping 在 all; 返回 启动 only 作为 
            // non-answer. canSeekTo() 是 假 用于 该 target, so callers must 不 seek here.
            return local != null ? local.getSeekPoints(timeUs) : new SeekPoints(SeekPoint.START);
        }
    }
}

