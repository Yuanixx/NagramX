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

import org.telegram.messenger.FileLoadOperation;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Fetches a small byte range of a document and returns it, without touching the
 * file that playback is filling in.
 *
 * A preview needs the bytes around a keyframe that has not been buffered yet.
 * Telegram can serve any offset, so the range is requested directly and kept in
 * memory: writing it into the playback file would corrupt that file's sparse
 * bookkeeping and confuse the downloader.
 *
 * The location is taken from the running download when there is one, because it
 * carries the current file_reference; rebuilding one risks an expired reference
 * the server rejects.
 */
public final class FileRangeFetcher {

    /** upload.getFile requires 4KB-aligned offsets. */
    private static final int OFFSET_ALIGN = 4096;
    /** Request granularity. 256KB divides 1MB evenly, which the server requires. */
    public static final int BLOCK = 256 * 1024;
    /** At most one range request per second, and never while the file downloads. */
    private static final long MIN_REQUEST_GAP_MS = 1000;
    private static volatile long lastRequestAt;

    private FileRangeFetcher() {
    }

    /**
     * Blocking range fetch. Call from a background thread only.
     *
     * @return the requested bytes, or null on failure or timeout
     */
    public static byte[] fetchSync(int account, TLRPC.Document document,
                                   long offset, long length, long timeoutMs,
                                   FetchHandle handle) {
        return fetchSync(account, document, offset, length, timeoutMs, true, handle);
    }

    public static byte[] fetchSync(int account, TLRPC.Document document,
                                   long offset, long length, long timeoutMs) {
        return fetchSync(account, document, offset, length, timeoutMs, true, null);
    }

    /**
     * Carries the request token of an in-flight fetch.
     *
     * The token is allocated inside sendRequest before it returns, so it can be
     * cancelled right away. Cancelling does NOT invoke the completion callback
     * — ConnectionsManager drops it silently — so the holder must also wake
     * its own latch.
     */
    public static final class FetchHandle {
        volatile int token;
        volatile boolean cancelled;
        private volatile CountDownLatch latch;

        /**
         * Cancels the in-flight request. ConnectionsManager silently drops the
         * completion callback on cancel, so this must wake the waiting thread
         * itself instead of waiting for a callback that will never come.
         */
        void cancel() {
            final boolean first = !cancelled;
            cancelled = true;
            final int t = token;
            if (first && t != 0) {
                // Only actually cancel once; a second call is a no-op apart from
                // waking the waiter.
                try {
                    ConnectionsManager.getInstance(account).cancelRequest(t, true);
                } catch (Throwable ignore) {
                }
            }
            final CountDownLatch l = latch;
            if (l != null) {
                l.countDown();
            }
        }

        private int account = -1;
    }

