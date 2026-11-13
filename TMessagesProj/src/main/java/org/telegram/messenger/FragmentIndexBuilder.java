package org.telegram.messenger;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.extractor.ChunkIndex;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ChunkIndex} for a fully downloaded fragmented MP4 that has no sidx/mfra/tfra.
 * Only top-level box headers are read; every suspicious structure rejects (returns null) so the
 * caller falls back to the existing RestartableSeekMap behaviour.
 */
public final class FragmentIndexBuilder {

    private static final String TAG = "FMPIDX";
    private static final int MAX_BOX_BODY = 16 * 1024 * 1024;
    private static final long MAX_TAIL_BYTES = 64;
    private static final int SAMPLE_FLAG_NON_SYNC = 0x00010000;
    /** Size of a plain ISO-BMFF box header: 4 bytes size + 4 bytes type. */
    private static final int Atom_HEADER_SIZE = 8;

    private static final int FTYP = cc("ftyp"), MOOV = cc("moov"), MOOF = cc("moof"),
            MDAT = cc("mdat"), SIDX = cc("sidx"), MFRA = cc("mfra"), TFRA = cc("tfra"),
            MVHD = cc("mvhd"), MEHD = cc("mehd"), TRAK = cc("trak"), TKHD = cc("tkhd"),
            MDIA = cc("mdia"), MDHD = cc("mdhd"), HDLR = cc("hdlr"), MVEX = cc("mvex"),
            TREX = cc("trex"), TRAF = cc("traf"), TFHD = cc("tfhd"), TFDT = cc("tfdt"),
            TRUN = cc("trun"), VIDE = cc("vide");

    private static final LinkedHashMap<String, ChunkIndex> CACHE =
            new LinkedHashMap<String, ChunkIndex>(4, 0.75f, true);

    /**
     * Partial (still downloading) indexes, keyed by document identity <em>and</em> the file
     * length they were built from. The length is part of the key on purpose: a growing
     * download must not reuse an index that describes an older, shorter prefix, and it
     * invalidates itself as soon as more bytes have arrived.
     */
    private static final LinkedHashMap<String, ChunkIndex> PARTIAL =
            new LinkedHashMap<String, ChunkIndex>(4, 0.75f, true);

    /** Debug A/B switch: set to false to force the legacy RestartableSeekMap path. */
    public static volatile boolean ENABLED = true;

    private FragmentIndexBuilder() {
    }

