package com.lizongying.mytv.api

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * 重定向落地地址固化：把稳定的 302 落地地址记住，下次直接请求，省掉一跳。
 *
 * 实测背景（MR622-DK 上用机身 curl 采样 24 个存活源）：
 *  - 约 1/3 直连（206/200），约 2/3 走 301/302 且落地跨主机（num_connects 实打实 2~3 次）；
 *  - 重定向类源比直连源慢 5~70 倍，建连占总耗时 40~45%；
 *  - HLS 每 2.5~5s 刷新一次 playlist，**每一轮刷新都在重复跳同一个 302**。
 *
 * 但采样同时发现一个决定性细节：**过半的 302 落地地址带时间签名**
 * （`?jsbt=&jsbk=`、`?tm=&key=`、`?u=&t=&k=`），每次请求都不同 ——
 * 固化这种地址必然失效，反而会变成「先失败一次再回退」的净亏损。
 *
 * 因此这里采用「自适应确认」策略：
 *  - 只有**连续两次观察到完全相同的落地地址**才标记为 confirmed 并开始直连；
 *  - 签名型地址每次都不一样，永远停在未确认状态，永远不会被使用（零额外请求）；
 *  - 静态 CDN 地址第二次就被确认，此后每次 playlist 刷新都省掉一跳；
 *  - 原始地址自带 query 时不做固化（改写会丢掉原参数）；
 *  - 直连失败（非 2xx / 异常）立即清除映射并回退原始地址；
 *  - 映射带 TTL，避免 CDN 调度变化后一直用旧落地地址。
 */
class RedirectMemory(
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : Interceptor {

    private class Entry(val target: HttpUrl, val ts: Long, val confirmed: Boolean)

    /** key = scheme://host:port/path（刻意不含 query：播放地址的 token 每轮都会变） */
    private val memo = ConcurrentHashMap<String, Entry>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = keyOf(request.url)

        // 原始地址带参数时不动它，避免改写后丢参数
        val usable = request.url.query == null
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
                    // 落地地址仍然有效，刷新时间戳
                    memo[key] = Entry(entry.target, System.currentTimeMillis(), true)
                    return resp
                }
                resp?.close()
                memo.remove(key)
            }
        }

        val response = chain.proceed(request)
        if (usable) learn(key, response)
        return response
    }

    /**
     * 学习一次跳转结果。
     *
     * 只有「本次落地地址与上次观测到的完全相同」才确认 —— 时间签名地址每次不同，
     * 因此永远不会被确认，也就永远不会被拿去直连（这是本实现不引入净亏损的关键）。
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
        val prev = memo[key]
        // 连续两次一致 -> 确认稳定，可固化；否则重置为「待观察」
        val confirmed = prev != null && prev.target == finalUrl
        memo[key] = Entry(finalUrl, System.currentTimeMillis(), confirmed)
    }

    private fun keyOf(url: HttpUrl): String =
        "${url.scheme}://${url.host}:${url.port}${url.encodedPath}"

    companion object {
        private const val TAG = "RedirectMemory"
        private const val DEFAULT_TTL_MS = 30 * 60 * 1000L
    }
}
