package org.telegram.ui.Components;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.extractor.ChunkIndex;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;

import org.telegram.messenger.FragmentIndexBuilder;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.seek.SeekCoverageRules;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 每个播放器实例一个的 extractors 工厂。
 *
 * 为什么包装 ExtractorOutput：media3 没有旧 fork 的 setChunkIndex 钩子，
 * 只能在 extractor 提交 seekMap 时，把库的 fallback（不可 seek）替换成我们准备好的索引。
 * 文件自带真实 sidx 时优先保留库自己的 ChunkIndex，因为那是服务端认证过的索引。
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

    @Nullable
    private TLRPC.Document seekDocument;
    private int seekAccount;
    private long seekTargetTimeUs;
    private double seekDurationSec;
    @Nullable
    private volatile SeekPoint remotePoint;
    private volatile long remoteSpanUs;

    /**
     * 为本次 prepare 设置需要探测的远端目标。必须在第一次 createExtractors 之前调用。
     *
     * 为什么由 PhotoViewer 传入而不是工厂自己猜：目标来自用户拖动/续播恢复，
     * 属于 PhotoViewer 的待重放 seek；工厂只在 loader 线程使用它发探测请求。
     * 所有网络工作都留在 index() 里，UI 线程不会阻塞。
     */
    public void setSeekTarget(@Nullable TLRPC.Document document, int account,
                              long targetTimeUs, double durationSec) {
        this.seekDocument = document;
        this.seekAccount = account;
        this.seekTargetTimeUs = targetTimeUs;
        this.seekDurationSec = durationSec;
    }

    /** Eager mode, for callers that already built the index. */
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

    /** Lazy mode: the scan runs on the loader thread inside createExtractors. */
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
        return attempted && (resolvedIndex != null || remotePoint != null);
    }

    /**
     * 当前映射能否把目标时间解析成真实字节位置。
     * 本地只认索引实际覆盖的区间；远端只认探测证明过的左闭右开区间。
     * 不再用固定秒数容差，超出区间必须重新探测（并因此重新 prepare）。
     */
    public boolean canSeekTo(long timeUs) {
        if (!attempted) {
            return false;
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
     * 注意 resolvedIndex 可能为 null（没有任何索引），此时不能把“没有索引”缓存成
     * “可 seek”——canSeekTo 会如实返回 false，调用方因此延迟 seek 而不是交给 media3 归零。
     */
    @Nullable
    private ChunkIndex index() {
        if (prebuiltIndex != null) {
            return prebuiltIndex;
        }
        if (!attempted) {
            synchronized (this) {
                if (!attempted) {
                    // 1) Document identity only: works while streaming and after the local
                    //    file was deleted, because the payload behind a document id never
                    //    changes. This is the desktop-client behaviour: the demuxer already
                    //    owns an index, so a seek is just a byte offset.
                    ChunkIndex idx = FragmentIndexBuilder.lookup(docId, docSize, expectedDurationSec);
                    if (idx != null) {
                        Log.w(TAG, "index source=persisted chunks=" + idx.length + " doc=" + docId);
                    } else if (path != null) {
                        // 2) No persisted copy yet: scan the fully downloaded file, and persist
                        //    the result so every later play of this document can seek instantly.
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
     * 即使 index 和远端点都为空也要包装，这样有索引和无索引走完全相同的代码路径，
     * 避免“某条路径忘了注入”导致静默归零。
     */
    private Extractor[] stamp(Extractor[] extractors) {
        ChunkIndex idx = index();
        int n = 0;
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof FragmentedMp4Extractor) {
                // Always wrap, even with a null index: keeps the path identical for both cases.
                extractors[i] = new ChunkIndexInjectingExtractor(extractors[i], idx, remotePoint,
                        remoteSpanUs, expectedDurationSec);
                n++;
            }
        }
        Log.w(TAG, "stamp fmp4=" + n + " idx="
                + (idx != null ? "chunks=" + idx.length : "null"));
        return extractors;
    }

    private static final class ChunkIndexInjectingExtractor implements Extractor {

        private final Extractor delegate;
        @Nullable
        private final ChunkIndex chunkIndex;
        @Nullable
        private final SeekPoint remotePoint;
        private final long remoteSpanUs;
        private final double durationSec;

        ChunkIndexInjectingExtractor(Extractor delegate, @Nullable ChunkIndex chunkIndex,
                                     @Nullable SeekPoint remotePoint, long remoteSpanUs,
                                     double durationSec) {
            this.delegate = delegate;
            this.chunkIndex = chunkIndex;
            this.remotePoint = remotePoint;
            this.remoteSpanUs = remoteSpanUs;
            this.durationSec = durationSec;
        }

        @Override
        public void init(ExtractorOutput output) {
            if (chunkIndex == null && remotePoint == null) {
                delegate.init(output);
                return;
            }
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
                 * extractor 提交 seekMap 时做替换：
                 * - 真实 sidx 产生的 ChunkIndex 最权威，原样放行；
                 * - 其余情况（库的 unseekable fallback）用我们的本地/远端组合映射替换。
                 * 这样 media3 看到的永远是 isSeekable()==true，不会把 seek 解析成 0。
                 */
                @Override
                public void seekMap(SeekMap seekMap) {
                    // A ChunkIndex emitted by the extractor comes from a real Sidx and is more
                    // authoritative than the scanned index. Anything else is the fallback
                    // (restartable/unseekable) that the old fork replaced with the injected one.
                    if (seekMap instanceof ChunkIndex) {
                        output.seekMap(seekMap);
                    } else if (remotePoint != null) {
                        output.seekMap(new RemoteExtendedSeekMap(chunkIndex, remotePoint,
                                remoteSpanUs, (long) (durationSec * 1000000.0)));
                    } else {
                        output.seekMap(chunkIndex);
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
        private final long remoteSpanUs;
        private final long durationUs;

        RemoteExtendedSeekMap(@Nullable ChunkIndex local, SeekPoint remote, long remoteSpanUs,
                              long durationUs) {
            this.local = local;
            this.remote = remote;
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
                    local != null ? local.length : 0, timeUs)) {
                return local.getSeekPoints(timeUs);
            }
            if (SeekCoverageRules.coversRemote(remote.timeUs,
                    remote.timeUs + remoteSpanUs, timeUs)) {
                return new SeekPoints(remote);
            }
            // Never present a stale remote point as valid for an unrelated time. When there is
            // no local index either, there is no valid mapping at all; return START only as a
            // non-answer. canSeekTo() is false for this target, so callers must not seek here.
            return local != null ? local.getSeekPoints(timeUs) : new SeekPoints(SeekPoint.START);
        }
    }
}

