package com.lizongying.mytv

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 远程 / 本地频道列表加载器。
 *
 * 支持两种配置格式，按文件内容自动识别：
 *  1. M3U  —— 以 #EXTM3U 开头（也兼容直接以 #EXTINF 开头），即 IPTV 通用格式
 *  2. JSON —— 以 { 开头，可完整表达 TV 的全部字段（pid / programType / needToken 等）
 *
 * 加载优先级（见 TVList.load / MainFragment）：
 *  本地文件 > 上次远程结果缓存 > 内置 TVList
 */
object TVSource {

    private const val TAG = "TVSource"

    /**
     * 远程列表地址。留空则不做网络拉取，只使用本地文件、缓存与内置列表。
     * 例：https://raw.githubusercontent.com/<you>/<repo>/main/tvlist.m3u
     */
    const val REMOTE_URL = "https://mytemple.fshby.cc/cn_all_staue.m3u8"

    /** 本地文件放在 App 外部私有目录，无需任何存储权限，adb push 即可 */
    private const val LOCAL_M3U = "tvlist.m3u"
    private const val LOCAL_JSON = "tvlist.json"

    /**
     * 也可以在外部目录放一个 tvlist.remote，内容只有一行 URL。
     * 它的优先级高于上面的常量，便于不重新编译就切换远程列表地址。
     */
    private const val REMOTE_FILE = "tvlist.remote"

    /** 远程拉取结果的缓存文件名（位于 cacheDir） */
    private const val CACHE_NAME = "tvlist.cache"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 文件位置

    /** 本地列表文件：优先 tvlist.m3u，其次 tvlist.json */
    fun localFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        File(dir, LOCAL_M3U).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        File(dir, LOCAL_JSON).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        return null
    }

    fun localDir(context: Context): String =
        context.getExternalFilesDir(null)?.absolutePath ?: "(不可用)"

    fun cacheFile(context: Context): File = File(context.cacheDir, CACHE_NAME)

    /**
     * 实际生效的远程地址：外部目录的 tvlist.remote（一行 URL）优先，其次 [REMOTE_URL]。
     * 返回空串表示不做远程拉取。
     */
    fun remoteUrl(context: Context): String {
        val dir = context.getExternalFilesDir(null)
        if (dir != null) {
            val f = File(dir, REMOTE_FILE)
            if (f.isFile) {
                val url = runCatching { f.readText().trim() }.getOrNull()
                if (!url.isNullOrEmpty()) {
                    return url
                }
            }
        }
        return REMOTE_URL
    }

    // ---------------------------------------------------------------- 加载

    /**
     * 同步加载本地文件或缓存，用于启动首屏（毫秒级，不阻塞用户）。
     * @return 解析结果；null 表示没有可用外部列表，应继续使用内置列表。
     */
    fun loadSync(context: Context): Map<String, List<TV>>? {
        localFile(context)?.let { f ->
            runCatching { parse(readText(f), "") }.getOrNull()?.let {
                Log.i(TAG, "load local ${f.name} -> ${it.size} groups")
                return it
            }
        }

        val cache = cacheFile(context)
        if (cache.isFile && cache.length() > 0) {
            runCatching { parse(readText(cache), "") }.getOrNull()?.let {
                Log.i(TAG, "load cache -> ${it.size} groups")
                return it
            }
        }
        return null
    }

    /** 拉取远程文本（阻塞，请在 IO 线程调用）。失败返回 null。 */
    fun fetch(url: String): String? {
        if (url.isEmpty()) return null
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "fetch failed http ${resp.code}")
                    return null
                }
                val bytes = resp.body?.bytes() ?: return null
                decode(bytes).also { Log.i(TAG, "fetch ok ${bytes.size} bytes") }
            }
        }.onFailure { Log.e(TAG, "fetch error", it) }.getOrNull()
    }

    /** 把远程内容写入缓存，下次启动可直接使用 */
    fun saveCache(context: Context, text: String) {
        runCatching { cacheFile(context).writeText(text) }
            .onFailure { Log.e(TAG, "save cache failed", it) }
    }

    /**
     * 把（探测过滤后的）频道分组序列化回标准 M3U 文本。
     * 缓存应保存过滤后的列表而非远程原文：否则下次启动秒开的是
     * 未探测的全量列表，用户会看到大量播放错误的频道。
     */
    fun toM3U(groups: Map<String, List<TV>>): String {
        val sb = StringBuilder("#EXTM3U\n")
        groups.forEach { (group, list) ->
            list.forEach { tv ->
                val url = tv.videoUrl.firstOrNull() ?: return@forEach
                val logo = (tv.logo as? String).orEmpty()
                val logoAttr = if (logo.isNotEmpty()) " tvg-logo=\"${logo}\"" else ""
                sb.append("#EXTINF:-1${logoAttr} group-title=\"${group}\",${tv.title}\n")
                sb.append(url).append('\n')
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 解析入口

    /** 按内容自动识别格式并解析；无法解析返回 null */
    fun parse(text: String, baseUrl: String): Map<String, List<TV>>? {
        val t = text.removePrefix("\uFEFF").trim()
        if (t.isEmpty()) return null
        return if (t.startsWith("{")) parseJson(t) else parseM3U(t, baseUrl)
    }

    // ---------------------------------------------------------------- M3U

    /** 匹配形如 group-title="央视" 的属性对 */
    private val attrRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    /**
     * 解析 IPTV 风格的 M3U：
     *   #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV1 综合
     *   http://a.com/cctv1.m3u8
     *
     * 映射：group-title -> 分组行 / 逗号后文字 -> 频道名 / 下一行 -> 播放地址
     * 注意频道名里不要含逗号，否则会被属性区分割符抢先截断。
     */
    private fun parseM3U(text: String, baseUrl: String): Map<String, List<TV>>? {
        val groups = LinkedHashMap<String, MutableList<TV>>()

        var title = ""
        var attrs: Map<String, String> = emptyMap()
        var group = ""

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF") -> {
                    val body = line.substringAfter("#EXTINF").removePrefix(":")
                    val comma = indexOfUnquotedComma(body)
                    val attrPart = if (comma >= 0) body.substring(0, comma) else body
                    title = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    attrs = attrRegex.findAll(attrPart)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    group = attrs["group-title"].orEmpty()
                }

                // 老式写法：分组单独一行
                line.startsWith("#EXTGRP") -> {
                    group = line.substringAfter("#EXTGRP").removePrefix(":").trim()
                }

                // 其余 # 开头的都是指令行
                line.startsWith("#") -> Unit

                else -> {
                    val url = resolveUrl(line, baseUrl)
                    // 过滤无效/播不了的地址（空、rtmp/rtsp 等本 App 无对应渲染器）
                    if (!isPlayableUrl(url)) {
                        Log.w(TAG, "skip invalid url: $line")
                        title = ""
                        attrs = emptyMap()
                        continue
                    }
                    val name = title.ifEmpty { url.substringAfterLast('/').substringBefore('?') }
                    val g = group.ifEmpty { "其他" }
                    groups.getOrPut(g) { mutableListOf() }.add(
                        TV(
                            0,
                            name,
                            attrs["tvg-name"] ?: attrs["tvg-id"] ?: name,
                            listOf(url),
                            g,
                            attrs["tvg-logo"].orEmpty(),
                            "",                                 // pid 留空 -> 直连播放
                            "",
                            ProgramType.Y_PROTO,
                            false,
                            false,
                            volume = 1.0F                       // 远程源统一满音量，避免默认 0.1 听感无声
                        )
                    )
                    title = ""
                    attrs = emptyMap()
                }
            }
        }
        return groups.ifEmpty { null }
    }

    /** 找第一个「不在引号内」的逗号，用于切分属性区与频道名 */
    private fun indexOfUnquotedComma(s: String): Int {
        var inQuote = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> inQuote = !inQuote
                ',' -> if (!inQuote) return i
            }
        }
        return -1
    }

    /** 相对路径按列表文件自身的 URL 解析 */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("rtmp") || url.startsWith("rtsp")
        ) return url
        if (baseUrl.isEmpty()) return url
        // 用 java.net.URL 解析，避免依赖 OkHttp 的 HttpUrl API（本项目是 OkHttp 3.x）
        return runCatching { URL(URL(baseUrl), url).toString() }.getOrDefault(url)
    }

    // ---------------------------------------------------------------- JSON

    /**
     * 解析 JSON 格式：
     * {
     *   "groups": [
     *     { "name": "央视", "channels": [
     *         { "title": "CCTV1 综合", "alias": "CCTV1", "logo": "http://a.com/1.png",
     *           "url": ["http://a.com/cctv1.m3u8"], "pid": "", "sid": "",
     *           "type": "Y_PROTO", "needToken": false, "mustToken": false }
     *     ]}
     *   ]
     * }
     * 顶层也接受直接就是数组的形式。url 可写单个字符串或字符串数组（数组即多源）。
     * pid 留空则直连播放；填上 pid 则走央视频 / 凤凰接口拉流。
     */
    private fun parseJson(text: String): Map<String, List<TV>>? {
        return runCatching {
            val root = JsonParser.parseString(text)
            val groupArray: JsonArray = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("groups") ?: return null
                else -> return null
            }

            val groups = LinkedHashMap<String, MutableList<TV>>()
            for (element in groupArray) {
                val obj = element.asJsonObject
                val groupName = obj.string("name") ?: "其他"
                val channels = obj.getAsJsonArray("channels") ?: continue
                for (c in channels) {
                    val o = c.asJsonObject
                    val title = o.string("title") ?: continue
                    val urlEl = o.get("url")
                    val urls: List<String> = when {
                        urlEl == null || urlEl.isJsonNull -> emptyList()
                        urlEl.isJsonArray -> urlEl.asJsonArray.mapNotNull { it.asStringOrNull() }
                        else -> listOfNotNull(urlEl.asStringOrNull())
                    }.filter { isPlayableUrl(it) }
                    // 没有可用地址且没有央视频 pid 的条目直接丢弃
                    if (urls.isEmpty() && (o.string("pid") ?: "").isEmpty()) {
                        Log.w(TAG, "skip invalid channel: $title")
                        continue
                    }
                    groups.getOrPut(groupName) { mutableListOf() }.add(
                        TV(
                            0,
                            title,
                            o.string("alias") ?: title,
                            urls,
                            groupName,
                            o.string("logo") ?: "",
                            o.string("pid") ?: "",
                            o.string("sid") ?: "",
                            programType(o.string("type")),
                            o.get("needToken")?.asBooleanOrFalse ?: false,
                            o.get("mustToken")?.asBooleanOrFalse ?: false,
                            volume = 1.0F
                        )
                    )
                }
            }
            groups.ifEmpty { null }
        }.onFailure { Log.e(TAG, "parse json error", it) }.getOrNull()
    }

    private fun programType(s: String?): ProgramType = when (s?.uppercase()) {
        "Y_JCE" -> ProgramType.Y_JCE
        "F" -> ProgramType.F
        else -> ProgramType.Y_PROTO
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonElement.asStringOrNull(): String? =
        takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

    private val JsonElement.asBooleanOrFalse: Boolean
        get() = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

    // ---------------------------------------------------------------- 频道清洗

    /** 只接受本 App 真正能播的地址：http(s)。rtmp/rtsp 没有对应渲染器，属于「异常频道」 */
    fun isPlayableUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("http://") || u.startsWith("https://")) {
            // 至少要有主机名
            return u.substringAfter("://").substringBefore('/').isNotBlank()
        }
        return false
    }

    // ---------------------------------------------------------------- 可用性探测

    /** 探测用客户端：短超时，避免个别死源拖慢整体 */
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 头部预取的字节数——m3u8 列表很小，取一小段足够判断存活 */
    private const val PROBE_RANGE = "bytes=0-2047"

    /** 单个地址是否可用：HTTP 成功且响应内容确实是媒体（m3u8 文本 / TS 字节流） */
    fun isAlive(url: String): Boolean {
        if (!isPlayableUrl(url)) return false
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", PROBE_RANGE)
                .header("User-Agent", "okhttp/3.14.9")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..206) return@use false
                val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                if (ct.contains("mpegurl") || ct.contains("mp2t")) return@use true
                // 严禁 body.bytes()：部分服务器无视 Range 头返回完整直播流，
                // 全量读取会无限吞内存直接 OOM。只限量读头部 2KB 判断内容。
                val head = readHead(resp, PROBE_BYTES) ?: return@use false
                isMediaPayload(ct, head)
            }
        }.onFailure { Log.d(TAG, "probe fail $url ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    /** 限量预读头部字节数 */
    private const val PROBE_BYTES = 2048

    /** 从响应体限量读取前 [max] 字节后立即关闭；流提前结束则返回实际读到的内容 */
    private fun readHead(resp: okhttp3.Response, max: Int): ByteArray? {
        val input = resp.body?.byteStream() ?: return null
        val buf = ByteArray(max)
        var off = 0
        try {
            while (off < max) {
                val n = input.read(buf, off, max - off)
                if (n < 0) break
                off += n
            }
        } finally {
            input.close()
        }
        return buf.copyOf(off)
    }

    /**
     * 响应内容是否是真正的流媒体数据。
     * 大量 IPTV 中转接口（如 api.php）对任意 GET 都返回 200 + 空 text/html，
     * 只看状态码会把它们误判为存活 -> 频道进入列表却播放错误。
     * 这里额外校验：媒体 Content-Type，或内容是 m3u8 文本 / MPEG-TS 同步字节。
     */
    private fun isMediaPayload(contentType: String?, body: ByteArray): Boolean {
        if (body.isEmpty()) return false
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("mpegurl") || ct.contains("mp2t") ||
            ct.contains("octet-stream") || ct.startsWith("video/") || ct.startsWith("audio/")
        ) {
            return true
        }
        // text/html 等页面类型必须靠内容自证是媒体，否则一律判死
        val head = String(body, 0, body.size.coerceAtMost(512), Charsets.ISO_8859_1)
        if (head.contains("#EXTM3U") || head.contains("#EXTINF") || head.contains("#EXT-X")) {
            return true
        }
        // MPEG-TS 包同步字节 0x47（每 188 字节一个，校验前几个包位提高置信度）
        return body.size > 376 && body[0].toInt() == 0x47 &&
            body[188].toInt() == 0x47 && body[376].toInt() == 0x47
    }

    /**
     * 并发探测并剔除「所有源都探测不通过」的频道，让异常频道不出现在列表里。
     *
     * - 有 pid 的频道（央视频等接口拉流）没有直连地址，跳过探测，原样保留
     * - 多源频道按顺序探测，命中后把可用地址提到首位
     * - 若全部频道都探测失败，视为网络异常，原样返回（避免断网时把列表清空）
     */
    suspend fun filterAlive(
        groups: Map<String, List<TV>>,
        concurrency: Int = 12,
    ): Map<String, List<TV>> {
        val all = groups.values.flatten()
        if (all.isEmpty()) return groups

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val done = AtomicInteger(0)
        val total = all.size
        val alive = coroutineScope {
            all.map { tv ->
                async(Dispatchers.IO) {
                    val ok = semaphore.withPermit { probeTV(tv) }
                    val n = done.incrementAndGet()
                    if (n % 20 == 0 || n == total) {
                        Log.i(TAG, "probe $n/$total")
                    }
                    tv to ok
                }
            }.map { it.await() }.filter { it.second }.map { it.first }
        }

        if (alive.isEmpty()) {
            // 全部探测失败多半是断网/被限流，不能因此把频道列表清空
            Log.w(TAG, "probe: all $total channels unreachable, keep list as is")
            return groups
        }

        Log.i(TAG, "probe done: alive ${alive.size}/$total, dropped ${total - alive.size}")

        // 用同一批对象做身份比对，避免数据类 equals 受字段改动影响
        val aliveSet = Collections.newSetFromMap(IdentityHashMap<TV, Boolean>())
        aliveSet.addAll(alive)

        val next = LinkedHashMap<String, List<TV>>()
        groups.forEach { (group, list) ->
            val kept = list.filter { aliveSet.contains(it) }
            if (kept.isNotEmpty()) {
                next[group] = kept
            }
        }
        return next
    }

    /** 探测单个频道的多个源；有一个通得过就返回 true（可用地址排到首位）。阻塞式，调用方在 IO 线程。 */
    private fun probeTV(tv: TV): Boolean {
        if (tv.pid.isNotEmpty()) {
            return true // 接口拉流频道，没有直连地址，不参与探测
        }
        val urls = tv.videoUrl
        if (urls.isEmpty()) return false
        val hit = urls.indexOfFirst { isAlive(it) }
        if (hit < 0) {
            Log.i(TAG, "unreachable: ${tv.title}")
            return false
        }
        if (hit > 0) {
            tv.videoUrl = listOf(urls[hit]) + urls.filterIndexed { i, _ -> i != hit }
        }
        return true
    }

    // ---------------------------------------------------------------- 编码

    private fun readText(file: File): String = decode(file.readBytes())

    /**
     * 中文 IPTV 源大量使用 GBK / GB2312，直接按 UTF-8 解会得到乱码。
     * 这里先尝试严格 UTF-8，失败再退到 GBK。
     */
    private fun decode(bytes: ByteArray): String {
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            runCatching { String(bytes, charset("GBK")) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
        }
    }
}
