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

package org.telegram.ui.Components.preview;

import org.telegram.ui.Components.MoovKeyframeParser;

import java.util.ArrayList;

/**
 * Walks the moof chain of a fragmented MP4 inside one downloaded block.
 *
 * A fragmented file describes every fragment in a moof that sits right in
 * front of its media data. The local index only covers fragments already on
 * disk, so the preview needs a way to learn about fragments that are still
 * remote: fetch one block at the end of the known chain, read the moof
 * inside it, and turn it into the keyframe the decoder can ask for.
 *
 * The box parsing mirrors FragmentedMp4Parser, but it works on a byte block
 * from the network instead of a local file, and it also reports where the
 * chain continues so the next block can be fetched.
 */
public final class RemoteMoofScanner {

    private static final int MAX_MOOF_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOP_LEVEL_BOXES = 8192;

    /** tfhd optional fields. */
    private static final int TFHD_BASE_DATA_OFFSET = 0x000001;
    private static final int TFHD_SAMPLE_DESCRIPTION_INDEX = 0x000002;
    private static final int TFHD_DEFAULT_SAMPLE_DURATION = 0x000008;
    private static final int TFHD_DEFAULT_SAMPLE_SIZE = 0x000010;
    private static final int TFHD_DEFAULT_SAMPLE_FLAGS = 0x000020;

    /** trun optional fields. */
    private static final int TRUN_DATA_OFFSET = 0x000001;
    private static final int TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
    private static final int TRUN_SAMPLE_DURATION = 0x000100;
    private static final int TRUN_SAMPLE_SIZE = 0x000200;
    private static final int TRUN_SAMPLE_FLAGS = 0x000400;

    /** sample_flags bit 16: this sample is not a sync sample. */
    private static final int SAMPLE_FLAG_NON_SYNC = 0x00010000;

    private RemoteMoofScanner() {
    }

    /** What one fetched block contributed to the chain walk. */
    public static final class Fragment {
        /** Keyframes of every usable video moof inside the block, in file order. */
        public final ArrayList<MoovKeyframeParser.Keyframe> keyframes = new ArrayList<>();
        /** Absolute offset where the chain continues; -1 when unknown. */
        public long nextOffset = -1;
        /** True when the chain cannot continue past this block. */
        public boolean exhausted;
        /** True when a moof was cut by the block edge and a bigger fetch may help. */
        public boolean truncatedMoof;
    }

    /**
     * @param data       bytes fetched from the network
     * @param dataOffset absolute document offset of data[0]; a box must start here
     * @param fileLength full document size, the bound for sane box sizes
     * @param timescale  mdhd timescale of the video track
     * @param trackId    tkhd track_ID of the video track, or 0 to take the first
     *                   traf of every moof
     */
    public static Fragment scan(byte[] data, long dataOffset, long fileLength,
                                long timescale, int trackId) {
        final Fragment out = new Fragment();
        if (data == null || data.length < 8 || dataOffset < 0
                || timescale <= 0 || dataOffset + 8 > fileLength) {
            out.exhausted = true;
            return out;
        }
        final int blockEnd = data.length;
        int position = 0;
        long absolute = dataOffset;
        int boxes = 0;
        try {
            while (position + 8 <= blockEnd && boxes++ < MAX_TOP_LEVEL_BOXES) {
                long size = u32(data, position);
                final String type = type(data, position + 4);
                int headerSize = 8;
                if (size == 1) {
                    if (position + 16 > blockEnd) {
                        out.nextOffset = absolute;
                        return out;
                    }
                    size = u64(data, position + 8);
                    headerSize = 16;
                } else if (size == 0) {
                    // A zero header inside a fetched block is a broken chain.
                    out.exhausted = true;
                    return out;
                }
                if (size < headerSize || absolute + size > fileLength) {
                    out.exhausted = true;
                    return out;
                }
                if (position + size > blockEnd) {
                    // The box reaches past the block edge.
                    if ("moof".equals(type)) {
                        if (size <= MAX_MOOF_BYTES) {
                            out.truncatedMoof = true;
                            out.nextOffset = absolute;
                        } else {
                            out.exhausted = true;
                        }
                    } else if ("mfra".equals(type)) {
                        out.exhausted = true;
                    } else {
                        // mdat or any other box: its header is complete, so
                        // the chain resumes right after it.
                        out.nextOffset = absolute + size;
                    }
                    return out;
                }
                if ("moof".equals(type)) {
                    final MoovKeyframeParser.Keyframe keyframe = parseMoof(
                            data, position, (int) size, headerSize,
                            absolute, fileLength, timescale, trackId);
                    if (keyframe != null) {
                        out.keyframes.add(keyframe);
                    }
                } else if ("mfra".equals(type)) {
                    // The random access index ends the chain; everything
                    // behind it is already indexed by whoever wrote the file.
                    out.exhausted = true;
                    return out;
                }
                position += (int) size;
                absolute += size;
            }
        } catch (Throwable e) {
            // A malformed chain must never kill the scan thread.
            out.exhausted = true;
            return out;
        }
        // The block was consumed; the chain continues right after it.
        out.nextOffset = absolute;
        return out;
    }

