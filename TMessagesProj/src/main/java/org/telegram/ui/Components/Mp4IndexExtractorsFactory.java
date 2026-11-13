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
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;

import org.telegram.messenger.FragmentIndexBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Per-player {@link ExtractorsFactory} that injects a prebuilt {@link ChunkIndex} into every
 * {@link FragmentedMp4Extractor} instance it creates. Media3 removed the fork's
 * {@code setChunkIndex} hook, so the index is injected by wrapping {@link ExtractorOutput}:
 * until the file provides its own Sidx (a real {@link ChunkIndex}), the extractor's fallback
 * seek map is replaced by the prebuilt index.
 *
 * The index is built lazily on ExoPlayer's loader thread (createExtractors), never on the UI
 * thread. PhotoViewer only resolves local paths / document metadata synchronously and creates one
 * factory per prepare; there is no asynchronous UI callback and therefore no stale-player race.
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
                    resolvedIndex = idx;
                    attempted = true;
                }
            }
        }
        return resolvedIndex;
    }

    private Extractor[] stamp(Extractor[] extractors) {
        ChunkIndex idx = index();
        int n = 0;
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof FragmentedMp4Extractor) {
                // Always wrap, even with a null index: keeps the path identical for both cases.
                extractors[i] = new ChunkIndexInjectingExtractor(extractors[i], idx);
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

        ChunkIndexInjectingExtractor(Extractor delegate, @Nullable ChunkIndex chunkIndex) {
            this.delegate = delegate;
            this.chunkIndex = chunkIndex;
        }

        @Override
        public void init(ExtractorOutput output) {
            if (chunkIndex == null) {
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

                @Override
                public void seekMap(SeekMap seekMap) {
                    // A ChunkIndex emitted by the extractor comes from a real Sidx and is more
                    // authoritative than the scanned index. Anything else is the fallback
                    // (restartable/unseekable) that the old fork replaced with the injected one.
                    output.seekMap(seekMap instanceof ChunkIndex ? seekMap : chunkIndex);
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
}
