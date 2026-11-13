/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.telegram.ui.Components;

import android.net.Uri;
import android.util.Log;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Stream-level normalizer for fragmented MP4 files whose moof chain contains an
 * empty track run.
 *
 * media3 1.11.1 {@code FragmentedMp4Extractor.parseTruns()} sizes the
 * {@code TrackFragment.trunLength} array from the number of truns with
 * {@code sample_count > 0}, but then calls {@code parseTrun()} for every trun
 * box. A trun with {@code sample_count == 0} throws
 * {@code ArrayIndexOutOfBoundsException: length=0; index=0} while the first moof
 * is parsed, and the viewer reports "can't play this video".
 *
 * This wrapper looks at the bytes the wrapped {@link DataSource} returns: a
 * moof header is recognized from its own size/type, and inside that moof a trun
 * with sample_count 0 is exposed as {@code free}. Every parser ignores a free
 * box. Nothing is read ahead beyond a few bytes of the box header the extractor
 * is already parsing, and neither the network stream nor a local file is
 * modified, so it also works when no local copy exists at all.
 *
 * The pattern match is strict: the size field must precede the type, the size
 * must equal the exact empty-trun payload length implied by the flags, and
 * sample_count must be 0. A false match would corrupt a payload, so candidates
 * that do not satisfy all checks are skipped.
 */
public final class TrunFilteringDataSource implements DataSource {

    private static final String TAG = "TRUNFILTER";
    private static final int MAX_MOOF_BYTES = 4 * 1024 * 1024;

    private final DataSource upstream;

    /** Absolute position of the next byte delivered to the extractor. */
    private long position;
    private long streamLength = -1;
    private long moofStart = -1;
    private long moofEnd = -1;
    private boolean loggedPatch;
    /** Per-stream trace: whether this stream carried a moof and whether a trun was patched. */
    private boolean sawMoof;
    private boolean patchedStream;

    /**
     * Bytes fetched from the upstream while inspecting a trun header but not yet
     * delivered to the extractor. At most one box header is examined at a time,
     * so a handful of bytes is enough.
     */
    private final byte[] pending = new byte[16];
    private int pendingLength;
    private int pendingOffset;
    private long pendingStart;

