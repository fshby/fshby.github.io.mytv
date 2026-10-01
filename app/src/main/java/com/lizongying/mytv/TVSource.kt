package com.lizongying.mytv

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lizongying.mytv.api.SourceProfile
import com.lizongying.mytv.api.SourceProfiles
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
import java.io.IOException
import java.net.URL
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 远程 / 本地频道列表加载器。
 *
 * 支持两种配置格式，按文件内容自动识别：
 *  1. M3U  —— 以 #EXTM3U 开头（也兼容直接以 #EXTINF 开头），即 IPTV 通用格式
 *  2. JSON —— 以 { 开头，可完整表达 TV 的全部字段（pid / programType / needToken 等）
 *
 * 加载优先级（见 TVList.load / MainFragment）：
 *  本地文件 > 上次远程结果缓存 > 随包内置快照 > 内置 TVList
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

    /**
     * 随包内置的频道列表快照（app/src/main/assets）。
     *
     * 兜底场景：首次安装还没有缓存，或者远程列表因为网络 / 系统时钟问题拉不下来。
     * 此时若直接回退内置表，用户看到的是央视频接口源——而该接口现已全面失效，
     * 满屏都是「认证状态错误」；换成这份 IPTV 快照，离线开机也能直接看。
     * 快照随版本发布刷新，联网时会被远程列表覆盖（优先级见 [loadSync]）。
     */
    private const val SNAPSHOT_ASSET = "tvlist.snapshot.m3u"

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
     * 同步加载本地文件、缓存或内置快照，用于启动首屏（毫秒级，不阻塞用户）。
     *
     * 优先级：本地文件（用户手动 push，最高）> 上次远程缓存 > 随包内置快照。
     * @return 解析结果；null 表示三者都不可用，应回退内置表。
     */
    fun loadSync(context: Context): Map<String, List<TV>>? {
        localFile(context)?.let { f ->
            val t0 = System.currentTimeMillis()
            runCatching { parse(readText(f), "") }.getOrNull()?.let {
                Log.i(TAG, "load local ${f.name} -> ${it.size} groups (${elapsed(t0)}ms)")
                return it
            }
        }

        val cache = cacheFile(context)
        if (cache.isFile && cache.length() > 0) {
            val t0 = System.currentTimeMillis()
            runCatching { parse(readText(cache), "") }.getOrNull()?.let {
                Log.i(TAG, "load cache -> ${it.size} groups (${elapsed(t0)}ms)")
                return it
            }
        }

        // 内置快照兜底：既没有本地文件也没有缓存（首次安装、缓存被清、或
        // 上次启动时远程就拉不下来因而没能写缓存）时，用随包快照而不是内置表。
        loadSnapshot(context)?.let { return it }
        return null
    }

    /**
     * 读取随包内置的频道列表快照。
     *
     * 不参与探测：快照是「离线/异常时的可用列表」，联网后远程列表会正常覆盖它；
     * 对快照跑一遍全量探测既慢（几百条源）又没有意义——用户此刻多半正在看第一个频道。
     */
    fun loadSnapshot(context: Context): Map<String, List<TV>>? {
        val t0 = System.currentTimeMillis()
        val bytes = runCatching {
            context.assets.open(SNAPSHOT_ASSET).use { it.readBytes() }
        }.getOrNull() ?: return null
        return runCatching { parse(decode(bytes), "") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
            Log.i(TAG, "load snapshot -> ${it.size} groups (${elapsed(t0)}ms)")
            it
        }
    }

    private fun elapsed(from: Long) = System.currentTimeMillis() - from

    /** 远程拉取结果 */
    data class FetchResult(
        /** 拉到的原文；304 或失败时为 null */
        val text: String? = null,
        /** true 表示服务器回了 304：内容未变化，可直接复用现有列表 */
        val notModified: Boolean = false,
        val etag: String = "",
        val lastModified: String = "",
        /**
         * 失败原因是 TLS 证书校验 —— 几乎必然是系统时钟不在证书有效期内。
         * 调用方据此提示用户校准时间，而不是让用户以为「App 坏了」。
         */
        val certError: Boolean = false,
    )

    /**
     * 拉取远程文本（阻塞，请在 IO 线程调用）。
     *
     * 传入上次的 etag / lastModified 时走条件请求：服务器未变化会直接回 304，
     * 省掉一次整表下载与解析。服务器不支持时退化为普通 GET，不影响功能。
     */
    fun fetch(url: String, etag: String = "", lastModified: String = ""): FetchResult {
        if (url.isEmpty()) return FetchResult()
        var certError = false
        return runCatching {
            val builder = Request.Builder().url(url)
            if (etag.isNotEmpty()) builder.header("If-None-Match", etag)
            if (lastModified.isNotEmpty()) builder.header("If-Modified-Since", lastModified)
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    Log.i(TAG, "fetch not modified (304)")
                    return@use FetchResult(
                        notModified = true,
                        etag = resp.header("ETag").orEmpty().ifEmpty { etag },
                        lastModified = resp.header("Last-Modified").orEmpty().ifEmpty { lastModified },
                    )
                }
                if (!resp.isSuccessful) {
                    Log.e(TAG, "fetch failed http ${resp.code}")
                    return@use FetchResult()
                }
                val bytes = resp.body?.bytes() ?: return@use FetchResult()
                Log.i(TAG, "fetch ok ${bytes.size} bytes")
                FetchResult(
                    text = decode(bytes),
                    etag = resp.header("ETag").orEmpty(),
                    lastModified = resp.header("Last-Modified").orEmpty(),
                )
            }
        }.onFailure {
            certError = isCertificateError(it)
            Log.e(TAG, "fetch error (cert=$certError)", it)
        }.getOrDefault(FetchResult(certError = certError))
    }

    /**
     * 判断异常是否由 TLS 证书校验失败引起。
     *
     * 系统时间不在证书有效期内时，Java/Android 会抛出
     * `CertificateNotYetValidException` / `CertificateExpiredException`（均为
     * [CertificateException] 子类），通常被包在 `SSLHandshakeException: Chain validation failed`
     * 里。异常链长度有限，逐层找即可。
     */
    private fun isCertificateError(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 12) {
            if (t is CertificateException ||
                t is CertPathValidatorException ||
                t is SSLHandshakeException ||
                t is SSLPeerUnverifiedException
            ) {
                return true
            }
            if (t.cause === t) break
            t = t.cause
        }
        return false
    }

    /**
     * 校准用时间源：纯 HTTP、响应必带 `Date` 头，国内可达。
     * 顺序即优先级，第一个拿到的就用。
     */
    private val TIME_SOURCES = listOf(
        "http://www.baidu.com/",
        "http://www.qq.com/",
    )

    /**
     * 时钟探测客户端：**不跟随重定向**。
     *
     * 重定向目标基本都是 HTTPS，正好是这里要绕开的东西——证书校验失败时，
     * 跟随重定向等于又把请求掐死一次。只读第一跳响应头即可。
     */
    private val clockClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    /**
     * 系统时钟偏斜探测：返回「本机时间 − 服务器时间」（毫秒，正数表示本机偏快）；
     * 所有时间源都不可达时返回 null（无法判断，调用方不应据此下结论）。
     *
     * 为什么不能用业务接口来判断：`https://mytemple.fshby.cc` 用的是 Let's Encrypt
     * 三个月期证书，时钟一偏，**请求在 TLS 握手阶段就失败了，根本拿不到任何响应**。
     * 而 HTTP 请求不过 TLS，`Date` 头照样可读——这是时钟坏掉时唯一还可信的途径。
     *
     * 只在远程列表拉取失败后才调用一次，成功路径上没有额外开销。
     */
    fun clockSkewMs(): Long? {
        for (url in TIME_SOURCES) {
            val server = runCatching {
                clockClient.newCall(Request.Builder().url(url).head().build())
                    .execute().use { it.headers.getDate("Date") }
            }.getOrNull() ?: continue
            return System.currentTimeMillis() - server.time
        }
        return null
    }

    // ---------------------------------------------------------------- 缓存元信息

    /**
     * 缓存元信息。
     *
     * [sig] 是上次远程原文的内容签名——重启时若拉到的原文签名与之一致，
     * 说明列表没变，可以直接复用 [CACHE_NAME] 里已探测过滤好的结果。
     * [probeTs] 是上次「全量探测完成」的时间，用来判断可用性结论是否还新鲜：
     * 内容没变 + 结论新鲜 → 这次启动连解析和探测都可以跳过；
     * 内容没变但结论过期（长时间没开机）→ 必须重探一遍，否则死源会一直留在列表里。
     */
    data class CacheMeta(
        /** 上次确认列表内容的时间 */
        val ts: Long = 0L,
        /** 上次全量探测完成的时间 */
        val probeTs: Long = 0L,
        val url: String = "",
        val etag: String = "",
        val lastModified: String = "",
        val sig: String = "",
    )

    private const val META_NAME = "tvlist.cache.meta"

    private fun metaFile(context: Context): File = File(context.cacheDir, META_NAME)

    fun readCacheMeta(context: Context): CacheMeta {
        val f = metaFile(context)
        if (!f.isFile || f.length() == 0L) return CacheMeta()
        return runCatching {
            val lines = f.readText().split('\n')
            fun at(i: Int) = lines.getOrNull(i)?.trim().orEmpty()
            CacheMeta(
                ts = at(0).toLongOrNull() ?: 0L,
                probeTs = at(1).toLongOrNull() ?: 0L,
                url = at(2),
                etag = at(3),
                lastModified = at(4),
                sig = at(5),
            )
        }.getOrElse { CacheMeta() }
    }

    fun writeCacheMeta(context: Context, meta: CacheMeta) {
        runCatching {
            metaFile(context).writeText(
                listOf(
                    meta.ts, meta.probeTs, meta.url, meta.etag, meta.lastModified, meta.sig
                ).joinToString("\n")
            )
        }.onFailure { Log.e(TAG, "write cache meta failed", it) }
    }

    /** 上次探测结论是否还在 [maxAgeMs] 内 —— 只有它新鲜时才可以跳过重探 */
    fun isProbeFresh(context: Context, maxAgeMs: Long): Boolean {
        val ts = readCacheMeta(context).probeTs
        return ts > 0L && System.currentTimeMillis() - ts < maxAgeMs
    }

    /** 远程原文的轻量签名，用于判断列表有没有变化 */
    fun contentSig(text: String): String =
        if (text.isEmpty()) "" else "${text.length}:${text.hashCode()}"

    /** 把远程内容写入缓存，下次启动可直接使用 */
    fun saveCache(context: Context, text: String) {
        runCatching { cacheFile(context).writeText(text) }
            .onFailure { Log.e(TAG, "save cache failed", it) }
    }

    /**
     * 把（探测过滤后的）频道分组序列化回标准 M3U 文本。
     * 缓存应保存过滤后的列表而非远程原文：否则下次启动秒开的是
     * 未探测的全量列表，用户会看到大量播放错误的频道。
     *
     * 多源频道会为每个源写一条 EXTINF —— 解析侧（parseM3U）按频道名重新合并回多源，
     * 这样「写缓存 → 读缓存」的往返不会像以前那样把多源压成单源。
     */
    fun toM3U(groups: Map<String, List<TV>>): String {
        val sb = StringBuilder("#EXTM3U\n")
        for ((group, list) in groups) {
            for (tv in list) {
                val logo = (tv.logo as? String).orEmpty()
                val logoAttr = if (logo.isNotEmpty()) " tvg-logo=\"${logo}\"" else ""
                for (url in tv.videoUrl) {
                    sb.append("#EXTINF:-1${logoAttr} group-title=\"${group}\",${tv.title}\n")
                    sb.append(url).append('\n')
                }
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

    /**
     * EXTINF 里需要抽取的属性。只保留实际会用到的四个键，
     * 避免给每一行都建一个 HashMap（1000+ 行的缓存文件下这是解析热路径）。
     */
    private class M3uAttrs {
        var group: String = ""
        var tvgName: String = ""
        var tvgId: String = ""
        var tvgLogo: String = ""

        fun reset() {
            group = ""
            tvgName = ""
            tvgId = ""
            tvgLogo = ""
        }
    }

    /**
     * 手写属性扫描：`key="value"` 逐对读取，只认需要的四个键。
     *
     * 原实现是 `attrRegex.findAll(part).associate { ... }`，每行一次正则 + 一个 HashMap，
     * 实测解析 1000+ 条缓存要 ~2.9s（占开机时间近三分之一）；改成顺序扫描后开销基本消失。
     */
    private fun scanAttrs(s: String, out: M3uAttrs) {
        out.reset()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == ',')) i++
            val keyStart = i
            while (i < n && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
            if (i == keyStart) {
                i++
                continue
            }
            val key = s.substring(keyStart, i)
            if (i >= n || s[i] != '=') continue
            i++
            if (i >= n) break
            val quote = s[i]
            if (quote != '"' && quote != '\'') continue
            i++
            val valueStart = i
            while (i < n && s[i] != quote) i++
            val value = s.substring(valueStart, i)
            i++
            when {
                key.equals("group-title", true) -> out.group = value
                key.equals("tvg-name", true) -> out.tvgName = value
                key.equals("tvg-id", true) -> out.tvgId = value
                key.equals("tvg-logo", true) -> out.tvgLogo = value
            }
        }
    }

    /**
     * 解析 IPTV 风格的 M3U：
     *   #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV1 综合
     *   http://a.com/cctv1.m3u8
     *
     * 映射：group-title -> 分组行 / 逗号后文字 -> 频道名 / 下一行 -> 播放地址
     * 注意频道名里不要含逗号，否则会被属性区分割符抢先截断。
     *
     * **同名频道会合并成一个 TV 的多源列表**（键 = 分组 + 归一化频道名）。
     * 生产列表里 CCTV1 这类频道有 20 条源，原实现是「一条 URL 一个 TV」，
     * 随后被 TVList 的同名去重丢掉 19 条 —— 备用源在进入播放器之前就消失了，
     * rotateToNextSource() 因此成了死代码。合并后多源才真正可用。
     */
    private fun parseM3U(text: String, baseUrl: String): Map<String, List<TV>>? {
        val groups = LinkedHashMap<String, MutableList<TV>>()
        // 分组 + 归一化频道名 -> 已建好的 TV（用于把后续同名 URL 追加成备用源）
        val byKey = HashMap<String, TV>()

        var title = ""
        var group = ""
        val attrs = M3uAttrs()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF") -> {
                    val body = line.substringAfter("#EXTINF").removePrefix(":")
                    val comma = indexOfUnquotedComma(body)
                    val attrPart = if (comma >= 0) body.substring(0, comma) else body
                    title = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    scanAttrs(attrPart, attrs)
                    group = attrs.group
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
                        attrs.reset()
                        continue
                    }
                    val name = title.ifEmpty { url.substringAfterLast('/').substringBefore('?') }
                    val g = group.ifEmpty { "其他" }
                    val key = g + '\u0000' + mergeKey(name)
                    val exist = byKey[key]
                    if (exist != null) {
                        // 同一频道再来一个源：追加为备用源（URL 级去重，列表里同源重复很常见）
                        if (!exist.videoUrl.contains(url)) {
                            exist.videoUrl = exist.videoUrl + url
                        }
                    } else {
                        val tv = TV(
                            0,
                            name,
                            attrs.tvgName.ifEmpty { attrs.tvgId.ifEmpty { name } },
                            listOf(url),
                            g,
                            attrs.tvgLogo,
                            "",                                 // pid 留空 -> 直连播放
                            "",
                            ProgramType.Y_PROTO,
                            false,
                            false,
                            volume = 1.0F                       // 远程源统一满音量，避免默认 0.1 听感无声
                        )
                        byKey[key] = tv
                        groups.getOrPut(g) { mutableListOf() }.add(tv)
                    }
                    title = ""
                    attrs.reset()
                }
            }
        }
        return groups.ifEmpty { null }
    }

    private val bracketRegex = Regex("""[\[\(（【][^\]\)）】]*[\]\)）】]""")
    private val qualityRegex = Regex(
        """(1080p|720p|576p|480p|360p|4k|8k|uhd|fhd|hd|sd|高清|超清|标清|蓝光|原画)""",
        RegexOption.IGNORE_CASE
    )
    private val punctuationRegex = Regex("""[\s\-_.·、/]+""")

    /**
     * 合并同频道用的归一化键，与 TVList.dedupeKey 保持同一套规则
     * （去括号、去画质词、去「频道」、去标点），保证「合并」与「去重」口径一致：
     * parseM3U 合并出来的键，到 normalize 阶段不会又被判成重复而丢弃。
     */
    private fun mergeKey(title: String): String {
        var s = title.trim().lowercase()
        s = bracketRegex.replace(s, "")
        s = qualityRegex.replace(s, "")
        s = s.replace("频道", "")
        return punctuationRegex.replace(s, "")
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

    private const val UA = "okhttp/3.14.9"

    /** 探测用客户端：短超时，避免个别死源拖慢整体 */
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 吞吐实测专用客户端。
     *
     * 读超时故意设得比预算更短：慢源读到 1.6s 就中断，[measureSpeed] 用
     * 「已读字节 ÷ 已用时间」照样算出速率——**慢本身就是结论**，不必等它读完。
     */
    private val speedClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(SPEED_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 预读上限。实测 5 片 playlist ≈ 700B、20 片 ≈ 2.5KB，4KB 已能覆盖绝大多数全文；
     * 没读全（[Head.truncated]）时再补抓一次 [PLAYLIST_MAX_BYTES]。
     */
    private const val PROBE_BYTES = 4096

    private const val PROBE_RANGE = "bytes=0-${PROBE_BYTES - 1}"

    /** 预读被截断时补抓 playlist 的上限 */
    private const val PLAYLIST_MAX_BYTES = 32 * 1024

    /** 吞吐实测：单次最多读多少字节 / 最多花多少毫秒 */
    private const val SPEED_BYTES = 384 * 1024
    private const val SPEED_BUDGET_MS = 1_000L
    private const val SPEED_READ_TIMEOUT_MS = 1_600L

    /** 一个频道凑够几个可用源就不再往下测（留 1 个主用 + 1 个备用足够） */
    private const val PROBE_SOURCES_ENOUGH = 2

    /** 单频道最多测几个源：多源频道（CCTV1 有 20 条）全测会让探测时间失控 */
    private const val PROBE_SOURCES_MAX = 6

    /** 进入吞吐实测与排序的候选源上限：测太多会把探测时间拖成几分钟 */
    private const val PROBE_CANDIDATES = 3

    /** 单个地址是否可用：HTTP 成功且响应内容确实是媒体（m3u8 文本 / TS 字节流） */
    fun isAlive(url: String): Boolean = probeHead(url) != null

    /**
     * 探测单个地址，返回「建连 + 首字节」耗时（毫秒）；不可用返回 null。
     *
     * 注意：这个耗时**不再**用于多源排序（playlist 建连快慢与分片吞吐无关，
     * 实测同一列表内两个源能差 500 倍），排序改由 [measureSpeed] 的实测速率决定。
     * 它现在只用于日志与「同一档位时」的次级比较。
     */
    fun probeUrl(url: String): Long? = probeHead(url)?.costMs

    /** 探测结果：建连耗时 + 预读到的头部字节 */
    private class Head(val costMs: Long, val body: ByteArray, val truncated: Boolean)

    /**
     * 探测单个地址：HTTP 成功 + 内容确实是媒体，返回建连耗时与预读头部。
     *
     * 严禁 body.bytes()：部分服务器无视 Range 头返回完整直播流，
     * 全量读取会无限吞内存直接 OOM（真机崩溃循环实证）。只限量读头部。
     *
     * 顺带把预读到的字节留作 playlist 解析用（大多数 playlist 一次就够），
     * 不给每条源多添加一次网络往返。
     */
    private fun probeHead(url: String): Head? {
        if (!isPlayableUrl(url)) return null
        val t0 = System.nanoTime()
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Range", PROBE_RANGE)
                .header("User-Agent", UA)
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..206) return@use null
                val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                val head = readHead(resp, PROBE_BYTES) ?: return@use null
                if (!isMediaPayload(ct, head)) return@use null
                Head(costMs(t0), head, head.size >= PROBE_BYTES)
            }
        }.getOrDefault(null)
    }

    private fun costMs(fromNano: Long): Long = (System.nanoTime() - fromNano) / 1_000_000

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

    // ---------------------------------------------------------------- HLS 结构画像

    /** 一个 playlist 的结构信息；分片地址已补全为绝对地址 */
    private class HlsInfo(
        /** 分片列表所在 playlist 的地址（master 会下钻一层，可能与频道地址不同） */
        val mediaUrl: String,
        val targetDurationMs: Long,
        val windowMs: Long,
        val segments: Int,
        val segUrls: List<String>,
        val segDurations: List<Double>,
        /** playlist 声明的平均码率 KB/s；0 表示未声明 */
        val declaredKbps: Long,
    )

    private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""")

    /**
     * 解析 playlist 文本，抽出「目标时长 / 窗口长度 / 分片数 / 分片地址 / 声明码率」。
     *
     * 遇到 master playlist（含 `#EXT-X-STREAM-INF`）时按 BANDWIDTH 选最高档下钻一层——
     * 实测 209 个可用源里有 27 个是 master，而分片地址与 `#EXT-X-TARGETDURATION`
     * 只存在于 media playlist。下钻最多一层，避免被自引用地址套住。
     */
    private fun parseHls(text: String, baseUrl: String, depth: Int = 0): HlsInfo? {
        if (!text.contains("#EXTM3U")) return null

        if (text.contains("#EXT-X-STREAM-INF")) {
            if (depth >= 1) return null
            var bestVariant: String? = null
            var bestBw = -1L
            var pendingBw = -1L
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pendingBw =
                        bandwidthRegex.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                } else if (line.isNotEmpty() && !line.startsWith("#")) {
                    if (pendingBw > bestBw) {
                        bestBw = pendingBw
                        bestVariant = line
                    }
                    pendingBw = -1L
                }
            }
            val variant = bestVariant ?: return null
            val variantUrl = resolveUrl(variant, baseUrl)
            val sub = fetchText(variantUrl, PLAYLIST_MAX_BYTES) ?: return null
            return parseHls(sub, variantUrl, depth + 1)
        }

        var targetMs = 0L
        var windowMs = 0L
        var declared = 0L
        val segUrls = ArrayList<String>(32)
        val segDurations = ArrayList<Double>(32)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    val sec = line.substringAfter(':').trim().toDoubleOrNull()
                    if (sec != null && sec > 0) targetMs = (sec * 1000).toLong()
                }
                // 平均分片码率（kbps）-> KB/s
                line.startsWith("#EXT-X-BITRATE:") -> {
                    val kbps = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    if (kbps > 0) declared = kbps / 8
                }
                line.startsWith("#EXTINF:") -> {
                    // #EXTINF:4.000, 或 #EXTINF:4.000,标题
                    val sec =
                        line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    segDurations.add(sec)
                    windowMs += (sec * 1000).toLong()
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> segUrls.add(resolveUrl(line, baseUrl))
            }
        }
        if (segUrls.isEmpty()) return null
        if (targetMs <= 0L) {
            // 没写 TARGETDURATION 就退而用分片平均时长，至少不让偏移推导失去依据
            val avg = segDurations.filter { it > 0 }.average()
            if (!avg.isNaN()) targetMs = (avg * 1000).toLong()
        }
        // 分片数只算「真正占时间」的那些：0 时长分片既不能作为起播位置，
        // 也不该让一个实际只有 2 片的窗口被当成 3 片从而逃过 tinyWindow 降权
        val realSegments = segDurations.count { it > 0.0 }
        return HlsInfo(baseUrl, targetMs, windowMs, realSegments, segUrls, segDurations, declared)
    }

    /** 取回 playlist 全文（限量）；失败返回 null */
    private fun fetchText(url: String, max: Int): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${max - 1}")
            .header("User-Agent", UA)
            .get()
            .build()
        probeClient.newCall(req).execute().use { resp ->
            if (resp.code !in 200..206) return@use null
            readHead(resp, max)?.let { decode(it) }
        }
    }.getOrDefault(null)

    /** 由探测头部（必要时补抓）得到 HLS 结构 */
    private fun hlsInfo(url: String, head: Head): HlsInfo? {
        val text = if (head.truncated) {
            fetchText(url, PLAYLIST_MAX_BYTES) ?: return null
        } else {
            decode(head.body)
        }
        return parseHls(text, url)
    }

    /** 实测速率结果 */
    private class Speed(val kbps: Long, val totalBytes: Long)

    /**
     * 限量下载一个分片，返回实测速率（KB/s）与分片总字节数。
     *
     * 只读 [SPEED_BYTES] 或最多花 [SPEED_BUDGET_MS]。慢源会在时间预算内被截断，
     * 这时「已读字节 ÷ 已用时间」依然诚实地反映了它追不上实时码率这个事实。
     *
     * [Speed.totalBytes] 用来推算实时码率需求：服务器支持 Range 时回 206，
     * 此时 Content-Length 只是本次区间长度，必须改从 `Content-Range: a-b/总长` 取总长。
     */
    private fun measureSpeed(segUrl: String): Speed {
        if (!isPlayableUrl(segUrl)) return Speed(0L, 0L)
        var read = 0
        var fullBytes = 0L
        var bodyStartNs = System.nanoTime()
        try {
            val req = Request.Builder()
                .url(segUrl)
                .header("Range", "bytes=0-${SPEED_BYTES - 1}")
                .header("User-Agent", UA)
                .get()
                .build()
            speedClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..206) return Speed(0L, 0L)
                val contentRange = resp.header("Content-Range")
                fullBytes = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: 0L
                if (fullBytes <= 0L) {
                    fullBytes = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                }
                val input = resp.body?.byteStream() ?: return Speed(0L, fullBytes)
                val buf = ByteArray(16 * 1024)
                bodyStartNs = System.nanoTime()
                try {
                    while (read < SPEED_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        read += n
                        if ((System.nanoTime() - bodyStartNs) / 1_000_000 >= SPEED_BUDGET_MS) break
                    }
                } finally {
                    input.close()
                }
            }
        } catch (e: IOException) {
            // 读超时 / 连接中断：已读到的字节依旧能反映速率，不丢弃
        }
        if (read <= 0) return Speed(0L, fullBytes)
        val ms = ((System.nanoTime() - bodyStartNs) / 1_000_000).coerceAtLeast(1L)
        return Speed(read.toLong() * 1000 / ms / 1024, fullBytes)
    }

    /** 探测候选源：结构 + 实测质量，用于频道内排序 */
    private class Candidate(val url: String, val costMs: Long, val info: HlsInfo) {
        var kbps = 0L
        var demandKbps = 0L

        /** 排序用的画像，在吞吐实测之后填充 */
        lateinit var profile: SourceProfile
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
     * - 多源频道按实测耗时排序写回（最快的当默认源，次快的当 failover 备用源）
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

    /**
     * 探测单个频道的多个源。返回 true 表示至少一个源可用。
     *
     * 两轮真机实测后收敛下来的流程：
     *  1. 对最多 [PROBE_CANDIDATES] 个候选做**结构画像**（目标时长 / 窗口 / 分片数）；
     *  2. 再对候选做**限量吞吐实测**，用「下载速率 ÷ 实时码率需求」定序 ——
     *     原来拿 playlist 建连耗时刻画流畅度是错的，实测同一列表内两个源能差 500 倍，
     *     playlist 快的那个分片可能只有 61KB/s；
     *  3. 窗口过小的源（≤2 片 / ≤8s）降到后面：这类源无论如何调参都只剩 ≤1 片余量；
     *  4. 只保留排序后的前 [PROBE_SOURCES_ENOUGH] 条，并把画像写入 [SourceProfiles]，
     *     供 PlayerFragment 推算起播偏移；
     *  5. 解析不出 HLS 结构、但确实过得了存活校验的源（直连 TS / 渐进式流）作为兜底，
     *     只在 HLS 名额有剩时补在末尾——这类源无法画像，能播但排不出优劣。
     *     **不能因为解析不出 playlist 就丢源**：若一个频道的源全属这类，整条频道会消失。
     *
     * 阻塞式，调用方在 IO 线程。
     */
    private fun probeTV(tv: TV): Boolean {
        if (tv.pid.isNotEmpty()) {
            return true // 接口拉流频道，没有直连地址，不参与探测
        }
        val urls = tv.videoUrl
        if (urls.isEmpty()) return false

        val cands = ArrayList<Candidate>(PROBE_CANDIDATES)
        // 非 HLS 但确实是媒体的源（直连 MPEG-TS / 渐进式流）：解析不出分片结构，
        // 无法画像与排序，但**能播**。留作兜底，否则「所有源都解析不出 playlist」
        // 的频道会被整条剔除——那是比「播得不够顺」严重得多的回归。
        val fallbacks = ArrayList<Pair<String, Long>>(4)
        var probed = 0
        for (u in urls) {
            if (probed >= PROBE_SOURCES_MAX) break
            probed++
            val head = probeHead(u) ?: continue
            val info = hlsInfo(u, head)
            if (info == null) {
                fallbacks.add(u to head.costMs)
                continue
            }
            cands.add(Candidate(u, head.costMs, info))
            if (cands.size >= PROBE_CANDIDATES) break
        }
        if (cands.isEmpty()) {
            // 一个 HLS 源都没有：退回「按建连耗时排序」的老办法，保住频道
            if (fallbacks.isEmpty()) {
                // 不逐条打日志：一次全量探测有几百个死源，
                // 逐条 Log 在弱盒子上本身就是可观开销
                return false
            }
            tv.videoUrl = fallbacks.sortedBy { it.second }
                .take(PROBE_SOURCES_ENOUGH)
                .map { it.first }
            return true
        }

        for (c in cands) {
            val segUrls = c.info.segUrls
            val segDurations = c.info.segDurations
            // 取「次新片」：正是起播点之后最先被消费到的那一片，最能代表换台瞬间的真实体验。
            // 跳过 0 时长分片（读它没有任何意义）
            val usable = segDurations.indices.filter { segDurations[it] > 0.0 }
            val i = if (usable.size >= 2) usable[usable.size - 2] else -1
            val durSec = if (i >= 0) segDurations[i] else 0.0
            val speed = if (i >= 0) measureSpeed(segUrls[i]) else Speed(0L, 0L)
            c.kbps = speed.kbps
            c.demandKbps = when {
                speed.totalBytes > 0L && durSec > 0.5 ->
                    (speed.totalBytes / 1024.0 / durSec).toLong()
                // 服务器不回 Content-Length（chunked / 无视 Range）时退回 playlist 声明的码率
                else -> c.info.declaredKbps
            }
            // 低富余源（实测速率 < 2× 需求，如跨洋中转）：垫片抬到窗口 2/3，
            // 用实时性换流畅（切台初期薄垫子会被速度波动反复击穿）
            val lowHeadroom =
                c.kbps > 0L && c.demandKbps > 0L &&
                    c.kbps.toFloat() / c.demandKbps < SourceProfile.LOW_HEADROOM_MAX
            c.profile = SourceProfile(
                targetDurationMs = c.info.targetDurationMs,
                windowMs = c.info.windowMs,
                segments = c.info.segments,
                kbps = c.kbps,
                demandKbps = c.demandKbps,
                // 按真实分片边界算起播偏移，随画像一起落盘（见 SourceProfile.targetOffsetFor）
                targetOffsetMs = SourceProfile.targetOffsetFor(c.info.segDurations, lowHeadroom),
                // 实播重缓冲史跨探测周期保留：快照归零会把「实播证实会卡」的源洗白
                stalls = SourceProfiles.get(c.url)?.stalls ?: 0,
            )
        }

        cands.sortWith(Comparator { a, b ->
            val pa = a.profile
            val pb = b.profile
            when {
                // 1) 窗口正常的源优先
                pa.tinyWindow != pb.tinyWindow -> if (pa.tinyWindow) 1 else -1
                // 2) 实播重缓冲多的源降权：探测的速率快照测不出「运气好但实播卡」
                //    的中转源，实播裁决优先于一切快照指标
                pa.stalls != pb.stalls -> if (pa.stalls > pb.stalls) 1 else -1
                else -> {
                    // 2) 实测富余倍数大的优先；算不出需求时按中性 1.0，
                    //    不因为「测不出需求」而冤枉一个可能没问题的源
                    val ha = pa.headroom.let { if (it > 0f) it else 1f }
                    val hb = pb.headroom.let { if (it > 0f) it else 1f }
                    val d = ha - hb
                    when {
                        d > 0.15f || d < -0.15f -> if (hb > ha) 1 else -1
                        // 3) 实测速率高的优先
                        a.kbps != b.kbps -> if (b.kbps > a.kbps) 1 else -1
                        // 4) 建连快的优先
                        else -> a.costMs.compareTo(b.costMs)
                    }
                }
            }
        })

        val keepCount = minOf(PROBE_SOURCES_ENOUGH, cands.size)
        val keep = cands.subList(0, keepCount)
        keep.forEach { SourceProfiles.put(it.url, it.profile) }
        // HLS 源按实测质量排前面（画像也随之下发）；直连流只在名额还有剩时补在末尾备用
        tv.videoUrl = (keep.map { it.url } + fallbacks.sortedBy { it.second }.map { it.first })
            .take(PROBE_SOURCES_ENOUGH)
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
