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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/**
 * Reads the sample tables of a non-fragmented MP4 and turns them into a
 * keyframe list: for each sync sample, where it lives in the file and when it
 * plays. Also exposes the codec configuration (avcC/hvcC), coded size and
 * display rotation, which the MediaCodec keyframe decoder needs.
 *
 * A tail-moov file carries no index until the atom arrives, which is why a
 * decoder cannot produce any frame from the buffered prefix alone. Once moov is
 * available the layout is fully described, so thumbnails can be fetched by byte
 * range instead of by guessing.
 *
 * Only the first video track is considered. Tables handled:
 *   tkhd  display matrix / rotation
 *   mdhd  timescale
 *   hdlr  video track marker
 *   stsd  avcC / hvcC / colr
 *   stss  sync sample numbers
 *   stts  decode time deltas
 *   stsc  sample-to-chunk mapping
 *   stco  / co64  chunk offsets
 *   stsz  sample sizes
 */
public final class MoovKeyframeParser {

    /** One decodable entry point in the file. */
    public static final class Keyframe {
        public final long timeMs;
        /** Exact presentation time in microseconds, when the timescale allows it. */
        public long timeUs;
        public final long offset;
        public final int size;
        /** Index of this sample in the track's sample table, or -1. */
        public final int sampleIndex;

        /**
         * End offset of the data this keyframe governs, i.e. where the next
         * keyframe starts (or EOF).
         */
        public long rangeEnd;
        /**
         * Bytes from this keyframe through the next few samples.
         */
        public long sampleSpan;
        /** Real end offset of the last sample a decoder needs past this sync frame. */
        public long tailEndOffset;
        /** Same as {@link #tailEndOffset} but with a wider window; diagnosis only. */
        public long tailEndOffsetWide;
        /** Third window (N=15); diagnosis only. */
        public long tailEndOffsetWider;

        public Keyframe(long timeMs, long offset, int size) {
            this(timeMs, offset, size, -1);
        }

        public Keyframe(long timeMs, long offset, int size, int sampleIndex) {
            this.timeMs = timeMs;
            this.timeUs = timeMs * 1000L;
            this.offset = offset;
            this.size = size;
            this.sampleIndex = sampleIndex;
            this.rangeEnd = offset + size;
            this.sampleSpan = size;
            this.tailEndOffset = offset + size;
            this.tailEndOffsetWide = offset + size;
            this.tailEndOffsetWider = offset + size;
        }

        @Override
        public String toString() {
            return "t=" + timeMs + "ms off=" + offset + " size=" + size;
        }
    }

    /**
     * Everything the keyframe decoder needs from the video track.
     */
    public static final class TrackInfo {
        public String mime;
        public String codecFourcc;
        public int codedWidth;
        public int codedHeight;
        public int displayWidth;
        public int displayHeight;
        public int rotationDegrees;
        /** 1 = BT.709, 6 = BT.601, 9 = BT.2020, 0 = unknown. */
        public int colorMatrix;
        public boolean fullRange;
        public int nalLengthSize = 4;

        /** Annex B SPS (AVC) or VPS+SPS+PPS (HEVC). */
        public byte[] csd0;
        /** Annex B PPS for AVC, null for HEVC. */
        public byte[] csd1;
        /** Raw avcC/hvcC payload, fallback csd when the split form is rejected. */
        public byte[] codecConfig;

        public long timescale;
        /** tkhd track_ID, used to match the trafs of a fragmented file. */
        public int trackId;
        public long durationMs;
        public int sampleCount;
        public final ArrayList<Keyframe> keyframes = new ArrayList<>();

        public boolean isAvc() {
            return "video/avc".equals(mime);
        }

        public boolean isHevc() {
            return "video/hevc".equals(mime);
        }

        public boolean isAvcOrHevc() {
            return isAvc() || isHevc();
        }

        public boolean hasCodecConfig() {
            return csd0 != null && csd0.length > 0;
        }
    }

    /** How many samples past a sync frame a decoder typically consumes. */
    private static final int TAIL_SAMPLES = 5;

    private static final int TAIL_SAMPLES_WIDE = 10;

    private static final int TAIL_SAMPLES_WIDER = 15;

    private MoovKeyframeParser() {
    }

