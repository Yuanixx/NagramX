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

package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strict, Android-free parser for the per-track metadata carried by one ISO-BMFF moof.
 *
 * <p>A moof may contain several traf boxes (for example, separate audio and video tracks).
 * The returned fragments share the same containing moof byte position; callers attach that
 * position and the following mdat end to every track fragment.</p>
 *
 * <p>Only tracks already described by the moov are returned. A malformed known track rejects
 * the whole moof so a partial or truncated sample table can never become a seek index.</p>
 */
final class FragmentMoofParser {

    private static final int TRAF = cc("traf");
    private static final int TFHD = cc("tfhd");
    private static final int TFDT = cc("tfdt");
    private static final int TRUN = cc("trun");
    private static final int SAMPLE_FLAG_NON_SYNC = 0x00010000;
    private static final int MAX_SAMPLES_PER_FRAGMENT = 100_000;

    private FragmentMoofParser() {
    }

    static final class Track {
        int id;
        int timescale = 1;
        long duration;
        boolean video;
        boolean audio;
        int defaultDuration;
        int defaultFlags;

        @Override
        public String toString() {
            return "Track{" + id + " ts=" + timescale + " video=" + video + "}";
        }
    }

    static final class Fragment {
        int trackId;
        int samples;
        long pos;
        long end;
        long tfdt;
        long durTicks;
        boolean sync;
    }

    /**
     * Parses each known track's traf. Returns null for an empty or malformed moof.
     * Unknown track IDs are skipped, which allows metadata tracks not represented in
     * the seek-index track table without hiding valid audio/video traf boxes.
     */
    static List<Fragment> parse(byte[] data, Map<Integer, Track> tracks) {
        if (data == null || data.length < 8 || tracks == null || tracks.isEmpty()) {
            return null;
        }
        List<int[]> trafs = children(data, 0, data.length, TRAF);
        if (trafs == null || trafs.isEmpty()) {
            return null;
        }

        List<Fragment> result = new ArrayList<>();
        for (int[] traf : trafs) {
            int[] tfhd = child(data, traf[0], traf[1], TFHD);
            if (tfhd == null || tfhd[1] - tfhd[0] < 8) {
                return null;
            }

            int tfhdBody = tfhd[0];
            int tfhdFlags = i32(data, tfhdBody) & 0x00FFFFFF;
            int trackId = i32(data, tfhdBody + 4);
            Track track = tracks.get(trackId);
            if (track == null || (!track.video && !track.audio)) {
                // Ignore subtitle, metadata, and other non A/V tracks. They are
                // not random-access video points and must not become audio spans.
                continue;
            }

            int p = tfhdBody + 8;
            int defaultDuration = track.defaultDuration;
            int defaultFlags = track.defaultFlags;
            if ((tfhdFlags & 0x000001) != 0) {
                p += 8; // base_data_offset
            }
            if ((tfhdFlags & 0x000002) != 0) {
                p += 4; // sample_description_index
            }
            if ((tfhdFlags & 0x000008) != 0) {
                if (p + 4 > tfhd[1]) {
                    return null;
                }
                defaultDuration = i32(data, p);
                p += 4;
            }
            if ((tfhdFlags & 0x000010) != 0) {
                p += 4; // default_sample_size
            }
            if ((tfhdFlags & 0x000020) != 0) {
                if (p + 4 > tfhd[1]) {
                    return null;
                }
                defaultFlags = i32(data, p);
                p += 4;
            }
            if (p > tfhd[1]) {
                return null;
            }

            int[] tfdt = child(data, traf[0], traf[1], TFDT);
            if (tfdt == null || tfdt[0] >= tfdt[1]) {
                return null;
            }
            int tfdtVersion = data[tfdt[0]] & 0xFF;
            final long decodeTime;
            if (tfdtVersion == 1) {
                if (tfdt[1] - tfdt[0] < 12) {
                    return null;
                }
                decodeTime = i64(data, tfdt[0] + 4);
            } else if (tfdtVersion == 0) {
                if (tfdt[1] - tfdt[0] < 8) {
                    return null;
                }
                decodeTime = u32(data, tfdt[0] + 4);
            } else {
                return null;
            }
            // tfdt is an unsigned value. Values outside signed-long range cannot
            // be represented by the index and must not wrap into negative time.
            if (decodeTime < 0L) {
                return null;
            }

            Fragment fragment = new Fragment();
            fragment.trackId = track.id;
            fragment.tfdt = decodeTime;
            fragment.sync = true;

            List<int[]> truns = children(data, traf[0], traf[1], TRUN);
            if (truns == null) {
                return null;
            }
            boolean firstSample = true;
            for (int[] trun : truns) {
                if (trun[1] - trun[0] < 8) {
                    return null;
                }
                int q = trun[0];
                int trunFlags = i32(data, q) & 0x00FFFFFF;
                long sampleCount = u32(data, q + 4);
                if (sampleCount > MAX_SAMPLES_PER_FRAGMENT - fragment.samples) {
                    return null;
                }
                q += 8;
                if ((trunFlags & 0x000001) != 0) {
                    q += 4; // data_offset
                }

                int firstSampleFlags = defaultFlags;
                boolean hasFirstSampleFlags = (trunFlags & 0x000004) != 0;
                if (hasFirstSampleFlags) {
                    if (q + 4 > trun[1]) {
                        return null;
                    }
                    firstSampleFlags = i32(data, q);
                    q += 4;
                }
                if (q > trun[1]) {
                    return null;
                }

                for (int sample = 0; sample < sampleCount; sample++) {
                    long sampleDuration = defaultDuration & 0xFFFFFFFFL;
                    int sampleFlags = (sample == 0 && hasFirstSampleFlags)
                            ? firstSampleFlags : defaultFlags;

                    if ((trunFlags & 0x000100) != 0) {
                        if (q + 4 > trun[1]) {
                            return null;
                        }
                        sampleDuration = u32(data, q);
                        q += 4;
                    }
                    if ((trunFlags & 0x000200) != 0) {
                        if (q + 4 > trun[1]) {
                            return null;
                        }
                        q += 4; // sample_size
                    }
                    if ((trunFlags & 0x000400) != 0) {
                        if (q + 4 > trun[1]) {
                            return null;
                        }
                        sampleFlags = i32(data, q);
                        q += 4;
                    }
                    if ((trunFlags & 0x000800) != 0) {
                        if (q + 4 > trun[1]) {
                            return null;
                        }
                        q += 4; // composition_time_offset
                    }

                    if (firstSample) {
                        fragment.sync = (sampleFlags & SAMPLE_FLAG_NON_SYNC) == 0;
                        firstSample = false;
                    }
                    fragment.durTicks += sampleDuration;
                    fragment.samples++;
                }
                if (q > trun[1]) {
                    return null;
                }
            }
            result.add(fragment);
        }
        return result.isEmpty() ? null : result;
    }

