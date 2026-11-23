package com.faststrap.dev

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    /** One UI toggle plus the FFlags it controls. Add a new tweak by adding one entry in onCreate. */
    private class Tweak(
        val prefKey: String,
        val toggle: CompoundButton,
        val flags: Map<String, Any>
    )

    /** A set of flags imported from the clipboard, a file or a link. Applied before tweaks. */
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

    /** One row of the flag explorer. */
    private class FlagRow(
        val key: String,
        val value: Any,
        val group: String,
        val source: String,
        val applied: Boolean,
        val category: String
    )

    /** Everything Remove all clears, kept briefly so Undo can put it back. */
    private class PrefState(
        val tweaks: Map<String, Boolean>,
        val packs: String?,
        val custom: Set<String>,
        val stale: Set<String>,
        val overrides: String?
    )

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
            holder.source.text = r.source + " \u00b7 " + r.category + if (r.applied) "" else " \u00b7 not applied"
            holder.value.text = formatValue(r.value)
            styleValueChip(holder.value, r.value)
            holder.itemView.setOnClickListener { showFlagSheet(r) }
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 0f
            holder.itemView.animate().alpha(if (r.applied) 1f else 0.6f)
                .setStartDelay((position % 10) * 25L).setDuration(200).start()
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
    private val removeLabel = "Removed all Stargazer flags"

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
    private lateinit var profileRow: LinearLayout
    private lateinit var segRow: LinearLayout
    private lateinit var segExplorer: TextView
    private lateinit var segAdd: TextView
    private lateinit var explorerView: View
    private lateinit var addView: View
    private lateinit var filterRow: LinearLayout
    private lateinit var flagSearch: EditText
    private lateinit var appliedCount: TextView
    private lateinit var flagRecycler: RecyclerView
    private lateinit var packEmpty: TextView
    private lateinit var packList: LinearLayout
    private lateinit var customInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var perfStatus: TextView
    private lateinit var fpsText: TextView
    private lateinit var frameTimeText: TextView
    private lateinit var frameGraph: FrameGraphView
    private lateinit var cpuText: TextView
    private lateinit var gpuText: TextView
    private lateinit var ramText: TextView
    private lateinit var tempText: TextView
    private lateinit var deviceInfoText: TextView
    private lateinit var historyEmpty: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var logText: TextView
    private lateinit var requestButton: MaterialButton
    private lateinit var applyButton: MaterialButton
    private lateinit var viewButton: MaterialButton
    private lateinit var undoButton: MaterialButton
    private lateinit var removeButton: MaterialButton
    private lateinit var clearLogButton: MaterialButton
    private lateinit var addCustomButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var importPackButton: MaterialButton
    private lateinit var importUrlButton: MaterialButton
    private lateinit var launchButton: MaterialCardView
    private lateinit var launchIcon: ImageView
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
    private var allRows: List<FlagRow> = emptyList()

    // Flag explorer state.
    private var showingAdd = false
    private var scopeAll = false
    private var srcFilter = 0
    private var catFilter = 0
    private var sortMode = 0

    // Sliding nav indicator state.
    private var indLeft = 0f
    private var indRight = 0f
    private var indReady = false
    private var animL: ValueAnimator? = null
    private var animR: ValueAnimator? = null

    private val history by lazy { FlagHistory(File(filesDir, "history")) }
    private val activityLog = ActivityLog()

    // Shell commands run one at a time; the watchdog kills hung processes. Downloads and the
    // live performance sampler get their own threads so they never block a flag change.
    private val executor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private val perfExecutor = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var busy = false
    private var spinnerAnim: ObjectAnimator? = null

    @Volatile
    private var perfRunning = false

    @Volatile
    private var perfToken = 0

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
        profileRow = findViewById(R.id.profileRow)
        segRow = findViewById(R.id.segRow)
        segExplorer = findViewById(R.id.segExplorer)
        segAdd = findViewById(R.id.segAdd)
        explorerView = findViewById(R.id.explorerView)
        addView = findViewById(R.id.addView)
        filterRow = findViewById(R.id.filterRow)
        flagSearch = findViewById(R.id.flagSearch)
        appliedCount = findViewById(R.id.appliedCount)
        flagRecycler = findViewById(R.id.flagRecycler)
        packEmpty = findViewById(R.id.packEmpty)
        packList = findViewById(R.id.packList)
        customInput = findViewById(R.id.customInput)
        urlInput = findViewById(R.id.urlInput)
        perfStatus = findViewById(R.id.perfStatus)
        fpsText = findViewById(R.id.fpsText)
        frameTimeText = findViewById(R.id.frameTimeText)
        frameGraph = findViewById(R.id.frameGraph)
        cpuText = findViewById(R.id.cpuText)
        gpuText = findViewById(R.id.gpuText)
        ramText = findViewById(R.id.ramText)
        tempText = findViewById(R.id.tempText)
        deviceInfoText = findViewById(R.id.deviceInfoText)
        historyEmpty = findViewById(R.id.historyEmpty)
        historyList = findViewById(R.id.historyList)
        logText = findViewById(R.id.logText)
        requestButton = findViewById(R.id.requestButton)
        applyButton = findViewById(R.id.applyButton)
        viewButton = findViewById(R.id.viewButton)
        undoButton = findViewById(R.id.undoButton)
        removeButton = findViewById(R.id.removeButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        addCustomButton = findViewById(R.id.addCustomButton)
        importButton = findViewById(R.id.importButton)
        exportButton = findViewById(R.id.exportButton)
        importPackButton = findViewById(R.id.importPackButton)
        importUrlButton = findViewById(R.id.importUrlButton)
        launchButton = findViewById(R.id.launchButton)
        launchIcon = findViewById(R.id.launchIcon)
        cbFps = findViewById(R.id.cbFps)
        cbFpsCounter = findViewById(R.id.cbFpsCounter)
        cbSky = findViewById(R.id.cbSky)
        cbGrass = findViewById(R.id.cbGrass)
        cbShadows = findViewById(R.id.cbShadows)
        cbTextures = findViewById(R.id.cbTextures)
        tabViews = listOf(
            findViewById<View>(R.id.tabTweaks),
            findViewById<View>(R.id.tabFlags),
            findViewById<View>(R.id.tabPerf),
            findViewById<View>(R.id.tabActivity)
        )
        navItems = listOf(
            findViewById<LinearLayout>(R.id.navTweaks),
            findViewById<LinearLayout>(R.id.navFlags),
            findViewById<LinearLayout>(R.id.navPerf),
            findViewById<LinearLayout>(R.id.navActivity)
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
        switches.forEach {
            it.setOnCheckedChangeListener { _, _ ->
                updateCount()
                renderProfiles()
            }
        }

        flagRecycler.layoutManager = LinearLayoutManager(this)
        flagRecycler.adapter = flagAdapter
        flagSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        requestButton.setOnClickListener { Shizuku.requestPermission(100) }
        applyButton.setOnClickListener { startApply() }
        viewButton.setOnClickListener { viewFlags() }
        undoButton.setOnClickListener { undoLast(null) }
        removeButton.setOnClickListener {
            confirmRestart("Remove all Stargazer flags, including packs, edits and custom flags added here. Flags you added by hand outside the app are kept. You can undo this right after.") {
                removeFlags(it)
            }
        }
        clearLogButton.setOnClickListener {
            activityLog.clear()
            renderLog()
        }
        addCustomButton.setOnClickListener { onAddCustomClicked() }
        importButton.setOnClickListener { startImport() }
        exportButton.setOnClickListener { startExport() }
        importPackButton.setOnClickListener { importPackFromClipboard() }
        importUrlButton.setOnClickListener { importFromUrl() }
        segExplorer.setOnClickListener { setFlagsMode(false) }
        segAdd.setOnClickListener { setFlagsMode(true) }
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
            themeButton, statusPill, launchButton, applyButton, requestButton, viewButton, undoButton,
            removeButton, clearLogButton, addCustomButton, importButton, exportButton,
            importPackButton, importUrlButton, segExplorer, segAdd
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

    override fun onPause() {
        super.onPause()
        stopPerf()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dialog(): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Stargazer_Dialog)

    private fun fmtDate(t: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(t))

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Stargazer", text))
    }

    // ---- Log ----

    private fun log(severity: Severity, text: String) {
        activityLog.add(severity, text)
        renderLog()
    }

    private fun renderLog() {
        logText.text = activityLog.render()
    }

    private fun logIssues(issues: List<Issue>) {
        issues.sortedBy { it.severity.ordinal }.take(40).forEach { activityLog.add(it.severity, it.line()) }
        renderLog()
    }

    /** Newest entry first, so details are added before their summary line. */
    private fun logResult(result: String) {
        val lines = result.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val severity = if (lines[0].startsWith("Error")) Severity.ERROR else Severity.INFO
        val details = lines.drop(1).take(12)
        if (lines.size > 13) activityLog.add(Severity.INFO, "  \u2026 ${lines.size - 13} more lines")
        details.reversed().forEach { activityLog.add(Severity.INFO, "  $it") }
        activityLog.add(severity, lines[0])
        renderLog()
    }

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
     * Opening sequence: the plain badge shape is already on screen (slate background), then it
     * turns while the logo pops in, holds, and shrinks away as the top bar, content and
     * navigation bar slide into place.
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
        if (from == 2 && index != 2) stopPerf()
        when (index) {
            1 -> {
                styleSeg()
                renderChips()
                if (!showingAdd) refreshExplorer()
            }
            2 -> {
                updateDeviceInfo()
                startPerf()
            }
            3 -> {
                renderHistory()
                renderLog()
            }
        }
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

    private fun pillBg(color: Int, radiusDp: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }

    private fun makeChip(label: String, active: Boolean): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setPadding(dp(16), dp(10), dp(16), dp(10))
        t.setTextColor(if (active) accent else textPrimary)
        t.background = pillBg(if (active) container else surfaceHigh)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(8)
        t.layoutParams = lp
        pressable(t)
        return t
    }

    private fun applyTheme() {
        val a = accent
        val tint = ColorStateList.valueOf(a)
        themeButton.setCardBackgroundColor(a)
        listOf(applyButton, requestButton, addCustomButton, importPackButton, importUrlButton).forEach {
            it.backgroundTintList = tint
            it.setTextColor(onAccent)
        }
        removeButton.setStrokeColor(tint)
        removeButton.setTextColor(a)
        clearLogButton.setTextColor(a)
        switches.forEach { (it as? MaterialSwitch)?.trackTintList = trackTint() }
        segments.forEach { it.setStrokeColor(a) }
        busySpinner.setColorFilter(a)
        frameGraph.accent = a
        launchButton.setCardBackgroundColor(container)
        launchIcon.setColorFilter(a)
        navIndicator.background = pillBg(container, 24)
        updateNav(false)
        styleSeg()
        renderChips()
        renderProfiles()
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

    // ---- Status ----

    private fun hasShizuku(): Boolean =
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun robloxVersion(): String? =
        try {
            packageManager.getPackageInfo(robloxPkg, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
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
            ready -> "Connected \u00b7 Shizuku \u00b7 ${shizukuMode()}"
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
        val cur = robloxVersion()
        val last = prefs.getString("applied_version", null)
        hintText.text = when {
            !alive -> "Open the Shizuku app and tap Start."
            !ready -> "Allow Stargazer to use Shizuku."
            cur == null -> "Roblox ($robloxPkg) isn't installed."
            last != null && last != cur ->
                "Roblox updated ($last \u2192 $cur) since you last applied flags. Review the Flags tab, some may no longer work."
            else -> ""
        }
        connectCard.visibility = if (hintText.text.isEmpty()) View.GONE else View.VISIBLE
        requestButton.visibility = if (alive && !ready) View.VISIBLE else View.GONE
        val enabled = ready && !busy
        listOf(
            applyButton, viewButton, undoButton, removeButton, addCustomButton, importButton, exportButton
        ).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
        if (tab == 1 && !showingAdd) refreshExplorer()
        if (tab == 2) {
            updateDeviceInfo()
            startPerf()
        }
    }

    private fun shizukuMode(): String =
        try {
            when (val uid = Shizuku.getUid()) {
                0 -> "root"
                2000 -> "adb"
                else -> "uid $uid"
            }
        } catch (e: Throwable) {
            "unknown"
        }

    // ---- Shell ----

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
     * then writes its result to the log (and its first line as a toast when [toastResult]).
     */
    private fun runTask(
        toastResult: Boolean = true,
        onDone: ((String) -> Unit)? = null,
        task: () -> String
    ) {
        if (busy) {
            toast("Still working, try again in a moment.")
            return
        }
        busy = true
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
                logResult(result)
                if (toastResult) {
                    val first = result.lineSequence().firstOrNull { it.isNotBlank() }
                    if (first != null && first.length < 120) {
                        Toast.makeText(this, first, Toast.LENGTH_SHORT).show()
                    }
                }
                onDone?.invoke(result)
                updateStatus()
                if (tab == 3) renderHistory()
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

    // ---- Flags file: merge, write, history ----

    private fun managedKeys(): Set<String> = tweaks.flatMap { it.flags.keys }.toSet()

    /** Keys added through the custom flags card, kept so Remove can clear them. */
    private fun customKeys(): Set<String> =
        HashSet(prefs.getStringSet("custom_keys", emptySet()) ?: emptySet())

    /** Keys of packs that were deleted but may still be in the flags file until the next Apply. */
    private fun staleKeys(): Set<String> =
        HashSet(prefs.getStringSet("stale_keys", emptySet()) ?: emptySet())

    /** Values edited in the flag sheet. They are applied last, so they win over tweaks and packs. */
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

    /**
     * Writes the flags file and records it in the history (plus the original file the first
     * time). Also remembers which Roblox version the flags were applied on.
     */
    private fun commitFlags(existing: String, json: String?, label: String): String {
        val before = readFlagsObject(existing)
        val after = if (json == null) JSONObject() else readFlagsObject(json)
        val ver = robloxVersion()
        if (history.isEmpty() && before.length() > 0) history.add("Before Stargazer", before, ver)
        val out = writeFlagsFile(json)
        val newest = history.list().firstOrNull()
        if (newest == null || !FlagDiff.diff(newest.flags, after).isEmpty) history.add(label, after, ver)
        if (ver != null) prefs.edit().putString("applied_version", ver).apply()
        return out
    }

    // ---- Apply (with validation), view, remove ----

    /** What Apply would write, as (source, flags) pairs from lowest to highest priority. */
    private fun intendedSources(): List<Pair<String, Map<String, Any>>> {
        val sources = mutableListOf<Pair<String, Map<String, Any>>>()
        loadPacks().filter { it.on }.forEach { sources.add(Pair(it.name, FlagValidator.toMap(it.flags))) }
        tweaks.filter { it.toggle.isChecked }.forEach { sources.add(Pair("Tweak ${it.prefKey}", it.flags)) }
        val ov = loadOverrides()
        if (ov.length() > 0) sources.add(Pair("Edited", FlagValidator.toMap(ov)))
        return sources
    }

    /**
     * Checks the selection before it is written. Errors and conflicts stop to ask; ordinary
     * warnings (such as an unrecognised prefix) go to the log so large packs don't nag.
     */
    private fun startApply() {
        val sources = intendedSources()
        val merged = LinkedHashMap<String, Any>()
        sources.forEach { merged.putAll(it.second) }
        val issues = FlagValidator.validateFlags(merged) + FlagValidator.findConflicts(sources)
        logIssues(issues)
        val blocking = issues.filter { it.severity == Severity.ERROR || it.conflict }
        val message = "Apply the selected tweaks and enabled packs. Roblox needs a restart for them to take effect."
        if (blocking.isEmpty()) {
            val minor = issues.size
            if (minor > 0) toast("$minor warnings, see the Activity log.")
            confirmRestart(message) { applyFlags(it) }
            return
        }
        val hasErrors = blocking.any { it.severity == Severity.ERROR }
        val builder = dialog()
            .setTitle(if (hasErrors) "Fix these first" else "Check before applying")
            .setMessage(FlagValidator.summarize(blocking))
        if (hasErrors) {
            builder.setPositiveButton("OK", null)
        } else {
            builder.setPositiveButton("Apply anyway") { _, _ -> confirmRestart(message) { applyFlags(it) } }
            builder.setNegativeButton("Cancel", null)
        }
        builder.show()
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
            val existing = runShell("cat $flagsPath 2>/dev/null")
            val json = mergeJson(existing, selected, packs, custom, stale, overrides, false)
            val sb = StringBuilder()
            sb.append(if (fpsOn) fpsOnScript() else fpsOffScript())
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Applied ${selected.size} tweak(s) and $activePacks pack(s). " +
                if (restart) "Roblox was restarted.'\n" else "Restart Roblox to use them.'\n")
            val label = "Applied ${selected.size} tweak(s), $activePacks pack(s)"
            commitFlags(existing, json, label) + runShell(sb.toString())
        }
    }

    private fun viewFlags() {
        if (!hasShizuku()) {
            toast("Connect Shizuku first.")
            return
        }
        executor.execute {
            val text = try {
                runShell("cat $flagsPath 2>/dev/null")
            } catch (e: Exception) {
                "Error: $e"
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val shown = if (text.isBlank()) "(no flags file)" else text
                dialog()
                    .setTitle("Flags file")
                    .setMessage(shown.take(60000))
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy") { _, _ ->
                        copyText(text)
                        toast("Copied.")
                    }
                    .show()
                log(Severity.INFO, "Viewed the flags file (${readFlagsObject(text).length()} flags).")
            }
        }
    }

    private fun captureState(): PrefState = PrefState(
        tweaks.associate { Pair(it.prefKey, it.toggle.isChecked) },
        prefs.getString("packs", null),
        customKeys(),
        staleKeys(),
        prefs.getString("overrides", null)
    )

    private fun restoreState(s: PrefState) {
        val e = prefs.edit()
        s.tweaks.forEach { (k, v) -> e.putBoolean(k, v) }
        e.putString("packs", s.packs)
        e.putStringSet("custom_keys", s.custom)
        e.putStringSet("stale_keys", s.stale)
        e.putString("overrides", s.overrides)
        e.apply()
        tweaks.forEach { it.toggle.isChecked = s.tweaks[it.prefKey] ?: false }
        renderPacks()
        renderProfiles()
    }

    private fun removeFlags(restart: Boolean) {
        // Snapshot before prefs change, so Undo can put everything back.
        val backup = captureState()
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

        runTask(onDone = { showRemoveSnack(backup) }) {
            val existing = runShell("cat $flagsPath 2>/dev/null")
            val json = mergeJson(existing, emptyList(), packs, custom, stale, overrides, true)
            val sb = StringBuilder()
            sb.append(fpsOffScript())
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Stargazer flags removed. " +
                if (restart) "Roblox was restarted.'" else "Restart Roblox to finish.'")
            commitFlags(existing, json, removeLabel) + runShell(sb.toString())
        }
    }

    private fun showRemoveSnack(backup: PrefState) {
        Snackbar.make(root, "Flags removed", Snackbar.LENGTH_LONG)
            .setAnchorView(bottomBar)
            .setAction("Undo") {
                restoreState(backup)
                undoLast(removeLabel)
            }
            .show()
    }

    // ---- History: restore, compare, undo ----

    private fun renderHistory() {
        executor.execute {
            val list = history.list()
            runOnUiThread {
                if (!isDestroyed) renderHistoryList(list)
            }
        }
    }

    private fun renderHistoryList(list: List<HistoryEntry>) {
        historyList.removeAllViews()
        historyEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (e in list.take(25)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(16), dp(12), dp(16), dp(12))
            row.background = pillBg(surfaceHigh, 18)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            row.layoutParams = lp
            val title = TextView(this)
            title.text = e.label
            title.setTextColor(textPrimary)
            title.textSize = 14f
            val sub = TextView(this)
            sub.text = fmtDate(e.time) + " \u00b7 ${e.flags.length()} flags" +
                (if (e.roblox != null) " \u00b7 Roblox ${e.roblox}" else "")
            sub.setTextColor(Color.parseColor("#9AA0A6"))
            sub.textSize = 12f
            row.addView(title)
            row.addView(sub)
            row.setOnClickListener { showHistoryMenu(e, list) }
            pressable(row)
            historyList.addView(row)
        }
    }

    private fun showHistoryMenu(e: HistoryEntry, list: List<HistoryEntry>) {
        val idx = list.indexOfFirst { it.id == e.id }
        val previous = if (idx >= 0) list.getOrNull(idx + 1) else null
        dialog()
            .setTitle(e.label)
            .setItems(
                arrayOf(
                    "Restore this configuration",
                    "Compare with current",
                    "Compare with the change before",
                    "Compare with another\u2026",
                    "Delete from history"
                )
            ) { _, which ->
                when (which) {
                    0 -> restoreEntry(e)
                    1 -> withCurrentFlags { cur ->
                        showDiff("Since \u201c${e.label}\u201d", FlagDiff.diff(e.flags, cur))
                    }
                    2 -> if (previous == null) {
                        toast("There is no earlier entry.")
                    } else {
                        showDiff("What \u201c${e.label}\u201d changed", FlagDiff.diff(previous.flags, e.flags))
                    }
                    3 -> pickOtherEntry(e, list)
                    4 -> {
                        executor.execute {
                            history.delete(e.id)
                            runOnUiThread { if (!isDestroyed) renderHistory() }
                        }
                    }
                }
            }
            .show()
    }

    private fun pickOtherEntry(e: HistoryEntry, list: List<HistoryEntry>) {
        val others = list.filter { it.id != e.id }
        if (others.isEmpty()) {
            toast("There is nothing else to compare with.")
            return
        }
        val names = others.take(25).map { it.label + " (" + fmtDate(it.time) + ")" }.toTypedArray()
        dialog().setTitle("Compare with").setItems(names) { _, which ->
            val other = others[which]
            val older = if (other.time < e.time) other else e
            val newer = if (other.time < e.time) e else other
            showDiff("${older.label} \u2192 ${newer.label}", FlagDiff.diff(older.flags, newer.flags))
        }.show()
    }

    private fun showDiff(title: String, d: DiffResult) {
        dialog()
            .setTitle(title)
            .setMessage(FlagDiff.format(d))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun withCurrentFlags(block: (JSONObject) -> Unit) {
        if (!hasShizuku()) {
            toast("Connect Shizuku first.")
            return
        }
        executor.execute {
            val text = try {
                runShell("cat $flagsPath 2>/dev/null")
            } catch (e: Exception) {
                ""
            }
            val cur = readFlagsObject(text)
            runOnUiThread {
                if (!isDestroyed) block(cur)
            }
        }
    }

    private fun restoreEntry(e: HistoryEntry) {
        if (!hasShizuku()) {
            toast("Connect Shizuku first.")
            return
        }
        confirmRestart(
            "Put the flags file back to \u201c${e.label}\u201d (${fmtDate(e.time)}). Your tweak toggles and packs in the app stay as they are, so tapping Apply later applies those again."
        ) { restart ->
            runTask {
                val existing = runShell("cat $flagsPath 2>/dev/null")
                val sb = StringBuilder()
                sb.append(commitFlags(existing, render(e.flags), "Restored: ${e.label}"))
                if (restart) sb.append(runShell("am force-stop $robloxPkg"))
                sb.append("Restored \u201c${e.label}\u201d.\n")
                sb.toString()
            }
        }
    }

    /** Goes back one step: the newest history entry is dropped and the one before it is written. */
    private fun undoLast(requireLabel: String?) {
        if (!hasShizuku()) {
            toast("Connect Shizuku first.")
            return
        }
        executor.execute {
            val list = history.list()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (list.size < 2 || (requireLabel != null && list[0].label != requireLabel)) {
                    toast("Nothing to undo.")
                    return@runOnUiThread
                }
                val newest = list[0]
                val target = list[1]
                if (requireLabel != null) {
                    doUndo(newest, target)
                } else {
                    dialog()
                        .setTitle("Undo last change?")
                        .setMessage(
                            "Undo \u201c${newest.label}\u201d and go back to \u201c${target.label}\u201d (${fmtDate(target.time)})."
                        )
                        .setPositiveButton("Undo") { _, _ -> doUndo(newest, target) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun doUndo(newest: HistoryEntry, target: HistoryEntry) {
        runTask {
            val out = writeFlagsFile(render(target.flags))
            history.delete(newest.id)
            out + "Undone: ${newest.label}. Restart Roblox to use it.\n"
        }
    }

    // ---- Profiles ----

    private fun renderProfiles() {
        profileRow.removeAllViews()
        val selected = tweaks.filter { it.toggle.isChecked }.map { it.prefKey }.toSet()
        val packsOn = loadPacks().filter { it.on }.map { it.id }.toSet()
        for (p in Profiles.builtIn) {
            val chip = makeChip(p.name, selected == p.tweaks)
            chip.setOnClickListener { applyProfile(p) }
            profileRow.addView(chip)
        }
        for (p in Profiles.loadCustom(prefs)) {
            val chip = makeChip(p.name, selected == p.tweaks && packsOn == (p.packs ?: emptySet<Long>()))
            chip.setOnClickListener { applyProfile(p) }
            chip.setOnLongClickListener {
                confirmDeleteProfile(p)
                true
            }
            profileRow.addView(chip)
        }
        val save = makeChip("+ Save current", false)
        save.setOnClickListener { promptSaveProfile() }
        profileRow.addView(save)
    }

    private fun applyProfile(p: Profile) {
        tweaks.forEach { it.toggle.isChecked = it.prefKey in p.tweaks }
        val ids = p.packs
        if (ids != null) {
            val packs = loadPacks()
            packs.forEach { it.on = it.id in ids }
            savePacks(packs)
            renderPacks()
        }
        renderProfiles()
        toast("\u201c${p.name}\u201d selected. Tap Apply to use it.")
    }

    private fun promptSaveProfile() {
        val input = EditText(this)
        input.hint = "Profile name"
        input.setTextColor(textPrimary)
        input.inputType = InputType.TYPE_CLASS_TEXT
        val wrap = FrameLayout(this)
        wrap.setPadding(dp(24), dp(8), dp(24), 0)
        wrap.addView(input)
        dialog()
            .setTitle("Save current selection")
            .setMessage("Saves the tweaks that are switched on and which packs are enabled.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> saveProfile(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveProfile(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            toast("Give the profile a name.")
            return
        }
        val list = Profiles.loadCustom(prefs).filter { !it.name.equals(name, ignoreCase = true) }.toMutableList()
        val tw = tweaks.filter { it.toggle.isChecked }.map { it.prefKey }.toSet()
        val pk = loadPacks().filter { it.on }.map { it.id }.toSet()
        list.add(Profile(name, tw, pk, false))
        Profiles.saveCustom(prefs, list)
        renderProfiles()
        toast("Saved \u201c$name\u201d.")
    }

    private fun confirmDeleteProfile(p: Profile) {
        dialog()
            .setTitle("Delete \u201c${p.name}\u201d?")
            .setPositiveButton("Delete") { _, _ ->
                val list = Profiles.loadCustom(prefs).filter { it.name != p.name }
                Profiles.saveCustom(prefs, list)
                renderProfiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Flag explorer ----

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
        tv.background = pillBg(if (on) container else surfaceHigh, 14)
        tv.setTextColor(if (on) accent else textPrimary)
    }

    private fun styleSeg() {
        listOf(Pair(segExplorer, !showingAdd), Pair(segAdd, showingAdd)).forEach { (v, selected) ->
            v.setTextColor(if (selected) accent else textMuted)
            v.background = if (selected) pillBg(container, 20) else null
        }
    }

    private fun setFlagsMode(add: Boolean) {
        if (showingAdd == add) return
        showingAdd = add
        styleSeg()
        explorerView.visibility = if (add) View.GONE else View.VISIBLE
        addView.visibility = if (add) View.VISIBLE else View.GONE
        staggerIn(if (add) addView else explorerView)
        if (!add) refreshExplorer()
    }

    private fun renderChips() {
        filterRow.removeAllViews()

        fun add(label: String, active: Boolean, onClick: () -> Unit) {
            val c = makeChip(label, active)
            c.setOnClickListener { onClick() }
            filterRow.addView(c)
        }

        add(if (scopeAll) "All known flags" else "Applied only", scopeAll) {
            scopeAll = !scopeAll
            renderChips()
            refreshExplorer()
        }
        add(if (srcFilter == 0) "Source" else FlagInfo.sources[srcFilter], srcFilter != 0) {
            pickFilter("Source", FlagInfo.sources, srcFilter) { srcFilter = it }
        }
        add(if (catFilter == 0) "Category" else FlagInfo.categories[catFilter], catFilter != 0) {
            pickFilter("Category", FlagInfo.categories, catFilter) { catFilter = it }
        }
        add("Sort: ${FlagInfo.sorts[sortMode]}", false) {
            pickFilter("Sort by", FlagInfo.sorts, sortMode) { sortMode = it }
        }
    }

    private fun pickFilter(title: String, options: List<String>, current: Int, set: (Int) -> Unit) {
        dialog()
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), current) { d, which ->
                set(which)
                d.dismiss()
                renderChips()
                applyFilters()
            }
            .show()
    }

    private fun makeRow(
        k: String,
        v: Any,
        applied: Boolean,
        overrides: Set<String>,
        custom: Set<String>,
        tweakKeys: Set<String>,
        packSets: List<Pair<Pack, Set<String>>>
    ): FlagRow {
        var group = "Other"
        var source = "Other (not set by Stargazer)"
        when {
            k in overrides -> {
                group = "Edited"
                source = "Edited"
            }
            k in tweakKeys -> {
                group = "Tweak"
                source = "Tweak"
            }
            k in custom -> {
                group = "Custom"
                source = "Custom"
            }
            else -> {
                val p = packSets.firstOrNull { k in it.second }
                if (p != null) {
                    group = "Pack"
                    source = p.first.name
                }
            }
        }
        return FlagRow(k, v, group, source, applied, FlagInfo.categoryOf(k))
    }

    /** Reads the flags file (and, in "All known" mode, what tweaks and packs could add). */
    private fun refreshExplorer() {
        if (!hasShizuku()) {
            allRows = emptyList()
            applyFilters()
            return
        }
        val overrides = jsonKeys(loadOverrides()).toSet()
        val custom = customKeys()
        val packs = loadPacks()
        val packSets = packs.map { Pair(it, it.keys().toHashSet() as Set<String>) }
        val tweakKeys = managedKeys()
        val includeKnown = scopeAll
        executor.execute {
            val text = try {
                runShell("cat $flagsPath 2>/dev/null")
            } catch (e: Exception) {
                ""
            }
            val obj = readFlagsObject(text)
            val rows = ArrayList<FlagRow>()
            val seen = HashSet<String>()
            for (k in jsonKeys(obj)) {
                seen.add(k)
                rows.add(makeRow(k, obj.get(k), true, overrides, custom, tweakKeys, packSets))
            }
            if (includeKnown) {
                for (t in tweaks) {
                    for ((k, v) in t.flags) {
                        if (seen.add(k)) rows.add(makeRow(k, v, false, overrides, custom, tweakKeys, packSets))
                    }
                }
                for (p in packs) {
                    for (k in p.keys()) {
                        if (seen.add(k)) rows.add(makeRow(k, p.flags.get(k), false, overrides, custom, tweakKeys, packSets))
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allRows = rows
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        var list = allRows
        val q = flagSearch.text.toString().trim().lowercase()
        if (q.isNotEmpty()) {
            list = list.filter {
                it.key.lowercase().contains(q) || formatValue(it.value).lowercase().contains(q)
            }
        }
        if (srcFilter > 0) list = list.filter { it.group == FlagInfo.sources[srcFilter] }
        if (catFilter > 0) list = list.filter { it.category == FlagInfo.categories[catFilter] }
        list = when (sortMode) {
            0 -> list.sortedBy { it.key.lowercase() }
            1 -> list.sortedByDescending { it.key.lowercase() }
            2 -> list.sortedWith(compareBy<FlagRow>({ it.group }, { it.key.lowercase() }))
            else -> list.sortedWith(compareBy<FlagRow>({ formatValue(it.value) }, { it.key.lowercase() }))
        }
        flagAdapter.items = list
        flagAdapter.notifyDataSetChanged()
        appliedCount.text = when {
            !hasShizuku() -> "Connect Shizuku to see your flags."
            allRows.isEmpty() -> "No flags applied yet."
            list.size == allRows.size -> "${allRows.size} flags \u00b7 tap one for details"
            else -> "${list.size} of ${allRows.size} flags"
        }
    }

    private fun buildFlagInfo(row: FlagRow): String {
        val lines = mutableListOf<String>()
        lines.add("Default value: not known")
        lines.add("Description: Stargazer has no reference data for this flag.")
        val issues = FlagValidator.validateFlags(mapOf(row.key to row.value))
        if (issues.isEmpty()) {
            lines.add("Checks: the name, type and prefix look valid.")
        } else {
            issues.forEach { lines.add(it.line()) }
        }
        val cur = robloxVersion()
        val last = prefs.getString("applied_version", null)
        if (cur != null && last != null && cur != last) {
            lines.add("! Last applied on Roblox $last, but $cur is installed. Check that it still works.")
        } else if (cur != null) {
            lines.add("Roblox installed: $cur")
        }
        if (!row.applied) lines.add("This flag is known to Stargazer but isn't in the flags file right now.")
        return lines.joinToString("\n")
    }

    /** A bottom sheet with the full flag name, its value, details and actions. */
    private fun showFlagSheet(row: FlagRow) {
        val dlg = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_flag, null)
        val input = v.findViewById<EditText>(R.id.sheetValue)
        val save = v.findViewById<MaterialButton>(R.id.sheetSave)
        val copy = v.findViewById<MaterialButton>(R.id.sheetCopy)
        val delete = v.findViewById<MaterialButton>(R.id.sheetDelete)
        v.findViewById<TextView>(R.id.sheetName).text = row.key
        v.findViewById<TextView>(R.id.sheetSub).text =
            FlagValidator.typeLabel(row.value) + " \u00b7 " + row.category + " (guessed from the name) \u00b7 " + row.source
        v.findViewById<TextView>(R.id.sheetInfo).text = buildFlagInfo(row)
        input.setText(valueToText(row.value))
        save.text = if (row.applied) "Save value" else "Add to flags file"
        save.backgroundTintList = ColorStateList.valueOf(accent)
        save.setTextColor(onAccent)
        delete.visibility = if (row.applied) View.VISIBLE else View.GONE
        copy.setOnClickListener {
            copyText(row.key)
            toast("Name copied.")
        }
        save.setOnClickListener {
            if (!hasShizuku()) {
                toast("Connect Shizuku first.")
            } else {
                saveFlagValue(row.key, input.text.toString())
                dlg.dismiss()
            }
        }
        delete.setOnClickListener {
            if (!hasShizuku()) {
                toast("Connect Shizuku first.")
            } else {
                deleteFlag(row.key)
                dlg.dismiss()
            }
        }
        dlg.setContentView(v)
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.setOnShowListener { dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        dlg.show()
    }

    private fun saveFlagValue(key: String, text: String) {
        if (text.isBlank()) {
            toast("Enter a value.")
            return
        }
        val value = parseValue(text)
        val ov = loadOverrides()
        ov.put(key, value)
        saveOverrides(ov)
        runTask {
            val existing = runShell("cat $flagsPath 2>/dev/null")
            val merged = readFlagsObject(existing)
            merged.put(key, value)
            commitFlags(existing, render(merged), "Edited $key") + "Saved $key. Restart Roblox to use it.\n"
        }
    }

    private fun deleteFlag(key: String) {
        val ov = loadOverrides()
        ov.remove(key)
        saveOverrides(ov)
        val custom = HashSet(customKeys())
        custom.remove(key)
        prefs.edit().putStringSet("custom_keys", custom).apply()
        runTask {
            val existing = runShell("cat $flagsPath 2>/dev/null")
            val merged = readFlagsObject(existing)
            merged.remove(key)
            commitFlags(existing, render(merged), "Deleted $key") +
                "Deleted $key. It comes back on Apply if a tweak or pack still sets it.\n"
        }
    }

    // ---- Performance and device ----

    private fun startPerf() {
        if (perfRunning) return
        if (!hasShizuku()) {
            perfStatus.text = "Connect Shizuku to see live stats."
            return
        }
        perfRunning = true
        val token = ++perfToken
        perfExecutor.execute {
            while (perfRunning && token == perfToken && !isDestroyed) {
                val out = try {
                    runShell(PerfSampler.script(robloxPkg))
                } catch (e: Exception) {
                    ""
                }
                val sample = PerfSampler.parse(out, robloxPkg)
                runOnUiThread {
                    if (perfRunning && token == perfToken && !isDestroyed) showSample(sample)
                }
                try {
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopPerf() {
        perfRunning = false
        perfToken++
    }

    private fun showSample(s: PerfSample) {
        perfStatus.text =
            if (s.running) "Live \u00b7 updates every couple of seconds" else "Roblox isn't running. Launch it, then check back."
        fpsText.text = s.fps?.let { it.roundToInt().toString() } ?: "\u2014"
        val ft = s.frameTimesMs
        frameTimeText.text = if (ft.isEmpty()) {
            "No frame data"
        } else {
            "avg ${"%.1f".format(ft.average())} ms \u00b7 worst ${"%.0f".format(ft.maxOrNull() ?: 0f)} ms"
        }
        frameGraph.setData(ft)
        cpuText.text = s.cpuApp?.let { "%.0f%%".format(it) } ?: "\u2014"
        gpuText.text = s.gpu?.let { "%.0f%%".format(it) } ?: "N/A"
        ramText.text = s.ramMb?.let { if (it >= 1024.0) "%.2f GB".format(it / 1024.0) else "%.0f MB".format(it) }
            ?: "\u2014"
        tempText.text = s.tempC?.let { "%.1f \u00b0C".format(it) } ?: "N/A"
    }

    private fun updateDeviceInfo() {
        val ver = robloxVersion()
        val last = prefs.getString("applied_version", null)
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val gb = mi.totalMem / 1073741824.0
        val alive = Shizuku.pingBinder()
        val ready = hasShizuku()
        val sb = StringBuilder()
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Memory: %.1f GB\n".format(gb))
        sb.append("Roblox: ${ver ?: "not installed"}\n")
        sb.append("Shizuku: ${if (alive) "running, ${shizukuMode()}" else "not running"}\n\n")
        sb.append(if (alive) "\u2713 Shizuku is running\n" else "\u2717 Shizuku isn't running\n")
        sb.append(if (ready) "\u2713 Permission granted\n" else "\u2717 Permission not granted\n")
        sb.append(if (ver != null) "\u2713 Roblox is installed\n" else "\u2717 Roblox isn't installed\n")
        sb.append(
            if (last != null && ver != null && last != ver) {
                "! Roblox changed since your last Apply ($last \u2192 $ver)\n"
            } else {
                "\u2713 Roblox hasn't changed since your last Apply\n"
            }
        )
        val suggestion = when {
            gb <= 4.0 -> "Competitive / FPS"
            gb <= 8.0 -> "Balanced"
            else -> "High graphics"
        }
        sb.append("\nA starting point for this device: $suggestion. It's a guess from the memory size, not a promise of better performance.")
        deviceInfoText.text = sb.toString()
    }

    // ---- Flag packs ----

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
     * Shows what the validator found in incoming flags. Clean imports (or ones with only minor
     * warnings, which go to the log) continue straight away; errors, cut-off text and duplicates
     * ask first.
     */
    private fun reviewIncoming(title: String, parsed: ParsedFlags, actionLabel: String, onAccept: () -> Unit) {
        logIssues(parsed.issues)
        if (parsed.flags.length() == 0) {
            dialog()
                .setTitle("Nothing to import")
                .setMessage(FlagValidator.summarize(parsed.issues))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val significant = parsed.issues.any {
            it.severity == Severity.ERROR || it.message.startsWith("JSON syntax") || it.message.contains("appears")
        }
        if (!significant) {
            val minor = parsed.issues.size
            if (minor > 0) toast("$minor warnings, see the Activity log.")
            onAccept()
            return
        }
        dialog()
            .setTitle(title)
            .setMessage("${parsed.flags.length()} usable flags found.\n\n" + FlagValidator.summarize(parsed.issues))
            .setPositiveButton(actionLabel) { _, _ -> onAccept() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPack(flags: JSONObject, suffix: String?) {
        val packs = loadPacks()
        val id = (packs.maxOfOrNull { it.id } ?: 0L) + 1L
        val name = if (suffix == null) "Pack $id" else "Pack $id ($suffix)"
        packs.add(Pack(id, name, true, flags))
        savePacks(packs)
        renderPacks()
        renderProfiles()
        toast("Imported ${flags.length()} flags as $name. Tap Apply to use it.")
        log(Severity.INFO, "Imported ${flags.length()} flags as $name.")
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
        reviewIncoming("Review clipboard", FlagValidator.parse(text), "Import") {
            addPack(FlagValidator.parse(text).flags, null)
        }
    }

    private fun importFromUrl() {
        val url = UrlImport.normalize(urlInput.text.toString())
        if (url == null) {
            toast("Enter a link that starts with https://")
            return
        }
        toast("Downloading from ${UrlImport.host(url)}\u2026")
        log(Severity.INFO, "Downloading $url")
        netExecutor.execute {
            val result = UrlImport.download(url)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val text = result.text
                if (text == null) {
                    log(Severity.ERROR, "Download failed: ${result.error}")
                    toast("Download failed: ${result.error}")
                    return@runOnUiThread
                }
                val host = UrlImport.host(url)
                val parsed = FlagValidator.parse(text)
                reviewIncoming("Review download from $host", parsed, "Import") {
                    addPack(parsed.flags, host)
                    urlInput.text.clear()
                }
            }
        }
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
        renderProfiles()
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
                renderProfiles()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Custom flags: paste, import, export ----

    private fun onAddCustomClicked() {
        val text = customInput.text.toString()
        reviewIncoming("Review flags", FlagValidator.parse(text), "Add") {
            val flags = FlagValidator.parse(text).flags
            confirmRestart("Add ${flags.length()} custom flag(s) to the flags file.") {
                customInput.text.clear()
                addCustomFlags(flags, it)
            }
        }
    }

    private fun addCustomFlags(obj: JSONObject, restart: Boolean) {
        val keys = jsonKeys(obj).toSet()
        val custom = HashSet(customKeys())
        custom.addAll(keys)
        prefs.edit().putStringSet("custom_keys", custom).apply()

        runTask {
            val existing = runShell("cat $flagsPath 2>/dev/null")
            val merged = readFlagsObject(existing)
            keys.forEach { k -> merged.put(k, obj.get(k)) }
            val sb = StringBuilder()
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Added ${keys.size} custom flag(s). " +
                if (restart) "Roblox was restarted.'\n" else "Restart Roblox to use them.'\n")
            commitFlags(existing, render(merged), "Added ${keys.size} custom flag(s)") + runShell(sb.toString())
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
                if (bytes.size > 1_000_000) null else String(bytes)
            }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            toast("Couldn't read that file (max 1 MB).")
            return
        }
        reviewIncoming("Review file", FlagValidator.parse(text), "Add") {
            val flags = FlagValidator.parse(text).flags
            confirmRestart("Add ${flags.length()} flag(s) from the file.") { addCustomFlags(flags, it) }
        }
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
        stopPerf()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        spinnerAnim?.cancel()
        animL?.cancel()
        animR?.cancel()
        executor.shutdownNow()
        netExecutor.shutdownNow()
        perfExecutor.shutdownNow()
        watchdog.shutdownNow()
    }
}
