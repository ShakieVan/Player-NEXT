package de.shakie.playernext.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyPOPM
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

class RatingTagsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        TagOptionSingleton.getInstance().isAndroid = true
    }

    private fun copy(ext: String): File {
        val target = File(tmp.root, "t${System.nanoTime()}.$ext")
        javaClass.getResourceAsStream("/tags/test.$ext")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** Writes [rating] like TagIO does and reads it back like the library scan does. */
    private fun roundTrip(ext: String, rating: Int): Int {
        val file = copy(ext)
        val af = AudioFileIO.read(file)
        RatingTags.write(af.tagOrCreateAndSetDefault, rating)
        af.commit()
        return RatingTags.readFile(file, ext)
    }

    @Test
    fun scalesMapToHalfStars() {
        assertEquals(listOf(0, 2, 4, 6, 8, 10), listOf(0, 1, 64, 128, 196, 255).map(RatingTags::fromPopm))
        assertEquals(listOf(1, 3, 5, 7, 9), listOf(13, 54, 118, 186, 242).map(RatingTags::fromPopm))
        // Other POPM writers: anything in a star's band counts as that star.
        assertEquals(listOf(2, 4, 6, 8, 10), listOf(20, 51, 102, 180, 230).map(RatingTags::fromPopm))
        assertEquals((0..10).toList(), (0..10).map { RatingTags.fromPopm(RatingTags.toPopm(it)) })
        assertEquals(8, RatingTags.fromText("4"))
        assertEquals(9, RatingTags.fromText("4.5"))
        assertEquals(9, RatingTags.fromText("4,5"))
        assertEquals(8, RatingTags.fromText("80"))
        assertEquals(10, RatingTags.fromText("100"))
        assertEquals(6, RatingTags.fromText("0.6", fraction = true))
        assertEquals(0, RatingTags.fromText("0"))
        assertEquals(null, RatingTags.fromText("viel"))
    }

    @Test
    fun writeAndReadEveryFormat() {
        for (ext in listOf("mp3", "flac", "ogg", "m4a")) {
            for (r in listOf(10, 7, 2, 1)) assertEquals("$ext $r", r, roundTrip(ext, r))
            assertEquals("$ext cleared", 0, roundTrip(ext, 0))
        }
        assertEquals(0, RatingTags.readFile(copy("mp3"), "mp3"))
    }

    @Test
    fun readsRatingsOfOtherPlayers() {
        // MediaMonkey POPM 196 = 4 stars, next to an unrated POPM of another player.
        val mp3 = copy("mp3")
        AudioFileIO.read(mp3).let { af ->
            val tag = af.tagOrCreateAndSetDefault as AbstractID3v2Tag
            val a = tag.createFrame("POPM").apply { body = FrameBodyPOPM("no@email", 0, 3) }
            val b = tag.createFrame("POPM").apply { body = FrameBodyPOPM(FrameBodyPOPM.MEDIA_MONKEY_NO_EMAIL, 196, 0) }
            tag.setFrame("POPM", listOf(a, b))
            af.commit()
        }
        assertEquals(8, RatingTags.readFile(mp3, "mp3"))
        // Writing updates both frames and keeps the play counter.
        AudioFileIO.read(mp3).let { af -> RatingTags.write(af.tag, 5); af.commit() }
        val frames = (AudioFileIO.read(mp3).tag as AbstractID3v2Tag).getFields("POPM").map { (it as org.jaudiotagger.tag.id3.AbstractID3v2Frame).body as FrameBodyPOPM }
        assertEquals(listOf(118L, 118L), frames.map { it.rating })
        assertEquals(3L, frames.first { it.emailToUser == "no@email" }.counter)

        // foobar2000 style TXXX:RATING on 1–5.
        val txxx = copy("mp3")
        AudioFileIO.read(txxx).let { af ->
            val tag = af.tagOrCreateAndSetDefault as AbstractID3v2Tag
            tag.setFrame(tag.createFrame("TXXX").apply { body = FrameBodyTXXX(0, "RATING", "4") })
            af.commit()
        }
        assertEquals(8, RatingTags.readFile(txxx, "mp3"))

        // FLAC: RATING on 1–5 stays on that scale; FMPS_RATING is a fraction.
        val flac = copy("flac")
        AudioFileIO.read(flac).let { af -> (af.tag as FlacTag).setField(FieldKey.RATING, "3"); af.commit() }
        assertEquals(6, RatingTags.readFile(flac, "flac"))
        AudioFileIO.read(flac).let { af -> RatingTags.write(af.tag, 9); af.commit() }
        assertEquals("4.5", AudioFileIO.read(flac).tag.getFirst(FieldKey.RATING))
        val ogg = copy("ogg")
        AudioFileIO.read(ogg).let { af -> (af.tag as VorbisCommentTag).setField("FMPS_RATING", "0.8"); af.commit() }
        assertEquals(8, RatingTags.readFile(ogg, "ogg"))
    }
}
