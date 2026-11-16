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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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

    /**
     * Outcome of one range fetch.
     *
     * Callers must classify on {@link Status}, never on {@link #text}: the text
     * is whatever the server or this class happened to say and is only meant
     * for logs. {@link #countsAsFailure} spells out whether the outcome should
     * move a failure streak, because the answer differs per exit (a cancelled
     * request is not a failure, a malformed response is).
     */
    public static final class FetchResult {
        public enum Status {
            /** Bytes were delivered. */
            SUCCESS,
            /** Caller gave up, or the request was dropped before it was sent. */
            CANCELLED,
            /** The request was sent but no reply arrived in time. */
            TIMEOUT,
            /** The server answered 420; {@link #floodSeconds} carries its wait. */
            FLOOD,
            /** A local cooldown (server flood) is still running; nothing was sent. */
            LOCAL_COOLDOWN,
            /** A stale file reference survived the one retry we allow. */
            RETRY_LIMIT,
            /** A local switch forbids network use; nothing was sent. */
            DISABLED,
            /** Anything else that went wrong. */
            ERROR
        }

        public final Status status;
        /** The bytes, only for {@link Status#SUCCESS}. */
        public final byte[] data;
        /** Server error code, 420 for {@link Status#FLOOD}, otherwise 0. */
        public final int code;
        /** Seconds the server asked us to wait, -1 when unknown. */
        public final int floodSeconds;
        /** Free-form detail for logs only; never used for decisions. */
        public final String text;
        /** True when this outcome should count towards the failure streak. */
        public final boolean countsAsFailure;
        /** True when a multi-block fetch failed after some blocks landed. */
        public final boolean partial;
        /** Number of getFile requests actually sent. */
        public final int requests;
        /** Wall time of the whole fetch, retries included. */
        public final long elapsedMs;

        private FetchResult(Status status, byte[] data, int code, int floodSeconds,
                            String text, boolean countsAsFailure, boolean partial,
                            int requests, long startedMs) {
            this.status = status;
            this.data = data;
            this.code = code;
            this.floodSeconds = floodSeconds;
            this.text = text;
            this.countsAsFailure = countsAsFailure;
            this.partial = partial;
            this.requests = requests;
            this.elapsedMs = startedMs > 0
                    ? Math.max(0L, System.currentTimeMillis() - startedMs) : 0L;
        }

        public boolean ok() {
            return status == Status.SUCCESS;
        }

        /** Same outcome with the accounting of the enclosing fetch filled in. */
        private FetchResult rewrap(boolean newPartial, int newRequests, long startedMs) {
            return new FetchResult(status, data, code, floodSeconds, text,
                    countsAsFailure, newPartial, newRequests, startedMs);
        }
    }

    private static FetchResult success(byte[] data, int requests, long startedMs) {
        return new FetchResult(FetchResult.Status.SUCCESS, data, 0, -1, null,
                false, false, requests, startedMs);
    }

    private static FetchResult failure(FetchResult.Status status, int code, int floodSeconds,
                                       String text, boolean countsAsFailure, int requests,
                                       long startedMs) {
        return new FetchResult(status, null, code, floodSeconds, text,
                countsAsFailure, false, requests, startedMs);
    }

    /** upload.getFile requires 4KB-aligned offsets. */
    private static final int OFFSET_ALIGN = 4096;
    /** Request granularity. 256KB divides 1MB evenly, which the server requires. */
    public static final int BLOCK = 256 * 1024;

    /**
     * The granularity actually used. Configurable for the 64/128KB experiment:
     * the server only accepts limits that divide 1MB evenly, so anything else
     * falls back to the default 256KB.
     */
    public static int blockBytes() {
        final int kb = tw.nekomimi.nekogram.NekoConfig.videoPreviewFetchBlockKb.Int();
        final int bytes = kb * 1024;
        return (bytes > 0 && (1024 * 1024) % bytes == 0) ? bytes : BLOCK;
    }

    /**
     * Minimum gap between two range requests. While the video downloads the
     * preview yields to playback and keeps one request per second; when the
     * download is idle the coarse layer may fill faster.
     */
    private static final long MIN_REQUEST_GAP_MS_ACTIVE = 1000;
    private static final long MIN_REQUEST_GAP_MS_IDLE = 250;
    /**
     * A server flood wait (420) parks every range request until this time, no
     * matter which video asked for it: the limit belongs to the account, not
     * to one chat, so the state is static.
     */
    private static volatile long floodUntilMs;
    /** Floods seen in a row; each one doubles the next wait. */
    private static volatile int consecutiveFloods;
    /** Last flood, used to forget a streak that ended long ago. */
    private static volatile long lastFloodAtMs;
    /** A cooldown just ended: the next request probes whether it is over. */
    private static volatile boolean probePending;
    /** True while that single probe request is on the wire. */
    private static volatile boolean probeInFlight;
    /** Any 420 keeps requests spaced out for a while after the cooldown. */
    private static volatile long postFloodUntilMs;

    /** First flood parks for at least ten seconds. */
    private static final long FLOOD_MIN_BACKOFF_SECONDS = 10L;
    /** No matter how many floods stack up, never park for more than five minutes. */
    private static final long FLOOD_MAX_BACKOFF_SECONDS = 300L;
    /** A flood separated from the previous one by this much starts a new streak. */
    private static final long FLOOD_STREAK_WINDOW_MS = 10L * 60 * 1000;
    /** After a flood, keep two seconds between requests instead of one. */
    private static final long POST_FLOOD_GAP_MS = 2000L;
    /** How long that slower pace lasts past the cooldown itself. */
    private static final long POST_FLOOD_PENALTY_MS = 60_000L;

    /** True while a server flood wait is still cooling down. */
    public static boolean isCoolingDown() {
        return System.currentTimeMillis() < floodUntilMs;
    }

    /** Milliseconds left on the server cooldown, 0 when it is over. */
    public static long coolingDownRemainingMs() {
        return Math.max(0L, floodUntilMs - System.currentTimeMillis());
    }

    private static volatile long lastRequestAt;
    /** Makes the request gap hold even when both preview threads ask at once. */
    private static final Object rateLock = new Object();
    /** Tags every request so its log lines can be matched up. */
    private static final AtomicInteger requestSeq = new AtomicInteger();

    private FileRangeFetcher() {
    }

    /**
     * Blocking range fetch. Call from a background thread only.
     */
    public static FetchResult fetchSync(int account, TLRPC.Document document,
                                        long offset, long length, long timeoutMs,
                                        FetchHandle handle) {
        final long started = System.currentTimeMillis();
        return fetchSync(account, document, offset, length, timeoutMs, true, handle, started);
    }

    public static FetchResult fetchSync(int account, TLRPC.Document document,
                                        long offset, long length, long timeoutMs) {
        final long started = System.currentTimeMillis();
        return fetchSync(account, document, offset, length, timeoutMs, true, null, started);
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
        /**
         * Optional staleness probe. When it returns true the request is
         * dropped before it is sent and is not counted as a failure: a drag
         * moved the finger away, so the bytes would be wasted.
         */
        /** Public so callers in sub-packages can install a probe. */
        public volatile BooleanSupplier stale;
        private volatile CountDownLatch latch;

        public boolean isStale() {
            final BooleanSupplier probe = stale;
            if (probe == null) {
                return false;
            }
            try {
                return probe.getAsBoolean();
            } catch (Throwable e) {
                return false;
            }
        }

        /**
         * Cancels the in-flight request. ConnectionsManager silently drops the
         * completion callback on cancel, so this must wake the waiting thread
         * itself instead of waiting for a callback that will never come.
         *
         * <p>Only the caller may call this: it marks the fetch as abandoned,
         * which tells the caller not to count it as a failure.
         */
        public void cancel() {
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

        /**
         * Stops the native request without changing the outcome.
         *
         * <p>Used when this class gives up on a request that must still be
         * reported as a failure — a timeout. Routing that through
         * {@link #cancel()} would set the cancelled flag, and the caller would
         * then treat the timeout as an abandoned fetch and drop it from the
         * failure streak.
         */
        public void abort() {
            final int t = token;
            if (t != 0) {
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

        public boolean isCancelled() {
            return cancelled;
        }

        private int account = -1;
    }

    private static FetchResult fetchSync(int account, TLRPC.Document document,
                                         long offset, long length, long timeoutMs,
                                         boolean allowRetry, FetchHandle handle, long started) {
        if (document == null || length <= 0 || account < 0) {
            return failure(FetchResult.Status.ERROR, 0, -1, "invalid-args", false, 0, started);
        }
        // Master kill switch. Range requests share the download connection with
        // the video's own downloader; when they trip a server-side flood wait
        // (FLOOD_PREMIUM_WAIT_*) the download stalls and the player spins.
        // With this off the preview is local-only and cannot affect playback.
        if (!tw.nekomimi.nekogram.NekoConfig.videoPreviewNetworkFetch.Bool()) {
            return failure(FetchResult.Status.DISABLED, 0, -1,
                    "network fetch disabled", false, 0, started);
        }
        // Range requests share the download connection with the video's own
        // downloader. They are allowed while that download runs, because a
        // preview of a range that has not arrived yet is the whole point, but
        // they yield: one request per second while downloading, faster when
        // the downloader is idle. A server flood wait parks every caller until
        // the cooldown expires.
        FileLoadOperation active = null;
        try {
            active = FileLoader.getInstance(account).getLoadOperation(document);
        } catch (Throwable ignore) {
        }
        final boolean downloadActive = active != null;
        final boolean[] probe = new boolean[1];
        final FetchResult gate = awaitRateSlot(downloadActive, handle, probe);
        if (gate != null) {
            return gate.rewrap(false, 0, started);
        }
        try {
            final FetchResult result = fetchBlocks(account, document, offset, length,
                    timeoutMs, allowRetry, downloadActive, handle, started);
            // Only the probe may reopen the gate. A request that was already in
            // flight when another thread got a 420 must not erase that fresh
            // cooldown just because it happened to finish successfully.
            if (result.ok() && probe[0]) {
                noteProbeSuccess();
            }
            return result;
        } finally {
            // The probe slot is released on every exit: a probe that timed out
            // must not lock the layer until the process restarts.
            if (probe[0]) {
                releaseProbeSlot();
            }
        }
    }

    private static FetchResult fetchBlocks(int account, TLRPC.Document document,
                                           long offset, long length, long timeoutMs,
                                           boolean allowRetry, boolean downloadActive,
                                           FetchHandle handle, long started) {
        // Fixed blocks, aligned down, always requested in full.
        //
        // The server accepts only limits that divide 1MB evenly (4K, 8K ... 1M),
        // so a limit derived from the keyframe size — say 81920, which is 4K
        // aligned but does not divide 1MB — comes back LIMIT_INVALID. Every
        // failed request in the last capture had exactly that shape. Asking for
        // a whole 256KB block removes the whole class of problem, and the server
        // simply returns fewer bytes near the end of the file.
        final long aligned = (offset / blockBytes()) * blockBytes();
        final int delta = (int) (offset - aligned);

        // How many whole blocks does the caller need? A keyframe can easily
        // span more than one, and a fetch that stops after the first block hands
        // the decoder a truncated payload.
        final int blocks = (int) ((delta + length + blockBytes() - 1L) / blockBytes());

        final TLRPC.InputFileLocation location = resolveLocation(account, document);
        if (location == null) {
            return failure(FetchResult.Status.ERROR, 0, -1, "no-location", true, 0, started);
        }
        final int dcId = document.dc_id > 0
                ? document.dc_id
                : ConnectionsManager.DEFAULT_DATACENTER_ID;

        if (blocks <= 1) {
            final FetchResult slice = fetchOneBlock(account, document, location,
                    aligned, delta, blockBytes(), timeoutMs, handle, dcId, started);
            if (!slice.ok() && slice.status == FetchResult.Status.ERROR
                    && slice.text != null
                    && FileRefController.isFileRefError(slice.text)) {
                if (allowRetry) {
                    if (isCoolingDown()) {
                        // A flood started while this block was in flight; do
                        // not spend the retry on a request that will be refused.
                        return failure(FetchResult.Status.LOCAL_COOLDOWN, 0, -1,
                                "cooldown", false, slice.requests, started);
                    }
                    // The reference expired. Another part of the app may
                    // already have refreshed it, so resolve the location again
                    // and retry once. This stays inside the same fetch: going
                    // back through fetchSync would claim a second rate slot and,
                    // for the probe request, collide with its own probe slot.
                    FileLog.d("ABPV: range retry after file reference error: " + slice.text);
                    return fetchBlocks(account, document, offset, length, timeoutMs,
                            false, downloadActive, handle, started);
                }
                return failure(FetchResult.Status.RETRY_LIMIT, slice.code, -1,
                        RETRY_LIMIT_TEXT, true, slice.requests, started);
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
        int sent = 0;
        for (int b = 0; b < blocks; b++) {
            // The first block already passed the rate slot check. Every later
            // block is a separate request, so it waits its turn and stops if a
            // flood cooldown started in the meantime.
            if (b > 0) {
                final FetchResult next = awaitRateSlot(downloadActive, handle, null);
                if (next != null) {
                    FileLog.d("ABPV: range multi-block stopped at block "
                            + (b + 1) + "/" + blocks);
                    return next.rewrap(filled > 0, sent, started);
                }
            }
            final long blockAligned = aligned + (long) b * blockBytes();
            final int blockDelta = (b == 0) ? delta : 0;
            final FetchResult chunk = fetchOneBlock(account, document, location,
                    blockAligned, blockDelta, blockBytes(), timeoutMs, handle, dcId, started);
            sent += chunk.requests;
            if (!chunk.ok()) {
                FileLog.d("ABPV: range multi-block failed at block " + (b + 1)
                        + "/" + blocks + " aligned=" + blockAligned
                        + " status=" + chunk.status);
                return chunk.rewrap(filled > 0, sent, started);
            }
            final int take = Math.min(chunk.data.length, out.length - filled);
            System.arraycopy(chunk.data, 0, out, filled, take);
            filled += take;
            if (filled >= out.length) {
                break;
            }
        }
        FileLog.d("ABPV: range multi-block done off=" + offset + " got=" + filled
                + " want=" + length + " blocks=" + blocks);
        return success(out, sent, started);
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

    /** True when a file reference expired and the one retry did not help. */
    private static final String RETRY_LIMIT_TEXT = "file reference retry limit";

    /**
     * Records a 420 and parks every caller.
     *
     * The wait the server asks for is doubled and floored at ten seconds, then
     * doubled again for every flood in a row: 10, 20, 40 ... capped at five
     * minutes. The account type is never guessed from the message text — every
     * 420 follows this one rule.
     */
    private static void noteFloodWait(int serverSeconds) {
        synchronized (rateLock) {
            final long now = System.currentTimeMillis();
            if (now - lastFloodAtMs > FLOOD_STREAK_WINDOW_MS) {
                consecutiveFloods = 0;
            }
            consecutiveFloods++;
            final int shift = Math.min(consecutiveFloods - 1, 5);
            final long base = Math.max(serverSeconds * 2L, FLOOD_MIN_BACKOFF_SECONDS);
            final long seconds = Math.min(FLOOD_MAX_BACKOFF_SECONDS, base * (1L << shift));
            floodUntilMs = Math.max(floodUntilMs, now + seconds * 1000L);
            lastFloodAtMs = now;
            probePending = true;
            probeInFlight = false;
            postFloodUntilMs = Math.max(postFloodUntilMs,
                    floodUntilMs + POST_FLOOD_PENALTY_MS);
            FileLog.d("ABPV: flood streak=" + consecutiveFloods
                    + " serverSec=" + serverSeconds
                    + " backoff=" + (seconds * 1000L) + "ms until=" + floodUntilMs);
        }
    }

    /** A request went through: the cooldown, if any, is over. */
    private static void noteProbeSuccess() {
        synchronized (rateLock) {
            if (floodUntilMs != 0L || probePending || consecutiveFloods != 0) {
                FileLog.d("ABPV: flood cleared after probe");
            }
            floodUntilMs = 0L;
            probePending = false;
            probeInFlight = false;
            consecutiveFloods = 0;
            lastFloodAtMs = 0L;
        }
    }

    /** Frees the probe slot when the probe failed without a 420. */
    private static void releaseProbeSlot() {
        synchronized (rateLock) {
            probeInFlight = false;
        }
    }

    /**
     * Waits for the next request slot.
     *
     * @return null when the slot was claimed, otherwise the reason it was not
     *         (cancelled, stale, interrupted, or a running flood cooldown).
     * The wait is bounded by the longer gap (one second) and polls in small
     * slices so a cancelled preview does not hold the scheduler. The slot is
     * claimed under a lock: the prefetch and decoder threads share this gate,
     * so the gap holds across both.
     */
    private static FetchResult awaitRateSlot(boolean downloadActive, FetchHandle handle,
                                             boolean[] probeGranted) {
        final long baseGap = downloadActive
                ? MIN_REQUEST_GAP_MS_ACTIVE : MIN_REQUEST_GAP_MS_IDLE;
        while (true) {
            if (handle != null && handle.cancelled) {
                return failure(FetchResult.Status.CANCELLED, 0, -1,
                        "cancelled", false, 0, 0L);
            }
            if (handle != null && handle.isStale()) {
                return failure(FetchResult.Status.CANCELLED, 0, -1,
                        "stale", false, 0, 0L);
            }
            final long wait;
            synchronized (rateLock) {
                final long now = System.currentTimeMillis();
                if (now < floodUntilMs) {
                    return failure(FetchResult.Status.LOCAL_COOLDOWN, 0, -1,
                            "cooldown", false, 0, 0L);
                }
                final long gap = now < postFloodUntilMs
                        ? Math.max(baseGap, POST_FLOOD_GAP_MS) : baseGap;
                wait = lastRequestAt + gap - now;
                if (wait <= 0) {
                    if (probePending) {
                        // One request at a time checks whether the server
                        // accepted us back; everyone else waits for its verdict.
                        if (probeInFlight) {
                            return failure(FetchResult.Status.LOCAL_COOLDOWN, 0, -1,
                                    "probe in flight", false, 0, 0L);
                        }
                        probeInFlight = true;
                        if (probeGranted != null) {
                            probeGranted[0] = true;
                        }
                    }
                    lastRequestAt = now;
                    return null;
                }
            }
            try {
                Thread.sleep(Math.min(50L, wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure(FetchResult.Status.CANCELLED, 0, -1,
                        "interrupted", false, 0, 0L);
            }
        }
    }

    /**
     * Requests one whole block and returns the bytes starting at the caller's
     * offset (the block's leading {@code delta} bytes are stripped).
     */
    private static FetchResult fetchOneBlock(int account, TLRPC.Document document,
                                             TLRPC.InputFileLocation location,
                                             long aligned, int delta, int limit,
                                             long timeoutMs, FetchHandle handle, int dcId,
                                             long started) {
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
        final int seq = requestSeq.incrementAndGet();

        final CountDownLatch latch = new CountDownLatch(1);
        if (handle != null) {
            handle.account = account;
            handle.latch = latch;
            if (handle.cancelled) {
                return failure(FetchResult.Status.CANCELLED, 0, -1,
                        "cancelled before send", false, 0, started);
            }
            if (handle.isStale()) {
                return failure(FetchResult.Status.CANCELLED, 0, -1,
                        "stale", false, 0, started);
            }
        }
        final byte[][] result = new byte[1][];
        final String[] failure = new String[1];
        final int[] responseBytes = new int[1];
        final int[] failureCode = new int[1];
        final boolean[] callbackFired = new boolean[1];
        final boolean[] unsupported = new boolean[1];

        FileLog.d("ABPV: range request seq=" + seq + " off=" + aligned
                + " limit=" + limit + " delta=" + delta);

        final int requestToken = ConnectionsManager.getInstance(account).sendRequest(request, (response, error, responseTime) -> {
            callbackFired[0] = true;
            try {
                if (error != null) {
                    failure[0] = error.text;
                    failureCode[0] = error.code;
                    return;
                }
                if (response instanceof TLRPC.TL_upload_fileCdnRedirect) {
                    // The file lives on a CDN; serving it needs a second request
                    // to that DC plus hash verification.
                    failure[0] = "cdn redirect (unsupported)";
                    unsupported[0] = true;
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
        FileLog.d("ABPV: range token=" + requestToken + " seq=" + seq + " off=" + aligned
                + " limit=" + limit + " dc=" + dcId);

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                FileLog.d("ABPV: range fetch timeout seq=" + seq + " off=" + aligned
                        + " limit=" + limit + " token=" + requestToken);
                // The request is still alive in native and would keep
                // downloading; kill it so it stops eating the download slot.
                // This is not a cancel: the result stays TIMEOUT so it still
                // counts as a failure.
                if (handle != null) {
                    handle.abort();
                } else {
                    try {
                        ConnectionsManager.getInstance(account).cancelRequest(requestToken, true);
                    } catch (Throwable ignore) {
                    }
                }
                return failure(FetchResult.Status.TIMEOUT, 0, -1,
                        "timeout", true, 1, started);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (handle != null) {
                handle.abort();
            }
            return failure(FetchResult.Status.CANCELLED, 0, -1,
                    "interrupted", false, 1, started);
        }
        if (result[0] == null) {
            if (!callbackFired[0]) {
                // The completion never arrived. Either this class asked for the
                // cancellation, or the request was dropped on the way out.
                final boolean cancelled = handle != null && handle.isCancelled();
                final String text = cancelled ? "cancelled" : "no-response";
                FileLog.d("ABPV: range result seq=" + seq + " status="
                        + (cancelled ? "CANCELLED" : "TIMEOUT")
                        + " off=" + aligned + " limit=" + limit + " dc=" + dcId
                        + " bytes=" + responseBytes[0] + " code=" + failureCode[0]
                        + " req=1 partial=false callback=0 elapsed="
                        + (System.currentTimeMillis() - started) + " text=" + text);
                return failure(cancelled ? FetchResult.Status.CANCELLED
                                : FetchResult.Status.TIMEOUT,
                        0, -1, text, !cancelled, 1, started);
            }
            final String reason = failure[0] != null ? failure[0] : "unexpected response";
            if (failureCode[0] == 420) {
                final int seconds = parseFloodSeconds(reason);
                noteFloodWait(seconds);
                FileLog.d("ABPV: range result seq=" + seq + " status=FLOOD off=" + aligned
                        + " limit=" + limit + " dc=" + dcId + " bytes=" + responseBytes[0]
                        + " code=420 req=1 partial=false callback=1 elapsed="
                        + (System.currentTimeMillis() - started) + " text=" + reason);
                return failure(FetchResult.Status.FLOOD, 420, seconds,
                        reason, true, 1, started);
            }
            FileLog.d("ABPV: range result seq=" + seq + " status=ERROR off=" + aligned
                    + " limit=" + limit + " dc=" + dcId + " bytes=" + responseBytes[0]
                    + " code=" + failureCode[0] + " req=1 partial=false callback=1"
                    + " elapsed=" + (System.currentTimeMillis() - started)
                    + " text=" + reason);
            return failure(FetchResult.Status.ERROR, failureCode[0], -1,
                    reason, !unsupported[0], 1, started);
        }
        FileLog.d("ABPV: range ok seq=" + seq + " off=" + aligned
                + " limit=" + limit + " dc=" + dcId + " bytes=" + responseBytes[0]
                + " delta=" + delta + " req=1 elapsed="
                + (System.currentTimeMillis() - started) + " got=" + result[0].length);
        return success(result[0], 1, started);
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

        final FetchResult remote = fetchSync(account, document, offset, length, timeoutMs);
        if (!remote.ok()) {
            FileLog.d("ABPV: verify failed off=" + offset + " want=" + length
                    + " (fetch " + remote.status + ": " + remote.text + ")");
            return -1;
        }
        final byte[] data = remote.data;

        final byte[] local;
        try (RandomAccessFile raf = new RandomAccessFile(localFile, "r")) {
            raf.seek(offset);
            local = new byte[data.length];
            raf.readFully(local);
        } catch (Throwable e) {
            FileLog.e(e);
            FileLog.d("ABPV: verify failed off=" + offset + " (local read failed)");
            return -1;
        }

        int diff = 0;
        int firstDiff = -1;
        for (int i = 0; i < data.length; i++) {
            if (data[i] != local[i]) {
                diff++;
                if (firstDiff < 0) {
                    firstDiff = i;
                }
            }
        }
        FileLog.d("ABPV: verify off=" + offset + " want=" + length + " got=" + data.length
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
        FileLog.d("ABPV: range probe from 0 limit=" + blockBytes() + " dc=" + document.dc_id
                + " size=" + document.size);
        final FetchResult result = fetchSync(account, document, 0, blockBytes(), timeoutMs);
        FileLog.d("ABPV: range probe result status=" + result.status
                + " bytes=" + (result.data != null ? result.data.length : 0)
                + " text=" + result.text);
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
