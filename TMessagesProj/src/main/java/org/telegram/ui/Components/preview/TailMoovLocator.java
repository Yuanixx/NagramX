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

/**
 * Pure planning for the tail moov probe.
 *
 * <p>The window always ends at the declared file size. The old code aligned
 * its start down to a block boundary, which silently dropped the last partial
 * block; when the atom lived there the probe never saw it.
 *
 * <p>Callers read the tail, ask {@link #analyze} what they hold, and either
 * finish, fetch one exact missing prefix, or move the window further back.
 */
final class TailMoovLocator {

    /** Smallest atom worth handing to the parser. */
    static final int MIN_MOOV_BYTES = 16;
    /** Never allocate more than this for one atom. */
    static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;
    /** Two blocks find the usual small tail moov. */
    static final int INITIAL_BLOCKS = 2;
    /** Hard cap for the whole probe: 8 blocks = 2MB. */
    static final int MAX_BLOCKS = 8;

    enum Kind {
        /** No atom header in the bytes; move the window back. */
        NOT_FOUND,
        /** The whole atom is here. */
        COMPLETE,
        /** The header is here but the atom continues past the bytes. */
        NEED_PREFIX,
        /** The atom is larger than the probe budget. */
        TOO_LARGE,
        /** A header was found but its size is not credible. */
        INVALID
    }

    static final class Analysis {
        final Kind kind;
        final byte[] moov;
        final long moovOffset;
        final int moovSize;
        final long missingOffset;
        final int missingLength;
        final int blocks;

        private Analysis(Kind kind, byte[] moov, long moovOffset, int moovSize,
                         long missingOffset, int missingLength, int blocks) {
            this.kind = kind;
            this.moov = moov;
            this.moovOffset = moovOffset;
            this.moovSize = moovSize;
            this.missingOffset = missingOffset;
            this.missingLength = missingLength;
            this.blocks = blocks;
        }

        static Analysis of(Kind kind) {
            return new Analysis(kind, null, -1L, 0, -1L, 0, 0);
        }
    }

    private TailMoovLocator() {
    }

    /**
     * Start of the last {@code blocks} blocks, clamped to zero. The window
     * always covers the final byte of the file.
     */
    static long windowStart(long declaredSize, int blocks, int blockBytes) {
        if (declaredSize <= 0L || blocks <= 0 || blockBytes <= 0) {
            return 0L;
        }
        final long window = (long) blocks * blockBytes;
        return Math.max(0L, declaredSize - window);
    }

    /**
     * Whole blocks touched by a range starting at {@code offset}. This is what
     * the fetcher bills, because it aligns the start down and asks for full
     * blocks.
     */
    static int blocksForRange(long offset, long length, int blockBytes) {
        if (offset < 0L || length <= 0L || blockBytes <= 0) {
            return 0;
        }
        final long aligned = (offset / blockBytes) * blockBytes;
        final long span = offset - aligned + length;
        return (int) ((span + blockBytes - 1L) / blockBytes);
    }

    static byte[] combine(byte[] earlier, byte[] later) {
        if (earlier == null || earlier.length == 0) {
            return later;
        }
        if (later == null || later.length == 0) {
            return earlier;
        }
        final byte[] out = new byte[earlier.length + later.length];
        System.arraycopy(earlier, 0, out, 0, earlier.length);
        System.arraycopy(later, 0, out, earlier.length, later.length);
        return out;
    }

    /**
     * @param data     bytes starting at {@code dataStart}
     * @param dataStart file offset of {@code data[0]}
     * @param declaredSize the media's declared size, used to reject atoms
     *                     that claim to run past the end
     */
    static Analysis analyze(byte[] data, long dataStart, long declaredSize,
                            int blockBytes) {
        if (data == null || data.length < MIN_MOOV_BYTES) {
            return Analysis.of(Kind.NOT_FOUND);
        }
        final int tag = lastIndexOfMoov(data);
        if (tag < 4) {
            return Analysis.of(Kind.NOT_FOUND);
        }
        final int headerOffset = tag - 4;
        final long moovSize = readU32(data, headerOffset);
        if (moovSize < MIN_MOOV_BYTES || moovSize > MAX_MOOV_BYTES) {
            return Analysis.of(Kind.INVALID);
        }
        final long moovOffset = dataStart + headerOffset;
        if (declaredSize > 0L && moovOffset + moovSize > declaredSize) {
            return Analysis.of(Kind.INVALID);
        }
        final int blocks = blocksForRange(moovOffset, moovSize, blockBytes);
        if (blocks > MAX_BLOCKS) {
            return Analysis.of(Kind.TOO_LARGE);
        }
        if ((long) headerOffset + moovSize <= data.length) {
            final byte[] moov = new byte[(int) moovSize];
            System.arraycopy(data, headerOffset, moov, 0, (int) moovSize);
            return new Analysis(Kind.COMPLETE, moov, moovOffset, (int) moovSize,
                    -1L, 0, blocks);
        }
        final long dataEnd = dataStart + data.length;
        final long missingOffset = dataEnd;
        final long missingLength = moovOffset + moovSize - dataEnd;
        if (missingLength <= 0L || missingLength > Integer.MAX_VALUE) {
            return Analysis.of(Kind.INVALID);
        }
        return new Analysis(Kind.NEED_PREFIX, null, moovOffset, (int) moovSize,
                missingOffset, (int) missingLength, blocks);
    }

    /** Scans backwards: a tail moov is the last one before the file end. */
    private static int lastIndexOfMoov(byte[] data) {
        for (int i = data.length - 4; i >= 4; i--) {
            if (data[i] == 'm' && data[i + 1] == 'o'
                    && data[i + 2] == 'o' && data[i + 3] == 'v') {
                return i;
            }
        }
        return -1;
    }

    private static long readU32(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 24)
                | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8)
                | (data[offset + 3] & 0xffL);
    }
}
