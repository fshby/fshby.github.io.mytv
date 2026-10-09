package com.lizongying.mytv.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * 在默认渲染器之外挂上 [Mp2AudioRenderer]。
 *
 * media3 官方的 FFmpeg 扩展也是这个套路（DefaultRenderersFactory 里按
 * EXTENSION_RENDERER_MODE 往列表里塞 FfmpegAudioRenderer），区别只是
 * 我们把解码器静态编译进了 APK，不依赖动态查找的扩展类。
 *
 * 渲染器之间的取舍交给 ExoPlayer 的 supportsFormat 打分：
 * MP2 时只有本渲染器得高分，AAC/H.264 时它返回不支持，互不影响。
 */
@OptIn(UnstableApi::class)
internal class Mp2RenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    companion object {
        private const val TAG = "Mp2Dec"
    }

    /** buildAudioSink 需要 Context，父类没有开放访问器，这里自己留一份 */
    private val appContext: Context = context.applicationContext ?: context

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> {
        val base = super.createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput,
        )

        if (!Mp2DecoderNative.available) {
            Log.w(TAG, "libmp2dec.so 不可用，本次不启用 MP2 软解渲染器")
            return base
        }

        // 自建一个 AudioSink 给软解渲染器用：默认那个已经归 MediaCodecAudioRenderer
        // 所有。两个渲染器不会同时 enabled，因此各自持有 sink 是安全的，
        // 也避免了两个渲染器互相改写对方的 sink 配置。
        val sink = buildAudioSink(
            appContext,
            /* enableFloatOutput= */ false,
            /* enableAudioTrackPlaybackParams= */ false,
        ) ?: run {
            Log.w(TAG, "无法创建 AudioSink，本次不启用 MP2 软解渲染器")
            return base
        }
        val mp2 = Mp2AudioRenderer(eventHandler, audioRendererEventListener, sink)

        Log.i(TAG, "已启用 MP2 软解渲染器（libmad）")
        return arrayOf<Renderer>(mp2, *base)
    }
}
