package com.faststrap.dev

/** One reading of Roblox's performance. Anything that couldn't be read is null. */
class PerfSample(
    val running: Boolean,
    val fps: Double?,
    val frameTimesMs: List<Float>,
    val cpuApp: Double?,
    val cpuTotal: Double?,
    val ramMb: Double?,
    val gpu: Double?,
    val tempC: Double?,
    val battery: Int?
)

/**
 * Reads performance numbers through the shell. Everything here is best effort: FPS comes from
 * SurfaceFlinger's frame timestamps for Roblox's layer, CPU from dumpsys cpuinfo, memory from
 * dumpsys meminfo, GPU load from vendor files that only some devices expose, and temperature
 * from the battery sensor.
 */
object PerfSampler {
    private val cpuRegex = Regex("""^\s*(\d+(?:\.\d+)?)%\s+(.+)""")
    private val memPss = Regex("""TOTAL PSS:\s+(\d+)""")
    private val memTotal = Regex("""^\s*TOTAL\s+(\d+)""")
    private val tempRegex = Regex("""temperature:\s*(-?\d+)""")
    private val levelRegex = Regex("""level:\s*(\d+)""")
    private val numRegex = Regex("""\d+(?:\.\d+)?""")

    fun script(pkg: String): String = listOf(
        "echo '##CPU'",
        "dumpsys cpuinfo 2>/dev/null | grep -E '$pkg|TOTAL'",
        "echo '##MEM'",
        "dumpsys meminfo $pkg 2>/dev/null | grep -E 'TOTAL'",
        "echo '##BAT'",
        "dumpsys battery 2>/dev/null | grep -E 'temperature|level'",
        "echo '##GPU'",
        "for f in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpubusy " +
            "/sys/class/misc/mali0/device/utilisation /sys/devices/platform/mali/utilisation; do " +
            "if [ -r \"\$f\" ]; then cat \"\$f\"; break; fi; done",
        "echo '##FPS'",
        "L=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -i 'SurfaceView.*$pkg' | head -n 1)",
        "if [ -z \"\$L\" ]; then L=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -i '$pkg' | head -n 1); fi",
        "if [ -n \"\$L\" ]; then dumpsys SurfaceFlinger --latency \"\$L\" 2>/dev/null; fi",
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

    fun parse(out: String, pkg: String): PerfSample {
        val sec = sections(out)

        var cpuApp: Double? = null
        var cpuTotal: Double? = null
        for (l in sec["CPU"].orEmpty()) {
            val m = cpuRegex.find(l) ?: continue
            val pct = m.groupValues[1].toDouble()
            val rest = m.groupValues[2]
            if (rest.startsWith("TOTAL")) {
                cpuTotal = pct
            } else if (rest.contains(pkg)) {
                cpuApp = (cpuApp ?: 0.0) + pct
            }
        }

        var ramMb: Double? = null
        for (l in sec["MEM"].orEmpty()) {
            val kb = memPss.find(l)?.groupValues?.get(1) ?: memTotal.find(l)?.groupValues?.get(1)
            if (kb != null) {
                ramMb = kb.toDouble() / 1024.0
                break
            }
        }

        var tempC: Double? = null
        var battery: Int? = null
        for (l in sec["BAT"].orEmpty()) {
            tempRegex.find(l)?.let { tempC = it.groupValues[1].toDouble() / 10.0 }
            levelRegex.find(l)?.let { battery = it.groupValues[1].toInt() }
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

        // SurfaceFlinger prints the refresh period, then one row per frame: desired present,
        // actual present and ready time in nanoseconds.
        val frames = mutableListOf<Long>()
        for (l in sec["FPS"].orEmpty().filter { it.isNotBlank() }.drop(1)) {
            val p = l.trim().split(Regex("\\s+"))
            if (p.size < 3) continue
            val actual = p[1].toLongOrNull() ?: continue
            if (actual <= 0L || actual == Long.MAX_VALUE) continue
            frames.add(actual)
        }
        var fps: Double? = null
        var times: List<Float> = emptyList()
        if (frames.size >= 3) {
            val deltas = ArrayList<Float>()
            for (i in 1 until frames.size) {
                val ms = (frames[i] - frames[i - 1]) / 1_000_000f
                if (ms > 0f && ms < 1000f) deltas.add(ms)
            }
            times = deltas.takeLast(90)
            val last = frames.last()
            val window = frames.filter { it >= last - 1_000_000_000L }
            if (window.size >= 2) {
                val span = (window.last() - window.first()) / 1e9
                if (span > 0.0) fps = (window.size - 1) / span
            }
        }

        val running = fps != null || cpuApp != null || ramMb != null
        return PerfSample(running, fps, times, cpuApp, cpuTotal, ramMb, gpu, tempC, battery)
    }
}
