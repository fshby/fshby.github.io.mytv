package com.lizongying.mytv.player

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * media3 的自定义音频解码器：用内置的 libmad 解 MPEG-1 Layer II。
 *
 * 为什么需要它：media3 的 TS 解析器把 MP2 标成 `audio/mpeg-L2`，而 Android
 * 从不要求支持该格式（AOSP 的 MP3 软解 pvmp3 只有 Layer III），于是音频渲染器
 * 被整个禁用 —— 画面正常、无声、且不抛任何错误。这里补上这个缺口。
 *
 * 实现走 media3 的 [Decoder] 契约：media3 通过 [dequeueInputBuffer] 借出一个
 * 可写缓冲，填好压缩数据后由 [queueInputBuffer] 交还，随后在
 * [dequeueOutputBuffer] 里取走解好的 PCM。
 *
 * 时间戳约定：直播间一帧 MP2 固定 1152（MPEG-1）或 576（MPEG-2 LSF）个样本，
 * 因此一批输入若解出多帧，时间戳按帧长递增铺开，保证与视频时钟对齐。
 */
@OptIn(UnstableApi::class)
internal class Mp2Decoder(private val inputFormat: Format) :
    Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    companion object {
        private const val TAG = "Mp2Dec"

        /**
         * 单次向 native 索取的 PCM 上限。一帧立体声 16-bit 是 4608 字节，
         * 128KB ≈ 28 帧 ≈ 0.67 秒，足够覆盖 media3 一次投喂的量。
         */
        private const val SCRATCH_BYTES = 128 * 1024

        /** meta 数组下标 */
        private const val META_SAMPLE_RATE = 0
        private const val META_CHANNELS = 1
        private const val META_SAMPLES_PER_FRAME = 2
        private const val META_FRAMES = 3
    }

    private val handle: Long = Mp2DecoderNative.nativeCreate().also {
        if (it == 0L) {
            throw DecoderException("libmad 解码器句柄创建失败")
        }
    }

    /** native 解出的 PCM 中转区（direct，与 JNI 直接共享内存，不做额外拷贝） */
    private val scratch: ByteBuffer =
        ByteBuffer.allocateDirect(SCRATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    private val meta = IntArray(4)

    /** 解好但还没被 media3 取走的 PCM 帧 */
    private val pending = ArrayDeque<SimpleDecoderOutputBuffer>()

    /** 循环复用的输出缓冲池（SimpleDecoderOutputBuffer 内部按需扩容） */
    private val recycled = ArrayDeque<SimpleDecoderOutputBuffer>()

    private val bufferOwner = DecoderOutputBuffer.Owner<SimpleDecoderOutputBuffer> { buffer ->
        buffer.clear()
        recycled.addLast(buffer)
    }

    private var inputBuffer: DecoderInputBuffer? = null

    private var released = false

    /** 输出 PCM 的采样率（先按 format 估计，解出第一帧后用实测值校正） */
    var sampleRate: Int = inputFormat.sampleRate.takeIf { it > 0 } ?: 48_000
        private set

    /** 输出 PCM 的声道数 */
    var channelCount: Int = inputFormat.channelCount.takeIf { it in 1..2 } ?: 2
        private set

    /** media3 给的输入时间戳缺失时，用自己累加的值兜底 */
    private var lastOutputTimeUs = 0L

    /* ---- 诊断统计（每 3 秒一条，确认软解确实在产出 PCM） ---- */
    private var statInputs = 0
    private var statFrames = 0
    private var statTaken = 0
    private var statLastLogMs = 0L

    override fun getName(): String = "libmad(MPEG-L2)"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        // 直播流不需要平移输出时间戳（seek 由 flush 重新同步）
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        if (released) {
            return null
        }
        val buffer = inputBuffer ?: DecoderInputBuffer(
            // 必须是 DIRECT：media3 从 SampleQueue 拷数据进来时会按需扩容
            // （ensureSpaceForWrite），DISABLED 会直接抛 InsufficientCapacityException；
            // 而 JNI 侧用 GetDirectBufferAddress 取裸指针，堆内 buffer 不行。
            DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT
        ).also { inputBuffer = it }
        buffer.clear()
        return buffer
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        if (released) {
            return
        }
        val data = inputBuffer.data
        if (data == null || !data.isDirect) {
            Log.w(TAG, "输入缓冲不是 direct ByteBuffer，跳过")
            return
        }
        val length = data.remaining()
        if (length <= 0) {
            return
        }

        val timeUs = inputBuffer.timeUs.takeIf { it != C.TIME_UNSET } ?: lastOutputTimeUs

        val produced = try {
            Mp2DecoderNative.nativeDecode(handle, data, length, scratch, SCRATCH_BYTES, meta)
        } catch (t: Throwable) {
            throw DecoderException("调用 libmad 失败", t)
        }
        if (produced < 0) {
            throw DecoderException("libmad 解码失败")
        }
        if (produced == 0) {
            return
        }

        // 用实测值校正输出格式（首次解出后即固定，直播流内不会变）
        val decodedRate = meta[META_SAMPLE_RATE]
        val decodedChannels = meta[META_CHANNELS]
        if (decodedRate > 0 && decodedRate != sampleRate) {
            Log.i(TAG, "采样率：估计 $sampleRate → 实测 $decodedRate")
            sampleRate = decodedRate
        }
        if (decodedChannels in 1..2 && decodedChannels != channelCount) {
            Log.i(TAG, "声道数：估计 $channelCount → 实测 $decodedChannels")
            channelCount = decodedChannels
        }

        val samplesPerFrame = meta[META_SAMPLES_PER_FRAME].takeIf { it > 0 } ?: 1152
        val frames = meta[META_FRAMES].takeIf { it > 0 } ?: 1

        val frameBytes = samplesPerFrame * channelCount * 2
        if (frameBytes <= 0 || produced < frameBytes) {
            return
        }

        // 一帧的时长（微秒），用于把多帧的时间戳依次铺开
        val frameDurationUs = samplesPerFrame * 1_000_000L / sampleRate

        var cursor = 0
        var frameTimeUs = timeUs
        while (cursor + frameBytes <= produced) {
            val out = recycled.pollLast() ?: SimpleDecoderOutputBuffer(bufferOwner)
            val target = out.init(frameTimeUs, frameBytes)

            scratch.limit(cursor + frameBytes)
            scratch.position(cursor)
            target.put(scratch)
            target.position(0)   // 交还前复位读指针，media3 读的是 [0, limit)
            scratch.clear()

            pending.addLast(out)

            cursor += frameBytes
            frameTimeUs += frameDurationUs
        }

        lastOutputTimeUs = frameTimeUs

        statInputs++
        statFrames += frames
        val nowMs = SystemClock.elapsedRealtime()
        // 低频心跳：既能在现场日志里确认软解在跑，又不至于刷屏
        if (nowMs - statLastLogMs >= 30_000) {
            statLastLogMs = nowMs
            Log.i(
                TAG,
                "软解统计：输入 $statInputs 段 / 解出 $statFrames 帧 / media3 取走 $statTaken 帧 " +
                        "(${sampleRate}Hz ${channelCount}ch)"
            )
        }
    }

    override fun dequeueOutputBuffer(): SimpleDecoderOutputBuffer? {
        val buffer = pending.pollFirst()
        if (buffer != null) {
            statTaken++
        }
        return buffer
    }

    override fun flush() {
        while (true) {
            val out = pending.pollFirst() ?: break
            out.release()
        }
        if (!released) {
            Mp2DecoderNative.nativeFlush(handle)
        }
        lastOutputTimeUs = 0L
    }

    override fun release() {
        if (released) {
            return
        }
        released = true
        while (true) {
            val out = pending.pollFirst() ?: break
            out.release()
        }
        Mp2DecoderNative.nativeDestroy(handle)
    }
}
