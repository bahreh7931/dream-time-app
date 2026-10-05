package com.dreamtime.reader

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.text.Normalizer

data class TimedCaption(val startMs: Long, val endMs: Long, val text: String)
data class AlignmentResult(val anchors: List<Pair<Int, Long>>, val pdfPages: Int, val captions: Int)

object TextSync {
    @Volatile private var initialized = false

    fun readCaptions(context: Context, uri: Uri): List<TimedCaption> {
        val body = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("زیرنویس باز نشد")
        val lines = body.removePrefix("\uFEFF").replace("\r", "").lines()
        val result = mutableListOf<TimedCaption>()
        var index = 0
        while (index < lines.size) {
            val timeLine = lines[index].trim()
            if ("-->" !in timeLine) { index++; continue }
            val halves = timeLine.split("-->", limit = 2)
            val start = parseTime(halves[0].trim())
            val end = parseTime(halves[1].trim().substringBefore(' '))
            index++
            val text = buildList {
                while (index < lines.size && lines[index].isNotBlank() && "-->" !in lines[index]) add(lines[index++].trim())
            }.joinToString(" ")
            if (start != null && end != null && text.isNotBlank()) result += TimedCaption(start, end, text)
        }
        return result
    }

    fun align(context: Context, pdfUri: Uri, captions: List<TimedCaption>): AlignmentResult {
        synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
        val pages = context.contentResolver.openInputStream(pdfUri)?.use { input ->
            PDDocument.load(input).use { document ->
                val stripper = PDFTextStripper()
                (1..document.numberOfPages).map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.getText(document)
                }
            }
        } ?: error("PDF باز نشد")

        data class WordAt(val word: String, val time: Long)
        val transcript = captions.flatMap { cue -> normalize(cue.text).map { WordAt(it, cue.startMs) } }
        val index = HashMap<String, MutableList<Int>>()
        val phraseSize = 6
        for (i in 0..(transcript.size - phraseSize).coerceAtLeast(-1)) {
            val key = transcript.subList(i, i + phraseSize).joinToString("\u0001") { it.word }
            index.getOrPut(key) { mutableListOf() }.add(i)
        }

        val anchors = mutableListOf<Pair<Int, Long>>()
        var transcriptCursor = 0
        pages.forEachIndexed { pageIndex, text ->
            val words = normalize(text)
            if (words.size < phraseSize) return@forEachIndexed
            val maxStart = minOf(words.size - phraseSize, 100)
            var found: Pair<Int, Long>? = null
            for (offset in 0..maxStart step 4) {
                val key = words.subList(offset, offset + phraseSize).joinToString("\u0001")
                val tokenIndex = index[key]?.firstOrNull { it >= transcriptCursor }
                if (tokenIndex != null) {
                    found = tokenIndex to transcript[tokenIndex].time
                    transcriptCursor = tokenIndex + phraseSize
                    break
                }
            }
            found?.let { anchors += pageIndex to it.second }
        }
        return AlignmentResult(anchors, pages.size, captions.size)
    }

    private fun normalize(value: String): List<String> {
        val normalized = Normalizer.normalize(value.replace(Regex("<[^>]*>|\\{[^}]*}"), " "), Normalizer.Form.NFKC)
            .replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .lowercase()
        return Regex("[\\p{L}\\p{N}]+").findAll(normalized).map { it.value }.toList()
    }

    private fun parseTime(value: String): Long? = runCatching {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().toDouble()
        val wholeSeconds = when (parts.size) {
            3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + seconds.toLong()
            2 -> parts[0].toLong() * 60 + seconds.toLong()
            else -> return null
        }
        wholeSeconds * 1000 + ((seconds - seconds.toLong()) * 1000).toLong()
    }.getOrNull()
}