    private static long tailEndOf(long[] offsets, long[] sizes, int index, int count) {
        if (offsets == null || sizes == null || offsets.length == 0
                || offsets.length != sizes.length) {
            return 0;
        }
        final int first = Math.max(0, Math.min(index, offsets.length - 1));
        final int last = Math.min(offsets.length - 1, first + Math.max(0, count));
        long end = offsets[first] + sizes[first];
        for (int i = first + 1; i <= last; i++) {
            end = Math.max(end, offsets[i] + sizes[i]);
        }
        return end;
    }

    private static long spanBytes(long[] sizes, int index, int count) {
        long total = 0;
        final int end = Math.min(sizes.length, index + count + 1);
        for (int i = index; i < end; i++) {
            total += sizes[i];
        }
        return total;
    }

    /**
     * @param moov the complete moov atom (including its 8-byte header)
     * @return keyframes in presentation order, or an empty list when the atom
     *         cannot be understood
     */
    public static ArrayList<Keyframe> parse(byte[] moov) {
        final TrackInfo track = parseTrack(moov);
        return track != null ? track.keyframes : new ArrayList<>();
    }

    /**
     * Parses the first video track of a moov atom.
     *
     * @param moov the complete moov atom (including its header)
     * @return track information, or null when no video track can be understood
     */
    public static TrackInfo parseTrack(byte[] moov) {
        return parseTrack(moov, false);
    }

    /**
     * Parses everything except the sample tables: codec configuration, coded
     * size, display rotation and timescale. A fragmented MP4 keeps its sample
     * index in the moof chain instead of stbl, so this is the only part of the
     * moov that is meaningful for it.
     *
     * @param moov the complete moov atom (including its header)
     * @return track information with an empty keyframe list, or null
     */
    public static TrackInfo parseTrackConfig(byte[] moov) {
        return parseTrack(moov, true);
    }

    private static TrackInfo parseTrack(byte[] moov, boolean configOnly) {
        if (moov == null || moov.length < 8) {
            return null;
        }
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN);
            final int end = Math.min(moov.length, (int) readSize(buffer, 0));
            return parseMoov(buffer, headerSizeAt(buffer, 0),
                    end <= 8 ? moov.length : end, configOnly);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static TrackInfo parseMoov(ByteBuffer buffer, int start, int end,
                                       boolean configOnly) {
        int position = start;
        while (position + 8 <= end) {
            final long size = readSize(buffer, position);
            final String type = readType(buffer, position + 4);
            final int headerSize = headerSizeAt(buffer, position);
            if (size < headerSize || position + size > end) {
                break;
            }
            final int bodyStart = position + headerSize;
            final int bodyEnd = (int) (position + size);
            if ("trak".equals(type)) {
                final TrackInfo track = parseTrak(buffer, bodyStart, bodyEnd, configOnly);
                if (track != null && (configOnly || !track.keyframes.isEmpty())) {
                    return track;
                }
            }
            position = bodyEnd;
        }
        return null;
    }

    private static TrackInfo parseTrak(ByteBuffer buffer, int start, int end,
                                       boolean configOnly) {
        int rotation = 0;
        int displayWidth = 0;
        int displayHeight = 0;
        int trackId = 0;
        int mdiaStart = -1;
        int mdiaEnd = -1;

        int position = start;
        while (position + 8 <= end) {
            final long size = readSize(buffer, position);
            final String type = readType(buffer, position + 4);
            final int headerSize = headerSizeAt(buffer, position);
            if (size < headerSize || position + size > end) {
                break;
            }
            final int bodyStart = position + headerSize;
            final int bodyEnd = (int) (position + size);
            if ("tkhd".equals(type)) {
                try {
                    final int version = buffer.get(bodyStart) & 0xff;
                    final int idOffset = bodyStart + (version == 1 ? 20 : 12);
                    if (idOffset + 4 <= bodyEnd) {
                        trackId = buffer.getInt(idOffset);
                    }
                    final int matrix = bodyStart + (version == 1 ? 52 : 40);
                    if (matrix + 36 <= bodyEnd) {
                        final int a = buffer.getInt(matrix);
                        final int b = buffer.getInt(matrix + 4);
                        final int c = buffer.getInt(matrix + 12);
                        final int d = buffer.getInt(matrix + 16);
                        if (a == 0 && b == 0x00010000 && c == 0xffff0000 && d == 0) {
                            rotation = 90;
                        } else if (a == 0xffff0000 && b == 0 && c == 0 && d == 0xffff0000) {
                            rotation = 180;
                        } else if (a == 0 && b == 0xffff0000 && c == 0x00010000 && d == 0) {
                            rotation = 270;
                        }
                    }
                    final int widthOffset = matrix + 36;
                    if (widthOffset + 8 <= bodyEnd) {
                        displayWidth = (int) ((buffer.getInt(widthOffset) & 0xffffffffL) >> 16);
                        displayHeight = (int) ((buffer.getInt(widthOffset + 4) & 0xffffffffL) >> 16);
                    }
                } catch (Throwable ignore) {
                }
            } else if ("mdia".equals(type)) {
                mdiaStart = bodyStart;
                mdiaEnd = bodyEnd;
            }
            position = bodyEnd;
        }

        if (mdiaStart < 0) {
            return null;
        }
        return parseMdia(buffer, mdiaStart, mdiaEnd, rotation, displayWidth, displayHeight,
                trackId, configOnly);
    }

