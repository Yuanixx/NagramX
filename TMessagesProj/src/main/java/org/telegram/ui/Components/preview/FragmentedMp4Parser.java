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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Builds the keyframe table of a fragmented MP4 from its moof chain.
 *
 * A fragmented file keeps moov's sample table empty and describes every
 * fragment in a moof instead: tfdt gives the decode time, tfhd/trun give the
 * byte position of the media data. Only the first sample of each video
 * fragment is used, because an encoder starts every fragment with a sync
 * sample and one entry per fragment is all the seek preview needs.
 *
 * Only boxes reachable from the top level are read, so this stays a bounded
 * header scan: a moof is at most a few kilobytes even for long fragments.
 */
public final class FragmentedMp4Parser {

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

    private FragmentedMp4Parser() {
    }

    /**
     * @param raf       open file
     * @param start     offset to start the top-level box walk at, normally the
     *                  end of the moov atom
     * @param length    length of the file, so a half-written mdat is not indexed
     * @param timescale mdhd timescale of the video track
     * @param trackId   tkhd track_ID of the video track, or 0 to take the first
     *                  traf of every moof
     * @return keyframes sorted by presentation time, empty when the file has no
     *         usable fragment
     */
    public static ArrayList<MoovKeyframeParser.Keyframe> parse(
            RandomAccessFile raf, long start, long length, long timescale, int trackId) {
        final ArrayList<MoovKeyframeParser.Keyframe> keyframes = new ArrayList<>();
        if (raf == null || start < 0 || timescale <= 0 || start + 8 > length) {
            return keyframes;
        }
        try {
            final byte[] header = new byte[16];
            long position = start;
            int boxes = 0;
            while (position + 8 <= length && boxes++ < MAX_TOP_LEVEL_BOXES) {
                raf.seek(position);
                if (raf.read(header, 0, 8) < 8) {
                    break;
                }
                long size = u32(header, 0);
                final String type = type(header, 4);
                int headerSize = 8;
                if (size == 1) {
                    if (position + 16 > length) {
                        break;
                    }
                    raf.readFully(header, 8, 8);
                    size = u64(header, 8);
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - position;
                }
                if (size < headerSize || size > length - position) {
                    break;
                }
                if ("moof".equals(type) && size <= MAX_MOOF_BYTES) {
                    final MoovKeyframeParser.Keyframe keyframe = parseMoof(raf, position, size,
                            headerSize, length, timescale, trackId);
                    if (keyframe != null) {
                        keyframes.add(keyframe);
                    }
                }
                position += size;
            }
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: fragment scan failed", e);
        }
        if (keyframes.size() > 1) {
            Collections.sort(keyframes, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        }
        return keyframes;
    }

    private static MoovKeyframeParser.Keyframe parseMoof(RandomAccessFile raf, long moofOffset,
                                                         long moofSize, int headerSize,
                                                         long fileLength, long timescale,
                                                         int trackId) throws IOException {
        final byte[] moof = new byte[(int) moofSize];
        raf.seek(moofOffset);
        raf.readFully(moof);

        final int end = moof.length;
        int position = headerSize;
        while (position + 8 <= end) {
            final long size = readSize(moof, position);
            final String type = type(moof, position + 4);
            final int childHeader = headerSizeAt(moof, position);
            if (size < childHeader || position + size > end) {
                break;
            }
            if ("traf".equals(type)) {
                final Traf traf = parseTraf(moof, position + childHeader,
                        (int) (position + size), trackId);
                if (traf != null) {
                    final MoovKeyframeParser.Keyframe keyframe = buildKeyframe(
                            traf, moofOffset, moofSize, fileLength, timescale);
                    if (keyframe != null) {
                        return keyframe;
                    }
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

    private static MoovKeyframeParser.Keyframe buildKeyframe(Traf traf, long moofOffset,
                                                             long moofSize, long fileLength,
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
                    : moofOffset + traf.dataOffset;
        } else if (traf.baseDataOffset >= 0) {
            // No data_offset on the first run: its data starts at the base.
            offset = traf.baseDataOffset;
        } else {
            // No explicit position at all: the samples start right after the
            // moof, inside the mdat that follows it (8-byte 32-bit box header).
            offset = moofOffset + moofSize + 8;
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
