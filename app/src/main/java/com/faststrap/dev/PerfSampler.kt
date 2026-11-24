package com.faststrap.dev

/** One reading of Roblox's performance. Anything that couldn't be read is null. */
data class PerfSample(
    val running: Boolean,
    val fps: Double?,
    val frameTimesMs: List<Float>,
    val cpuApp: Double?,
    val cpuTotal: Double?,
    val ramMb: Double?,
    val gpu: Double?,
    val tempC: Double?,
    val battery: Int?,
    /** How many Roblox graphics layers SurfaceFlinger listed (0 means FPS can't be read). */
    val layerCount: Int = 0,
    val newestFrameNs: Long? = null,
    /** True when no new frame was presented since the previous reading. */
    val staticScreen: Boolean = false,
    val procJiffies: Long = -1L,
    val totalJiffies: Long = -1L,
    val idleJiffies: Long = -1L
)

/**
 * Reads performance numbers through the shell, as quickly as possible: everything comes from
 * /proc and /sys files except frame timing, which comes from SurfaceFlinger's per-layer
 * timestamps. Everything is best effort and varies by device and Android version.
 *
 * CPU use needs two readings (it is the change in CPU time between them), so it appears from
 * the second reading on; [PerfTracker] does that arithmetic.
 */
object PerfSampler {
    private val memPss = Regex("""^Pss:\s+(\d+)""")
    private val memRss = Regex("""VmRSS:\s+(\d+)""")
    private val tempRegex = Regex("""temperature:\s*(-?\d+)""")
    private val levelRegex = Regex("""level:\s*(\d+)""")
    private val numRegex = Regex("""\d+(?:\.\d+)?""")
    private val spaces = Regex("\\s+")

    fun script(pkg: String): String = listOf(
        "echo '##PID'",
        "PID=\$(pidof $pkg 2>/dev/null | cut -d' ' -f1)",
        "if [ -z \"\$PID\" ]; then PID=\$(ps -A 2>/dev/null | grep -F $pkg | head -n 1 | tr -s ' ' | cut -d' ' -f2); fi",
        "echo \"\$PID\"",
        "echo '##STAT'",
        "head -n 1 /proc/stat",
        "echo '##PROC'",
        "if [ -n \"\$PID\" ]; then cat /proc/\$PID/stat 2>/dev/null; fi",
        "echo '##MEM'",
        "if [ -n \"\$PID\" ]; then",
        "if [ -r /proc/\$PID/smaps_rollup ]; then grep '^Pss:' /proc/\$PID/smaps_rollup 2>/dev/null; fi",
        "grep '^VmRSS' /proc/\$PID/status 2>/dev/null",
        "fi",
        "echo '##BAT'",
        "T=\$(cat /sys/class/power_supply/battery/temp 2>/dev/null)",
        "if [ -n \"\$T\" ]; then echo \"temperature: \$T\"; echo \"level: \$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)\"; " +
            "else dumpsys battery 2>/dev/null | grep -E 'temperature|level'; fi",
        "echo '##GPU'",
        "for f in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpubusy " +
            "/sys/class/misc/mali0/device/utilisation /sys/devices/platform/mali/utilisation; do " +
            "if [ -r \"\$f\" ]; then cat \"\$f\"; break; fi; done",
        "echo '##FPS'",
        "dumpsys SurfaceFlinger --list 2>/dev/null | grep -i roblox | head -n 4 | while IFS= read -r L; do " +
            "echo \"@@\$L\"; dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null; done",
        "echo '##END'"
    ).joinToString("\n")

