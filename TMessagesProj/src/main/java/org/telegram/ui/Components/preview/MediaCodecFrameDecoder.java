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

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import org.telegram.ui.Components.MoovKeyframeParser;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Decodes exactly one keyframe per call by feeding that sample with
 * END_OF_STREAM, then flush() to reuse the codec instance.
 *
 * A codec that fails twice in a row is destroyed and rebuilt. Some vendor
 * decoders do not survive repeated flush, so "one codec forever" is not an
 * option; the stage 0 run showed zero recreations on the target device, but
 * the rebuild path stays as the safety net.
 */
public final class MediaCodecFrameDecoder implements FrameDecoder {

    private static final long OUTPUT_TIMEOUT_NS = 2_000_000_000L;
    private static final long INPUT_TIMEOUT_US = 200_000L;
    private static final int MAX_SAMPLE_BYTES = 32 * 1024 * 1024;

    private final File file;
    private final MoovKeyframeParser.TrackInfo info;
    private final ArrayList<MoovKeyframeParser.Keyframe> keyframes;
    /** Null for a complete local file; set for a sparse streaming .temp. */
    private final RangeAvailability availability;
    /**
     * Supplies keyframe bytes that are not on disk yet. Null for a complete
     * file, for an encrypted document, or when the network layer is off; in
     * all of those cases the decoder keeps its old skip behaviour.
     */
    private final RangeSupplier rangeSupplier;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private RandomAccessFile raf;
    private MediaCodec codec;
    private String codecName = "";
    private String codecSelection = "default";
    private boolean codecHardware;
    private boolean codecSoftware;
    private boolean codecVendor;
    private int consecutiveFailures;
    private int recreateCount;
    private int decodedFrames;
    private int unavailableSkips;
    private long inputWaitNanos;
    private long sampleReadNanos;
    private long outputWaitNanos;
    private long convertNanos;
    private long flushNanos;
    private byte[] sampleBytes;
    /** One keyframe fetched from the network, waiting to be decoded. */
    private byte[] remoteSampleBytes;
    private long remoteSampleOffset = -1;
    private int remoteSampleSize;
    private boolean closed;
    private volatile boolean cancelRequested;
    private volatile boolean cancelled;

    public MediaCodecFrameDecoder(File file, MoovKeyframeParser.TrackInfo info,
                                  RangeAvailability availability) {
        this(file, info, availability, null);
    }

    public MediaCodecFrameDecoder(File file, MoovKeyframeParser.TrackInfo info,
                                  RangeAvailability availability,
                                  RangeSupplier rangeSupplier) {
        this.file = file;
        this.info = info;
        this.keyframes = info != null ? info.keyframes : new ArrayList<>();
        this.availability = availability;
        this.rangeSupplier = rangeSupplier;
    }

    /**
     * Adds keyframes discovered after the session was built, e.g. fragments
     * that finished downloading while the preview is open. Called only from
     * the scheduler thread.
     */
    public void appendKeyframes(java.util.List<MoovKeyframeParser.Keyframe> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (MoovKeyframeParser.Keyframe keyframe : extra) {
            if (keyframe == null) {
                continue;
            }
            int index = 0;
            while (index < keyframes.size()
                    && keyframes.get(index).timeMs < keyframe.timeMs) {
                index++;
            }
            if (index < keyframes.size()
                    && keyframes.get(index).timeMs == keyframe.timeMs) {
                continue;
            }
            keyframes.add(index, keyframe);
        }
    }

    public void open() throws Exception {
        if (closed) {
            throw new IllegalStateException("decoder is closed");
        }
        raf = new RandomAccessFile(file, "r");
        try {
            openCodec(false);
            PreviewLog.d("SeekPreview: MediaCodec open " + info.mime
                    + " coded=" + info.codedWidth + "x" + info.codedHeight
                    + " display=" + info.displayWidth + "x" + info.displayHeight
                    + " rot=" + info.rotationDegrees
                    + " keyframes=" + keyframes.size()
                    + " decoder=" + codecName
                    + " hardware=" + codecHardware
                    + " software=" + codecSoftware
                    + " vendor=" + codecVendor
                    + " selection=" + codecSelection);
        } catch (Throwable e) {
            closeQuietly();
            throw e;
        }
    }

