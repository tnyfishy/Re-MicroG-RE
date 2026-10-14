/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.app.Activity
import android.os.Build
import android.util.Log

/**
 * Asks the system to use the highest refresh rate the screen offers at its current resolution
 * (e.g. 120 Hz), so scrolling and touch feedback in the app stay smooth. It is only a request:
 * the system may still lower it, for example in battery saver.
 */
object HighRefreshRate {
    private const val TAG = "GmsHighRefreshRate"

    @JvmStatic
    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
            val current = display?.mode ?: return
            val best = display.supportedModes
                    .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                    .maxByOrNull { it.refreshRate } ?: return
            val attributes = activity.window.attributes
            attributes.preferredDisplayModeId = best.modeId
            attributes.preferredRefreshRate = best.refreshRate
            activity.window.attributes = attributes
        } catch (e: Exception) {
            Log.d(TAG, "Cannot request a high refresh rate: $e")
        }
    }
}