    private static byte[] fetchSync(int account, TLRPC.Document document,
                                    long offset, long length, long timeoutMs,
                                    boolean allowRetry, FetchHandle handle) {
        if (document == null || length <= 0 || account < 0) {
            return null;
        }
        // Master kill switch. Range requests share the download connection with
        // the video's own downloader; when they trip a server-side flood wait
        // (FLOOD_PREMIUM_WAIT_*) the download stalls and the player spins.
        // With this off the preview is local-only and cannot affect playback.
        if (!tw.nekomimi.nekogram.NekoConfig.videoPreviewNetworkFetch.Bool()) {
            lastFailureText[0] = "network fetch disabled";
            return null;
        }
        // Playback owns the download connection. A range request while this
        // document is downloading shares that connection and can trip a
        // server-side FLOOD_PREMIUM_WAIT_*, which stalls the player. The
        // download itself is filling the missing ranges, so the preview has no
        // reason to compete: local passes pick the new bytes up as they arrive.
        // Only when the download stops (paused, cancelled, finished) do the
        // leftover gaps get fetched remotely, at most one request per second.
        FileLoadOperation active = null;
        try {
            active = FileLoader.getInstance(account).getLoadOperation(document);
        } catch (Throwable ignore) {
        }
        if (active != null) {
            lastFailureText[0] = "download active (playback priority)";
            return null;
        }
        final long now = System.currentTimeMillis();
        if (now - lastRequestAt < MIN_REQUEST_GAP_MS) {
            lastFailureText[0] = "throttled (1 req/s)";
            return null;
        }
        lastRequestAt = now;
        // Fixed blocks, aligned down, always requested in full.
        //
        // The server accepts only limits that divide 1MB evenly (4K, 8K ... 1M),
        // so a limit derived from the keyframe size — say 81920, which is 4K
        // aligned but does not divide 1MB — comes back LIMIT_INVALID. Every
        // failed request in the last capture had exactly that shape. Asking for
        // a whole 256KB block removes the whole class of problem, and the server
        // simply returns fewer bytes near the end of the file.
        final long aligned = (offset / BLOCK) * BLOCK;
        final int delta = (int) (offset - aligned);

        // How many whole blocks does the caller need? A keyframe can easily
        // span more than one, and a fetch that stops after the first block hands
        // the decoder a truncated payload.
        final int blocks = (int) ((delta + length + BLOCK - 1L) / BLOCK);

        final TLRPC.InputFileLocation location = resolveLocation(account, document);
        if (location == null) {
            return null;
        }
        final int dcId = document.dc_id > 0
                ? document.dc_id
                : ConnectionsManager.DEFAULT_DATACENTER_ID;

        if (blocks <= 1) {
            final byte[] slice = fetchOneBlock(account, document, location,
                    aligned, delta, BLOCK, timeoutMs, handle, dcId);
            if (slice == null && allowRetry && lastFailureText[0] != null
                    && FileRefController.isFileRefError(lastFailureText[0])) {
                // The reference expired. Another part of the app may already
                // have refreshed it, so resolve the location again and retry
                // once.
                FileLog.d("ABPV: range retry after file reference error: " + lastFailureText[0]);
                return fetchSync(account, document, offset, length, timeoutMs, false, handle);
            }
            return slice;
        }

        // Several blocks: request them one at a time and stitch. The first block
        // carries the delta; the rest start at a block boundary. Any block that
        // fails fails the whole fetch — a half-assembled range would be worse
        // than none, because the decoder would read whatever happened to land in
        // the gap.
        FileLog.d("ABPV: range multi-block off=" + offset + " len=" + length
                + " delta=" + delta + " blocks=" + blocks + " dc=" + dcId);
        final byte[] out = new byte[(int) length];
        int filled = 0;
        for (int b = 0; b < blocks; b++) {
            final long blockAligned = aligned + (long) b * BLOCK;
            final int blockDelta = (b == 0) ? delta : 0;
            final byte[] chunk = fetchOneBlock(account, document, location,
                    blockAligned, blockDelta, BLOCK, timeoutMs, handle, dcId);
            if (chunk == null) {
                FileLog.d("ABPV: range multi-block failed at block " + (b + 1) + "/" + blocks
                        + " aligned=" + blockAligned);
                return null;
            }
            final int take = Math.min(chunk.length, out.length - filled);
            System.arraycopy(chunk, 0, out, filled, take);
            filled += take;
            if (filled >= out.length) {
                break;
            }
        }
        FileLog.d("ABPV: range multi-block done off=" + offset + " got=" + filled
                + " want=" + length + " blocks=" + blocks);
        return out;
    }

    /** Last failure text, for callers that need to classify the failure. */
    static String failureText() {
        return lastFailureText[0];
    }

    /** True when the failure text names a flood wait (code 420). */
    static boolean isFloodWait(String text) {
        return text != null && text.contains("FLOOD");
    }

