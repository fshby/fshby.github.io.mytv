package com.lizongying.mytv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lizongying.mytv.api.DnsCache

/**
 * 网络状态监听 + 播放恢复协调器。
 *
 * ## 背景：为什么需要它
 *
 * ExoPlayer 在断网时会持续报错，默认处理链路会把「网络不可用」当成「源坏了」：
 * 耗尽瞬时重试 → 轮换频道内所有备用源 → 弹出「播放错误」。等网络真正回来时，
 * 重试预算与备用源轮换上限已经用尽，播放器停在错误屏，**不会自己恢复**。
 *
 * 更早的实现里 `MainActivity.registerNetworkCallback()` 是死代码（从未被调用），
 * 等于完全没有网络恢复机制——断网后只能靠用户手动换台。
 *
 * 这里把网络状态作为**一等公民**管起来：
 *
 *  - 监听默认网络：`onLost` → 播放器进入「等待网络」态，暂停一切主动重试；
 *    `onAvailable` / `CAPABILITY_VALIDATED` → 清 DNS 缓存并退避重试恢复播放。
 *  - 恢复**不是一次性**的：按 [RETRY_PLAN] 退避重试，直到真正播起来
 *    （路由器 DHCP/NAT 就绪、CDN 重新调度都需要时间，只试一次必然经常失败）。
 *  - 以 `NET_CAPABILITY_VALIDATED` 为「真的能上网」信号：Wi-Fi 连上但外网未通时
 *    `onAvailable` 也会触发，此时重连必然再失败一次，所以要等系统联网校验通过。
 */
object PlaybackRecovery {

    /** 播放器侧实现的恢复接口（由 PlayerFragment 注册） */
    interface Listener {
        /**
         * 是否**需要**恢复：正在收看某频道、且当前没在播。
         *
         * 协调器用它决定「要不要打扰」：启动后系统会立刻回调一次
         * `onCapabilitiesChanged`，此时还没有任何频道在播（tvViewModel 为空），
         * 若不加这个判断就会白白重试一整轮。
         */
        fun needsRecovery(): Boolean

        /** 网络断开：进入等待态，不要消耗重试预算、不要轮换源 */
        fun onNetworkLost()

        /** 网络恢复：重置错误状态与重试计数，准备重连 */
        fun onNetworkAvailable()

        /** 第 [attempt] 次恢复尝试（从 1 开始计数） */
        fun onRecoverAttempt(attempt: Int)
    }

    private const val TAG = "PlaybackRecovery"

    /**
     * 恢复重试计划（毫秒）：首次尽快，其后逐步退避。
     *
     * 断网重连后链路的就绪是渐进的（物理层 → DHCP → 网关 NAT → 外网），
     * 实测 1s 内重连成功率一般，3~7s 才稳定；给到 15s 覆盖慢速路由器。
     */
    private val RETRY_PLAN = longArrayOf(1_000, 3_000, 7_000, 15_000)

    private val handler = Handler(Looper.getMainLooper())

    /** PlayerFragment 在 onViewCreated 注册、onDestroyView 注销 */
    @Volatile
    var listener: Listener? = null

    /** 默认网络是否可用（参考值，权威状态以系统回调为准） */
    @Volatile
    var isOnline: Boolean = true
        private set

    /** 便捷反向判断：网络是否已断开 */
    val isOffline: Boolean
        get() = !isOnline

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 当前已进行的恢复尝试序号（0 = 尚未尝试） */
    private var attemptIndex = 0

    private var started = false

    /** 由 MainActivity.onCreate 调用；重复调用安全 */
    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            Log.e(TAG, "ConnectivityManager unavailable")
            return
        }
        cm = manager

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "onAvailable")
                markOnline()
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "onLost")
                markOffline()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // 只有通过系统联网校验（VALIDATED）才算「真能上网」。
                // Wi-Fi 关联上但外网不通时 onAvailable 也会触发，此时重连必然失败，
                // 因此这里以 VALIDATED 作为触发的充分条件。
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    markOnline()
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(cb)
            } else {
                // API 21~23 无 registerDefaultNetworkCallback，退回通用请求
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            }
            callback = cb
            started = true
            isOnline = app.isNetworkConnected
            Log.i(TAG, "start, online=$isOnline sdk=${Build.VERSION.SDK_INT}")
        } catch (e: Exception) {
            Log.e(TAG, "register network callback failed", e)
        }
    }

    /** 由 MainActivity.onDestroy 调用 */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        cm = null
        started = false
        attemptIndex = 0
    }

    /**
     * 网络可用（或联网校验通过）。
     *
     * 若此前并非离线状态、且播放正常，则什么都不做——避免能力变化导致的
     * 重复回调打断正在进行的播放。
     */
    private fun markOnline() {
        val wasOffline = !isOnline
        isOnline = true

        // 出口网关 / NAT 地址可能整体变化，DNS 缓存必须整体失效，
        // 否则恢复时会拿着 5 分钟 TTL 内的旧 IP 直连，表现为「网络回来了却连不上」
        DnsCache.shared.clear()
        if (wasOffline) Log.i(TAG, "network restored, schedule recovery")

        val l = listener ?: return
        // 网络没断过（含启动后首次回调）：没有待恢复的播放就不打扰
        if (!wasOffline && !l.needsRecovery()) return

        l.onNetworkAvailable()
        scheduleRecovery()
    }

    /** 默认网络丢失：取消待执行的恢复，通知播放器进入等待态 */
    private fun markOffline() {
        if (!isOnline && attemptIndex == 0) return
        isOnline = false
        handler.removeCallbacksAndMessages(null)
        attemptIndex = 0
        Log.i(TAG, "network lost, playback waits")
        listener?.onNetworkLost()
    }

    /** 按 [RETRY_PLAN] 退避重试，直到播放真正恢复 */
    private fun scheduleRecovery() {
        handler.removeCallbacksAndMessages(null)
        attemptIndex = 0
        postNextAttempt()
    }

    private fun postNextAttempt() {
        if (attemptIndex >= RETRY_PLAN.size) {
            Log.w(TAG, "recovery gave up after ${RETRY_PLAN.size} attempts")
            return
        }
        val delay = RETRY_PLAN[attemptIndex]
        handler.postDelayed({
            val l = listener ?: return@postDelayed
            // 网络又断了 / 已经自己恢复了 / 本来就没事，都不必再试
            if (!isOnline) return@postDelayed
            if (!l.needsRecovery()) {
                attemptIndex = 0
                return@postDelayed
            }
            attemptIndex++
            Log.i(TAG, "recovery attempt #$attemptIndex")
            l.onRecoverAttempt(attemptIndex)
            if (!l.needsRecovery()) {
                Log.i(TAG, "recovered at attempt #$attemptIndex")
                attemptIndex = 0
            } else {
                postNextAttempt()
            }
        }, delay)
    }
}
