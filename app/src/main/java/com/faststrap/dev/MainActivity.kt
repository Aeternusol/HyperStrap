package com.faststrap.dev

import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class Palette(
    val bg: Int, val c1: Int, val c2: Int, val c3: Int,
    val accent: Int, val seg: Int, val stroke: Int
)

private fun pal(bg: String, c1: String, c2: String, c3: String, accent: String, seg: String, stroke: String) =
    Palette(
        Color.parseColor(bg), Color.parseColor(c1), Color.parseColor(c2), Color.parseColor(c3),
        Color.parseColor(accent), Color.parseColor(seg), Color.parseColor(stroke)
    )

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

    private val palettes = listOf(
        pal("#FFFFFF", "#ECEFFE", "#E5F1F8", "#FBE9EE", "#111111", "#AAB6F5", "#B9C1F2"),
        pal("#F7FBF8", "#E3F4E8", "#E1F1F0", "#F3F1DD", "#1F3D2B", "#8CCB9B", "#A9D8B5"),
        pal("#FFFBF8", "#FFE9DC", "#FFF1D9", "#FBE3E8", "#4A2511", "#F4A97F", "#F2BFA0"),
        pal("#F7FAFD", "#DEEBFB", "#DDF3F6", "#E7E6FA", "#0F2A4A", "#7FB0EA", "#A5C6EE"),
        pal("#FFF9FB", "#FCE4EC", "#F5E3F6", "#FFE9E3", "#4A1230", "#F08FB3", "#F2B3CB"),
        pal("#F6F7F9", "#E8EAEE", "#EDEFF2", "#E3E7EC", "#1F2937", "#8E9AAF", "#B4BCC9")
    )
    private var themeIndex = 0

    private lateinit var prefs: SharedPreferences
    private lateinit var root: View
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var countText: TextView
    private lateinit var outputText: TextView
    private lateinit var packEmpty: TextView
    private lateinit var packList: LinearLayout
    private lateinit var requestButton: MaterialButton
    private lateinit var applyButton: MaterialButton
    private lateinit var viewButton: MaterialButton
    private lateinit var removeButton: MaterialButton
    private lateinit var addCustomButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var importPackButton: MaterialButton
    private lateinit var customInput: EditText
    private lateinit var refreshButton: MaterialCardView
    private lateinit var statusCard: MaterialCardView
    private lateinit var heroCard: MaterialCardView
    private lateinit var perfCard: MaterialCardView
    private lateinit var gfxCard: MaterialCardView
    private lateinit var packsCard: MaterialCardView
    private lateinit var customCard: MaterialCardView
    private lateinit var swatchRow: LinearLayout
    private lateinit var segRow: LinearLayout
    private lateinit var cbFps: CompoundButton
    private lateinit var cbFpsCounter: CompoundButton
    private lateinit var cbSky: CompoundButton
    private lateinit var cbGrass: CompoundButton
    private lateinit var cbShadows: CompoundButton
    private lateinit var cbTextures: CompoundButton
    private lateinit var switches: List<CompoundButton>
    private lateinit var tweaks: List<Tweak>
    private val segments = mutableListOf<MaterialCardView>()

    // Commands run one at a time off the main thread; the watchdog kills hung processes.
    private val executor = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private var busy = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> updateStatus() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { updateStatus() }
    private val binderDead = Shizuku.OnBinderDeadListener { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("faststrap", MODE_PRIVATE)
        themeIndex = prefs.getInt("theme", 0).coerceIn(0, palettes.size - 1)

        root = findViewById(R.id.root)
        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)
        countText = findViewById(R.id.countText)
        outputText = findViewById(R.id.outputText)
        packEmpty = findViewById(R.id.packEmpty)
        packList = findViewById(R.id.packList)
        requestButton = findViewById(R.id.requestButton)
        applyButton = findViewById(R.id.applyButton)
        viewButton = findViewById(R.id.viewButton)
        removeButton = findViewById(R.id.removeButton)
        addCustomButton = findViewById(R.id.addCustomButton)
        importButton = findViewById(R.id.importButton)
        exportButton = findViewById(R.id.exportButton)
        importPackButton = findViewById(R.id.importPackButton)
        customInput = findViewById(R.id.customInput)
        refreshButton = findViewById(R.id.refreshButton)
        statusCard = findViewById(R.id.statusCard)
        heroCard = findViewById(R.id.heroCard)
        perfCard = findViewById(R.id.perfCard)
        gfxCard = findViewById(R.id.gfxCard)
        packsCard = findViewById(R.id.packsCard)
        customCard = findViewById(R.id.customCard)
        swatchRow = findViewById(R.id.swatchRow)
        segRow = findViewById(R.id.segRow)
        cbFps = findViewById(R.id.cbFps)
        cbFpsCounter = findViewById(R.id.cbFpsCounter)
        cbSky = findViewById(R.id.cbSky)
        cbGrass = findViewById(R.id.cbGrass)
        cbShadows = findViewById(R.id.cbShadows)
        cbTextures = findViewById(R.id.cbTextures)

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
            confirmRestart("Remove all Stargazer flags, including packs and custom flags added here. Flags you added by hand outside the app are kept.") {
                removeFlags(it)
            }
        }
        addCustomButton.setOnClickListener { onAddCustomClicked() }
        importButton.setOnClickListener { startImport() }
        exportButton.setOnClickListener { startExport() }
        importPackButton.setOnClickListener { importPackFromClipboard() }
        refreshButton.setOnClickListener { updateStatus() }

        applyTheme()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun buildSegments() {
        val d = resources.displayMetrics.density
        for (i in 0 until 6) {
            val s = MaterialCardView(this)
            val lp = LinearLayout.LayoutParams(0, (30 * d).toInt(), 1f)
            if (i < 5) lp.marginEnd = (6 * d).toInt()
            s.layoutParams = lp
            s.radius = 10 * d
            s.cardElevation = 0f
            s.strokeWidth = (1 * d).toInt().coerceAtLeast(1)
            segRow.addView(s)
            segments.add(s)
        }
    }

    private fun buildSwatches() {
        swatchRow.removeAllViews()
        val d = resources.displayMetrics.density
        val size = (40 * d).toInt()
        palettes.forEachIndexed { i, p ->
            val s = MaterialCardView(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (10 * d).toInt()
            s.layoutParams = lp
            s.radius = size / 2f
            s.cardElevation = 0f
            s.setCardBackgroundColor(p.seg)
            s.strokeWidth = if (i == themeIndex) (3 * d).toInt() else 0
            s.setStrokeColor(palettes[themeIndex].accent)
            s.contentDescription = "Theme ${i + 1}" + if (i == themeIndex) ", selected" else ""
            s.setOnClickListener {
                themeIndex = i
                prefs.edit().putInt("theme", i).apply()
                applyTheme()
            }
            swatchRow.addView(s)
        }
    }

    private fun applyTheme() {
        val p = palettes[themeIndex]
        root.setBackgroundColor(p.bg)
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        heroCard.setCardBackgroundColor(p.c1)
        perfCard.setCardBackgroundColor(p.c2)
        gfxCard.setCardBackgroundColor(p.c3)
        packsCard.setCardBackgroundColor(p.c2)
        customCard.setCardBackgroundColor(p.c1)
        refreshButton.setCardBackgroundColor(p.accent)
        statusCard.setCardBackgroundColor(p.accent)
        val tint = ColorStateList.valueOf(p.accent)
        requestButton.backgroundTintList = tint
        applyButton.backgroundTintList = tint
        addCustomButton.backgroundTintList = tint
        importPackButton.backgroundTintList = tint
        removeButton.setStrokeColor(tint)
        removeButton.setTextColor(p.accent)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val track = ColorStateList(states, intArrayOf(p.accent, Color.parseColor("#D1D5DB")))
        switches.forEach { (it as? MaterialSwitch)?.trackTintList = track }
        segments.forEach { it.setStrokeColor(p.stroke) }
        buildSwatches()
        renderPacks()
        updateCount()
    }

    private fun updateCount() {
        val p = palettes[themeIndex]
        val n = switches.count { it.isChecked }
        countText.text = n.toString()
        segments.forEachIndexed { i, s ->
            s.setCardBackgroundColor(if (i < n) p.seg else Color.TRANSPARENT)
        }
    }

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
        hintText.text = when {
            !alive -> "Open the Shizuku app and tap Start."
            !ready -> "Allow Stargazer to use Shizuku."
            !robloxInstalled() -> "Roblox ($robloxPkg) isn't installed."
            else -> ""
        }
        hintText.visibility = if (hintText.text.isEmpty()) View.GONE else View.VISIBLE
        requestButton.visibility = if (alive && !ready) View.VISIBLE else View.GONE
        val enabled = ready && !busy
        listOf(applyButton, viewButton, removeButton, addCustomButton, importButton, exportButton)
            .forEach {
                it.isEnabled = enabled
                it.alpha = if (enabled) 1f else 0.4f
            }
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

    /** Runs [task] off the main thread, disables the buttons meanwhile, then shows its result. */
    private fun runTask(task: () -> String) {
        if (busy) return
        busy = true
        outputText.text = "Running..."
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
                outputText.text = result
                updateStatus()
            }
        }
    }

    private fun confirmRestart(message: String, action: (restart: Boolean) -> Unit) {
        MaterialAlertDialogBuilder(this)
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

    private fun readFlagsObject(existing: String): JSONObject =
        try {
            if (existing.isBlank()) JSONObject() else JSONObject(existing)
        } catch (e: JSONException) {
            JSONObject()
        }

    private fun render(obj: JSONObject): String? = if (obj.length() == 0) null else obj.toString(2)

    /**
     * Builds the flags file content from the existing file. Order, lowest priority first:
     * enabled packs, then custom flags (left in place), then tweaks.
     * When [removing], every key Stargazer manages (tweaks, packs, custom, stale) is deleted instead.
     * Keys the user added by hand are never touched. Returns null when nothing is left.
     */
    private fun mergeJson(
        existing: String,
        selected: List<Tweak>,
        packs: List<Pack>,
        custom: Set<String>,
        stale: Set<String>,
        removing: Boolean
    ): String? {
        val obj = readFlagsObject(existing)
        managedKeys().forEach { obj.remove(it) }
        stale.forEach { if (it !in custom || removing) obj.remove(it) }
        packs.forEach { p -> p.keys().forEach { k -> if (k !in custom || removing) obj.remove(k) } }
        if (removing) {
            custom.forEach { obj.remove(it) }
        } else {
            packs.filter { it.on }.forEach { p ->
                p.keys().forEach { k -> if (k !in custom) obj.put(k, p.flags.get(k)) }
            }
            selected.forEach { t -> t.flags.forEach { (k, v) -> obj.put(k, v) } }
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
        prefs.edit().remove("stale_keys").apply()
        val activePacks = packs.count { it.on }

        runTask {
            val json = mergeJson(runShell("cat $flagsPath 2>/dev/null"), selected, packs, custom, stale, false)
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
        runTask {
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

        switches.forEach { it.isChecked = false }
        val editor = prefs.edit()
        tweaks.forEach { editor.remove(it.prefKey) }
        editor.remove("custom_keys").remove("stale_keys").apply()
        // Packs stay saved but are switched off, so the next Apply won't bring them back.
        packs.forEach { it.on = false }
        savePacks(packs)
        renderPacks()

        runTask {
            val json = mergeJson(runShell("cat $flagsPath 2>/dev/null"), emptyList(), packs, custom, stale, true)
            val sb = StringBuilder()
            sb.append(fpsOffScript())
            if (restart) sb.append("am force-stop $robloxPkg\n")
            sb.append("echo 'Stargazer flags removed. " +
                if (restart) "Roblox was restarted.'" else "Restart Roblox to finish.'")
            writeFlagsFile(json) + runShell(sb.toString())
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
        val p = palettes[themeIndex]
        val packs = loadPacks()
        packEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        packList.removeAllViews()
        val d = resources.displayMetrics.density
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val track = ColorStateList(states, intArrayOf(p.accent, Color.parseColor("#D1D5DB")))
        packs.forEach { pack ->
            val sw = MaterialSwitch(this)
            sw.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            sw.text = "${pack.name}  \u00b7  ${pack.flags.length()} flags"
            sw.textSize = 15f
            sw.setTextColor(Color.parseColor("#111111"))
            sw.setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            sw.trackTintList = track
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
        MaterialAlertDialogBuilder(this)
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
        val keys = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
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
        executor.shutdownNow()
        watchdog.shutdownNow()
    }
}
