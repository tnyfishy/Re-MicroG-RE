/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager

/**
 * The app starts in Vietnamese instead of following the system language. This only happens
 * once: afterwards the choice in the language setting (including "System default") is kept.
 *
 * From Android 13 the system's own LocaleManager does it, which works from any context. Before
 * that AppCompat does it, and it needs an activity, so it waits for the first one.
 */
object LanguageDefaults {
    // v2: an earlier version marked itself done without having changed anything
    private const val PREF_DEFAULT_LANGUAGE_APPLIED = "pref_default_language_applied_v2"
    private const val DEFAULT_LANGUAGE_TAG = "vi"

    /** Call from [android.app.Application.onCreate] and from the main activity. */
    @JvmStatic
    fun applyOnce(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_DEFAULT_LANGUAGE_APPLIED, false)) return
        val done = try {
            if (Build.VERSION.SDK_INT >= 33) applyWithLocaleManager(context)
            else if (context is Activity) applyWithAppCompat()
            else false
        } catch (_: Exception) {
            false
        }
        if (done) prefs.edit().putBoolean(PREF_DEFAULT_LANGUAGE_APPLIED, true).apply()
    }

    private fun applyWithLocaleManager(context: Context): Boolean {
        val manager = context.getSystemService(LocaleManager::class.java) ?: return false
        // Someone who already picked a language keeps it
        if (manager.applicationLocales.isEmpty) {
            manager.applicationLocales = LocaleList.forLanguageTags(DEFAULT_LANGUAGE_TAG)
        }
        return true
    }

    private fun applyWithAppCompat(): Boolean {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(DEFAULT_LANGUAGE_TAG))
        }
        return true
    }
}
