package com.faststrap.dev

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    /** One UI toggle plus the FFlags it controls. Add a new tweak by adding one entry in onCreate. */
    private class Tweak(
        val prefKey: String,
        val toggle: CompoundButton,
        val flags: Map<String, Any>
    )

    /** A set of flags imported from the clipboard. Applied before tweaks and custom flags. */
    private class Pack(
        val id: Long,
        val name: String,
        var on: Boolean,
        val flags: JSONObject
    ) {
        fun keys(): List<String> {
            val out = mutableListOf<String>()
            val it = flags.keys()
            while (it.hasNext()) out.add(it.next())
            return out
        }
    }

    /** One row of the Applied tab: a flag currently in the flags file and where it came from. */
    private class FlagRow(val key: String, val value: Any, val source: String)

    private class FlagVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.flagName)
        val source: TextView = v.findViewById(R.id.flagSource)
        val value: TextView = v.findViewById(R.id.flagValue)
    }

    private inner class FlagAdapter : RecyclerView.Adapter<FlagVH>() {
        var items: List<FlagRow> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlagVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_flag, parent, false)
            pressable(v)
            return FlagVH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: FlagVH, position: Int) {
            val r = items[position]
            holder.name.text = r.key
            holder.source.text = r.source
            holder.value.text = formatValue(r.value)
            styleValueChip(holder.value, r.value)
            holder.itemView.setOnClickListener { showFlagEditor(r) }
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 0f
            holder.itemView.animate().alpha(1f).setStartDelay((position % 10) * 25L).setDuration(200).start()
        }
    }

    private val flagsPath = "/data/local/tmp/ClientAppSettings.json"
    private val backupPath = "/data/local/tmp/faststrap_refresh_backup"
    private val fpsProp = "debug.graphics.game_default_frame_rate.disabled"
    private val robloxPkg = "com.roblox.client"
    private val shellTimeoutSeconds = 20L
    private val reqImport = 201
    private val reqExport = 202
    private val maxClipboardChars = 2_000_000
    private val flagNameRegex = Regex("^[A-Za-z0-9_]+$")
    private val pairRegex = Regex(
        "\"([A-Za-z0-9_]+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?\\d+(?:\\.\\d+)?)"
    )

    // Dark "AMOLED" look: black background, slate surfaces, one accent color.
    private val accents = listOf("#D0BCFF", "#A3C7FF", "#A8DAB5", "#FFB59A", "#FFB1C8", "#C4C7C5")
    private val accentNames = arrayOf("Violet", "Blue", "Green", "Orange", "Pink", "Gray")
    private val surface = Color.parseColor("#1B1F23")
    private val surfaceHigh = Color.parseColor("#2A2F35")
    private val textMuted = Color.parseColor("#C4C7C5")
    private val textPrimary = Color.parseColor("#E3E3E3")
    private val onAccent = Color.parseColor("#101418")
    private var themeIndex = 0
    private var tab = 0
    private var lastCount = -1
    private val accent: Int get() = Color.parseColor(accents[themeIndex])
    private val container: Int get() = ColorUtils.blendARGB(surface, accent, 0.35f)

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var topBar: View
    private lateinit var contentFrame: View
    private lateinit var bottomBar: LinearLayout
    private lateinit var bottomScrim: View
    private lateinit var navRow: LinearLayout
    private lateinit var navIndicator: View
    private lateinit var splash: View
    private lateinit var splashGroup: View
    private lateinit var splashCookie: View
    private lateinit var splashLogo: View
    private lateinit var themeButton: MaterialCardView
    private lateinit var statusPill: MaterialCardView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var busySpinner: ImageView
    private lateinit var connectCard: MaterialCardView
    private lateinit var hintText: TextView
    private lateinit var countText: TextView
    private lateinit var outputText: TextView
    private lateinit var packEmpty: TextView
    private lateinit var packList: LinearLayout
    private lateinit var appliedCount: TextView
    private lateinit var flagSearch: EditText
    private lateinit var flagRecycler: RecyclerView
    private lateinit var requestButton: MaterialButton
    private lateinit var applyButton: MaterialButton
    private lateinit var viewButton: MaterialButton
    private lateinit var removeButton: MaterialButton
    private lateinit var addCustomButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var importPackButton: MaterialButton
    private lateinit var launchButton: MaterialCardView
    private lateinit var launchIcon: ImageView
    private lateinit var customInput: EditText
    private lateinit var segRow: LinearLayout
    private lateinit var cbFps: CompoundButton
    private lateinit var cbFpsCounter: CompoundButton
    private lateinit var cbSky: CompoundButton
    private lateinit var cbGrass: CompoundButton
    private lateinit var cbShadows: CompoundButton
    private lateinit var cbTextures: CompoundButton
    private lateinit var switches: List<CompoundButton>
    private lateinit var tweaks: List<Tweak>
    private lateinit var tabViews: List<View>
    private lateinit var navItems: List<LinearLayout>
    private val segments = mutableListOf<MaterialCardView>()
    private val flagAdapter = FlagAdapter()
    private var appliedAll: List<FlagRow> = emptyList()

    // Sliding nav indicator state.
    private var indLeft = 0f
    private var indRight = 0f
    private var indReady = false
    private var animL: ValueAnimator? = null
    private var animR: ValueAnimator? = null

    // Commands run one at a time off the main thread; the watchdog kills hung processes.
    private val executor = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var busy = false
    private var spinnerAnim: ObjectAnimator? = null

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> updateStatus() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { updateStatus() }
    private val binderDead = Shizuku.OnBinderDeadListener { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false

        prefs = getSharedPreferences("faststrap", MODE_PRIVATE)
        themeIndex = prefs.getInt("theme", 0).coerceIn(0, accents.size - 1)

        root = findViewById(R.id.root)
        mainColumn = findViewById(R.id.mainColumn)
        topBar = findViewById(R.id.topBar)
        contentFrame = findViewById(R.id.contentFrame)
        bottomBar = findViewById(R.id.bottomBar)
        bottomScrim = findViewById(R.id.bottomScrim)
        navRow = findViewById(R.id.navRow)
        navIndicator = findViewById(R.id.navIndicator)
        splash = findViewById(R.id.splash)
        splashGroup = findViewById(R.id.splashGroup)
        splashCookie = findViewById(R.id.splashCookie)
        splashLogo = findViewById(R.id.splashLogo)
        themeButton = findViewById(R.id.themeButton)
        statusPill = findViewById(R.id.statusPill)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        busySpinner = findViewById(R.id.busySpinner)
        connectCard = findViewById(R.id.connectCard)
        hintText = findViewById(R.id.hintText)
        countText = findViewById(R.id.countText)
        outputText = findViewById(R.id.outputText)
        packEmpty = findViewById(R.id.packEmpty)
        packList = findViewById(R.id.packList)
        appliedCount = findViewById(R.id.appliedCount)
        flagSearch = findViewById(R.id.flagSearch)
        flagRecycler = findViewById(R.id.flagRecycler)
        requestButton = findViewById(R.id.requestButton)
        applyButton = findViewById(R.id.applyButton)
        viewButton = findViewById(R.id.viewButton)
        removeButton = findViewById(R.id.removeButton)
        addCustomButton = findViewById(R.id.addCustomButton)
        importButton = findViewById(R.id.importButton)
        exportButton = findViewById(R.id.exportButton)
        importPackButton = findViewById(R.id.importPackButton)
        launchButton = findViewById(R.id.launchButton)
        launchIcon = findViewById(R.id.launchIcon)
        customInput = findViewById(R.id.customInput)
        segRow = findViewById(R.id.segRow)
        cbFps = findViewById(R.id.cbFps)
        cbFpsCounter = findViewById(R.id.cbFpsCounter)
        cbSky = findViewById(R.id.cbSky)
        cbGrass = findViewById(R.id.cbGrass)
        cbShadows = findViewById(R.id.cbShadows)
        cbTextures = findViewById(R.id.cbTextures)
        tabViews = listOf(
            findViewById<View>(R.id.tabTweaks),
            findViewById<View>(R.id.tabFlags),
            findViewById<View>(R.id.tabApplied),
            findViewById<View>(R.id.tabLog)
        )
        navItems = listOf(
            findViewById<LinearLayout>(R.id.navTweaks),
            findViewById<LinearLayout>(R.id.navFlags),
            findViewById<LinearLayout>(R.id.navApplied),
            findViewById<LinearLayout>(R.id.navLog)
        )

        bottomScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.BLACK)
        )

        // Keep content clear of the status and navigation bars (edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            mainColumn.setPadding(0, sys.top, 0, 0)
            val lp = bottomBar.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = sys.bottom + dp(16)
            bottomBar.layoutParams = lp
            val sp = bottomScrim.layoutParams
            sp.height = dp(150) + sys.bottom
            bottomScrim.layoutParams = sp
            insets
        }

        tweaks = listOf(
            Tweak(
                "fps", cbFps, linkedMapOf<String, Any>(
                    "DFIntTaskSchedulerTargetFps" to 9999,
                    "FFlagEnableAndroidVsync" to false,
                    "FFlagTaskSchedulerLimitTargetFpsTo2402" to false
                )
            ),
            Tweak("fpscounter", cbFpsCounter, linkedMapOf<String, Any>("FFlagDebugDisplayFPS" to true)),
            Tweak("sky", cbSky, linkedMapOf<String, Any>("FFlagDebugSkyGray" to true)),
            Tweak(
                "grass", cbGrass, linkedMapOf<String, Any>(
                    "FIntFRMMinGrassDistance" to 0,
                    "FIntFRMMaxGrassDistance" to 0
                )
            ),
            Tweak("shadows", cbShadows, linkedMapOf<String, Any>("DFFlagDebugPauseVoxelizer" to true)),
            Tweak(
                "textures", cbTextures, linkedMapOf<String, Any>(
                    "DFFlagTextureQualityOverrideEnabled" to true,
                    "DFIntTextureQualityOverride" to 0
                )
            )
        )
        switches = tweaks.map { it.toggle }

        buildSegments()

        tweaks.forEach { it.toggle.isChecked = prefs.getBoolean(it.prefKey, false) }
        switches.forEach { it.setOnCheckedChangeListener { _, _ -> updateCount() } }

        flagRecycler.layoutManager = LinearLayoutManager(this)
        flagRecycler.adapter = flagAdapter
        flagSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApplied()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        requestButton.setOnClickListener { Shizuku.requestPermission(100) }
        applyButton.setOnClickListener {
            confirmRestart("Apply the selected tweaks and enabled packs. Roblox needs a restart for them to take effect.") {
                applyFlags(it)
            }
        }
        viewButton.setOnClickListener { viewFlags() }
        removeButton.setOnClickListener {
            confirmRestart("Remove all Stargazer flags, including packs, edits and custom flags added here. Flags you added by hand outside the app are kept.") {
                removeFlags(it)
            }
        }
        addCustomButton.setOnClickListener { onAddCustomClicked() }
        importButton.setOnClickListener { startImport() }
        exportButton.setOnClickListener { startExport() }
        importPackButton.setOnClickListener { importPackFromClipboard() }
        statusPill.setOnClickListener { updateStatus() }
        themeButton.setOnClickListener { showThemeDialog() }
        launchButton.setOnClickListener {
            launchIcon.animate().rotationBy(360f).setDuration(380)
                .setInterpolator(DecelerateInterpolator()).start()
            launchButton.postDelayed({ launchRoblox() }, 220)
        }
        navItems.forEachIndexed { i, item -> item.setOnClickListener { showTab(i) } }

        // Every tappable thing squishes slightly when pressed.
        listOf<View>(
            themeButton, statusPill, launchButton, applyButton, requestButton, viewButton,
            removeButton, addCustomButton, importButton, exportButton, importPackButton
        ).forEach { pressable(it) }
        navItems.forEach { pressable(it) }

        applyTheme()
        showTab(0, false)
        updateStatus()

        if (savedInstanceState == null) {
            playIntro()
        } else {
            splash.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dialog(): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Stargazer_Dialog)

    // ---- Animations ----

    /** Squishes a view while it is pressed and springs back on release. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                        .setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            false
        }
    }

    /**
     * Opening sequence, modeled on the system splash: the plain badge shape is already on screen
     * (slate background), then it turns while the logo pops in, holds, and shrinks away as the
     * top bar, content and navigation bar slide into place.
     */
    private fun playIntro() {
        topBar.alpha = 0f
        topBar.translationY = -dp(24).toFloat()
        contentFrame.alpha = 0f
        contentFrame.translationY = dp(32).toFloat()
        bottomScrim.alpha = 0f
        bottomBar.alpha = 0f
        bottomBar.translationY = dp(120).toFloat()
        launchButton.scaleX = 0f
        launchButton.scaleY = 0f
        splashLogo.alpha = 0f
        splashLogo.scaleX = 0.5f
        splashLogo.scaleY = 0.5f
        splashLogo.rotation = -40f

        splashCookie.animate().rotation(120f).setDuration(650)
            .setInterpolator(OvershootInterpolator(0.8f)).start()
        splashLogo.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(120).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        // A small squash and stretch makes the badge feel alive while it holds.
        ObjectAnimator.ofPropertyValuesHolder(
            splashCookie,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f, 1f)
        ).apply {
            duration = 600
            startDelay = 350
            start()
        }

        splashGroup.postDelayed({
            if (isDestroyed) return@postDelayed
            splashGroup.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(260)
                .setInterpolator(AccelerateInterpolator()).start()
            splash.animate().alpha(0f).setStartDelay(60).setDuration(320).withEndAction {
                splash.visibility = View.GONE
            }.start()
            topBar.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(380)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
            contentFrame.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(420)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
            bottomScrim.animate().alpha(1f).setStartDelay(200).setDuration(400).start()
            bottomBar.animate().alpha(1f).translationY(0f).setStartDelay(220).setDuration(520)
                .setInterpolator(OvershootInterpolator(0.9f)).start()
            launchButton.animate().scaleX(1f).scaleY(1f).setStartDelay(420).setDuration(380)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }, 1150)
    }

    /** Children of a tab rise and fade in one after another. */
    private fun staggerIn(tabView: View) {
        val group: ViewGroup? =
            if (tabView is ScrollView) tabView.getChildAt(0) as? ViewGroup else tabView as? ViewGroup
        if (group == null) return
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i)
            c.animate().cancel()
            c.alpha = 0f
            c.translationY = dp(18).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(50L + i * 45L).setDuration(300)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
    }

    private fun setBusyUi(on: Boolean) {
        if (on) {
            busySpinner.visibility = View.VISIBLE
            val a = ObjectAnimator.ofPropertyValuesHolder(
                busySpinner,
                PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 360f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.75f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.75f, 1f)
            )
            a.duration = 1200
            a.repeatCount = ValueAnimator.INFINITE
            a.interpolator = LinearInterpolator()
            spinnerAnim = a
            a.start()
        } else {
            spinnerAnim?.cancel()
            spinnerAnim = null
            busySpinner.scaleX = 1f
            busySpinner.scaleY = 1f
            busySpinner.visibility = View.GONE
        }
    }

    // ---- Tabs and the sliding navigation pill ----

    private fun showTab(index: Int, animate: Boolean = true) {
        val from = tab
        if (!animate || index == from) {
            tabViews.forEachIndexed { i, v ->
                v.visibility = if (i == index) View.VISIBLE else View.GONE
                v.alpha = 1f
            }
        } else {
            val out = tabViews[from]
            val inn = tabViews[index]
            out.animate().cancel()
            inn.animate().cancel()
            out.animate().alpha(0f).setDuration(140).start()
            inn.alpha = 0f
            inn.visibility = View.VISIBLE
            inn.animate().alpha(1f).setStartDelay(80).setDuration(200).start()
            staggerIn(inn)
            // Hide whatever is no longer selected once the cross-fade is over.
            root.postDelayed({
                tabViews.forEachIndexed { i, v ->
                    if (i != tab) {
                        v.visibility = View.GONE
                        v.alpha = 1f
                    }
                }
            }, 300)
        }
        tab = index
        if (index == 2) refreshApplied()
        updateNav(animate && from != index)
    }

    /** Forces a layout pass so the new item positions are known right away. */
    private fun relayoutNav() {
        navRow.measure(
            View.MeasureSpec.makeMeasureSpec(navRow.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(navRow.height, View.MeasureSpec.EXACTLY)
        )
        navRow.layout(navRow.left, navRow.top, navRow.right, navRow.bottom)
    }

    private fun navBounds(i: Int): Pair<Float, Float> {
        val item = navItems[i]
        return Pair((navRow.left + item.left).toFloat(), (navRow.left + item.right).toFloat())
    }

    /**
     * The selected tab shows its label; the tonal pill behind it slides to it, stretching like a
     * drop (the leading edge moves first), and the other icons glide into their new places.
     */
    private fun updateNav(animate: Boolean) {
        val oldLefts = navItems.map { it.left }
        navItems.forEachIndexed { i, item ->
            val icon = item.getChildAt(0) as ImageView
            val label = item.getChildAt(1) as TextView
            val selected = i == tab
            label.visibility = if (selected) View.VISIBLE else View.GONE
            label.setTextColor(accent)
            icon.setColorFilter(if (selected) accent else textMuted)
        }
        if (navRow.width == 0) {
            navRow.post { snapIndicator() }
            return
        }
        relayoutNav()
        if (animate) {
            val newLefts = navItems.map { it.left }
            navItems.forEachIndexed { i, item ->
                item.animate().cancel()
                item.translationX = (oldLefts[i] - newLefts[i]).toFloat()
                item.animate().translationX(0f).setDuration(340)
                    .setInterpolator(OvershootInterpolator(0.7f)).start()
            }
            val label = navItems[tab].getChildAt(1) as TextView
            label.alpha = 0f
            label.animate().alpha(1f).setStartDelay(120).setDuration(200).start()
        }
        val (l, r) = navBounds(tab)
        animateIndicatorTo(l, r, animate)
    }

    private fun snapIndicator() {
        relayoutNav()
        val (l, r) = navBounds(tab)
        animateIndicatorTo(l, r, false)
    }

    private fun setIndicator(l: Float, r: Float) {
        indLeft = l
        indRight = r
        val lp = navIndicator.layoutParams
        lp.width = (r - l).toInt().coerceAtLeast(dp(40))
        navIndicator.layoutParams = lp
        navIndicator.translationX = l
    }

    private fun animateIndicatorTo(nl: Float, nr: Float, animate: Boolean) {
        animL?.cancel()
        animR?.cancel()
        if (!animate || !indReady) {
            setIndicator(nl, nr)
            indReady = true
            return
        }
        val sl = indLeft
        val sr = indRight
        val movingRight = nl > sl
        val dl = if (movingRight) 380L else 260L
        val dr = if (movingRight) 260L else 380L
        animL = ValueAnimator.ofFloat(sl, nl).apply {
            duration = dl
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setIndicator(it.animatedValue as Float, indRight) }
            start()
        }
        animR = ValueAnimator.ofFloat(sr, nr).apply {
            duration = dr
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setIndicator(indLeft, it.animatedValue as Float) }
            start()
        }
    }

    // ---- Theme ----

    private fun buildSegments() {
        for (i in 0 until 6) {
            val s = MaterialCardView(this)
            val lp = LinearLayout.LayoutParams(0, dp(10), 1f)
            if (i < 5) lp.marginEnd = dp(6)
            s.layoutParams = lp
            s.radius = dp(5).toFloat()
            s.cardElevation = 0f
            s.strokeWidth = dp(1).coerceAtLeast(1)
            segRow.addView(s)
            segments.add(s)
        }
    }

    private fun trackTint(): ColorStateList =
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, Color.parseColor("#3A3F45"))
        )

    private fun applyTheme() {
        val a = accent
        val tint = ColorStateList.valueOf(a)
        themeButton.setCardBackgroundColor(a)
        listOf(applyButton, requestButton, addCustomButton, importPackButton).forEach {
            it.backgroundTintList = tint
            it.setTextColor(onAccent)
        }
        removeButton.setStrokeColor(tint)
        removeButton.setTextColor(a)
        switches.forEach { (it as? MaterialSwitch)?.trackTintList = trackTint() }
        segments.forEach { it.setStrokeColor(a) }
        busySpinner.setColorFilter(a)
        launchButton.setCardBackgroundColor(container)
        launchIcon.setColorFilter(a)
        navIndicator.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(container)
        }
        updateNav(false)
        renderPacks()
        updateCount()
        flagAdapter.notifyDataSetChanged()
    }

    private fun showThemeDialog() {
        dialog()
            .setTitle("Accent color")
            .setSingleChoiceItems(accentNames, themeIndex) { d, which ->
                themeIndex = which
                prefs.edit().putInt("theme", which).apply()
                applyTheme()
                d.dismiss()
            }
            .show()
    }

    private fun updateCount() {
        val n = switches.count { it.isChecked }
        countText.text = n.toString()
        segments.forEachIndexed { i, s ->
            s.setCardBackgroundColor(if (i < n) accent else Color.TRANSPARENT)
        }
        if (lastCount >= 0 && n != lastCount) {
            // The number pops and the segment that changed springs in.
            countText.animate().cancel()
            countText.pivotX = 0f
            countText.pivotY = countText.height.toFloat()
            countText.scaleX = 1.2f
            countText.scaleY = 1.2f
            countText.animate().scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(OvershootInterpolator(2f)).start()
            val idx = if (n > lastCount) n - 1 else n
            segments.getOrNull(idx)?.let { s ->
                s.pivotX = 0f
                s.scaleX = 0.2f
                s.animate().scaleX(1f).setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.5f)).start()
            }
        }
        lastCount = n
    }

    // ---- Status and shell ----

    private fun hasShizuku(): Boolean =
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun robloxInstalled(): Boolean =
        try {
            packageManager.getPackageInfo(robloxPkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun launchRoblox() {
        val intent = packageManager.getLaunchIntentForPackage(robloxPkg)
        if (intent == null) {
            toast("Roblox isn't installed.")
            return
        }
        startActivity(intent)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun updateStatus() = runOnUiThread {
        val alive = Shizuku.pingBinder()
        val ready = hasShizuku()
        statusText.text = when {
            !alive -> "Shizuku not running"
            ready -> "Connected"
            else -> "Permission needed"
        }
        val dotColor = when {
            !alive -> Color.parseColor("#FF8A80")
            ready -> Color.parseColor("#7DDB8E")
            else -> Color.parseColor("#FFCB6B")
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
        hintText.text = when {
            !alive -> "Open the Shizuku app and tap Start."
            !ready -> "Allow Stargazer to use Shizuku."
            !robloxInstalled() -> "Roblox ($robloxPkg) isn't installed."
            else -> ""
        }
        connectCard.visibility = if (hintText.text.isEmpty()) View.GONE else View.VISIBLE
        requestButton.visibility = if (alive && !ready) View.VISIBLE else View.GONE
        val enabled = ready && !busy
        listOf(applyButton, viewButton, removeButton, addCustomButton, importButton, exportButton)
            .forEach {
                it.isEnabled = enabled
                it.alpha = if (enabled) 1f else 0.4f
            }
        if (tab == 2) refreshApplied()
    }

    /**
     * Runs a shell command through Shizuku. stderr is merged into stdout inside the shell so a
     * full stderr buffer can't deadlock us, and a watchdog kills the process after a timeout.
     * [stdin], when given, is piped to the command (used to write large flag files, which would
     * not fit in a command-line argument).
     */
    private fun runShell(cmd: String, stdin: String? = null): String {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null, arrayOf("sh", "-c", "{ $cmd\n} 2>&1"), null, null
        ) as Process
        val timedOut = AtomicBoolean(false)
        val killer = watchdog.schedule({
            timedOut.set(true)
            process.destroy()
        }, shellTimeoutSeconds, TimeUnit.SECONDS)
        try {
            if (stdin != null) {
                process.outputStream.use { it.write(stdin.toByteArray()) }
            }
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return if (timedOut.get()) out + "\n(timed out after ${shellTimeoutSeconds}s)" else out
        } finally {
            killer.cancel(false)
            process.destroy()
        }
    }

    /**
     * Runs [task] off the main thread, disables the buttons and spins the status icon meanwhile,
     * then shows its result in the Log tab (and its first line as a toast when [toastResult]).
     */
    private fun runTask(toastResult: Boolean = true, task: () -> String) {
        if (busy) {
            toast("Still working, try again in a moment.")
            return
        }
        busy = true
        outputText.text = "Running..."
        setBusyUi(true)
        updateStatus()
        executor.execute {
            val result = try {
                task()
            } catch (e: Exception) {
                "Error: $e"
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                setBusyUi(false)
                outputText.text = result
                if (toastResult) {
                    val first = result.lineSequence().firstOrNull { it.isNotBlank() }
                    if (first != null && first.length < 120) {
                        Toast.makeText(this, first, Toast.LENGTH_SHORT).show()
                    }
                }
                updateStatus()
            }
        }
    }

    private fun confirmRestart(message: String, action: (restart: Boolean) -> Unit) {
        dialog()
            .setTitle("Restart Roblox?")
            .setMessage(message)
            .setPositiveButton("Continue & restart") { _, _ -> action(true) }
            .setNeutralButton("Continue, no restart") { _, _ -> action(false) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fpsOnScript(): String = "setprop $fpsProp true\n"

    // The restore block cleans up refresh-rate backups that may have been written by older builds.
    private fun fpsOffScript(): String =
        "if [ -e $backupPath ]; then\n" +
            "set -- \$(cat $backupPath)\n" +
            "if [ \"\$1\" = null ]; then settings delete system peak_refresh_rate; " +
            "else settings put system peak_refresh_rate \"\$1\"; fi\n" +
            "if [ \"\$2\" = null ]; then settings delete system min_refresh_rate; " +
            "else settings put system min_refresh_rate \"\$2\"; fi\n" +
            "rm -f $backupPath\n" +
            "fi\n" +
            "setprop $fpsProp false\n"

    private fun managedKeys(): Set<String> = tweaks.flatMap { it.flags.keys }.toSet()

    /** Keys added through the Custom flags card (paste or import), remembered so Remove can clear them. */
    private fun customKeys(): Set<String> =
        HashSet(prefs.getStringSet("custom_keys", emptySet()) ?: emptySet())

    /** Keys of packs that were deleted but may still be in the flags file until the next Apply. */
    private fun staleKeys(): Set<String> =
        HashSet(prefs.getStringSet("stale_keys", emptySet()) ?: emptySet())

    /** Values edited in the Applied tab. They are applied last, so they win over tweaks and packs. */
    private fun loadOverrides(): JSONObject =
        try {
            JSONObject(prefs.getString("overrides", "{}") ?: "{}")
        } catch (e: JSONException) {
            JSONObject()
        }

    private fun saveOverrides(o: JSONObject) {
        prefs.edit().putString("overrides", o.toString()).apply()
    }

    private fun jsonKeys(o: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val it = o.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun readFlagsObject(existing: String): JSONObject =
        try {
            if (existing.isBlank()) JSONObject() else JSONObject(existing)
        } catch (e: JSONException) {
            JSONObject()
        }

    private fun render(obj: JSONObject): String? = if (obj.length() == 0) null else obj.toString(2)

    /**
     * Builds the flags file content from the existing file. Order, lowest priority first:
     * enabled packs, then custom flags (left in place), then tweaks, then edited values.
     * When [removing], every key Stargazer manages (tweaks, packs, custom, edited, stale) is
     * deleted instead. Keys the user added by hand are never touched.
     * Returns null when nothing is left.
     */
    private fun mergeJson(
        existing: String,
        selected: List<Tweak>,
        packs: List<Pack>,
        custom: Set<String>,
        stale: Set<String>,
        overrides: JSONObject,
        removing: Boolean
    ): String? {
        val obj = readFlagsObject(existing)
        managedKeys().forEach { obj.remove(it) }
        stale.forEach { if (it !in custom || removing) obj.remove(it) }
        packs.forEach { p -> p.keys().forEach { k -> if (k !in custom || removing) obj.remove(k) } }
        if (removing) {
            custom.forEach { obj.remove(it) }
            jsonKeys(overrides).forEach { obj.remove(it) }
        } else {
            packs.filter { it.on }.forEach { p ->
                p.keys().forEach { k -> if (k !in custom) obj.put(k, p.flags.get(k)) }
            }
            selected.forEach { t -> t.flags.forEach { (k, v) -> obj.put(k, v) } }
            jsonKeys(overrides).forEach { k -> obj.put(k, overrides.get(k)) }
        }
        return render(obj)
    }

    /** Writes the flags file by piping the JSON over stdin (no size limit from argv). */
    private fun writeFlagsFile(json: String?): String =
        if (json == null) {
            runShell("rm -f $flagsPath")
        } else {
            runShell("cat > $flagsPath.new", json) +
                runShell("mv -f $flagsPath.new $flagsPath && chmod 0644 $flagsPath")
        }

    private fun applyFlags(restart: Boolean) {
        val editor = prefs.edit()
        tweaks.forEach { editor.putBoolean(it.prefKey, it.toggle.isChecked) }
        editor.apply()

        // Snapshot UI and pref state here; the task runs on a worker thread.
        val selected = tweaks.filter { it.toggle.isChecked }
        val fpsOn = cbFps.isChecked
        val packs = loadPacks()
        val custom = customKeys()
        val stale = staleKeys()
        val overrides = loadOverrides()
        prefs.edit().remove("stale_keys").apply()
        val activePacks = packs.count { it.on }

        runTask {
            val json = mergeJson(
                runShell("cat $flagsPath 2>/dev/null"), selected, packs, custom, stale, overrides, false
            )
            val sb = StringBuilder()
            sb.append(if (fpsOn) fpsOnScript() else fpsOffScript())
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Applied ${selected.size} tweak(s) and $activePacks pack(s). " +
                if (restart) "Roblox was restarted.'\n" else "Restart Roblox to use them.'\n")
            sb.append("echo; ls -la $flagsPath 2>&1\n")
            sb.append("echo; echo fps_prop=\$(getprop $fpsProp)\n")
            writeFlagsFile(json) + runShell(sb.toString())
        }
    }

    private fun viewFlags() {
        showTab(3)
        runTask(toastResult = false) {
            runShell(
                "echo '== flags file =='; " +
                    "if [ -f $flagsPath ]; then cat $flagsPath; else echo '(no flags file)'; fi; " +
                    "echo; echo '== fps =='; echo fps_prop=\$(getprop $fpsProp); " +
                    "echo peak=\$(settings get system peak_refresh_rate) " +
                    "min=\$(settings get system min_refresh_rate)"
            )
        }
    }

    private fun removeFlags(restart: Boolean) {
        // Snapshot before prefs change.
        val custom = customKeys()
        val stale = staleKeys()
        val packs = loadPacks()
        val overrides = loadOverrides()

        switches.forEach { it.isChecked = false }
        val editor = prefs.edit()
        tweaks.forEach { editor.remove(it.prefKey) }
        editor.remove("custom_keys").remove("stale_keys").remove("overrides").apply()
        // Packs stay saved but are switched off, so the next Apply won't bring them back.
        packs.forEach { it.on = false }
        savePacks(packs)
        renderPacks()

        runTask {
            val json = mergeJson(
                runShell("cat $flagsPath 2>/dev/null"), emptyList(), packs, custom, stale, overrides, true
            )
            val sb = StringBuilder()
            sb.append(fpsOffScript())
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Stargazer flags removed. " +
                if (restart) "Roblox was restarted.'" else "Restart Roblox to finish.'")
            writeFlagsFile(json) + runShell(sb.toString())
        }
    }

    // ---- Applied flags: see every flag in the file, tap one to edit it ----

    private fun formatValue(v: Any): String = if (v is String) "\"$v\"" else v.toString()

    private fun valueToText(v: Any): String = if (v is String) v else v.toString()

    /** true/false, whole numbers and decimals keep their type; anything else is stored as text. */
    private fun parseValue(raw: String): Any {
        val t = raw.trim()
        if (t == "true") return true
        if (t == "false") return false
        t.toLongOrNull()?.let { return it }
        t.toDoubleOrNull()?.let { if (it.isFinite()) return it }
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length - 1)
        return t
    }

    private fun styleValueChip(tv: TextView, value: Any) {
        val on = value == true
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (on) container else surfaceHigh)
        }
        tv.setTextColor(if (on) accent else textPrimary)
    }

    private fun sourceOf(
        key: String,
        overrides: Set<String>,
        custom: Set<String>,
        packs: List<Pack>
    ): String = when {
        key in overrides -> "Edited"
        key in managedKeys() -> "Tweak"
        key in custom -> "Custom"
        else -> packs.firstOrNull { key in it.keys() }?.name ?: "Other (not set by Stargazer)"
    }

    /** Reads the flags file and refreshes the Applied tab. */
    private fun refreshApplied() {
        if (!hasShizuku()) {
            appliedAll = emptyList()
            filterApplied()
            return
        }
        val overrides = jsonKeys(loadOverrides()).toSet()
        val custom = customKeys()
        val packs = loadPacks()
        executor.execute {
            val text = try {
                runShell("cat $flagsPath 2>/dev/null")
            } catch (e: Exception) {
                ""
            }
            val obj = readFlagsObject(text)
            val rows = jsonKeys(obj)
                .map { k -> FlagRow(k, obj.get(k), sourceOf(k, overrides, custom, packs)) }
                .sortedBy { it.key.lowercase() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                appliedAll = rows
                filterApplied()
            }
        }
    }

    private fun filterApplied() {
        val q = flagSearch.text.toString().trim().lowercase()
        val list = if (q.isEmpty()) {
            appliedAll
        } else {
            appliedAll.filter {
                it.key.lowercase().contains(q) || formatValue(it.value).lowercase().contains(q)
            }
        }
        flagAdapter.items = list
        flagAdapter.notifyDataSetChanged()
        appliedCount.text = when {
            !hasShizuku() -> "Connect Shizuku to see applied flags."
            appliedAll.isEmpty() -> "No flags applied yet."
            q.isEmpty() -> "${appliedAll.size} flags \u00b7 tap one to edit"
            else -> "${list.size} of ${appliedAll.size} flags"
        }
    }

    private fun showFlagEditor(row: FlagRow) {
        if (!hasShizuku()) {
            toast("Connect Shizuku first.")
            return
        }
        val input = EditText(this)
        input.setText(valueToText(row.value))
        input.setSelectAllOnFocus(true)
        input.typeface = Typeface.MONOSPACE
        input.setTextColor(textPrimary)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(24), dp(8), dp(24), 0)
        wrap.addView(input)
        val note = when (row.source) {
            "Edited" -> "You edited this. Your value is kept when you tap Apply."
            "Tweak" -> "Set by a tweak. Your edit is kept when you tap Apply."
            else -> "Source: ${row.source}. Use true, false, a number, or text."
        }
        dialog()
            .setTitle(row.key)
            .setMessage(note)
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> saveFlagValue(row, input.text.toString()) }
            .setNeutralButton("Delete") { _, _ -> deleteFlag(row) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveFlagValue(row: FlagRow, text: String) {
        if (text.isBlank()) {
            toast("Enter a value.")
            return
        }
        val value = parseValue(text)
        val ov = loadOverrides()
        ov.put(row.key, value)
        saveOverrides(ov)
        runTask {
            val merged = readFlagsObject(runShell("cat $flagsPath 2>/dev/null"))
            merged.put(row.key, value)
            writeFlagsFile(render(merged)) + "Saved ${row.key}. Restart Roblox to use it.\n"
        }
    }

    private fun deleteFlag(row: FlagRow) {
        val ov = loadOverrides()
        ov.remove(row.key)
        saveOverrides(ov)
        val custom = HashSet(customKeys())
        custom.remove(row.key)
        prefs.edit().putStringSet("custom_keys", custom).apply()
        runTask {
            val merged = readFlagsObject(runShell("cat $flagsPath 2>/dev/null"))
            merged.remove(row.key)
            writeFlagsFile(render(merged)) +
                "Deleted ${row.key}. It comes back on Apply if a tweak or pack still sets it.\n"
        }
    }

    // ---- Flag packs (clipboard) ----

    private fun loadPacks(): MutableList<Pack> {
        val out = mutableListOf<Pack>()
        val raw = prefs.getString("packs", null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pack(o.getLong("id"), o.getString("name"), o.getBoolean("on"), o.getJSONObject("flags")))
            }
        } catch (e: JSONException) {
            // Corrupt data: start with no packs.
        }
        return out
    }

    private fun savePacks(list: List<Pack>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("on", it.on).put("flags", it.flags))
        }
        prefs.edit().putString("packs", arr.toString()).apply()
    }

    /**
     * Reads flags out of pasted text. Tries real JSON first; if that fails (for example the file
     * was cut off while copying) it falls back to picking out every complete "Name": value pair.
     * Entries with odd names or nested values are skipped.
     */
    private fun parseLenient(raw: String): JSONObject? {
        val out = JSONObject()
        val text = raw.trim()
        var parsed: JSONObject? = null
        try {
            if (text.startsWith("{")) parsed = JSONObject(text)
        } catch (e: JSONException) {
            parsed = null
        }
        if (parsed != null) {
            val it = parsed.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = parsed.get(k)
                if (flagNameRegex.matches(k) && (v is Boolean || v is Number || v is String)) out.put(k, v)
            }
        } else {
            pairRegex.findAll(text).forEach { m ->
                val k = m.groupValues[1]
                val v = m.groupValues[2]
                val value: Any = when {
                    v.startsWith("\"") -> v.substring(1, v.length - 1)
                    v == "true" -> true
                    v == "false" -> false
                    v.contains('.') -> v.toDouble()
                    else -> v.toLongOrNull() ?: v.toDouble()
                }
                out.put(k, value)
            }
        }
        return if (out.length() == 0) null else out
    }

    private fun importPackFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (text.isBlank()) {
            toast("The clipboard is empty. Copy a JSON flag file first.")
            return
        }
        if (text.length > maxClipboardChars) {
            toast("That's too large to import.")
            return
        }
        val flags = parseLenient(text)
        if (flags == null) {
            toast("No flags found in the clipboard.")
            return
        }
        val packs = loadPacks()
        val id = (packs.maxOfOrNull { it.id } ?: 0L) + 1L
        packs.add(Pack(id, "Pack $id", true, flags))
        savePacks(packs)
        renderPacks()
        toast("Imported ${flags.length()} flags as Pack $id. Tap Apply to use it.")
    }

    private fun renderPacks() {
        val packs = loadPacks()
        packEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        packList.removeAllViews()
        packs.forEach { pack ->
            val sw = MaterialSwitch(this)
            sw.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            sw.text = "${pack.name}  \u00b7  ${pack.flags.length()} flags"
            sw.textSize = 15f
            sw.setTextColor(textPrimary)
            sw.setPadding(0, dp(8), 0, dp(8))
            sw.trackTintList = trackTint()
            sw.isChecked = pack.on
            sw.setOnCheckedChangeListener { _, checked -> setPackEnabled(pack.id, checked) }
            sw.setOnLongClickListener {
                confirmDeletePack(pack)
                true
            }
            packList.addView(sw)
        }
    }

    private fun setPackEnabled(id: Long, on: Boolean) {
        val packs = loadPacks()
        packs.firstOrNull { it.id == id }?.on = on
        savePacks(packs)
    }

    private fun confirmDeletePack(pack: Pack) {
        dialog()
            .setTitle("Delete ${pack.name}?")
            .setMessage("Its flags are removed from Roblox the next time you tap Apply.")
            .setPositiveButton("Delete") { _, _ ->
                val packs = loadPacks().filter { it.id != pack.id }
                savePacks(packs)
                val stale = HashSet(staleKeys())
                stale.addAll(pack.keys())
                prefs.edit().putStringSet("stale_keys", stale).apply()
                renderPacks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Custom flags: paste, import, export ----

    /**
     * Parses pasted or imported FFlag JSON. Accepts a full object or bare `"Name": value,` lines.
     * Flag names must be letters, digits or underscores; values must be true/false, a number or a string.
     */
    private fun parseFlags(raw: String): JSONObject? {
        var text = raw.trim().trimEnd(',')
        if (text.isEmpty()) {
            toast("Nothing to add.")
            return null
        }
        if (!text.startsWith("{")) text = "{$text}"
        val obj = try {
            JSONObject(text)
        } catch (e: JSONException) {
            toast("That isn't valid JSON.")
            return null
        }
        if (obj.length() == 0) {
            toast("No flags found.")
            return null
        }
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            if (!flagNameRegex.matches(k) || !(v is Boolean || v is Number || v is String)) {
                toast("Unsupported flag: $k")
                return null
            }
        }
        return obj
    }

    private fun onAddCustomClicked() {
        val obj = parseFlags(customInput.text.toString()) ?: return
        confirmRestart("Add ${obj.length()} custom flag(s) to the flags file.") {
            customInput.text.clear()
            addCustomFlags(obj, it)
        }
    }

    private fun addCustomFlags(obj: JSONObject, restart: Boolean) {
        val keys = jsonKeys(obj).toSet()
        val custom = HashSet(customKeys())
        custom.addAll(keys)
        prefs.edit().putStringSet("custom_keys", custom).apply()

        runTask {
            val merged = readFlagsObject(runShell("cat $flagsPath 2>/dev/null"))
            keys.forEach { k -> merged.put(k, obj.get(k)) }
            val sb = StringBuilder()
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Added ${keys.size} custom flag(s). " +
                if (restart) "Roblox was restarted.'\n" else "Restart Roblox to use them.'\n")
            writeFlagsFile(render(merged)) + runShell(sb.toString())
        }
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqImport)
    }

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "stargazer-flags.json")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqExport)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            reqImport -> importFrom(uri)
            reqExport -> exportTo(uri)
        }
    }

    private fun importFrom(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { s ->
                val bytes = s.readBytes()
                if (bytes.size > 64 * 1024) null else String(bytes)
            }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            toast("Couldn't read that file (max 64 KB).")
            return
        }
        val obj = parseFlags(text) ?: return
        confirmRestart("Add ${obj.length()} flag(s) from the file.") { addCustomFlags(obj, it) }
    }

    private fun exportTo(uri: Uri) {
        runTask {
            val text = runShell("cat $flagsPath 2>/dev/null").ifBlank { "{}" }
            val count = readFlagsObject(text).length()
            val out = contentResolver.openOutputStream(uri) ?: return@runTask "Couldn't open the file."
            out.use { it.write(text.toByteArray()) }
            "Exported $count flag(s)."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        spinnerAnim?.cancel()
        animL?.cancel()
        animR?.cancel()
        executor.shutdownNow()
        watchdog.shutdownNow()
    }
}
