package com.lizongying.mytv.api

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 单个源（一条 m3u8 地址）的 HLS 画像 + 实测质量。
 *
 * 所有字段为 0 都表示「未知」，未知时下游一律退回 media3 自己的默认行为，
 * 绝不用猜测值去覆盖播放器。
 *
 * 为什么要单独按 URL 建表：同一条地址会经历「解析 → 同名合并 → 去重 → 写缓存 → 读缓存」，
 * 挂到 TV 对象上任何一环都可能被丢掉或复制；而画像只需要 URL 就能寻址，
 * 顺带还能在「重启后走缓存、跳过探测」的路径上继续生效（见 [SourceProfiles]）。
 */
data class SourceProfile(
    /** #EXT-X-TARGETDURATION，毫秒 */
    val targetDurationMs: Long = 0L,
    /** 整个直播窗口长度（playlist 内所有分片时长之和），毫秒 */
    val windowMs: Long = 0L,
    /** playlist 里的分片数量 */
    val segments: Int = 0,
    /** 实测下载速率 KB/s（限量下载一片分片得出） */
    val kbps: Long = 0L,
    /** 实时码率需求 KB/s（分片总字节 ÷ 分片时长，或 playlist 声明的 BANDWIDTH） */
    val demandKbps: Long = 0L,
    /** 推荐起播偏移（毫秒），由 [targetOffsetFor] 按真实分片边界算出；0 表示未知 */
    val targetOffsetMs: Long = 0L,
    /**
     * 运行时重缓冲次数（实播中 READY→BUFFERING 换向）。
     *
     * 探测的速率/富余是**一次性快照**，对「快照时运气好、实播期带宽塌方」的中转源
     * 无能为力（实测 204.12.224.154:88 探测排序第一、实播 110 秒重缓冲 2 次）。
     * 实播才是最终裁判：这里记录的重缓冲次数用于播放时换选与下轮探测排序降权，
     * 并跨探测周期保留（探测重建画像时显式携带，不随快照归零）。
     */
    val stalls: Int = 0,
) {

    /**
     * 窗口过小的源：整个窗口只装 ≤2 片，或总长 ≤8s。
     *
     * 实测远程列表里 44% 的源整个窗口只有 2~3 片。这类源无论怎么调参都只剩 ≤1 片余量，
     * 且 playlist 长时间无更新会抛 PlaylistStuckException（3.5 × 目标时长），
     * 所以多源排序时把它们压到后面，让更宽窗口的源优先。
     */
    val tinyWindow: Boolean
        get() = (segments in 1..2) || (windowMs in 1..TINY_WINDOW_MS)

    /** 实测富余倍数（下载速率 ÷ 实时需求）；0 表示无法计算 */
    val headroom: Float
        get() = if (kbps > 0 && demandKbps > 0) kbps.toFloat() / demandKbps else 0f

    companion object {
        /** 起播垫片时长下限：低于这个值基本等于贴着直播边缘起播 */
        const val MIN_TARGET_OFFSET_MS = 6_000L
        /** 起播垫片时长上限：分片本身很长时，「垫 2 片」会变成几分钟延迟，没有意义 */
        const val MAX_CUSHION_MS = 30_000L
        /** 「窗口过小」阈值 */
        const val TINY_WINDOW_MS = 8_000L
        /** 低富余源（headroom<2x）的起播垫片占窗口比例（伪实时模式） */
        const val LOW_HEADROOM_WINDOW_FRACTION = 2.0 / 3.0
        /** 低富余判定阈值：实测速率不足需求的该倍数 */
        const val LOW_HEADROOM_MAX = 2.0f

        /**
         * 按**真实分片边界**算起播偏移，毫秒；无法计算返回 0。
         *
         * 为什么必须按边界算：media3 的 `getLiveWindowDefaultStartPositionUs()`
         * 会把「直播边缘 − 目标偏移」再**回退到最近的分片起点**，所以填进去的毫秒数
         * 只是个近似——真正决定起播位置的是分片边界。分片时长不均匀时，
         * 时间式算法会被这一步向下取整推到窗口最旧的一片上（实测 209 个源里有 17 个），
         * 而贴在最旧片意味着窗口一滑就被挤出 → 反复 BehindLiveWindow。
         *
         * 规则三句话：
         *  1. 目标垫片时长 ≈ 2 个平均分片，钳在 [MIN_TARGET_OFFSET_MS, MAX_CUSHION_MS]；
         *  2. 在下标 ≥ 1（**身后至少留 1 整片**，不会被窗口滑动挤出）的约束内，
         *     取垫片不超过目标值的那个分片起点——垫得最多但不过头；
         *  3. 连最后一片的时长都超过目标时（分片本身很长的源），退到倒数第 1 片。
         *
         * [lowHeadroom]（实测下载速率 < 2× 实时需求，如跨洋中转源）时把目标垫片
         * 抬到 **窗口的 2/3**，取它与「2 个平均分片」的较大者——链路富余贴 1.0x 时
         * 薄垫子会被速度波动反复击穿（切台初期尤甚），这是用实时性换流畅
         * （用户已接受「伪实时」代价）。仍受 MAX_CUSHION_MS 上限约束，
         * 不会把长分片源垫成分钟级延迟。
         *
         * 效果（用实测 209 个源回算，偏移按毫秒截断、定位按 media3 的整数微秒模型）：
         * 起播可垫内容中位 6s → 10s，「至少垫 1 片」的源 92 → 170 个，
         * 「至少垫 2 片」的源 4 → 52 个，而贴在窗口最旧片
         * （身后不足 1 片、窗口一滑就被挤出 → 反复 BehindLiveWindow）的源 6 → 0 个，
         * 同时把垫片超过 60s 的源控制在 1 个（没有把长分片源垫成几分钟延迟）。
         *
         * 另外，**0 时长分片先剔除**：实测确有这种源（动作电影、TVBS 新闻台），
         * 它既不占时间也不可能成为起播位置，留着会把「身后留一片」的约束架空。
         */
        fun targetOffsetFor(durs: List<Double>, lowHeadroom: Boolean = false): Long {
            val real = durs.filter { it > 0.0 }
            val n = real.size
            if (n < 2) return 0L
            val starts = DoubleArray(n)
            var total = 0.0
            for (i in 0 until n) {
                starts[i] = total
                total += real[i]
            }
            if (total <= 0.0) return 0L

            val avgCushionMs = 2 * total / n * 1000
            val targetMs = if (lowHeadroom) {
                maxOf(avgCushionMs, total * 1000.0 * LOW_HEADROOM_WINDOW_FRACTION)
            } else {
                avgCushionMs
            }.coerceIn(
                MIN_TARGET_OFFSET_MS.toDouble(),
                MAX_CUSHION_MS.toDouble(),
            )

            // 可垫内容随下标单调递减：第一个「不超过目标」的下标就是垫得最多的合法位置
            var idx = n - 1
            for (i in 1 until n) {
                if ((total - starts[i]) * 1000 <= targetMs) {
                    idx = i
                    break
                }
            }
            return ((total - starts[idx]) * 1000).toLong()
        }
    }
}

