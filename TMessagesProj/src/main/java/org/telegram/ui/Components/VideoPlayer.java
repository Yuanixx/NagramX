/**
 * ZH-COMMENT-BY-AGENT
 * 文件：VideoPlayer.java
 * 模块：TMessagesProj/src/main/java/org/telegram/ui/Components
 * 类型：VideoPlayer（视频Player）、VideoPlayerDelegate（视频PlayerDelegate）、AudioVisualizerDelegate（音频VisualizerDelegate）、Quality（Quality）、VideoUri（视频Uri）、AudioVisualizerRenderersFactory（音频VisualizerRenderersFactory）、VisualizerBufferSink（Visualizer缓冲区Sink）、OffsetDataSource（Offset数据Source）
 * 主要成员：createdWithAudioTrack（createdWith音频Track）、onStateChanged（onStateChanged）、onError（on错误）、onVideoSizeChanged（on视频大小Changed）、onRenderedFirstFrame（onRenderedFirstFrame）、onSurfaceTextureUpdated（onSurfaceTextureUpdated）、onSurfaceDestroyed（onSurfaceDestroyed）、onSeekStarted（onSeekStarted）、onSeekFinished（onSeekFinished）、onVisualizerUpdate（onVisualizer更新）
 *          needUpdate（need更新）、SurfaceTextureListener（SurfaceTextureListener）、onSurfaceTextureAvailable（onSurfaceTextureAvailable）、Surface（Surface）
 * 说明：本文件由中文注释引擎自动生成结构化说明；业务语义详见各成员处注释。
 */
/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.EGLContext;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LongSparseArray;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.LoopingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.VideoSize;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FourierTransform;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.chromecast.ChromecastMedia;
import org.telegram.messenger.chromecast.ChromecastMediaVariations;
import org.telegram.messenger.secretmedia.ExtendedDefaultDataSourceFactory;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import xyz.nextalone.nagram.NaConfig;

/**
 * 类：视频Player，封装了相关的业务逻辑与数据处理。
 */
@SuppressLint("NewApi")
public class VideoPlayer implements Player.Listener, AnalyticsListener, NotificationCenter.NotificationCenterDelegate {

    private static int lastPlayerId = 0;
    public final int playerId = lastPlayerId++;
    public static final HashSet<Integer> activePlayers = new HashSet<>();

    private DispatchQueue workerQueue;
    private boolean isStory;

    /**
     * createdWith音频Track。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean createdWithAudioTrack() {
        return !audioDisabled;
    }

    /**
     * 接口：视频PlayerDelegate，封装了相关的业务逻辑与数据处理。
     */
    public interface VideoPlayerDelegate {
        /**
         * 响应状态Changed。
         * @param playWhenReady 布尔值类型的参数 playWhenReady
         * @param playbackState 整数类型的参数 playbackState
         */
        void onStateChanged(boolean playWhenReady, int playbackState);