    private static TrackInfo parseMdia(ByteBuffer buffer, int start, int end,
                                       int rotation, int displayWidth, int displayHeight,
                                       int trackId, boolean configOnly) {
        long timescale = 0;
        boolean video = false;
        int minfStart = -1;
        int minfEnd = -1;

        int position = start;
        while (position + 8 <= end) {
            final long size = readSize(buffer, position);
            final String type = readType(buffer, position + 4);
            final int headerSize = headerSizeAt(buffer, position);
            if (size < headerSize || position + size > end) {
                break;
            }
            final int bodyStart = position + headerSize;
            final int bodyEnd = (int) (position + size);
            if ("mdhd".equals(type)) {
                try {
                    final int version = buffer.get(bodyStart) & 0xff;
                    timescale = version == 1
                            ? buffer.getInt(bodyStart + 20) & 0xffffffffL
                            : buffer.getInt(bodyStart + 12) & 0xffffffffL;
                } catch (Throwable ignore) {
                }
            } else if ("hdlr".equals(type)) {
                if (bodyStart + 12 <= bodyEnd) {
                    video = "vide".equals(readType(buffer, bodyStart + 8));
                }
            } else if ("minf".equals(type)) {
                minfStart = bodyStart;
                minfEnd = bodyEnd;
            }
            position = bodyEnd;
        }

        if (!video || minfStart < 0 || timescale <= 0) {
            return null;
        }
        return parseMinf(buffer, minfStart, minfEnd, timescale, rotation,
                displayWidth, displayHeight, trackId, configOnly);
    }

    private static TrackInfo parseMinf(ByteBuffer buffer, int start, int end, long timescale,
                                       int rotation, int displayWidth, int displayHeight,
                                       int trackId, boolean configOnly) {
        int position = start;
        while (position + 8 <= end) {
            final long size = readSize(buffer, position);
            final String type = readType(buffer, position + 4);
            final int headerSize = headerSizeAt(buffer, position);
            if (size < headerSize || position + size > end) {
                break;
            }
            final int bodyStart = position + headerSize;
            final int bodyEnd = (int) (position + size);
            if ("stbl".equals(type)) {
                return parseStbl(buffer, bodyStart, bodyEnd, timescale, rotation,
                        displayWidth, displayHeight, trackId, configOnly);
            }
            position = bodyEnd;
        }
        return null;
    }

