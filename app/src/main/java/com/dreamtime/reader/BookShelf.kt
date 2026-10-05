package com.dreamtime.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BookProject(
    val id: String,
    val title: String,
    val pdfUri: Uri? = null,
    val audioUri: Uri? = null,
    val subtitleUri: Uri? = null,
    val page: Int = 0,
    val positionMs: Long = 0L,
    val complete: Boolean = false,
    val listenedMs: Long = 0L
)

class BookShelf(context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("dream-time-shelf", Context.MODE_PRIVATE)
    var activeId: String?
        get() = prefs.getString("active_id", null)
        private set(value) { prefs.edit().putString("active_id", value).apply() }

    fun all(): List<BookProject> {
        val json = runCatching { JSONArray(prefs.getString("books", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until json.length()).mapNotNull { i ->
            runCatching {
                val b = json.getJSONObject(i)
                BookProject(
                    id = b.getString("id"), title = b.optString("title", "کتاب تازه"),
                    pdfUri = b.optString("pdf").takeIf { it.isNotBlank() }?.let(Uri::parse),
                    audioUri = b.optString("audio").takeIf { it.isNotBlank() }?.let(Uri::parse),
                    subtitleUri = b.optString("subtitle").takeIf { it.isNotBlank() }?.let(Uri::parse),
                    page = b.optInt("page", 0), positionMs = b.optLong("position", 0L), complete = b.optBoolean("complete", false), listenedMs = b.optLong("listened", 0L)
                )
            }.getOrNull()
        }
    }

    fun active(): BookProject? = all().firstOrNull { it.id == activeId }

    fun select(book: BookProject) {
        activeId = book.id
        save(book)
    }

    fun newBook(): BookProject = BookProject(UUID.randomUUID().toString(), "کتاب تازه").also { select(it) }

    fun save(book: BookProject) {
        val others = all().filterNot { it.id == book.id }
        val out = JSONArray()
        (others + book).forEach { b ->
            out.put(JSONObject().apply {
                put("id", b.id); put("title", b.title); put("pdf", b.pdfUri?.toString() ?: "")
                put("audio", b.audioUri?.toString() ?: ""); put("subtitle", b.subtitleUri?.toString() ?: "")
                put("page", b.page); put("position", b.positionMs); put("complete", b.complete); put("listened", b.listenedMs)
            })
        }
        prefs.edit().putString("books", out.toString()).apply()
    }

    fun updatePdf(uri: Uri): BookProject {
        val old = active() ?: newBook()
        val name = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        return old.copy(title = if (old.title == "کتاب تازه" && name != null) name else old.title, pdfUri = uri, subtitleUri = null, page = 0, positionMs = 0L, complete = false).also { select(it) }
    }

    fun updateAudio(uri: Uri): BookProject {
        val old = active() ?: newBook()
        val title = if (old.title == "کتاب تازه") displayName(uri)?.substringBeforeLast('.') ?: old.title else old.title
        return old.copy(title = title, audioUri = uri, subtitleUri = null, positionMs = 0L, complete = false).also { select(it) }
    }

    fun updateSubtitle(uri: Uri): BookProject {
        val old = active() ?: newBook()
        return old.copy(subtitleUri = uri).also { select(it) }
    }

    fun updateProgress(id: String?, page: Int, positionMs: Long) {
        val book = all().firstOrNull { it.id == id } ?: return
        save(book.copy(page = page, positionMs = positionMs))
    }

    fun addListeningTime(id: String?, elapsedMs: Long) {
        val book = all().firstOrNull { it.id == id } ?: return
        save(book.copy(listenedMs = book.listenedMs + elapsedMs.coerceAtLeast(0L)))
    }

    fun setComplete(book: BookProject, complete: Boolean) = save(book.copy(complete = complete))

    fun remove(book: BookProject) {
        val out = JSONArray()
        all().filterNot { it.id == book.id }.forEach { b ->
            out.put(JSONObject().apply {
                put("id", b.id); put("title", b.title); put("pdf", b.pdfUri?.toString() ?: "")
                put("audio", b.audioUri?.toString() ?: ""); put("subtitle", b.subtitleUri?.toString() ?: "")
                put("page", b.page); put("position", b.positionMs); put("complete", b.complete); put("listened", b.listenedMs)
            })
        }
        prefs.edit().putString("books", out.toString()).apply()
        if (activeId == book.id) activeId = null
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun exportJson(): String = JSONObject().put("activeId", activeId).put("books", JSONArray(prefs.getString("books", "[]") ?: "[]")).toString()

    fun importJson(json: String) {
        val root = JSONObject(json)
        val list = root.getJSONArray("books")
        prefs.edit().putString("books", list.toString()).putString("active_id", root.optString("activeId").takeIf { it.isNotBlank() }).apply()
    }
}