/**
 * 源画像表：进程内 ConcurrentHashMap + cacheDir 下的一份文本缓存。
 *
 * 磁盘缓存的意义在于**重启**：列表内容没变且探测结论新鲜时，启动路径会整段跳过解析与探测，
 * 那次会话就没有任何实测数据。把画像落盘后，起播偏移在「没探测的那次」同样有效，
 * 不会退化成「有时生效有时不生效」。
 *
 * 文件格式（每行一条，URL 永远放最后，其余字段都是数字，读时按 \t 切分）：
 *   目标时长 \t 窗口长度 \t 分片数 \t 实测KB/s \t 需求KB/s \t 起播偏移 \t 重缓冲次数 \t URL
 * 兼容读取旧的 7 列行（无重缓冲列，按 0 处理），写入一律 8 列。
 */
object SourceProfiles {

    private const val TAG = "SourceProfiles"
    private const val FILE_NAME = "mytv-sources.txt"

    private val map = ConcurrentHashMap<String, SourceProfile>()

    @Volatile
    private var loaded = false

    fun get(url: String): SourceProfile? = map[url]

    fun put(url: String, profile: SourceProfile) {
        if (url.isEmpty()) return
        map[url] = profile
    }

    /** 记录一次运行时重缓冲（实播裁决，探测快照看不到的劣化） */
    fun noteStall(url: String) {
        if (url.isEmpty()) return
        val p = map[url] ?: return
        map[url] = p.copy(stalls = p.stalls + 1)
        Log.i(TAG, "stall #${p.stalls + 1} noted for ${url.substringBefore('?').takeLast(60)}")
    }

    /** 进程启动早期调用，避免首次换台时在主线程读文件 */
    fun preload(context: Context) {
        if (!loaded) load(context)
    }

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.isFile || f.length() == 0L) return
        runCatching {
            var n = 0
            f.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val p = line.split('\t')
                if (p.size < 7) return@forEachLine
                // URL 永远在最后；第 7 列（重缓冲）只有新格式才有，旧行按 0 读
                val url = p[p.size - 1]
                if (url.isEmpty()) return@forEachLine
                map[url] = SourceProfile(
                    targetDurationMs = p[0].toLongOrNull() ?: 0L,
                    windowMs = p[1].toLongOrNull() ?: 0L,
                    segments = p[2].toIntOrNull() ?: 0,
                    kbps = p[3].toLongOrNull() ?: 0L,
                    demandKbps = p[4].toLongOrNull() ?: 0L,
                    targetOffsetMs = p[5].toLongOrNull() ?: 0L,
                    stalls = if (p.size >= 8) p[6].toIntOrNull() ?: 0 else 0,
                )
                n++
            }
            Log.i(TAG, "load $n source profiles")
        }.onFailure { Log.e(TAG, "load failed", it) }
    }

    /**
     * 落盘，并顺手裁掉已不在 [urls] 里的条目。
     * 列表每 30 分钟会整体重建一次，不裁的话这份文件只增不减。
     */
    fun save(context: Context, urls: Collection<String>) {
        runCatching {
            val keep = HashSet<String>(urls.size * 2)
            keep.addAll(urls)
            map.keys.retainAll(keep)

            val sb = StringBuilder(map.size * 96)
            map.forEach { (url, p) ->
                sb.append(p.targetDurationMs).append('\t')
                    .append(p.windowMs).append('\t')
                    .append(p.segments).append('\t')
                    .append(p.kbps).append('\t')
                    .append(p.demandKbps).append('\t')
                    .append(p.targetOffsetMs).append('\t')
                    .append(p.stalls).append('\t')
                    .append(url).append('\n')
            }
            file(context).writeText(sb.toString())
            Log.i(TAG, "save ${map.size} source profiles")
        }.onFailure { Log.e(TAG, "save failed", it) }
    }

    private fun file(context: Context): File = File(context.cacheDir, FILE_NAME)
}