    private static TrackInfo parseStbl(ByteBuffer buffer, int start, int end, long timescale,
                                       int rotation, int displayWidth, int displayHeight,
                                       int trackId, boolean configOnly) {
        int[] syncSamples = null;
        long[] sampleSizes = null;
        long[] chunkOffsets = null;
        int[] stscFirstChunk = null;
        int[] stscSamplesPerChunk = null;
        int[] sttsCounts = null;
        long[] sttsDeltas = null;
        int sttsEntryCount = 0;
        int totalSamples = 0;
        int chunkCount = 0;
        int stsdStart = -1;
        int stsdEnd = -1;

        int position = start;
        while (position + 8 <= end) {
            final long size = readSize(buffer, position);
            final String type = readType(buffer, position + 4);
            final int headerSize = headerSizeAt(buffer, position);
            if (size < headerSize || position + size > end) {
                break;
            }
            final int body = position + headerSize;
            final int bodyEnd = (int) (position + size);
            try {
                switch (type) {
                    case "stsd":
                        stsdStart = body;
                        stsdEnd = bodyEnd;
                        break;
                    case "stss": {
                        final int count = buffer.getInt(body + 4);
                        if (count >= 0 && count < 4_000_000) {
                            syncSamples = new int[count];
                            for (int i = 0; i < count; i++) {
                                syncSamples[i] = buffer.getInt(body + 8 + i * 4);
                            }
                        }
                        break;
                    }
                    case "stsz": {
                        final int uniform = buffer.getInt(body + 4);
                        final int count = buffer.getInt(body + 8);
                        if (count < 0 || count > 8_000_000) {
                            break;
                        }
                        totalSamples = count;
                        sampleSizes = new long[count];
                        if (uniform != 0) {
                            for (int i = 0; i < count; i++) {
                                sampleSizes[i] = uniform;
                            }
                        } else {
                            for (int i = 0; i < count; i++) {
                                sampleSizes[i] = buffer.getInt(body + 12 + i * 4) & 0xffffffffL;
                            }
                        }
                        break;
                    }
                    case "stco": {
                        chunkCount = buffer.getInt(body + 4);
                        chunkOffsets = new long[chunkCount];
                        for (int i = 0; i < chunkCount; i++) {
                            chunkOffsets[i] = buffer.getInt(body + 8 + i * 4) & 0xffffffffL;
                        }
                        break;
                    }
                    case "co64": {
                        chunkCount = buffer.getInt(body + 4);
                        chunkOffsets = new long[chunkCount];
                        for (int i = 0; i < chunkCount; i++) {
                            chunkOffsets[i] = buffer.getLong(body + 8 + i * 8);
                        }
                        break;
                    }
                    case "stsc": {
                        final int count = buffer.getInt(body + 4);
                        stscFirstChunk = new int[count];
                        stscSamplesPerChunk = new int[count];
                        for (int i = 0; i < count; i++) {
                            final int base = body + 8 + i * 12;
                            stscFirstChunk[i] = buffer.getInt(base);
                            stscSamplesPerChunk[i] = buffer.getInt(base + 4);
                        }
                        break;
                    }
                    case "stts": {
                        sttsEntryCount = buffer.getInt(body + 4);
                        sttsCounts = new int[sttsEntryCount];
                        sttsDeltas = new long[sttsEntryCount];
                        for (int i = 0; i < sttsEntryCount; i++) {
                            final int base = body + 8 + i * 8;
                            sttsCounts[i] = buffer.getInt(base);
                            sttsDeltas[i] = buffer.getInt(base + 4) & 0xffffffffL;
                        }
                        break;
                    }
                    default:
                        break;
                }
            } catch (Throwable ignore) {
                // A malformed table must not abort the whole parse.
            }
            position = bodyEnd;
        }

        if (stsdStart < 0) {
            return null;
        }
        if (!configOnly && (sampleSizes == null || chunkOffsets == null
                || stscFirstChunk == null || sttsCounts == null || totalSamples <= 0)) {
            return null;
        }

        final TrackInfo info = new TrackInfo();
        if (!parseStsd(buffer, stsdStart, stsdEnd, info)) {
            return null;
        }
        info.timescale = timescale;
        info.trackId = trackId;
        info.rotationDegrees = rotation;
        if (displayWidth > 0 && displayHeight > 0) {
            info.displayWidth = displayWidth;
            info.displayHeight = displayHeight;
        } else {
            info.displayWidth = info.codedWidth;
            info.displayHeight = info.codedHeight;
        }
        info.sampleCount = totalSamples;
        if (configOnly) {
            return info;
        }

        final long[] sampleTimes = new long[totalSamples];
        long time = 0;
        int sample = 0;
        for (int i = 0; i < sttsEntryCount && sample < totalSamples; i++) {
            for (int k = 0; k < sttsCounts[i] && sample < totalSamples; k++) {
                sampleTimes[sample] = time;
                time += sttsDeltas[i];
                sample++;
            }
        }
        while (sample < totalSamples) {
            sampleTimes[sample] = time;
            sample++;
        }
        info.durationMs = time * 1000L / timescale;

        final long[] sampleOffsets = new long[totalSamples];
        sample = 0;
        for (int chunk = 0; chunk < chunkCount && sample < totalSamples; chunk++) {
            int samplesPerChunk = stscSamplesPerChunk[0];
            for (int i = 0; i < stscFirstChunk.length; i++) {
                if (stscFirstChunk[i] <= chunk + 1) {
                    samplesPerChunk = stscSamplesPerChunk[i];
                } else {
                    break;
                }
            }
            long offset = chunkOffsets[chunk];
            for (int i = 0; i < samplesPerChunk && sample < totalSamples; i++) {
                sampleOffsets[sample] = offset;
                offset += sampleSizes[sample];
                sample++;
            }
        }

        if (syncSamples != null && syncSamples.length > 0) {
            for (int syncSample : syncSamples) {
                final int index = syncSample - 1;
                if (index < 0 || index >= totalSamples) {
                    continue;
                }
                info.keyframes.add(makeKeyframe(info, sampleTimes, sampleOffsets,
                        sampleSizes, index, timescale));
            }
        } else {
            // No stss means every sample is a sync sample.
            for (int i = 0; i < totalSamples; i++) {
                info.keyframes.add(makeKeyframe(info, sampleTimes, sampleOffsets,
                        sampleSizes, i, timescale));
            }
        }
        return info.keyframes.isEmpty() ? null : info;
    }