    /**
     * First run of digits in the text, capped at an hour.
     *
     * Deliberately prefix-agnostic: both FLOOD_WAIT_10 and FLOOD_PREMIUM_WAIT_10
     * must yield 10, and a weird text must not park the cooldown for days.
     */
    static int parseFloodSeconds(String text) {
        if (text == null) {
            return -1;
        }
        final int n = text.length();
        int i = 0;
        while (i < n && !Character.isDigit(text.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return -1;
        }
        int value = 0;
        while (i < n && Character.isDigit(text.charAt(i))) {
            value = value * 10 + (text.charAt(i) - '0');
            if (value > 3600) {
                return 3600;
            }
            i++;
        }
        return value;
    }

    /** Last failure text of any block in the current fetch, for the ref-retry check. */
    private static final String[] lastFailureText = new String[1];

    /**
     * Requests one whole 256KB block and returns the bytes starting at the
     * caller's offset (the block's leading {@code delta} bytes are stripped).
     */
    private static byte[] fetchOneBlock(int account, TLRPC.Document document,
                                        TLRPC.InputFileLocation location,
                                        long aligned, int delta, int limit,
                                        long timeoutMs, FetchHandle handle, int dcId) {
        final TLRPC.TL_upload_getFile request = new TLRPC.TL_upload_getFile();
        request.location = location;
        request.offset = aligned;
        request.limit = limit;
        // CDN redirection would need a second hop and its own token handling;
        // a few hundred KB is served directly.
        request.cdn_supported = false;
        // precise disables some of the server's checks on offset and limit,
        // which is exactly what streaming by keyframe needs. It does not mean
        // "return this exact range".
        request.precise = true;

        final CountDownLatch latch = new CountDownLatch(1);
        if (handle != null) {
            handle.account = account;
            handle.latch = latch;
            if (handle.cancelled) {
                lastFailureText[0] = "cancelled before send";
                return null;
            }
        }
        final byte[][] result = new byte[1][];
        final String[] failure = new String[1];
        final String[] responseClass = new String[1];
        final int[] responseBytes = new int[1];
        final int[] failureCode = new int[1];

        FileLog.d("ABPV: range request off=" + aligned + " limit=" + limit
                + " delta=" + delta);

        final int requestToken = ConnectionsManager.getInstance(account).sendRequest(request, (response, error, responseTime) -> {
            try {
                if (error != null) {
                    failure[0] = error.text;
                    failureCode[0] = error.code;
                    return;
                }
                responseClass[0] = response == null ? "null" : response.getClass().getSimpleName();
                if (response instanceof TLRPC.TL_upload_fileCdnRedirect) {
                    // The file lives on a CDN; serving it needs a second request
                    // to that DC plus hash verification.
                    failure[0] = "cdn redirect (unsupported)";
                    return;
                }
                if (!(response instanceof TLRPC.TL_upload_file)) {
                    failure[0] = "unexpected response";
                    return;
                }
                final NativeByteBuffer buffer = ((TLRPC.TL_upload_file) response).bytes;
                responseBytes[0] = buffer == null ? -1 : buffer.limit();
                // limit() is the payload size. length() reports the read
                // position, and a freshly unpacked response sits at position 0,
                // so length() would call every successful reply empty.
                if (buffer == null || buffer.limit() == 0) {
                    failure[0] = "empty response";
                    return;
                }
                if (buffer.limit() <= delta) {
                    failure[0] = "short read (got " + buffer.limit() + ", skip " + delta + ")";
                    return;
                }
                // Copy out immediately and never let the buffer escape this
                // callback: it is native memory that the framework recycles
                // (freeResources -> reuse) as soon as the callback returns.
                // Never call reuse() by hand — it has no re-entry guard, and a
                // double free would hand the same block to another request.
                final int total = buffer.limit();
                final byte[] all = new byte[total];
                buffer.buffer.position(0);
                buffer.buffer.get(all);
                final int available = total - delta;
                final byte[] slice = new byte[available];
                System.arraycopy(all, delta, slice, 0, available);
                result[0] = slice;
            } finally {
                latch.countDown();
            }
        }, ConnectionsManager.RequestFlagDoNotWaitFloodWait,
                ConnectionsManager.ConnectionTypeDownload, dcId);
        if (handle != null) {
            handle.token = requestToken;
            if (handle.cancelled) {
                // cancel() ran before sendRequest returned; the token was still 0
                // then, so cancel it here instead.
                try {
                    ConnectionsManager.getInstance(account).cancelRequest(requestToken, true);
                } catch (Throwable ignore) {
                }
                latch.countDown();
            }
        }
        FileLog.d("ABPV: range token=" + requestToken + " off=" + aligned
                + " limit=" + limit + " dc=" + dcId);

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                FileLog.d("ABPV: range fetch timeout off=" + aligned + " limit=" + limit
                        + " token=" + requestToken);
                lastFailureText[0] = "timeout";
                // The request is still alive in native and would keep
                // downloading; kill it so it stops eating the download slot.
                if (handle != null) {
                    handle.cancel();
                } else {
                    try {
                        ConnectionsManager.getInstance(account).cancelRequest(requestToken, true);
                    } catch (Throwable ignore) {
                    }
                }
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailureText[0] = "interrupted";
            if (handle != null) {
                handle.cancel();
            }
            return null;
        }
        if (result[0] == null) {
            lastFailureText[0] = failure[0];
            FileLog.d("ABPV: range fetch failed off=" + aligned + " aligned=" + aligned
                    + " limit=" + limit + " dc=" + dcId
                    + " size=" + document.size + " reason=" + failure[0]
                    + " code=" + failureCode[0]
                    + " cls=" + responseClass[0] + " bytes=" + responseBytes[0]);
        } else {
            FileLog.d("ABPV: range ok off=" + aligned + " aligned=" + aligned
                    + " limit=" + limit + " dc=" + dcId
                    + " cls=" + responseClass[0] + " bytes=" + responseBytes[0]
                    + " delta=" + delta + " got=" + result[0].length);
        }
        return result[0];
    }

