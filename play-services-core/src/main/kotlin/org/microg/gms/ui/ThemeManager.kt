/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.google.android.gms.R

/**
 * App theme: light, dark, AMOLED (pure black) or follow the system. The choice lives in the
 * default shared preferences, next to the other options of the settings screen.
 */
object ThemeManager {
    const val PREF_THEME_MODE = "pref_theme_mode"

    private const val MODE_SYSTEM = "system"
    private const val MODE_LIGHT = "light"
    private const val MODE_DARK = "dark"
    private const val MODE_AMOLED = "amoled"

    private fun mode(context: Context): String =
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM

    /** Call once from [Application.onCreate]. */
    @JvmStatic
    fun install(application: Application) {
        applyNightMode(application)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyAmoledOverlay(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Before Android 10 there is no pre-created callback; this still runs before setContentView
                if (Build.VERSION.SDK_INT < 29) applyAmoledOverlay(activity)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Applies the light/dark choice. AMOLED is dark with the surfaces turned black. */
    @JvmStatic
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(when (mode(context)) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK, MODE_AMOLED -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    private fun applyAmoledOverlay(activity: Activity) {
        if (mode(activity) == MODE_AMOLED) {
            activity.theme.applyStyle(R.style.ThemeOverlay_ReMicroG_Amoled, true)
        }
    }
}