    private static Keyframe makeKeyframe(TrackInfo info, long[] sampleTimes,
                                         long[] sampleOffsets, long[] sampleSizes,
                                         int index, long timescale) {
        final Keyframe keyframe = new Keyframe(
                sampleTimes[index] * 1000L / timescale,
                sampleOffsets[index],
                (int) Math.min(Integer.MAX_VALUE, sampleSizes[index]),
                index);
        keyframe.timeUs = sampleTimes[index] * 1_000_000L / timescale;
        keyframe.sampleSpan = spanBytes(sampleSizes, index, TAIL_SAMPLES);
        keyframe.tailEndOffset = tailEndOf(sampleOffsets, sampleSizes, index, TAIL_SAMPLES);
        keyframe.tailEndOffsetWide = tailEndOf(sampleOffsets, sampleSizes, index, TAIL_SAMPLES_WIDE);
        keyframe.tailEndOffsetWider = tailEndOf(sampleOffsets, sampleSizes, index, TAIL_SAMPLES_WIDER);
        return keyframe;
    }

    private static boolean parseStsd(ByteBuffer buffer, int start, int end, TrackInfo info) {
        if (end - start < 8) {
            return false;
        }
        final int count = buffer.getInt(start + 4);
        int position = start + 8;
        for (int i = 0; i < count && position + 8 <= end; i++) {
            final long size = buffer.getInt(position) & 0xffffffffL;
            if (size < 8 || position + size > end) {
                break;
            }
            final String fourcc = readType(buffer, position + 4);
            final int body = position + 8;
            final int bodyEnd = (int) (position + size);
            final boolean videoEntry = "avc1".equals(fourcc) || "avc3".equals(fourcc)
                    || "hvc1".equals(fourcc) || "hev1".equals(fourcc)
                    || "av01".equals(fourcc) || "vp09".equals(fourcc);
            if (videoEntry && body + 78 <= bodyEnd) {
                info.codecFourcc = fourcc;
                info.codedWidth = buffer.getShort(body + 24) & 0xffff;
                info.codedHeight = buffer.getShort(body + 26) & 0xffff;
                if (info.codedWidth <= 0 || info.codedHeight <= 0) {
                    return false;
                }
                if ("avc1".equals(fourcc) || "avc3".equals(fourcc)) {
                    info.mime = "video/avc";
                } else if ("hvc1".equals(fourcc) || "hev1".equals(fourcc)) {
                    info.mime = "video/hevc";
                } else if ("av01".equals(fourcc)) {
                    info.mime = "video/av01";
                } else {
                    info.mime = "video/vp09";
                }

                final int childStart = body + 78;
                final int[] avcC = findBox(buffer, childStart, bodyEnd, "avcC");
                final int[] hvcC = findBox(buffer, childStart, bodyEnd, "hvcC");
                if (avcC != null) {
                    info.codecConfig = copy(buffer, avcC[0], avcC[1]);
                    parseAvcC(buffer, avcC[0], avcC[1], info);
                } else if (hvcC != null) {
                    info.codecConfig = copy(buffer, hvcC[0], hvcC[1]);
                    parseHvcC(buffer, hvcC[0], hvcC[1], info);
                }
                final int[] colr = findBox(buffer, childStart, bodyEnd, "colr");
                if (colr != null) {
                    parseColr(buffer, colr[0], colr[1], info);
                }
                return info.csd0 != null;
            }
            position = bodyEnd;
        }
        return false;
    }

