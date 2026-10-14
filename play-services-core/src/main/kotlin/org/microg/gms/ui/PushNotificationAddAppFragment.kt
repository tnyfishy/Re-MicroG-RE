/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.R
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.gcm.GcmDatabase

/**
 * Lets the user pick installed apps and allow them to register for push notifications
 * before the apps ask for it. Only user apps are listed unless system apps are shown.
 * Tap adds one app; long press starts selecting several apps to add together.
 */
class PushNotificationAddAppFragment : Fragment() {
    private class AppEntry(val info: ApplicationInfo, val label: String, val isSystem: Boolean) {
        val packageName: String get() = info.packageName
    }

    private class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.push_add_app_icon)
        val name: TextView = view.findViewById(R.id.push_add_app_name)
        val pkg: TextView = view.findViewById(R.id.push_add_app_package)
        val check: View = view.findViewById(R.id.push_add_app_check)
    }

    private val adapter = AppAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private var visibleApps: List<AppEntry> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var showSystemApps = false
    private var selectedColor = Color.LTGRAY
    private lateinit var progress: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSystemApps = savedInstanceState?.getBoolean(STATE_SHOW_SYSTEM_APPS) ?: false
        savedInstanceState?.getStringArrayList(STATE_SELECTED)?.let { selected.addAll(it) }
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.push_add_app_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(MaterialColors.getColor(view, android.R.attr.colorBackground))
        selectedColor = ColorUtils.setAlphaComponent(MaterialColors.getColor(view, android.R.attr.colorAccent, Color.GRAY), 56)
        progress = view.findViewById(R.id.push_add_app_progress)
        val list = view.findViewById<RecyclerView>(R.id.push_add_app_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        loadApps()
    }

    private fun loadApps() {
        val appContext = requireContext().applicationContext
        progress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            // Loading labels of every installed app is slow, so it never runs on the main thread
            val apps = withContext(Dispatchers.IO) {
                val pm = appContext.packageManager
                pm.getInstalledApplications(0)
                        .filter { it.packageName != appContext.packageName }
                        .map { AppEntry(it, pm.getApplicationLabel(it).toString(), it.isSystemApp) }
                        .sortedBy { it.label.lowercase() }
            }
            allApps = apps
            progress.visibility = View.GONE
            refreshList()
        }
    }

    private fun refreshList() {
        visibleApps = allApps.filter { showSystemApps || !it.isSystem }
        // Never keep a selection the user can no longer see
        val visibleNames = visibleApps.mapTo(HashSet()) { it.packageName }
        selected.retainAll(visibleNames)
        adapter.notifyDataSetChanged()
        activity?.invalidateOptionsMenu()
    }

    private fun toggleSelection(entry: AppEntry) {
        if (!selected.add(entry.packageName)) selected.remove(entry.packageName)
        adapter.notifyDataSetChanged()
        activity?.invalidateOptionsMenu()
    }

    private fun addApps(packages: Collection<String>) {
        if (packages.isEmpty()) return
        val toAdd = packages.toList()
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val database = GcmDatabase(appContext)
                try {
                    toAdd.forEach { database.noteAppKnown(it, true) }
                } finally {
                    database.close()
                }
            }
            Toast.makeText(appContext, appContext.getString(R.string.push_add_app_added, toAdd.size), Toast.LENGTH_SHORT).show()
            // Back to the Cloud Messaging screen, which reloads its list on resume
            if (isAdded) findNavController().popBackStack()
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
        val selecting = selected.isNotEmpty()
        menu.findItem(R.id.menu_add_selected)?.let {
            it.isVisible = selecting
            it.title = getString(R.string.push_add_app_add_selected, selected.size)
        }
        menu.findItem(R.id.menu_clear_selection)?.isVisible = selecting
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_toggle_system_apps -> {
                showSystemApps = !showSystemApps
                refreshList()
                true
            }
            R.id.menu_add_selected -> {
                addApps(selected)
                true
            }
            R.id.menu_clear_selection -> {
                selected.clear()
                adapter.notifyDataSetChanged()
                activity?.invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_SYSTEM_APPS, showSystemApps)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder =
                AppHolder(LayoutInflater.from(parent.context).inflate(R.layout.push_add_app_item, parent, false))

        override fun getItemCount(): Int = visibleApps.size

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val entry = visibleApps[position]
            holder.name.text = entry.label
            holder.pkg.text = entry.packageName
            holder.icon.setImageDrawable(entry.info.loadIcon(holder.itemView.context.packageManager))
            val isSelected = selected.contains(entry.packageName)
            holder.itemView.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                // While selecting, a tap toggles the app; otherwise it adds just that app
                if (selected.isNotEmpty()) toggleSelection(entry) else addApps(listOf(entry.packageName))
            }
            holder.itemView.setOnLongClickListener {
                toggleSelection(entry)
                true
            }
        }
    }

    companion object {
        private const val STATE_SHOW_SYSTEM_APPS = "show_system_apps"
        private const val STATE_SELECTED = "selected_apps"
    }
}

private val ApplicationInfo.isSystemApp: Boolean
    get() = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
