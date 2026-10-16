/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import androidx.annotation.Keep

/**
 * Runs as root outside the app (`CLASSPATH=<apk> app_process / org.microg.gms.ui.RootLockTool ...`,
 * see [RootKeepAlive]) and calls Xiaomi's system ProcessManager, which only a privileged caller can
 * use. The class is looked up by reflection because it belongs to the Xiaomi framework.
 *
 * Arguments: `<modes> <userId> <package>...`, modes being a comma separated list of:
 * - `lock` / `unlock`: the padlock on a Recents card. The launcher keeps locked apps out of its
 *   "clear all" and swipe-away kill (and their card stays in Recents); Power Keeper skips them too.
 * - `protect` / `unprotect`: `onePushUpdateWhitelist`, the system's list of push apps.
 *
 * It prints one line per mode and package.
 */
@Keep
object RootLockTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val manager = Class.forName("miui.process.ProcessManager")
            val isLocked = manager.getMethod("isLockedApplication", String::class.java, Int::class.javaPrimitiveType)
            val setLocked = manager.getMethod("updateApplicationLockedState", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val modes = args[0].split(',')
            val userId = args[1].toInt()
            for (pkg in args.drop(2)) {
                for (mode in modes) {
                    try {
                        when (mode) {
                            "lock", "unlock" -> {
                                val wanted = mode == "lock"
                                if (isLocked.invoke(null, pkg, userId) != wanted) setLocked.invoke(null, pkg, userId, wanted)
                                println("$mode $pkg: locked=${isLocked.invoke(null, pkg, userId)}")
                            }
                            "protect", "unprotect" -> {
                                val push = manager.getMethod("onePushUpdateWhitelist", String::class.java, Boolean::class.javaPrimitiveType)
                                println("$mode $pkg: ${push.invoke(null, pkg, mode == "protect")}")
                            }
                        }
                    } catch (t: Throwable) {
                        println("$mode $pkg: error ${t.cause ?: t}")
                    }
                }
            }
        } catch (t: Throwable) {
            // Not a Xiaomi device, or the call was refused
            println("error: ${t.cause ?: t}")
        }
    }
}
