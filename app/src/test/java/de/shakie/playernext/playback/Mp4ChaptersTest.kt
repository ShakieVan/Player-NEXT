package de.shakie.playernext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class Mp4ChaptersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val expected = listOf(Chapter(0, "Eins"), Chapter(1000, "Zwei – Größe"), Chapter(2500, "Drei"))

    private fun resource(name: String): File =
        File(tmp.root, name).also { f -> javaClass.getResourceAsStream("/chapters/$name")!!.use { i -> f.outputStream().use { i.copyTo(it) } } }

    @Test
    fun quickTimeChapterTrack() {
        assertEquals(expected, Mp4Chapters.read(resource("track.m4b")))
    }

    @Test
    fun chapterTrackWinsOverNero() {
        assertEquals(expected, Mp4Chapters.read(resource("both.m4b")))
    }

    @Test
    fun neroChaptersAlone() {
        // ftyp + moov/udta/chpl as written by older tools (version 1, 100 ns units).
        fun atom(type: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply { writeInt(body.size + 8); writeBytes(type); write(body) }
            return out.toByteArray()
        }
        val chpl = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(0x01000000)
                writeInt(0)
                writeByte(2)
                for ((ms, title) in listOf(0L to "Vorwort", 90_500L to "")) {
                    writeLong(ms * 10_000)
                    val t = title.toByteArray()
                    writeByte(t.size)
                    write(t)
                }
            }
        }.toByteArray()
        val file = File(tmp.root, "nero.m4b")
        file.writeBytes(atom("ftyp", "M4B ".toByteArray()) + atom("moov", atom("udta", atom("chpl", chpl))))
        assertEquals(listOf(Chapter(0, "Vorwort"), Chapter(90_500, "Kapitel 2")), Mp4Chapters.read(file))
    }

    @Test
    fun filesWithoutChaptersOrBrokenOnes() {
        assertTrue(Mp4Chapters.read(File(tmp.root, "fehlt.m4b")).isEmpty())
        File(tmp.root, "kaputt.m4b").apply { writeBytes(ByteArray(100) { 0x7F }) }.let { assertTrue(Mp4Chapters.read(it).isEmpty()) }
        // An MP3 is no MP4: nothing, and no exception.
        val mp3 = File(tmp.root, "t.mp3").also { f -> javaClass.getResourceAsStream("/tags/test.mp3")!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
        assertTrue(Mp4Chapters.read(mp3).isEmpty())
    }
}
