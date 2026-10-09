/*
 * mp2dec_jni.c —— libmad 的 JNI 包装
 *
 * 职责单一：把 MPEG-1/2 Layer II（以及顺带 Layer I/III）压缩音频流
 * 解码成 16-bit 交错 PCM，交给上层（Kotlin 的 Mp2Decoder）喂给 media3。
 *
 * 数据流：
 *   TS 解析器给的 MP2 帧数据 --append--> in(累积缓冲)
 *     --> mad_frame_decode / mad_synth_frame 循环
 *     --> out(PCM16LE 累积缓冲) --拷出--> Java direct ByteBuffer
 *
 * 关键约定：
 *   - 一次 nativeDecode 调用解出「当前缓冲区里所有能完整解码的帧」，
 *     但要受 outCap 限制（解满就停，剩余输入留到下次）。
 *   - 未消费的输入按帧边界保留在 in 里，下次调用继续 —— libmad 每次
 *     都会重新 buffer，因此必须自己维护残留，不能假设输入按帧对齐。
 *   - 输出是小端 16-bit 交错 PCM，采样率/声道数由 meta 回传。
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>

/* 必须先于 mad.h：mad.h 里要按 FPM_* 选择定点实现，缺了就是 "no FPM selected"。
   libmad 自己的 .c 靠 -DHAVE_CONFIG_H 引入，这里是项目自有文件，直接包含。 */
#include "config.h"
#include "mad.h"

#define TAG "Mp2Dec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* 输入累积缓冲：media3 每次通常只给一帧（MP2 约 0.4~1.4KB），96KB 足够从容 */
#define IN_CAP (96 * 1024)
/* MP2 一帧固定 1152 个样本/声道，立体声 16-bit = 4608 字节 */
#define MAX_FRAME_BYTES 4608
/* 连续可恢复错误阈值：超过就认为进了垃圾区，跳过 4 字节强制重新同步 */
#define MAX_CONSECUTIVE_ERRORS 256

typedef struct {
    struct mad_stream stream;
    struct mad_frame  frame;
    struct mad_synth  synth;

    unsigned char *in;      /* 压缩数据累积（未消费部分始终从帧边界开始） */
    size_t         in_len;

    unsigned char *out;     /* 本次调用产出的 PCM16LE */
    size_t         out_len;

    int sample_rate;
    int channels;
    int frame_samples;   /* 每帧样本数：MPEG-1 = 1152，MPEG-2 LSF = 576 */
    long frames_decoded;
} Mp2Handle;

/* mad_fixed_t(28 位小数) -> 16-bit，带饱和裁剪 */
static inline int16_t pcm16(mad_fixed_t s)
{
    if (s >= MAD_F_ONE) {
        s = MAD_F_ONE - 1;
    } else if (s <= -MAD_F_ONE) {
        s = -MAD_F_ONE;
    }
    return (int16_t) (s >> (MAD_F_FRACBITS + 1 - 16));
}

static void handle_free(Mp2Handle *h)
{
    if (h == NULL) {
        return;
    }
    mad_synth_finish(&h->synth);
    mad_frame_finish(&h->frame);
    mad_stream_finish(&h->stream);
    free(h->in);
    free(h->out);
    free(h);
}

