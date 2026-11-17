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

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.ConnectionsManager;
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
 * <p>The window always reaches the declared end of the file; the old block
 * alignment dropped the final partial block and could never see an atom that
 * lived there. The probe starts with the last two blocks, moves the window
 * back while no atom header is visible, and stops at eight blocks (2MB).
 * Once the header is found, a cut atom is completed with one exact prefix
 * request. Nothing is retried: a failed or oversized probe keeps the
 * retriever path.
 *
 * <p>The probe is skipped on metered or roaming networks, for encrypted
 * documents, when the network layer is off and when aggressiveness is zero.
 * Successful atoms are cached by {@code (dc_id, document.id, size)}, so
 * reopening the same message costs no request at all.
 */
final class TailMoovFetcher {

    private static final String CACHE_DIR = "moov_cache";
    /** One probe must never take longer than this in total. */
    private static final long TOTAL_BUDGET_MS = 12_000L;
    /** Per-request ceiling; the budget above may shorten it. */
    private static final long FETCH_TIMEOUT_MS = 2500L;

    private TailMoovFetcher() {
    }

    /** Returns a parsed source, or null when the probe gave up. */
    static KeyframeSource.Result fetch(TLRPC.Document document, int account,
                                       long declaredSize) {
        if (document == null || account < 0 || declaredSize <= 0) {
            return null;
        }
        if (document.key != null && document.key.length > 0) {
            PreviewLog.d("SeekPreview: tail moov skipped (encrypted)");
            return null;
        }
        if (!networkAllowed()) {
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
            PreviewLog.d("SeekPreview: tail moov parse failed size=" + moov.length);
            return null;
        }
        PreviewLog.d("SeekPreview: tail moov ok size=" + moov.length
                + " keyframes=" + track.keyframes.size());
        return new KeyframeSource.Result(track, false,
                declaredSize - moov.length, moov.length, declaredSize);
    }

    /**
     * Probes backwards without retries. The first read covers the last two
     * blocks; every later read prepends more blocks, up to the eight-block
     * cap. Any failure ends the probe and leaves the retriever in charge.
     */
    private static byte[] probeTail(TLRPC.Document document, int account,
                                    long declaredSize) {
        final long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        final int block = FileRangeFetcher.blockBytes();
        byte[] data = null;
        long dataStart = 0L;
        int blocks = TailMoovLocator.INITIAL_BLOCKS;
        while (blocks <= TailMoovLocator.MAX_BLOCKS) {
            final long start = TailMoovLocator.windowStart(declaredSize, blocks, block);
            if (data == null) {
                final int length = (int) Math.min(declaredSize - start,
                        (long) blocks * block);
                final byte[] fetched = fetchRange(document, account, start, length, deadline);
                if (fetched == null) {
                    PreviewLog.d("SeekPreview: tail moov probe failed at " + length + " bytes");
                    return null;
                }
                data = fetched;
                dataStart = start;
            } else if (start < dataStart) {
                // Only the new prefix goes on the wire; the tail is reused.
                final int length = (int) Math.min(dataStart - start, Integer.MAX_VALUE);
                final byte[] prefix = fetchRange(document, account, start, length, deadline);
                if (prefix == null) {
                    PreviewLog.d("SeekPreview: tail moov expansion failed at "
                            + blocks + " blocks");
                    return null;
                }
                data = TailMoovLocator.combine(prefix, data);
                dataStart = start;
            } else {
                // The window already covers the whole file, so more blocks
                // cannot reveal anything new.
                break;
            }

            final TailMoovLocator.Analysis result = TailMoovLocator.analyze(
                    data, dataStart, declaredSize, block);
            switch (result.kind) {
                case COMPLETE:
                    return result.moov;
                case NEED_PREFIX: {
                    final byte[] prefix = fetchRange(document, account,
                            result.missingOffset, result.missingLength, deadline);
                    if (prefix == null) {
                        PreviewLog.d("SeekPreview: tail moov prefix failed size="
                                + result.moovSize);
                        return null;
                    }
                    final byte[] combined = TailMoovLocator.combine(data, prefix);
                    final TailMoovLocator.Analysis done = TailMoovLocator.analyze(
                            combined, dataStart, declaredSize, block);
                    if (done.kind == TailMoovLocator.Kind.COMPLETE) {
                        return done.moov;
                    }
                    PreviewLog.d("SeekPreview: tail moov still incomplete kind="
                            + done.kind);
                    return null;
                }
                case TOO_LARGE:
                    PreviewLog.d("SeekPreview: tail moov too large size="
                            + result.moovSize + " blocks=" + result.blocks);
                    return null;
                case INVALID:
                    PreviewLog.d("SeekPreview: tail moov invalid header");
                    return null;
                case NOT_FOUND:
                default:
                    if (dataStart <= 0L) {
                        PreviewLog.d("SeekPreview: tail moov not found in "
                                + data.length + " bytes");
                        return null;
                    }
                    blocks += 2;
                    break;
            }
        }
        PreviewLog.d("SeekPreview: tail moov not found in "
                + TailMoovLocator.MAX_BLOCKS + " blocks");
        return null;
    }

    /** One range read with no retry, clamped to what is left of the budget. */
    private static byte[] fetchRange(TLRPC.Document document, int account,
                                     long offset, int length, long deadline) {
        if (length <= 0) {
            return null;
        }
        final long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0L) {
            return null;
        }
        try {
            final FileRangeFetcher.FetchResult result = FileRangeFetcher.fetchSync(
                    account, document, offset, length,
                    Math.min(FETCH_TIMEOUT_MS, remaining), remaining, null);
            return result.ok() ? result.data : null;
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: tail moov read failed off=" + offset, e);
            return null;
        }
    }

    /**
     * Previews are optional traffic: no mobile data, no roaming, no traffic
     * when the user turned the layer off.
     */
    @SuppressWarnings("deprecation")
    private static boolean networkAllowed() {
        if (!tw.nekomimi.nekogram.NekoConfig.videoPreviewNetworkFetch.Bool()) {
            PreviewLog.d("SeekPreview: tail moov skipped (setting)");
            return false;
        }
        if (tw.nekomimi.nekogram.NekoConfig.videoPreviewFetchAggressiveness.Int() == 0) {
            PreviewLog.d("SeekPreview: tail moov skipped (aggressiveness 0)");
            return false;
        }
        try {
            final ConnectivityManager manager = (ConnectivityManager)
                    ApplicationLoader.applicationContext
                            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return true;
            }
            final NetworkInfo info = manager.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                PreviewLog.d("SeekPreview: tail moov skipped (offline)");
                return false;
            }
            if (info.isRoaming()) {
                PreviewLog.d("SeekPreview: tail moov skipped (roaming)");
                return false;
            }
            if (manager.isActiveNetworkMetered()) {
                PreviewLog.d("SeekPreview: tail moov skipped (metered)");
                return false;
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    private static File cacheDir() {
        return new File(ApplicationLoader.applicationContext.getCacheDir(), CACHE_DIR);
    }

    private static File cacheFileFor(TLRPC.Document document, long declaredSize) {
        final int dcId = document.dc_id > 0
                ? document.dc_id : ConnectionsManager.DEFAULT_DATACENTER_ID;
        return new File(cacheDir(),
                dcId + "_" + document.id + "_" + declaredSize + ".moov");
    }

    private static byte[] readCached(File file) {
        try {
            if (!file.isFile() || file.length() < TailMoovLocator.MIN_MOOV_BYTES) {
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