    public int getRecreateCount() {
        return recreateCount;
    }

    @Override
    public DecodedFrame decodeAt(long timeMs, int maxWidth, int maxHeight) throws Exception {
        if (closed) {
            throw new IllegalStateException("decoder is closed");
        }
        cancelled = false;
        cancelRequested = false;
        final int index = nearestKeyframeIndex(timeMs);
        if (index < 0 || index >= keyframes.size()) {
            return null;
        }
        if (availability != null) {
            final MoovKeyframeParser.Keyframe keyframe = keyframes.get(index);
            if (!availability.isAvailable(keyframe.offset, keyframe.size)) {
                // The range is still missing locally. Ask the network layer for
                // exactly this sample and decode it from memory; the downloader
                // file is never touched. A null answer keeps the old behaviour.
                final byte[] remote = fetchRemote(keyframe);
                if (remote != null) {
                    remoteSampleBytes = remote;
                    remoteSampleOffset = keyframe.offset;
                    remoteSampleSize = keyframe.size;
                    return decodeKeyframe(index, maxWidth, maxHeight, false);
                }
                // A streaming .temp is sparse: bytes the downloader has not
                // filled are zeros, and zeros would burn the codec. Skipping is
                // not a failure; the hover retry picks the frame up when the
                // range arrives.
                if (unavailableSkips < 5 || (unavailableSkips & 31) == 0) {
                    PreviewLog.d("SeekPreview: MediaCodec skip kf=" + index
                            + " t=" + keyframe.timeMs
                            + " off=" + keyframe.offset
                            + " size=" + keyframe.size);
                }
                unavailableSkips++;
                return null;
            }
        }
        return decodeKeyframe(index, maxWidth, maxHeight, false);
    }