    /**
     * Fetches a range and compares it byte for byte with the same range in the
     * local file.
     *
     * A zero difference proves that the offset, the alignment and the assembly
     * of the block are all correct. The comparison only covers bytes that are
     * really on disk: the .temp file is sparse, and reading a hole yields
     * zeros, which would look like a mismatch and send us chasing a phantom
     * offset bug.
     *
     * @return number of differing bytes, -1 when the fetch or the read failed,
     *         -2 when the range is not fully downloaded
     */
    public static int verifyAgainstLocal(int account, TLRPC.Document document, File localFile,
                                          long offset, int length, long timeoutMs) {
        if (document == null || localFile == null || length <= 0) {
            return -1;
        }
        final int available;
        try {
            available = (int) Math.min(length, FileLoader.getInstance(account)
                    .getDownloadedLengthFast(document, offset, length));
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
        if (available < length) {
            FileLog.d("ABPV: verify skip off=" + offset + " want=" + length
                    + " available=" + available + " (not fully on disk)");
            return -2;
        }

        final byte[] remote = fetchSync(account, document, offset, length, timeoutMs);
        if (remote == null) {
            FileLog.d("ABPV: verify failed off=" + offset + " want=" + length + " (fetch failed)");
            return -1;
        }

        final byte[] local;
        try (RandomAccessFile raf = new RandomAccessFile(localFile, "r")) {
            raf.seek(offset);
            local = new byte[remote.length];
            raf.readFully(local);
        } catch (Throwable e) {
            FileLog.e(e);
            FileLog.d("ABPV: verify failed off=" + offset + " (local read failed)");
            return -1;
        }

        int diff = 0;
        int firstDiff = -1;
        for (int i = 0; i < remote.length; i++) {
            if (remote[i] != local[i]) {
                diff++;
                if (firstDiff < 0) {
                    firstDiff = i;
                }
            }
        }
        FileLog.d("ABPV: verify off=" + offset + " want=" + length + " got=" + remote.length
                + " available=" + available + " diff=" + diff + " firstDiff=" + firstDiff
                + " outOfRange=" + (length - available));
        return diff;
    }

    /**
     * Fetches the first block of a file once, as a control sample.
     *
     * If offset 0 succeeds while a deep offset fails, the request itself is
     * fine and the problem is specific to the offset. If it fails too, the
     * location, the file reference or the authorisation is at fault.
     */
    public static void probeFromStart(int account, TLRPC.Document document, long timeoutMs) {
        if (document == null) {
            return;
        }
        FileLog.d("ABPV: range probe from 0 limit=" + BLOCK + " dc=" + document.dc_id
                + " size=" + document.size);
        fetchSync(account, document, 0, BLOCK, timeoutMs);
    }

    private static TLRPC.InputFileLocation resolveLocation(int account, TLRPC.Document document) {
        final FileLoader loader = FileLoader.getInstance(account);
        final FileLoadOperation operation = loader.getLoadOperation(document);
        if (operation != null) {
            final TLRPC.InputFileLocation active = operation.getFileLocation();
            // Only a document location can be read at an offset; photo, encrypted
            // and secure locations describe something else entirely.
            if (active instanceof TLRPC.TL_inputDocumentFileLocation) {
                return active;
            }
        }
        // Nothing running: build the location from the document itself.
        if (document.id == 0
                || document.file_reference == null
                || document.file_reference.length == 0) {
            return null;
        }
        final TLRPC.TL_inputDocumentFileLocation location = new TLRPC.TL_inputDocumentFileLocation();
        location.id = document.id;
        location.access_hash = document.access_hash;
        location.file_reference = document.file_reference;
        location.thumb_size = "";
        return location;
    }
}