    private static MoovKeyframeParser.Keyframe parseMoof(byte[] data, int moofPosition,
                                                         int moofSize, int headerSize,
                                                         long moofAbsoluteOffset,
                                                         long fileLength, long timescale,
                                                         int trackId) {
        final int end = moofPosition + moofSize;
        int position = moofPosition + headerSize;
        while (position + 8 <= end) {
            final long size = readSize(data, position);
            final String boxType = type(data, position + 4);
            final int childHeader = headerSizeAt(data, position);
            if (size < childHeader || position + size > end) {
                break;
            }
            if ("traf".equals(boxType)) {
                final Traf traf = parseTraf(data, position + childHeader,
                        (int) (position + size), trackId);
                if (traf != null) {
                    return buildKeyframe(traf, moofAbsoluteOffset, moofSize,
                            fileLength, timescale);
                }
            }
            position += (int) size;
        }
        return null;
    }

    /**
     * @return the first traf of the video track that carries all three boxes,
     *         or null
     */
    private static Traf parseTraf(byte[] traf, int start, int end, int videoTrackId) {
        final Traf out = new Traf();
        try {
            int position = start;
            while (position + 8 <= end) {
                final long size = readSize(traf, position);
                final String type = type(traf, position + 4);
                final int headerSize = headerSizeAt(traf, position);
                if (size < headerSize || position + size > end) {
                    break;
                }
                final int body = position + headerSize;
                final int bodyEnd = (int) (position + size);
                if ("tfhd".equals(type)) {
                    final int flags = flags(traf, body, bodyEnd);
                    int p = body + 4;
                    if (p + 4 > bodyEnd) {
                        return null;
                    }
                    out.trackId = (int) u32(traf, p);
                    p += 4;
                    if ((flags & TFHD_BASE_DATA_OFFSET) != 0) {
                        if (p + 8 > bodyEnd) {
                            return null;
                        }
                        out.baseDataOffset = u64(traf, p);
                        p += 8;
                    }
                    if ((flags & TFHD_SAMPLE_DESCRIPTION_INDEX) != 0) {
                        p += 4;
                    }
                    if ((flags & TFHD_DEFAULT_SAMPLE_DURATION) != 0) {
                        p += 4;
                    }
                    if ((flags & TFHD_DEFAULT_SAMPLE_SIZE) != 0) {
                        if (p + 4 > bodyEnd) {
                            return null;
                        }
                        out.defaultSampleSize = (int) u32(traf, p);
                        p += 4;
                    }
                    if ((flags & TFHD_DEFAULT_SAMPLE_FLAGS) != 0) {
                        p += 4;
                    }
                    out.hasTfhd = true;
                } else if ("tfdt".equals(type)) {
                    if (body + 8 > bodyEnd) {
                        return null;
                    }
                    out.decodeTime = (traf[body] & 0xff) == 1
                            ? u64(traf, body + 4) : u32(traf, body + 4);
                    out.hasTfdt = true;
                } else if ("trun".equals(type) && !out.hasTrun) {
                    final int flags = flags(traf, body, bodyEnd);
                    if (body + 8 > bodyEnd) {
                        return null;
                    }
                    final int count = (int) u32(traf, body + 4);
                    int p = body + 8;
                    long dataOffset = 0;
                    boolean hasDataOffset = false;
                    if ((flags & TRUN_DATA_OFFSET) != 0) {
                        if (p + 4 > bodyEnd) {
                            return null;
                        }
                        // Signed: it is relative to the base and may point back.
                        dataOffset = (int) u32(traf, p);
                        hasDataOffset = true;
                        p += 4;
                    }
                    int firstSampleFlags = 0;
                    boolean hasFirstSampleFlags = false;
                    if ((flags & TRUN_FIRST_SAMPLE_FLAGS) != 0) {
                        if (p + 4 > bodyEnd) {
                            return null;
                        }
                        firstSampleFlags = (int) u32(traf, p);
                        hasFirstSampleFlags = true;
                        p += 4;
                    }
                    int firstSampleSize = 0;
                    if (count > 0) {
                        if ((flags & TRUN_SAMPLE_DURATION) != 0) {
                            p += 4;
                        }
                        if ((flags & TRUN_SAMPLE_SIZE) != 0) {
                            if (p + 4 > bodyEnd) {
                                return null;
                            }
                            firstSampleSize = (int) u32(traf, p);
                            p += 4;
                        }
                        if (!hasFirstSampleFlags && (flags & TRUN_SAMPLE_FLAGS) != 0) {
                            if (p + 4 > bodyEnd) {
                                return null;
                            }
                            firstSampleFlags = (int) u32(traf, p);
                            hasFirstSampleFlags = true;
                        }
                        out.dataOffset = dataOffset;
                        out.hasDataOffset = hasDataOffset;
                        out.firstSampleSize = firstSampleSize;
                        out.firstSampleFlags = firstSampleFlags;
                        out.hasFirstSampleFlags = hasFirstSampleFlags;
                        out.hasTrun = true;
                    }
                }
                position = bodyEnd;
            }
        } catch (Throwable ignore) {
            return null;
        }
        if (!out.hasTfhd || !out.hasTfdt || !out.hasTrun) {
            return null;
        }
        if (videoTrackId != 0 && out.trackId != videoTrackId) {
            return null;
        }
        return out;
    }

