/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.microg.gms.gcm.GcmDatabase

/**
 * Lets the user pick an installed app and allow it to register for push notifications
 * before the app asks for it. Only user apps are listed unless system apps are shown.
 */
class PushNotificationAddAppFragment : PreferenceFragmentCompat() {
    private lateinit var database: GcmDatabase
    private lateinit var appsCategory: PreferenceCategory
    private lateinit var progress: Preference
    private var showSystemApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = GcmDatabase(context)
        showSystemApps = savedInstanceState?.getBoolean(STATE_SHOW_SYSTEM_APPS) ?: false
        setHasOptionsMenu(true)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_push_notifications_add_app)
        appsCategory = preferenceScreen.findPreference("prefcat_push_add_app") ?: appsCategory
        progress = preferenceScreen.findPreference("pref_push_add_app_progress") ?: progress
    }

    override fun onResume() {
        super.onResume()
        updateContent()
    }

    override fun onPause() {
        super.onPause()
        database.close()
    }

    private fun updateContent() {
        val context = requireContext()
        lifecycleScope.launchWhenResumed {
            val prefs = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(0)
                        .filter { it.packageName != context.packageName }
                        .filter { showSystemApps || !it.isSystemApp }
                        .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                        .mapIndexed { idx, info ->
                            val pref = AppIconPreference(context)
                            pref.applicationInfo = info
                            pref.key = "pref_push_add_app_" + info.packageName
                            pref.order = idx
                            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                                addApp(info.packageName)
                                true
                            }
                            pref
                        }
            }

            appsCategory.removeAll()
            prefs.forEachIndexed { index, pref ->
                pref.layoutResource = chooseLayoutForPosition(index, prefs.size)
                pref.isIconSpaceReserved = true
                appsCategory.addPreference(pref)
            }
            progress.isVisible = false
        }
    }

    private fun addApp(packageName: String) {
        lifecycleScope.launchWhenResumed {
            withContext(Dispatchers.IO) {
                database.noteAppKnown(packageName, true)
            }
            // Back to the Cloud Messaging screen, which reloads and shows the new app
            findNavController().popBackStack()
        }
    }

    private fun chooseLayoutForPosition(index: Int, total: Int): Int {
        return when {
            total <= 1 -> R.layout.preference_material_secondary_single
            total == 2 -> if (index == 0) R.layout.preference_material_secondary_top else R.layout.preference_material_secondary_bottom
            else -> when (index) {
                0 -> R.layout.preference_material_secondary_top
                total - 1 -> R.layout.preference_material_secondary_bottom
                else -> R.layout.preference_material_secondary_middle
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.push_add_app_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.menu_toggle_system_apps)?.setTitle(
                if (showSystemApps) R.string.push_add_app_hide_system_apps else R.string.push_add_app_show_system_apps)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_toggle_system_apps -> {
                showSystemApps = !showSystemApps
                requireActivity().invalidateOptionsMenu()
                updateContent()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_SYSTEM_APPS, showSystemApps)
    }

    companion object {
        private const val STATE_SHOW_SYSTEM_APPS = "show_system_apps"
    }
}

private val ApplicationInfo.isSystemApp: Boolean
    get() = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
