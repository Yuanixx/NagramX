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

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.FileRangeFetcher;
import org.telegram.ui.Components.MoovKeyframeParser;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * Fetches the moov atom of a video whose directory sits past the downloaded
 * prefix (a "tail moov" file).
 *
 * <p>Without this, a partial .temp whose first bytes do not contain moov falls
 * straight to {@link RetrieverFrameDecoder}: there is no keyframe table, so
 * the range fetcher has nothing to ask for. The probe is deliberately small —
 * at most two request blocks from the end of the declared size — because a
 * 1–8MB moov would cost dozens of range requests and easily trip the
 * account's premium throttling. Files with a bigger moov keep using the
 * retriever; the log says so instead of silently degrading.
 *
 * <p>Successful atoms are cached next to the app cache, so reopening the same
 * message costs no request at all.
 */
final class TailMoovFetcher {

    private static final String CACHE_DIR = "moov_cache";
    /** Two blocks are enough for the small moov atoms used by short videos. */
    private static final int MAX_TAIL_BLOCKS = 2;
    private static final long MAX_MOOV_BYTES = 64L * 1024 * 1024;
    private static final long FETCH_TIMEOUT_MS = 2500;

    private TailMoovFetcher() {
    }

    /** Returns a parsed source, or null when the probe gave up. */
    static KeyframeSource.Result fetch(TLRPC.Document document, int account,
                                       long declaredSize) {
        if (document == null || declaredSize <= 0) {
            return null;
        }
        final File cacheFile = cacheFileFor(document, declaredSize);
        byte[] moov = readCached(cacheFile);
        if (moov == null) {
            moov = probeTail(document, account, declaredSize);
            if (moov == null) {
                return null;
            }
            writeCached(cacheFile, moov);
        }
        final MoovKeyframeParser.TrackInfo track = MoovKeyframeParser.parseTrack(moov);
        if (track == null || track.keyframes.isEmpty()) {
            cacheFile.delete();
            return null;
        }
        PreviewLog.d("SeekPreview: tail moov ok size=" + moov.length
                + " keyframes=" + track.keyframes.size());
        return new KeyframeSource.Result(track, false,
                declaredSize - moov.length, moov.length, declaredSize);
    }

    /** Reads at most two blocks from the end and cuts the moov atom out of it. */
    private static byte[] probeTail(TLRPC.Document document, int account,
                                    long declaredSize) {
        final int block = FileRangeFetcher.blockBytes();
        final long window = (long) block * MAX_TAIL_BLOCKS;
        final long tailStart = Math.max(0L,
                ((declaredSize - window) / block) * block);
        final int length = (int) Math.min(declaredSize - tailStart, window);
        if (length < 16) {
            return null;
        }
        final FileRangeFetcher.FetchResult result = FileRangeFetcher.fetchSync(account, document,
                tailStart, length, FETCH_TIMEOUT_MS, null);
        if (!result.ok() || result.data.length < 16) {
            return null;
        }
        final byte[] tail = result.data;
        final int tag = lastIndexOfMoov(tail);
        if (tag < 4) {
            return null;
        }
        final long moovSize = readU32(tail, tag - 4);
        if (moovSize < 16 || moovSize > MAX_MOOV_BYTES) {
            return null;
        }
        final int headerOffset = tag - 4;
        if (headerOffset + moovSize > tail.length) {
            // The atom starts inside the window but is longer than it; asking
            // for the rest would cost many requests, so give up loudly.
            PreviewLog.d("SeekPreview: tail moov too large size=" + moovSize
                    + " window=" + length);
            return null;
        }
        final byte[] moov = new byte[(int) moovSize];
        System.arraycopy(tail, headerOffset, moov, 0, (int) moovSize);
        return moov;
    }

    /** Scans backwards: a tail moov ends at the end of the file. */
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

    private static File cacheDir() {
        return new File(ApplicationLoader.applicationContext.getCacheDir(), CACHE_DIR);
    }

    private static File cacheFileFor(TLRPC.Document document, long declaredSize) {
        return new File(cacheDir(),
                document.id + "_" + declaredSize + ".moov");
    }

    private static byte[] readCached(File file) {
        try {
            if (!file.isFile() || file.length() < 16) {
                return null;
            }
            return Files.readAllBytes(file.toPath());
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeCached(File file, byte[] moov) {
        try {
            final File dir = cacheDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(moov);
            }
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: tail moov cache write failed", e);
        }
    }
}
