package com.faststrap.dev

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogEntry(val time: Long, val severity: Severity, val text: String)

/** In-memory activity log shown on the Activity tab, newest entry first. */
class ActivityLog(private val max: Int = 300) {
    private val items = ArrayList<LogEntry>()

    fun add(severity: Severity, text: String) {
        items.add(LogEntry(System.currentTimeMillis(), severity, text))
        while (items.size > max) items.removeAt(0)
    }

    fun clear() = items.clear()

    /** Plain text, oldest first, for copying to the clipboard. */
    fun plain(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return items.joinToString("\n") {
            fmt.format(Date(it.time)) + " " + it.severity.name.padEnd(5) + " " + it.text
        }
    }

    fun render(): CharSequence {
        val sb = SpannableStringBuilder()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val muted = Color.parseColor("#9AA0A6")
        val primary = Color.parseColor("#E3E3E3")
        for (e in items.asReversed().take(150)) {
            var start = sb.length
            sb.append(fmt.format(Date(e.time))).append("  ")
            sb.setSpan(ForegroundColorSpan(muted), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = sb.length
            val tag = when (e.severity) {
                Severity.ERROR -> "ERROR  "
                Severity.WARNING -> "WARN   "
                Severity.INFO -> "INFO   "
            }
            val tagColor = when (e.severity) {
                Severity.ERROR -> Color.parseColor("#FF8A80")
                Severity.WARNING -> Color.parseColor("#FFCB6B")
                Severity.INFO -> Color.parseColor("#7DDB8E")
            }
            sb.append(tag)
            sb.setSpan(ForegroundColorSpan(tagColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = sb.length
            sb.append(e.text).append('\n')
            sb.setSpan(ForegroundColorSpan(primary), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
}
