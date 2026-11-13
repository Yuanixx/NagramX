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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Locates the moov atom of a complete local MP4 and turns it into the track
 * information the MediaCodec decoder needs.
 *
 * A fragmented MP4 has an empty sample table in moov. Its codec configuration
 * still lives there, so that part is read as usual; the keyframe positions come
 * from the moof chain instead of stbl.
 */
public final class KeyframeSource {

    private static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;
    private static final int MAX_TOP_LEVEL_BOXES = 256;

    public static final class Result {
        public final MoovKeyframeParser.TrackInfo track;
        public final boolean fragmented;
        public final long moovOffset;
        public final long moovSize;
        public final long fileSize;

        Result(MoovKeyframeParser.TrackInfo track, boolean fragmented,
               long moovOffset, long moovSize, long fileSize) {
            this.track = track;
            this.fragmented = fragmented;
            this.moovOffset = moovOffset;
            this.moovSize = moovSize;
            this.fileSize = fileSize;
        }
    }

    private KeyframeSource() {
    }

    /**
     * @return parsed source, or null when no usable moov atom exists
     */
    public static Result read(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            final long length = raf.length();
            long position = 0;
            int boxes = 0;
            final byte[] header = new byte[16];
            while (position + 8 <= length && boxes++ < MAX_TOP_LEVEL_BOXES) {
                raf.seek(position);
                if (raf.read(header, 0, 8) < 8) {
                    return null;
                }
                long size = u32(header, 0);
                final String type = type(header, 4);
                int headerSize = 8;
                if (size == 1) {
                    if (position + 16 > length) {
                        return null;
                    }
                    raf.readFully(header, 8, 8);
                    size = u64(header, 8);
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - position;
                }
                if (size < headerSize || size > length - position) {
                    return null;
                }
                if ("moov".equals(type)) {
                    if (size > MAX_MOOV_BYTES) {
                        PreviewLog.d("SeekPreview: moov too large " + size);
                        return null;
                    }
                    final byte[] moov = new byte[(int) size];
                    raf.seek(position);
                    raf.readFully(moov);
                    final boolean fragmented = hasChild(moov, "mvex")
                            || hasMoofAfter(raf, position + size, length);
                    MoovKeyframeParser.TrackInfo track;
                    if (fragmented) {
                        // stbl is empty in a fragmented file, so only the codec
                        // configuration can be read there. The keyframes have to
                        // come from the moof chain.
                        track = MoovKeyframeParser.parseTrackConfig(moov);
                        if (track != null) {
                            track.keyframes.addAll(FragmentedMp4Parser.parse(
                                    raf, position + size, length,
                                    track.timescale, track.trackId));
                        }
                    } else {
                        track = MoovKeyframeParser.parseTrack(moov);
                    }
                    PreviewLog.d("SeekPreview: moov offset=" + position
                            + " size=" + size
                            + " fragmented=" + fragmented
                            + " track=" + (track != null ? track.mime : "null")
                            + " keyframes=" + (track != null ? track.keyframes.size() : 0));
                    return new Result(track, fragmented, position, size, length);
                }
                position += size;
            }
        } catch (Throwable e) {
            PreviewLog.e("SeekPreview: moov read failed", e);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignore) {
                }
            }
        }
        return null;
    }

    /**
     * Walks the top-level boxes that follow the moov atom and reports whether
     * the file continues with a moof. Only box headers are read, so this stays
     * cheap even for a growing 300MB download. A file with an empty stbl but a
     * readable moov is fragmented exactly when such a moof exists, even if the
     * moov itself has no mvex child.
     */
    private static boolean hasMoofAfter(RandomAccessFile raf, long position, long length) {
        final byte[] header = new byte[16];
        int boxes = 0;
        while (position + 8 <= length && boxes++ < MAX_TOP_LEVEL_BOXES) {
            try {
                raf.seek(position);
                if (raf.read(header, 0, 8) < 8) {
                    return false;
                }
                long size = u32(header, 0);
                final String type = type(header, 4);
                int headerSize = 8;
                if (size == 1) {
                    if (position + 16 > length) {
                        return false;
                    }
                    raf.readFully(header, 8, 8);
                    size = u64(header, 8);
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - position;
                }
                if (size < headerSize || size > length - position) {
                    return false;
                }
                if ("moof".equals(type)) {
                    return true;
                }
                position += size;
            } catch (Throwable ignore) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasChild(byte[] atom, String childType) {
        if (atom == null || atom.length < 8) {
            return false;
        }
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(atom).order(ByteOrder.BIG_ENDIAN);
            final long atomSize = u32(atom, 0);
            int end = atomSize == 1 ? (int) Math.min(atom.length, u64(atom, 8))
                    : (int) Math.min(atom.length, atomSize);
            if (end <= 8) {
                end = atom.length;
            }
            int position = (atomSize == 1) ? 16 : 8;
            while (position + 8 <= end) {
                final long size = buffer.getInt(position) & 0xffffffffL;
                final String type = type(atom, position + 4);
                int headerSize = 8;
                long bodySize = size;
                if (size == 1) {
                    if (position + 16 > end) {
                        return false;
                    }
                    bodySize = buffer.getLong(position + 8);
                    headerSize = 16;
                } else if (size == 0) {
                    bodySize = end - position;
                }
                if (bodySize < headerSize || position + bodySize > end) {
                    return false;
                }
                if (childType.equals(type)) {
                    return true;
                }
                position += (int) bodySize;
            }
        } catch (Throwable ignore) {
        }
        return false;
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
