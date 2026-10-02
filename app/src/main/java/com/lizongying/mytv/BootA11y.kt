package com.lizongying.mytv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 * 自启动「武装」需要同时满足四件事，缺一即开机不自启（见 status()）：
 *   ① App 内「开机自启」开关为开；
 *   ② `enabled_accessibility_services` 含本服务条目；
 *   ③ 无障碍总开关 `accessibility_enabled == 1`；   ← 2026-10-02 前为代码盲区
 *   ④ 持有 `WRITE_SECURE_SETTINGS`（自愈/一键修复的前提，否则只能 adb 手动写）。
 *
 * 残留缺口：若切信号源/被清理后一直不打开应用直接重启，那一次开机仍不自启，
 * 手动打开一次即恢复武装。无 Root 下无解。
 */
object BootA11y {

    private const val TAG = "BootA11y"
    private const val SERVICE_CLASS = "com.lizongying.mytv.BootAccessibilityService"

    /** 四项前置条件的当前快照（只读，不产生写入） */
    data class Status(
        /** ① 应用内「开机自启」开关 */
        val switchOn: Boolean,
        /** ② `enabled_accessibility_services` 是否含本服务条目 */
        val entryPresent: Boolean,
        /** ③ 无障碍总开关是否开启 */
        val masterOn: Boolean,
        /** ④ 是否持有 WRITE_SECURE_SETTINGS（自愈的前提） */
        val writable: Boolean,
    ) {
        /** 系统侧已武装 = 条目在 + 总开关开（缺一即便开机，系统也不会绑定服务） */
        val armed: Boolean get() = entryPresent && masterOn
    }

    /** 读取当前四项状态（只读）。任何一项读取异常都降级为「否」，不抛异常 */
    fun status(context: Context): Status {
        val resolver = context.contentResolver
        val raw = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val master = runCatching {
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }.getOrDefault(0)
        return Status(
            switchOn = SP.bootStartup,
            entryPresent = isEnabled(raw, context.packageName),
            masterOn = master == 1,
            writable = hasWriteSecureSettings(context),
        )
    }

    /**
     * 应用启动时调用：自启动开关开着但系统授权不完整 → 补齐。
     *
     * 判据同时校验「条目」与「无障碍总开关」两项。只比对条目会漏掉
     * 「列表里有我 + 总开关 = 0」的半写态，从而判定为已就绪并永久跳过修复
     * （2026-10-02 源码级分析缺陷 A）。
     *
     * @return true = 当前已就绪，或本次修复成功；false = 未开开关 / 无写权限 / 写入失败
     */
    fun rearmIfMissing(context: Context): Boolean {
        if (!SP.bootStartup) return false
        val st = status(context)
        if (st.armed) return true
        if (!st.writable) {
            Log.w(
                TAG, "grant incomplete (entry=${st.entryPresent} master=${st.masterOn}) "
                        + "and not writable; run once: adb shell pm grant "
                        + "${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
            return false
        }
        return arm(context)
    }

    /**
     * 强制武装（自愈与设置页「一键修复」共用）：补齐条目与总开关。
     * 两条写入**独立执行、各自校验**，任一条失败都会单独记录，
     * 不会被 `&&` 短路掩盖成一条含糊的 warning（缺陷 B）。
     */
    fun arm(context: Context): Boolean {
        val resolver = context.contentResolver

        // —— 写入 1：授权条目 ——
        var entryOk = runCatching {
            isEnabled(
                Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty(),
                context.packageName
            )
        }.getOrDefault(false)

        if (!entryOk) {
            val raw = runCatching {
                Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            val merged = if (raw.isBlank()) {
                flatName(context)
            } else {
                raw.trim().trimEnd(':') + ":" + flatName(context)
            }
            entryOk = runCatching {
                Settings.Secure.putString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged
                )
            }.getOrDefault(false)
            if (!entryOk) {
                Log.w(TAG, "write ENABLED_ACCESSIBILITY_SERVICES failed "
                        + "(missing WRITE_SECURE_SETTINGS?)")
            }
        }

        // —— 写入 2：无障碍总开关（与上一条解耦，不被短路跳过） ——
        var masterOk = runCatching {
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!masterOk) {
            masterOk = runCatching {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.getOrDefault(false)
            if (!masterOk) {
                Log.w(TAG, "write ACCESSIBILITY_ENABLED failed")
            }
        }

        val ok = entryOk && masterOk
        if (ok) {
            Log.i(TAG, "accessibility grant armed (entry=$entryOk master=$masterOk)")
        } else {
            Log.w(TAG, "accessibility grant incomplete (entry=$entryOk master=$masterOk)")
        }
        return ok
    }

    /**
     * 用户关闭 App 内自启动开关时调用：把我们自己从系统授权列表中摘除。
     * 只摘本服务条目，**绝不动无障碍总开关**——总开关是全局的，关掉会连带
     * 影响其它无障碍服务。
     */
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

    /** 是否已持有 WRITE_SECURE_SETTINGS（adb 一次性授予，跨重启/覆盖安装保留） */
    fun hasWriteSecureSettings(context: Context): Boolean {
        val perm = Manifest.permission.WRITE_SECURE_SETTINGS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        } else {
            context.packageManager.checkPermission(perm, context.packageName) ==
                    PackageManager.PERMISSION_GRANTED
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
