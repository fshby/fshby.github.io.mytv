package com.lizongying.mytv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
import com.lizongying.mytv.player.Mp2RenderersFactory
import okhttp3.OkHttpClient
import java.net.NoRouteToHostException
import java.net.SocketException
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

    /** 当前播放的源地址（重缓冲归因用） */
    private var currentPlayUrl: String? = null

    /** 当前媒体项是否已进入过 READY（用于识别 READY→BUFFERING 的重缓冲换向） */
    private var wasReady = false

    /**
     * 是否正在等待网络恢复。
     *
     * 断网期间播放器报错属于预期现象，此标记用于：
     *  - 抑制「播放错误」误导性文案（真实原因是网络断了，不是源坏了）；
     *  - 阻止消耗重试预算 / 轮换备用源（否则网络恢复时重试额度已耗尽）。
     */
    private var awaitingNetwork = false

    /**
     * 本次换台内是否已经处理过「音频本机不可解」。
     *
     * onTracksChanged 在每次 prepare 后都会回调，重试/重建也会再触发；
     * 没有这个门闩的话，一个 MP2 源会在每次重试时反复换源、反复弹提示。
     * 只在用户显式换台（[play]）时复位。
     */
    private var audioFixTried = false

    /**
     * 看门狗：兜底「静默卡死」。
     *
     * 断网重连后最常见的坏形态不是 onPlayerError，而是播放器**卡在 BUFFERING
     * 没有任何错误回调**——加载器一轮轮重试、播放器干等，既不报错也不出画面。
     * 此时错误重试链路完全不会被触发，用户看到的就是「网络早回来了，画面却没回来」。
     *
     * 另外两类场景也由它兜底：
     *  - 系统没有发出网络回调（路由器 WAN 闪断、链路没变但出口换了）；
     *  - 播放器停在错误态后重试链路因列表/观察者重建而中断。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            checkStuck()
        }
    }

    /** 上一次确认「播放正常」的时间戳（READY 或 isPlaying 时刷新） */
    private var lastHealthyAt = 0L

    /** 稳定播放满 [STABLE_DECAY_MS] 后给当前源的劣迹计数减一票（可自愈，防冤案） */
    private val stableDecay = Runnable {
        if (playerView?.player?.isPlaying == true) {
            SourceProfiles.decayStall(currentPlayUrl ?: "")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

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

                // 内置 MP2 软解（libmad）：IPTV 聚合源里 MPEG-1 Layer II 很常见，
                // 而 Android 从不保证支持 audio/mpeg-L2（AOSP 的 MP3 软解只有
                // Layer III），缺了它这些源就是「有画面、没声音、还不报错」
                val renderersFactory = Mp2RenderersFactory(requireContext())
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
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            wasReady = true
                            lastHealthyAt = System.currentTimeMillis()
                        } else if (playbackState == Player.STATE_BUFFERING && wasReady) {
                            // READY→BUFFERING 换向 = 真实重缓冲（初缓冲是 IDLE→BUFFERING，
                            // 换台/重试会重置 wasReady，都不会误计）。归因到当前源，
                            // 供播放时换选与下轮探测排序降权。
                            wasReady = false
                            if (PlaybackRecovery.isOnline) {
                                currentPlayUrl?.let { SourceProfiles.noteStall(it) }
                                Log.i(TAG, "rebuffer on current source")
                            } else {
                                // 断网导致的重缓冲与源质量无关，记进去会把好源标成劣迹源，
                                // 恢复后换台反而换选到更差的源（自我伤害）
                                Log.i(TAG, "rebuffer while offline, not attributed")
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        super.onTracksChanged(tracks)
                        if (audioFixTried) return
                        if (!hasUndecodableAudio(tracks)) return
                        audioFixTried = true
                        val url = currentPlayUrl ?: return
                        Log.w(
                            TAG,
                            "audio not decodable on this device (e.g. MP2), " +
                                    "video continues without sound: ${url.substringBefore('?').takeLast(60)}"
                        )
                        // 记入画像（持久化）：换台/换源时据此跳过这类源
                        SourceProfiles.noteAudioUnsupported(url)
                        context?.let { SourceProfiles.persist(it) }
                        if (!tryAudioFailover()) {
                            // 没有可信的备选源：别动播放（画面还在），但必须让用户知道原因，
                            // 否则「有画面没声音」会被当成 App 的玄学故障。
                            // 注意：MP2 已由内置 libmad 软解覆盖，走到这里的都是更冷门的编码。
                            Toast.makeText(
                                requireContext(),
                                "该源音频编码本机无法解码，只有画面无声音",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

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

                        // 断网 / 网络不可达：这不是「源坏了」，绝不能消耗重试预算或轮换备用源——
                        // 否则网络恢复时重试额度与源轮换上限已用尽，播放器会停在错误屏不再自愈。
                        // 这里只标记等待网络，真正的重连由 PlaybackRecovery 在网络恢复后驱动。
                        if (PlaybackRecovery.isOffline || isNetworkUnreachable(error)) {
                            val offline = PlaybackRecovery.isOffline
                            if (!awaitingNetwork) {
                                Log.i(
                                    TAG,
                                    "network unreachable, wait for recovery (offline=$offline)"
                                )
                                awaitingNetwork = true
                                tvViewModel?.setErrInfo(WAIT_NETWORK_MSG)
                            }
                            return
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
                            lastHealthyAt = System.currentTimeMillis()
                            // 真正播起来了才算恢复完成：此时才清掉「等待网络」提示
                            awaitingNetwork = false
                            tvViewModel?.setErrInfo("")
                            // 连续稳定播放一分钟后给源画像减一票劣迹，
                            // 让被断网冤枉的源有机会自愈（真烂的源播不满这段时间）
                            handler.removeCallbacks(stableDecay)
                            handler.postDelayed(stableDecay, STABLE_DECAY_MS)
                        } else {
                            handler.removeCallbacks(stableDecay)
                        }
                    }
                })
            }
        })
        // 断网恢复后自动重连当前频道（由 PlaybackRecovery 按退避计划驱动多次尝试）；
        // 没在收看任何频道、或本来就在正常播放时，needsRecovery() 返回 false，
        // 恢复链路会直接放弃，不打扰正常播放
        PlaybackRecovery.listener = object : PlaybackRecovery.Listener {
            override fun needsRecovery(): Boolean =
                tvViewModel != null && playerView?.player?.isPlaying != true

            override fun onNetworkLost() {
                if (awaitingNetwork) return
                awaitingNetwork = true
                // 明确告知用户「等网络」，而不是让错误屏显示「播放错误」这种误导性归因
                tvViewModel?.setErrInfo(WAIT_NETWORK_MSG)
            }

            override fun onNetworkAvailable() {
                // 不在这里清文案：等 onIsPlayingChanged(true) 确认真的播起来再清。
                // 同时把健康计时清零，让看门狗在下一个巡检周期（≤5s）就立即尝试重建，
                // 而不是再等满 20s 的卡死判定——网络都回来了，没必要让用户多等。
                lastHealthyAt = 0L
                Log.i(TAG, "network available, will try to recover")
            }

            override fun onRecoverAttempt(attempt: Int) {
                val vm = tvViewModel ?: return
                val p = playerView?.player ?: return
                if (p.isPlaying) return
                // 每次恢复尝试都是一次全新的重试预算；否则断网期间消耗的上限
                // 会让恢复后的 failover 直接失效
                transientRetries = 0
                sourceRotations = 0
                Log.i(TAG, "recover attempt #$attempt: ${vm.getTV().title}")
                // 断网期间 player 可能已进入 error/idle，单独 prepare() 不可靠，
                // 直接按当前源重建媒体项（内含 setMediaItem + prepare + play）
                startPlay(vm)
            }
        }
        (activity as? MainActivity)?.fragmentReady(TAG)
        // 看门狗启动：兜底「无错误回调的静默卡死」与「网络回调缺失」
        lastHealthyAt = System.currentTimeMillis()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        return _binding!!.root
    }

    @OptIn(UnstableApi::class)
    fun play(tvViewModel: TVViewModel) {
        this.tvViewModel = tvViewModel
        transientRetries = 0
        sourceRotations = 0
        // 用户主动换台：重新开放「音频不可解」的一次性处理机会
        audioFixTried = false
        // 用户主动换台：退出「等待网络」态，按新频道正常起播
        awaitingNetwork = false
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
        // 运行时重缓冲反馈：当前源被实播证实会卡（连续 ≥2 次）且存在更干净的备选时，
        // 换选实播无劣迹的源。探测的速率快照测不出「快照时运气好、实播期带宽塌方」
        // 的中转源（实测 204.12.224.154:88 排序第一、实播 110s 重缓冲 2 次）。
        val urls = tvViewModel.getVideoUrls()
        if (urls.size > 1) {
            val cur = tvViewModel.getVideoUrlCurrent()
            val curProf = SourceProfiles.get(cur)
            val curStalls = curProf?.stalls ?: 0
            // 当前源有实播劣迹（频繁重缓冲）或音频本机解不了（无声）时，
            // 换选更干净的源。比较器与 tryAudioFailover 一致：先「探测证实活着」，
            // 再劣迹最少——不能硬性要求 kbps>0，部分源拒绝探测请求却允许正常播放。
            if (curStalls >= STALL_SWITCH_THRESHOLD || curProf?.audioUnsupported == true) {
                val better = urls.indices
                    .filter { urls[it] != cur }
                    .filter { (SourceProfiles.get(urls[it])?.audioUnsupported) != true }
                    .minWithOrNull(
                        compareBy(
                            { !((SourceProfiles.get(urls[it])?.kbps ?: 0L) > 0L) },
                            { SourceProfiles.get(urls[it])?.stalls ?: 0 },
                        )
                    )
                if (better != null) {
                    tvViewModel.setVideoIndex(better)
                    Log.i(
                        TAG,
                        "prefer source #$better (current: " +
                                (if (curProf?.audioUnsupported == true) "audio-unsupported " else "") +
                                "$curStalls stalls)"
                    )
                }
            }
        }
        val url = tvViewModel.getVideoUrlCurrent()
        if (url.isEmpty()) {
            // 空源频道直接跳过，避免用空地址 setMediaItem 后反复报错
            Log.e(TAG, "${tvViewModel.getTV().title} videoUrl is empty, skip play")
            tvViewModel.setErrInfo("无可用播放地址")
            return
        }
        val mime = guessMimeType(url)
        currentPlayUrl = url
        wasReady = false
        // 起播宽限：初缓冲在弱盒子上可能持续数秒，这段时间内不应被看门狗判定为卡死
        lastHealthyAt = System.currentTimeMillis()

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

    /**
     * 判断媒体里是否**有音频轨道、但没有任何一条能被本机解码**。
     *
     * 走 media3 的 Tracks 能力判定（getTrackSupport），不用自己枚举 MediaCodec：
     * 音频轨道存在但全部 FORMAT_UNSUPPORTED_TYPE，正是「画面正常却无声且不报错」
     * 的唯一形态（典型：IPTV 的 MPEG-1 Layer II / MP2 音频）。
     * 注意：流里压根没有音频轨道（纯画面）时返回 false，不误伤。
     */
    private fun hasUndecodableAudio(tracks: Tracks): Boolean {
        var hasAudio = false
        var handled = false
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                hasAudio = true
                if (group.getTrackSupport(i) == C.FORMAT_HANDLED) handled = true
            }
        }
        return hasAudio && !handled
    }

    /**
     * 音频解不了时的守卫式换源：换到「音频本机可解」且画像最优的备选源。
     *
     * 为什么按优先级挑而不是硬性过滤：探测对部分源拿不到速率快照（源对探测请求
     * 返回 404/拒绝，但正常播放请求带 UA 就能拿到），如果硬性要求 kbps>0，
     * 会把这种「探测看不见、实播却正常」的源错杀掉（实测 CCTV9 的 107.150.60.122
     * 探测 kbps=0、实播正常出声）。所以：排除已知音频不可解的源，剩下的按
     * 画像质量排序；真换到死源时还有 onPlayerError → 轮换那条链路兜底。
     * 返回 true 表示已发起换源，调用方不必再弹提示。
     */
    private fun tryAudioFailover(): Boolean {
        val vm = tvViewModel ?: return false
        val urls = vm.getVideoUrls()
        if (urls.size < 2) return false
        val cur = vm.getVideoUrlCurrent()

        class Cand(val index: Int, val stalls: Int, val alive: Boolean)

        val target = urls.indices
            .filter { urls[it] != cur }
            .filter { (SourceProfiles.get(urls[it])?.audioUnsupported) != true }
            .map {
                Cand(
                    it,
                    SourceProfiles.get(urls[it])?.stalls ?: 0,
                    (SourceProfiles.get(urls[it])?.kbps ?: 0L) > 0L,
                )
            }
            .minWithOrNull(
                // ① 探测证实活着（kbps>0）的优先——但不能硬性过滤，部分源拒绝探测
                //    请求却允许正常播放（实测 107.150.60.122 探测 kbps=0、实播正常出声）；
                // ② 其次劣迹（重缓冲）最少的
                compareBy({ !it.alive }, { it.stalls })
            )
            ?: return false
        Log.i(TAG, "audio failover -> source #${target.index} (${urls[target.index].substringBefore('?').takeLast(50)})")
        transientRetries = 0
        sourceRotations = 0
        vm.setVideoIndex(target.index)
        startPlay(vm)
        return true
    }

    /**
     * 判断异常链里是否含「网络不可达」类错误。
     *
     * 只认**明确表示链路不通**的错误：`NoRouteToHostException`、socket 的
     * ENETUNREACH / ENETDOWN / EHOSTUNREACH / "network is unreachable"。
     *
     * 刻意**不**把 `UnknownHostException` 与 `ConnectException` 算进来：在链路正常的
     * 情况下它们分别代表「该域名解析不了」与「对端拒绝连接」，都属于源本身的问题，
     * 应该按原有的 重试→换源 流程处理，否则坏源会被无限当作断网等待。
     * 整网断开的情形由 `PlaybackRecovery.isOffline` 覆盖（此时任何错误都不消耗预算）。
     */
    private fun isNetworkUnreachable(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is NoRouteToHostException) return true
            if (cause is SocketException) {
                val msg = (cause.message ?: "").lowercase()
                if (msg.contains("unreachable") || msg.contains("enetunreach") ||
                    msg.contains("enetdown") || msg.contains("ehostunreach") ||
                    msg.contains("network is down")
                ) {
                    return true
                }
            }
            cause = cause.cause
        }
        return false
    }

    /**
     * 看门狗判据：有正在收看的频道、不在播、且链路正常时，若已经 [STUCK_MS]
     * 没有出现「健康」信号（READY 或 isPlaying），就按当前源重建一次。
     *
     * 只在 Fragment 处于前台（[isResumed]）时干预：退到后台时 onPause 会主动 pause，
     * 此时「不在播」是正常状态，不应触发重建。
     */
    private fun checkStuck() {
        val vm = tvViewModel ?: return
        val p = playerView?.player ?: return
        if (p.isPlaying) {
            lastHealthyAt = System.currentTimeMillis()
            return
        }
        if (!isResumed) return
        // 断网中：交给 PlaybackRecovery 的恢复链路，别在这里空转
        if (PlaybackRecovery.isOffline) return
        val stuckMs = System.currentTimeMillis() - lastHealthyAt
        if (stuckMs < STUCK_MS) return
        Log.i(TAG, "watchdog: no healthy signal for ${stuckMs}ms, rebuild ${vm.getTV().title}")
        awaitingNetwork = false
        transientRetries = 0
        sourceRotations = 0
        startPlay(vm)
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
        handler.removeCallbacksAndMessages(null)
        PlaybackRecovery.listener = null
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"

        /** 当前源累计重缓冲达到该值后，换台时优先换选实播无劣迹的备选源 */
        private const val STALL_SWITCH_THRESHOLD = 2

        /** 断网期间展示在错误屏上的提示：明确是网络问题，且会自动恢复 */
        private const val WAIT_NETWORK_MSG = "网络已断开，等待网络恢复后自动重连…"

        /** 看门狗巡检间隔 */
        private const val WATCHDOG_INTERVAL_MS = 5_000L

        /** 多久没有任何「健康」信号就判定为卡死并重建当前源 */
        private const val STUCK_MS = 20_000L

        /** 连续稳定播放满此时长，给当前源衰减一次劣迹计数 */
        private const val STABLE_DECAY_MS = 60_000L
    }
}
