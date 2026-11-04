package de.shakie.playernext.playback

import java.io.File
import java.io.RandomAccessFile

/** A chapter of an audiobook: where it starts and what it is called. */
data class Chapter(val startMs: Long, val title: String)

/**
 * Chapters of MP4 audio (M4B audiobooks, also M4A): the QuickTime chapter track that iTunes,
 * Audible conversions and ffmpeg write (a text track referenced by `tref/chap`), otherwise
 * Nero's `udta/chpl`. Only the atom headers and the few chapter samples are read.
 */
object Mp4Chapters {
    private const val MAX_CHAPTERS = 5000
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "tref", "edts")

    private class Track {
        var id = 0
        var handler = ""
        var timescale = 0L
        var chapRefs = IntArray(0)
        var durations = ArrayList<Pair<Long, Long>>() // (count, delta) from stts
        var chunkMap = ArrayList<Pair<Long, Long>>() // (first chunk, samples per chunk) from stsc
        var chunkOffsets = LongArray(0)
        var sampleSizes = LongArray(0)
        var fixedSampleSize = 0L
        var sampleCount = 0L
    }

    fun read(file: File): List<Chapter> = try {
        RandomAccessFile(file, "r").use { parse(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun parse(f: RandomAccessFile): List<Chapter> {
        val tracks = ArrayList<Track>()
        var nero: List<Chapter> = emptyList()
        fun walk(start: Long, end: Long, track: Track?) {
            var pos = start
            while (pos + 8 <= end) {
                f.seek(pos)
                var size = f.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                var header = 8L
                if (size == 1L) {
                    size = f.readLong()
                    header = 16
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < header || pos + size > end) return
                val body = pos + header
                val bodySize = size - header
                when (type) {
                    "trak" -> Track().also { tracks += it; walk(body, pos + size, it) }
                    in containers -> walk(body, pos + size, track)
                    "tkhd" -> if (track != null) {
                        val version = f.read()
                        f.seek(body + if (version == 1) 20 else 12)
                        track.id = f.readInt()
                    }
                    "chap" -> if (track != null) track.chapRefs = IntArray((bodySize / 4).toInt().coerceAtMost(64)) { f.readInt() }
                    "hdlr" -> if (track != null && track.handler.isEmpty()) {
                        f.seek(body + 8)
                        track.handler = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                    }
                    "mdhd" -> if (track != null) {
                        val version = f.read()
                        f.seek(body + if (version == 1) 20 else 12)
                        track.timescale = f.readInt().toLong() and 0xFFFFFFFFL
                    }
                    "stts" -> if (track != null) {
                        f.seek(body + 4)
                        repeat(f.readInt().coerceIn(0, MAX_CHAPTERS)) {
                            track.durations += (f.readInt().toLong() and 0xFFFFFFFFL) to (f.readInt().toLong() and 0xFFFFFFFFL)
                        }
                    }
                    "stsc" -> if (track != null) {
                        f.seek(body + 4)
                        repeat(f.readInt().coerceIn(0, MAX_CHAPTERS)) {
                            track.chunkMap += (f.readInt().toLong() and 0xFFFFFFFFL) to (f.readInt().toLong() and 0xFFFFFFFFL)
                            f.readInt() // sample description index
                        }
                    }
                    "stsz" -> if (track != null) {
                        f.seek(body + 4)
                        track.fixedSampleSize = f.readInt().toLong() and 0xFFFFFFFFL
                        track.sampleCount = f.readInt().toLong() and 0xFFFFFFFFL
                        // Only the (small) chapter track needs the sizes; audio tracks have thousands.
                        if (track.fixedSampleSize == 0L && track.sampleCount <= MAX_CHAPTERS) {
                            track.sampleSizes = LongArray(track.sampleCount.toInt()) { f.readInt().toLong() and 0xFFFFFFFFL }
                        }
                    }
                    "stco", "co64" -> if (track != null) {
                        f.seek(body + 4)
                        val n = f.readInt()
                        if (n in 0..MAX_CHAPTERS) {
                            track.chunkOffsets = LongArray(n) { if (type == "co64") f.readLong() else f.readInt().toLong() and 0xFFFFFFFFL }
                        }
                    }
                    "chpl" -> nero = readChpl(f, body, bodySize)
                }
                pos += size
            }
        }
        walk(0, f.length(), null)
        val refs = tracks.flatMap { it.chapRefs.toList() }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in refs && it.timescale > 0 && it.handler != "soun" && it.handler != "vide" }
        val fromTrack = chapterTrack?.let { readTrack(f, it) }.orEmpty()
        return tidy(fromTrack.ifEmpty { nero })
    }

    /** Chapter titles are the text samples of the track: 16-bit length, then UTF-8 or UTF-16 text. */
    private fun readTrack(f: RandomAccessFile, t: Track): List<Chapter> {
        val count = if (t.sampleSizes.isNotEmpty()) t.sampleSizes.size else t.sampleCount.toInt().coerceAtMost(MAX_CHAPTERS)
        if (count == 0 || t.chunkOffsets.isEmpty()) return emptyList()
        // Sample offsets from chunk offsets and samples per chunk.
        val offsets = LongArray(count)
        var sample = 0
        for (chunk in t.chunkOffsets.indices) {
            val chunkNumber = chunk + 1L
            val perChunk = t.chunkMap.lastOrNull { it.first <= chunkNumber }?.second ?: 1L
            var offset = t.chunkOffsets[chunk]
            for (k in 0 until perChunk) {
                if (sample >= count) break
                offsets[sample] = offset
                offset += if (t.fixedSampleSize > 0) t.fixedSampleSize else t.sampleSizes[sample]
                sample++
            }
        }
        // Start times from the sample durations.
        val starts = LongArray(count)
        var time = 0L
        var i = 0
        loop@ for ((n, delta) in t.durations) {
            for (k in 0 until n) {
                if (i >= count) break@loop
                starts[i++] = time
                time += delta
            }
        }
        return (0 until count).map { s ->
            f.seek(offsets[s])
            val len = f.readUnsignedShort().coerceAtMost(1024)
            val bytes = ByteArray(len).also { f.readFully(it) }
            val title = if (len >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) String(bytes, 2, len - 2, Charsets.UTF_16BE)
            else String(bytes, Charsets.UTF_8)
            Chapter(starts[s] * 1000 / t.timescale, title.trim())
        }
    }

    /** Nero chapters: version, flags, (reserved), count, then start (100 ns units), title length, title. */
    private fun readChpl(f: RandomAccessFile, body: Long, size: Long): List<Chapter> {
        f.seek(body)
        val version = f.read()
        f.skipBytes(3)
        if (version != 0) f.skipBytes(4)
        val n = f.read().coerceAtLeast(0)
        val end = body + size
        val result = ArrayList<Chapter>(n)
        repeat(n) {
            if (f.filePointer + 9 > end) return result
            val start = f.readLong() / 10_000
            val len = f.read()
            val title = ByteArray(len).also { f.readFully(it) }.toString(Charsets.UTF_8)
            result += Chapter(start, title.trim())
        }
        return result
    }

    private fun tidy(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedBy { it.startMs }.distinctBy { it.startMs }
            .mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = "Kapitel ${i + 1}") else c }
}

/** Chapter jumps, shared by the player (⏮/⏭) and the chapter list. */
object ChapterNav {
    /** Index of the chapter playing at [positionMs]. */
    fun indexAt(chapters: List<Chapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /**
     * Where ⏭ (forward) or ⏮ goes inside a file with [chapters], or null for the usual track
     * change: ⏮ first returns to the start of the chapter, within its first 3 s to the one before.
     */
    fun jumpTarget(chapters: List<Chapter>, positionMs: Long, forward: Boolean): Long? {
        if (chapters.size < 2) return null
        val i = indexAt(chapters, positionMs)
        return if (forward) {
            chapters.getOrNull(i + 1)?.startMs
        } else {
            val start = chapters[i].startMs
            when {
                positionMs - start > 3000 -> start
                i > 0 -> chapters[i - 1].startMs
                else -> null
            }
        }
    }
}
