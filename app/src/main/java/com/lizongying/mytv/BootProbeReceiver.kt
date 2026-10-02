package com.lizongying.mytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Diagnostic receiver: logs every boot-adjacent broadcast that AOSP allows in
 * manifest receivers, without side effects. Used to find a broadcast that
 * MIUI TV actually delivers to sideloaded apps (BOOT_COMPLETED is filtered).
 */
class BootProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "PROBE action=${intent.action}")
    }

    companion object {
        private const val TAG = "BootProbeReceiver"
    }

}
