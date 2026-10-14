/*
 * SPDX-FileCopyrightText: 2020, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.R
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.gcm.GcmDatabase

/**
 * Lets the user pick installed apps and allow them to register for push notifications
 * before the apps ask for it. Only apps the user installed are listed.
 * Tap adds one app; long press starts selecting several apps to add together;
 * pull down to reload the list.
 */
class PushNotificationAddAppFragment : Fragment() {
    // Label and icon are loaded together on a background thread, so scrolling never has to
    // load them on the main thread (that made the list stutter on high refresh rate screens).
    private class AppEntry(val info: ApplicationInfo, val label: String, val icon: Drawable) {
        val packageName: String get() = info.packageName
    }

    private class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.push_add_app_icon)
        val name: TextView = view.findViewById(R.id.push_add_app_name)
        val pkg: TextView = view.findViewById(R.id.push_add_app_package)
        val check: View = view.findViewById(R.id.push_add_app_check)
    }

    private val adapter = AppAdapter()
    private var apps: List<AppEntry> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var selectedColor = Color.LTGRAY
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var loadingIndicator: View? = null

    private val requestAppListPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadApps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(STATE_SELECTED)?.let { selected.addAll(it) }
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.push_add_app_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(MaterialColors.getColor(view, android.R.attr.colorBackground))
        selectedColor = ColorUtils.setAlphaComponent(MaterialColors.getColor(view, android.R.attr.colorAccent, Color.GRAY), 56)
        loadingIndicator = view.findViewById(R.id.push_add_app_loading)
        val list = view.findViewById<RecyclerView>(R.id.push_add_app_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.setHasFixedSize(true)
        list.adapter = adapter
        // Selecting an app only changes its highlight, so skip the cross-fade animation
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // The loading indicator below replaces the stock pull-to-refresh spinner, which is hidden
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.push_add_app_swipe)
        swipe.setColorSchemeColors(Color.TRANSPARENT)
        swipe.setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
        for (i in 0 until swipe.childCount) {
            val child = swipe.getChildAt(i)
            if (child !== list) child.elevation = 0f
        }
        swipe.setOnRefreshListener { loadApps() }
        swipeRefresh = swipe

        if (needsAppListPermission()) requestAppListPermission.launch(APP_LIST_PERMISSION) else loadApps()
    }

    override fun onDestroyView() {
        swipeRefresh = null
        loadingIndicator = null
        super.onDestroyView()
    }

    /** Xiaomi's MIUI/HyperOS asks before an app may see the installed apps; other systems do not have it. */
    private fun needsAppListPermission(): Boolean {
        val context = requireContext()
        val declaredBySystem = try {
            context.packageManager.getPermissionInfo(APP_LIST_PERMISSION, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return declaredBySystem &&
                ContextCompat.checkSelfPermission(context, APP_LIST_PERMISSION) != PackageManager.PERMISSION_GRANTED
    }

    private fun setLoading(loading: Boolean) {
        swipeRefresh?.isRefreshing = loading
        val indicator = loadingIndicator ?: return
        indicator.animate().cancel()
        if (loading) {
            indicator.alpha = 0f
            indicator.scaleX = 0.6f
            indicator.scaleY = 0.6f
            indicator.visibility = View.VISIBLE
            indicator.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
        } else {
            indicator.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(200)
                    .withEndAction { indicator.visibility = View.GONE }.start()
        }
    }

    private fun loadApps() {
        val appContext = context?.applicationContext ?: return
        if (view == null) return
        setLoading(true)
        val startedAt = SystemClock.elapsedRealtime()
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pm = appContext.packageManager
                pm.getInstalledApplications(0)
                        .filter { it.packageName != appContext.packageName && !it.isSystemApp }
                        .map { AppEntry(it, pm.getApplicationLabel(it).toString(), it.loadIcon(pm)) }
                        .sortedBy { it.label.lowercase() }
            }
            apps = loaded
            // Never keep a selection of an app that is gone
            selected.retainAll(loaded.mapTo(HashSet()) { it.packageName })
            adapter.notifyDataSetChanged()
            activity?.invalidateOptionsMenu()
            // A load that finishes at once would only flash the indicator, so keep it a moment
            val remaining = MIN_LOADING_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            setLoading(false)
        }
    }

    private fun toggleSelection(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val packageName = apps[position].packageName
        if (!selected.add(packageName)) selected.remove(packageName)
        adapter.notifyItemChanged(position, PAYLOAD_SELECTION)
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
        val selecting = selected.isNotEmpty()
        menu.findItem(R.id.menu_add_selected)?.let {
            it.isVisible = selecting
            it.title = getString(R.string.push_add_app_add_selected, selected.size)
        }
        menu.findItem(R.id.menu_clear_selection)?.isVisible = selecting
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_add_selected -> {
                addApps(selected)
                true
            }
            R.id.menu_clear_selection -> {
                selected.clear()
                adapter.notifyItemRangeChanged(0, apps.size, PAYLOAD_SELECTION)
                activity?.invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder {
            val holder = AppHolder(LayoutInflater.from(parent.context).inflate(R.layout.push_add_app_item, parent, false))
            holder.itemView.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                // While selecting, a tap toggles the app; otherwise it adds just that app
                if (selected.isNotEmpty()) toggleSelection(position) else addApps(listOf(apps[position].packageName))
            }
            holder.itemView.setOnLongClickListener {
                toggleSelection(holder.bindingAdapterPosition)
                true
            }
            return holder
        }

        override fun getItemCount(): Int = apps.size

        override fun getItemId(position: Int): Long = apps[position].packageName.hashCode().toLong()

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val entry = apps[position]
            holder.name.text = entry.label
            holder.pkg.text = entry.packageName
            holder.icon.setImageDrawable(entry.icon)
            bindSelection(holder, entry)
        }

        override fun onBindViewHolder(holder: AppHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) bindSelection(holder, apps[position]) else super.onBindViewHolder(holder, position, payloads)
        }

        private fun bindSelection(holder: AppHolder, entry: AppEntry) {
            val isSelected = selected.contains(entry.packageName)
            holder.itemView.setBackgroundColor(if (isSelected) selectedColor else Color.TRANSPARENT)
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val STATE_SELECTED = "selected_apps"
        private const val MIN_LOADING_MS = 600L
        private const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
        private val PAYLOAD_SELECTION = Any()
    }
}

private val ApplicationInfo.isSystemApp: Boolean
    get() = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
