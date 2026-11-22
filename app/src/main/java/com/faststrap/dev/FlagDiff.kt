package com.faststrap.dev

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import org.json.JSONObject

class DiffResult(
    val added: List<Pair<String, Any>>,
    val removed: List<Pair<String, Any>>,
    val changed: List<Triple<String, Any, Any>>
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

object FlagDiff {

    /** What changed going from [old] to [new]. */
    fun diff(old: JSONObject, new: JSONObject): DiffResult {
        val a = FlagValidator.toMap(old)
        val b = FlagValidator.toMap(new)
        val added = b.keys.filter { it !in a }.sorted().map { Pair(it, b.getValue(it)) }
        val removed = a.keys.filter { it !in b }.sorted().map { Pair(it, a.getValue(it)) }
        val changed = a.keys
            .filter { it in b && a.getValue(it).toString() != b.getValue(it).toString() }
            .sorted()
            .map { Triple(it, a.getValue(it), b.getValue(it)) }
        return DiffResult(added, removed, changed)
    }

    private fun show(v: Any): String = if (v is String) "\"$v\"" else v.toString()

    private fun line(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text).append('\n')
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Colored text: green for added, red for removed, amber for changed. */
    fun format(d: DiffResult, maxLines: Int = 200): CharSequence {
        if (d.isEmpty) return "No differences."
        val green = Color.parseColor("#7DDB8E")
        val red = Color.parseColor("#FF8A80")
        val amber = Color.parseColor("#FFCB6B")
        val plain = Color.parseColor("#E3E3E3")
        val sb = SpannableStringBuilder()
        line(sb, "${d.added.size} added, ${d.removed.size} removed, ${d.changed.size} changed\n", plain)
        var used = 0
        for ((k, v) in d.added) {
            if (used++ >= maxLines) break
            line(sb, "+ $k = ${show(v)}", green)
        }
        for ((k, v) in d.removed) {
            if (used++ >= maxLines) break
            line(sb, "\u2212 $k = ${show(v)}", red)
        }
        for ((k, o, n) in d.changed) {
            if (used++ >= maxLines) break
            line(sb, "~ $k: ${show(o)} \u2192 ${show(n)}", amber)
        }
        val total = d.added.size + d.removed.size + d.changed.size
        if (total > maxLines) line(sb, "\u2026and ${total - maxLines} more", plain)
        return sb
    }
}
