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
import android.view.ViewTreeObserver
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
import java.text.Normalizer
import androidx.core.widget.doAfterTextChanged
import androidx.activity.OnBackPressedCallback
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.MotionEvent
import android.content.Context
import android.animation.ValueAnimator

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
        // Lowercase, without accents, so "chinh sua" finds "Chỉnh sửa"
        val searchKey: String = fold(label) + " " + info.packageName.lowercase()
    }

    private class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.push_add_app_icon)
        val name: TextView = view.findViewById(R.id.push_add_app_name)
        val pkg: TextView = view.findViewById(R.id.push_add_app_package)
        val check: View = view.findViewById(R.id.push_add_app_check)
    }

    private val adapter = AppAdapter()
    private var allApps: List<AppEntry> = emptyList()
    private var apps: List<AppEntry> = emptyList()
    private var query = ""
    private var searchExpanded = false
    private var searchBar: View? = null
    private var searchInput: EditText? = null
    private var searchTap: View? = null
    private var searchClear: View? = null
    private var searchAnimator: ValueAnimator? = null
    private var backCallback: OnBackPressedCallback? = null
    private val selected = LinkedHashSet<String>()
    private var selectedColor = Color.LTGRAY
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var loadingIndicator: View? = null
    private var pullCircle: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private val requestAppListPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadApps() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList(STATE_SELECTED)?.let { selected.addAll(it) }
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""
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
        pullCircle = (0 until swipe.childCount).map { swipe.getChildAt(it) }.firstOrNull { it !== list }
        val listener = ViewTreeObserver.OnPreDrawListener {
            syncLoadingContainer()
            true
        }
        swipe.viewTreeObserver.addOnPreDrawListener(listener)
        preDrawListener = listener

        setupSearch(view)

        if (needsAppListPermission()) requestAppListPermission.launch(APP_LIST_PERMISSION) else loadApps()
    }

    override fun onDestroyView() {
        preDrawListener?.let { listener ->
            val observer = swipeRefresh?.viewTreeObserver
            if (observer != null && observer.isAlive) observer.removeOnPreDrawListener(listener)
        }
        preDrawListener = null
        searchAnimator?.cancel()
        backCallback?.remove()
        backCallback = null
        searchBar = null
        searchInput = null
        searchTap = null
        searchClear = null
        pullCircle = null
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
    }

    /**
     * The stock pull-to-refresh circle is invisible; the Material 3 loading indicator in its round
     * container is placed where that circle is on every frame, so the container follows the
     * finger while pulling, stays while loading, and leaves when the circle goes away.
     */
    private fun syncLoadingContainer() {
        val container = loadingIndicator ?: return
        val circle = pullCircle ?: return
        val reveal = if (circle.visibility == View.VISIBLE && circle.height > 0) {
            ((circle.top + circle.height) / (circle.height * 1.4f)).coerceIn(0f, 1f) * circle.scaleX.coerceIn(0f, 1f)
        } else {
            0f
        }
        if (reveal <= 0.01f) {
            if (container.visibility != View.GONE) container.visibility = View.GONE
            return
        }
        if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE
        container.alpha = reveal
        container.scaleX = 0.6f + 0.4f * reveal
        container.scaleY = 0.6f + 0.4f * reveal
        container.translationY = (swipeRefresh?.top ?: 0) + circle.top + (circle.height - container.layoutParams.height) / 2f
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
            allApps = loaded
            // Never keep a selection of an app that is gone
            selected.retainAll(loaded.mapTo(HashSet()) { it.packageName })
            applyFilter()
            activity?.invalidateOptionsMenu()
            // A load that finishes at once would only flash the indicator, so keep it a moment
            val remaining = MIN_LOADING_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            setLoading(false)
        }
    }

    private fun applyFilter() {
        val needle = fold(query.trim())
        apps = if (needle.isEmpty()) allApps else allApps.filter { it.searchKey.contains(needle) }
        adapter.notifyDataSetChanged()
    }

    private fun setupSearch(view: View) {
        val bar = view.findViewById<View>(R.id.push_add_app_search_bar)
        val input = view.findViewById<EditText>(R.id.push_add_app_search_input)
        val tap = view.findViewById<View>(R.id.push_add_app_search_tap)
        val clear = view.findViewById<View>(R.id.push_add_app_search_clear)
        searchBar = bar
        searchInput = input
        searchTap = tap
        searchClear = clear

        input.doAfterTextChanged {
            query = it?.toString() ?: ""
            applyFilter()
        }
        input.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }

        // Pressing the bar squishes it in...
        tap.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> bar.animate().scaleX(0.94f).scaleY(0.88f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val inside = event.x >= 0 && event.x <= v.width && event.y >= 0 && event.y <= v.height
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL || !inside) {
                        bar.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    }
                }
            }
            false
        }
        // ...and releasing it makes it spring open
        tap.setOnClickListener { setSearchExpanded(true, animate = true) }
        clear.setOnClickListener {
            if (query.isNotEmpty()) input.setText("") else setSearchExpanded(false, animate = true)
        }

        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                input.setText("")
                setSearchExpanded(false, animate = true)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        backCallback = callback

        if (query.isNotEmpty()) {
            input.setText(query)
            setSearchExpanded(true, animate = false)
        }
    }

    private fun setSearchExpanded(expanded: Boolean, animate: Boolean) {
        val bar = searchBar ?: return
        val input = searchInput ?: return
        searchExpanded = expanded
        backCallback?.isEnabled = expanded
        searchTap?.visibility = if (expanded) View.GONE else View.VISIBLE
        searchClear?.visibility = if (expanded) View.VISIBLE else View.GONE
        input.isFocusable = expanded
        input.isFocusableInTouchMode = expanded

        val params = bar.layoutParams as ViewGroup.MarginLayoutParams
        val target = ((if (expanded) 12 else 24) * bar.resources.displayMetrics.density).toInt()
        searchAnimator?.cancel()
        if (animate) {
            searchAnimator = ValueAnimator.ofInt(params.marginStart, target).apply {
                duration = if (expanded) 450L else 250L
                // Overshoot: the bar opens a little too wide, then settles
                interpolator = if (expanded) OvershootInterpolator(2.4f) else DecelerateInterpolator()
                addUpdateListener {
                    val margin = it.animatedValue as Int
                    params.marginStart = margin
                    params.marginEnd = margin
                    bar.layoutParams = params
                }
                start()
            }
            bar.animate().scaleX(1f).scaleY(1f).setDuration(if (expanded) 420L else 200L)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
        } else {
            params.marginStart = target
            params.marginEnd = target
            bar.layoutParams = params
            bar.scaleX = 1f
            bar.scaleY = 1f
        }

        if (expanded) {
            input.requestFocus()
            input.post {
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            input.clearFocus()
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val input = searchInput ?: return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
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
            // In root mode, newly added apps get the same background exemption as the others
            RootKeepAlive.applyIfEnabled(appContext)
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
        outState.putString(STATE_QUERY, query)
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
        private const val STATE_QUERY = "search_query"
        private val DIACRITICS = Regex("\\p{Mn}+")

        private fun fold(text: String): String =
                Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "").replace('đ', 'd')
        private const val MIN_LOADING_MS = 600L
        private const val APP_LIST_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
        private val PAYLOAD_SELECTION = Any()
    }
}

private val ApplicationInfo.isSystemApp: Boolean
    get() = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 || (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
