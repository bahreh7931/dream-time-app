package com.dreamtime.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.nio.charset.StandardCharsets

class BookPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("dream-time", Context.MODE_PRIVATE)
    var pdfUri: Uri?
        get() = prefs.getString("pdf", null)?.let(Uri::parse)
        set(value) { prefs.edit().putString("pdf", value?.toString()).apply() }
    var audioUri: Uri?
        get() = prefs.getString("audio", null)?.let(Uri::parse)
        set(value) { prefs.edit().putString("audio", value?.toString()).apply() }
    var page: Int
        get() = prefs.getInt("page", 0)
        set(value) { prefs.edit().putInt("page", value).apply() }
    var audioPosition: Long
        get() = prefs.getLong("position", 0L)
        set(value) { prefs.edit().putLong("position", value).apply() }
    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(value) { prefs.edit().putFloat("speed", value).apply() }
    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", false)
        set(value) { prefs.edit().putBoolean("dark_theme", value).apply() }
    var palette: Int
        get() = prefs.getInt("palette", 0)
        set(value) { prefs.edit().putInt("palette", value).apply() }
    var highlightColor: Int
        get() = prefs.getInt("highlight_color", 0)
        set(value) { prefs.edit().putInt("highlight_color", value).apply() }
    var activeBookId: String?
        get() = prefs.getString("active_book_id", null)
        set(value) { prefs.edit().putString("active_book_id", value).apply() }
    var sleepTimerEnd: Long
        get() = prefs.getLong("sleep_timer_end", 0L)
        set(value) { prefs.edit().putLong("sleep_timer_end", value).apply() }
    var screenBrightness: Float
        get() = prefs.getFloat("screen_brightness", 1f)
        set(value) { prefs.edit().putFloat("screen_brightness", value.coerceIn(.2f, 1f)).apply() }
    var rotationLocked: Boolean
        get() = prefs.getBoolean("rotation_locked", false)
        set(value) { prefs.edit().putBoolean("rotation_locked", value).apply() }

    private val scope: String get() = activeBookId ?: "default"

    fun saveAnchor(page: Int, positionMs: Long) {
        prefs.edit().putLong("anchor_${scope}_$page", positionMs).apply()
    }

    fun pageAt(positionMs: Long): Int? = prefs.all
        .filterKeys { it.startsWith("anchor_${scope}_") }
        .mapNotNull { (key, value) ->
            val page = key.removePrefix("anchor_${scope}_").toIntOrNull()
            val time = value as? Long
            if (page != null && time != null && time <= positionMs) time to page else null
        }
        .maxByOrNull { it.first }?.second

    fun highlights(page: Int): List<HighlightMark> = prefs.getString("highlights_${scope}_$page", "")
        .orEmpty().split(';').mapNotNull { encoded ->
            val values = encoded.split(',')
            val coordinates = values.take(4).mapNotNull { it.toFloatOrNull() }
            val color = values.getOrNull(4)?.toIntOrNull() ?: 0xCCFFF0A8.toInt()
            val note = values.getOrNull(5)?.let { encodedNote -> runCatching { String(Base64.decode(encodedNote, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8) }.getOrNull() }.orEmpty()
            if (coordinates.size == 4) HighlightMark(coordinates[0], coordinates[1], coordinates[2], coordinates[3], color, note) else null
        }

    fun saveHighlights(page: Int, marks: List<HighlightMark>) {
        val value = marks.joinToString(";") { mark ->
            val note = Base64.encodeToString(mark.note.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            "${mark.left},${mark.top},${mark.right},${mark.bottom},${mark.color},$note"
        }
        prefs.edit().putString("highlights_${scope}_$page", value).apply()
    }
}

data class HighlightMark(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int, val note: String = "")