    /** Returns the bodies of all direct child boxes of the requested type. */
    private static List<int[]> children(byte[] data, int start, int end, int type) {
        if (start < 0 || end < start || end > data.length) {
            return null;
        }
        List<int[]> result = new ArrayList<>();
        int p = start;
        while (p + 8 <= end) {
            long size = u32(data, p);
            int headerSize = 8;
            if (size == 1L) {
                if (p + 16 > end) {
                    return null;
                }
                size = i64(data, p + 8);
                headerSize = 16;
            } else if (size == 0L) {
                size = end - p;
            }
            if (size < headerSize || size > Integer.MAX_VALUE || size > end - p) {
                return null;
            }
            if (i32(data, p + 4) == type) {
                result.add(new int[]{p + headerSize, p + (int) size});
            }
            p += (int) size;
        }
        return p == end ? result : null;
    }

    private static int[] child(byte[] data, int start, int end, int type) {
        List<int[]> matches = children(data, start, end, type);
        return matches == null || matches.isEmpty() ? null : matches.get(0);
    }

    private static int cc(String value) {
        return (value.charAt(0) << 24) | (value.charAt(1) << 16)
                | (value.charAt(2) << 8) | value.charAt(3);
    }

    private static int i32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static long u32(byte[] data, int offset) {
        return i32(data, offset) & 0xFFFFFFFFL;
    }

    private static long i64(byte[] data, int offset) {
        return (u32(data, offset) << 32) | u32(data, offset + 4);
    }
}
