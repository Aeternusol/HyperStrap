package com.faststrap.dev

import org.json.JSONObject
import java.io.File

/** One saved state of the flags file. */
class HistoryEntry(
    val id: Long,
    val label: String,
    val time: Long,
    val roblox: String?,
    val flags: JSONObject
)

/** Keeps the last [maxEntries] versions of the flags file, one small JSON file each. */
class FlagHistory(private val dir: File, private val maxEntries: Int = 40) {

    /** Newest first. */
    @Synchronized
    fun list(): List<HistoryEntry> {
        dir.mkdirs()
        val files = dir.listFiles { f -> f.name.startsWith("h_") && f.name.endsWith(".json") }
            ?: return emptyList()
        val out = mutableListOf<HistoryEntry>()
        for (f in files) {
            try {
                val o = JSONObject(f.readText())
                val roblox = o.optString("roblox", "")
                out.add(
                    HistoryEntry(
                        o.getLong("id"),
                        o.getString("label"),
                        o.getLong("time"),
                        if (roblox.isEmpty()) null else roblox,
                        o.getJSONObject("flags")
                    )
                )
            } catch (e: Exception) {
                // Skip unreadable entries.
            }
        }
        return out.sortedByDescending { it.time }
    }

    @Synchronized
    fun isEmpty(): Boolean = list().isEmpty()

    @Synchronized
    fun add(label: String, flags: JSONObject, roblox: String?): HistoryEntry {
        dir.mkdirs()
        var id = System.currentTimeMillis()
        while (File(dir, "h_$id.json").exists()) id++
        val o = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("time", id)
            .put("roblox", roblox ?: "")
            .put("flags", flags)
        File(dir, "h_$id.json").writeText(o.toString())
        val all = list()
        if (all.size > maxEntries) all.drop(maxEntries).forEach { delete(it.id) }
        return HistoryEntry(id, label, id, roblox, flags)
    }

    @Synchronized
    fun delete(id: Long) {
        File(dir, "h_$id.json").delete()
    }
}
