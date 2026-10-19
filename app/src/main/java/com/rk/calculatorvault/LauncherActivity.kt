package com.rk.calculatorvault

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RK Home — a lightweight, native Android launcher inspired by the soft cards,
 * rounded shapes and clear hierarchy of modern Samsung-style home screens.
 * Uses Android framework widgets only; no remote images or UI libraries.
 */
class LauncherActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("rk_vault_secure", MODE_PRIVATE) }
    private val queryExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cachedApps: List<ResolveInfo>? = null
    private var appQueryInProgress = false
    private var appDrawerOpen = false
    private var clockTimeView: TextView? = null
    private var clockDateView: TextView? = null
    private var drawerCountView: TextView? = null
    private var drawerSearchInput: EditText? = null
    private var drawerAdapter: AppGridAdapter? = null
    private var allVisibleApps: List<ResolveInfo> = emptyList()

    private val backgroundColor = Color.rgb(244, 245, 251)
    private val inkColor = Color.rgb(27, 31, 42)
    private val mutedColor = Color.rgb(110, 116, 132)
    private val accentColor = Color.rgb(74, 91, 183)
    private val hiddenSet: Set<String>
        get() = prefs.getStringSet("hidden_launcher_packages", emptySet())?.toSet() ?: emptySet()

    private val clockTicker = object : Runnable {
        override fun run() {
            val now = Date()
            clockTimeView?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            clockDateView?.text = SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(now)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("id", "ID")) else it.toString() }
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleSystemBars()
        renderHome()
        requestAppList()
    }

    override fun onResume() {
        super.onResume()
        styleSystemBars()
        renderHome()
        mainHandler.removeCallbacks(clockTicker)
        mainHandler.post(clockTicker)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockTicker)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clockTicker)
        queryExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated by Android, retained for broad device compatibility")
    override fun onBackPressed() {
        if (appDrawerOpen) {
            appDrawerOpen = false
            renderHome()
        } else {
            super.onBackPressed()
        }
    }

    private fun styleSystemBars() {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun gradient(colors: IntArray, radius: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors
    ).apply { cornerRadius = dp(radius).toFloat() }

    private fun makeText(
        text: String,
        size: Float,
        color: Int = inkColor,
        bold: Boolean = false,
        gravity: Int = Gravity.CENTER_VERTICAL
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        this.gravity = gravity
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD)
        includeFontPadding = true
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun makePillButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(inkColor)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(Color.WHITE, 20)
        stateListAnimator = null
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun makeHeader(isDrawer: Boolean): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(makeText(if (isDrawer) "Semua aplikasi" else "RK Home", 21f, inkColor, true))
        titleStack.addView(makeText(
            if (isDrawer) "Cari dan buka aplikasi" else "Sederhana. Rapi. Cepat.",
            11.5f, mutedColor
        ))
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(makePillButton(if (isDrawer) "‹  Beranda" else "⚙  Atur") {
            if (isDrawer) {
                appDrawerOpen = false
                renderHome()
            } else {
                openHomeSettings()
            }
        }, LinearLayout.LayoutParams(-2, dp(40)))
        return header
    }

    private fun renderHome() {
        styleSystemBars()
        if (appDrawerOpen) {
            renderAppDrawer()
            return
        }
        drawerAdapter = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
            background = gradient(intArrayOf(Color.rgb(246, 247, 252), Color.rgb(238, 240, 249)), 0)
        }
        root.addView(makeHeader(false), LinearLayout.LayoutParams(-1, dp(54)))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(7), 0, dp(12))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
        }
        content.addView(createClockCard(), LinearLayout.LayoutParams(-1, dp(150)).apply {
            bottomMargin = dp(14)
        })
        content.addView(createHomeSearchBar(), LinearLayout.LayoutParams(-1, dp(52)).apply {
            bottomMargin = dp(23)
        })

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(makeText("Aplikasi favorit", 16f, inkColor, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(makePillButton("Semua  ›") {
            appDrawerOpen = true
            renderHome()
        }, LinearLayout.LayoutParams(-2, dp(34)))
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val allApps = cachedApps
        if (allApps == null) {
            content.addView(makeText("Menyiapkan aplikasi…", 13f, mutedColor).apply {
                setPadding(dp(4), dp(18), dp(4), dp(18))
            })
        } else {
            allVisibleApps = filterHidden(allApps)
            val dockApps = chooseDockApps(allVisibleApps)
            val dockPackages = dockApps.mapNotNull { it.activityInfo?.packageName }.toSet()
            val homeApps = allVisibleApps.filter { it.activityInfo?.packageName !in dockPackages }.take(8)
            if (homeApps.isEmpty()) {
                content.addView(makeText("Ketuk ‘Semua’ untuk melihat aplikasi di perangkat ini.", 12f, mutedColor).apply {
                    setPadding(dp(4), dp(18), dp(4), dp(18))
                })
            } else {
                val grid = GridLayout(this).apply {
                    columnCount = 4
                    alignmentMode = GridLayout.ALIGN_BOUNDS
                    useDefaultMargins = false
                }
                homeApps.forEachIndexed { index, info ->
                    val row = index / 4
                    val column = index % 4
                    val params = GridLayout.LayoutParams(
                        GridLayout.spec(row), GridLayout.spec(column, 1f)
                    ).apply {
                        width = 0
                        height = dp(91)
                        setMargins(dp(1), dp(2), dp(1), dp(2))
                    }
                    grid.addView(createAppTile(info, 42, 10.5f), params)
                }
                content.addView(grid, LinearLayout.LayoutParams(-1, -2))
            }
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val pageIndicator = makeText("●   ○   ○", 9f, accentColor, false, Gravity.CENTER).apply {
            setPadding(0, dp(1), 0, dp(6))
        }
        root.addView(pageIndicator, LinearLayout.LayoutParams(-1, dp(18)))
        root.addView(createDock(allVisibleApps), LinearLayout.LayoutParams(-1, dp(78)))
        setContentView(root)
        mainHandler.removeCallbacks(clockTicker)
        mainHandler.post(clockTicker)
    }

    private fun createClockCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(16), dp(12))
            background = gradient(intArrayOf(Color.rgb(231, 233, 251), Color.rgb(241, 237, 250)), 28)
        }
        val clockStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockTimeView = makeText("--:--", 45f, Color.rgb(31, 34, 50), false).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setLineSpacing(-dp(3).toFloat(), 1f)
        }
        clockDateView = makeText("", 13f, Color.rgb(86, 91, 112), false)
        clockStack.addView(clockTimeView)
        clockStack.addView(clockDateView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        card.addView(clockStack, LinearLayout.LayoutParams(0, -2, 1f))

        val emblem = FrameLayout(this).apply {
            background = rounded(Color.argb(90, 255, 255, 255), 23)
        }
        val monogram = makeText("RK", 21f, accentColor, true, Gravity.CENTER)
        emblem.addView(monogram, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        card.addView(emblem, LinearLayout.LayoutParams(dp(66), dp(66)))
        return card
    }

    private fun createHomeSearchBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(15), 0)
            background = rounded(Color.WHITE, 19)
            isClickable = true
            isFocusable = true
            contentDescription = "Cari aplikasi"
            setOnClickListener {
                appDrawerOpen = true
                renderHome()
                mainHandler.postDelayed({
                    drawerSearchInput?.let { input ->
                        input.requestFocus()
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.showSoftInput(input, SHOW_IMPLICIT)
                    }
                }, 100L)
            }
        }
        bar.addView(makeText("⌕", 28f, mutedColor, false, Gravity.CENTER), LinearLayout.LayoutParams(dp(30), -1))
        bar.addView(makeText("Cari aplikasi", 14f, mutedColor), LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(makeText("☷", 21f, mutedColor, false, Gravity.CENTER), LinearLayout.LayoutParams(dp(28), -1))
        return bar
    }

    private fun renderAppDrawer() {
        styleSystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = gradient(intArrayOf(Color.rgb(246, 247, 252), Color.rgb(238, 240, 249)), 0)
        }
        root.addView(makeHeader(true), LinearLayout.LayoutParams(-1, dp(54)))

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(Color.WHITE, 19)
        }
        searchBar.addView(makeText("⌕", 26f, mutedColor, false, Gravity.CENTER), LinearLayout.LayoutParams(dp(28), -1))
        val search = EditText(this).apply {
            hint = "Cari aplikasi"
            textSize = 14f
            setTextColor(inkColor)
            setHintTextColor(mutedColor)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = null
            setPadding(dp(4), 0, dp(4), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            isFocusableInTouchMode = true
        }
        drawerSearchInput = search
        searchBar.addView(search, LinearLayout.LayoutParams(0, dp(50), 1f))
        searchBar.addView(makeText("×", 22f, mutedColor, false, Gravity.CENTER).apply {
            isClickable = true
            setOnClickListener { search.setText("") }
        }, LinearLayout.LayoutParams(dp(25), -1))
        root.addView(searchBar, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        drawerCountView = makeText("Aplikasi", 13f, mutedColor, true)
        summary.addView(drawerCountView, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(makeText("Tekan lama untuk info aplikasi", 10.5f, mutedColor))
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        val loaded = cachedApps
        val visible = if (loaded == null) emptyList() else filterHidden(loaded)
        allVisibleApps = visible
        drawerAdapter = AppGridAdapter(visible)
        val grid = GridView(this).apply {
            numColumns = calculateColumnCount()
            horizontalSpacing = dp(2)
            verticalSpacing = dp(5)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(0, dp(2), 0, dp(6))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = drawerAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        drawerCountView?.text = if (loaded == null) "Memuat aplikasi…" else "${visible.size} aplikasi"

        val bottom = makePillButton("Kembali ke beranda") {
            appDrawerOpen = false
            renderHome()
        }
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(44)).apply {
            topMargin = dp(8)
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val term = s?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
                val filtered = visible.filter { appLabel(it).lowercase(Locale.getDefault()).contains(term) }
                drawerAdapter?.replaceApps(filtered)
                drawerCountView?.text = "${filtered.size} aplikasi"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        setContentView(root)

        if (loaded == null) {
            requestAppList()
        }
    }

    private fun calculateColumnCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 600 -> 6
            widthDp >= 420 -> 5
            else -> 4
        }
    }

    private fun createDock(apps: List<ResolveInfo>): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(Color.argb(238, 255, 255, 255), 25)
        }
        val selected = chooseDockApps(apps).take(4)
        if (selected.isEmpty()) {
            val openApps = makeText("Semua aplikasi  ›", 13f, inkColor, true, Gravity.CENTER)
            openApps.setOnClickListener { appDrawerOpen = true; renderHome() }
            dock.addView(openApps, LinearLayout.LayoutParams(-1, -1))
            return dock
        }
        selected.forEach { info ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(3), 0, dp(3), 0)
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                try { setImageDrawable(info.loadIcon(packageManager)) } catch (_: Exception) { setImageDrawable(null) }
                contentDescription = appLabel(info)
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(35), dp(35)))
            item.addView(makeText(appLabel(info), 9.5f, inkColor, false, Gravity.CENTER).apply {
                maxLines = 1
                setPadding(0, dp(1), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(18)))
            item.setOnClickListener { launchResolvedApp(info) }
            item.setOnLongClickListener { showAppOptions(info); true }
            dock.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return dock
    }

    private fun chooseDockApps(apps: List<ResolveInfo>): List<ResolveInfo> {
        if (apps.isEmpty()) return emptyList()
        val selected = mutableListOf<ResolveInfo>()
        val preferredGroups = listOf(
            listOf("phone", "telepon", "dialer", "panggilan"),
            listOf("message", "pesan", "sms", "perpesanan"),
            listOf("camera", "kamera"),
            listOf("chrome", "browser", "internet", "peramban")
        )
        for (group in preferredGroups) {
            val match = apps.firstOrNull { app ->
                val label = appLabel(app).lowercase(Locale.getDefault())
                group.any { label.contains(it) } && selected.none { samePackage(it, app) }
            }
            if (match != null) selected.add(match)
        }
        for (app in apps) {
            if (selected.size >= 4) break
            if (selected.none { samePackage(it, app) }) selected.add(app)
        }
        return selected.take(4)
    }

    private fun samePackage(a: ResolveInfo, b: ResolveInfo): Boolean =
        a.activityInfo?.packageName == b.activityInfo?.packageName

    private fun createAppTile(info: ResolveInfo, iconSizeDp: Int, labelSize: Float): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), dp(3))
            isClickable = true
            isFocusable = true
            contentDescription = appLabel(info)
        }
        val iconFrame = FrameLayout(this).apply {
            background = rounded(Color.argb(80, 255, 255, 255), 17)
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            try { setImageDrawable(info.loadIcon(packageManager)) } catch (_: Exception) { setImageDrawable(null) }
        }
        iconFrame.addView(icon, FrameLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp), Gravity.CENTER))
        tile.addView(iconFrame, LinearLayout.LayoutParams(dp(iconSizeDp + 9), dp(iconSizeDp + 9)).apply {
            bottomMargin = dp(4)
        })
        tile.addView(makeText(appLabel(info), labelSize, inkColor, false, Gravity.CENTER).apply {
            maxLines = 2
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setLineSpacing(0f, 0.95f)
        }, LinearLayout.LayoutParams(-1, dp(29)))
        tile.setOnClickListener { launchResolvedApp(info) }
        tile.setOnLongClickListener { showAppOptions(info); true }
        return tile
    }

    private fun showAppOptions(info: ResolveInfo) {
        val label = appLabel(info)
        val options = arrayOf("Buka aplikasi", "Info aplikasi")
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchResolvedApp(info)
                    1 -> openAppInfo(info)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun openAppInfo(info: ResolveInfo) {
        val packageName = info.activityInfo?.packageName ?: return
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            Toast.makeText(this, "Info aplikasi tidak tersedia.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appLabel(info: ResolveInfo): String = try {
        info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            .ifBlank { info.activityInfo?.packageName.orEmpty() }
    } catch (_: Exception) {
        info.activityInfo?.packageName.orEmpty()
    }

    private fun filterHidden(apps: List<ResolveInfo>): List<ResolveInfo> {
        val hidden = hiddenSet
        return apps.filter { info ->
            val pkg = info.activityInfo?.packageName
            pkg != null && pkg !in hidden && pkg != packageName
        }
    }

    private fun requestAppList() {
        if (appQueryInProgress || cachedApps != null) return
        appQueryInProgress = true
        queryExecutor.execute {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = try {
                packageManager.queryIntentActivities(query, 0)
                    .distinctBy { it.activityInfo?.packageName }
                    .filter { it.activityInfo?.packageName != packageName }
                    .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
            } catch (_: Exception) {
                emptyList()
            }
            mainHandler.post {
                appQueryInProgress = false
                cachedApps = found
                if (!isFinishing && !isDestroyed) renderHome()
            }
        }
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Buka Setelan > Aplikasi default > Aplikasi Beranda.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchResolvedApp(info: ResolveInfo) {
        val target = info.activityInfo ?: return
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(target.packageName, target.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Aplikasi tidak bisa dibuka.", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class AppGridAdapter(initialApps: List<ResolveInfo>) : BaseAdapter() {
        private var apps: List<ResolveInfo> = initialApps
        fun replaceApps(value: List<ResolveInfo>) {
            apps = value
            notifyDataSetChanged()
        }
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): ResolveInfo = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val info = getItem(position)
            val tile: LinearLayout
            val holder: TileHolder
            if (convertView == null) {
                tile = LinearLayout(this@LauncherActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(5), dp(2), dp(4))
                    isClickable = true
                    isFocusable = true
                }
                val iconFrame = FrameLayout(this@LauncherActivity).apply {
                    background = rounded(Color.argb(70, 255, 255, 255), 16)
                }
                val icon = ImageView(this@LauncherActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                iconFrame.addView(icon, FrameLayout.LayoutParams(dp(43), dp(43), Gravity.CENTER))
                tile.addView(iconFrame, LinearLayout.LayoutParams(dp(51), dp(51)).apply { bottomMargin = dp(4) })
                val label = makeText("", 10.5f, inkColor, false, Gravity.CENTER).apply {
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2
                    setLineSpacing(0f, 0.95f)
                }
                tile.addView(label, LinearLayout.LayoutParams(-1, dp(31)))
                holder = TileHolder(icon, label)
                tile.tag = holder
            } else {
                tile = convertView as LinearLayout
                holder = tile.tag as TileHolder
            }
            holder.label.text = appLabel(info)
            holder.icon.contentDescription = holder.label.text
            try { holder.icon.setImageDrawable(info.loadIcon(packageManager)) } catch (_: Exception) { holder.icon.setImageDrawable(null) }
            tile.contentDescription = holder.label.text
            tile.setOnClickListener { launchResolvedApp(info) }
            tile.setOnLongClickListener { showAppOptions(info); true }
            return tile
        }
    }

    private data class TileHolder(val icon: ImageView, val label: TextView)
}
