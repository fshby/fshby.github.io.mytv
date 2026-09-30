package com.lizongying.mytv.api

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.CleartextNotPermittedException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 面向 IPTV 短窗口 HLS 源的加载错误策略。
 *
 * 默认策略（`DefaultLoadErrorHandlingPolicy`）对 404/502 这类分片级错误同样重试，
 * 退避是 `min((errorCount-1)×1000, 5000)` —— 也就是 0s、1s、2s，从第一次失败算起
 * **一片坏就等于冻结约 3 秒**，然后才升级成 `onPlayerError` 走整源重建。
 * 实测分片级失败率 15%~20% 且集中在窗口两端，这条退避曲线被反复触发，
 * 正好解释了「换台后头几片固定卡一下」。
 *
 * 这里改成：
 *
 *  - **分片（媒体数据）**：固定 300ms 短退避、最多 [mediaRetries] 次。
 *    单片坏 → 约 1.2s 内消化在加载器内部，不惊动整源 prepare()；
 *  - **playlist（清单）**：稍长的递增退避，允许更多次——清单本身很小，
 *    失败基本是瞬时网络抖动，重建整源的代价远大于多等一会儿；
 *  - **数据本身有问题**（ParserException / 文件不存在 / 明文流量被禁）：直接判致命，
 *    重试没有任何意义，早抛早让上层换源；
 *  - **不启用位置回退**：IPTV 源只有一个地址，回退到「同一数据的其它地址」不存在，
 *    多源轮换由 PlayerFragment 的 failover 负责。
 */
@OptIn(UnstableApi::class)
class MyLoadErrorHandlingPolicy(
    /** 分片级最大重试次数（单片坏合计约 mediaRetries × 300ms 后升级） */
    private val mediaRetries: Int = 4,
    /** playlist 最大重试次数 */
    private val manifestRetries: Int = 3,
) : LoadErrorHandlingPolicy {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MANIFEST) manifestRetries else mediaRetries

    /**
     * 不提供回退选项。
     *
     * 默认策略会在 403/404/410/416/500/503 时选择「换一个地址取同一份数据」
     * （FALLBACK_TYPE_LOCATION），这在 DASH 多地址场景下有用；IPTV 单地址源上
     * 只会产生无意义的排除与重选。
     */
    override fun getFallbackSelectionFor(
        fallbackOptions: FallbackOptions,
        loadErrorInfo: LoadErrorInfo,
    ): FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val errorCount = loadErrorInfo.errorCount
        val isManifest = loadErrorInfo.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST
        val maxRetries = if (isManifest) manifestRetries else mediaRetries

        // 次数用尽即判致命，交给上层的换源 / 整台重试处理
        if (errorCount > maxRetries) return C.TIME_UNSET

        return when (error) {
            // 数据本身不可用，重试无意义：越早抛出越早换源
            is ParserException,
            is FileNotFoundException,
            is CleartextNotPermittedException,
            -> C.TIME_UNSET

            // DNS 失败通常意味着整体断网，交给 PlaybackRecovery 的网络回调处理，
            // 这里只等一小会儿、少试几次，避免和网络恢复逻辑抢时机
            is UnknownHostException -> if (errorCount <= 2) DNS_DELAY_MS else C.TIME_UNSET

            // 分片级失败（404 / 502 / 超时 / 连接被重置）：短退避快速消化
            is InvalidResponseCodeException,
            is SocketTimeoutException,
            is InterruptedIOException,
            is HttpDataSource.HttpDataSourceException,
            -> if (isManifest) (MANIFEST_DELAY_MS * errorCount).coerceAtMost(MANIFEST_DELAY_MS_MAX)
            else MEDIA_DELAY_MS

            // 其余 IO 异常（读写中断、连接被重置等）一律按分片级短退避处理：
            // 重试成本远低于整源 prepare() 重建
            else ->
                if (isManifest) (MANIFEST_DELAY_MS * errorCount).coerceAtMost(MANIFEST_DELAY_MS_MAX)
                else MEDIA_DELAY_MS
        }
    }

    companion object {
        /** 分片级固定退避：足够让「刚发布还没就绪」的分片重试成功，又不至于拖成可见卡顿 */
        private const val MEDIA_DELAY_MS = 300L
        /** playlist 级递增退避基数 */
        private const val MANIFEST_DELAY_MS = 400L
        private const val MANIFEST_DELAY_MS_MAX = 2_000L
        /** DNS 失败退避 */
        private const val DNS_DELAY_MS = 800L
    }
}
