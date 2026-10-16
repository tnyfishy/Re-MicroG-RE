/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import androidx.annotation.Keep

/**
 * Runs as root outside the app (`CLASSPATH=<apk> app_process / org.microg.gms.ui.RootLockTool ...`,
 * see [RootKeepAlive]) and "locks" apps in Xiaomi's Recents, like the padlock on a Recents card.
 *
 * The launcher's Recents puts locked apps on the white list of its "clear all" and swipe-away kill,
 * and Power Keeper's own kills skip them (`ProcessManager.isLockedApplication`). The lock lives in
 * the system's ProcessManager, which only a privileged caller can change; the class is looked up by
 * reflection because it belongs to the Xiaomi framework.
 *
 * Arguments: `<userId> <package>...`. It prints one line per package.
 */
@Keep
object RootLockTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val manager = Class.forName("miui.process.ProcessManager")
            val isLocked = manager.getMethod("isLockedApplication", String::class.java, Int::class.javaPrimitiveType)
            val update = manager.getMethod("updateApplicationLockedState", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val userId = args[0].toInt()
            for (pkg in args.drop(1)) {
                if (isLocked.invoke(null, pkg, userId) == true) {
                    println("lock $pkg: already locked")
                    continue
                }
                update.invoke(null, pkg, userId, true)
                println("lock $pkg: now ${isLocked.invoke(null, pkg, userId)}")
            }
        } catch (t: Throwable) {
            // Not a Xiaomi device, or the call was refused
            println("lock error: ${t.cause ?: t}")
        }
    }
}