    /**
     * @param path                local file to scan
     * @param docId               Telegram document id (cache key)
     * @param docSize             expected file size from the document
     * @param expectedDurationSec video duration from TL_documentAttributeVideo (seconds)
     * @param notLoading          true if FileLoader has no active download for this file.
     *                            <b>Not a gate any more.</b> A growing *.temp is now scanned on
     *                            purpose, because its prefix is the real beginning of the
     *                            document and yields a usable partial index. The flag is kept
     *                            so a caller can still tell the two states apart, but the
     *                            complete/partial decision is made from the file length alone.
     */
    @Nullable
    public static ChunkIndex build(String path, long docId, long docSize,
                                  double expectedDurationSec, boolean notLoading) {
        long t0 = System.currentTimeMillis();
        final String key = docId + "_" + docSize;
        if (!ENABLED) {
            reject("disabled by debug flag");
            return null;
        }
        if (path == null) {
            reject("path=null");
            return null;
        }
        File file = new File(path);
        long len = file.length();
        if (len <= 0) {
            reject("length=" + len);
            return null;
        }
        if (docSize > 0 && len > docSize) {
            reject("length=" + len + " > docSize=" + docSize);
            return null;
        }
        // A Telegram *.temp file grows while it is downloaded. Everything below the
        // current length is already the real prefix of the document, so the box chain
        // can be walked up to the last complete fragment. That is what makes a seek
        // work before the download has finished, instead of falling back to
        // RestartableSeekMap (which restarts at byte 0 and looks like "seek is stuck").
        final boolean complete = docSize <= 0 || len == docSize;
        // Box-header scanning cannot detect holes inside mdat payloads; st_blocks is a cheap
        // gate for a finished file. A growing *.temp can legitimately be sparse, and the
        // scanner stops at the first zeroed header anyway, so the gate is skipped there.
        if (complete) {
            try {
                StructStat st = Os.stat(path);
                if ((long) st.st_blocks * 512L < st.st_size) {
                    reject("sparse blocks=" + st.st_blocks + " size=" + st.st_size);
                    return null;
                }
            } catch (ErrnoException e) {
                reject("stat " + e.getMessage());
                return null;
            }
        }

        if (complete) {
            synchronized (CACHE) {
                if (CACHE.containsKey(key)) {
                    ChunkIndex cached = CACHE.get(key);
                    Log.w(TAG, "cache hit key=" + key + " idx=" + (cached != null ? cached.length : "null"));
                    return cached;
                }
            }
        } else {
            // The partial cache is keyed by the exact length the scan saw, so a hit means the
            // file has not grown since. That is the only case where the prefix is unchanged.
            final String partialKey = key + "_" + len;
            synchronized (PARTIAL) {
                ChunkIndex cached = PARTIAL.get(partialKey);
                if (cached != null) {
                    Log.w(TAG, "partial cache hit key=" + partialKey + " idx=" + cached.length);
                    return cached;
                }
            }
        }

        ChunkIndex index = scan(file, len, docSize, expectedDurationSec, complete);
        if (complete) {
            synchronized (CACHE) {
                // Never cache a failure: a transient reject (file still being filled, transient I/O)
                // would otherwise poison the key for the whole process lifetime.
                if (index != null) {
                    CACHE.put(key, index);
                    while (CACHE.size() > 2) {
                        String oldest = CACHE.keySet().iterator().next();
                        CACHE.remove(oldest);
                    }
                }
                Log.w(TAG, "cache put key=" + key + " idx="
                        + (index != null ? index.length : "null (not cached)"));
            }
            if (index != null) {
                persist(docId, docSize, index);
            }
        } else {
            // A partial index must never be written to the shared CACHE or to disk: lookup()
            // cannot tell the two apart, and a stale partial index would hide the real one for
            // the rest of the process lifetime. It is kept in a separate, length-keyed cache
            // instead: the key carries the current file length, so a growing download misses
            // automatically once more bytes have arrived, and a re-open of the same partially
            // downloaded file does not pay for the scan again.
            if (index != null) {
                // A partial index skips the duration checks the complete path performs, so it
                // gets the structural ones instead: strictly increasing offsets and times,
                // positive spans, and an end that stays inside the bytes actually on disk.
                // Without this a truncated or half-written prefix could be adopted as-is.
                if (!validatePartial(index, docSize)) {
                    reject("partial index failed structural validation chunks=" + index.length);
                    index = null;
                }
            }
            if (index != null) {
                synchronized (PARTIAL) {
                    PARTIAL.put(key + "_" + len, index);
                    while (PARTIAL.size() > 4) {
                        String oldest = PARTIAL.keySet().iterator().next();
                        PARTIAL.remove(oldest);
                    }
                }
            }
            Log.w(TAG, "partial index chunks=" + (index != null ? index.length : "null")
                    + " len=" + len + "/" + docSize + " doc=" + docId);
        }
        Log.w(TAG, "build costMs=" + (System.currentTimeMillis() - t0)
                + " complete=" + complete + " doc=" + docId + " size=" + docSize);
        return index;
    }

    /**
     * Box-chain scan for a local file, complete or still growing, with no side effects.
     * Used by the seek-bar preview when the moov sample tables are empty (fragmented MP4),
     * because the fragment chain carries exactly the keyframe table it needs.
     */
    @Nullable
    public static ChunkIndex previewIndex(@Nullable String path, long docSize,
                                         double expectedDurationSec) {
        if (!ENABLED || path == null) {
            return null;
        }
        File file = new File(path);
        long len = file.length();
        if (len <= 0 || (docSize > 0 && len > docSize)) {
            return null;
        }
        return scan(file, len, docSize, expectedDurationSec, docSize <= 0 || len == docSize);
    }

    // ---------------------------------------------------------------------
    // Persistence
    //
    // A Telegram document id plus its byte size identify an immutable payload, so an index
    // built once from a fully downloaded file stays valid for every later play of the same
    // document -- including a streaming play after the local copy was deleted. Without this
    // the player had no index while re-downloading and fell back to RestartableSeekMap,
    // which is exactly the "seek only works once the download is finished" behaviour.
    // ---------------------------------------------------------------------

    private static final int DISK_MAGIC = 0x464D5058; // 'FMPX'
    private static final int DISK_VERSION = 1;
    private static final int MAX_DISK_CHUNKS = 200000;

    /**
     * Looks an index up by document identity only: no file access, no download state required.
     * Returns null when nothing usable is known for this document.
     */
    @Nullable
    public static ChunkIndex lookup(long docId, long docSize, double expectedDurationSec) {
        if (!ENABLED || docId == 0 || docSize <= 0) {
            return null;
        }
        final String key = docId + "_" + docSize;
        synchronized (CACHE) {
            ChunkIndex cached = CACHE.get(key);
            if (cached != null) {
                Log.w(TAG, "lookup memory key=" + key + " chunks=" + cached.length);
                return cached;
            }
        }
        ChunkIndex disk = readPersisted(docId, docSize, expectedDurationSec);
        if (disk != null) {
            synchronized (CACHE) {
                CACHE.put(key, disk);
                while (CACHE.size() > 2) {
                    String oldest = CACHE.keySet().iterator().next();
                    CACHE.remove(oldest);
                }
            }
            Log.w(TAG, "lookup disk key=" + key + " chunks=" + disk.length
                    + " totalUs=" + disk.getDurationUs());
        }
        return disk;
    }

