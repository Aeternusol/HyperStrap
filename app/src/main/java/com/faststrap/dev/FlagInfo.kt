package com.faststrap.dev

/** Helpers for the flag explorer: names, categories and sort choices. */
object FlagInfo {
    val sources = listOf("All sources", "Tweak", "Pack", "Custom", "Edited", "Other")
    val categories = listOf(
        "All categories", "Performance", "Graphics", "Network", "Debug",
        "Audio", "Camera", "UI / Input", "Other"
    )
    val sorts = listOf("Name A\u2013Z", "Name Z\u2013A", "Source", "Value")

    private val rules = listOf(
        "Network" to listOf("net", "http", "replic", "ping", "server", "packet", "latency", "bandwidth"),
        "Audio" to listOf("audio", "sound", "music", "voice"),
        "Camera" to listOf("camera", "zoom"),
        "Performance" to listOf("fps", "frame", "scheduler", "task", "thread", "cpu", "gpu", "memory", "perf"),
        "Graphics" to listOf(
            "texture", "shadow", "grass", "sky", "light", "render", "graphic", "quality", "voxel",
            "mesh", "material", "particle", "fog", "water", "terrain", "bloom", "post"
        ),
        "UI / Input" to listOf("gui", "ui", "input", "touch", "menu", "button", "keyboard", "chat"),
        "Debug" to listOf("debug", "log", "telemetry", "analytics")
    )

    /** A guess based only on words in the flag's name. Roblox doesn't publish categories. */
    fun categoryOf(name: String): String {
        val n = name.lowercase()
        for ((cat, words) in rules) {
            if (words.any { n.contains(it) }) return cat
        }
        return "Other"
    }
}
