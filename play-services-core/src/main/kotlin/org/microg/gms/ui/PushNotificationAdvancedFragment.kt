/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.gcm.*

class PushNotificationAdvancedFragment : PreferenceFragmentCompat() {
    private lateinit var pingInterval: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_gcm_advanced)
    }

    @SuppressLint("RestrictedApi")
    override fun onBindPreferences() {
        pingInterval = preferenceScreen.findPreference(PREF_PING_INTERVAL) ?: pingInterval

        pingInterval.setOnPreferenceClickListener {
            showPingIntervalDialog()
            true
        }
        findPreference<Preference>("pref_remove_all_registers")
            ?.setOnPreferenceClickListener {
                showRemoveRegistersDialog()
                true
            }
    }

    override fun onResume() {
        super.onResume()
        updateContent()
    }

    /** One ping interval for every network type; seconds, at least 1, or automatic. */
    private fun showPingIntervalDialog() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launchWhenResumed {
            val config = getGcmServiceInfo(appContext).configuration
            val current = config.wifi
            val input = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.gcm_ping_custom_hint)
                if (current >= GcmPrefs.CUSTOM_SECONDS_BASE) setText((current - GcmPrefs.CUSTOM_SECONDS_BASE).toString())
                else if (current > 0) setText((current * 60).toString())
            }
            val container = FrameLayout(requireContext()).apply {
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
            }
            requireContext().buildAlertDialog()
                    .setTitle(R.string.pref_gcm_ping_title)
                    .setMessage(R.string.gcm_ping_custom_message)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val seconds = input.text.toString().trim().toIntOrNull()
                        if (seconds != null && seconds >= 1) {
                            saveInterval(GcmPrefs.CUSTOM_SECONDS_BASE + seconds)
                        } else {
                            Toast.makeText(requireContext(), R.string.gcm_ping_custom_invalid, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNeutralButton(org.microg.gms.base.core.R.string.service_status_automatic) { _, _ -> saveInterval(0) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
        }
    }

    /** The same value is stored for every network type, so one setting rules them all. */
    private fun saveInterval(value: Int) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launchWhenResumed {
            setGcmServiceConfiguration(appContext, getGcmServiceInfo(appContext).configuration
                    .copy(mobile = value, wifi = value, roaming = value, other = value))
            updateContent()
        }
    }

    private fun updateContent() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launchWhenResumed {
            val serviceInfo = getGcmServiceInfo(appContext)
            pingInterval.summary = getSummaryString(serviceInfo.configuration.wifi, serviceInfo.learntWifiInterval)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showRemoveRegistersDialog() {
        val dialog = AlertDialog.Builder(requireContext()).setIcon(R.drawable.ic_unregister)
            .setTitle(R.string.gcm_remove_registers_dialog_title)
            .setMessage(R.string.gcm_remove_registers_dialog_message)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null).create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        LastCheckinInfo.clear(requireContext())
                        GcmDatabase(requireContext().applicationContext).resetDatabase()
                    }
                    Toast.makeText(requireContext(), R.string.gcm_remove_registers_toast_message, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun getSummaryString(value: Int, learnt: Int): String = when (value) {
        -1 -> getString(R.string.push_notifications_summary_off)
        0 -> getString(R.string.push_notifications_summary_automatic, getHeartbeatString(learnt))
        else -> getString(R.string.push_notifications_summary_manual, getHeartbeatString(GcmPrefs.intervalMs(value)))
    }

    private fun getHeartbeatString(heartbeatMs: Int): String {
        return if (heartbeatMs < 120000) {
            getString(R.string.push_notifications_summary_values_seconds, (heartbeatMs / 1000).toString())
        } else getString(R.string.push_notifications_summary_values_minutes, (heartbeatMs / 60000).toString())
    }

    companion object {
        private const val PREF_PING_INTERVAL = "pref_gcm_ping_interval"
        private val HEARTBEAT_PREFS = arrayOf(GcmPrefs.PREF_NETWORK_MOBILE, GcmPrefs.PREF_NETWORK_ROAMING, GcmPrefs.PREF_NETWORK_WIFI, GcmPrefs.PREF_NETWORK_OTHER)
    }
}
