package com.lizongying.mytv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Boot autostart fallback for OEMs that filter BOOT_COMPLETED for sideloaded
 * apps (e.g. MIUI TV). Accessibility services are system-bound at boot and do
 * not depend on broadcast delivery, so when enabled they run shortly after
 * boot; use that moment to bring the app to the foreground.
 */
class BootAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected, bootStartup=${SP.bootStartup}")
        // 服务能连上说明授权在；顺手补一次自愈（极低频、幂等）
        BootA11y.rearmIfMissing(this)
        maybeLaunch("connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == PKG_MIUI_HOME) {
            maybeLaunch("home_visible")
        }
    }

    override fun onInterrupt() {}

    private fun maybeLaunch(reason: String) {
        if (!SP.bootStartup) return
        val uptime = SystemClock.elapsedRealtime()
        // Only act shortly after boot so manually toggling the service on a
        // long-running system never launches the app.
        if (uptime > LAUNCH_WINDOW_MS) return
        if (uptime - lastLaunch < THROTTLE_MS) return
        lastLaunch = uptime
        Log.i(TAG, "launching app, reason=$reason")
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private const val TAG = "BootA11y"
        private const val PKG_MIUI_HOME = "com.mitv.tvhome"
        private const val LAUNCH_WINDOW_MS = 5 * 60 * 1000L
        private const val THROTTLE_MS = 10_000L

        @Volatile
        private var lastLaunch = 0L
    }

}