    private fun sections(out: String): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (line in out.lineSequence()) {
            if (line.startsWith("##")) {
                val list = mutableListOf<String>()
                map[line.substring(2).trim()] = list
                cur = list
            } else {
                cur?.add(line)
            }
        }
        return map
    }

    /** Frame presentation times (ns) for each layer, in the order the layers were listed. */
    private fun frameGroups(lines: List<String>): List<List<Long>> {
        val groups = mutableListOf<MutableList<Long>>()
        var cur: MutableList<Long>? = null
        var skippedHeader = false
        for (raw in lines) {
            val l = raw.trim()
            if (l.startsWith("@@")) {
                val g = mutableListOf<Long>()
                groups.add(g)
                cur = g
                skippedHeader = false
                continue
            }
            if (l.isEmpty() || cur == null) continue
            if (!skippedHeader) {
                // The first line of each layer is the display's refresh period.
                skippedHeader = true
                continue
            }
            val p = l.split(spaces)
            if (p.size < 3) continue
            val actual = p[1].toLongOrNull() ?: continue
            if (actual <= 0L || actual == Long.MAX_VALUE) continue
            cur.add(actual)
        }
        return groups
    }

    fun parse(out: String, pkg: String): PerfSample {
        val sec = sections(out)

        val pidText = sec["PID"].orEmpty().firstOrNull { it.isNotBlank() }?.trim()
        val pidFound = pidText?.toIntOrNull() != null

        var total = -1L
        var idle = -1L
        val statLine = sec["STAT"].orEmpty().firstOrNull { it.startsWith("cpu") }
        if (statLine != null) {
            val nums = statLine.trim().split(spaces).drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size >= 8) {
                total = nums.take(8).sum()
                idle = nums[3] + nums[4]
            }
        }

        var proc = -1L
        val procText = sec["PROC"].orEmpty().firstOrNull { it.isNotBlank() }
        if (procText != null) {
            val close = procText.lastIndexOf(')')
            if (close > 0 && close + 2 < procText.length) {
                val rest = procText.substring(close + 2).trim().split(spaces)
                val ut = rest.getOrNull(11)?.toLongOrNull()
                val st = rest.getOrNull(12)?.toLongOrNull()
                if (ut != null && st != null) proc = ut + st
            }
        }

        var pssKb: String? = null
        var rssKb: String? = null
        for (l in sec["MEM"].orEmpty()) {
            memPss.find(l)?.let { pssKb = it.groupValues[1] }
            memRss.find(l)?.let { rssKb = it.groupValues[1] }
        }
        val memKb = pssKb ?: rssKb
        val ramMb = memKb?.toDoubleOrNull()?.let { it / 1024.0 }

        var tempC: Double? = null
        var battery: Int? = null
        for (l in sec["BAT"].orEmpty()) {
            tempRegex.find(l)?.let { tempC = it.groupValues[1].toDouble() / 10.0 }
            levelRegex.find(l)?.let { battery = it.groupValues[1].toIntOrNull() }
        }

        var gpu: Double? = null
        val gpuLine = sec["GPU"].orEmpty().firstOrNull { it.isNotBlank() }
        if (gpuLine != null) {
            val nums = numRegex.findAll(gpuLine).map { it.value.toDouble() }.toList()
            gpu = when {
                gpuLine.contains('%') && nums.isNotEmpty() -> nums[0]
                nums.size >= 2 && nums[1] > 0.0 -> nums[0] * 100.0 / nums[1]
                nums.size == 1 && nums[0] <= 100.0 -> nums[0]
                nums.size == 1 -> nums[0] * 100.0 / 255.0
                else -> null
            }
            gpu = gpu?.coerceIn(0.0, 100.0)
        }

        // Use the layer that presented a frame most recently; that is the game's own surface.
        val groups = frameGroups(sec["FPS"].orEmpty())
        var frames: List<Long> = emptyList()
        for (g in groups) {
            if (g.size >= 2 && (frames.isEmpty() || g.last() > frames.last())) frames = g.sorted()
        }
        var fps: Double? = null
        var times: List<Float> = emptyList()
        if (frames.size >= 2) {
            val deltas = ArrayList<Float>()
            for (i in 1 until frames.size) {
                val ms = (frames[i] - frames[i - 1]) / 1_000_000f
                if (ms > 0f && ms < 2000f) deltas.add(ms)
            }
            times = deltas.takeLast(90)
            val last = frames.last()
            var window = frames.filter { it >= last - 1_000_000_000L }
            // A quiet screen presents few frames; fall back to the most recent ones.
            if (window.size < 2) window = frames.takeLast(minOf(frames.size, 10))
            val span = (window.last() - window.first()) / 1e9
            if (window.size >= 2 && span > 0.0) fps = (window.size - 1) / span
        }

        return PerfSample(
            running = pidFound || fps != null,
            fps = fps,
            frameTimesMs = times,
            cpuApp = null,
            cpuTotal = null,
            ramMb = ramMb,
            gpu = gpu,
            tempC = tempC,
            battery = battery,
            layerCount = groups.size,
            newestFrameNs = frames.lastOrNull(),
            staticScreen = false,
            procJiffies = proc,
            totalJiffies = total,
            idleJiffies = idle
        )
    }
}

/** Turns raw readings into CPU percentages by comparing each reading with the previous one. */
class PerfTracker {
    private var prev: PerfSample? = null

    fun update(raw: PerfSample): PerfSample {
        val p = prev
        var cpuApp: Double? = null
        var cpuTotal: Double? = null
        if (p != null && raw.totalJiffies > 0 && p.totalJiffies > 0) {
            val dTotal = raw.totalJiffies - p.totalJiffies
            if (dTotal > 0) {
                if (raw.procJiffies >= 0 && p.procJiffies >= 0 && raw.procJiffies >= p.procJiffies) {
                    // Share of the whole device's CPU capacity (all cores together).
                    cpuApp = (raw.procJiffies - p.procJiffies).toDouble() * 100.0 / dTotal
                }
                val dIdle = raw.idleJiffies - p.idleJiffies
                cpuTotal = ((dTotal - dIdle).toDouble() * 100.0 / dTotal).coerceIn(0.0, 100.0)
            }
        }
        var fps = raw.fps
        var quiet = false
        val newest = raw.newestFrameNs
        if (p != null && newest != null && newest == p.newestFrameNs) {
            fps = 0.0
            quiet = true
        }
        val out = raw.copy(fps = fps, staticScreen = quiet, cpuApp = cpuApp, cpuTotal = cpuTotal)
        prev = out
        return out
    }
}
