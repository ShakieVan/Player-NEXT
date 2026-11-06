package de.shakie.playernext.tags

import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractTagFrame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Lyrics as stored (embedded or .lrc). LRC time tags make them synchronized: [lines] then holds every sung
 * line in playback order, a line with several time tags once per tag. Plain lyrics have no [lines].
 */
class Lyrics private constructor(
    /** Text as stored, with time tags; this is what the editor shows and saves. */
    val raw: String,
    /** Text without time tags and LRC header, in written order, for reading. */
    val plain: String,
    val lines: List<Line>,
) {
    class Line(val timeMs: Long, val text: String)

    val synced: Boolean get() = lines.isNotEmpty()

    /** Index of the line sung at [positionMs]; -1 before the first timed line. */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0 && lines[found].timeMs >= 0) found else -1
    }

    /** Lines starting together with line [index] (an untimed line belongs to the timed one above it). */
    fun groupOf(index: Int): IntRange {
        if (index !in lines.indices) return IntRange.EMPTY
        val t = lines[index].timeMs
        var first = index
        while (first > 0 && lines[first - 1].timeMs == t) first--
        var last = index
        while (last < lines.size - 1 && lines[last + 1].timeMs == t) last++
        return first..last
    }

    /** Start of the first line after [positionMs], null after the last one. */
    fun nextChangeAfter(positionMs: Long): Long? {
        val i = indexAt(positionMs)
        var j = i + 1
        // Before the first timed line the leading untimed lines (time -1) are skipped.
        while (j < lines.size && lines[j].timeMs <= positionMs) j++
        return lines.getOrNull(j)?.timeMs
    }

    companion object {
        /**
         * Lyrics stored in [tag]: the standard field (USLT, LYRICS, ©lyr), else UNSYNCEDLYRICS (foobar2000) or the
         * TXXX "lyrics-…" frame that ffmpeg writes into MP3 instead of USLT.
         */
        fun fieldOf(tag: Tag?): String? {
            tag ?: return null
            tag.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }?.let { return it }
            for (field in tag.fields) {
                val id = field.id.orEmpty().uppercase(Locale.ROOT)
                if (id == "UNSYNCEDLYRICS" || id == "UNSYNCED LYRICS") {
                    (field as? TagTextField)?.content?.takeIf { it.isNotBlank() }?.let { return it }
                }
                val txxx = (field as? AbstractTagFrame)?.body as? FrameBodyTXXX ?: continue
                val name = txxx.description.orEmpty().lowercase(Locale.ROOT)
                if (name == "lyrics" || name.startsWith("lyrics-") || name == "unsyncedlyrics") {
                    txxx.firstTextValue?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
            return null
        }

        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val LEADING_TIME_TAGS = Regex("""^(?:\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
        private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val HEADER = Regex("""^\s*\[(ar|al|ti|au|by|offset|length|re|ve|tool|la|lang|id|#):.*]\s*$""", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("""^\s*\[offset:\s*([+-]?\d+)\s*]\s*$""", RegexOption.IGNORE_CASE)

        fun parse(raw: String): Lyrics {
            val source = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
            var offset = 0L
            val plain = ArrayList<String>()
            // (times, text) per written line; times empty for untimed lines.
            val written = ArrayList<Pair<List<Long>, String>>()
            for (line in source.split('\n')) {
                OFFSET.find(line)?.let { m -> offset = m.groupValues[1].toLongOrNull() ?: 0 }
                if (HEADER.matches(line)) continue
                val lead = LEADING_TIME_TAGS.find(line)?.value.orEmpty()
                val times = TIME_TAG.findAll(lead).map { toMs(it) }.toList()
                val text = clean(line.substring(lead.length))
                plain += text
                written += times to text
            }
            val plainText = plain.joinToString("\n").trim()
            val timed = written.count { it.first.isNotEmpty() }
            val untimed = written.count { it.first.isEmpty() && it.second.isNotEmpty() }
            if (timed == 0 || timed < untimed) return Lyrics(raw, plainText, emptyList())

            // Untimed lines inside synchronized lyrics are shown with the timed line above them.
            val entries = ArrayList<Line>()
            var current: List<Long> = listOf(-1L)
            for ((times, text) in written) {
                if (times.isNotEmpty()) current = times.map { (it - offset).coerceAtLeast(0) }
                else if (text.isEmpty()) continue
                for (t in current) entries += Line(t, text)
            }
            // Stable: lines with the same time keep their written order.
            entries.sortBy { it.timeMs }
            return Lyrics(raw, plainText, entries)
        }

        private fun toMs(m: MatchResult): Long {
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            return (min * 60 + sec) * 1000 + frac
        }

        /** Removes word time tags (enhanced LRC) and time tags inside the line. */
        private fun clean(text: String) = text.replace(WORD_TAG, "").replace(TIME_TAG, "").trim()

        /** Text of an .lrc file: UTF-8 or UTF-16 with BOM, else UTF-8, else Windows-1252 (older Windows tools). */
        fun decode(bytes: ByteArray): String {
            fun hasPrefix(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
            return when {
                hasPrefix(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                hasPrefix(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                hasPrefix(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: CharacterCodingException) {
                    String(bytes, Charset.forName("windows-1252"))
                }
            }
        }
    }
}