    private static MoovKeyframeParser.Keyframe buildKeyframe(Traf traf,
                                                             long moofAbsoluteOffset,
                                                             long moofSize,
                                                             long fileLength,
                                                             long timescale) {
        // Every fragment should start with a sync sample; trust it unless the
        // flags say otherwise.
        if (traf.hasFirstSampleFlags && (traf.firstSampleFlags & SAMPLE_FLAG_NON_SYNC) != 0) {
            return null;
        }
        final int size = traf.firstSampleSize > 0
                ? traf.firstSampleSize : traf.defaultSampleSize;
        if (size <= 0) {
            return null;
        }
        final long offset;
        if (traf.hasDataOffset) {
            offset = traf.baseDataOffset >= 0
                    ? traf.baseDataOffset + traf.dataOffset
                    : moofAbsoluteOffset + traf.dataOffset;
        } else if (traf.baseDataOffset >= 0) {
            // No data_offset on the first run: its data starts at the base.
            offset = traf.baseDataOffset;
        } else {
            // No explicit position at all: the samples start right after the
            // moof, inside the mdat that follows it (8-byte 32-bit box header).
            offset = moofAbsoluteOffset + moofSize + 8;
        }
        if (offset < 0 || offset + size > fileLength) {
            return null;
        }
        final MoovKeyframeParser.Keyframe keyframe = new MoovKeyframeParser.Keyframe(
                traf.decodeTime * 1000L / timescale, offset, size);
        keyframe.timeUs = traf.decodeTime * 1_000_000L / timescale;
        return keyframe;
    }

    private static final class Traf {
        int trackId = -1;
        boolean hasTfhd;
        boolean hasTfdt;
        boolean hasTrun;
        long baseDataOffset = -1;
        long decodeTime;
        int defaultSampleSize;
        long dataOffset;
        boolean hasDataOffset;
        int firstSampleSize;
        int firstSampleFlags;
        boolean hasFirstSampleFlags;
    }

    private static int flags(byte[] buffer, int body, int bodyEnd) {
        if (body + 4 > bodyEnd) {
            throw new IndexOutOfBoundsException("truncated full box");
        }
        return ((buffer[body + 1] & 0xff) << 16)
                | ((buffer[body + 2] & 0xff) << 8)
                | (buffer[body + 3] & 0xff);
    }

    private static int headerSizeAt(byte[] buffer, int position) {
        return (int) u32(buffer, position) == 1 ? 16 : 8;
    }

    private static long readSize(byte[] buffer, int position) {
        final long size = u32(buffer, position);
        return size == 1 ? u64(buffer, position + 8) : size;
    }

    private static long u32(byte[] b, int offset) {
        return ((long) (b[offset] & 0xff) << 24)
                | ((long) (b[offset + 1] & 0xff) << 16)
                | ((long) (b[offset + 2] & 0xff) << 8)
                | (b[offset + 3] & 0xff);
    }

    private static long u64(byte[] b, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (b[offset + i] & 0xffL);
        }
        return value;
    }

    private static String type(byte[] b, int offset) {
        final char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            chars[i] = (char) (b[offset + i] & 0xff);
        }
        return new String(chars);
    }
}
