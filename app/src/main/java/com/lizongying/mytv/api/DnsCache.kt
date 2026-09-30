package com.lizongying.mytv.api

import android.util.Log
import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 带 TTL 与容量上限的 DNS 缓存。
 *
 * 换成 IPTV 直播链路后，每次换台都要为一批域名做解析，实测单次 29~61ms，
 * 正好落在换台首帧的关键路径上。但**不能**沿用原来那版「无 TTL 的永久缓存」：
 * IPTV 源换 IP 很常见，永久缓存会一直连旧 IP，等于人为制造连接失败与卡顿。
 *
 * 因此这里做两个约束：
 *  - TTL（默认 5 分钟）：过期即重新解析，兼顾命中率与可用性；
 *  - 容量上限（默认 256 条）：超过就整体清空，避免长时间运行后表无限膨胀。
 */
class DnsCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : Dns {

    private class Entry(val addrs: List<InetAddress>, val ts: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { e ->
            if (now - e.ts <= ttlMs && e.addrs.isNotEmpty()) {
                return e.addrs
            }
            cache.remove(hostname)
        }

        val addrs = InetAddress.getAllByName(hostname).toList()
        if (addrs.isNotEmpty()) {
            if (cache.size >= maxEntries) {
                // 简易容量控制：宁可整体清空也不让表无限增长
                Log.i(TAG, "cache full ($maxEntries), clear")
                cache.clear()
            }
            cache[hostname] = Entry(addrs, now)
        }
        return addrs
    }

    companion object {
        private const val TAG = "DnsCache"
        private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        private const val DEFAULT_MAX_ENTRIES = 256

        /** 全局共享实例：列表拉取、探测与播放器走同一份缓存，收益才叠加得起来 */
        val shared: DnsCache by lazy { DnsCache() }
    }
}
