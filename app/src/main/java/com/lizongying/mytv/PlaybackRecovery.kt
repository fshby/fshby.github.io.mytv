package com.lizongying.mytv

import android.os.Handler
import android.os.Looper

/**
 * 网络恢复 -> 播放器自愈的桥。
 * MainActivity 的 ConnectivityManager.NetworkCallback 收到 onAvailable 后
 * 通过这里通知 PlayerFragment 对当前频道做一次 prepare+play；
 * 若播放本来正常，PlayerFragment 侧的 isPlaying 守卫会直接忽略。
 */
object PlaybackRecovery {

    private val handler = Handler(Looper.getMainLooper())
    private const val SETTLE_MS = 1000L

    /** PlayerFragment 注册；MainActivity 销毁时无需清理（弱耦合，随进程生命周期） */
    @Volatile
    var listener: (() -> Unit)? = null

    /** 网络恢复后延迟 1 秒再触发，给路由/DNS 一点稳定时间 */
    fun onNetworkRestored() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            listener?.invoke()
        }, SETTLE_MS)
    }
}
