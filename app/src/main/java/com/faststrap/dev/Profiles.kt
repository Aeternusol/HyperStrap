package com.faststrap.dev

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A saved selection of tweaks. [packs] lists the flag packs that should be on; null means the
 * profile leaves packs alone (the built-in ones).
 */
class Profile(
    val name: String,
    val tweaks: Set<String>,
    val packs: Set<Long>?,
    val builtIn: Boolean
)

object Profiles {
    // Starting points that choose between the app's tweaks. They describe intent only and make
    // no promise about results on any particular device.
    val builtIn = listOf(
        Profile("Competitive / FPS", setOf("fps", "sky", "grass", "shadows", "textures"), null, true),
        Profile("Balanced", setOf("fps", "grass", "shadows"), null, true),
        Profile("High graphics", setOf("fps"), null, true),
        Profile("Battery saver", setOf("sky", "grass", "shadows", "textures"), null, true)
    )

    fun loadCustom(prefs: SharedPreferences): MutableList<Profile> {
        val out = mutableListOf<Profile>()
        val raw = prefs.getString("profiles", null) ?: return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tw = HashSet<String>()
                val twArr = o.getJSONArray("tweaks")
                for (j in 0 until twArr.length()) tw.add(twArr.getString(j))
                val pk = HashSet<Long>()
                val pkArr = o.getJSONArray("packs")
                for (j in 0 until pkArr.length()) pk.add(pkArr.getLong(j))
                out.add(Profile(o.getString("name"), tw, pk, false))
            }
        } catch (e: JSONException) {
            // Corrupt data: start with no custom profiles.
        }
        return out
    }

    fun saveCustom(prefs: SharedPreferences, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("tweaks", JSONArray(p.tweaks.toList()))
            o.put("packs", JSONArray((p.packs ?: emptySet()).toList()))
            arr.put(o)
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }
}
