package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer;
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;

/**
 * 支持 FFmpeg 软解的 RenderersFactory。
 *
 * 在默认渲染器之后追加 FFmpeg 音频/视频软解器。
 * 若 FFmpeg native 库不可用，这些渲染器不会被加入，自动回退到硬解。
 */
@OptIn(markerClass = UnstableApi.class)
public final class FfmpegRenderersFactory extends DefaultRenderersFactory {

    public FfmpegRenderersFactory(Context context) {
        super(context);
    }

    @Override
    protected void buildAudioRenderers(
            @NonNull Context context,
            int extensionRendererMode,
            @NonNull MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            @NonNull AudioSink audioSink,
            @NonNull Handler eventHandler,
            @NonNull AudioRendererEventListener eventListener,
            @NonNull ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
                enableDecoderFallback, audioSink, eventHandler, eventListener, out);
        if (!FfmpegSupport.isAvailable()) return;
        try {
            out.add(new FfmpegAudioRenderer());
        } catch (Throwable ignored) {
            // 构造失败则保持仅有硬解
        }
    }

    @Override
    protected void buildVideoRenderers(
            @NonNull Context context,
            int extensionRendererMode,
            @NonNull MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            @NonNull Handler eventHandler,
            @NonNull VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            @NonNull ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
                enableDecoderFallback, eventHandler, eventListener,
                allowedVideoJoiningTimeMs, out);
        if (!FfmpegSupport.isAvailable()) return;
        try {
            out.add(new ExperimentalFfmpegVideoRenderer(
                    allowedVideoJoiningTimeMs, eventHandler, eventListener, 50));
        } catch (Throwable ignored) {
        }
    }
}
