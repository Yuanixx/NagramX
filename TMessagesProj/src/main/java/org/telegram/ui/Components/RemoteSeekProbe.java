/*
 * Copyright (C) 2024 NagramX Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 */
package org.telegram.ui.Components;

import androidx.media3.extractor.SeekPoint;

import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.seek.RemoteFragmentRules;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the byte offset of the fragmented-MP4 fragment that contains a given time,
 * without downloading the media around it.
 *
 * <p>The result is a proved fragment interval, not a single point: the probe only succeeds
 * when the target lies inside [tfdt, next tfdt). Otherwise it returns null so the caller
 * never mistakes a low-precision guess for a valid seek.
 */
public final class RemoteSeekProbe {

    private static final int LOCAL_MOOV_LIMIT = 256 * 1024;
    private static final int PROBE_BYTES = 1024 * 1024;
    private static final int MAX_PROBES = 6;
    private static final long TOTAL_BUDGET_MS = 12000L;
    private static final long FETCH_TIMEOUT_MS = 2000L;
    private static final long FETCH_BUDGET_MS = 3000L;

    private RemoteSeekProbe() {
    }

    /** moov box bytes together with their absolute position in the document. */
    private static final class MoovBox {
        byte[] data;
        long offset;
    }

    /** A moof header discovered in a fetched block. */
    private static final class Fragment {
        long position;
        long end;
        long timeUs;
        long spanUs;
        int size;
    }

    /** A probe result with the proved fragment span, not just a single point. */
    public static final class ProbeResult {
        public final SeekPoint point;
        public final long spanUs;
        public final int size;

        ProbeResult(SeekPoint point, long spanUs, int size) {
            this.point = point;
            this.spanUs = spanUs;
            this.size = size;
        }
    }

    /**
     * Runs on a background thread. Returns null on flood, timeout, non-convergence or
     * unusable data; callers must keep their old (safe, non-zero-clamping) behaviour.
     */
    public static ProbeResult find(int account, TLRPC.Document document,
                                   String localPath, long fileLength,
                                   long targetTimeUs, double durationSec) {
        if (document == null || fileLength <= 0 || targetTimeUs <= 0
                || Double.isNaN(durationSec) || Double.isInfinite(durationSec)
                || durationSec <= 0) {
            return null;
        }
        final long started = System.currentTimeMillis();
        final long deadline = started + TOTAL_BUDGET_MS;
        try {
            MoovBox moov = readMoov(localPath, account, document);
            if (moov == null || System.currentTimeMillis() >= deadline) {
                return null;
            }
            MoovKeyframeParser.TrackInfo track = MoovKeyframeParser.parseTrackConfig(moov.data);
            if (track == null || track.timescale <= 0) {
                return null;
            }
            final long moovEnd = moov.offset + moov.data.length;
            final long trackId = track.trackId;
            final long timescale = track.timescale;

            long lo = Math.min(moovEnd, fileLength - 1);
            long hi = fileLength - 1;
            Fragment converged = null;
            int requests = 0;
            for (int probe = 0; probe < MAX_PROBES && lo < hi; probe++) {
                final long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                final long guess = lo + (hi - lo) / 2L;
                final long start = Math.max(moovEnd, guess - PROBE_BYTES / 2L);
                final FileRangeFetcher.FetchResult result = FileRangeFetcher.fetchSync(
                        account, document, alignDown(start), PROBE_BYTES,
                        FETCH_TIMEOUT_MS, Math.min(FETCH_BUDGET_MS, remaining), null);
                requests++;
                if (result == null || !result.ok() || result.data == null) {
                    FileLog.d("FMPIDX: remote probe stopped status="
                            + (result != null ? result.status : "null")
                            + (result != null && result.status == FileRangeFetcher.FetchResult.Status.DISABLED
                                    ? " (network fetch off, using local index)" : ""));
                    return null;
                }
                final long dataOffset = alignDown(start);
                Fragment found = nearestFragment(result.data, dataOffset, fileLength,
                        timescale, trackId, targetTimeUs);
                if (found == null) {
                    lo = Math.min(hi, dataOffset + result.data.length);
                    continue;
                }
                if (RemoteFragmentRules.containsTarget(found.timeUs, found.spanUs, targetTimeUs)) {
                    converged = found;
                    break;
                }
                if (found.timeUs <= targetTimeUs) {
                    lo = Math.min(hi, found.end);
                } else {
                    hi = Math.max(lo, found.position - 1);
                }
            }

            if (converged == null) {
                FileLog.d("FMPIDX: remote probe not converged target=" + targetTimeUs
                        + " probes=" + requests);
                return null;
            }
            FileLog.d("FMPIDX: remote probe pos=" + converged.position
                    + " t=" + converged.timeUs + " span=" + converged.spanUs
                    + " target=" + targetTimeUs + " probes=" + requests
                    + " ms=" + (System.currentTimeMillis() - started));
            return new ProbeResult(
                    new SeekPoint(converged.timeUs, converged.position),
                    converged.spanUs, converged.size);
        } catch (Throwable e) {
            FileLog.e("FMPIDX: remote probe failed", e);
            return null;
        }
    }

    private static long alignDown(long value) {
        return value - (value % 4096L);
    }

