package org.telegram.ui.Components;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;

/**
 * {@link DefaultRenderersFactory} that installs {@link SdrFallbackCodecAdapterFactory}.
 * Use this for the video player; the audio-only player does not need color defaults.
 */
@UnstableApi
public class SdrFallbackRenderersFactory extends DefaultRenderersFactory {

    private final MediaCodecAdapter.Factory codecAdapterFactory;

    public SdrFallbackRenderersFactory(Context context) {
        super(context);
        this.codecAdapterFactory =
                new SdrFallbackCodecAdapterFactory(super.getCodecAdapterFactory());
    }

    @Override
    protected MediaCodecAdapter.Factory getCodecAdapterFactory() {
        return codecAdapterFactory;
    }
}