    @Nullable
    private static File indexFile(long docId, long docSize) {
        try {
            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (dir == null) {
                return null;
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return new File(dir, "fmpidx_" + docId + "_" + docSize + ".bin");
        } catch (Throwable e) {
            return null;
        }
    }

    private static void persist(long docId, long docSize, ChunkIndex index) {
        if (docId == 0 || docSize <= 0) {
            return;
        }
        File file = indexFile(docId, docSize);
        if (file == null) {
            return;
        }
        // A unique temp name per attempt. Two players can resolve the same document at the same
        // time -- a chat preview plus the full player, picture-in-picture plus the main one --
        // so two threads can reach this method with the same key. With a single shared
        // "<name>.tmp" they wrote into one file at once, and because a rename keeps the inode,
        // the slower writer kept writing into the file the faster one had already renamed into
        // place: the result was a .bin whose CRC did not match, readPersisted() deleted it, and
        // the next play paid for a full scan again. Where rename() refuses to overwrite (it does
        // on Windows) the same race was worse -- every attempt failed and no .bin appeared at
        // all. A per-attempt name makes the whole thing a plain "last complete writer wins".
        File tmp = null;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(32 + index.length * 28);
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            out.writeInt(DISK_MAGIC);
            out.writeInt(DISK_VERSION);
            out.writeLong(docId);
            out.writeLong(docSize);
            out.writeInt(index.length);
            for (int i = 0; i < index.length; i++) {
                out.writeInt(index.sizes[i]);
            }
            for (int i = 0; i < index.length; i++) {
                out.writeLong(index.offsets[i]);
            }
            for (int i = 0; i < index.length; i++) {
                out.writeLong(index.durationsUs[i]);
            }
            for (int i = 0; i < index.length; i++) {
                out.writeLong(index.timesUs[i]);
            }
            out.flush();
            byte[] payload = bos.toByteArray();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payload);

            tmp = File.createTempFile("fmpidx_partial_", ".tmp", file.getParentFile());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            try {
                java.io.DataOutputStream fout = new java.io.DataOutputStream(fos);
                fout.writeLong(crc.getValue());
                fout.write(payload);
                fout.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
            Log.w(TAG, "persist key=" + docId + "_" + docSize + " chunks=" + index.length
                    + " bytes=" + (payload.length + 8));
        } catch (Throwable e) {
            if (tmp != null) {
                tmp.delete();
            }
            Log.w(TAG, "persist failed key=" + docId + "_" + docSize + " " + e);
        }
    }

