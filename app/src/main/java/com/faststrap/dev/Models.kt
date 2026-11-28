package com.faststrap.dev

import android.widget.CompoundButton
import org.json.JSONObject

/** One UI toggle plus the FFlags it controls. */
class Tweak(
    val prefKey: String,
    val toggle: CompoundButton,
    val flags: Map<String, Any>
)

/** A set of flags imported from the clipboard, a file or a link. Applied before tweaks. */
class Pack(
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
class FlagRow(
    val key: String,
    val value: Any,
    val group: String,
    val source: String,
    val applied: Boolean,
    val category: String
)

/** Everything Remove all clears, kept briefly so Undo can put it back. */
class PrefState(
    val tweaks: Map<String, Boolean>,
    val packs: String?,
    val custom: Set<String>,
    val stale: Set<String>,
    val overrides: String?
)