JNIEXPORT jlong JNICALL
Java_com_lizongying_mytv_player_Mp2DecoderNative_nativeCreate(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;

    Mp2Handle *h = (Mp2Handle *) calloc(1, sizeof(Mp2Handle));
    if (h == NULL) {
        LOGE("calloc handle failed");
        return 0;
    }
    h->in = (unsigned char *) malloc(IN_CAP);
    h->out = (unsigned char *) malloc(MAX_FRAME_BYTES * 4);
    if (h->in == NULL || h->out == NULL) {
        LOGE("alloc buffers failed");
        handle_free(h);
        return 0;
    }
    h->in_len = 0;
    h->out_len = 0;

    mad_stream_init(&h->stream);
    mad_frame_init(&h->frame);
    mad_synth_init(&h->synth);

    LOGI("libmad %s ready (Layer I/II/III, FPM_DEFAULT)", mad_version);
    return (jlong) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_lizongying_mytv_player_Mp2DecoderNative_nativeDestroy(JNIEnv *env, jclass clazz,
                                                              jlong handle)
{
    (void) env;
    (void) clazz;
    Mp2Handle *h = (Mp2Handle *) (intptr_t) handle;
    if (h != NULL) {
        LOGI("destroyed after %ld frames", h->frames_decoded);
    }
    handle_free(h);
}

/* 丢弃所有缓冲与同步状态（media3 seek/flush 时调用） */
JNIEXPORT void JNICALL
Java_com_lizongying_mytv_player_Mp2DecoderNative_nativeFlush(JNIEnv *env, jclass clazz,
                                                            jlong handle)
{
    (void) env;
    (void) clazz;
    Mp2Handle *h = (Mp2Handle *) (intptr_t) handle;
    if (h == NULL) {
        return;
    }
    h->in_len = 0;
    h->out_len = 0;
    mad_synth_init(&h->synth);
    mad_frame_init(&h->frame);
    mad_stream_init(&h->stream);
}

/*
 * 返回值：
 *   >= 0 本次产出的 PCM 字节数（0 表示还需要更多输入）
 *   <  0 解码器不可恢复错误
 * meta[0] = 采样率，meta[1] = 声道数
 */
JNIEXPORT jint JNICALL
Java_com_lizongying_mytv_player_Mp2DecoderNative_nativeDecode(JNIEnv *env, jclass clazz,
                                                             jlong handle,
                                                             jobject input, jint inputLen,
                                                             jobject output, jint outputCap,
                                                             jintArray meta)
{
    (void) clazz;
    Mp2Handle *h = (Mp2Handle *) (intptr_t) handle;
    if (h == NULL) {
        return -1;
    }

    const unsigned char *src = (const unsigned char *) (*env)->GetDirectBufferAddress(env, input);
    unsigned char *dst = (unsigned char *) (*env)->GetDirectBufferAddress(env, output);
    if ((inputLen > 0 && src == NULL) || dst == NULL) {
        LOGE("non-direct ByteBuffer passed");
        return -1;
    }

    /* ---- 1. 追加输入 ---- */
    if (inputLen > 0) {
        size_t want = (size_t) inputLen;
        if (h->in_len + want > IN_CAP) {
            /* 正常不会走到这里（media3 每次给的是一帧）。真发生说明长期
               同步不上，丢掉最旧的数据，保证最新数据能被处理。 */
            size_t drop = h->in_len + want - IN_CAP;
            if (drop >= h->in_len) {
                h->in_len = 0;
            } else {
                memmove(h->in, h->in + drop, h->in_len - drop);
                h->in_len -= drop;
            }
            LOGW("input overflow, dropped %zu bytes", drop);
        }
        memcpy(h->in + h->in_len, src, want);
        h->in_len += want;
    }

    h->out_len = 0;
    size_t out_cap = (size_t) outputCap;

    /* ---- 2. 解码循环 ---- */
    const unsigned char *last_next = NULL;
    int err_streak = 0;
    int frames_this_call = 0;

    if (h->in_len >= 4 && out_cap >= MAX_FRAME_BYTES) {
        mad_stream_buffer(&h->stream, h->in, (unsigned long) h->in_len);

        for (;;) {
            if (h->out_len + MAX_FRAME_BYTES > out_cap) {
                break;  /* 输出已满，剩下的下次解 */
            }

            if (mad_frame_decode(&h->frame, &h->stream) != 0) {
                if (h->stream.error == MAD_ERROR_BUFLEN) {
                    break;  /* 数据不够一帧，等下次输入 */
                }
                if (MAD_RECOVERABLE(h->stream.error)) {
                    if (++err_streak > MAX_CONSECUTIVE_ERRORS) {
                        LOGW("too many recoverable errors, force resync");
                        break;
                    }
                    continue;  /* 坏帧，跳过继续找同步 */
                }
                LOGE("unrecoverable decode error 0x%04x", h->stream.error);
                last_next = h->stream.next_frame;
                break;
            }

            err_streak = 0;
            last_next = h->stream.next_frame;

            mad_synth_frame(&h->synth, &h->frame);
            const struct mad_pcm *pcm = &h->synth.pcm;
            int ch = (int) pcm->channels;
            if (ch <= 0) {
                continue;
            }
            if (ch > 2) {
                ch = 2;  /* libmad 最多输出 2 声道 */
            }

            h->sample_rate = (int) pcm->samplerate;
            h->channels = ch;
            h->frame_samples = (int) pcm->length;
            h->frames_decoded++;
            frames_this_call++;

            int16_t *o = (int16_t *) (h->out + h->out_len);
            unsigned int n = pcm->length;  /* 每声道样本数（Layer II 固定 1152） */
            for (unsigned int i = 0; i < n; i++) {
                for (int c = 0; c < ch; c++) {
                    *o++ = pcm16(pcm->samples[c][i]);
                }
            }
            h->out_len += (size_t) n * (size_t) ch * 2u;
        }
    }

    /* ---- 3. 压缩输入缓冲，只保留未完成的帧 ---- */
    size_t consumed = 0;
    if (last_next != NULL && last_next >= h->in && last_next <= h->in + h->in_len) {
        consumed = (size_t) (last_next - h->in);
    } else if (err_streak > MAX_CONSECUTIVE_ERRORS && h->in_len > 0) {
        /* 从头到尾都没解开一帧：丢弃 4 字节强行错开，避免死循环卡在同一处 */
        consumed = h->in_len < 4 ? h->in_len : 4;
        LOGW("resync: dropped %zu bytes", consumed);
    }
    if (consumed > 0) {
        size_t remain = h->in_len - consumed;
        if (remain > 0) {
            memmove(h->in, h->in + consumed, remain);
        }
        h->in_len = remain;
    }

    /* ---- 4. 回传 PCM 与格式 ---- */
    if (h->out_len > 0 && dst != NULL) {
        memcpy(dst, h->out, h->out_len);
    }
    if (meta != NULL) {
        jint values[4];
        jsize count = (*env)->GetArrayLength(env, meta);
        if (count > 4) {
            count = 4;
        }
        if (count > 0) {
            values[0] = h->sample_rate;
            values[1] = h->channels;
            values[2] = h->frame_samples;
            values[3] = frames_this_call;
            (*env)->SetIntArrayRegion(env, meta, 0, count, values);
        }
    }

    return (jint) h->out_len;
}
