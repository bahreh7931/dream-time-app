package com.dreamtime.reader

import android.content.Context
import org.json.JSONObject

class BackupSupport(context: Context) {
    private val appPrefs = context.getSharedPreferences("dream-time", Context.MODE_PRIVATE)
    private val shelf = BookShelf(context)

    fun export(): String {
        val settings = JSONObject()
        appPrefs.all.forEach { (key, value) ->
            when (value) {
                is String, is Boolean, is Int, is Long, is Float, is Double -> settings.put(key, value)
            }
        }
        return JSONObject().put("format", "dream-time-backup-v1").put("settings", settings)
            .put("shelf", JSONObject(shelf.exportJson())).toString(2)
    }

    fun restore(json: String) {
        val root = JSONObject(json)
        require(root.optString("format") == "dream-time-backup-v1") { "این فایل پشتیبان Dream Time نیست." }
        val settings = root.getJSONObject("settings")
        val editor = appPrefs.edit().clear()
        settings.keys().forEach { key ->
            val value = settings.get(key)
            when {
                value is Boolean -> editor.putBoolean(key, value)
                value is String -> editor.putString(key, value)
                value is Number && key in setOf("speed", "screen_brightness") -> editor.putFloat(key, value.toFloat())
                value is Number && (key in setOf("position", "sleep_timer_end") || key.startsWith("anchor_")) -> editor.putLong(key, value.toLong())
                value is Number -> editor.putInt(key, value.toInt())
            }
        }
        editor.apply()
        shelf.importJson(root.getJSONObject("shelf").toString())
    }
}
