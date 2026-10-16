/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.gcm

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * "Keep awake", the way chat apps stay connected: the push service runs as a foreground service
 * with a quiet notification and holds a CPU wake lock (and a Wi-Fi lock) while it is connected,
 * so heartbeats run on time and messages are not held back while the screen is off.
 *
 * It is on by default. The state is a flag file, because the push service runs in a separate
 * process where shared preferences would not be up to date. The same file name is read by
 * ForegroundServiceContext in the base module.
 */
object KeepAwake {
    private const val OFF_FLAG_FILE = "keep_awake_off"

    @JvmStatic
    fun isEnabled(context: Context): Boolean = !File(context.filesDir, OFF_FLAG_FILE).exists()

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        val flag = File(context.filesDir, OFF_FLAG_FILE)
        if (enabled) flag.delete() else flag.writeText("1")
        // Let the push service pick the new mode up (foreground notification, wake lock)
        if (GcmPrefs.get(context).isEnabled) {
            context.sendBroadcast(Intent(TriggerReceiver.FORCE_TRY_RECONNECT, null, context, TriggerReceiver::class.java))
        }
    }
}
