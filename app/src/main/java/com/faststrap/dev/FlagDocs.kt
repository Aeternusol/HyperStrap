package com.faststrap.dev

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** What is known about one flag: any of these can be missing. */
class DocEntry(
    val name: String,
    val description: String?,
    val defaultValue: String?,
    val type: String?
)

class DocImport(val added: Int, val updated: Int, val skipped: Int, val error: String?)

/**
 * A local, searchable reference of flag descriptions and defaults, plus the user's own notes.
 * Stargazer ships with no reference data: it is filled by importing JSON from a source the user
 * trusts, so nothing in it is invented by the app.
 *
 * Accepted JSON: an object of flag name to either a description (text), a default (true/false or
 * a number) or an object with description / default / type fields; or an array of objects with
 * name (or flag, or key) plus those same fields.
 */
class FlagDocs(private val file: File) {
    private val entries = LinkedHashMap<String, DocEntry>()
    private val notes = HashMap<String, String>()
    private var loaded = false
    private val nameRegex = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    private fun clean(s: String): String? = if (s.isEmpty()) null else s

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val e = root.optJSONObject("entries")
            if (e != null) {
                val it = e.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val o = e.optJSONObject(k) ?: continue
                    entries[k] = DocEntry(
                        k,
                        clean(o.optString("d", "")),
                        clean(o.optString("f", "")),
                        clean(o.optString("t", ""))
                    )
                }
            }
            val n = root.optJSONObject("notes")
            if (n != null) {
                val it = n.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    notes[k] = n.optString(k, "")
                }
            }
        } catch (ex: Exception) {
            // Unreadable file: start empty.
        }
    }

    private fun save() {
        val e = JSONObject()
        for ((k, v) in entries) {
            val o = JSONObject()
            v.description?.let { o.put("d", it) }
            v.defaultValue?.let { o.put("f", it) }
            v.type?.let { o.put("t", it) }
            e.put(k, o)
        }
        val n = JSONObject()
        for ((k, v) in notes) n.put(k, v)
        file.writeText(JSONObject().put("entries", e).put("notes", n).toString())
    }

    private fun first(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val s = o.get(k).toString()
                if (s.isNotEmpty()) return s
            }
        }
        return null
    }

    /** 1 = added, 2 = updated, 0 = skipped. */
    private fun putEntry(name: String, v: Any?): Int {
        if (!nameRegex.matches(name)) return 0
        var desc: String? = null
        var def: String? = null
        var type: String? = null
        when (v) {
            is JSONObject -> {
                desc = first(v, "description", "desc", "d", "summary")
                def = first(v, "default", "defaultValue", "def", "value")
                type = first(v, "type", "t")
            }
            is String -> desc = v
            is Boolean, is Number -> def = v.toString()
            else -> return 0
        }
        val existed = entries.containsKey(name)
        entries[name] = DocEntry(name, desc, def, type)
        return if (existed) 2 else 1
    }

    @Synchronized
    fun importText(raw: String): DocImport {
        ensureLoaded()
        if (raw.length > 5_000_000) return DocImport(0, 0, 0, "That is too large (5 MB at most).")
        val text = raw.trim()
        var added = 0
        var updated = 0
        var skipped = 0
        try {
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i)
                    if (o == null) {
                        skipped++
                        continue
                    }
                    val name = first(o, "name", "flag", "key") ?: ""
                    when (putEntry(name, o)) {
                        1 -> added++
                        2 -> updated++
                        else -> skipped++
                    }
                }
            } else {
                val obj = JSONObject(text)
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    when (putEntry(k, obj.get(k))) {
                        1 -> added++
                        2 -> updated++
                        else -> skipped++
                    }
                }
            }
        } catch (e: JSONException) {
            return DocImport(0, 0, 0, "That isn't valid JSON.")
        }
        if (added + updated == 0) return DocImport(0, 0, skipped, "No usable entries were found.")
        save()
        return DocImport(added, updated, skipped, null)
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return entries.size
    }

    @Synchronized
    fun get(name: String): DocEntry? {
        ensureLoaded()
        return entries[name]
    }

    @Synchronized
    fun note(name: String): String? {
        ensureLoaded()
        return notes[name]
    }

    @Synchronized
    fun setNote(name: String, text: String) {
        ensureLoaded()
        if (text.isBlank()) notes.remove(name) else notes[name] = text.trim()
        save()
    }

    /** Every flag that has reference data or a note, sorted by name. */
    @Synchronized
    fun names(): List<String> {
        ensureLoaded()
        return (entries.keys + notes.keys).toSortedSet().toList()
    }

    /** Removes the imported reference but keeps the user's own notes. */
    @Synchronized
    fun clearReference() {
        ensureLoaded()
        entries.clear()
        save()
    }
}
