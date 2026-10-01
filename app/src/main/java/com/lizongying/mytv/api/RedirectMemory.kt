package com.lizongying.mytv.api

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * 重定向落地地址固化：把 302 落地地址记住，下次直接请求，省掉一跳。
 *
 * 实测背景（MR622-DK 上用机身 curl 采样 24 个存活源）：
 *  - 约 1/3 直连（206/200），约 2/3 走 301/302 且落地跨主机（num_connects 实打实 2~3 次）；
 *  - 重定向类源比直连源慢 5~70 倍，建连占总耗时 40~45%；
 *  - HLS 每 2.5~5s 刷新一次 playlist，**每一轮刷新都在重复跳同一个 302**。
 *
 * 落地地址分两类，策略不同：
 *
 * **静态落地**（无 query）：沿用「自适应确认」——只有连续两次观察到完全相同的
 * 落地地址才确认并直连。防止把轮换型网关的错误调度固化下来。
 *
 * **token 型落地**（带 `?jsbt=&jsbk=` 等时间签名 query）：一次学习即固化。
 * 实测（107.150.60.122 网关源，2026-10-01）这类 token **有效期 ≥8 分钟**，
 * 且网关背后有多台后端、每台序列号体系独立——每轮 playlist 刷新都过网关的话，
 * 偶发落到另一台后端会导致播放器时间轴错乱（表现为隔几秒卡一次）。
 * 固化落地地址同时实现了**后端粘性**：整个会话期间 playlist 与分片都命中同一台后端。
 *
 * 失败回退与「一次性 token」防护：
 *  - 直连失败（非 2xx / 异常）立即清除映射，**本次请求就地回退**原始网关地址重放
 *    （播放器完全感知不到这次失败，不会走 LoadErrorHandlingPolicy 的退避）；
 *  - 曾经直连成功过的条目失败 = token 自然过期，清除后下一轮网关响应会带上新
 *    token 被重新固化，粘性无缝续期；
 *  - **从未成功过**就失败的条目（hits == 0）说明该源用的是一次性 token，固化
 *    必然净亏损（每次刷新先失败一次再回退），把该 key 拉黑 [BLOCK_MS]，
 *    期间完全不固化，退回「每次都走网关」的旧行为（零额外请求）。
 *  - 原始地址自带 query 时不做固化（改写会丢掉原参数）；
 *  - 映射带 TTL，避免 CDN 调度变化后一直用旧落地地址。
 */
class RedirectMemory(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : Interceptor {

    private class Entry(
        val target: HttpUrl,
        val ts: Long,
        /** 是否确认可直连：token 型一次学习即确认；静态型需连续两次观察到相同落地 */
        val confirmed: Boolean,
        /** 直连成功次数；用于区分「token 自然过期」和「一次性 token」 */
        val hits: Int,
        /** 落地地址带 query（时间签名） */
        val tokenized: Boolean,
    )

    /** key = scheme://host:port/path（刻意不含 query：播放地址的 token 每轮都会变） */
    private val memo = ConcurrentHashMap<String, Entry>()

    /** 一次性 token 的拉黑表：key -> 解禁时刻 */
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = keyOf(request.url)

        // 原始地址带参数时不动它，避免改写后丢参数
        val usable = request.url.query == null && !isBlocked(key)
        val entry = if (usable) memo[key] else null

        if (entry != null && entry.confirmed) {
            if (System.currentTimeMillis() - entry.ts > ttlMs) {
                memo.remove(key)
            } else {
                val resp = runCatching {
                    chain.proceed(request.newBuilder().url(entry.target).build())
                }.onFailure {
                    Log.i(TAG, "direct hit failed ${it.javaClass.simpleName}, fallback")
                }.getOrNull()
                if (resp != null && resp.isSuccessful) {
                    // 落地地址仍然有效，刷新时间戳与成功计数
                    memo[key] = Entry(entry.target, System.currentTimeMillis(), true, entry.hits + 1, entry.tokenized)
                    return resp
                }
                resp?.close()
                memo.remove(key)
                if (entry.hits == 0) {
                    // 一次都没成功过就失败：一次性 token，固化是净亏损，拉黑一段时间
                    blockedUntil[key] = System.currentTimeMillis() + BLOCK_MS
                    Log.i(TAG, "pin never succeeded, block $key for ${BLOCK_MS / 60000}min")
                }
                // 曾经成功过（token 自然过期）：不拉黑，本次回退网关的响应
                // 会在 learn() 里带着新 token 被重新固化，粘性无缝续期
            }
        }

        val response = chain.proceed(request)
        if (usable) learn(key, response)
        return response
    }

    /**
     * 学习一次跳转结果。
     *
     * - 静态落地：连续两次一致才确认，轮换型调度永远不会被固化；
     * - token 型落地：一次学习即确认（有效期实测 ≥8 分钟，失效靠直连失败回退兜底）。
     */
    private fun learn(key: String, response: Response) {
        val finalUrl = response.request.url
        if (keyOf(finalUrl) == key) {
            // 没发生跨主机跳转（含直连成功的情况）：失败则丢弃映射
            if (response.code >= 400) {
                memo.remove(key)
            }
            return
        }
        if (!response.isSuccessful) {
            memo.remove(key)
            return
        }
        val tokenized = finalUrl.query != null
        if (tokenized) {
            Log.i(TAG, "pin tokenized landing: $key -> ${finalUrl.host}:${finalUrl.port}")
        }
        // token 型一次学习即确认；静态型需与上次观测到的落地一致（连续两次）才确认，
        // 轮换型调度永远不会被固化
        val prev = memo[key]
        val confirmed = tokenized || (prev != null && prev.target == finalUrl)
        memo[key] = Entry(finalUrl, System.currentTimeMillis(), confirmed, 0, tokenized)
    }

    private fun isBlocked(key: String): Boolean {
        val until = blockedUntil[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            blockedUntil.remove(key)
            return false
        }
        return true
    }

    private fun keyOf(url: HttpUrl): String =
        "${url.scheme}://${url.host}:${url.port}${url.encodedPath}"

    companion object {
        private const val TAG = "RedirectMemory"
        private const val DEFAULT_TTL_MS = 30 * 60 * 1000L
        /** 一次性 token 源的固化拉黑时长 */
        private const val BLOCK_MS = 10 * 60 * 1000L
    }
}
