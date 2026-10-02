package com.lizongying.mytv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Boot autostart fallback for OEMs that filter BOOT_COMPLETED for sideloaded
 * apps (e.g. MIUI TV). Accessibility services are system-bound at boot and do
 * not depend on broadcast delivery, so when enabled they run shortly after
 * boot; use that moment to bring the app to the foreground.
 *
 * 2026-10-02 兼容适配：
 *  - 触发窗口 5min → 10min（系统升级后首启 / 开机很慢时不再整场错过）
 *  - 节流 10s → 3s（首次 startActivity 被桌面覆盖后仍有机会立刻补上）
 *  - 桌面可见判断由单一硬编码包名改为候选集合（换机型/固件不再静默失效）
 *  - 服务连接后追加两次延时兜底重试，不再依赖「系统重启 a11y 服务」这一
 *    未文档化行为来补救
 */
class BootAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 是否已通过无障碍事件确认「本应用在前台」，用于跳过无谓的兜底重拉起 */
    @Volatile
    private var appSeen = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected, bootStartup=${SP.bootStartup}")
        // 服务能连上说明授权在；顺手补一次自愈（极低频、幂等）
        BootA11y.rearmIfMissing(this)
        maybeLaunch("connected")
        // 兜底重试：拉起请求可能被桌面抢占或被系统静默丢弃，窗口内再补两次
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ maybeLaunch("retry_3s") }, RETRY_FIRST_MS)
        handler.postDelayed({ maybeLaunch("retry_15s") }, RETRY_SECOND_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) {
            // 自己已在前台，后续兜底重试不必再拉起
            appSeen = true
        } else if (pkg in HOME_PACKAGES) {
            maybeLaunch("home_visible")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun maybeLaunch(reason: String) {
        if (!SP.bootStartup) return
        val uptime = SystemClock.elapsedRealtime()
        // Only act shortly after boot so manually toggling the service on a
        // long-running system never launches the app.
        if (uptime > LAUNCH_WINDOW_MS) return
        if (uptime - lastLaunch < THROTTLE_MS) return
        // 兜底重试只在「未确认已在前台」时才拉起，避免把已启动的界面再拉一次
        if (reason.startsWith(RETRY_PREFIX) && appSeen) return
        lastLaunch = uptime
        Log.i(TAG, "launching app, reason=$reason")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "startActivity failed, reason=$reason", it) }
    }

    companion object {
        private const val TAG = "BootA11y"

        private const val RETRY_PREFIX = "retry_"

        /**
         * 桌面/主屏包名候选集合。`home_visible` 只是 onServiceConnected 之外的
         * 次要触发通道，用集合代替单一硬编码包名以提升机型移植性。
         */
        private val HOME_PACKAGES = setOf(
            "com.mitv.tvhome",                    // 小米电视 MIUI TV
            "com.android.tv.home",                // AOSP TV
            "com.google.android.tvlauncher",      // Google TV
        )

        private const val LAUNCH_WINDOW_MS = 10 * 60 * 1000L
        private const val THROTTLE_MS = 3_000L
        private const val RETRY_FIRST_MS = 3_000L
        private const val RETRY_SECOND_MS = 15_000L

        @Volatile
        private var lastLaunch = 0L
    }

}
