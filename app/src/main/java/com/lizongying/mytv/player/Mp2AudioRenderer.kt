package com.lizongying.mytv.player

import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

/**
 * 把 [Mp2Decoder]（libmad 软解）接进 media3 的音频渲染器。
 *
 * 只认 `audio/mpeg-L1` 与 `audio/mpeg-L2`：
 *   - `audio/mpeg`（Layer III）交给系统自带的 MP3 解码器，这里返回不支持，
 *     不抢硬解、也不多耗电；
 *   - `audio/mpeg-L1/L2` 在 Android 上没有保证的软解，本机（Hi3798/Android 9）
 *     也没有任何 MP2 硬解，正是本类存在的理由。
 *
 * 渲染器选择由 ExoPlayer 按 supportsFormat 的分数决定：MP2 时本渲染器得
 * FORMAT_HANDLED、系统渲染器得 FORMAT_UNSUPPORTED_TYPE，本渲染器自然胜出；
 * AAC 等情况则反过来，互不干扰。
 */
@OptIn(UnstableApi::class)
internal class Mp2AudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<Mp2Decoder>(eventHandler, eventListener, audioSink) {

    companion object {
        private const val TAG = "Mp2Dec"
    }

    private var currentFormat: Format? = null

    override fun getName(): String = "Mp2AudioRenderer(libmad)"

    override fun supportsFormatInternal(format: Format): Int {
        return when (format.sampleMimeType) {
            MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> C.FORMAT_HANDLED
            else -> C.FORMAT_UNSUPPORTED_TYPE
        }
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Mp2Decoder {
        currentFormat = format
        Log.i(TAG, "开始用 libmad 软解 ${format.sampleMimeType} " +
                "(${format.sampleRate}Hz ${format.channelCount}ch)")
        return Mp2Decoder(format)
    }

    override fun getOutputFormat(decoder: Mp2Decoder): Format {
        val source = currentFormat
        val sampleRate = decoder.sampleRate.takeIf { it > 0 } ?: 48_000
        val channelCount = decoder.channelCount.takeIf { it in 1..2 } ?: 2

        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .setId(source?.id)
            .build()
    }
}
