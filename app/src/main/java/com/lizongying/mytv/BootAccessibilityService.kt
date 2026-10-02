package com.lizongying.mytv

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 *
 * 2026-10-02 唤醒路径适配（本轮，修正真机实测缺陷）：
 *  - **开机窗口不再卡死唤醒拉起**：原实现把所有拉起统一限制在
 *    `elapsedRealtime() <= 10min`，本机实测 uptime 达 4318s（72min）时
 *    服务 `connected` 却拒绝拉起（日志只有 connected、无 launching）→
 *    STR 待机唤醒后永远回不到应用。现改为「开机窗口 / 唤醒窗口」双判据。
 *  - 新增 **ACTION_SCREEN_ON / SCREEN_OFF 运行时接收器**：屏幕点亮即视为
 *    「本次是唤醒」，在唤醒窗口内允许拉起（屏幕点亮是 STR 唤醒的唯一可靠
 *    信号；manifest 声明收不到该受保护广播，只能运行时注册）。
 *  - 新增 `appSeenInWake` 去重：唤醒后一旦确认本应用已回到前台，就不再
 *    对后续 home 事件重复拉起，避免用户主动按主页键时被反复抢回。
 */
class BootAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 是否已通过无障碍事件确认「本应用在前台」，用于跳过无谓的兜底重拉 */
    @Volatile
    private var appSeen = false

    /** 本次「屏幕点亮（唤醒）」之后是否已经确认本应用回到前台 */
    @Volatile
    private var appSeenInWake = false

    /** 最近一次屏幕点亮的 elapsedRealtime（0 = 未知） */
    @Volatile
    private var screenOnAt = 0L

    /** 最近一次屏幕熄灭的 elapsedRealtime（0 = 未知） */
    @Volatile
    private var screenOffAt = 0L

    /**
     * 屏幕亮/灭监听。SCREEN_ON 是受保护广播：manifest 声明收不到，且 stopped
     * 态应用一律被排除；只有「进程存活 + 运行时注册」才能收到，这也正好符合
     * 我们的前提（服务活着才谈得上拉起）。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = SystemClock.elapsedRealtime()
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnAt = now
                    appSeenInWake = false
                    Log.i(TAG, "screen on → wake context armed")
                    maybeLaunch(REASON_WAKE)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = now
                    appSeen = false
                    Log.i(TAG, "screen off")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected, bootStartup=${SP.bootStartup}")
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            )
        }.onFailure { Log.w(TAG, "register screen receiver failed", it) }
        // 服务能连上说明授权在；顺手补一次自愈（极低频、幂等）
        BootA11y.rearmIfMissing(this)
        maybeLaunch(REASON_CONNECTED)
        // 兜底重试：拉起请求可能被桌面抢占或被系统静默丢弃，窗口内再补两次
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ maybeLaunch(RETRY_PREFIX + "3s") }, RETRY_FIRST_MS)
        handler.postDelayed({ maybeLaunch(RETRY_PREFIX + "15s") }, RETRY_SECOND_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) {
            // 自己已在前台，后续兜底重试/唤醒补拉都不必再做
            appSeen = true
            appSeenInWake = true
        } else if (pkg in HOME_PACKAGES) {
            maybeLaunch(REASON_HOME_VISIBLE)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    /**
     * 拉起判定。三类窗口：
     *  - boot 窗口：`elapsedRealtime() <= 10min` —— 开机后的常规自启（含首启/慢启动）。
     *  - wake 窗口：最近 [WAKE_WINDOW_MS] 内屏幕点亮（ACTION_SCREEN_ON）—— STR 待机
     *    唤醒。**不受 boot 窗口限制**，因为长待机后 uptime 早已远超 10min（本机实测
     *    uptime 4318s 时旧逻辑直接 return，导致唤醒永不回应用）。
     *  - resume 窗口：最近 [WAKE_WINDOW_MS] 内屏幕曾熄灭（SCREEN_ON 未被投递时的兜底）。
     */
    private fun maybeLaunch(reason: String) {
        if (!SP.bootStartup) {
            Log.i(TAG, "skip($reason): bootStartup=off")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val inBootWindow = now <= LAUNCH_WINDOW_MS
        val inWakeWindow = screenOnAt > 0 && now - screenOnAt <= WAKE_WINDOW_MS
        val inResumeWindow = screenOffAt > 0 && now - screenOffAt <= WAKE_WINDOW_MS
        val inAnyWake = inWakeWindow || inResumeWindow

        val allowed = when {
            // 兜底重试：仅开机窗口内且尚未确认本应用在前台
            reason.startsWith(RETRY_PREFIX) -> inBootWindow && !appSeen
            reason == REASON_CONNECTED -> inBootWindow
            // 屏幕点亮：唤醒的第一时间就拉起，不等桌面窗口事件
            reason == REASON_WAKE -> inAnyWake || inBootWindow
            // 桌面可见：唤醒窗口内、且本次唤醒还没把我们拉起来过（用户主动按主页键时不抢）
            reason == REASON_HOME_VISIBLE -> (inAnyWake && !appSeenInWake) || (inBootWindow && !appSeen)
            else -> inBootWindow
        }
        if (!allowed) {
            Log.i(
                TAG,
                "skip($reason): boot=$inBootWindow wake=$inWakeWindow resume=$inResumeWindow" +
                    " appSeen=$appSeen appSeenInWake=$appSeenInWake uptime=${now / 1000}s"
            )
            return
        }
        // 唤醒路径用更短的节流（桌面窗口事件可能紧随屏幕点亮而来）
        val throttle = if (inAnyWake) WAKE_THROTTLE_MS else THROTTLE_MS
        if (now - lastLaunch < throttle) return
        lastLaunch = now
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

        private const val REASON_CONNECTED = "connected"
        private const val REASON_WAKE = "screen_on"
        private const val REASON_HOME_VISIBLE = "home_visible"
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

        /** 开机窗口：仅用于「开机后自启」与兜底重试 */
        private const val LAUNCH_WINDOW_MS = 10 * 60 * 1000L

        /** 唤醒窗口：屏幕点亮后这段时间内视为「本次唤醒」 */
        private const val WAKE_WINDOW_MS = 2 * 60 * 1000L

        private const val THROTTLE_MS = 3_000L
        private const val WAKE_THROTTLE_MS = 1_200L
        private const val RETRY_FIRST_MS = 3_000L
        private const val RETRY_SECOND_MS = 15_000L

        @Volatile
        private var lastLaunch = 0L
    }

}
