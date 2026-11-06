package de.shakie.playernext.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class LyricsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun Lyrics.texts() = lines.map { it.text }
    private fun Lyrics.times() = lines.map { it.timeMs }

    @Test
    fun plainLyricsAreNotSynced() {
        val l = Lyrics.parse("[Verse]\nErste Zeile\nZweite Zeile\n\n[Chorus]\nRefrain")
        assertFalse(l.synced)
        assertEquals("[Verse]\nErste Zeile\nZweite Zeile\n\n[Chorus]\nRefrain", l.plain)
    }

    @Test
    fun parsesTimeTagsInAllCommonForms() {
        val l = Lyrics.parse("[00:01]a\n[00:02.5]b\n[00:03.25]c\n[00:04.125]d\n[00:05:50]e\n[1:06.00]f\n[100:00.00]g")
        assertTrue(l.synced)
        assertEquals(listOf(1000L, 2500, 3250, 4125, 5500, 66_000, 6_000_000), l.times())
        assertEquals("a\nb\nc\nd\ne\nf\ng", l.plain)
    }

    @Test
    fun headerIsHiddenAndOffsetApplied() {
        val l = Lyrics.parse("[ti:Titel]\n[ar:Interpret]\n[offset:+500]\n[00:10.00]Eins\n[00:20.00]Zwei")
        assertEquals(listOf(9500L, 19_500), l.times())
        assertEquals("Eins\nZwei", l.plain)
        val later = Lyrics.parse("[offset:-250]\n[00:10.00]Eins")
        assertEquals(listOf(10_250L), later.times())
    }

    @Test
    fun repeatedLineAppearsAtEachTimeInOrder() {
        val l = Lyrics.parse("[00:30.00][01:30.00]Refrain\nZweite Refrainzeile\n[01:00.00]Strophe")
        assertEquals(listOf("Refrain", "Zweite Refrainzeile", "Strophe", "Refrain", "Zweite Refrainzeile"), l.texts())
        assertEquals(listOf(30_000L, 30_000, 60_000, 90_000, 90_000), l.times())
        // Reading text keeps the written order and shows the refrain once.
        assertEquals("Refrain\nZweite Refrainzeile\nStrophe", l.plain)
        assertEquals(0..1, l.groupOf(1))
        assertEquals(3..4, l.groupOf(l.indexAt(95_000)))
    }

    @Test
    fun wordTagsAndCrLfAreRemoved() {
        val l = Lyrics.parse("﻿[00:01.00]<00:01.00>Hal<00:01.40>lo\r\n[00:02.00] Welt ")
        assertEquals(listOf("Hallo", "Welt"), l.texts())
    }

    @Test
    fun linesBeforeTheFirstTimeTagAreNeverCurrent() {
        val l = Lyrics.parse("Lied - Interpret\n[00:05.00]Los\n[00:08.00]\n[00:12.00]Weiter\n[00:20.00]")
        assertEquals(listOf("Lied - Interpret", "Los", "", "Weiter", ""), l.texts())
        assertEquals(-1, l.indexAt(0))
        assertEquals(-1, l.indexAt(4_999))
        assertEquals(1, l.indexAt(5_000))
        assertEquals(2, l.indexAt(9_000)) // empty line: instrumental, nothing sung
        assertEquals(4, l.indexAt(600_000)) // end of the vocals
        assertEquals(5_000L, l.nextChangeAfter(0))
        assertEquals(8_000L, l.nextChangeAfter(5_000))
        assertEquals(20_000L, l.nextChangeAfter(12_000))
        assertNull(l.nextChangeAfter(20_000))
    }

    @Test
    fun mostlyUntimedTextStaysPlain() {
        val l = Lyrics.parse("[00:01.00]Nur eine Zeit\nZeile\nNoch eine\nUnd noch eine")
        assertFalse(l.synced)
        assertEquals("Nur eine Zeit\nZeile\nNoch eine\nUnd noch eine", l.plain)
    }

    @Test
    fun decodesUtf8Utf16AndWindows1252() {
        assertEquals("Grüße", Lyrics.decode("Grüße".toByteArray(Charsets.UTF_8)))
        assertEquals("Grüße", Lyrics.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Grüße".toByteArray(Charsets.UTF_8)))
        assertEquals("Grüße", Lyrics.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Grüße".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Grüße", Lyrics.decode("Grüße".toByteArray(charset("windows-1252"))))
    }

    private fun copy(ext: String): File {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        TagOptionSingleton.getInstance().isAndroid = true
        val target = File(tmp.root, "t${System.nanoTime()}.$ext")
        javaClass.getResourceAsStream("/tags/test.$ext")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /**
     * An ID3v2.3 USLT frame built byte by byte like the KI-Musik tools write their frames:
     * encoding 1 (UTF-16 with BOM), language "deu", empty description, text.
     */
    @Test
    fun handBuiltId3v23UsltFrameIsRead() {
        val lrc = "[00:01.50]Erste Zeile\n[00:04.00]Zweite Zeile mit Ä"
        fun utf16(s: String) = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
        val data = byteArrayOf(1) + "deu".toByteArray() + utf16("") + byteArrayOf(0, 0) + utf16(lrc)
        val frame = "USLT".toByteArray() + java.nio.ByteBuffer.allocate(4).putInt(data.size).array() + byteArrayOf(0, 0) + data
        val n = frame.size
        val header = "ID3".toByteArray() + byteArrayOf(3, 0, 0) +
            byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
        val source = copy("mp3").readBytes()
        val audioStart = if (source.copyOf(3).contentEquals("ID3".toByteArray())) {
            10 + ((source[6].toInt() shl 21) or (source[7].toInt() shl 14) or (source[8].toInt() shl 7) or source[9].toInt())
        } else 0
        val file = File(tmp.root, "uslt.mp3")
        file.writeBytes(header + frame + source.copyOfRange(audioStart, source.size))
        assertEquals(lrc, Lyrics.fieldOf(AudioFileIO.read(file).tag))
    }

    /** ffmpeg writes "-metadata lyrics-deu=…" into MP3 as TXXX; foobar2000 uses UNSYNCEDLYRICS in FLAC. */
    @Test
    fun ffmpegTxxxAndUnsyncedLyricsAreFound() {
        val lrc = "[00:01.00]Eins\n[00:02.00]Zwei"
        val mp3 = copy("mp3")
        AudioFileIO.read(mp3).let { af ->
            val tag = af.tagOrCreateAndSetDefault as org.jaudiotagger.tag.id3.AbstractID3v2Tag
            tag.setFrame(tag.createFrame("TXXX").apply { body = org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX(1, "lyrics-deu", lrc) })
            af.commit()
        }
        assertEquals(lrc, Lyrics.fieldOf(AudioFileIO.read(mp3).tag))
        val flac = copy("flac")
        AudioFileIO.read(flac).let { af -> (af.tag as org.jaudiotagger.tag.flac.FlacTag).vorbisCommentTag.setField("UNSYNCEDLYRICS", lrc); af.commit() }
        assertEquals(lrc, Lyrics.fieldOf(AudioFileIO.read(flac).tag))
    }

    /** The embedded lyrics field holds LRC text unchanged in MP3 (USLT), FLAC and M4A. */
    @Test
    fun lrcTextSurvivesTheLyricsTag() {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        TagOptionSingleton.getInstance().isAndroid = true
        val lrc = "[ti:Test]\n[00:01.50]Erste Zeile\n[00:04.00]Zweite Zeile mit Ä"
        for (ext in listOf("mp3", "flac", "m4a")) {
            val stream = javaClass.getResourceAsStream("/tags/test.$ext") ?: continue
            val file = File(tmp.root, "lrc.$ext")
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
            val audio = AudioFileIO.read(file)
            audio.tagOrCreateAndSetDefault.setField(FieldKey.LYRICS, lrc)
            audio.commit()
            val back = AudioFileIO.read(file).tag.getFirst(FieldKey.LYRICS)
            assertEquals(ext, lrc, back)
            assertEquals(ext, listOf(1500L, 4000), Lyrics.parse(back).times())
        }
    }
}
