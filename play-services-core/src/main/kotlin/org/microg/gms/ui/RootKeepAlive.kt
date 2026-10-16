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

    // Options inside root mode. Each is a file with 1 or 0 (the push service and the watchdog run in
    // other processes, where shared preferences would not be up to date). Missing: the default.
    const val OPTION_LOCK = "lock"           // padlock in Recents (the card stays in Recents)
    const val OPTION_PROTECT = "protect"     // keep the processes when swiping / "clear all" (system white list)
    const val OPTION_AUTOSTART = "autostart" // start the apps in the background when they are not running

    private fun optionDefault(name: String) = name != OPTION_LOCK

    @JvmStatic
    fun getOption(context: Context, name: String): Boolean {
        val value = try {
            File(context.filesDir, "root_opt_$name").readText().trim()
        } catch (_: Exception) {
            return optionDefault(name)
        }
        return value == "1"
    }

    @JvmStatic
    fun setOption(context: Context, name: String, enabled: Boolean) {
        File(context.filesDir, "root_opt_$name").writeText(if (enabled) "1" else "0")
        if (!isEnabled(context)) return
        val appContext = context.applicationContext ?: context
        Thread({
            // Switching an option off also takes the change back
            val undo = when {
                name == OPTION_LOCK && !enabled -> "unlock"
                name == OPTION_PROTECT && !enabled -> "unkeep"
                else -> null
            }
            if (undo != null) runTool(appContext, listOf(undo), listOf(appContext.packageName) + readPushApps(appContext))
            apply(appContext)
        }, "RootKeepAliveOption").start()
    }

    private fun readPushApps(context: Context): List<String> = try {
        File(context.filesDir, PUSH_APPS_FILE).readLines().filter { PACKAGE_NAME.matches(it) }
    } catch (_: Exception) {
        emptyList()
    }

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
        synchronized(shellLock) { closeShell() }
    }

    // A root shell that stays open, so waking an app for a message costs milliseconds, not a new su
    private val shellLock = Any()
    private var shell: Process? = null
    private var shellInput: java.io.BufferedWriter? = null
    private val shellDone = java.util.concurrent.LinkedBlockingQueue<Boolean>()
    private val lastWake = HashMap<String, Long>()

    /**
     * Called right before a push message is handed to [packageName]: lets the app run and use the
     * network for a while, like the allowlist real Play Services grants. Does nothing without root mode.
     */
    @JvmStatic
    fun wakeApp(context: Context, packageName: String) {
        if (!PACKAGE_NAME.matches(packageName) || !isEnabled(context)) return
        synchronized(shellLock) {
            val now = SystemClock.elapsedRealtime()
            if (now - (lastWake[packageName] ?: 0L) < 2000L) return
            lastWake[packageName] = now
            try {
                val writer = openShell() ?: return
                shellDone.clear()
                writer.write("cmd deviceidle tempwhitelist -d 30000 $packageName >/dev/null 2>&1; " +
                        "cmd activity set-standby-bucket $packageName active >/dev/null 2>&1; echo done")
                writer.newLine()
                writer.flush()
                // Short wait: the message should not be held back long if root is slow
                shellDone.poll(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot wake $packageName", e)
                closeShell()
            }
        }
    }

    private fun openShell(): java.io.BufferedWriter? {
        val running = shell?.let {
            try {
                it.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        } ?: false
        if (running) return shellInput
        closeShell()
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            return null
        }
        shell = process
        shellInput = process.outputStream.bufferedWriter()
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { if (it == "done") shellDone.offer(true) }
            } catch (_: Exception) {
            }
        }, "RootShellReader").start()
        return shellInput
    }

    private fun closeShell() {
        try {
            shell?.destroy()
        } catch (_: Exception) {
        }
        shell = null
        shellInput = null
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
        runAsRoot(listOf(POWER_KEEPER_FUNCTION.replace('§', '$'), "pk $pkg"))
        val pushApps = allowPushApps(context)
        // Earlier versions locked the apps unconditionally: when the lock option is off, undo that once
        val migrated = File(context.filesDir, "root_lock_migrated")
        if (!migrated.exists()) {
            migrated.writeText("1")
            if (!getOption(context, OPTION_LOCK)) runTool(context, listOf("unlock"), listOf(pkg) + pushApps)
        }
        val modes = ArrayList<String>()
        if (getOption(context, OPTION_LOCK)) modes.add("lock")
        if (getOption(context, OPTION_PROTECT)) modes.add("keep")
        runTool(context, modes, listOf(pkg) + pushApps)
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
        val apk = context.applicationInfo.sourceDir ?: return
        if (!PACKAGE_NAME.matches(pkg) || !Regex("^[A-Za-z0-9_./-]+$").matches(dir) ||
                !Regex("^[A-Za-z0-9_./=~+-]+$").matches(apk)) return
        val script = WATCHDOG
                .replace("@PKFUNC@", POWER_KEEPER_FUNCTION)
                .replace("@APK@", apk)
                .replace("@TOOL@", RootLockTool::class.java.name)
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
    private fun allowPushApps(context: Context): List<String> {
        val packages = try {
            val database = GcmDatabase(context)
            try {
                database.appList.filter { it.allowRegister }.map { it.packageName }
            } finally {
                database.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read push apps", e)
            return emptyList()
        }
        // Package names go into a shell command: only accept real package names
        val safe = packages.filter { PACKAGE_NAME.matches(it) }
        // The watchdog re-applies the exemptions of these apps from time to time
        try {
            File(context.filesDir, PUSH_APPS_FILE).writeText(safe.joinToString("\n"))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write the push app list", e)
        }
        if (safe.isEmpty()) return safe
        runAsRoot(listOf(POWER_KEEPER_FUNCTION.replace('§', '$')) + safe.map { "pk $it" })
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
        return safe
    }

    /** Runs [RootLockTool] as root for the packages (see there for the modes); the result is logged. */
    private fun runTool(context: Context, modes: List<String>, packages: List<String>) {
        if (modes.isEmpty()) return
        val apk = context.applicationInfo.sourceDir ?: return
        if (!Regex("^[A-Za-z0-9_./=~+-]+$").matches(apk)) return
        val list = packages.filter { PACKAGE_NAME.matches(it) }.distinct().joinToString(" ")
        if (list.isEmpty()) return
        val output = runAsRoot(listOf(
                "CLASSPATH=$apk app_process /system/bin ${RootLockTool::class.java.name} ${modes.joinToString(",")} 0 $list"
        ))
        Log.d(TAG, "Root tool ${modes}: ${output?.trim()}")
    }

    /**
     * Xiaomi's Power Keeper keeps a per-app "background control" (userTable.bgControl). Apps set to
     * "noRestrict" are skipped by its screen-off clean-up and, through the MILLET_NO_RESTRICT_APP
     * setting that Power Keeper writes from this table, by the system freezer. This shell function
     * sets an app to "noRestrict" (only when it is not already, so other columns stay untouched).
     * It does nothing on devices without Power Keeper. § stands for the shell's dollar sign.
     */
    private const val POWER_KEEPER_FUNCTION = """
PKX=0
cmd package path com.miui.powerkeeper >/dev/null 2>&1 && PKX=1
pk() {
  [ §PKX = 1 ] || return 0
  r=§(content query --uri content://com.miui.powerkeeper.configure/userTable --projection bgControl --where "pkgName='§1'" 2>/dev/null)
  case "§r" in
    *bgControl=noRestrict*) ;;
    *) content insert --uri content://com.miui.powerkeeper.configure/userTable --bind userId:i:0 --bind pkgName:s:§1 --bind bgControl:s:noRestrict >/dev/null 2>&1 ;;
  esac
}
"""

    // § stands for the shell's dollar sign, @NAME@ for values filled in by startWatchdog
    private const val WATCHDOG = """
PKG=@PKG@
DIR=@DIR@
echo §§ > §DIR/root_watchdog.pid
# Leave the app's process group, so killing the app does not kill this loop with it
for f in /acct/cgroup.procs /dev/cg2_bpf/cgroup.procs /sys/fs/cgroup/cgroup.procs; do
  [ -w §f ] && echo §§ > §f 2>/dev/null
done
@PKFUNC@
# opt NAME DEFAULT: is the root option on (files hold 1 or 0)?
opt() {
  v=§(cat §DIR/root_opt_§1 2>/dev/null)
  [ -z "§v" ] && v=§2
  [ "§v" = 1 ]
}
# Puts this app and the push apps on the system's cleaner white list again when it was replaced
# (the cleaner behind "clear all" and swiping skips every package on that list)
keepcheck() {
  [ "§(cat §DIR/root_opt_protect 2>/dev/null)" = 0 ] && return 0
  d=§(dumpsys ProcessManager 2>/dev/null | awk '/^CL:/{f=1;next} /^[^ ]/{f=0} f{gsub(/ /,"");print}')
  apps="§PKG §(cat §DIR/root_push_apps 2>/dev/null)"
  need=0
  for a in §apps; do echo "§d" | grep -qx "§a" || need=1; done
  [ §need = 1 ] && CLASSPATH=@APK@ app_process /system/bin @TOOL@ keep 0 §apps >/dev/null 2>&1
}
# Starts an app's process in the background through its Firebase messaging service
startapp() {
  c=§(cmd package query-services --components -a com.google.firebase.MESSAGING_EVENT -p §1 2>/dev/null | head -n 1)
  [ -n "§c" ] && am start-service -n "§c" -a com.google.firebase.MESSAGING_EVENT >/dev/null 2>&1
}
tick=0
fails=0
while [ -f §DIR/root_keep_alive ] && [ -d §DIR ]; do
  if opt autostart 1 && [ -f §DIR/push_running ] && ! pidof §PKG:persistent >/dev/null 2>&1; then
    am start-foreground-service -n §PKG/@SERVICE@ -a @ACTION@ --ez @FOREGROUND@ true >/dev/null 2>&1
    fails=§((fails + 1))
    if [ §fails -ge 3 ]; then sleep 60; fi
  else
    fails=0
  fi
  tick=§((tick + 1))
  if [ §((tick % 12)) -eq 0 ]; then
    keepcheck
    for p in §(pidof §PKG:persistent) §(pidof §PKG); do echo -1000 > /proc/§p/oom_score_adj 2>/dev/null; done
    for a in §(cat §DIR/root_push_apps 2>/dev/null); do
      am set-standby-bucket §a active >/dev/null 2>&1
      cmd appops set §a RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
      # an app that was killed (swiped away, "clear all") comes back in the background
      if opt autostart 1 && [ -f §DIR/push_running ]; then
        pidof §a >/dev/null 2>&1 || startapp §a
      fi
    done
  fi
  # about every 10 minutes: keep Power Keeper from restricting this app and the push apps
  if [ §((tick % 120)) -eq 0 ]; then
    pk §PKG
    for a in §(cat §DIR/root_push_apps 2>/dev/null); do pk §a; done
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
