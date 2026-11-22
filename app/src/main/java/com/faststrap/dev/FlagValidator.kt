package com.faststrap.dev

import org.json.JSONException
import org.json.JSONObject
import kotlin.math.floor

enum class Severity { ERROR, WARNING, INFO }

/** One finding from the validator. [conflict] marks clashes between configurations. */
class Issue(
    val severity: Severity,
    val key: String?,
    val message: String,
    val conflict: Boolean = false
) {
    fun line(): String {
        val mark = when (severity) {
            Severity.ERROR -> "\u2717"
            Severity.WARNING -> "!"
            Severity.INFO -> "\u2022"
        }
        return if (key == null) "$mark $message" else "$mark $key: $message"
    }
}

/** Flags read out of some text, plus everything the validator noticed while reading it. */
class ParsedFlags(val flags: JSONObject, val issues: List<Issue>)

object FlagValidator {
    private val nameRegex = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    private val pairRegex = Regex(
        "\"([A-Za-z0-9_]+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?\\d+(?:\\.\\d+)?)"
    )
    private val boolPrefixes = listOf("DFFlag", "FFlag", "SFFlag")
    private val intPrefixes = listOf("DFInt", "FInt", "SFInt", "DFLog", "FLog")
    private val stringPrefixes = listOf("DFString", "FString", "SFString")

    /** The value type implied by a flag's prefix, or null when the prefix isn't recognised. */
    fun expectedType(name: String): String? = when {
        boolPrefixes.any { name.startsWith(it) } -> "boolean"
        intPrefixes.any { name.startsWith(it) } -> "number"
        stringPrefixes.any { name.startsWith(it) } -> "string"
        else -> null
    }

    fun typeLabel(v: Any): String = when (v) {
        is Boolean -> "Boolean"
        is String -> "String"
        is Int, is Long -> "Integer"
        is Number -> "Decimal"
        else -> "Unknown"
    }

    private fun isWhole(v: Any): Boolean =
        v is Int || v is Long || (v is Double && !v.isInfinite() && v == floor(v))

    fun toMap(o: JSONObject): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            m[k] = o.get(k)
        }
        return m
    }

    /** Checks names, value types, prefixes and min/max pairs. */
    fun validateFlags(flags: Map<String, Any>): List<Issue> {
        val out = mutableListOf<Issue>()
        for ((k, v) in flags) {
            if (!nameRegex.matches(k)) {
                out.add(Issue(Severity.ERROR, k, "name may only use letters, digits and underscores"))
            }
            if (!(v is Boolean || v is Number || v is String)) {
                out.add(Issue(Severity.ERROR, k, "value must be true/false, a number or text"))
                continue
            }
            when (expectedType(k)) {
                null -> out.add(Issue(Severity.WARNING, k, "unrecognised prefix, Roblox may ignore it"))
                "boolean" -> if (v !is Boolean) {
                    out.add(Issue(Severity.WARNING, k, "expects true/false but has a ${typeLabel(v)} value"))
                }
                "number" -> if (!isWhole(v)) {
                    out.add(Issue(Severity.WARNING, k, "expects a whole number but has a ${typeLabel(v)} value"))
                }
                "string" -> if (v !is String) {
                    out.add(Issue(Severity.WARNING, k, "expects text but has a ${typeLabel(v)} value"))
                }
            }
        }
        for ((k, v) in flags) {
            val i = k.indexOf("Min")
            if (i < 0 || v !is Number) continue
            val maxKey = k.substring(0, i) + "Max" + k.substring(i + 3)
            val mv = flags[maxKey]
            if (mv is Number && v.toDouble() > mv.toDouble()) {
                out.add(Issue(Severity.WARNING, k, "is larger than $maxKey", true))
            }
        }
        return out
    }

    /**
     * Finds flags set to different values by different sources. [sources] runs from lowest to
     * highest priority, so the last one listed wins.
     */
    fun findConflicts(sources: List<Pair<String, Map<String, Any>>>): List<Issue> {
        val seen = LinkedHashMap<String, MutableList<Pair<String, Any>>>()
        for ((name, flags) in sources) {
            for ((k, v) in flags) seen.getOrPut(k) { mutableListOf() }.add(Pair(name, v))
        }
        val out = mutableListOf<Issue>()
        for ((k, list) in seen) {
            if (list.map { it.second.toString() }.toSet().size > 1) {
                val detail = list.joinToString(", ") { "${it.first} = ${it.second}" }
                out.add(Issue(Severity.WARNING, k, "$detail. ${list.last().first} wins.", true))
            }
        }
        return out
    }

    /**
     * Reads flags out of pasted, imported or downloaded text. Accepts a full JSON object or bare
     * `"Name": value,` lines. Broken JSON (for example a cut-off copy) is recovered entry by entry.
     */
    fun parse(raw: String): ParsedFlags {
        val text = raw.trim()
        val out = JSONObject()
        if (text.isEmpty()) {
            return ParsedFlags(out, listOf(Issue(Severity.ERROR, null, "there is nothing to import")))
        }
        val issues = mutableListOf<Issue>()

        val counts = LinkedHashMap<String, Int>()
        pairRegex.findAll(text).forEach { m ->
            val k = m.groupValues[1]
            counts[k] = (counts[k] ?: 0) + 1
        }
        for ((k, n) in counts) {
            if (n > 1) issues.add(Issue(Severity.WARNING, k, "appears $n times, the last value is used"))
        }

        val candidate = if (text.startsWith("{")) text else "{" + text.trimEnd(',') + "}"
        var obj: JSONObject? = null
        var syntaxError: String? = null
        try {
            obj = JSONObject(candidate)
        } catch (e: JSONException) {
            syntaxError = e.message
        }

        if (obj != null) {
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = obj.get(k)
                when {
                    !nameRegex.matches(k) -> issues.add(Issue(Severity.ERROR, k, "invalid name, skipped"))
                    !(v is Boolean || v is Number || v is String) ->
                        issues.add(Issue(Severity.ERROR, k, "unsupported value (nested or empty), skipped"))
                    else -> out.put(k, v)
                }
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
            issues.add(
                0,
                Issue(
                    Severity.WARNING, null,
                    "JSON syntax error (${syntaxError ?: "unknown"}). Recovered ${out.length()} complete " +
                        "entries, the text may be cut off."
                )
            )
        }
        issues.addAll(validateFlags(toMap(out)))
        return ParsedFlags(out, issues)
    }

    /** Errors first, capped so a dialog stays readable. */
    fun summarize(issues: List<Issue>, max: Int = 25): String {
        val sorted = issues.sortedBy { it.severity.ordinal }
        val lines = sorted.take(max).map { it.line() }
        val extra = sorted.size - lines.size
        return lines.joinToString("\n") + if (extra > 0) "\n\u2026and $extra more" else ""
    }
}
