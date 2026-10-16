/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.microg.gms.common.ForegroundServiceContext
import org.microg.gms.gcm.GcmDatabase
import org.microg.gms.gcm.McsConstants
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
    private const val PUSH_APPS_FILE = "root_push_apps"
    private const val WATCHDOG_SCRIPT = "root_watchdog.sh"
    private const val WATCHDOG_PID = "root_watchdog.pid"
    private const val PUSH_SERVICE = "org.microg.gms.gcm.McsService"
    private const val SU_TIMEOUT_MS = 15000L
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

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
                // Xiaomi's "autostart" permission, and the most permissive standby bucket
                "cmd appops set $pkg 10008 allow",
                "am set-standby-bucket $pkg active",
                "am set-inactive $pkg false",
                // Lets the app write system settings itself afterwards
                "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
                // Never pick this app's processes first when memory runs low
                "echo -1000 > /proc/$myPid/oom_score_adj",
                "for p in \$(pidof $pkg) \$(pidof $pkg:persistent); do echo -1000 > /proc/\$p/oom_score_adj; done"
        ))
        Log.d(TAG, "Applied root keep-alive: ${output?.trim()}")
        allowPushApps(context)
        startWatchdog(context)
    }

    /**
     * A small root shell loop that outlives this app: when the system or a "clean up" button
     * (Recents "close all", a battery manager...) kills the push process, it starts the push
     * service again within seconds. It also keeps re-applying the exemptions of the push apps.
     * It stops by itself when root mode is switched off or the push service is stopped on purpose
     * (the service removes its "running" marker file then).
     */
    private fun startWatchdog(context: Context) {
        val pkg = context.packageName
        val dir = context.filesDir.absolutePath
        if (!PACKAGE_NAME.matches(pkg) || !Regex("^[A-Za-z0-9_./-]+$").matches(dir)) return
        val script = WATCHDOG
                .replace("@PKG@", pkg)
                .replace("@DIR@", dir)
                .replace("@SERVICE@", PUSH_SERVICE)
                .replace("@ACTION@", McsConstants.ACTION_CONNECT)
                .replace("@FOREGROUND@", ForegroundServiceContext.EXTRA_FOREGROUND)
                .replace('§', '$')
        val output = runAsRoot(listOf(
                "[ -f $dir/$WATCHDOG_PID ] && kill \$(cat $dir/$WATCHDOG_PID) 2>/dev/null",
                "cat > $dir/$WATCHDOG_SCRIPT <<'WATCHDOG_EOF'",
                script,
                "WATCHDOG_EOF",
                "nohup setsid sh $dir/$WATCHDOG_SCRIPT >/dev/null 2>&1 &"
        ))
        Log.d(TAG, "Started root watchdog: ${output?.trim()}")
    }

    /**
     * Apps receiving push messages are woken for each message by real Play Services through a
     * privileged temporary allowlist that a user-installed app cannot use. With root, exempt them
     * from battery restrictions instead, so their messages are handled right away.
     */
    private fun allowPushApps(context: Context) {
        val packages = try {
            val database = GcmDatabase(context)
            try {
                database.appList.filter { it.allowRegister }.map { it.packageName }
            } finally {
                database.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read push apps", e)
            return
        }
        // Package names go into a shell command: only accept real package names
        val safe = packages.filter { PACKAGE_NAME.matches(it) }
        // The watchdog re-applies the exemptions of these apps from time to time
        try {
            File(context.filesDir, PUSH_APPS_FILE).writeText(safe.joinToString("\n"))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write the push app list", e)
        }
        if (safe.isEmpty()) return
        val output = runAsRoot(safe.flatMap {
            listOf(
                    "dumpsys deviceidle whitelist +$it",
                    "cmd appops set $it RUN_ANY_IN_BACKGROUND allow",
                    "cmd appops set $it 10008 allow",
                    "am set-standby-bucket $it active",
                    "am set-inactive $it false"
            )
        })
        Log.d(TAG, "Allowed ${safe.size} push apps in background: ${output?.trim()}")
    }

    // § stands for the shell's dollar sign, @NAME@ for values filled in by startWatchdog
    private const val WATCHDOG = """
PKG=@PKG@
DIR=@DIR@
echo §§ > §DIR/root_watchdog.pid
# Leave the app's process group, so killing the app does not kill this loop with it
for f in /acct/cgroup.procs /dev/cg2_bpf/cgroup.procs /sys/fs/cgroup/cgroup.procs; do
  [ -w §f ] && echo §§ > §f 2>/dev/null
done
tick=0
fails=0
while [ -f §DIR/root_keep_alive ] && [ -d §DIR ]; do
  if [ -f §DIR/push_running ] && ! pidof §PKG:persistent >/dev/null 2>&1; then
    am start-foreground-service -n §PKG/@SERVICE@ -a @ACTION@ --ez @FOREGROUND@ true >/dev/null 2>&1
    fails=§((fails + 1))
    if [ §fails -ge 3 ]; then sleep 60; fi
  else
    fails=0
  fi
  tick=§((tick + 1))
  if [ §((tick % 12)) -eq 0 ]; then
    for p in §(pidof §PKG:persistent) §(pidof §PKG); do echo -1000 > /proc/§p/oom_score_adj 2>/dev/null; done
    for a in §(cat §DIR/root_push_apps 2>/dev/null); do
      am set-standby-bucket §a active >/dev/null 2>&1
      cmd appops set §a RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
    done
  fi
  sleep 5
done
rm -f §DIR/root_watchdog.pid
"""

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