        /**
         * 带身份的回调重载。
         *
         * generation 必须在“回调产生时”由 VideoPlayer 传入，而不是让消费方在回调到达时
         * 再去读 player.getPrepareGeneration()。原因：同一个 VideoPlayer 包装对象可能被
         * 复用并再次 prepare；消费时读到的是当前 generation，旧 prepare 的迟到回调
         * 会伪装成当前回调。只有产生时捕获的 generation 才能证明回调来源。
         *
         * 未覆写的其他 delegate 实现继续走下面这个不带身份的默认方法，零改动兼容。
         */
        default void onStateChanged(VideoPlayer player, int generation, long requestId,
                                    boolean playWhenReady, int playbackState) {
            onStateChanged(playWhenReady, playbackState);
        }
        /**
         * 响应错误。
         * @param player VideoPlayer类型的参数 player
         * @param e Exception类型的参数 e
         */
        void onError(VideoPlayer player, Exception e);
        /**
         * 响应视频尺寸Changed。
         * @param width 宽度（整数）
         * @param height 高度（整数）
         * @param unappliedRotationDegrees 整数类型的参数 unappliedRotationDegrees
         * @param pixelWidthHeightRatio 浮点值类型的参数 pixelWidthHeightRatio
         */
        void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio);
        /**
         * 响应Rendered首个Frame。
         */
        void onRenderedFirstFrame();
        /**
         * 响应SurfaceTextureUpdated。
         * @param surfaceTexture SurfaceTexture类型的参数 surfaceTexture
         */
        void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture);
        /**
         * 响应SurfaceDestroyed。
         * @param surfaceTexture SurfaceTexture类型的参数 surfaceTexture
         * @return 布尔结果，true 表示条件成立或操作成功
         */
        boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture);
        /**
         * 响应Rendered首个Frame。
         * @param eventTime EventTime类型的参数 eventTime
         */
        default void onRenderedFirstFrame(EventTime eventTime) {

        }
        /**
         * 响应定位到Started。
         * @param eventTime EventTime类型的参数 eventTime
         */
        default void onSeekStarted(EventTime eventTime) {

        }
        /**
         * 响应定位到Finished。
         */
        default void onSeekFinished() {

        }
    }

    /**
     * 接口：音频VisualizerDelegate，封装了相关的业务逻辑与数据处理。
     */
    public interface AudioVisualizerDelegate {
        /**
         * 响应Visualizer更新。
         * @param playing 布尔值类型的参数 playing
         * @param animate 播放动画（布尔值）
         * @param values 值（浮点数组）
         */
        void onVisualizerUpdate(boolean playing, boolean animate, float[] values);
        /**
         * need更新。
         * @return 布尔结果，true 表示条件成立或操作成功
         */
        boolean needUpdate();
    }

    public ExoPlayer player;
    private ExoPlayer audioPlayer;
    private DefaultBandwidthMeter bandwidthMeter;
    private MappingTrackSelector trackSelector;
    private ExtendedDefaultDataSourceFactory mediaDataSourceFactory;
    /**
     * Wraps every 数据 source 使用 {@link TrunFilteringDataSource}, so  empty
     * trun 是 neutralized 在  stream itself. Independent 的 any local copy
     * 和 的 which quality 是 为 played.
     */
    private DataSource.Factory filteringDataSourceFactory;
    private TextureView textureView;
    private SurfaceView surfaceView;
    private Surface surface;
    private Surface ownedTextureSurface;

    /**
     * Replacement 用于 ExoPlayer's VideoListener surface callbacks, which no longer exist 在
     * Media3. Keeps  old fork behaviour:  delegate can veto SurfaceTexture destruction
     * (needed 用于  Picture-在-Picture hand-off) 和 surface 更新 是 posted 到 
     * optional worker queue.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        /**
         * 响应SurfaceTextureAvailable。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param surfaceTexture SurfaceTexture类型的参数 surfaceTexture
         * @param width 宽度（整数）
         * @param height 高度（整数）
         */
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            Runnable r = () -> {
                if (player != null) {
                    Surface newSurface = new Surface(surfaceTexture);
                    player.setVideoSurface(newSurface);
                    setOwnedTextureSurface(newSurface);
                }
            };
            if (workerQueue != null) {
                workerQueue.postRunnable(r);
            } else {
                r.run();
            }
        }

        /**
         * 响应SurfaceTexture尺寸Changed。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param surfaceTexture SurfaceTexture类型的参数 surfaceTexture
         * @param width 宽度（整数）
         * @param height 高度（整数）
         */
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        }

        /**
         * 响应SurfaceTextureDestroyed。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param surfaceTexture SurfaceTexture类型的参数 surfaceTexture
         * @return 布尔结果，true 表示条件成立或操作成功
         */
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            boolean keepSurface = delegate != null && delegate.onSurfaceDestroyed(surfaceTexture);
            if (!keepSurface) {
                Runnable r = () -> {
                    if (player != null) {
                        player.clearVideoSurface();
                    }
                    releaseOwnedTextureSurface();
                };
                if (workerQueue != null) {
                    workerQueue.postRunnable(r);
                } else {
                    r.run();
                }
            }
            return !keepSurface;
        }

        /**
         * 响应SurfaceTextureUpdated。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param surfaceTexture SurfaceTexture类型的参数 surfaceTexture
         */
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            Runnable r = () -> {
                if (delegate != null) {
                    delegate.onSurfaceTextureUpdated(surfaceTexture);
                }
            };
            if (workerQueue != null) {
                workerQueue.postRunnable(r);
            } else {
                r.run();
            }
        }
    };

    /**
     * attachTexture视图。
     * @param texture TextureView类型的参数 texture
     */
    private void attachTextureView(TextureView texture) {
        texture.setSurfaceTextureListener(surfaceTextureListener);
        if (texture.isAvailable()) {
            SurfaceTexture surfaceTexture = texture.getSurfaceTexture();
            if (surfaceTexture != null && player != null) {
                Surface newSurface = new Surface(surfaceTexture);
                player.setVideoSurface(newSurface);
                setOwnedTextureSurface(newSurface);
            }
        }
    }

    /**
     * 设置OwnedTextureSurface。
     * @param newSurface Surface类型的参数 newSurface
     */
    private void setOwnedTextureSurface(Surface newSurface) {
        Surface oldSurface = ownedTextureSurface;
        ownedTextureSurface = newSurface;
        if (oldSurface != null && oldSurface != newSurface) {
            oldSurface.release();
        }
    }

    /**
     * 释放OwnedTextureSurface。
     */
    private void releaseOwnedTextureSurface() {
        if (ownedTextureSurface != null) {
            ownedTextureSurface.release();
            ownedTextureSurface = null;
        }
    }
    private boolean isStreaming;
    private boolean autoplay;
    private boolean mixedAudio;
    public boolean allowMultipleInstances;

    private boolean triedReinit;

    // Internal 错误-recovery position preservation (onPlayerError AV1 / decoder-初始化).
    // Only armed 由 那些 two internal recovery paths; normal 打开 / quality switch do 不 使用 它.
    // volatile because recovery may 为 scheduled 在  UI 线程 和 consumed 在  player looper.
    private volatile ExoPlayer recoveryPlayerRef = null;
    private volatile long recoveryPositionMs = C.TIME_UNSET;
    private volatile boolean recoveryPlayWhenReady = false;
    private volatile int recoveryGeneration = -1;
    private volatile int prepareGeneration = 0;
    /** PhotoViewer 为本次重建绑定的 seek 请求 ID；在 prepare 创建监听器时捕获。 */
    private volatile long boundRequestId = -1L;
    /** 当前正在分发的回调所携带的来源身份，由监听器在注册时捕获并写入。 */
    private volatile int callbackGeneration = -1;
    private volatile long callbackRequestId = -1L;
    /** 当前注册的代次感知监听器；每次 prepare 都要先移除旧的再注册新的。 */
    @Nullable
    private Player.Listener generationAwareListener;

    /**
     * 设置Bound请求标识。
     * @param requestId 长整型类型的参数 requestId
     */
    public void setBoundRequestId(long requestId) {
        this.boundRequestId = requestId;
    }

    /**
     * 当前 prepare 的代次。每次 preparePlayer* 都会递增。
     * PhotoViewer 用它把待重放 seek 绑定到产生它的那一次 prepare；
     * 旧 prepare 即使复用同一个包装对象，generation 也不同，无法消费新目标。
     */
    /**
     * 获取准备Generation。
     * @return 整型结果值
     */
    public int getPrepareGeneration() {
        return prepareGeneration;
    }

    // Observe-only Player-internal diagnostics (参见 PlayerDiag: log lines). No behavior change.
    private volatile long diagLastStateAtMs = -1L;
    private volatile long diagLastTimelineChangeAtMs = -1L;
    private volatile long diagLastPrepareAtMs = -1L;

    private Uri currentUri;

    private boolean videoPlayerReady;
    private boolean audioPlayerReady;
    private boolean mixedPlayWhenReady;

    private VideoPlayerDelegate delegate;
    private AudioVisualizerDelegate audioVisualizerDelegate;
    private int lastReportedPlaybackState;
    private boolean lastReportedPlayWhenReady;

    private ArrayList<Quality> videoQualities;
    private Quality videoQualityToSelect;
    private ArrayList<VideoUri> manifestUris;
    private Uri videoUri, audioUri;
    private String videoType, audioType;
    private boolean loopingMediaSource;
    private boolean looping;
    private int repeatCount;

    private boolean shouldPauseOther;
    MediaSource.Factory dashMediaSourceFactory;
    HlsMediaSource.Factory hlsMediaSourceFactory;
    SsMediaSource.Factory ssMediaSourceFactory;
    ProgressiveMediaSource.Factory progressiveMediaSourceFactory;

    /** Per-player extractor factory carrying  prebuilt ChunkIndex; 空值 keeps  默认. */
    @Nullable
    private ExtractorsFactory extractorsFactoryOverride;

    /**
     * 被调用 由 PhotoViewer before every prepare. Passing 空值 清空 any 索引 left 从 
     * previous 媒体 (also covers  PiP-injected player, which 是 reused instead 的 recreated).
     */
    /**
     * 设置ExtractorsFactory。
     * @param factory ExtractorsFactory类型的参数 factory
     */
    public void setExtractorsFactory(@Nullable ExtractorsFactory factory) {
        extractorsFactoryOverride = factory;
    }

    /**
     * Whether  current 媒体 has  time-到-byte map.  streaming fragmented 文件 prepared
     * before 其 download started has none; issuing  seek 则 makes media3 clamp  target
     * 到 t=0. Callers must re-prepare once  download 是 active instead.
     */
    /**
     * 判断是否包含定位到索引。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean hasSeekIndex() {
        return extractorsFactoryOverride instanceof Mp4IndexExtractorsFactory
                && ((Mp4IndexExtractorsFactory) extractorsFactoryOverride).hasIndex();
    }

    /** Whether  current seek map can resolve  target (milliseconds). */
    public boolean hasSeekIndexFor(long positionMs) {
        return extractorsFactoryOverride instanceof Mp4IndexExtractorsFactory
                && ((Mp4IndexExtractorsFactory) extractorsFactoryOverride)
                        .canSeekTo(positionMs * 1000L);
    }

    Handler audioUpdateHandler = new Handler(Looper.getMainLooper());

    boolean audioDisabled;

    /**
     * 构造方法：创建 VideoPlayer 实例并初始化其内部状态。
     */
    public VideoPlayer() {
        this(true, false);
    }

    static int playerCounter = 0;
    /**
     * 构造方法：创建 VideoPlayer 实例并初始化其内部状态。
     * @param pauseOther 布尔值类型的参数 pauseOther
     * @param audioDisabled 布尔值类型的参数 audioDisabled
     */
    public VideoPlayer(boolean pauseOther, boolean audioDisabled) {
        this.audioDisabled = audioDisabled;
        mediaDataSourceFactory = new ExtendedDefaultDataSourceFactory(ApplicationLoader.applicationContext, "Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)");
        filteringDataSourceFactory = () -> new TrunFilteringDataSource(mediaDataSourceFactory.createDataSource());
        trackSelector = new DefaultTrackSelector(ApplicationLoader.applicationContext, new AdaptiveTrackSelection.Factory());
        if (audioDisabled) {
            trackSelector.setParameters(trackSelector.getParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        }
        lastReportedPlaybackState = Player.STATE_IDLE;
        shouldPauseOther = pauseOther;
        if (pauseOther) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.playerDidStartPlaying);
        }
        playerCounter++;
    }

    /**
     * didReceivedNotification。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param id 标识（整数）
     * @param account 整数类型的参数 account
     * @param args Object...类型的参数 args
     */
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.playerDidStartPlaying) {
            VideoPlayer p = (VideoPlayer) args[0];
            if (p != this && isPlaying() && !allowMultipleInstances) {
                pause();
            }
        }
    }

    private Looper looper;
    /**
     * 设置Looper。
     * @param looper Looper类型的参数 looper
     */
    public void setLooper(Looper looper) {
        this.looper = looper;
    }

    private EGLContext eglParentContext;
    /**
     * 设置EGLContext。
     * @param ctx EGLContext类型的参数 ctx
     */
    public void setEGLContext(EGLContext ctx) {
        eglParentContext = ctx;
    }

    /**
     * 获取PlayerExtensionRenderer模式。
     * @return 整型结果值
     */
    private int getPlayerExtensionRendererMode() {
        return switch (NaConfig.INSTANCE.getPlayerDecoder().Int()) {
            case 0 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
            case 1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
            default -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        };
    }

    /**
     * 创建一个在注册时捕获代次和请求 ID 的 Player 监听器。
     * 只有 onPlayerStateChanged 需要携带身份；其余回调原样转发给 VideoPlayer 自身的实现，
     * 保证原有行为不变。
     */
    /**
     * 创建GenerationAware监听器。
     * @param generation 整数类型的参数 generation
     * @param requestId 长整型类型的参数 requestId
     * @return Player.Listener 类型的结果
     */
    private Player.Listener createGenerationAwareListener(final int generation, final long requestId) {
        return new Player.Listener() {
            /**
             * 响应Player状态Changed。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param playWhenReady 布尔值类型的参数 playWhenReady
             * @param playbackState 整数类型的参数 playbackState
             */
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                dispatchPlayerState(generation, requestId, playWhenReady, playbackState);
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                Player.PositionInfo newPosition,
                                                @Player.DiscontinuityReason int reason) {
                VideoPlayer.this.onPositionDiscontinuity(oldPosition, newPosition, reason);
            }

            /**
             * 响应TimelineChanged。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param timeline Timeline类型的参数 timeline
             * @param reason 整数类型的参数 reason
             */
            @Override
            public void onTimelineChanged(Timeline timeline, int reason) {
                VideoPlayer.this.onTimelineChanged(timeline, reason);
            }

            /**
             * 响应Player错误。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param error 错误（PlaybackException）
             */
            @Override
            public void onPlayerError(PlaybackException error) {
                VideoPlayer.this.onPlayerError(error);
            }

            /**
             * 响应视频尺寸Changed。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param videoSize VideoSize类型的参数 videoSize
             */
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                VideoPlayer.this.onVideoSizeChanged(videoSize);
            }

            /**
             * 响应Rendered首个Frame。
             * <p>该方法重写了父类/接口中的实现。</p>
             */
            @Override
            public void onRenderedFirstFrame() {
                VideoPlayer.this.onRenderedFirstFrame();
            }

            /**
             * 响应PlaybackParametersChanged。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param playbackParameters PlaybackParameters类型的参数 playbackParameters
             */
            @Override
            public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
                VideoPlayer.this.onPlaybackParametersChanged(playbackParameters);
            }

            /**
             * 响应TracksChanged。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param tracks Tracks类型的参数 tracks
             */
            @Override
            public void onTracksChanged(Tracks tracks) {
                VideoPlayer.this.onTracksChanged(tracks);
            }

            /**
             * 响应Repeat模式Changed。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param repeatMode 整数类型的参数 repeatMode
             */
            @Override
            public void onRepeatModeChanged(int repeatMode) {
                VideoPlayer.this.onRepeatModeChanged(repeatMode);
            }

            /**
             * 响应Surface尺寸Changed。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param width 宽度（整数）
             * @param height 高度（整数）
             */
            @Override
            public void onSurfaceSizeChanged(int width, int height) {
                VideoPlayer.this.onSurfaceSizeChanged(width, height);
            }

            /**
             * 响应TrackSelectionParametersChanged。
             * <p>该方法重写了父类/接口中的实现。</p>
             * @param parameters TrackSelectionParameters类型的参数 parameters
             */
            @Override
            public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
                VideoPlayer.this.onTrackSelectionParametersChanged(parameters);
            }
        };
    }

    /**
     * 确保PlayerCreated。
     */
    private void ensurePlayerCreated() {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setAllocator(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        isStory ? 1000 : 100,
                        isStory ? 1000 : DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
                .setPrioritizeTimeOverSizeThresholds(DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS)
                .setBackBuffer(
                        DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
                        DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME)
                .build();
        if (player == null) {
            DefaultRenderersFactory factory;
            if (audioVisualizerDelegate != null) {
                factory = new AudioVisualizerRenderersFactory(ApplicationLoader.applicationContext);
            } else {
                factory = new SdrFallbackRenderersFactory(ApplicationLoader.applicationContext);
            }
            factory.setExtensionRendererMode(getPlayerExtensionRendererMode());
            ExoPlayer.Builder builder = new ExoPlayer.Builder(ApplicationLoader.applicationContext).setRenderersFactory(factory)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl);
            if (looper != null) {
                builder.setLooper(looper);
            }
            player = builder.build();

            player.addAnalyticsListener(this);
            if (textureView != null) {
                attachTextureView(textureView);
            } else if (surface != null) {
                player.setVideoSurface(surface);
            } else if (surfaceView != null) {
                player.setVideoSurfaceView(surfaceView);
            }
            player.setPlayWhenReady(autoplay);
            player.setRepeatMode(looping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
        }
        // 关键：监听器注册不能只在 player==null 分支里做。底层 ExoPlayer 在重建播放源时
        // 通常不会销毁，如果沿用第一次 prepare 注册的监听器，它捕获的 generation/requestId
        // 永远是旧的，新 prepare 的 READY 会被 PhotoViewer 当成旧回调拒绝。
        // 因此每次 prepare 都先移除旧监听器，再注册一个捕获当前身份的新监听器。
        // 已排队的旧回调仍携带旧身份，会被消费端拒绝，不会污染新请求。
        if (generationAwareListener != null) {
            player.removeListener(generationAwareListener);
        }
        generationAwareListener = createGenerationAwareListener(prepareGeneration, boundRequestId);
        player.addListener(generationAwareListener);
        if (mixedAudio) {
            if (audioPlayer == null) {
                audioPlayer = new ExoPlayer.Builder(ApplicationLoader.applicationContext)
                        .setRenderersFactory(new DefaultRenderersFactory(ApplicationLoader.applicationContext).setExtensionRendererMode(getPlayerExtensionRendererMode()))
                        .setTrackSelector(trackSelector)
                        .setLoadControl(loadControl).build();
                audioPlayer.addListener(new Player.Listener() {

                    /**
                     * 响应Player状态Changed。
                     * <p>该方法重写了父类/接口中的实现。</p>
                     * @param playWhenReady 布尔值类型的参数 playWhenReady
                     * @param playbackState 整数类型的参数 playbackState
                     */
                    @Override
                    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                        if (!audioPlayerReady && playbackState == Player.STATE_READY) {
                            audioPlayerReady = true;
                            checkPlayersReady();
                        }
                    }
                });
                audioPlayer.setPlayWhenReady(autoplay);
            }
        }
    }

    /**
     * 准备PlayerLoop。
     * @param videoUri Uri类型的参数 videoUri
     * @param videoType 字符串类型的参数 videoType
     * @param audioUri Uri类型的参数 audioUri
     * @param audioType 字符串类型的参数 audioType
     */
    public void preparePlayerLoop(Uri videoUri, String videoType, Uri audioUri, String audioType) {
        prepareGeneration++;
        diagLastPrepareAtMs = SystemClock.elapsedRealtime();
        this.videoQualities = null;
        this.videoQualityToSelect = null;
        this.videoUri = videoUri;
        this.audioUri = audioUri;
        this.videoType = videoType;
        this.audioType = audioType;
        this.loopingMediaSource = true;
        currentStreamIsHls = false;

        mixedAudio = true;
        audioPlayerReady = false;
        videoPlayerReady = false;
        ensurePlayerCreated();
        MediaSource mediaSource1 = null, mediaSource2 = null;
        for (int a = 0; a < 2; a++) {
            MediaSource mediaSource;
            String type;
            Uri uri;
            if (a == 0) {
                type = videoType;
                uri = videoUri;
            } else {
                type = audioType;
                uri = audioUri;
            }
            mediaSource = mediaSourceFromUri(uri, 0, type);
            mediaSource = new LoopingMediaSource(mediaSource);
            if (a == 0) {
                mediaSource1 = mediaSource;
            } else {
                mediaSource2 = mediaSource;
            }
        }
        player.setMediaSource(mediaSource1, true);
        player.prepare();
        audioPlayer.setMediaSource(mediaSource2, true);
        audioPlayer.prepare();
        activePlayers.add(playerId);
    }

    /**
     * mediaSourceFromUri。
     * @param videoUri VideoUri类型的参数 videoUri
     * @param type 类型（字符串）
     * @return MediaSource 类型的结果
     */
    private MediaSource mediaSourceFromUri(VideoUri videoUri, String type) {
        return mediaSourceFromUri(videoUri.uri, videoUri.fileVideoOffset, type);
    }

    /**
     * mediaSourceFromUri。
     * @param uri Uri类型的参数 uri
     * @param videoByteOffset 长整型类型的参数 videoByteOffset
     * @param type 类型（字符串）
     * @return MediaSource 类型的结果
     */
    private MediaSource mediaSourceFromUri(Uri uri, long videoByteOffset, String type) {
        final MediaItem mediaItem = new MediaItem.Builder().setUri(uri).build();
        if (videoByteOffset != 0) {
            return new ProgressiveMediaSource.Factory(
                () -> new OffsetDataSource(filteringDataSourceFactory.createDataSource(), videoByteOffset)
            ).createMediaSource(mediaItem);
        }
        switch (type) {
            case "dash":
                if (dashMediaSourceFactory == null) {
                    dashMediaSourceFactory = new DashMediaSource.Factory(filteringDataSourceFactory);
                }
                return dashMediaSourceFactory.createMediaSource(mediaItem);
            case "hls":
                if (hlsMediaSourceFactory == null) {
                    hlsMediaSourceFactory = new HlsMediaSource.Factory(filteringDataSourceFactory);
                }
                return hlsMediaSourceFactory.createMediaSource(mediaItem);
            case "ss":
                if (ssMediaSourceFactory == null) {
                    ssMediaSourceFactory = new SsMediaSource.Factory(filteringDataSourceFactory);
                }
                return ssMediaSourceFactory.createMediaSource(mediaItem);
            default:
                if (extractorsFactoryOverride != null) {
                    // 构建  fresh factory 用于 该 prepare; quality switches go through here too.
                    // Do 不 reuse progressiveMediaSourceFactory (它 holds  默认 extractors).
                    return new ProgressiveMediaSource.Factory(filteringDataSourceFactory, extractorsFactoryOverride).createMediaSource(mediaItem);
                }
                if (progressiveMediaSourceFactory == null) {
                    progressiveMediaSourceFactory = new ProgressiveMediaSource.Factory(filteringDataSourceFactory);
                }
                return progressiveMediaSourceFactory.createMediaSource(mediaItem);
        }
    }

    /**
     * 准备Player。
     * @param uri Uri类型的参数 uri
     * @param type 类型（字符串）
     */
    public void preparePlayer(Uri uri, String type) {
        preparePlayer(uri, type, FileLoader.PRIORITY_HIGH, 0);
    }

    /**
     * 准备Player。
     * @param uri Uri类型的参数 uri
     * @param type 类型（字符串）
     * @param priority 优先级（整数）
     * @param videoByteOffset 长整型类型的参数 videoByteOffset
     */
    public void preparePlayer(Uri uri, String type, int priority, long videoByteOffset) {
        prepareGeneration++;
        diagLastPrepareAtMs = SystemClock.elapsedRealtime();
        this.videoQualities = null;
        this.videoQualityToSelect = null;
        this.videoUri = uri;
        this.videoType = type;
        this.audioUri = null;
        this.audioType = null;
        this.loopingMediaSource = false;
        this.autoIsOriginal = false;
        this.currentStreamIsHls = false;

        videoPlayerReady = false;
        mixedAudio = false;
        currentUri = uri;
        String scheme = uri != null ? uri.getScheme() : null;
        isStreaming = scheme != null && !scheme.startsWith("file");
        ensurePlayerCreated();
        MediaSource mediaSource = mediaSourceFromUri(uri, videoByteOffset, type);
        player.setMediaSource(mediaSource, true);
        player.prepare();
    }

    /**
     * 准备Player。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @param select Quality类型的参数 select
     */
    public void preparePlayer(ArrayList<Quality> qualities, Quality select) {
        prepareGeneration++;
        diagLastPrepareAtMs = SystemClock.elapsedRealtime();
        this.videoQualities = qualities;
        this.videoQualityToSelect = select;
        this.videoUri = null;
        this.videoType = "hls";
        this.audioUri = null;
        this.audioType = null;
        this.loopingMediaSource = false;
        this.autoIsOriginal = false;

        videoPlayerReady = false;
        mixedAudio = false;
        currentUri = null;
        isStreaming = true;
        ensurePlayerCreated();

        currentStreamIsHls = false;
        selectedQualityIndex = select == null || videoQualities == null ? QUALITY_AUTO : videoQualities.indexOf(select);
        setSelectedQuality(true, select);
        if (autoIsOriginal) {
            selectedQualityIndex = QUALITY_AUTO;
        }
    }

    /**
     * 获取默认SavedQualityInt。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @param pL 整数类型的参数 pL
     * @param p 整数类型的参数 p
     * @return Quality 类型的结果
     */
    public static Quality getDefaultSavedQualityInt(ArrayList<Quality> qualities, int pL, int p) {
        for (Quality q : qualities) {
            if (!q.original && q.p() <= pL && q.p() >= p) return q;
        }
        return null;
    }

    /**
     * 获取默认SavedQuality。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @return Quality 类型的结果
     */
    public static Quality getDefaultSavedQuality(ArrayList<Quality> qualities) {
        int v = NaConfig.INSTANCE.getDefaultHlsVideoQuality().Int();
        Quality q1;
        switch (v) {
            case 0:
                return null;
            case 1:
                for (Quality q : qualities) {
                    if (q.original) return q;
                }
            case 2:
                q1 = getDefaultSavedQualityInt(qualities, Integer.MAX_VALUE, 1440);
                if (q1 != null) return q1;
            case 3:
                q1 = getDefaultSavedQualityInt(qualities, 1440, 1000);
                if (q1 != null) return q1;
            case 4:
                q1 = getDefaultSavedQualityInt(qualities, 1000, 700);
                if (q1 != null) return q1;
            case 5:
                q1 = getDefaultSavedQualityInt(qualities, 700, 0);
                if (q1 != null) return q1;
        }
        return null;
    }

    /**
     * 获取SavedQuality。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @param messageObject MessageObject类型的参数 messageObject
     * @return Quality 类型的结果
     */
    public static Quality getSavedQuality(ArrayList<Quality> qualities, MessageObject messageObject) {
        if (messageObject == null) return getDefaultSavedQuality(qualities);
        var q = getSavedQuality(qualities, messageObject.getDialogId(), messageObject.getId());
        if (q == null) {
            return getDefaultSavedQuality(qualities);
        }
        return q;
    }

    /**
     * 获取SavedQuality。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @param did 长整型类型的参数 did
     * @param mid 整数类型的参数 mid
     * @return Quality 类型的结果
     */
    public static Quality getSavedQuality(ArrayList<Quality> qualities, long did, int mid) {
        final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
        final String setting = preferences.getString(did + "_" + mid + "q2", "");
        if (TextUtils.isEmpty(setting)) return null;
        for (Quality q : qualities) {
            final String idx = q.width + "x" + q.height + (q.original ? "s" : "");
            if (TextUtils.equals(setting, idx)) return q;
        }
        return null;
    }

    /**
     * 保存Quality。
     * @param q Quality类型的参数 q
     * @param messageObject MessageObject类型的参数 messageObject
     */
    public static void saveQuality(Quality q, MessageObject messageObject) {
        if (messageObject == null) return;
        saveQuality(q, messageObject.getDialogId(), messageObject.getId());
    }

    /**
     * 保存Quality。
     * @param q Quality类型的参数 q
     * @param did 长整型类型的参数 did
     * @param mid 整数类型的参数 mid
     */
    public static void saveQuality(Quality q, long did, int mid) {
        final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
        final SharedPreferences.Editor editor = preferences.edit();
        if (q == null) {
            editor.remove(did + "_" + mid + "q2");
        } else {
            editor.putString(did + "_" + mid + "q2", q.width + "x" + q.height + (q.original ? "s" : ""));
        }
        editor.apply();
    }

    /**
     * 保存Looping。
     * @param looping 布尔值类型的参数 looping
     * @param messageObject MessageObject类型的参数 messageObject
     */
    public static void saveLooping(boolean looping, MessageObject messageObject) {
        if (messageObject == null) return;
        final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
        final String key = messageObject.getDialogId() + "_" + messageObject.getId() + "loop";
        preferences.edit().putBoolean(key, looping).apply();
    }

    /**
     * 获取Looping。
     * @param messageObject MessageObject类型的参数 messageObject
     * @return Boolean 类型的结果
     */
    public static Boolean getLooping(MessageObject messageObject) {
        if (messageObject == null) return null;
        final SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
        final String key = messageObject.getDialogId() + "_" + messageObject.getId() + "loop";
        if (!preferences.contains(key)) return null;
        return preferences.getBoolean(key, false);
    }

    public static final int QUALITY_AUTO = -1; // HLS
    private boolean autoIsOriginal = false;
    private int selectedQualityIndex = QUALITY_AUTO;
    private boolean currentStreamIsHls;

    /**
     * 获取Quality。
     * @param index 索引（整数）
     * @return Quality 类型的结果
     */
    public Quality getQuality(int index) {
        if (videoQualities == null) return getHighestQuality(false);
        if (index < 0 || index >= videoQualities.size()) return getHighestQuality(false);
        return videoQualities.get(index);
    }

    /**
     * 获取OriginalQuality。
     * @return Quality 类型的结果
     */
    public Quality getOriginalQuality() {
        for (int i = 0; i < getQualitiesCount(); ++i) {
            final Quality q = getQuality(i);
            if (q.original) return q;
        }
        return null;
    }

    /**
     * 获取HighestQuality。
     * @param original Boolean类型的参数 original
     * @return Quality 类型的结果
     */
    public Quality getHighestQuality(Boolean original) {
        Quality max = null;
        for (int i = 0; i < getQualitiesCount(); ++i) {
            final Quality q = getQuality(i);
            if (original != null && q.original != original) continue;
            if (max == null || max.width * max.height < q.width * q.height) {
                max = q;
            }
        }
        return max;
    }

    /**
     * 获取HighestQuality索引。
     * @param original Boolean类型的参数 original
     * @return 整型结果值
     */
    public int getHighestQualityIndex(Boolean original) {
        int maxIndex = -1;
        Quality max = null;
        for (int i = 0; i < getQualitiesCount(); ++i) {
            final Quality q = getQuality(i);
            if (original != null && q.original != original) continue;
            if (max == null || max.width * max.height < q.width * q.height) {
                max = q;
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    /**
     * 获取LowestQuality。
     * @return Quality 类型的结果
     */
    public Quality getLowestQuality() {
        Quality min = null;
        for (int i = 0; i < getQualitiesCount(); ++i) {
            final Quality q = getQuality(i);
            if (min == null || min.width * min.height > q.width * q.height) {
                min = q;
            }
        }
        return min;
    }

    /**
     * 获取Qualities数量。
     * @return 整型结果值
     */
    public int getQualitiesCount() {
        if (videoQualities == null) return 0;
        return videoQualities.size();
    }

    /**
     * 获取文件。
     * @return File 类型的结果
     */
    public File getFile() {
        if (videoQualities != null) {
            for (Quality q : videoQualities) {
                for (VideoUri v : q.uris) {
                    if (v.isCached())
                        return new File(v.uri.getPath());
                }
            }
        }
        if (videoUri != null) {
            if ("file".equalsIgnoreCase(videoUri.getScheme()))
                return new File(videoUri.getPath());
        }
        return null;
    }

    /**
     * 获取Lowest文件。
     * @return File 类型的结果
     */
    public File getLowestFile() {
        if (videoQualities != null) {
            for (int i = videoQualities.size() - 1; i >= 0; --i) {
                Quality q = videoQualities.get(i);
                for (VideoUri v : q.uris) {
                    if (!v.isCached())
                        v.updateCached(true);
                    if (v.isCached())
                        return new File(v.uri.getPath());
                }
            }
        }
        if (videoUri != null) {
            if ("file".equalsIgnoreCase(videoUri.getScheme()))
                return new File(videoUri.getPath());

        }
        return null;
    }

    /**
     * 获取SelectedQuality。
     * @return 整型结果值
     */
    public int getSelectedQuality() {
        return selectedQualityIndex;
    }

    /**
     * 获取当前Document。
     * @return TLRPC.Document 类型的结果
     */
    public TLRPC.Document getCurrentDocument() {
        if (player == null || videoQualities == null) return null;
        final int index = getCurrentQualityIndex();
        if (index < 0 || index >= videoQualities.size()) return null;
        final Quality quality = videoQualities.get(index);
        if (quality == null || quality.uris.isEmpty()) return null;
        return quality.uris.get(0).document;
    }

    /**
     * 获取当前Quality索引。
     * @return 整型结果值
     */
    public int getCurrentQualityIndex() {
        if (selectedQualityIndex == QUALITY_AUTO) {
            try {
                if (autoIsOriginal) {
                    for (int j = 0; j < getQualitiesCount(); ++j) {
                        final Quality q = getQuality(j);
                        if (q.original) {
                            return j;
                        }
                    }
                }

                if (player == null) return -1;
                final Format format = getVideoFormat();
                if (format == null) return -1;
                for (int j = 0; j < getQualitiesCount(); ++j) {
                    final Quality q = getQuality(j);
                    if (!q.original && format.width == q.width && format.height == q.height && format.bitrate == (int) Math.floor(q.uris.get(0).bitrate * 8)) {
                        return j;
                    }
                }

//                final MappingTrackSelector.MappedTrackInfo mapTrackInfo = trackSelector.getCurrentMappedTrackInfo();
//                用于 (int renderIndex = 0; renderIndex < mapTrackInfo.getRendererCount(); ++renderIndex) {
//                    final TrackGroupArray trackGroups = mapTrackInfo.getTrackGroups(renderIndex);
//                    用于 (int groupIndex = 0; groupIndex < trackGroups.长度; ++groupIndex) {
//                        final TrackGroup trackGroup = trackGroups.获取(groupIndex);
//                        用于 (int trackIndex = 0; trackIndex < trackGroup.长度; ++trackIndex) {
//                            final 格式化 格式化 = trackGroup.getFormat(trackIndex);
//                            int formatIndex;
//                            try {
//                                formatIndex = Integer.parseInt(格式化.id);
//                            } catch (Exception e) {
//                                formatIndex = -1;
//                            }
//                            如果 (formatIndex >= 0) {
//                                int formatOrder = 0;
//                                用于 (int j = 0; j < getQualitiesCount(); ++j) {
//                                    final Quality q = getQuality(j);
//                                    用于 (int i = 0; i < q.uris.大小(); ++i){
//                                        如果 (q.uris.获取(i).m3u8uri != 空值) {
//                                            如果 (formatOrder == formatIndex) {
//                                                返回 j;
//                                            }
//                                            formatOrder++;
//                                        }
//                                    }
//                                }
//                            }
//                            用于 (int j = 0; j < getQualitiesCount(); ++j) {
//                                final Quality q = getQuality(j);
//                                如果 (格式化.width == q.width && 格式化.height == q.height) {
//                                    返回 j;
//                                }
//                            }
//                        }
//                    }
//                }
            } catch (Exception e) {
                FileLog.e(e);
                return -1;
            }
        }
        return selectedQualityIndex;
    }

    /**
     * 获取QualityTrackSelection。
     * @param videoUri VideoUri类型的参数 videoUri
     * @return TrackSelectionOverride 类型的结果
     */
    private TrackSelectionOverride getQualityTrackSelection(VideoUri videoUri) {
        try {
            int qualityOrder = manifestUris.indexOf(videoUri);
            final MappingTrackSelector.MappedTrackInfo mapTrackInfo = trackSelector.getCurrentMappedTrackInfo();
            for (int renderIndex = 0; renderIndex < mapTrackInfo.getRendererCount(); ++renderIndex) {
                final TrackGroupArray trackGroups = mapTrackInfo.getTrackGroups(renderIndex);
                for (int groupIndex = 0; groupIndex < trackGroups.length; ++groupIndex) {
                    final TrackGroup trackGroup = trackGroups.get(groupIndex);
                    for (int trackIndex = 0; trackIndex < trackGroup.length; ++trackIndex) {
                        final Format format = trackGroup.getFormat(trackIndex);

                        int formatIndex;
                        try {
                            formatIndex = Integer.parseInt(format.id);
                        } catch (Exception e) {
                            formatIndex = -1;
                        }
                        if (formatIndex >= 0) {
                            if (qualityOrder == formatIndex) {
                                return new TrackSelectionOverride(trackGroup, trackIndex);
                            }
                        }
                        if (format.width == videoUri.width && format.height == videoUri.height) {
                            return new TrackSelectionOverride(trackGroup, trackIndex);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    /**
     * 响应TrackSelectionParametersChanged。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param parameters TrackSelectionParameters类型的参数 parameters
     */
    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        Player.Listener.super.onTrackSelectionParametersChanged(parameters);
        if (onQualityChangeListener != null) {
            AndroidUtilities.runOnUIThread(onQualityChangeListener);
        }
    }

    private long fallbackDuration = C.TIME_UNSET;
    private long fallbackPosition = C.TIME_UNSET;

    /**
     * 设置SelectedQuality。
     * @param index 索引（整数）
     */
    public void setSelectedQuality(int index) {
        if (player == null) return;
        if (index != selectedQualityIndex) {
            selectedQualityIndex = index;
            Quality q = null;
            if (videoQualities != null && index >= 0 && index < videoQualities.size())  q = videoQualities.get(index);
            setSelectedQuality(false, q);
        }
    }

    /**
     * 设置SelectedQuality。
     * @param start 启动（布尔值）
     * @param quality Quality类型的参数 quality
     */
    private void setSelectedQuality(boolean start, Quality quality) {
        if (player == null) return;

        final boolean lastPlaying = player.isPlaying();
        final long lastPosition = player.getCurrentPosition();
        if (!start) {
            fallbackPosition = lastPosition;
            fallbackDuration = player.getDuration();
        }

        boolean reset = false;

        videoQualityToSelect = quality;
        if (quality == null) { // AUTO
            final Uri hlsManifest = makeManifest(videoQualities);
            final Quality original = getOriginalQuality();
            if (original != null && original.uris.size() == 1 && original.uris.get(0).isCached()) {
                currentStreamIsHls = false;
                autoIsOriginal = true;
                quality = original;
                videoQualityToSelect = quality;
                player.setMediaSource(mediaSourceFromUri(quality.getDownloadUri(), "other"), false);
                reset = true;
            } else if (hlsManifest != null) {
                autoIsOriginal = false;
                trackSelector.setParameters(trackSelector.getParameters().buildUpon().clearOverrides().build());
                if (!currentStreamIsHls) {
                    currentStreamIsHls = true;
                    player.setMediaSource(mediaSourceFromUri(hlsManifest, 0, "hls"), false);
                    reset = true;
                }
            } else {
                quality = getHighestQuality(true);
                if (quality == null) quality = getHighestQuality(false);
                if (quality == null || quality.uris.isEmpty()) return;
                currentStreamIsHls = false;
                videoQualityToSelect = quality;
                autoIsOriginal = quality.original;
                player.setMediaSource(mediaSourceFromUri(quality.getDownloadUri(), "other"), false);
                reset = true;
            }
        } else {
            autoIsOriginal = false;
            if (quality.uris.isEmpty()) return;
            Uri hlsManifest = null;
            if (quality.uris.size() > 1) {
                hlsManifest = makeManifest(videoQualities);
            }
            if (hlsManifest == null || quality.uris.size() == 1 || trackSelector.getCurrentMappedTrackInfo() == null) {
                currentStreamIsHls = false;
                player.setMediaSource(mediaSourceFromUri(quality.getDownloadUri(), "other"), false);
                reset = true;
            } else {
                if (!currentStreamIsHls) {
                    currentStreamIsHls = true;
                    player.setMediaSource(mediaSourceFromUri(hlsManifest, 0, "hls"), false);
                    reset = true;
                }
                TrackSelectionParameters.Builder selector = trackSelector.getParameters().buildUpon().clearOverrides();
                for (VideoUri uri : quality.uris) {
                    TrackSelectionOverride override = getQualityTrackSelection(uri);
                    if (override == null) continue;
                    selector.addOverride(override);
                }
                trackSelector.setParameters(selector.build());
            }
        }

        if (reset) {
            player.prepare();
            if (!start) {
                player.seekTo(lastPosition);
                if (lastPlaying) {
                    player.play();
                }
            }
            if (onQualityChangeListener != null) {
                AndroidUtilities.runOnUIThread(onQualityChangeListener);
            }
            activePlayers.add(playerId);
        }
    }

    /**
     * 获取当前Quality。
     * @return Quality 类型的结果
     */
    public Quality getCurrentQuality() {
        final int index = getCurrentQualityIndex();
        if (index < 0 || index >= getQualitiesCount()) return null;
        return getQuality(index);
    }

    private Runnable onQualityChangeListener;
    /**
     * 设置响应QualityChange监听器。
     * @param listener 监听器（可运行任务）
     */
    public void setOnQualityChangeListener(Runnable listener) {
        this.onQualityChangeListener = listener;
    }

    /**
     * 获取Qualities。
     * @param currentAccount 整数类型的参数 currentAccount
     * @param original TLRPC.Document类型的参数 original
     * @param alt_documents ArrayList<TLRPC.Document>类型的参数 alt_documents
     * @param reference 整数类型的参数 reference
     * @param forThumb 布尔值类型的参数 forThumb
     * @return ArrayList<Quality> 类型的结果
     */
    public static ArrayList<Quality> getQualities(int currentAccount, TLRPC.Document original, ArrayList<TLRPC.Document> alt_documents, int reference, boolean forThumb) {
        return getQualities(currentAccount, original, alt_documents, reference, forThumb, true);
    }
    /**
     * 获取Qualities。
     * @param currentAccount 整数类型的参数 currentAccount
     * @param original TLRPC.Document类型的参数 original
     * @param alt_documents ArrayList<TLRPC.Document>类型的参数 alt_documents
     * @param reference 整数类型的参数 reference
     * @param forThumb 布尔值类型的参数 forThumb
     * @param useFileDatabaseQueue 布尔值类型的参数 useFileDatabaseQueue
     * @return ArrayList<Quality> 类型的结果
     */
    public static ArrayList<Quality> getQualities(int currentAccount, TLRPC.Document original, ArrayList<TLRPC.Document> alt_documents, int reference, boolean forThumb, boolean useFileDatabaseQueue) {
        ArrayList<TLRPC.Document> documents = new ArrayList<>();
        if (original != null) {
            documents.add(original);
        }
        if (!MessagesController.getInstance(currentAccount).videoIgnoreAltDocuments && alt_documents != null) {
            documents.addAll(alt_documents);
        }

        final LongSparseArray<TLRPC.Document> manifests = new LongSparseArray<>();
        for (int i = 0; i < documents.size(); ++i) {
            final TLRPC.Document document = documents.get(i);
            if ("application/x-mpegurl".equalsIgnoreCase(document.mime_type)) {
                if (document.file_name_fixed == null || !document.file_name_fixed.startsWith("mtproto")) continue;
                try {
                    long videoDocumentId = Long.parseLong(document.file_name_fixed.substring(7));
                    manifests.put(videoDocumentId, document);
                    documents.remove(i);
                    i--;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }

        ArrayList<VideoUri> result = new ArrayList<>();
        for (int i = 0; i < documents.size(); ++i) {
            try {
                final TLRPC.Document document = documents.get(i);
                if (
                    "application/x-mpegurl".equalsIgnoreCase(document.mime_type) ||
                    "application/x-tgstoryboard".equalsIgnoreCase(document.mime_type) ||
                    "application/x-tgstoryboardmap".equalsIgnoreCase(document.mime_type)
                ) {
                    continue;
                }
                VideoUri q = VideoUri.of(currentAccount, document, manifests.get(document.id), reference, useFileDatabaseQueue);
                if (q.width <= 0 || q.height <= 0) {
                    continue;
                }
                if (document == original) {
                    q.original = true;
                }
                result.add(q);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        ArrayList<VideoUri> filtered = new ArrayList<>();
        for (int i = 0; i < result.size(); ++i) {
            final VideoUri q = result.get(i);
            if (q.codec != null) {
                if (forThumb) {
                    if (!("avc".equals(q.codec) || "h264".equals(q.codec) || "vp9".equals(q.codec) || "vp8".equals(q.codec) || ("av1".equals(q.codec) || "av01".equals(q.codec)) && supportsHardwareDecoder(q.codec))) {
                        continue;
                    }
                } else {
                    if (("av1".equals(q.codec) || "av01".equals(q.codec) || "hevc".equals(q.codec) || "h265".equals(q.codec) || "vp9".equals(q.codec)) && !supportsHardwareDecoder(q.codec)) {
                        continue;
                    }
                }
            }
            filtered.add(q);
        }

        ArrayList<VideoUri> qualities = new ArrayList<>();
        if (filtered.isEmpty())
            qualities.addAll(result);
        else
            qualities.addAll(filtered);

        return Quality.group(qualities);
    }

    /**
     * 获取Qualities。
     * @param currentAccount 整数类型的参数 currentAccount
     * @param media TLRPC.MessageMedia类型的参数 media
     * @param useFileDatabaseQueue 布尔值类型的参数 useFileDatabaseQueue
     * @return ArrayList<Quality> 类型的结果
     */
    public static ArrayList<Quality> getQualities(int currentAccount, TLRPC.MessageMedia media, boolean useFileDatabaseQueue) {
        if (!(media instanceof TLRPC.TL_messageMediaDocument))
            return new ArrayList<>();
        return getQualities(currentAccount, media.document, media.alt_documents, 0, false, useFileDatabaseQueue);
    }

    /**
     * 判断是否包含Qualities。
     * @param currentAccount 整数类型的参数 currentAccount
     * @param media TLRPC.MessageMedia类型的参数 media
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public static boolean hasQualities(int currentAccount, TLRPC.MessageMedia media) {
        if (!(media instanceof TLRPC.TL_messageMediaDocument))
            return false;
        ArrayList<Quality> qualities = getQualities(currentAccount, media.document, media.alt_documents, 0, false);
        return qualities != null && qualities.size() > 1;
    }

    /**
     * 获取DocumentForThumb。
     * @param currentAccount 整数类型的参数 currentAccount
     * @param media TLRPC.MessageMedia类型的参数 media
     * @return TLRPC.Document 类型的结果
     */
    public static TLRPC.Document getDocumentForThumb(int currentAccount, TLRPC.MessageMedia media) {
        if (!(media instanceof TLRPC.TL_messageMediaDocument))
            return null;
        final VideoUri videoUri = getQualityForThumb(getQualities(currentAccount, media.document, media.alt_documents, 0, true));
        return videoUri == null ? null : videoUri.document;
    }

    /**
     * 获取QualityForThumb。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @return VideoUri 类型的结果
     */
    public static VideoUri getQualityForThumb(ArrayList<Quality> qualities) {
        for (final Quality q : qualities) {
            for (final VideoUri v : q.uris) {
                if (v.isCached())
                    return v;
            }
        }

        final int MAX_SIZE = 900;
        VideoUri uri = null;
        for (final Quality q : qualities) {
            for (final VideoUri v : q.uris) {
                if (!v.original && (uri == null || uri.width * uri.height > v.width * v.height || v.bitrate < uri.bitrate) && v.width <= MAX_SIZE && v.height <= MAX_SIZE) {
                    uri = v;
                }
            }
        }
        if (uri == null) {
            for (final Quality q : qualities) {
                for (final VideoUri v : q.uris) {
                    if ((uri == null || uri.width * uri.height > v.width * v.height || v.bitrate < uri.bitrate)) {
                        uri = v;
                    }
                }
            }
        }
        return uri;
    }

    /**
     * 获取CachedQuality。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @return VideoUri 类型的结果
     */
    public static VideoUri getCachedQuality(ArrayList<Quality> qualities) {
        if (qualities == null) return null;
        for (final Quality q : qualities)
        for (final VideoUri v : q.uris)
            if (v.isCached())
                return v;
        return null;
    }

    /**
     * 获取QualityForPlayer。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @return VideoUri 类型的结果
     */
    public static VideoUri getQualityForPlayer(ArrayList<Quality> qualities) {
        final Quality defaultQuality = getDefaultSavedQuality(qualities);
        if (defaultQuality != null) {
            return defaultQuality.getDownloadUri();
        }
        for (final Quality q : qualities) {
            for (final VideoUri v : q.uris) {
                if (v.original && v.isCached())
                    return v;
            }
        }

        VideoUri uri = null;
        if (uri == null) {
            for (final Quality q : qualities) {
                for (final VideoUri v : q.uris) {
                    if (!v.original && VideoPlayer.supportsHardwareDecoder(v.codec) && (uri == null || v.width * v.height > uri.width * uri.height || v.width * v.height == uri.width * uri.height && v.bitrate < uri.bitrate)) {
                        uri = v;
                    }
                }
            }
        }
        if (uri == null) {
            for (final Quality q : qualities) {
                for (final VideoUri v : q.uris) {
                    if (uri == null || uri.width * uri.height > v.width * v.height || v.bitrate < uri.bitrate) {
                        uri = v;
                    }
                }
            }
        }
        return uri;
    }

    /**
     * toMime。
     * @param codec 字符串类型的参数 codec
     * @return 字符串 类型的结果
     */
    public static String toMime(String codec) {
        if (codec == null) return null;
        switch (codec) {
            case "h264":
            case "avc": return "video/avc";
            case "vp8": return "video/x-vnd.on2.vp8";
            case "vp9": return "video/x-vnd.on2.vp9";
            case "h265":
            case "hevc": return "video/hevc";
            case "av1": case "av01": return "video/av01";
            default: return "video/" + codec;
        }
    }

    private static HashMap<String, Boolean> cachedSupportedCodec;

    /**
     * Mirrors  检查 Media3 使用 internally 在 {@代码 MediaCodecUtil}, which 是 private 和
     * therefore 不 accessible here. 在 API 29+  platform answer 是 authoritative; below 该
     * we fall back 到 Media3's 名称-based approximation.
     */
    /**
     * 判断是否为HardwareAccelerated。
     * @param codecInfo MediaCodecInfo类型的参数 codecInfo
     * @param mime 字符串类型的参数 mime
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    private static boolean isHardwareAccelerated(MediaCodecInfo codecInfo, String mime) {
        if (Build.VERSION.SDK_INT >= 29) {
            return codecInfo.isHardwareAccelerated();
        }
        final String name = codecInfo.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("arc.")) {
            return true;
        }
        return !(name.startsWith("omx.google.")
                || name.startsWith("omx.ffmpeg.")
                || (name.startsWith("omx.sec.") && name.contains(".sw."))
                || name.equals("omx.qcom.video.decoder.hevcswvdec")
                || name.startsWith("c2.android.")
                || name.startsWith("c2.google.")
                || (!name.startsWith("omx.") && !name.startsWith("c2.")));
    }

    /**
     * supportsHardwareDecoder。
     * @param codec 字符串类型的参数 codec
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public static boolean supportsHardwareDecoder(String codec) {
        try {
            final String mime = toMime(codec);
            if (mime == null) return false;
            if (cachedSupportedCodec == null) cachedSupportedCodec = new HashMap<>();
            Boolean cached = cachedSupportedCodec.get(mime);
            if (cached != null) return cached;
            if (MessagesController.getGlobalMainSettings().getBoolean("unsupport_" + mime, false)) {
                return false;
            }
            final int count = MediaCodecList.getCodecCount();
            for (int i = 0; i < count; i++) {
                final MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) continue;
                if (!isHardwareAccelerated(info, mime)) continue;
                final String[] supportedTypes = info.getSupportedTypes();
                for (int j = 0; j < supportedTypes.length; ++j) {
                    if (supportedTypes[j].equalsIgnoreCase(mime)) {
                        cachedSupportedCodec.put(mime, true);
                        return true;
                    }
                }
            }
            cachedSupportedCodec.put(mime, false);
            return false;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * makeManifest。
     * @param qualities ArrayList<Quality>类型的参数 qualities
     * @return Uri 类型的结果
     */
    public Uri makeManifest(ArrayList<Quality> qualities) {
        final StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:6\n");
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n\n");
        manifestUris = new ArrayList<>();
        boolean hasManifests = false;
        ArrayList<String> streams = new ArrayList<>();
        for (Quality q : qualities) {
            for (VideoUri v : q.uris) {
                mediaDataSourceFactory.putDocumentUri(v.docId, v.uri);
                mediaDataSourceFactory.putDocumentUri(v.manifestDocId, v.m3u8uri);
                if (v.m3u8uri != null) {
                    manifestUris.add(v);
                    final StringBuilder stream = new StringBuilder();
                    stream.append("#EXT-X-STREAM-INF:BANDWIDTH=").append((int) Math.floor(v.bitrate * 8)).append(",RESOLUTION=").append(v.width).append("x").append(v.height);
                    final String mime = toMime(v.codec);
                    if (mime != null) {
                        stream.append(",MIME=\"").append(mime).append("\"");
                    }
                    if (v.isCached() && v.isManifestCached()) {
                        stream.append(",CACHED=\"true\"");
                    }
                    stream.append(",DOCID=\"").append(v.docId).append("\"");
                    stream.append(",ACCOUNT=\"").append(v.currentAccount).append("\"");
                    stream.append("\n");
                    if (v.isManifestCached()) {
                        stream.append(v.m3u8uri).append("\n\n");
                    } else {
                        stream.append("mtproto:").append(v.manifestDocId).append("\n\n");
                    }
                    hasManifests = true;
                    streams.add(stream.toString());
                }
            }
        }
        if (!hasManifests) return null;
        Collections.reverse(streams);
        sb.append(TextUtils.join("", streams));
        final String base64 = Base64.encodeToString(sb.toString().getBytes(), Base64.NO_WRAP);
        return Uri.parse("data:application/x-mpegurl;base64," + base64);
    }

    /**
     * 类：Quality，封装了相关的业务逻辑与数据处理。
     */
    public static class Quality {

        public boolean original;
        public int width, height;
        public final ArrayList<VideoUri> uris = new ArrayList<>();

        /**
         * 构造方法：创建 Quality 实例并初始化其内部状态。
         * @param uri VideoUri类型的参数 uri
         */
        public Quality(VideoUri uri) {
            original = uri.original;
            width = uri.width;
            height = uri.height;
            uris.add(uri);
        }

        /**
         * 分组。
         * @param uris ArrayList<VideoUri>类型的参数 uris
         * @return ArrayList<Quality> 类型的结果
         */
        public static ArrayList<Quality> group(ArrayList<VideoUri> uris) {
            final ArrayList<Quality> qualities = new ArrayList<>();

            for (VideoUri uri : uris) {
                if (uri.original) {
                    qualities.add(new Quality(uri));
                    continue;
                }

                Quality q = null;
                for (Quality _q : qualities) {
                    if (!_q.original && _q.width == uri.width && _q.height == uri.height) {
                        q = _q;
                        break;
                    }
                }

                if (q != null && !SharedConfig.debugVideoQualities) {
                    q.uris.add(uri);
                } else {
                    qualities.add(new Quality(uri));
                }
            }

//            如果 (BuildVars.LOGS_ENABLED) {
//                用于 (Quality q : qualities) {
//                    FileLog.d("debug_loading_player: Quality "+q.p()+"p (" + q.width + "x" + q.height + ")" + (q.original ? " (source)" : "") + ":");
//                    用于 (VideoUri uri : q.uris) {
//                        FileLog.d("debug_loading_player: - 视频 " + uri.width + "x" + uri.height + ", codec=" + uri.codec + ", bitrate=" + (int) (uri.bitrate*8) + ", doc#" + uri.docId + (uri.isCached() ? " (cached)" : "") + ", manifest#" + uri.manifestDocId + (uri.isManifestCached() ? " (cached)" : ""));
//                    }
//                }
//                FileLog.d("debug_loading_player: ");
//            }

            return qualities;
        }

        /**
         * 过滤ByCodec。
         * @param qualities ArrayList<Quality>类型的参数 qualities
         * @return ArrayList<Quality> 类型的结果
         */
        public static ArrayList<Quality> filterByCodec(ArrayList<Quality> qualities) {
            if (qualities == null) return null;
            for (int i = 0; i < qualities.size(); ++i) {
                Quality q = qualities.get(i);
                for (int j = 0; j < q.uris.size(); ++j) {
                    VideoUri u = q.uris.get(j);
                    if (!TextUtils.isEmpty(u.codec) && !supportsHardwareDecoder(u.codec)) {
                        q.uris.remove(j);
                        j--;
                    }
                }
                if (q.uris.isEmpty()) {
                    qualities.remove(i);
                    i--;
                }
            }
            return qualities;
        }

        /**
         * toString。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @return 字符串 类型的结果
         */
        @NonNull
        @Override
        public String toString() {
            if (SharedConfig.debugVideoQualities) {
                return width + "x" + height +
                    (original ? " (" + getString(R.string.QualitySource) + ")" : "") + "\n" +
                    AndroidUtilities.formatFileSize((long) uris.get(0).bitrate).replace(" ", "") + "/s" +
                    (uris.get(0).codec != null ? ", " + uris.get(0).codec : "");
            } else {
                return p() + "p" + (original ? " (" + getString(R.string.QualitySource) + ")" : "");
            }
        }

        /**
         * p。
         * @return 整型结果值
         */
        public int p() {
            int p = Math.min(width, height);
            if      (Math.abs(p - 2160) < 55) p = 2160;
            else if (Math.abs(p - 1440) < 55) p = 1440;
            else if (Math.abs(p - 1080) < 55) p = 1080;
            else if (Math.abs(p - 720) <  55) p = 720;
            else if (Math.abs(p - 480) <  55) p = 480;
            else if (Math.abs(p - 360) <  55) p = 360;
            else if (Math.abs(p - 240) <  55) p = 240;
            else if (Math.abs(p - 144) <  55) p = 144;
            return p;
        }

        /**
         * 获取下载Document。
         * @return TLRPC.Document 类型的结果
         */
        public TLRPC.Document getDownloadDocument() {
            if (uris.isEmpty()) return null;
            for (VideoUri uri : uris) {
                if (uri.isCached())
                    return uri.document;
            }
            long min_size = Long.MAX_VALUE;
            VideoUri selected_uri = null;
            for (int i = 0; i < uris.size(); ++i) {
                VideoUri uri = uris.get(i);
                if (uri.size < min_size && supportsHardwareDecoder(uri.codec)) {
                    min_size = uri.size;
                    selected_uri = uri;
                }
            }
            if (selected_uri != null) {
                return selected_uri.document;
            }
            return uris.get(0).document;
        }

        /**
         * 获取下载Uri。
         * @return VideoUri 类型的结果
         */
        public VideoUri getDownloadUri() {
            if (uris.isEmpty()) return null;
            for (VideoUri uri : uris) {
                if (uri.isCached())
                    return uri;
            }
            long min_size = Long.MAX_VALUE;
            VideoUri selected_uri = null;
            for (int i = 0; i < uris.size(); ++i) {
                VideoUri uri = uris.get(i);
                if (uri.size < min_size && supportsHardwareDecoder(uri.codec)) {
                    min_size = uri.size;
                    selected_uri = uri;
                }
            }
            if (selected_uri != null) {
                return selected_uri;
            }
            return uris.get(0);
        }
    }

    /**
     * 类：视频Uri，封装了相关的业务逻辑与数据处理。
     */
    public static class VideoUri {

        public int currentAccount;
        public boolean original;
        public long docId;
        public Uri uri;
        public long manifestDocId;
        public Uri m3u8uri;
        public long fileVideoOffset;

        public TLRPC.Document document;
        public TLRPC.Document manifestDocument;

        /**
         * 判断是否为Cached。
         * @return 布尔结果，true 表示条件成立或操作成功
         */
        public boolean isCached() {
            return uri != null && "file".equalsIgnoreCase(uri.getScheme());
        }

        /**
         * 判断是否为ManifestCached。
         * @return 布尔结果，true 表示条件成立或操作成功
         */
        public boolean isManifestCached() {
            return m3u8uri != null && "file".equalsIgnoreCase(m3u8uri.getScheme());
        }

        /**
         * 更新Cached。
         * @param useFileDatabaseQueue 布尔值类型的参数 useFileDatabaseQueue
         */
        public void updateCached(boolean useFileDatabaseQueue) {
            if (!isCached() && document != null) {
                File file = FileLoader.getInstance(currentAccount).getPathToAttach(document, null, false, useFileDatabaseQueue);
                if (file != null && file.exists()) {
                    this.uri = Uri.fromFile(file);
                } else {
                    file = FileLoader.getInstance(currentAccount).getPathToAttach(document, null, true, useFileDatabaseQueue);
                    if (file != null && file.exists()) {
                        this.uri = Uri.fromFile(file);
                    }
                }
            }
            if (!isManifestCached() && manifestDocument != null) {
                File file = FileLoader.getInstance(currentAccount).getPathToAttach(manifestDocument, null, false, useFileDatabaseQueue);
                if (file != null && file.exists()) {
                    this.m3u8uri = Uri.fromFile(file);
                } else {
                    file = FileLoader.getInstance(currentAccount).getPathToAttach(manifestDocument, null, true, useFileDatabaseQueue);
                    if (file != null && file.exists()) {
                        this.m3u8uri = Uri.fromFile(file);
                    }
                }
            }
        }

        public int width, height;
        public double duration;
        public long size;
        public double bitrate;

        public String codec;
        public MediaItem mediaItem;

        /**
         * 获取Uri。
         * @param currentAccount 整数类型的参数 currentAccount
         * @param document TLRPC.Document类型的参数 document
         * @param reference 整数类型的参数 reference
         * @return Uri 类型的结果
         */
        public static Uri getUri(int currentAccount, TLRPC.Document document, int reference) throws UnsupportedEncodingException {
            final String params =
                "?account=" + currentAccount +
                "&id=" + document.id +
                "&hash=" + document.access_hash +
                "&dc=" + document.dc_id +
                "&size=" + document.size +
                "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                "&rid=" + reference +
                "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
            return Uri.parse("tg://" + MessageObject.getFileName(document) + params);
        }

        /**
         * of。
         * @param currentAccount 整数类型的参数 currentAccount
         * @param document TLRPC.Document类型的参数 document
         * @param manifest TLRPC.Document类型的参数 manifest
         * @param reference 整数类型的参数 reference
         * @param useFileDatabaseQueue 布尔值类型的参数 useFileDatabaseQueue
         * @return VideoUri 类型的结果
         */
        public static VideoUri of(int currentAccount, TLRPC.Document document, TLRPC.Document manifest, int reference, boolean useFileDatabaseQueue) throws UnsupportedEncodingException {
            final VideoUri videoUri = new VideoUri();
            TLRPC.TL_documentAttributeVideo attributeVideo = null;
            for (int i = 0; i < document.attributes.size(); ++i) {
                final TLRPC.DocumentAttribute attribute = document.attributes.get(i);
                if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    attributeVideo = (TLRPC.TL_documentAttributeVideo) attribute;
                    break;
                }
            }
            final String codec = attributeVideo == null || attributeVideo.video_codec == null ? null : attributeVideo.video_codec.toLowerCase();

            videoUri.currentAccount = currentAccount;
            videoUri.document = document;
            videoUri.docId = document.id;
            videoUri.uri = getUri(currentAccount, document, reference);
            if (manifest != null) {
                videoUri.manifestDocument = manifest;
                videoUri.manifestDocId = manifest.id;
                videoUri.m3u8uri = getUri(currentAccount, manifest, reference);
                File file = FileLoader.getInstance(currentAccount).getPathToAttach(manifest, null, false, useFileDatabaseQueue);
                if (file != null && file.exists()) {
                    videoUri.m3u8uri = Uri.fromFile(file);
                } else {
                    file = FileLoader.getInstance(currentAccount).getPathToAttach(manifest, null, true, useFileDatabaseQueue);
                    if (file != null && file.exists()) {
                        videoUri.m3u8uri = Uri.fromFile(file);
                    }
                }
            }

            videoUri.codec = codec;
            videoUri.size = document.size;
            if (attributeVideo != null) {
                videoUri.duration = attributeVideo.duration;
                videoUri.width = attributeVideo.w;
                videoUri.height = attributeVideo.h;

                videoUri.bitrate = videoUri.size / videoUri.duration;
            }

            File file = FileLoader.getInstance(currentAccount).getPathToAttach(document, null, false, useFileDatabaseQueue);
            if (file != null && file.exists()) {
                videoUri.uri = Uri.fromFile(file);
            } else {
                file = FileLoader.getInstance(currentAccount).getPathToAttach(document, null, true, useFileDatabaseQueue);
                if (file != null && file.exists()) {
                    videoUri.uri = Uri.fromFile(file);
                }
            }

            return videoUri;
        }

        /**
         * 获取Media条目。
         * @return MediaItem 类型的结果
         */
        public MediaItem getMediaItem() {
            if (mediaItem == null) {
                mediaItem = new MediaItem.Builder().setUri(uri).build();
            }
            return mediaItem;
        }

    }

    /**
     * 判断是否为PlayerPrepared。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isPlayerPrepared() {
        return player != null;
    }

    /**
     * 释放Player。
     * @param async 布尔值类型的参数 async
     */
    public void releasePlayer(boolean async) {
        activePlayers.remove(playerId);
        recoveryPlayerRef = null;
        recoveryPositionMs = C.TIME_UNSET;
        recoveryGeneration = -1;
        if (player != null) {
            player.release();
            player = null;
        }
        if (audioPlayer != null) {
            audioPlayer.release();
            audioPlayer = null;
        }
        if (textureView != null && textureView.getSurfaceTextureListener() == surfaceTextureListener) {
            textureView.setSurfaceTextureListener(null);
        }
        releaseOwnedTextureSurface();
        if (shouldPauseOther) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.playerDidStartPlaying);
        }
        playerCounter--;
    }

    /**
     * 响应定位到Started。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param eventTime EventTime类型的参数 eventTime
     */
    @Override
    public void onSeekStarted(EventTime eventTime) {
        if (delegate != null) {
            delegate.onSeekStarted(eventTime);
        }
    }

    private final ArrayList<Runnable> seekFinishedListeners = new ArrayList<>();

    /**
     * 通知定位到Finished。
     */
    private void notifySeekFinished() {
        if (delegate != null) {
            delegate.onSeekFinished();
        }
        for (Runnable r : seekFinishedListeners) {
            r.run();
        }
        seekFinishedListeners.clear();
    }

    /**
     * 响应Rendered首个Frame。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param eventTime EventTime类型的参数 eventTime
     * @param output 输出（对象）
     * @param renderTimeMs 长整型类型的参数 renderTimeMs
     */
    @Override
    public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
        fallbackPosition = C.TIME_UNSET;
        fallbackDuration = C.TIME_UNSET;
        if (delegate != null) {
            delegate.onRenderedFirstFrame(eventTime);
        }
    }

    /**
     * 设置Texture视图。
     * @param texture TextureView类型的参数 texture
     */
    public void setTextureView(TextureView texture) {
        if (textureView == texture) {
            return;
        }
        if (textureView != null && textureView.getSurfaceTextureListener() == surfaceTextureListener) {
            textureView.setSurfaceTextureListener(null);
        }
        textureView = texture;
        if (texture != null) {
            surfaceView = null;
        }
        if (player == null) {
            return;
        }
        if (texture == null) {
            player.clearVideoSurface();
            releaseOwnedTextureSurface();
        } else {
            attachTextureView(texture);
        }
    }

    /**
     * 设置Surface视图。
     * @param surfaceView SurfaceView类型的参数 surfaceView
     */
    public void setSurfaceView(SurfaceView surfaceView) {
        if (this.surfaceView == surfaceView) {
            return;
        }
        if (textureView != null && textureView.getSurfaceTextureListener() == surfaceTextureListener) {
            textureView.setSurfaceTextureListener(null);
        }
        textureView = null;
        this.surfaceView = surfaceView;
        if (player == null) {
            return;
        }
        player.setVideoSurfaceView(surfaceView);
        if (surfaceView != null) {
            releaseOwnedTextureSurface();
        }
    }

    /**
     * 设置Surface。
     * @param s Surface类型的参数 s
     */
    public void setSurface(Surface s) {
        if (surface == s) {
            return;
        }
        surface = s;
        if (player == null) {
            return;
        }
        player.setVideoSurface(s);
        releaseOwnedTextureSurface();
    }

    /**
     * 获取播放WhenReady。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean getPlayWhenReady() {
        return player.getPlayWhenReady();
    }

    /**
     * 获取Playback状态。
     * @return 整型结果值
     */
    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    /**
     * 获取当前Uri。
     * @return Uri 类型的结果
     */
    public Uri getCurrentUri() {
        return currentUri;
    }

    /**
     * 播放。
     */
    public void play() {
        mixedPlayWhenReady = true;
        if (mixedAudio) {
            if (!audioPlayerReady || !videoPlayerReady) {
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
                if (audioPlayer != null) {
                    audioPlayer.setPlayWhenReady(false);
                }
                return;
            }
        }
        if (player != null) {
            player.setPlayWhenReady(true);
        }
        if (audioPlayer != null) {
            audioPlayer.setPlayWhenReady(true);
        }
    }

    /**
     * 暂停。
     */
    public void pause() {
        mixedPlayWhenReady = false;
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        if (audioPlayer != null) {
            audioPlayer.setPlayWhenReady(false);
        }

        if (audioVisualizerDelegate != null) {
            audioUpdateHandler.removeCallbacksAndMessages(null);
            audioVisualizerDelegate.onVisualizerUpdate(false, true, null);
        }
    }

    /**
     * 设置Playback速度。
     * @param speed 速度（浮点值）
     */
    public void setPlaybackSpeed(float speed) {
        try {
            if (player != null) {
                player.setPlaybackParameters(new PlaybackParameters(speed, speed > 1.0f ? 0.98f : 1.0f));
            }
        } catch (Exception ignore) {}
    }

    /**
     * 获取Playback速度。
     * @return 浮点结果值
     */
    public float getPlaybackSpeed() {
        if (player == null) return 1.0f;
        final PlaybackParameters params = player.getPlaybackParameters();
        if (params == null) return 1.0f;
        return params.speed;
    }

    /**
     * 设置播放WhenReady。
     * @param playWhenReady 布尔值类型的参数 playWhenReady
     */
    public void setPlayWhenReady(boolean playWhenReady) {
        mixedPlayWhenReady = playWhenReady;
        if (playWhenReady && mixedAudio) {
            if (!audioPlayerReady || !videoPlayerReady) {
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
                if (audioPlayer != null) {
                    audioPlayer.setPlayWhenReady(false);
                }
                return;
            }
        }
        autoplay = playWhenReady;
        if (player != null) {
            player.setPlayWhenReady(playWhenReady);
        }
        if (audioPlayer != null) {
            audioPlayer.setPlayWhenReady(playWhenReady);
        }
    }

    /**
     * 获取时长。
     * @return 整型结果值
     */
    public long getDuration() {
        if (fallbackDuration != C.TIME_UNSET) {
            return fallbackDuration;
        }
        return player != null ? player.getDuration() : 0;
    }

    /**
     * 获取当前位置。
     * @return 整型结果值
     */
    public long getCurrentPosition() {
        if (fallbackPosition != C.TIME_UNSET) {
            return fallbackPosition;
        }
        return player != null ? player.getCurrentPosition() : 0;
    }

    /**
     * 判断是否为Muted。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isMuted() {
        return player != null && player.getVolume() == 0.0f;
    }

    /**
     * 设置静音。
     * @param value 值（布尔值）
     */
    public void setMute(boolean value) {
        if (player != null) {
            player.setVolume(value ? 0.0f : 1.0f);
        }
        if (audioPlayer != null) {
            audioPlayer.setVolume(value ? 0.0f : 1.0f);
        }
    }

    /**
     * 响应Repeat模式Changed。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param repeatMode 整数类型的参数 repeatMode
     */
    @Override
    public void onRepeatModeChanged(int repeatMode) {

    }

    /**
     * 响应Surface尺寸Changed。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param width 宽度（整数）
     * @param height 高度（整数）
     */
    @Override
    public void onSurfaceSizeChanged(int width, int height) {

    }

    /**
     * 设置Volume。
     * @param volume 浮点值类型的参数 volume
     */
    public void setVolume(float volume) {
        if (player != null) {
            player.setVolume(volume);
        }
        if (audioPlayer != null) {
            audioPlayer.setVolume(volume);
        }
    }

    /**
     * 获取Volume。
     * @return 浮点结果值
     */
    public float getVolume() {
        if (player != null) {
            return player.getVolume();
        }
        return 1.0f;
    }

    /**
     * 定位到To。
     * @param positionMs 长整型类型的参数 positionMs
     */
    public void seekTo(long positionMs) {
        seekTo(positionMs, false);
    }

    /**
     * 定位到To。
     * @param positionMs 长整型类型的参数 positionMs
     * @param fast 布尔值类型的参数 fast
     */
    public void seekTo(long positionMs, boolean fast) {
        //  negative target 是 clamped 到 0 由 ExoPlayer, which restarts 
        // streamed fragmented 文件 从  first frame. Drop 它 instead.
        if (positionMs < 0) {
            return;
        }
        if (player != null) {
            player.setSeekParameters(fast ? SeekParameters.CLOSEST_SYNC : SeekParameters.EXACT);
            player.seekTo(positionMs);
        }
    }

    /**
     * 定位到To。
     * @param positionMs 长整型类型的参数 positionMs
     * @param fast 布尔值类型的参数 fast
     * @param whenDone 可运行任务类型的参数 whenDone
     */
    public void seekTo(long positionMs, boolean fast, Runnable whenDone) {
        if (positionMs < 0) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        if (player != null) {
            if (whenDone != null) {
                seekFinishedListeners.add(whenDone);
            }
            player.setSeekParameters(fast ? SeekParameters.CLOSEST_SYNC : SeekParameters.EXACT);
            player.seekTo(positionMs);
        }
    }

    /**
     * 定位到ToBack。
     * @param positionMs 长整型类型的参数 positionMs
     * @param fast 布尔值类型的参数 fast
     * @param whenDone 可运行任务类型的参数 whenDone
     */
    public void seekToBack(long positionMs, boolean fast, Runnable whenDone) {
        if (positionMs < 0) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        if (player != null) {
            if (whenDone != null) {
                seekFinishedListeners.add(whenDone);
            }
            player.setSeekParameters(fast ? SeekParameters.PREVIOUS_SYNC : SeekParameters.EXACT);
            player.seekTo(positionMs);
        }
    }

    /**
     * 定位到To转发。
     * @param positionMs 长整型类型的参数 positionMs
     * @param fast 布尔值类型的参数 fast
     * @param whenDone 可运行任务类型的参数 whenDone
     */
    public void seekToForward(long positionMs, boolean fast, Runnable whenDone) {
        if (positionMs < 0) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        if (player != null) {
            if (whenDone != null) {
                seekFinishedListeners.add(whenDone);
            }
            player.setSeekParameters(fast ? SeekParameters.NEXT_SYNC : SeekParameters.EXACT);
            player.seekTo(positionMs);
        }
    }

    /**
     * 设置Delegate。
     * @param videoPlayerDelegate VideoPlayerDelegate类型的参数 videoPlayerDelegate
     */
    public void setDelegate(VideoPlayerDelegate videoPlayerDelegate) {
        delegate = videoPlayerDelegate;
    }

    /**
     * 设置音频VisualizerDelegate。
     * @param audioVisualizerDelegate AudioVisualizerDelegate类型的参数 audioVisualizerDelegate
     */
    public void setAudioVisualizerDelegate(AudioVisualizerDelegate audioVisualizerDelegate) {
        this.audioVisualizerDelegate = audioVisualizerDelegate;
    }

    /**
     * 获取BufferedPercentage。
     * @return 整型结果值
     */
    public int getBufferedPercentage() {
        return isStreaming ? (player != null ? player.getBufferedPercentage() : 0) : 100;
    }

    /**
     * 获取Buffered位置。
     * @return 整型结果值
     */
    public long getBufferedPosition() {
        return player != null ? (isStreaming ? player.getBufferedPosition() : player.getDuration()) : 0;
    }

    /**
     * 判断是否为Streaming。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isStreaming() {
        return isStreaming;
    }

    /**
     * 判断是否为Playing。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isPlaying() {
        return mixedAudio && mixedPlayWhenReady || player != null && player.getPlayWhenReady();
    }

    /**
     * 判断是否为Buffering。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isBuffering() {
        return player != null && lastReportedPlaybackState == ExoPlayer.STATE_BUFFERING;
    }


    private boolean handleAudioFocus = false;
    /**
     * 处理音频聚焦。
     * @param handleAudioFocus 布尔值类型的参数 handleAudioFocus
     */
    public void handleAudioFocus(boolean handleAudioFocus) {
        this.handleAudioFocus = handleAudioFocus;
        if (player != null) {
            player.setAudioAttributes(player.getAudioAttributes(), handleAudioFocus);
        }
    }

    /**
     * 设置Stream类型。
     * @param type 类型（整数）
     */
    public void setStreamType(int type) {
        if (player != null) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(type == AudioManager.STREAM_VOICE_CALL ? C.USAGE_VOICE_COMMUNICATION : C.USAGE_MEDIA)
                .build(), handleAudioFocus);
        }
        if (audioPlayer != null) {
            audioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(type == AudioManager.STREAM_VOICE_CALL ? C.USAGE_VOICE_COMMUNICATION : C.USAGE_MEDIA)
                .build(), true);
        }
    }

    /**
     * 设置Looping。
     * @param looping 布尔值类型的参数 looping
     */
    public void setLooping(boolean looping) {
        if (this.looping != looping) {
            this.looping = looping;
            if (player != null) {
                player.setRepeatMode(looping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            }
        }
    }

    /**
     * 判断是否为Looping。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isLooping() {
        return looping;
    }

    /**
     * 检查PlayersReady。
     */
    private void checkPlayersReady() {
        if (audioPlayerReady && videoPlayerReady && mixedPlayWhenReady) {
            play();
        }
    }

    /**
     * 响应Player状态Changed。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param playWhenReady 布尔值类型的参数 playWhenReady
     * @param playbackState 整数类型的参数 playbackState
     */
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        long prevT = diagLastStateAtMs;
        diagLastStateAtMs = SystemClock.elapsedRealtime();
        if (player != null) {
            diagPlayer("STATE " + diagStateName(playbackState),
                    "from=" + (prevT < 0 ? "?" : (diagLastStateAtMs - prevT) + "ms ago")
                    + " pwr=" + playWhenReady
                    + " suppress=" + player.getPlaybackSuppressionReason()
                    + " pos=" + player.getCurrentPosition()
                    + " item=" + player.getCurrentMediaItemIndex()
                    + " dur=" + player.getDuration()
                    + " timeline=" + (player.getCurrentTimeline() != null ? player.getCurrentTimeline().getWindowCount() : -1));
        }
        maybeReportPlayerState();
        if (playWhenReady && playbackState == Player.STATE_READY && !isMuted() && shouldPauseOther) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.playerDidStartPlaying, this);
        }
        if (playbackState == Player.STATE_READY) {
            // One-shot internal 错误-recovery restore. Only armed 由 onPlayerError's AV1 /
            // decoder-初始化 paths. Generation 和 player instance must both still 匹配, so 
            // normal 打开,  quality switch,  later re-prepare 或  new player cannot consume 它.
            if (recoveryPlayerRef == player && recoveryPositionMs != C.TIME_UNSET && prepareGeneration == recoveryGeneration) {
                long restorePos = recoveryPositionMs;
                boolean restorePlaying = recoveryPlayWhenReady;
                recoveryPlayerRef = null;
                recoveryPositionMs = C.TIME_UNSET;
                recoveryGeneration = -1;
                if (restorePos > 0) {
                    player.seekTo(restorePos);
                }
                player.setPlayWhenReady(restorePlaying);
            }
            if (!videoPlayerReady) {
                videoPlayerReady = true;
                checkPlayersReady();
            }
        }
        if (playbackState != Player.STATE_READY) {
            audioUpdateHandler.removeCallbacksAndMessages(null);
            if (audioVisualizerDelegate != null) {
                audioVisualizerDelegate.onVisualizerUpdate(false, true, null);
            }
        }
    }

    /**
     * 响应位置Discontinuity。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param oldPosition Player.PositionInfo类型的参数 oldPosition
     * @param newPosition Player.PositionInfo类型的参数 newPosition
     * @param reason 整数类型的参数 reason
     */
    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, @Player.DiscontinuityReason int reason) {
        diagPlayer("DISCONT " + diagReasonName(reason),
                "oldPos=" + (oldPosition != null ? oldPosition.positionMs : -1)
                + " newPos=" + (newPosition != null ? newPosition.positionMs : -1)
                + " oldItem=" + (oldPosition != null ? oldPosition.mediaItemIndex : -1)
                + " newItem=" + (newPosition != null ? newPosition.mediaItemIndex : -1)
                + " state=" + diagStateName(player != null ? player.getPlaybackState() : Player.STATE_IDLE)
                + " pos=" + (player != null ? player.getCurrentPosition() : -1));
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            repeatCount++;
        } else if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            notifySeekFinished();
        }
    }

    /**
     * 响应TimelineChanged。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param timeline Timeline类型的参数 timeline
     * @param reason 整数类型的参数 reason
     */
    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        diagLastTimelineChangeAtMs = SystemClock.elapsedRealtime();
        diagPlayer("TIMELINE", "reason=" + reason
                + " windows=" + (timeline != null ? timeline.getWindowCount() : -1)
                + " pos=" + (player != null ? player.getCurrentPosition() : -1));
    }

    // Observe-only. Emits  single line 使用  monotonic timestamp (SystemClock.elapsedRealtime,
    // same clock base 作为 PhotoViewer's ABPV t=),  ExoPlayer instance identity, generation 和
    // timeline/prepare timestamps. Never touches playback state. detail 是 appended verbatim.
    /**
     * diagPlayer。
     * @param tag 标签（字符串）
     * @param detail 字符串类型的参数 detail
     */
    private void diagPlayer(String tag, String detail) {
        try {
            FileLog.d("PlayerDiag: " + tag
                    + " t=" + SystemClock.elapsedRealtime()
                    + " pid=" + (player != null ? System.identityHashCode(player) : 0)
                    + " gen=" + prepareGeneration
                    + " timelineT=" + diagLastTimelineChangeAtMs
                    + " prepareT=" + diagLastPrepareAtMs
                    + (detail != null ? " " + detail : ""));
        } catch (Throwable ignore) {
        }
    }

    /**
     * diag状态名称。
     * @param s 整数类型的参数 s
     * @return 字符串 类型的结果
     */
    private static String diagStateName(int s) {
        return switch (s) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> "STATE_" + s;
        };
    }

    /**
     * diagReason名称。
     * @param r 整数类型的参数 r
     * @return 字符串 类型的结果
     */
    private static String diagReasonName(int r) {
        return switch (r) {
            case Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION";
            case Player.DISCONTINUITY_REASON_SEEK -> "SEEK";
            case Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT";
            case Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL";
            case Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE";
            default -> "REASON_" + r;
        };
    }

    // 被调用 immediately before  internal 错误-recovery prepare(). Records  position 和
    // playback state still held 由  old source. generation 是 设置 到  值 prepareGeneration
    // will have AFTER  imminent preparePlayer* 调用 increments 它; STATE_READY restores only 如果
    //  generation 和  player instance still 匹配 (one-shot, cleared 在 restore).
    /**
     * captureRecovery意图。
     */
    private void captureRecoveryIntent() {
        if (player != null) {
            recoveryPlayerRef = player;
            recoveryPositionMs = player.getCurrentPosition();
            recoveryPlayWhenReady = player.getPlayWhenReady();
            recoveryGeneration = prepareGeneration + 1;
        }
    }

    /**
     * 响应Player错误。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param error 错误（PlaybackException）
     */
    @Override
    public void onPlayerError(PlaybackException error) {
        AndroidUtilities.runOnUIThread(() -> {
            Throwable cause = error.getCause();
            if (cause instanceof MediaCodecDecoderException) {
                if (cause.toString().contains("av1") || cause.toString().contains("av01")) {
                    FileLog.e(error);
                    FileLog.e("av1 codec failed, we think this codec is not supported");
                    MessagesController.getGlobalMainSettings().edit().putBoolean("unsupport_video/av01", true).commit();
                    if (cachedSupportedCodec != null) {
                        cachedSupportedCodec.clear();
                    }
                    videoQualities = Quality.filterByCodec(videoQualities);
                    if (videoQualities != null) {
                        captureRecoveryIntent();
                        preparePlayer(videoQualities, videoQualityToSelect);
                    }
                    return;
                }
            }
            if (textureView != null && !triedReinit && cause instanceof MediaCodecRenderer.DecoderInitializationException) {
                triedReinit = true;
                if (player != null) {
                    ViewGroup parent = (ViewGroup) textureView.getParent();
                    if (parent != null) {
                        int i = parent.indexOfChild(textureView);
                        parent.removeView(textureView);
                        parent.addView(textureView, i);
                    }
                    if (workerQueue != null) {
                        workerQueue.postRunnable(() -> {
                            if (player != null) {
                                captureRecoveryIntent();
                                player.clearVideoSurface();
                                attachTextureView(textureView);
                                if (videoQualities != null) {
                                    preparePlayer(videoQualities, videoQualityToSelect);
                                } else if (loopingMediaSource) {
                                    preparePlayerLoop(videoUri, videoType, audioUri, audioType);
                                } else {
                                    preparePlayer(videoUri, videoType);
                                }
                                play();
                            }
                        });
                    } else {
                        captureRecoveryIntent();
                        player.clearVideoSurface();
                        attachTextureView(textureView);
                        if (videoQualities != null) {
                            preparePlayer(videoQualities, videoQualityToSelect);
                        } else if (loopingMediaSource) {
                            preparePlayerLoop(videoUri, videoType, audioUri, audioType);
                        } else {
                            preparePlayer(videoUri, videoType);
                        }
                        play();
                    }
                }
            } else {
                // 不  internal recovery: do 不 leave  stale restore armed.
                recoveryPlayerRef = null;
                recoveryPositionMs = C.TIME_UNSET;
                recoveryGeneration = -1;
                if (delegate != null) {
                    delegate.onError(this, error);
                }
            }
        });
    }

    /**
     * 获取视频尺寸。
     * @return VideoSize 类型的结果
     */
    public VideoSize getVideoSize() {
        return player != null ? player.getVideoSize() : null;
    }

    /**
     * 响应视频尺寸Changed。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param videoSize VideoSize类型的参数 videoSize
     */
    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        if (delegate != null) {
            delegate.onVideoSizeChanged(videoSize.width, videoSize.height, videoSize.unappliedRotationDegrees, videoSize.pixelWidthHeightRatio);
        }
        Player.Listener.super.onVideoSizeChanged(videoSize);
    }

    /**
     * 响应Rendered首个Frame。
     * <p>该方法重写了父类/接口中的实现。</p>
     */
    @Override
    public void onRenderedFirstFrame() {
        if (delegate != null) {
            delegate.onRenderedFirstFrame();
        }
    }

    /**
     * 响应PlaybackParametersChanged。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param playbackParameters PlaybackParameters类型的参数 playbackParameters
     */
    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

    }

    /** 由捕获了代次/请求 ID 的监听器调用，保证身份来自回调产生处而不是消费时现读。 */
    private void dispatchPlayerState(int generation, long requestId, boolean playWhenReady,
                                     int playbackState) {
        callbackGeneration = generation;
        callbackRequestId = requestId;
        onPlayerStateChanged(playWhenReady, playbackState);
    }

    /**
     * maybeReportPlayer状态。
     */
    private void maybeReportPlayerState() {
        if (player == null) {
            return;
        }
        boolean playWhenReady = player.getPlayWhenReady();
        int playbackState = player.getPlaybackState();
        if (lastReportedPlayWhenReady != playWhenReady || lastReportedPlaybackState != playbackState) {
            if (delegate != null) {
                // 使用监听器在注册时捕获的代次和请求 ID，绝不是当前字段的即时值。
                delegate.onStateChanged(this, callbackGeneration, callbackRequestId,
                        playWhenReady, playbackState);
            }
            lastReportedPlayWhenReady = playWhenReady;
            lastReportedPlaybackState = playbackState;
        }
    }

    /**
     * 获取Repeat数量。
     * @return 整型结果值
     */
    public int getRepeatCount() {
        return repeatCount;
    }

    /**
     * 类：音频VisualizerRenderersFactory，封装了相关的业务逻辑与数据处理。
     */
    private class AudioVisualizerRenderersFactory extends SdrFallbackRenderersFactory {

        /**
         * 构造方法：创建 AudioVisualizerRenderersFactory 实例并初始化其内部状态。
         * @param context 上下文类型的参数 context
         */
        public AudioVisualizerRenderersFactory(Context context) {
            super(context);
        }

        /**
         * 构建音频Sink。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param context 上下文类型的参数 context
         * @param enableFloatOutput 布尔值类型的参数 enableFloatOutput
         * @param enableAudioOutputPlaybackParams 布尔值类型的参数 enableAudioOutputPlaybackParams
         * @return AudioSink 类型的结果
         */
        @Nullable
        @Override
        protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
            return new DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(new AudioProcessor[] {new TeeAudioProcessor(new VisualizerBufferSink())})
                    .build();
        }
    }

    /**
     * 类：VisualizerBufferSink，封装了相关的业务逻辑与数据处理。
     */
    private class VisualizerBufferSink implements TeeAudioProcessor.AudioBufferSink {

        private final int BUFFER_SIZE = 1024;
        private final int MAX_BUFFER_SIZE = BUFFER_SIZE * 8;
        FourierTransform.FFT fft = new FourierTransform.FFT(BUFFER_SIZE, 48000);
        float[] real = new float[BUFFER_SIZE];
        ByteBuffer byteBuffer;
        int position = 0;

        /**
         * 构造方法：创建 VisualizerBufferSink 实例并初始化其内部状态。
         */
        public VisualizerBufferSink() {
            byteBuffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE);
            byteBuffer.position(0);
        }

        /**
         * flush。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param sampleRateHz 整数类型的参数 sampleRateHz
         * @param channelCount 整数类型的参数 channelCount
         * @param encoding 整数类型的参数 encoding
         */
        @Override
        public void flush(int sampleRateHz, int channelCount, int encoding) {

        }


        long lastUpdateTime;

        /**
         * 处理Buffer。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param buffer ByteBuffer类型的参数 buffer
         */
        @Override
        public void handleBuffer(ByteBuffer buffer) {
            if (audioVisualizerDelegate == null) {
                return;
            }
            if (buffer == AudioProcessor.EMPTY_BUFFER || !mixedPlayWhenReady) {
                audioUpdateHandler.postDelayed(() -> {
                    audioUpdateHandler.removeCallbacksAndMessages(null);
                    audioVisualizerDelegate.onVisualizerUpdate(false, true, null);
                }, 80);
                return;
            }

            if (!audioVisualizerDelegate.needUpdate()) {
                return;
            }

            int len = buffer.limit();
            if (len > MAX_BUFFER_SIZE) {
                audioUpdateHandler.removeCallbacksAndMessages(null);
                audioVisualizerDelegate.onVisualizerUpdate(false, true, null);
                return;
//                len = MAX_BUFFER_SIZE;
//                byte[] bytes = new byte[BUFFER_SIZE];
//                缓冲区.获取(bytes);
//                byteBuffer.put(bytes, 0, BUFFER_SIZE);
            } else {
                byteBuffer.put(buffer);
            }
            position += len;

            if (position >= BUFFER_SIZE) {
                len = BUFFER_SIZE;
                byteBuffer.position(0);
                for (int i = 0; i < len; i++) {
                    real[i] = (byteBuffer.getShort()) / 32768.0F;
                }
                byteBuffer.rewind();
                position = 0;

                fft.forward(real);
                float sum = 0;
                for (int i = 0; i < len; i++) {
                    float r = fft.getSpectrumReal()[i];
                    float img = fft.getSpectrumImaginary()[i];
                    float peak = (float) Math.sqrt(r * r + img * img) / 30f;
                    if (peak > 1f) {
                        peak = 1f;
                    } else if (peak < 0) {
                        peak = 0;
                    }
                    sum += peak * peak;
                }
                float amplitude = (float) (Math.sqrt(sum / len));

                float[] partsAmplitude = new float[7];
                partsAmplitude[6] = amplitude;
                if (amplitude < 0.4f) {
                    for (int k = 0; k < 7; k++) {
                        partsAmplitude[k] = 0;
                    }
                } else {
                    int part = len / 6;

                    for (int k = 0; k < 6; k++) {
                        int start = part * k;
                        float r = fft.getSpectrumReal()[start];
                        float img = fft.getSpectrumImaginary()[start];
                        partsAmplitude[k] = (float) (Math.sqrt(r * r + img * img) / 30f);

                        if (partsAmplitude[k] > 1f) {
                            partsAmplitude[k] = 1f;
                        } else if (partsAmplitude[k] < 0) {
                            partsAmplitude[k] = 0;
                        }
                    }
                }

                int updateInterval = 64;

                if (System.currentTimeMillis() - lastUpdateTime < updateInterval) {
                    return;
                }
                lastUpdateTime = System.currentTimeMillis();

                audioUpdateHandler.postDelayed(() -> audioVisualizerDelegate.onVisualizerUpdate(true, true, partsAmplitude), 130);
            }
        }
    }

    /**
     * 判断是否为HDR。
     * @return 布尔结果，true 表示条件成立或操作成功
     */
    public boolean isHDR() {
        if (player == null) {
            return false;
        }
        try {
            Format format = getVideoFormat();
            if (format == null || format.colorInfo == null) {
                return false;
            }
            return (
                format.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
                format.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG
            );
        } catch (Exception ignore) {}
        return false;
    }


    /**
     * 获取视频格式化。
     * @return Format 类型的结果
     */
    @Nullable
    private Format getVideoFormat() {
        if (player == null) {
            return null;
        }
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSelected()) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    return group.getTrackFormat(i);
                }
            }
        }
        return null;
    }

    /**
     * toMedia格式化颜色Transfer。
     * @param colorTransfer 整数类型的参数 colorTransfer
     * @return 整型结果值
     */
    private static int toMediaFormatColorTransfer(int colorTransfer) {
        if (colorTransfer == Format.NO_VALUE) {
            return 0;
        }
        if (colorTransfer == C.COLOR_TRANSFER_SDR) {
            return MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
        }
        return colorTransfer;
    }

    /**
     * toMedia格式化颜色Standard。
     * @param colorSpace 整数类型的参数 colorSpace
     * @return 整型结果值
     */
    private static int toMediaFormatColorStandard(int colorSpace) {
        return colorSpace == Format.NO_VALUE ? 0 : colorSpace;
    }

    /**
     * toMedia格式化颜色Range。
     * @param colorRange 整数类型的参数 colorRange
     * @return 整型结果值
     */
    private static int toMediaFormatColorRange(int colorRange) {
        switch (colorRange) {
            case C.COLOR_RANGE_LIMITED:
                return MediaFormat.COLOR_RANGE_LIMITED;
            case C.COLOR_RANGE_FULL:
                return MediaFormat.COLOR_RANGE_FULL;
            default:
                return 0;
        }
    }

    /**
     * 获取HDRStaticInfo。
     * @param hdrInfo StoryEntry.HDRInfo类型的参数 hdrInfo
     * @return StoryEntry.HDRInfo 类型的结果
     */
    public StoryEntry.HDRInfo getHDRStaticInfo(StoryEntry.HDRInfo hdrInfo) {
        if (hdrInfo == null) {
            hdrInfo = new StoryEntry.HDRInfo();
        }
        try {
            Format format = getVideoFormat();
            if (format == null || format.colorInfo == null) {
                hdrInfo.maxlum = hdrInfo.minlum = 0;
                return hdrInfo;
            }
            byte[] hdrStaticInfo = format.colorInfo.hdrStaticInfo;
            if (hdrStaticInfo != null && hdrStaticInfo.length >= 21 && hdrStaticInfo[0] == 0) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(hdrStaticInfo).order(ByteOrder.LITTLE_ENDIAN);
                hdrInfo.maxlum = byteBuffer.getShort(17);
                hdrInfo.minlum = byteBuffer.getShort(19) * 0.0001f;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (format.colorInfo.colorTransfer != Format.NO_VALUE) {
                    hdrInfo.colorTransfer = toMediaFormatColorTransfer(format.colorInfo.colorTransfer);
                }
                if (format.colorInfo.colorSpace != Format.NO_VALUE) {
                    hdrInfo.colorStandard = toMediaFormatColorStandard(format.colorInfo.colorSpace);
                }
                if (format.colorInfo.colorRange != Format.NO_VALUE) {
                    hdrInfo.colorRange = toMediaFormatColorRange(format.colorInfo.colorRange);
                }
            }
        } catch (Exception ignore) {
            hdrInfo.maxlum = hdrInfo.minlum = 0;
        }
        return hdrInfo;
    }

    /**
     * 设置Worker队列。
     * @param dispatchQueue DispatchQueue类型的参数 dispatchQueue
     */
    public void setWorkerQueue(DispatchQueue dispatchQueue) {
        workerQueue = dispatchQueue;
    }

    /**
     * 设置判断是否为Story。
     */
    public void setIsStory() {
        isStory = true;
    }

    /**
     * 响应TracksChanged。
     * <p>该方法重写了父类/接口中的实现。</p>
     * @param tracks Tracks类型的参数 tracks
     */
    @Override
    public void onTracksChanged(Tracks tracks) {
        Player.Listener.super.onTracksChanged(tracks);
        if (onQualityChangeListener != null) {
            AndroidUtilities.runOnUIThread(onQualityChangeListener);
        }
    }

    /**
     * 获取当前ChromecastMedia。
     * @param defaultId 字符串类型的参数 defaultId
     * @param title 标题（字符串）
     * @param subtitle 副标题（字符串）
     * @return ChromecastMediaVariations 类型的结果
     */
    public ChromecastMediaVariations getCurrentChromecastMedia(String defaultId, String title, String subtitle) {
        if (videoQualities == null) {
            if (videoUri == null) {
                return null;
            }

            final String path = "/mtproto_" + defaultId;
            String mime = videoUri.getQueryParameter("mime");
            if (TextUtils.isEmpty(mime)) {
                mime = ChromecastMedia.VIDEO_MP4;
            }
            final ChromecastMedia media = ChromecastMedia.Builder.fromUri(videoUri, path, mime)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .build();

            return ChromecastMediaVariations.of(media);
        }

        final ChromecastMediaVariations.Builder builder = new ChromecastMediaVariations.Builder();
        for (Quality quality : videoQualities) {
            for (VideoUri vUri : quality.uris) {
                final String path = "/mtproto_" + vUri.docId;
                String mime = null;
                if (vUri.document != null) {
                    mime = vUri.document.mime_type;
                }
                if (TextUtils.isEmpty(mime)) {
                    mime = ChromecastMedia.VIDEO_MP4;
                }
                final ChromecastMedia media = ChromecastMedia.Builder.fromUri(vUri.uri, path, mime)
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setSize(vUri.width, vUri.height)
                        .build();

                builder.add(media);
            }
        }

        return builder.build();
    }

    /**
     * 类：偏移数据Source，封装了相关的业务逻辑与数据处理。
     */
    public static class OffsetDataSource implements DataSource {
        private final DataSource upstream;
        private final long byteOffset;

        /**
         * 构造方法：创建 OffsetDataSource 实例并初始化其内部状态。
         * @param upstream DataSource类型的参数 upstream
         * @param byteOffset 长整型类型的参数 byteOffset
         */
        public OffsetDataSource(DataSource upstream, long byteOffset) {
            this.upstream = upstream;
            this.byteOffset = byteOffset;
        }

        /**
         * 添加Transfer监听器。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param transferListener TransferListener类型的参数 transferListener
         */
        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        /**
         * 打开。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param dataSpec DataSpec类型的参数 dataSpec
         * @return 整型结果值
         */
        @Override
        public long open(DataSpec dataSpec) throws IOException {
            DataSpec offsetDataSpec = dataSpec.buildUpon()
                .setPosition(dataSpec.position + byteOffset)
                .build();
            return upstream.open(offsetDataSpec);
        }

        /**
         * read。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @param buffer byte[]类型的参数 buffer
         * @param offset 偏移（整数）
         * @param length 长度（整数）
         * @return 整型结果值
         */
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return upstream.read(buffer, offset, length);
        }

        /**
         * 获取Uri。
         * <p>该方法重写了父类/接口中的实现。</p>
         * @return Uri 类型的结果
         */
        @Override
        public Uri getUri() { return upstream.getUri(); }

        /**
         * 关闭。
         * <p>该方法重写了父类/接口中的实现。</p>
         */
        @Override
        public void close() throws IOException { upstream.close(); }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return upstream.getResponseHeaders();
        }
    }

}