    @Nullable
    private static ChunkIndex readPersisted(long docId, long docSize, double expectedDurationSec) {
        File file = indexFile(docId, docSize);
        if (file == null || !file.exists() || file.length() < 33 || file.length() > 8 * 1024 * 1024) {
            return null;
        }
        try {
            byte[] all = new byte[(int) file.length()];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                raf.readFully(all);
            }
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(all, 8, all.length - 8);
            if (crc.getValue() != i64(all, 0)) {
                Log.w(TAG, "persist corrupt crc key=" + docId + "_" + docSize);
                file.delete();
                return null;
            }
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(all, 8, all.length - 8));
            if (in.readInt() != DISK_MAGIC || in.readInt() != DISK_VERSION) {
                file.delete();
                return null;
            }
            if (in.readLong() != docId || in.readLong() != docSize) {
                file.delete();
                return null;
            }
            int n = in.readInt();
            if (n <= 0 || n > MAX_DISK_CHUNKS) {
                file.delete();
                return null;
            }
            int[] sizes = new int[n];
            long[] offsets = new long[n];
            long[] durationsUs = new long[n];
            long[] timesUs = new long[n];
            for (int i = 0; i < n; i++) {
                sizes[i] = in.readInt();
            }
            for (int i = 0; i < n; i++) {
                offsets[i] = in.readLong();
            }
            for (int i = 0; i < n; i++) {
                durationsUs[i] = in.readLong();
            }
            for (int i = 0; i < n; i++) {
                timesUs[i] = in.readLong();
            }
            if (!validate(docSize, expectedDurationSec, sizes, offsets, durationsUs, timesUs)) {
                Log.w(TAG, "persist rejected key=" + docId + "_" + docSize);
                file.delete();
                return null;
            }
            return new ChunkIndex(sizes, offsets, durationsUs, timesUs);
        } catch (Throwable e) {
            Log.w(TAG, "persist read failed key=" + docId + "_" + docSize + " " + e);
            return null;
        }
    }

    /**
     * Structural check for a partial index. The complete path also verifies the total duration
     * against the document attribute, but a partial prefix legitimately covers only part of the
     * timeline, so only the invariants that must hold for any prefix are checked here:
     * strictly increasing offsets and times, positive spans, and an end that stays inside the
     * bytes that are actually on disk.
     */
    private static boolean validatePartial(ChunkIndex index, long docSize) {
        final int n = index.length;
        if (n <= 0 || index.sizes.length != n || index.offsets.length != n
                || index.durationsUs.length != n || index.timesUs.length != n) {
            return false;
        }
        if (index.offsets[0] < 0 || index.timesUs[0] < 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (index.sizes[i] <= 0 || index.durationsUs[i] <= 0) {
                return false;
            }
            if (i > 0 && (index.offsets[i] <= index.offsets[i - 1]
                    || index.timesUs[i] <= index.timesUs[i - 1])) {
                return false;
            }
        }
        // The last chunk must not claim bytes that were never downloaded.
        return docSize <= 0 || index.offsets[n - 1] + index.sizes[n - 1] <= docSize;
    }

    private static boolean validate(long docSize, double expectedDurationSec, int[] sizes,
                                    long[] offsets, long[] durationsUs, long[] timesUs) {
        final int n = sizes.length;
        if (n <= 0 || offsets.length != n || durationsUs.length != n || timesUs.length != n) {
            return false;
        }
        if (offsets[0] < 0 || timesUs[0] < 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (sizes[i] <= 0 || durationsUs[i] <= 0) {
                return false;
            }
            if (i > 0) {
                if (offsets[i] <= offsets[i - 1] || timesUs[i] <= timesUs[i - 1]) {
                    return false;
                }
                if (offsets[i - 1] + sizes[i - 1] != offsets[i]) {
                    // A gap is suspicious for an A/V interleaved fragment chain, but not fatal:
                    // accept only a small slack to stay tolerant of a leading/trailing box.
                    if (Math.abs(offsets[i] - (offsets[i - 1] + sizes[i - 1])) > 4096) {
                        return false;
                    }
                }
            }
        }
        if (offsets[n - 1] + sizes[n - 1] > docSize) {
            return false;
        }
        long totalUs = timesUs[n - 1] + durationsUs[n - 1];
        if (expectedDurationSec > 0) {
            long expectedUs = (long) (expectedDurationSec * 1000000L);
            if (Math.abs(totalUs - expectedUs) > Math.max(2000000L, expectedUs / 20)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static ChunkIndex scan(File file, long len, long docSize,
                                  double expectedDurationSec, boolean complete) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            return scan(raf, len, docSize, expectedDurationSec, complete);
        } catch (Throwable e) {
            reject("exception " + e);
            return null;
        }
    }

    @Nullable
    private static ChunkIndex scan(RandomAccessFile raf, long len, long docSize,
                                  double expectedDurationSec, boolean complete)
            throws Exception {
        Map<Integer, Track> tracks = new HashMap<>();
        List<Frag> frags = new ArrayList<>();
        byte[] hdr = new byte[16];
        long pos = 0;
        long validEnd = 0;
        int moofCount = 0, mdatCount = 0;
        long mvhdTimescale = 0, mvhdDuration = 0, mehdDuration = -1;
        long firstMoofPos = -1;
        boolean hasSidx = false, hasMfra = false;
        Frag pending = null;

        while (pos + 8 <= len) {
            raf.seek(pos);
            raf.readFully(hdr, 0, 8);
            long size = u32(hdr, 0);
            int type = i32(hdr, 4);
            int header = 8;
            if (size == 1) {
                if (pos + 16 > len) {
                    break;
                }
                raf.readFully(hdr, 8, 8);
                size = i64(hdr, 8);
                header = 16;
            } else if (size == 0) {
                // A sparse hole may read back as zeros; never treat size==0 as "extends to EOF".
                break;
            }
            if (size < header || pos + size > len) {
                // e.g. the 14 trailing 0x20 bytes of this file: fake header 0x20202020.
                break;
            }
            if (type == SIDX) {
                hasSidx = true;
            }
            if (type == MFRA || type == TFRA) {
                hasMfra = true;
            }
            if (type == MOOV || type == MOOF) {
                long bodyLen = size - header;
                if (bodyLen <= 0 || bodyLen > MAX_BOX_BODY) {
                    reject("box body len=" + bodyLen + " type=" + type + " pos=" + pos);
                    return null;
                }
                byte[] body = new byte[(int) bodyLen];
                raf.readFully(body);
                if (type == MOOV) {
                    long[] mvhd = parseMoov(body, tracks);
                    mvhdTimescale = mvhd[0];
                    mvhdDuration = mvhd[1];
                    mehdDuration = mvhd[2];
                } else {
                    if (pending != null) {
                        if (complete) {
                            reject("moof without following mdat pos=" + pending.pos);
                            return null;
                        }
                        break;
                    }
                    Frag f = parseMoof(body, tracks);
                    if (f == null) {
                        // A half-written mdat/moof in a growing *.temp parses as garbage:
                        // keep everything up to here instead of discarding the whole prefix.
                        if (complete) {
                            reject("moof parse failed pos=" + pos);
                            return null;
                        }
                        break;
                    }
                    f.pos = pos;
                    if (firstMoofPos < 0) {
                        firstMoofPos = pos;
                    }
                    pending = f;
                    moofCount++;
                }
            } else if (type == MDAT) {
                if (pending == null) {
                    if (complete) {
                        reject("mdat without moof pos=" + pos);
                        return null;
                    }
                    break;
                }
                pending.end = pos + size;
                frags.add(pending);
                pending = null;
                mdatCount++;
            }
            validEnd = pos + size;
            pos += size;
        }
        if (pending != null && complete) {
            reject("trailing moof without mdat pos=" + pending.pos);
            return null;
        }
        long tail = len - validEnd;
        Log.w(TAG, "boxes moof=" + moofCount + " mdat=" + mdatCount + " tail=" + tail
                + " validEnd=" + validEnd + " len=" + len + " complete=" + complete);
        if (hasSidx || hasMfra) {
            reject("has native index sidx=" + hasSidx + " mfra=" + hasMfra);
            return null;
        }
        if (complete) {
            if (tail < 0 || tail > MAX_TAIL_BYTES) {
                reject("tail=" + tail + " (truncated or sparse hole)");
                return null;
            }
            if (moofCount != mdatCount || moofCount < 2) {
                reject("moof=" + moofCount + " mdat=" + mdatCount);
                return null;
            }
        } else if (moofCount < 2) {
            reject("partial moof=" + moofCount + " mdat=" + mdatCount);
            return null;
        }
        Track video = null, audio = null;
        for (Track t : tracks.values()) {
            if (t.video) {
                video = t;
            } else {
                audio = t;
            }
        }
        if (video == null || audio == null || video.timescale <= 0 || audio.timescale <= 0) {
            reject("tracks video=" + video + " audio=" + audio);
            return null;
        }
        long videoUs = us(video.duration, video.timescale);
        long audioUs = us(audio.duration, audio.timescale);
        long mvhdUs = mvhdTimescale > 0 ? us(mvhdDuration, mvhdTimescale) : -1;
        long mehdUs = mehdDuration >= 0 && mvhdTimescale > 0 ? us(mehdDuration, mvhdTimescale) : -1;
        long expectedUs = expectedDurationSec > 0 ? (long) (expectedDurationSec * 1000000L) : -1;
        Log.w(TAG, "tracks video=" + video.id + " ts=" + video.timescale + " durUs=" + videoUs
                + " audio=" + audio.id + " ts=" + audio.timescale + " durUs=" + audioUs
                + " mvhdUs=" + mvhdUs + " mehdUs=" + mehdUs + " expectUs=" + expectedUs);
        if (complete && (videoUs <= 0 || audioUs <= 0)) {
            reject("zero track duration videoUs=" + videoUs + " audioUs=" + audioUs);
            return null;
        }
        if (complete && Math.abs(videoUs - audioUs) * 100L >= Math.max(videoUs, audioUs)) {
            reject("av mismatch videoUs=" + videoUs + " audioUs=" + audioUs);
            return null;
        }
        if (expectedUs <= 0 && mvhdUs > 0) {
            // Document attribute unavailable: mvhd is a valid fallback for this file.
            expectedUs = mvhdUs;
        }
        if (complete && expectedUs <= 0) {
            reject("no expected duration (docAttribute/mvhd both unavailable)");
            return null;
        }
        if (complete && Math.abs(videoUs - expectedUs) * 100L > expectedUs * 2L) {
            reject("duration mismatch videoUs=" + videoUs + " expectUs=" + expectedUs);
            return null;
        }
        // Total playable duration, used for the last chunk's span. A partial scan still wants
        // the full duration here: the player then shows the real 27:40 and a far seek lands on
        // the last known fragment instead of jumping back to byte 0.
        final long totalUs = Math.max(videoUs, expectedUs);

        List<Frag> videoFrags = new ArrayList<>();
        List<Frag> audioFrags = new ArrayList<>();
        for (Frag f : frags) {
            if (f.samples <= 0) {
                continue; // the empty first fragment (audio track)
            }
            Track t = tracks.get(f.trackId);
            if (t == null) {
                reject("fragment unknown track=" + f.trackId);
                return null;
            }
            (t.video ? videoFrags : audioFrags).add(f);
        }
        if (videoFrags.size() < 2) {
            reject("videoFrags=" + videoFrags.size());
            return null;
        }

        List<Long> times = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        int droppedNonSync = 0, droppedDupTime = 0, audioLeadAdj = 0;
        long prevOffset = -1;
        long lastTime = -1;
        for (Frag v : videoFrags) {
            if (!v.sync) {
                droppedNonSync++;
                continue;
            }
            long tUs = us(v.tfdt, video.timescale);
            if (lastTime >= 0 && tUs <= lastTime) {
                droppedDupTime++;
                continue;
            }
            long start = v.pos;
            for (Frag a : audioFrags) {
                if (a.pos > prevOffset && a.pos < start) {
                    start = a.pos; // include leading audio moof so audio is not missing after a seek
                    audioLeadAdj++;
                    break;
                }
            }
            if (times.isEmpty() && firstMoofPos >= 0) {
                // Start the first chunk at the very first moof (this file's empty audio fragment),
                // which is where uninterrupted playback from offset 0 would start.
                start = Math.min(start, firstMoofPos);
            }
            if (start <= prevOffset) {
                droppedDupTime++;
                continue;
            }
            times.add(tUs);
            offsets.add(start);
            prevOffset = start;
            lastTime = tUs;
        }
        int n = times.size();
        if (n < 2) {
            reject("chunks=" + n + " droppedNonSync=" + droppedNonSync
                    + " droppedDupTime=" + droppedDupTime);
            return null;
        }
        if (times.get(0) < 0) {
            // A negative first timestamp means the fragment chain claims to start before the
            // file does. validate() already refuses such an index when it is read back from
            // disk, so the freshly scanned one has to refuse it as well -- otherwise the same
            // file would be accepted on the first play and rejected on every later one.
            reject("negative first timestamp " + times.get(0));
            return null;
        }

        int[] sizes = new int[n];
        long[] offsetsArr = new long[n], durationsUs = new long[n], timesUs = new long[n];
        // Fill both arrays completely before computing spans: the next element must already be
        // populated, otherwise offsetsArr[i+1]/timesUs[i+1] would still be the default 0.
        for (int i = 0; i < n; i++) {
            offsetsArr[i] = offsets.get(i);
            timesUs[i] = times.get(i);
        }
        // Narrowest span seen so far, used as the last chunk's duration when the file has no
        // declared total (or the prefix already reaches past it) -- a positive value is all
        // ChunkIndex needs to keep getDurationUs() monotonic.
        long minDur = 0;
        for (int i = 0; i + 1 < n; i++) {
            final long d = timesUs[i + 1] - timesUs[i];
            if (d > 0 && (minDur == 0 || d < minDur)) {
                minDur = d;
            }
        }
        for (int i = 0; i < n; i++) {
            long endPos = (i + 1 < n) ? offsetsArr[i + 1] : validEnd;
            long sz = endPos - offsetsArr[i];
            long dur = (i + 1 < n) ? (timesUs[i + 1] - timesUs[i]) : (totalUs - timesUs[i]);
            if (i + 1 == n && dur <= 0) {
                dur = minDur > 0 ? minDur : 1000000L;
            }
            if (sz <= 0 || sz > Integer.MAX_VALUE || dur <= 0
                    || (i > 0 && offsetsArr[i] <= offsetsArr[i - 1])) {
                if (complete) {
                    reject("chunk " + i + " sz=" + sz + " dur=" + dur
                            + " offset=" + offsetsArr[i]);
                    return null;
                }
                // A partial prefix can end in the middle of a fragment: drop the tail
                // chunk instead of throwing away the whole index.
                if (i < 2) {
                    reject("partial chunk " + i + " sz=" + sz + " dur=" + dur);
                    return null;
                }
                Log.w(TAG, "partial drop tail from chunk " + i);
                int[] cutSizes = java.util.Arrays.copyOf(sizes, i);
                long[] cutOffsets = java.util.Arrays.copyOf(offsetsArr, i);
                long[] cutDur = java.util.Arrays.copyOf(durationsUs, i);
                long[] cutTimes = java.util.Arrays.copyOf(timesUs, i);
                ChunkIndex trimmed = new ChunkIndex(cutSizes, cutOffsets, cutDur, cutTimes);
                Log.w(TAG, "partial chunks=" + trimmed.length
                        + " trimmedFrom=" + n + " lastEnd=" + (cutOffsets[i - 1] + cutSizes[i - 1]));
                return trimmed;
            }
            sizes[i] = (int) sz;
            durationsUs[i] = dur;
        }

        ChunkIndex index = new ChunkIndex(sizes, offsetsArr, durationsUs, timesUs);
        Log.w(TAG, "chunks=" + n + " videoFrags=" + videoFrags.size()
                + " audioFrags=" + audioFrags.size() + " droppedNonSync=" + droppedNonSync
                + " droppedDupTime=" + droppedDupTime + " audioLeadAdj=" + audioLeadAdj
                + " totalUs=" + index.getDurationUs()
                + " firstOffset=" + offsetsArr[0] + " lastEnd=" + validEnd);
        return index;
    }

    // ---------------------------------------------------------------------------------------
    // moov / moof parsing
    // ---------------------------------------------------------------------------------------

    /**
     * True when a moov atom carries an {@code mvex} box, which is what makes the movie
     * fragmented. Cheap and unambiguous: a plain MP4 never has one, and a fragmented one
     * cannot be identified from the box walk when moov sits before the first moof.
     *
     * @param moov the complete moov atom, i.e. starting with its own 8-byte header -- the
     *             same bytes {@link org.telegram.ui.Components.MoovKeyframeParser} parses.
     *             Its children start at offset 8, so the scan has to skip the header first.
     */
    public static boolean isFragmentedMoov(@Nullable byte[] moov) {
        if (moov == null || moov.length < 16) {
            return false;
        }
        // Offset 0 is the moov box header itself, not a child. Scanning from 0 would read the
        // moov as its own first child and walk straight past every real child, so mvex would
        // never be found and the whole fragmented check would silently return false.
        return child(moov, Atom_HEADER_SIZE, moov.length, MVEX) != null;
    }

    /** @return {mvhdTimescale, mvhdDuration, mehdDuration(-1 if absent)} */
    private static long[] parseMoov(byte[] d, Map<Integer, Track> tracks) {
        long[] result = new long[]{0, 0, -1};
        Map<Integer, int[]> trex = new HashMap<>();
        int[] mvex = child(d, 0, d.length, MVEX);
        if (mvex != null) {
            for (int[] r : children(d, mvex[0], mvex[1], TREX)) {
                if (r[0] + 24 <= d.length) {
                    trex.put(i32(d, r[0] + 4), new int[]{i32(d, r[0] + 12), i32(d, r[0] + 20)});
                }
            }
        }
        int[] mvhd = child(d, 0, d.length, MVHD);
        if (mvhd != null) {
            int ver = d[mvhd[0]] & 0xFF;
            result[0] = u32(d, mvhd[0] + (ver == 1 ? 20 : 12));
            result[1] = ver == 1 ? i64(d, mvhd[0] + 24) : u32(d, mvhd[0] + 16);
        }
        int[] mehd = child(d, 0, d.length, MEHD);
        if (mehd == null && mvex != null) {
            mehd = child(d, mvex[0], mvex[1], MEHD);
        }
        if (mehd != null) {
            int ver = d[mehd[0]] & 0xFF;
            result[2] = ver == 1 ? i64(d, mehd[0] + 4) : u32(d, mehd[0] + 4);
        }
        for (int[] trak : children(d, 0, d.length, TRAK)) {
            int[] tkhd = child(d, trak[0], trak[1], TKHD);
            int[] mdia = child(d, trak[0], trak[1], MDIA);
            if (tkhd == null || mdia == null) {
                continue;
            }
            int[] mdhd = child(d, mdia[0], mdia[1], MDHD);
            int[] hdlr = child(d, mdia[0], mdia[1], HDLR);
            if (mdhd == null || hdlr == null) {
                continue;
            }
            Track t = new Track();
            t.id = i32(d, tkhd[0] + ((d[tkhd[0]] & 0xFF) == 1 ? 20 : 12));
            t.timescale = i32(d, mdhd[0] + ((d[mdhd[0]] & 0xFF) == 1 ? 20 : 12));
            t.duration = (d[mdhd[0]] & 0xFF) == 1
                    ? i64(d, mdhd[0] + 24) : u32(d, mdhd[0] + 16);
            t.video = i32(d, hdlr[0] + 8) == VIDE;
            int[] tx = trex.get(t.id);
            if (tx != null) {
                t.defaultDuration = tx[0];
                t.defaultFlags = tx[1];
            }
            if (t.timescale > 0) {
                tracks.put(t.id, t);
            }
        }
        return result;
    }

    @Nullable
    private static Frag parseMoof(byte[] d, Map<Integer, Track> tracks) {
        List<int[]> trafs = children(d, 0, d.length, TRAF);
        if (trafs.isEmpty()) {
            return null;
        }
        int[] traf = trafs.get(0); // this file has exactly one traf per moof
        int[] tfhd = child(d, traf[0], traf[1], TFHD);
        int[] tfdt = child(d, traf[0], traf[1], TFDT);
        if (tfhd == null || tfdt == null) {
            return null;
        }
        int b = tfhd[0];
        int tfFlags = i32(d, b) & 0xFFFFFF;
        Track t = tracks.get(i32(d, b + 4));
        if (t == null) {
            return null;
        }
        int p = b + 8;
        if ((tfFlags & 0x1) != 0) {
            p += 8;  // base_data_offset
        }
        if ((tfFlags & 0x2) != 0) {
            p += 4;  // sample_description_index
        }
        int defaultDuration = t.defaultDuration;
        int defaultFlags = t.defaultFlags;
        if ((tfFlags & 0x8) != 0) {
            defaultDuration = i32(d, p);
            p += 4;
        }
        if ((tfFlags & 0x10) != 0) {
            p += 4;  // default_sample_size
        }
        if ((tfFlags & 0x20) != 0) {
            defaultFlags = i32(d, p);
            p += 4;
        }

        Frag f = new Frag();
        f.trackId = t.id;
        f.tfdt = (d[tfdt[0]] & 0xFF) == 1 ? i64(d, tfdt[0] + 4) : u32(d, tfdt[0] + 4);
        f.sync = true;

        boolean firstSample = true;
        for (int[] tr : children(d, traf[0], traf[1], TRUN)) {
            int q = tr[0];
            int trFlags = i32(d, q) & 0xFFFFFF;
            int count = i32(d, q + 4);
            if (count < 0 || count > 100000) {
                return null;
            }
            q += 8;
            if ((trFlags & 0x1) != 0) {
                q += 4; // data_offset
            }
            int firstSampleFlags = 0;
            boolean hasFirstSampleFlags = false;
            if ((trFlags & 0x4) != 0) {
                firstSampleFlags = i32(d, q);
                hasFirstSampleFlags = true;
                q += 4;
            }
            for (int i = 0; i < count; i++) {
                int dur = defaultDuration;
                int flags = (i == 0 && hasFirstSampleFlags) ? firstSampleFlags : defaultFlags;
                if ((trFlags & 0x100) != 0) {
                    dur = i32(d, q);
                    q += 4;
                }
                if ((trFlags & 0x200) != 0) {
                    q += 4; // sample_size
                }
                if ((trFlags & 0x400) != 0) {
                    flags = i32(d, q); // per-sample flags override tfhd/trex defaults
                    q += 4;
                }
                if ((trFlags & 0x800) != 0) {
                    q += 4; // composition_time_offset
                }
                if (firstSample) {
                    f.sync = (flags & SAMPLE_FLAG_NON_SYNC) == 0;
                    firstSample = false;
                }
                f.durTicks += dur & 0xFFFFFFFFL;
                f.samples++;
            }
        }
        return f;
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private static List<int[]> children(byte[] d, int s, int e, int type) {
        List<int[]> out = new ArrayList<>();
        int p = s;
        while (p + 8 <= e) {
            long size = u32(d, p);
            int t = i32(d, p + 4);
            int hs = 8;
            if (size == 1) {
                if (p + 16 > e) {
                    break;
                }
                size = i64(d, p + 8);
                hs = 16;
            } else if (size == 0) {
                break;
            }
            if (size < hs || p + size > e) {
                break;
            }
            if (t == type) {
                out.add(new int[]{p + hs, (int) (p + size)});
            }
            p += (int) size;
        }
        return out;
    }

    @Nullable
    private static int[] child(byte[] d, int s, int e, int type) {
        List<int[]> l = children(d, s, e, type);
        return l.isEmpty() ? null : l.get(0);
    }

    private static long us(long ticks, long timescale) {
        return timescale <= 0 ? 0 : ticks * 1000000L / timescale;
    }

    private static void reject(String reason) {
        Log.w(TAG, "reject=" + reason);
    }

    private static int cc(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private static int i32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    private static long u32(byte[] d, int o) {
        return i32(d, o) & 0xFFFFFFFFL;
    }

    private static long i64(byte[] d, int o) {
        return (u32(d, o) << 32) | u32(d, o + 4);
    }

    private static final class Track {
        int id;
        int timescale = 1;
        long duration;
        boolean video;
        int defaultDuration;
        int defaultFlags;

        @Override
        public String toString() {
            return "Track{" + id + " ts=" + timescale + " video=" + video + "}";
        }
    }

    private static final class Frag {
        int trackId;
        int samples;
        long pos;
        long end;
        long tfdt;
        long durTicks;
        boolean sync;
    }
}
