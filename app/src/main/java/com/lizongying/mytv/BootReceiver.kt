package com.lizongying.mytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive action=$action bootStartup=${SP.bootStartup}")
        if (action in BOOT_ACTIONS && SP.bootStartup) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        // QuickBoot / MIUI STR wake are OEM-specific; MIUI TV filters BOOT_COMPLETED
        // for non-whitelisted sideloaded apps, so listen to OEM variants as well.
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "mitv.action.STR_BOOT_COMPLETED"
        )
    }

}