    private static void parseAvcC(ByteBuffer buffer, int start, int end, TrackInfo info) {
        try {
            int p = start;
            info.nalLengthSize = (buffer.get(p + 4) & 3) + 1;
            p += 5;
            final int numSps = buffer.get(p) & 0x1f;
            p++;
            final java.io.ByteArrayOutputStream sps = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < numSps; i++) {
                final int len = buffer.getShort(p) & 0xffff;
                p += 2;
                appendAnnexB(sps, buffer, p, len);
                p += len;
            }
            final int numPps = buffer.get(p) & 0xff;
            p++;
            final java.io.ByteArrayOutputStream pps = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < numPps; i++) {
                final int len = buffer.getShort(p) & 0xffff;
                p += 2;
                appendAnnexB(pps, buffer, p, len);
                p += len;
            }
            info.csd0 = sps.toByteArray();
            info.csd1 = pps.toByteArray();
        } catch (Throwable ignore) {
            info.csd0 = null;
            info.csd1 = null;
        }
    }

    private static void parseHvcC(ByteBuffer buffer, int start, int end, TrackInfo info) {
        try {
            int p = start;
            info.nalLengthSize = (buffer.get(p + 21) & 3) + 1;
            final int numArrays = buffer.get(p + 22) & 0xff;
            p += 23;
            final java.io.ByteArrayOutputStream csd = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < numArrays; i++) {
                p++; // array_completeness + NAL_unit_type
                final int numNalus = buffer.getShort(p) & 0xffff;
                p += 2;
                for (int k = 0; k < numNalus; k++) {
                    final int len = buffer.getShort(p) & 0xffff;
                    p += 2;
                    appendAnnexB(csd, buffer, p, len);
                    p += len;
                }
            }
            info.csd0 = csd.toByteArray();
            info.csd1 = null;
        } catch (Throwable ignore) {
            info.csd0 = null;
        }
    }

    private static void parseColr(ByteBuffer buffer, int start, int end, TrackInfo info) {
        try {
            if (end - start < 10) {
                return;
            }
            final String colourType = readType(buffer, start);
            if ("nclx".equals(colourType)) {
                info.fullRange = (buffer.get(start + 10) & 0x80) != 0;
            } else if (!"nclc".equals(colourType)) {
                return;
            }
            info.colorMatrix = buffer.getShort(start + 8) & 0xffff;
        } catch (Throwable ignore) {
        }
    }

    private static void appendAnnexB(java.io.ByteArrayOutputStream out, ByteBuffer buffer,
                                     int offset, int length) {
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(1);
        for (int i = 0; i < length; i++) {
            out.write(buffer.get(offset + i) & 0xff);
        }
    }

    private static byte[] copy(ByteBuffer buffer, int start, int end) {
        final byte[] out = new byte[end - start];
        for (int i = 0; i < out.length; i++) {
            out[i] = buffer.get(start + i);
        }
        return out;
    }

    private static int[] findBox(ByteBuffer buffer, int start, int end, String type) {
        int position = start;
        while (position + 8 <= end) {
            final long size = readSize(buffer, position);
            final int headerSize = headerSizeAt(buffer, position);
            if (size < headerSize || position + size > end) {
                break;
            }
            if (type.equals(readType(buffer, position + 4))) {
                return new int[]{position + headerSize, (int) (position + size)};
            }
            position += (int) size;
        }
        return null;
    }

    private static int headerSizeAt(ByteBuffer buffer, int position) {
        return (buffer.getInt(position) & 0xffffffffL) == 1 ? 16 : 8;
    }

    private static long readSize(ByteBuffer buffer, int position) {
        final long size = buffer.getInt(position) & 0xffffffffL;
        if (size == 1) {
            return buffer.getLong(position + 8);
        }
        return size;
    }

    private static String readType(ByteBuffer buffer, int position) {
        final char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            chars[i] = (char) (buffer.get(position + i) & 0xff);
        }
        return new String(chars);
    }
}
