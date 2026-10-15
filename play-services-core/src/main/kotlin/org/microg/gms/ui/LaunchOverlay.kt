/*
 * SPDX-FileCopyrightText: 2025, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.google.android.gms.R

/**
 * Cold-start screen: the app logo and a Material 3 loading indicator in its round container.
 * The logo pops in, the container fades in, and after a short moment everything scales up
 * slightly and fades out to reveal the settings.
 */
object LaunchOverlay {
    private const val SHOW_MS = 1000L

    @JvmStatic
    fun show(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val overlay = LayoutInflater.from(activity).inflate(R.layout.app_launch_overlay, content, false)
        content.addView(overlay)

        val logo = overlay.findViewById<View>(R.id.launch_logo)
        val container = overlay.findViewById<View>(R.id.launch_container)
        logo.alpha = 0f
        logo.scaleX = 0.7f
        logo.scaleY = 0.7f
        container.alpha = 0f
        container.scaleX = 0.6f
        container.scaleY = 0.6f

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
        container.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(150).setDuration(400)
                .setInterpolator(DecelerateInterpolator()).start()

        overlay.postDelayed({
            overlay.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(300)
                    .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                    .start()
        }, SHOW_MS)
    }
}
