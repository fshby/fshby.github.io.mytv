package com.lizongying.mytv.player

import android.util.Log
import java.nio.ByteBuffer

/**
 * libmp2dec.so 的 JNI 门面。
 *
 * 该库由 libmad（纯 C，MPEG-1/2 Layer I/II/III 定点软解）+ [mp2dec_jni.c] 编成，
 * 只做一件事：把 MP2 压缩帧解成 16-bit 交错 PCM。
 *
 * 加载失败（ABI 不匹配、库缺失等）时 [available] 保持 false，上层据此**降级**
 * ——只是 MP2 源仍然无声，绝不能因此崩溃或影响其它格式的播放。
 */
internal object Mp2DecoderNative {

    private const val TAG = "Mp2Dec"

    /** 原生库是否加载成功。 */
    val available: Boolean

    init {
        available = try {
            System.loadLibrary("mp2dec")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "libmp2dec.so 加载失败，MP2 软解不可用（MP2 源将无声）", t)
            false
        }
    }

    /** @return 解码器句柄，0 表示失败 */
    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    /** 丢弃缓冲与同步状态（media3 的 flush/seek 时调用）。 */
    external fun nativeFlush(handle: Long)

    /**
     * 喂入压缩数据并取出 PCM。
     *
     * @param input     输入 direct ByteBuffer（读 [inputLen] 字节）
     * @param output    输出 direct ByteBuffer（最多写 [outputCap] 字节）
     * @param meta      出参：`[采样率, 声道数, 每帧样本数, 本次帧数]`
     * @return 实际写入 `output` 的 PCM 字节数；负数表示不可恢复的解码错误
     */
    external fun nativeDecode(
        handle: Long,
        input: ByteBuffer,
        inputLen: Int,
        output: ByteBuffer,
        outputCap: Int,
        meta: IntArray,
    ): Int
}
