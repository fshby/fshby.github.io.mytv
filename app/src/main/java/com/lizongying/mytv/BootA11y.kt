package com.lizongying.mytv

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 无障碍自启动授权自愈。
 *
 * MiTV 系统行为（2026-10-02 真机+受控实验证实）：应用一旦被 force-stop
 * （切换电视信号源时 `str kill all third app`、后台一键清理、内存清理等），
 * 系统会立即把它从 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 中移除，
 * 导致下次开机无障碍自启动失效。而 force-stop 后应用处于 stopped 状态，
 * 广播 / Job / Alarm 全部不再投递，应用自身无法在被杀后主动恢复。
 *
 * 唯一可自救的时机是应用下次被拉起（开机自启成功后、或用户手动打开）。
 * `WRITE_SECURE_SETTINGS` 可通过 adb 一次性授予并跨重启/覆盖安装保留：
 * `pm grant com.fshby.mytv android.permission.WRITE_SECURE_SETTINGS`
 * 授予后应用即可在启动时检测授权丢失并补写回去（保留其它服务的条目）。
 *
 * 残留缺口：若切信号源/被清理后一直不打开应用直接重启，那一次开机仍不自启，
 * 手动打开一次即恢复武装。无 Root 下无解。
 */
object BootA11y {

    private const val TAG = "BootA11y"
    private const val SERVICE_CLASS = "com.lizongying.mytv.BootAccessibilityService"

    /** 应用启动时调用：自启动开关开着但系统授权丢失 → 补写（幂等，无权限时静默降级仅记日志） */
    fun rearmIfMissing(context: Context) {
        if (!SP.bootStartup) return
        try {
            val resolver = context.contentResolver
            val raw = Settings.Secure.getString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (isEnabled(raw, context.packageName)) return
            val merged = if (raw.isBlank()) {
                flatName(context)
            } else {
                raw.trim().trimEnd(':') + ":" + flatName(context)
            }
            val ok = Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged
            ) && Settings.Secure.putInt(
                resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
            if (ok) {
                Log.i(TAG, "grant revoked by system, restored by self-heal")
            } else {
                Log.w(TAG, "grant missing and not writable; run once: "
                        + "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "self-heal failed", e)
        }
    }

    /** 用户关闭 App 内自启动开关时调用：把我们自己从系统授权列表中摘除（不动其它服务） */
    fun disarm(context: Context) {
        try {
            val resolver = context.contentResolver
            val raw = Settings.Secure.getString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (!isEnabled(raw, context.packageName)) return
            val kept = raw.split(':')
                .map { it.trim() }
                .filter { it.isNotBlank() && !matches(it, context.packageName) }
                .joinToString(":")
            if (Settings.Secure.putString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept
                )
            ) {
                Log.i(TAG, "disarmed on user request")
            }
        } catch (e: Exception) {
            Log.w(TAG, "disarm failed", e)
        }
    }

    private fun flatName(context: Context): String =
        ComponentName(context.packageName, SERVICE_CLASS).flattenToString()

    /** 兼容 full（pkg/pkgPrefix.Class）与 short（pkg/.Class）两种扁平写法 */
    private fun isEnabled(raw: String, pkg: String): Boolean =
        raw.split(':').any { matches(it.trim(), pkg) }

    private fun matches(entry: String, pkg: String): Boolean =
        entry == ComponentName(pkg, SERVICE_CLASS).flattenToString() ||
                entry == ComponentName(pkg, SERVICE_CLASS).flattenToShortString()
}