    /**
     * Asks the supplier for one whole keyframe. Anything shorter is refused:
     * a truncated sample fails inside the codec and would hide the real cause.
     */
    private byte[] fetchRemote(MoovKeyframeParser.Keyframe keyframe) {
        if (rangeSupplier == null || keyframe == null || keyframe.size <= 0) {
            return null;
        }
        try {
            final byte[] bytes = rangeSupplier.fetch(keyframe.offset, keyframe.size,
                    keyframe.timeMs);
            if (bytes == null || bytes.length < keyframe.size) {
                return null;
            }
            return bytes;
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: remote keyframe failed off=" + keyframe.offset
                    + " size=" + keyframe.size, e);
            return null;
        }
    }

    private DecodedFrame decodeKeyframe(int keyframeIndex, int maxWidth, int maxHeight,
                                        boolean retried) throws Exception {
        try {
            return decodeOnce(keyframeIndex, maxWidth, maxHeight);
        } catch (Throwable e) {
            if (cancelRequested) {
                cancelled = true;
                throw e;
            }
            consecutiveFailures++;
            PreviewLog.w("SeekPreview: keyframe decode failed kf=" + keyframeIndex
                    + " failures=" + consecutiveFailures + " retried=" + retried, e);
            try {
                if (codec != null) {
                    codec.flush();
                }
            } catch (Throwable ignore) {
            }
            if (consecutiveFailures >= 2) {
                consecutiveFailures = 0;
                rebuild();
            }
            if (retried) {
                throw e;
            }
            return decodeKeyframe(keyframeIndex, maxWidth, maxHeight, true);
        }
    }

    private DecodedFrame decodeOnce(int keyframeIndex, int maxWidth, int maxHeight)
            throws Exception {
        if (codec == null) {
            throw new IllegalStateException("codec is not open");
        }
        if (keyframeIndex < 0 || keyframeIndex >= keyframes.size()) {
            throw new IllegalArgumentException("keyframe index " + keyframeIndex);
        }
        final MoovKeyframeParser.Keyframe keyframe = keyframes.get(keyframeIndex);

        final long inputStarted = System.nanoTime();
        final int inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
        inputWaitNanos += System.nanoTime() - inputStarted;
        if (inputIndex < 0) {
            throw new IllegalStateException("no input buffer");
        }
        final ByteBuffer input = codec.getInputBuffer(inputIndex);
        if (input == null) {
            throw new IllegalStateException("null input buffer");
        }
        input.clear();
        final long sampleStarted = System.nanoTime();
        final int bytes = writeSample(input, keyframe);
        sampleReadNanos += System.nanoTime() - sampleStarted;
        if (bytes <= 0) {
            throw new IllegalStateException("empty keyframe " + keyframeIndex);
        }

        final long ptsUs = keyframe.timeUs > 0 ? keyframe.timeUs : keyframe.timeMs * 1000L;
        final long started = System.nanoTime();
        codec.queueInputBuffer(inputIndex, 0, bytes, ptsUs,
                MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_END_OF_STREAM);

        Bitmap bitmap = null;
        MediaFormat outputFormat = null;
        final int[] target = targetSize(maxWidth, maxHeight);
        while (System.nanoTime() - started < OUTPUT_TIMEOUT_NS) {
            if (cancelRequested) {
                cancelled = true;
                throw new IllegalStateException("decoder cancelled");
            }
            final long outputStarted = System.nanoTime();
            final int out = codec.dequeueOutputBuffer(bufferInfo, 10_000);
            outputWaitNanos += System.nanoTime() - outputStarted;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = codec.getOutputFormat();
                continue;
            }
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER
                    || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            }
            if (out < 0) {
                continue;
            }
            final boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (bufferInfo.size != 0) {
                Image image = null;
                try {
                    image = codec.getOutputImage(out);
                } catch (Throwable e) {
                    PreviewLog.w("SeekPreview: getOutputImage failed, format=" + codec.getOutputFormat(), e);
                }
                if (image != null) {
                    try {
                        final long convertStarted = System.nanoTime();
                        bitmap = YuvToBitmap.convert(image, target[0], target[1],
                                info.rotationDegrees, info.colorMatrix, info.fullRange);
                        convertNanos += System.nanoTime() - convertStarted;
                    } finally {
                        try {
                            image.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if (bitmap != null) {
                    break;
                }
            } else {
                codec.releaseOutputBuffer(out, false);
            }
            if (eos) {
                break;
            }
        }

        if (bitmap == null) {
            final String format = outputFormat != null ? outputFormat.toString() : "null";
            throw new IllegalStateException("no output image, outputFormat=" + format);
        }

        final long flushStarted = System.nanoTime();
        try {
            codec.flush();
        } catch (Throwable e) {
            PreviewLog.w("SeekPreview: flush failed", e);
        } finally {
            flushNanos += System.nanoTime() - flushStarted;
        }
        consecutiveFailures = 0;
        decodedFrames++;
        // The frame is out; drop the network copy. A failed attempt keeps it so
        // the retry does not fall back to the sparse file and read zeros.
        remoteSampleBytes = null;
        remoteSampleOffset = -1;
        remoteSampleSize = 0;
        return new DecodedFrame(bitmap, keyframe.timeMs, keyframeIndex);
    }

    String timingStats() {
        final int count = Math.max(1, decodedFrames);
        return " codecFrames=" + decodedFrames
                + " avgInputMs=" + inputWaitNanos / 1_000_000L / count
                + " avgReadMs=" + sampleReadNanos / 1_000_000L / count
                + " avgOutputMs=" + outputWaitNanos / 1_000_000L / count
                + " avgConvertMs=" + convertNanos / 1_000_000L / count
                + " avgFlushMs=" + flushNanos / 1_000_000L / count;
    }

    /**
     * @return coded target size, i.e. before the display rotation is applied
     */
    private int[] targetSize(int maxWidth, int maxHeight) {
        final boolean swap = info.rotationDegrees == 90 || info.rotationDegrees == 270;
        int displayW = info.displayWidth > 0 ? info.displayWidth : info.codedWidth;
        int displayH = info.displayHeight > 0 ? info.displayHeight : info.codedHeight;
        if (displayW <= 0 || displayH <= 0) {
            displayW = info.codedWidth > 0 ? info.codedWidth : 16;
            displayH = info.codedHeight > 0 ? info.codedHeight : 9;
        }
        if (swap) {
            final int tmp = displayW;
            displayW = displayH;
            displayH = tmp;
        }
        final boolean portrait = displayH > displayW;
        final int boxW = portrait ? maxHeight : maxWidth;
        final int boxH = portrait ? maxWidth : maxHeight;
        final float scale = Math.min(boxW / (float) displayW, boxH / (float) displayH);
        final int outW = even(Math.round(displayW * scale));
        final int outH = even(Math.round(displayH * scale));
        return swap ? new int[]{outH, outW} : new int[]{outW, outH};
    }

    private static int even(int value) {
        final int rounded = Math.max(2, (value / 2) * 2);
        return rounded;
    }

    /**
     * Copies one AVCC/HVCC sample into Annex B form. Returns bytes written.
     */
    private int writeSample(ByteBuffer dst, MoovKeyframeParser.Keyframe keyframe) throws Exception {
        final int size = keyframe.size;
        if (size <= 0 || size > MAX_SAMPLE_BYTES) {
            return 0;
        }
        if (sampleBytes == null || sampleBytes.length < size) {
            sampleBytes = new byte[Math.max(size, 4 * 1024 * 1024)];
        }
        if (remoteSampleBytes != null && remoteSampleOffset == keyframe.offset
                && remoteSampleSize == keyframe.size) {
            // This sample came from the network; the local file still has a
            // hole at this offset. Copy only the sample, not the rest of the
            // fetched block.
            System.arraycopy(remoteSampleBytes, 0, sampleBytes, 0, size);
        } else {
            raf.seek(keyframe.offset);
            raf.readFully(sampleBytes, 0, size);
        }

        final int nalLength = info.nalLengthSize > 0 ? info.nalLengthSize : 4;
        int p = 0;
        int written = 0;
        while (p + nalLength <= size) {
            int len = 0;
            for (int i = 0; i < nalLength; i++) {
                len = (len << 8) | (sampleBytes[p + i] & 0xff);
            }
            p += nalLength;
            if (len <= 0 || p + len > size) {
                break;
            }
            if (dst.remaining() < len + 4) {
                break;
            }
            dst.put((byte) 0);
            dst.put((byte) 0);
            dst.put((byte) 0);
            dst.put((byte) 1);
            dst.put(sampleBytes, p, len);
            written += len + 4;
            p += len;
        }
        return written;
    }

    private int nearestKeyframeIndex(long timeMs) {
        final int n = keyframes.size();
        if (n == 0) {
            return -1;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (keyframes.get(mid).timeMs < timeMs) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if (lo > 0) {
            final long before = timeMs - keyframes.get(lo - 1).timeMs;
            final long after = keyframes.get(lo).timeMs - timeMs;
            if (before <= after) {
                return lo - 1;
            }
        }
        return lo;
    }

    private void openCodec(boolean useRawConfig) throws Exception {
        final MediaFormat format = MediaFormat.createVideoFormat(info.mime,
                info.codedWidth, info.codedHeight);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024);
        if (useRawConfig && info.codecConfig != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(info.codecConfig));
        } else {
            if (info.csd0 != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(info.csd0));
            }
            if (info.csd1 != null) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(info.csd1));
            }
        }
        final MediaCodecInfo selected = findDecoder(format);
        MediaCodec c = selected != null
                ? MediaCodec.createByCodecName(selected.getName())
                : MediaCodec.createDecoderByType(info.mime);
        try {
            c.configure(format, null, null, 0);
            c.start();
        } catch (Throwable e) {
            releaseFailedCodec(c);
            if (!useRawConfig && info.codecConfig != null) {
                PreviewLog.w("SeekPreview: csd split rejected, retrying with raw codec config", e);
                openCodec(true);
                return;
            }
            if (selected != null && codecSelection.startsWith("hardware")) {
                PreviewLog.w("SeekPreview: hardware decoder " + selected.getName()
                        + " rejected the format, using the system default", e);
                c = MediaCodec.createDecoderByType(info.mime);
                try {
                    c.configure(format, null, null, 0);
                    c.start();
                } catch (Throwable fallbackError) {
                    releaseFailedCodec(c);
                    throw fallbackError;
                }
                codecSelection = "system-fallback";
            } else {
                throw e;
            }
        }
        codec = c;
        codecName = c.getName();
        setCodecFlags(c.getCodecInfo(), codecSelection);
        bufferInfo.set(0, 0, 0, 0);
    }

    private MediaCodecInfo findDecoder(MediaFormat format) {
        final MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String recommendedName = null;
        try {
            recommendedName = list.findDecoderForFormat(format);
        } catch (Throwable ignore) {
        }
        MediaCodecInfo first = null;
        MediaCodecInfo hardwareName = null;
        MediaCodecInfo recommended = null;
        for (MediaCodecInfo codecInfo : list.getCodecInfos()) {
            if (codecInfo.isEncoder() || !supportsType(codecInfo, info.mime)) {
                continue;
            }
            if (first == null) {
                first = codecInfo;
            }
            if (codecInfo.getName().equals(recommendedName)) {
                recommended = codecInfo;
            }
            if (Build.VERSION.SDK_INT >= 29 && codecInfo.isHardwareAccelerated()) {
                setCodecFlags(codecInfo, "hardware-flag");
                return codecInfo;
            }
            if (hardwareName == null && isHardwareName(codecInfo.getName())) {
                hardwareName = codecInfo;
            }
        }
        if (hardwareName != null) {
            setCodecFlags(hardwareName, "hardware-name");
            return hardwareName;
        }
        if (recommended != null) {
            setCodecFlags(recommended, "system-recommended");
            return recommended;
        }
        if (first != null) {
            setCodecFlags(first, "available-decoder");
            return first;
        }
        codecSelection = "system-default";
        return null;
    }

    private void setCodecFlags(MediaCodecInfo codecInfo, String selection) {
        codecSelection = selection;
        codecHardware = Build.VERSION.SDK_INT >= 29 && codecInfo.isHardwareAccelerated();
        codecSoftware = isSoftwareName(codecInfo.getName())
                || Build.VERSION.SDK_INT >= 29 && codecInfo.isSoftwareOnly();
        codecVendor = Build.VERSION.SDK_INT >= 29 && codecInfo.isVendor();
    }

    private static boolean supportsType(MediaCodecInfo codecInfo, String mime) {
        try {
            for (String type : codecInfo.getSupportedTypes()) {
                if (mime.equalsIgnoreCase(type)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean isHardwareName(String name) {
        final String value = name.toLowerCase(Locale.US);
        return value.contains("qti") || value.contains("qcom")
                || value.contains("exynos") || value.contains("mediatek")
                || value.contains("mtk") || value.contains("mali")
                || value.contains("nvidia") || value.contains("goldfish");
    }

    private static boolean isSoftwareName(String name) {
        final String value = name.toLowerCase(Locale.US);
        return value.startsWith("omx.google") || value.startsWith("c2.android")
                || value.contains("sw.decoder") || value.contains("software");
    }

    private static void releaseFailedCodec(MediaCodec c) {
        try {
            c.release();
        } catch (Throwable ignore) {
        }
    }

    private void rebuild() throws Exception {
        recreateCount++;
        PreviewLog.w("SeekPreview: rebuilding decoder after consecutive failures, count=" + recreateCount);
        releaseCodec();
        openCodec(true);
    }

    private void releaseCodec() {
        final MediaCodec old = codec;
        codec = null;
        if (old != null) {
            try {
                old.stop();
            } catch (Throwable ignore) {
            }
            try {
                old.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private void closeQuietly() {
        releaseCodec();
        if (raf != null) {
            try {
                raf.close();
            } catch (Throwable ignore) {
            }
            raf = null;
        }
    }

    @Override
    public long getDurationMs() {
        return info != null ? info.durationMs : 0;
    }

    @Override
    public String name() {
        return codecName;
    }

    @Override
    public void cancel() {
        cancelRequested = true;
    }

    @Override
    public boolean wasCancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        closed = true;
        cancelRequested = true;
        closeQuietly();
    }
}
