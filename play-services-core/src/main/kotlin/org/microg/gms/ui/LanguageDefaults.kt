/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager

/**
 * The app starts in Vietnamese instead of following the system language. This only happens
 * once: afterwards the choice in the language setting (including "System default") is kept.
 */
object LanguageDefaults {
    private const val PREF_DEFAULT_LANGUAGE_APPLIED = "pref_default_language_applied"
    private const val DEFAULT_LANGUAGE_TAG = "vi"

    /** Call from [android.app.Application.onCreate]. */
    @JvmStatic
    fun applyOnce(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_DEFAULT_LANGUAGE_APPLIED, false)) return
        prefs.edit().putBoolean(PREF_DEFAULT_LANGUAGE_APPLIED, true).apply()
        // Someone who already picked a language keeps it
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(DEFAULT_LANGUAGE_TAG))
    }
}