    /** Reads the moov box from the local temp first, falling back to a remote block. */
    private static MoovBox readMoov(String localPath, int account, TLRPC.Document document) {
        if (localPath != null) {
            try {
                RandomAccessFile raf = new RandomAccessFile(new File(localPath), "r");
                try {
                    int len = (int) Math.min(LOCAL_MOOV_LIMIT, raf.length());
                    byte[] head = new byte[len];
                    raf.readFully(head);
                    MoovBox box = findMoov(head, 0);
                    if (box != null) {
                        return box;
                    }
                } finally {
                    raf.close();
                }
            } catch (Throwable ignore) {
            }
        }
        FileRangeFetcher.FetchResult result = FileRangeFetcher.fetchSync(
                account, document, 0, LOCAL_MOOV_LIMIT, FETCH_TIMEOUT_MS,
                FETCH_BUDGET_MS, null);
        if (result == null || !result.ok() || result.data == null) {
            return null;
        }
        return findMoov(result.data, 0);
    }

    private static MoovBox findMoov(byte[] data, long dataOffset) {
        int position = 0;
        while (position + 8 <= data.length) {
            long size = u32(data, position);
            int headerSize = 8;
            if (size == 1) {
                if (position + 16 > data.length) {
                    return null;
                }
                size = u64(data, position + 8);
                headerSize = 16;
            }
            if (size < headerSize || size > data.length - position) {
                return null;
            }
            if (isType(data, position + 4, "moov")) {
                MoovBox moov = new MoovBox();
                moov.data = new byte[(int) size];
                System.arraycopy(data, position, moov.data, 0, moov.data.length);
                moov.offset = dataOffset + position;
                return moov;
            }
            position += (int) size;
        }
        return null;
    }

    private static Fragment nearestFragment(byte[] data, long dataOffset, long fileLength,
                                             long timescale, long trackId, long targetTimeUs) {
        List<Fragment> fragments = new ArrayList<>();
        int position = 0;
        while (position + 8 <= data.length) {
            if (!isType(data, position + 4, "moof")) {
                position++;
                continue;
            }
            long size = u32(data, position);
            if (size < 8 || size > data.length - position) {
                position++;
                continue;
            }
            long absolute = dataOffset + position;
            if (absolute + size > fileLength) {
                position++;
                continue;
            }
            byte[] moof = new byte[(int) size];
            System.arraycopy(data, position, moof, 0, moof.length);
            long timeUs = fragmentTimeUs(moof, timescale, trackId);
            if (timeUs >= 0L) {
                Fragment f = new Fragment();
                f.position = absolute;
                f.end = absolute + size;
                f.size = (int) size;
                f.timeUs = timeUs;
                fragments.add(f);
            }
            position += (int) size;
        }
        if (fragments.isEmpty()) {
            return null;
        }
        long[] times = new long[fragments.size()];
        for (int i = 0; i < fragments.size(); i++) {
            times[i] = fragments.get(i).timeUs;
        }
        int best = RemoteFragmentRules.bestAtOrBefore(times, times.length, targetTimeUs);
        if (best < 0) {
            // Only later fragments in the window; use the earliest as a search bound.
            best = 0;
            for (int i = 1; i < times.length; i++) {
                if (times[i] < times[best]) {
                    best = i;
                }
            }
        }
        Fragment chosen = fragments.get(best);
        chosen.spanUs = RemoteFragmentRules.spanAfter(times, times.length, best);
        return chosen;
    }

    /** tfdt baseMediaDecodeTime of the traf whose tfhd matches the video track. */
    private static long fragmentTimeUs(byte[] moof, long timescale, long trackId) {
        int i = 0;
        while (i + 8 <= moof.length) {
            if (!isType(moof, i + 4, "traf")) {
                i++;
                continue;
            }
            long trafSize = u32(moof, i);
            if (trafSize < 8 || i + trafSize > moof.length) {
                i++;
                continue;
            }
            int end = (int) (i + trafSize);
            int p = i + 8;
            long foundTrackId = 0;
            long ticks = -1L;
            while (p + 8 <= end) {
                long childSize = u32(moof, p);
                if (childSize < 8 || p + childSize > end) {
                    break;
                }
                if (isType(moof, p + 4, "tfhd") && childSize >= 16) {
                    foundTrackId = u32(moof, p + 12);
                } else if (isType(moof, p + 4, "tfdt") && childSize >= 16) {
                    int version = moof[p + 8] & 0xff;
                    if (version == 1 && childSize >= 20) {
                        ticks = u64(moof, p + 12);
                    } else {
                        ticks = u32(moof, p + 12);
                    }
                }
                p += (int) childSize;
            }
            if (RemoteFragmentRules.validDecodeTime(ticks)
                    && (trackId <= 0 || foundTrackId == trackId)) {
                return (long) (ticks * 1000000.0 / timescale);
            }
            i = end;
        }
        return -1L;
    }

    private static boolean isType(byte[] data, int offset, String type) {
        if (offset < 0 || offset + 4 > data.length) {
            return false;
        }
        return (data[offset] & 0xff) == type.charAt(0)
                && (data[offset + 1] & 0xff) == type.charAt(1)
                && (data[offset + 2] & 0xff) == type.charAt(2)
                && (data[offset + 3] & 0xff) == type.charAt(3);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    private static long u64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (long) (data[offset + i] & 0xff);
        }
        return value;
    }
}
