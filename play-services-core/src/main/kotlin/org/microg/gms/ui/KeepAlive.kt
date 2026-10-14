/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.R

/**
 * Helps the user keep push notifications working in the background: an app cannot grant itself
 * these exemptions, so this opens the matching system screens and shows what is already done.
 */
object KeepAlive {
    private const val TAG = "GmsKeepAlive"
    private const val PREFS = "keep_alive"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_AUTOSTART_OPENED = "autostart_opened"

    // MIUI/HyperOS keeps the auto-start permission as a private app op
    private const val OP_MIUI_AUTO_START = 10008

    // Auto-start manager screens of common vendor ROMs. None of them is a public API.
    private val AUTO_START_SCREENS = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.powersaver.PowerSaverSettings"
    )

    private var setupDialog: AlertDialog? = null

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** True when the system does not pause this app (and revoke its permissions) for being unused. */
    fun isUnusedAppRestrictionDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            context.packageManager.isAutoRevokeWhitelisted
        } catch (e: Exception) {
            Log.w(TAG, e)
            false
        }
    }

    /**
     * Whether the app may start by itself. Only Xiaomi systems report it (through a private app
     * op); elsewhere the state cannot be read and this returns null.
     */
    fun isAutoStartAllowed(context: Context): Boolean? {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return null
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val checkOp = AppOpsManager::class.java.getMethod("checkOpNoThrow",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            checkOp.invoke(appOps, OP_MIUI_AUTO_START, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read auto-start state: $e")
            null
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        val packageUri = Uri.parse("package:" + activity.packageName)
        if (!tryStart(activity, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))) {
            if (!tryStart(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails(activity)
        }
    }

    fun openAutoStartSettings(activity: Activity) {
        for ((pkg, cls) in AUTO_START_SCREENS) {
            if (tryStart(activity, Intent().setComponent(ComponentName(pkg, cls)))) return
        }
        openAppDetails(activity)
    }

    /** Opens the system "App permissions" page, which holds the "Manage app if unused" switch. */
    fun openUnusedAppSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:" + activity.packageName))
            // Some ROMs route this intent to their own screen first; the permission controller is
            // the one that shows the App permissions page, so ask for it directly
            @Suppress("DEPRECATION")
            val controller = activity.packageManager.queryIntentActivities(intent, 0)
                    .firstOrNull { it.activityInfo.packageName.contains("permissioncontroller") }
            if (controller != null &&
                    tryStart(activity, Intent(intent).setClassName(controller.activityInfo.packageName, controller.activityInfo.name))) return
            if (tryStart(activity, Intent("android.intent.action.MANAGE_APP_PERMISSIONS")
                            .putExtra("android.intent.extra.PACKAGE_NAME", activity.packageName))) return
            if (tryStart(activity, intent)) return
        }
        openAppDetails(activity)
    }

    private fun openAppDetails(activity: Activity) {
        tryStart(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))
    }

    private fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.d(TAG, "Cannot open $intent: $e")
        false
    }

    /**
     * Shows the setup checklist until the user confirms it with "Done". Called on resume, so the
     * ticks are up to date after coming back from a system screen.
     */
    @JvmStatic
    fun showSetupDialogIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SETUP_DONE, false) || activity.isFinishing) return
        dismissSetupDialog()

        fun label(done: Boolean, text: Int) = (if (done) "✓  " else "○  ") + activity.getString(text)
        val items = arrayOf(
                label(isIgnoringBatteryOptimizations(activity), R.string.keep_alive_item_battery),
                // Without a readable state, count it as done once the user opened its screen
                label(isAutoStartAllowed(activity) ?: prefs.getBoolean(KEY_AUTOSTART_OPENED, false), R.string.keep_alive_item_autostart),
                label(isUnusedAppRestrictionDisabled(activity), R.string.keep_alive_item_unused)
        )
        val dialog = activity.buildAlertDialog()
                .setTitle(R.string.keep_alive_title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> requestIgnoreBatteryOptimizations(activity)
                        1 -> {
                            prefs.edit().putBoolean(KEY_AUTOSTART_OPENED, true).apply()
                            openAutoStartSettings(activity)
                        }
                        2 -> openUnusedAppSettings(activity)
                    }
                }
                .setPositiveButton(R.string.keep_alive_done) { _, _ ->
                    prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
                }
                .setNegativeButton(R.string.keep_alive_later, null)
                .create()
        setupDialog = dialog
        dialog.show()
    }

    @JvmStatic
    fun dismissSetupDialog() {
        setupDialog?.dismiss()
        setupDialog = null
    }
}
