/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import androidx.annotation.Keep

/**
 * Runs as root outside the app (`CLASSPATH=<apk> app_process / org.microg.gms.ui.RootLockTool ...`,
 * see [RootKeepAlive]) and talks to Xiaomi's system ProcessManager, which only a privileged caller
 * may use. Its classes belong to the Xiaomi framework, so they are reached by reflection.
 *
 * Arguments: `<modes> <userId> <package>...`, modes being a comma separated list of:
 * - `lock` / `unlock`: the padlock on a Recents card. The launcher keeps locked apps out of its
 *   "clear all" kill (and their card stays in Recents).
 * - `keep` / `unkeep`: put the packages on (or take them off) the system's cloud white list. The
 *   cleaner behind "clear all", swiping a card away and Power Keeper's clean-up skips every process
 *   of a package on that list, while the card still leaves Recents.
 *
 * It prints one line per mode and package.
 */
@Keep
object RootLockTool {
    private val PACKAGE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val manager = Class.forName("miui.process.ProcessManager")
            val modes = args[0].split(',')
            val userId = args[1].toInt()
            val packages = args.drop(2)
            for (mode in modes) {
                try {
                    when (mode) {
                        "lock", "unlock" -> lock(manager, mode == "lock", userId, packages)
                        "keep", "unkeep" -> keep(manager, mode == "keep", packages)
                    }
                } catch (t: Throwable) {
                    println("$mode: error ${t.cause ?: t}")
                }
            }
        } catch (t: Throwable) {
            // Not a Xiaomi device, or the call was refused
            println("error: ${t.cause ?: t}")
        }
    }

    private fun lock(manager: Class<*>, wanted: Boolean, userId: Int, packages: List<String>) {
        val isLocked = manager.getMethod("isLockedApplication", String::class.java, Int::class.javaPrimitiveType)
        val setLocked = manager.getMethod("updateApplicationLockedState", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        for (pkg in packages) {
            if (isLocked.invoke(null, pkg, userId) != wanted) setLocked.invoke(null, pkg, userId, wanted)
            println("${if (wanted) "lock" else "unlock"} $pkg: locked=${isLocked.invoke(null, pkg, userId)}")
        }
    }

    /**
     * `ProcessManager.updateCloudData` replaces the system's cloud lists. So the lists that can be
     * read (`dumpsys ProcessManager`: CL = white list, FA = fast boot, SPAL = secretly protected)
     * are passed back unchanged, with only our packages added to or removed from the white list.
     * Nothing is sent when the dump cannot be read, so the system's own list is never wiped.
     */
    private fun keep(manager: Class<*>, add: Boolean, packages: List<String>) {
        val sections = readDump() ?: run {
            println("keep: cannot read the ProcessManager state, nothing changed")
            return
        }
        val current = sections["CL"].orEmpty()
        val wanted = if (add) current + packages.filter { it !in current } else current - packages.toSet()
        if (wanted == current) {
            println("keep: ${packages.joinToString()} already ${if (add) "on" else "off"} the white list")
        } else {
            val dataClass = Class.forName("miui.process.ProcessCloudData")
            val data = dataClass.getDeclaredConstructor().newInstance()
            dataClass.getMethod("setCloudWhiteList", List::class.java).invoke(data, wanted)
            sections["FA"]?.takeIf { it.isNotEmpty() }?.let { dataClass.getMethod("setFastBootList", List::class.java).invoke(data, it) }
            sections["SPAL"]?.takeIf { it.isNotEmpty() }?.let { dataClass.getMethod("setSecretlyProtectAppList", List::class.java).invoke(data, it) }
            manager.getMethod("updateCloudData", dataClass).invoke(null, data)
            println("keep: white list now ${wanted.size} packages (${if (add) "added" else "removed"} ${packages.joinToString()})")
        }
        // An earlier version added packages to the "one push" black set, which makes them killable; undo that
        if (add) {
            try {
                val push = manager.getMethod("onePushUpdateWhitelist", String::class.java, Boolean::class.javaPrimitiveType)
                packages.forEach { push.invoke(null, it, false) }
            } catch (_: Throwable) {
            }
        }
    }

    /** The CL, FA and SPAL lists of `dumpsys ProcessManager`, or null when the dump is not readable. */
    private fun readDump(): Map<String, List<String>>? {
        val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "ProcessManager"))
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        if (lines.none { it.trim() == "Process Policy:" }) return null
        val result = HashMap<String, MutableList<String>>()
        var section: String? = null
        for (line in lines) {
            if (line.isBlank()) continue
            if (!line[0].isWhitespace()) {
                // A header at the start of the line, e.g. "CL:"
                section = line.trim().removeSuffix(":").takeIf { line.trim().endsWith(":") }
                continue
            }
            val entry = line.trim()
            if (section in listOf("CL", "FA", "SPAL") && PACKAGE.matches(entry)) {
                result.getOrPut(section!!) { ArrayList() }.add(entry)
            }
        }
        return result
    }
}
