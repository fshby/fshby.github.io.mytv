package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.lizongying.mytv.api.DnsCache
import com.lizongying.mytv.api.MyLoadErrorHandlingPolicy
import com.lizongying.mytv.api.RedirectMemory
import com.lizongying.mytv.api.SourceProfiles
import com.lizongying.mytv.databinding.PlayerBinding
import com.lizongying.mytv.models.TVViewModel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit


class PlayerFragment : Fragment() {

    private var _binding: PlayerBinding? = null
    private var playerView: PlayerView? = null
    private var tvViewModel: TVViewModel? = null
    private val aspectRatio = 16f / 9f

    /** 瞬时源错误的静默重试计数，成功播放后归零 */
    private var transientRetries = 0

    /**
     * 本次换台内已轮换过的备用源次数。
     *
     * 多源频道合并后 rotateToNextSource 是取模循环，若不做上限，
     * 当频道内所有源都播不了时会 A→B→A→B 无限轮换，永远走不到错误提示。
     * 上限 = 源数量 - 1；只有显式换台（play()）才允许重置。
     */
    private var sourceRotations = 0

    /** 换台去抖：连续按键只让最后一次真正起播 */
    private val playHandler = Handler(Looper.getMainLooper())
    private var pendingPlay: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)

        playerView = _binding!!.playerView

        playerView?.viewTreeObserver?.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            @OptIn(UnstableApi::class)
            override fun onGlobalLayout() {
                // 布局回调可能在 Fragment 视图已销毁后才到，playerView 会变成空引用
                val pv = playerView ?: return
                pv.viewTreeObserver.removeOnGlobalLayoutListener(this)

                // OkHttp 数据源：连接池复用 + 更快的失败判定（5s 连接超时），
                // 并允许 http<->https 跨协议重定向（IPTV 源常见）
                val httpClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    // DNS 缓存：换台时省掉每次 29~61ms 的域名解析（已带 TTL，IP 变了会自动重解析）
                    .dns(DnsCache.shared)
                    // 重定向落地地址固化：实测 8/14 的源要跳 1~3 次 302，
                    // 且 HLS 每轮刷新 playlist 都重复跳，固化后每次省一个 RTT
                    .addInterceptor(RedirectMemory())
                    .build()
                val httpFactory = OkHttpDataSource.Factory(httpClient)
                    .setUserAgent("Mozilla/5.0 (Linux; Android 9) MyTV/2.1")

                val mediaSourceFactory = DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(requireContext(), httpFactory)
                )
                    // 自定义加载错误策略：分片级 404/502 用 300ms 短退避快速消化，
                    // 而不是默认那套 0/1/2s 长退避（一片坏 = 冻结约 3 秒）
                    .setLoadErrorHandlingPolicy(MyLoadErrorHandlingPolicy())

                val renderersFactory = DefaultRenderersFactory(requireContext())
                    .setEnableDecoderFallback(true)

                // 缓冲参数必须远小于短窗口源（IPTV 常见 4x5s≈20s 窗口）的直播窗口，
                // 否则重缓冲期间窗口滑走，会反复触发 BehindLiveWindowException
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        // 实测 44% 的源整个窗口只有 2~3 片（窗口中位 25s），
                        // 原 15s 的 minBuffer 加载器根本达不到，只会持续满速抢带宽，
                        // 在弱盒子上与首帧、频道探测互相争抢
                        /* minBufferMs = */ 8000,
                        /* maxBufferMs = */ 30000,
                        /* bufferForPlaybackMs = */ 2000,
                        // 原来 1500 意味着「补到 1.5s 就恢复播放」，几乎必然立刻再抖一次，
                        // 而目标偏移每次只 +500ms，恢复得很慢；3 秒刚好越过一个分片边界
                        /* bufferForPlaybackAfterRebufferMs = */ 3000,
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    // 直播不会倒回已播内容，关掉回退缓冲可省一大块内存
                    .setBackBuffer(0, false)
                    .build()

                val exoPlayer = ExoPlayer.Builder(requireContext(), renderersFactory)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setLoadControl(loadControl)
                    .build()

                pv.player = exoPlayer
                pv.player?.playWhenReady = true
                pv.player?.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        // 视图已销毁时 measuredHeight 可能取不到，直接放弃这次布局调整
                        val height = playerView?.measuredHeight ?: return
                        if (height == 0) return
                        val ratio = playerView?.measuredWidth?.div(height)
                        if (ratio != null) {
                            val layoutParams = playerView?.layoutParams
                            if (ratio < aspectRatio) {
                                layoutParams?.height =
                                    (playerView?.measuredWidth?.div(aspectRatio))?.toInt()
                                playerView?.layoutParams = layoutParams
                            } else if (ratio > aspectRatio) {
                                layoutParams?.width =
                                    (playerView?.measuredHeight?.times(aspectRatio))?.toInt()
                                playerView?.layoutParams = layoutParams
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        super.onPlayerError(error)
                        Log.e(TAG, "PlaybackException $error")

                        // 直播窗口滑走：回到直播边缘重新缓冲即可，不必整台重试
                        var cause: Throwable? = error
                        while (cause != null) {
                            if (cause is BehindLiveWindowException) {
                                playerView?.player?.run {
                                    seekToDefaultPosition()
                                    prepare()
                                    play()
                                }
                                return
                            }
                            cause = cause.cause
                        }

                        // 瞬时源错误（404/连接抖动等）：先静默重试当前源，
                        // 同一源连续失败则轮换到频道内下一个备用源（failover），
                        // 全部源轮完仍失败才升级为弹错误屏 + 整台重试
                        if (transientRetries < 2) {
                            transientRetries++
                            Log.i(TAG, "transient retry #$transientRetries")
                            view?.postDelayed({
                                playerView?.player?.run {
                                    prepare()
                                    play()
                                }
                            }, 500)
                            return
                        }
                        val vm = tvViewModel
                        val sources = vm?.videoUrl?.value?.size ?: 0
                        if (vm != null && sources > 1 &&
                            sourceRotations < sources - 1 && vm.rotateToNextSource()
                        ) {
                            sourceRotations++
                            Log.i(
                                TAG,
                                "failover ${sourceRotations}/${sources - 1} of ${vm.getTV().title}"
                            )
                            transientRetries = 0
                            startPlay(vm)
                            return
                        }

                        transientRetries = 0
                        val err = "播放错误"
                        tvViewModel?.setErrInfo(err)
                        // 延迟重试，避免坏源导致紧密的错误循环
                        view?.postDelayed({
                            if (view != null) {
                                tvViewModel?.changed("retry")
                            }
                        }, 2000)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        super.onIsPlayingChanged(isPlaying)
                        if (isPlaying) {
                            transientRetries = 0
                            tvViewModel?.setErrInfo("")
                        }
                    }
                })
            }
        })
        // 断网恢复后自动重连当前频道；播放正常时 isPlaying 守卫会忽略
        PlaybackRecovery.listener = {
            playerView?.player?.let { p ->
                if (!p.isPlaying) {
                    Log.i(TAG, "network restored, re-prepare current channel")
                    p.prepare()
                    p.play()
                }
            }
        }
        (activity as? MainActivity)?.fragmentReady(TAG)
        return _binding!!.root
    }

    @OptIn(UnstableApi::class)
    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        transientRetries = 0
        sourceRotations = 0
        // 换台去抖：连续按键/换台时取消上一次未起播的请求，350ms 内只播最后一次
        pendingPlay?.let { playHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingPlay = null
            startPlay(tvViewModel)
        }
        pendingPlay = r
        playHandler.postDelayed(r, 350)
    }

    @OptIn(UnstableApi::class)
    private fun startPlay(tvViewModel: TVViewModel) {
        val url = tvViewModel.getVideoUrlCurrent()
        if (url.isEmpty()) {
            // 空源频道直接跳过，避免用空地址 setMediaItem 后反复报错
            Log.e(TAG, "${tvViewModel.getTV().title} videoUrl is empty, skip play")
            tvViewModel.setErrInfo("无可用播放地址")
            return
        }
        val mime = guessMimeType(url)

        // 起播目标偏移按「源画像」动态计算（探测阶段实测出目标时长与窗口长度）。
        //
        // 原实现把所有源统一钉成 3 秒：短窗口源（窗口仅 2~3 片）上，起播点会被
        // HlsMediaSource 回退到分片起点，等于手上只有 1 片缓冲，且刚好贴在窗口最
        // 不可靠的一端（最新片刚发布、最旧片常被 CDN 清掉）。
        // 画像未知（本地列表、或本次启动直接复用缓存没跑探测）时返回 0 —— 此时
        // **不设置**，完全交给 media3 自己的默认（3 × 目标时长），行为与上游一致。
        val targetOffsetMs = SourceProfiles.get(url)?.targetOffsetMs ?: 0L
        Log.i(
            TAG,
            "play ${tvViewModel.getTV().title} offset=" +
                    if (targetOffsetMs > 0L) "${targetOffsetMs}ms" else "media3默认"
        )

        playerView?.player?.run {
            setMediaItem(
                MediaItem.Builder()
                    .setUri(url)
                    // 带参数或无 .m3u8 后缀的地址依赖扩展名推断会失败，需显式指定
                    .apply { if (mime != null) setMimeType(mime) }
                    // 追赶参数：落后目标偏移时轻微加速（≤1.08x）主动追回，而不是等滑出
                    // 窗口后打断式恢复；降速下限 0.97x 作为「离边缘太近」时的刹车。
                    // 注意这里的追赶目标就是上面那个偏移，不再是直播边缘本身。
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .apply { if (targetOffsetMs > 0L) setTargetOffsetMs(targetOffsetMs) }
                            .setMinPlaybackSpeed(0.97f)
                            .setMaxPlaybackSpeed(1.08f)
                            .build()
                    )
                    .build()
            )
            prepare()
            // 远程列表构造的频道曾落到 TV.volume 默认值 0.1，听感等同「没有声音」。
            // 这里只对异常小/零音量兜底为满音量，保留内置频道自定义的音量（0.5/0.7/0.8 等）。
            val tvVolume = tvViewModel.getTV().volume
            volume = if (tvVolume < 0.2F) 1.0F else tvVolume.coerceAtMost(1.0F)
        }
    }

    private fun guessMimeType(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.contains("mpd") -> MimeTypes.APPLICATION_MPD
            lower.substringBefore('?').endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            else -> null
        }
    }

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
        val p = playerView?.player ?: return
        // onPause 只做 pause() 保活，所以这里绝大多数情况只需 play() 就能秒回画面；
        // 只有从未起播 / 被 stop 过（STATE_IDLE）时才需要重新 prepare
        if (p.playbackState == Player.STATE_IDLE) {
            Log.i(TAG, "re-prepare (idle)")
            p.prepare()
        }
        p.play()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        // 保活：只暂停不 stop。stop() 会丢掉全部已缓冲数据，从 HOME / 待机返回时
        // 必须重新建连 + 首片下载（实测 0.03~2.1s 黑屏）；pause() 则保留缓冲秒回。
        // 若后台停留过久导致滑出直播窗口，由 onPlayerError 的 BehindLiveWindow 分支兜底。
        playerView?.player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 视图可能已先行销毁，这里不能再对 playerView 做非空断言
        playerView?.player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingPlay?.let { playHandler.removeCallbacks(it) }
        pendingPlay = null
        PlaybackRecovery.listener = null
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
    }
}
