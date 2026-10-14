/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Optional root mode for users whose device is rooted: it exempts the app from background
 * restrictions and shields its processes from the low-memory killer. Everything here only
 * touches this app and runs only after the user switched root mode on and root was granted.
 *
 * The enabled state is a flag file, because the push service runs in a separate process where
 * shared preferences would not be up to date.
 */
object RootKeepAlive {
    private const val TAG = "GmsRootKeepAlive"
    private const val FLAG_FILE = "root_keep_alive"
    private const val SU_TIMEOUT_MS = 15000L

    @JvmStatic
    fun isEnabled(context: Context): Boolean = File(context.filesDir, FLAG_FILE).exists()

    /** Asks for root and applies the measures. Blocking: call it off the main thread. */
    @JvmStatic
    fun enable(context: Context): Boolean {
        val id = runAsRoot(listOf("id")) ?: return false
        if (!id.contains("uid=0")) return false
        File(context.filesDir, FLAG_FILE).writeText("1")
        apply(context)
        return true
    }

    @JvmStatic
    fun disable(context: Context) {
        File(context.filesDir, FLAG_FILE).delete()
    }

    /** Re-applies the measures, e.g. when the push process restarted. Does nothing when off. */
    @JvmStatic
    fun applyIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        val appContext = context.applicationContext ?: context
        Thread({ apply(appContext) }, "RootKeepAlive").start()
    }

    private fun apply(context: Context) {
        val pkg = context.packageName
        val myPid = android.os.Process.myPid()
        val output = runAsRoot(listOf(
                // Not subject to Doze / app standby battery restrictions
                "dumpsys deviceidle whitelist +$pkg",
                "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                // Do not pause the app (or revoke its permissions) for being unused
                "cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",
                // Lets the app write system settings itself afterwards
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                // Never pick this app's processes first when memory runs low
                "echo -1000 > /proc/$myPid/oom_score_adj",
                "for p in \$(pidof $pkg) \$(pidof $pkg:persistent); do echo -1000 > /proc/\$p/oom_score_adj; done"
        ))
        Log.d(TAG, "Applied root keep-alive: ${output?.trim()}")
    }

    /** Runs the commands in one root shell. Returns its output, or null without root or on timeout. */
    private fun runAsRoot(commands: List<String>): String? {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            Log.d(TAG, "su is not available: $e")
            return null
        }
        try {
            process.outputStream.bufferedWriter().use { writer ->
                commands.forEach {
                    writer.write(it)
                    writer.newLine()
                }
                writer.write("exit")
                writer.newLine()
            }
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val deadline = SystemClock.elapsedRealtime() + SU_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    process.exitValue()
                    reader.join(1000)
                    return output.toString()
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(100)
                }
            }
            Log.w(TAG, "Root shell timed out")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Root shell failed", e)
            return null
        } finally {
            process.destroy()
        }
    }
}
