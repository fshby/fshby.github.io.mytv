package com.lizongying.mytv

import android.content.Context
import android.content.SharedPreferences

object SP {
    // Name of the sp file TODO Should use a meaningful name and do migrations
    private const val SP_FILE_NAME = "MainActivity"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    const val KEY_GRID = "grid"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    // guid
    private const val KEY_GUID = "guid"

    private lateinit var sp: SharedPreferences

    private var listener: OnSharedPreferenceChangeListener? = null

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun setOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        this.listener = listener
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, false)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, true)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, true)
        set(value) {
            if (value != this.time) {
                sp.edit().putBoolean(KEY_TIME, value).apply()
                listener?.onSharedPreferenceChanged(KEY_TIME)
            }
        }

    /**
     * 开机自启开关。**默认 true**。
     *
     * 默认值从 false 改为 true 是 2026-10-02 的兼容修复：清数据 / `pm clear` /
     * 卸载重装会同时抹掉 SP（开关回落默认值）与系统侧无障碍授权，而自愈函数
     * 以本开关为闸门 → 此前「默认 false + 授权被清」形成死结：重新打开应用也
     * 不会补授权，必须手动进设置页重开一次（源码级分析缺陷 C）。
     * 默认改为 true 后，清数据场景可自动恢复武装；用户**显式**关掉开关时值被
     * 持久化为 false，自愈会正确跳过，不违背用户意图。
     * 注意：实际武装仍需系统侧 `WRITE_SECURE_SETTINGS`（adb 授予）才生效。
     */
    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, true)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, false)
        set(value) {
            if (value != this.grid) {
                sp.edit().putBoolean(KEY_GRID, value).apply()
                listener?.onSharedPreferenceChanged(KEY_GRID)
            }
        }

    var itemPosition: Int
        get() = sp.getInt(KEY_POSITION, 0)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var guid: String
        get() = sp.getString(KEY_GUID, "") ?: ""
        set(value) = sp.edit().putString(KEY_GUID, value).apply()
}