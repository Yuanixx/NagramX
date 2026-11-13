package org.telegram.ui.Components;

import android.media.MediaFormat;
import android.os.Build;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;

import java.io.IOException;

/**
 * Restores the "no color info -> SDR / BT.709 / limited range" fallback that existed in the
 * vendored ExoPlayer 2.18.3 fork and was lost when the app moved to the Media3 1.11.1 AAR.
 *
 * <p>Old fork (exoplayer2/util/MediaFormatUtil.java:186) had an else branch in
 * {@code maybeSetColorInfo} which set the three color keys when {@code format.colorInfo == null}.
 * Media3 1.11.1's {@code androidx.media3.common.util.MediaFormatUtil.maybeSetColorInfo}
 * is just {@code if (colorInfo == null) return; ...} — no fallback — so on some GPUs/decoders
 * an SDR stream without color metadata is rendered as HDR (washed-out / overexposed).
 *
 * <p>Hooked in at the codec configuration stage rather than by replacing the video renderer:
 * {@link MediaCodecAdapter.Configuration#mediaFormat} is a mutable MediaFormat, and
 * {@code DefaultRenderersFactory.getCodecAdapterFactory()} is protected, so wrapping the
 * adapter factory needs no copy of Media3's internal renderer construction logic.
 *
 * <p>Uses {@code containsKey} per field instead of a single {@code colorInfo == null} check:
 * this also covers the case the old patch missed — a non-null ColorInfo whose fields are all
 * {@code Format.NO_VALUE}, for which Media3's {@code maybeSetInteger} skips the key entirely.
 * Keys already present (genuine HDR metadata) are never overwritten.
 */
@UnstableApi
public final class SdrFallbackCodecAdapterFactory implements MediaCodecAdapter.Factory {

    private final MediaCodecAdapter.Factory delegate;

    public SdrFallbackCodecAdapterFactory(MediaCodecAdapter.Factory delegate) {
        this.delegate = delegate;
    }

    @Override
    public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration configuration)
            throws IOException {
        MediaFormat mediaFormat = configuration.mediaFormat;
        // A video decode configuration carries a surface; audio ones do not.
        if (Build.VERSION.SDK_INT >= 24 && mediaFormat != null && configuration.surface != null) {
            if (!mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            }
            if (!mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                        MediaFormat.COLOR_STANDARD_BT709);
            }
            if (!mediaFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                        MediaFormat.COLOR_RANGE_LIMITED);
            }
        }
        return delegate.createAdapter(configuration);
    }
}
