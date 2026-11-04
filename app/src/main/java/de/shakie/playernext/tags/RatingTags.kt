package de.shakie.playernext.tags

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyPOPM
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ratings stored in music files, in half stars (0..10, 0 = none). Understands the usual
 * conventions:
 *
 * - MP3: POPM frames (Windows Media Player, MusicBee, MediaMonkey, PlayerPro: 1/64/128/196/255
 *   for 1–5 stars, MediaMonkey's 13/54/118/186/242 for half stars), otherwise TXXX "RATING"
 *   (1–5 or 0–100) or "FMPS_Rating" (0.0–1.0).
 * - FLAC/Ogg/Opus: RATING (1–5 like foobar2000, or 0–100) or FMPS_RATING (0.0–1.0).
 * - MP4/M4A: "rate" (0–100).
 *
 * Writing keeps the convention a file already uses and updates every POPM frame, so other
 * players see the same value.
 */
object RatingTags {
    const val WMP_EMAIL = "Windows Media Player 9 Series"
    private const val POPM = "POPM"
    private const val TXXX = "TXXX"
    private const val FMPS = "FMPS_RATING"
    private const val RATING = "RATING"

    // Half stars → POPM byte: whole stars as Windows Media Player, half stars as MediaMonkey.
    private val popmValues = intArrayOf(0, 13, 1, 54, 64, 118, 128, 186, 196, 242, 255)

    fun fromPopm(value: Int): Int {
        if (value <= 0) return 0
        val exact = popmValues.indexOf(value)
        if (exact > 0) return exact
        return when {
            value < 32 -> 2
            value < 96 -> 4
            value < 160 -> 6
            value < 224 -> 8
            else -> 10
        }
    }

    fun toPopm(halfStars: Int): Int = popmValues[halfStars.coerceIn(0, 10)]

    /** A text rating: [fraction] 0.0–1.0 (FMPS), otherwise 1–5 stars (decimals allowed) or 0–100. Null if unreadable. */
    fun fromText(text: String?, fraction: Boolean = false): Int? {
        val v = text?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val half = when {
            v <= 0 -> 0.0
            fraction -> v.coerceAtMost(1.0) * 10
            v <= 5 -> v * 2
            else -> v.coerceAtMost(100.0) / 10
        }
        return half.roundToInt().coerceIn(0, 10)
    }

    /** Rating of the file at [file] (only the tag is read). 0 when it has none. */
    fun readFile(file: File, extension: String): Int {
        val tag = if (extension == "mp3") MP3File(file, MP3File.LOAD_IDV2TAG, true).iD3v2Tag else AudioFileIO.read(file).tag
        return read(tag)
    }

    fun read(tag: Tag?): Int {
        tag ?: return 0
        if (tag is AbstractID3v2Tag) {
            val popm = frames(tag, POPM).mapNotNull { it.body as? FrameBodyPOPM }.filter { it.rating > 0 }
            val chosen = popm.firstOrNull { it.emailToUser == WMP_EMAIL } ?: popm.maxByOrNull { it.rating }
            if (chosen != null) return fromPopm(chosen.rating.toInt().coerceAtMost(255))
            for (txxx in frames(tag, TXXX).mapNotNull { it.body as? FrameBodyTXXX }) {
                val name = txxx.description.orEmpty().uppercase(Locale.ROOT)
                if (name == RATING || name == FMPS) fromText(txxx.firstTextValue, fraction = name == FMPS)?.let { if (it > 0) return it }
            }
            return 0
        }
        fromText(first(tag, FMPS), fraction = true)?.let { if (it > 0) return it }
        return fromText(firstRating(tag)) ?: 0
    }

    /** Sets the rating ([halfStars], 0 = remove) in [tag], keeping the file's convention. */
    fun write(tag: Tag, halfStars: Int) {
        val r = halfStars.coerceIn(0, 10)
        if (tag is AbstractID3v2Tag) {
            val popm = frames(tag, POPM)
            if (popm.isEmpty() && r > 0) {
                val frame = tag.createFrame(POPM)
                frame.body = FrameBodyPOPM(WMP_EMAIL, toPopm(r).toLong(), 0)
                tag.setFrame(frame)
            }
            popm.forEach { (it.body as? FrameBodyPOPM)?.rating = toPopm(r).toLong() }
            for (txxx in frames(tag, TXXX).mapNotNull { it.body as? FrameBodyTXXX }) {
                when (txxx.description.orEmpty().uppercase(Locale.ROOT)) {
                    FMPS -> txxx.setText(fraction(r))
                    RATING -> txxx.setText(sameScale(txxx.firstTextValue, r))
                }
            }
            return
        }
        val vorbis = tag as? VorbisCommentTag ?: (tag as? FlacTag)?.vorbisCommentTag
        if (vorbis != null && first(tag, FMPS).isNotEmpty()) {
            if (r == 0) vorbis.deleteField(FMPS) else vorbis.setField(FMPS, fraction(r))
        }
        val existing = firstRating(tag)
        if (r == 0) {
            try { tag.deleteField(FieldKey.RATING) } catch (_: Exception) { }
        } else {
            tag.setField(FieldKey.RATING, sameScale(existing, r))
        }
    }

    /** 1–5 if the existing value used that scale, else 0–100. */
    private fun sameScale(existing: String?, r: Int): String {
        val old = existing?.trim()?.replace(',', '.')?.toDoubleOrNull()
        return if (old != null && old > 0 && old <= 5) {
            if (r % 2 == 0) (r / 2).toString() else String.format(Locale.ROOT, "%.1f", r / 2.0)
        } else {
            (r * 10).toString()
        }
    }

    private fun fraction(r: Int) = String.format(Locale.ROOT, "%.1f", r / 10.0)

    private fun firstRating(tag: Tag): String? = try { tag.getFirst(FieldKey.RATING) } catch (_: Exception) { null }

    private fun first(tag: Tag, id: String): String = try { tag.getFirst(id).orEmpty() } catch (_: Exception) { "" }

    private fun frames(tag: AbstractID3v2Tag, id: String): List<AbstractID3v2Frame> =
        try { tag.getFields(id).filterIsInstance<AbstractID3v2Frame>() } catch (_: Exception) { emptyList() }
}