    public TrunFilteringDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        final long length = upstream.open(dataSpec);
        position = dataSpec.position;
        streamLength = length;
        moofStart = -1;
        moofEnd = -1;
        pendingLength = 0;
        pendingOffset = 0;
        sawMoof = false;
        patchedStream = false;
        Log.w(TAG, "open pos=" + dataSpec.position + " len=" + dataSpec.length
                + " uri=" + dataSpec.uri);
        return length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (pendingLength > 0) {
            final int n = Math.min(length, pendingLength);
            System.arraycopy(pending, pendingOffset, buffer, offset, n);
            pendingOffset += n;
            pendingLength -= n;
            pendingStart += n;
            position += n;
            if (pendingLength == 0) {
                pendingOffset = 0;
            }
            return n;
        }
        final long start = position;
        final int read = upstream.read(buffer, offset, length);
        if (read > 0) {
            patch(buffer, offset, start, read);
            position = start + read;
        }
        return read;
    }

    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        // One line per stream: whether it was fragmented at all and whether an
        // empty trun actually needed the rewrite. Makes a no-op quality switch
        // distinguishable from a silently missing filter.
        Log.w(TAG, "close moofSeen=" + sawMoof + " patched=" + patchedStream);
        upstream.close();
    }

    private void patch(byte[] buffer, int offset, long start, int length) throws IOException {
        final long end = start + length;
        final int limit = offset + length;
        // Continue a moof whose header was seen in an earlier read.
        if (moofStart >= 0 && start >= moofStart && start < moofEnd) {
            patchRange(buffer, offset, start, length, moofStart, Math.min(moofEnd, end));
        }
        // A moof can start anywhere inside a read; the upstream chunk size is
        // not aligned to box boundaries.
        for (int i = offset; i + 8 <= limit; i++) {
            if (buffer[i + 4] != 'm' || buffer[i + 5] != 'o'
                    || buffer[i + 6] != 'o' || buffer[i + 7] != 'f') {
                continue;
            }
            final long size = u32(buffer, i);
            final long boxStart = start + (i - offset);
            final long boxEnd = boxStart + size;
            if (size < 8 || size > MAX_MOOF_BYTES) {
                continue;
            }
            if (streamLength >= 0 && boxEnd > streamLength) {
                continue;
            }
            moofStart = boxStart;
            moofEnd = boxEnd;
            sawMoof = true;
            patchRange(buffer, offset, start, length, boxStart, Math.min(boxEnd, end));
        }
    }

    private void patchRange(byte[] buffer, int offset, long start, int length,
                            long rangeStart, long rangeEnd) throws IOException {
        final long scanStart = Math.max(start, rangeStart);
        final long scanEnd = Math.min(start + length, rangeEnd);
        if (scanEnd <= scanStart) {
            return;
        }
        final int from = offset + (int) (scanStart - start);
        final int to = offset + (int) (scanEnd - start);
        for (int i = from; i + 4 <= to; i++) {
            if (buffer[i] != 't' || buffer[i + 1] != 'r'
                    || buffer[i + 2] != 'u' || buffer[i + 3] != 'n') {
                continue;
            }
            if (i - 4 < offset) {
                // The box size is in a previous read; skip rather than guess.
                continue;
            }
            final long boxStart = start + (i - 4 - offset);
            if (boxStart < rangeStart) {
                continue;
            }
            final long size = u32(buffer, i - 4);
            // Validate against the moof end, not the (possibly shorter) read end:
            // the extractor reads the trun header and payload in separate calls.
            if (size < 16 || boxStart + size > moofEnd) {
                continue;
            }
            final long flags = intAt(buffer, offset, start, length, boxStart + 8);
            final long sampleCount = intAt(buffer, offset, start, length, boxStart + 12);
            if (flags < 0 || sampleCount < 0) {
                continue;
            }
            if ((flags & ~0x00000005L) != 0 || sampleCount != 0) {
                continue;
            }
            final long expectedSize = 16
                    + ((flags & 0x00000001L) != 0 ? 4 : 0)
                    + ((flags & 0x00000004L) != 0 ? 4 : 0);
            if (size != expectedSize) {
                continue;
            }
            buffer[i] = 'f';
            buffer[i + 1] = 'r';
            buffer[i + 2] = 'e';
            buffer[i + 3] = 'e';
            patchedStream = true;
            if (!loggedPatch) {
                loggedPatch = true;
                Log.w(TAG, "patched empty trun @" + boxStart + " moof@" + rangeStart);
            }
            return;
        }
    }

    /**
     * Reads a big-endian int from the current read buffer, or from a few bytes
     * fetched ahead when the extractor split the box header across two reads.
     *
     * @return the value, or -1 when unavailable
     */
    private long intAt(byte[] buffer, int offset, long start, int length, long absolute)
            throws IOException {
        if (absolute >= start && absolute + 4 <= start + length) {
            return u32(buffer, offset + (int) (absolute - start));
        }
        if (absolute >= pendingStart && absolute + 4 <= pendingStart + pendingLength) {
            return u32(pending, pendingOffset + (int) (absolute - pendingStart));
        }
        ensurePending(absolute + 4, start + length);
        if (absolute >= pendingStart && absolute + 4 <= pendingStart + pendingLength) {
            return u32(pending, pendingOffset + (int) (absolute - pendingStart));
        }
        return -1;
    }

    private void ensurePending(long needEnd, long upstreamNext) throws IOException {
        if (pendingLength == 0) {
            pendingStart = upstreamNext;
        }
        while (pendingStart + pendingLength < needEnd) {
            final int room = pending.length - pendingOffset - pendingLength;
            if (room <= 0) {
                return;
            }
            final int n = upstream.read(pending, pendingOffset + pendingLength, room);
            if (n <= 0) {
                return;
            }
            pendingLength += n;
        }
    }

    private static long u32(byte[] b, int offset) {
        return ((long) (b[offset] & 0xff) << 24)
                | ((long) (b[offset + 1] & 0xff) << 16)
                | ((long) (b[offset + 2] & 0xff) << 8)
                | (b[offset + 3] & 0xff);
    }
}
