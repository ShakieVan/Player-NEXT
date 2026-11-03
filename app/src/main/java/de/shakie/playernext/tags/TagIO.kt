package de.shakie.playernext.tags

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import de.shakie.playernext.data.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Reads and writes tags with jaudiotagger. Writing never touches the original path
 * directly: the file is edited as a private copy and streamed back through the
 * MediaStore URI the user granted write access to, then rescanned.
 */
object TagIO {
    enum class Field(val key: FieldKey) {
        TITLE(FieldKey.TITLE), ARTIST(FieldKey.ARTIST), ALBUM(FieldKey.ALBUM), ALBUM_ARTIST(FieldKey.ALBUM_ARTIST),
        COMPOSER(FieldKey.COMPOSER), GENRE(FieldKey.GENRE), YEAR(FieldKey.YEAR), TRACK(FieldKey.TRACK),
        TRACK_TOTAL(FieldKey.TRACK_TOTAL), DISC(FieldKey.DISC_NO), DISC_TOTAL(FieldKey.DISC_TOTAL),
        GROUPING(FieldKey.GROUPING), COMMENT(FieldKey.COMMENT), BPM(FieldKey.BPM), LYRICS(FieldKey.LYRICS),
    }

    class Tags(val values: Map<Field, String>, val artwork: ByteArray?, val header: String)

    sealed class ArtworkChange {
        object Keep : ArtworkChange()
        object Remove : ArtworkChange()
        class Replace(val data: ByteArray, val mime: String) : ArtworkChange()
    }

    init {
        TagOptionSingleton.getInstance().isAndroid = true
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    private fun extension(song: Song) = song.path.substringAfterLast('.', "mp3").lowercase()

    /** Opens the file directly when readable, otherwise through a private copy. */
    private fun <T> withReadableFile(context: Context, song: Song, block: (File) -> T): T {
        val direct = File(song.path)
        if (direct.canRead()) {
            try {
                return block(direct)
            } catch (e: Exception) {
                if (e !is java.io.FileNotFoundException && e !is SecurityException) throw e
            }
        }
        val tmp = File(context.cacheDir, "tagread.${extension(song)}")
        context.contentResolver.openInputStream(song.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try {
            return block(tmp)
        } finally {
            tmp.delete()
        }
    }

    fun read(context: Context, song: Song): Tags = withReadableFile(context, song) { file ->
        val af = AudioFileIO.read(file)
        val tag = af.tag
        val values = HashMap<Field, String>()
        if (tag != null) {
            for (f in Field.entries) {
                val v = try { tag.getFirst(f.key) } catch (_: Exception) { "" }
                if (!v.isNullOrEmpty()) values[f] = v
            }
        }
        val h = af.audioHeader
        val header = buildString {
            append(h.format ?: extension(song).uppercase())
            append(", ").append(h.trackLength).append(" s")
            h.bitsPerSample.takeIf { it > 0 }?.let { append(", ").append(it).append(" bit") }
            append(", ").append(h.sampleRate).append(" Hz")
            append(", ").append(h.bitRate).append(" kBps")
            append(", ").append(h.channels)
        }
        Tags(values, tag?.firstArtwork?.binaryData, header)
    }

    fun readLyrics(context: Context, song: Song, preferEmbedded: Boolean): String? {
        val embedded = try {
            withReadableFile(context, song) { AudioFileIO.read(it).tag?.getFirst(FieldKey.LYRICS) }
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() }
        val lrc = File(song.path.substringBeforeLast('.') + ".lrc").takeIf { it.canRead() }?.let {
            try { it.readText() } catch (_: Exception) { null }
        }?.let(::stripLrcTimestamps)?.takeIf { it.isNotBlank() }
        return if (preferEmbedded) embedded ?: lrc else lrc ?: embedded
    }

    private fun stripLrcTimestamps(text: String): String = text.lines()
        .filterNot { it.matches(Regex("^\\[(ar|ti|al|by|offset|length|re|ve):.*]$")) }
        .joinToString("\n") { it.replace(Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]"), "").trim() }
        .trim()

    /** Rating in half stars (0..10) from the file, if it carries one. */
    fun readRating(context: Context, song: Song): Int? = try {
        withReadableFile(context, song) { file ->
            val raw = AudioFileIO.read(file).tag?.getFirst(FieldKey.RATING)?.toIntOrNull() ?: return@withReadableFile null
            if (raw <= 0) null
            else if (extension(song) == "mp3") when {
                raw >= 224 -> 10; raw >= 160 -> 8; raw >= 96 -> 6; raw >= 32 -> 4; else -> 2
            } else (raw.coerceAtMost(100) / 10)
        }
    } catch (_: Exception) {
        null
    }

    /** Asks the user for write access to [songs]; true when granted. */
    suspend fun requestWriteAccess(activity: de.shakie.playernext.ui.BaseActivity, songs: List<Song>): Boolean {
        val request = MediaStore.createWriteRequest(activity.contentResolver, songs.map { it.uri })
        return suspendCoroutine { cont -> activity.runIntentSender(request.intentSender) { cont.resume(it) } }
    }

    /**
     * Applies [changes] (null value = clear field) and [artwork] to each song. Returns the
     * number of files written; throws on the first failure.
     */
    fun write(
        context: Context, songs: List<Song>, changes: Map<Field, String?>, artwork: ArtworkChange,
        rating: Int? = null, onProgress: (Int) -> Unit = {},
    ): Int {
        var done = 0
        for (song in songs) {
            val tmp = File(context.cacheDir, "tagwrite_${song.id}.${extension(song)}")
            try {
                context.contentResolver.openInputStream(song.uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val af = AudioFileIO.read(tmp)
                val tag = af.tagOrCreateAndSetDefault
                for ((field, value) in changes) {
                    if (value.isNullOrEmpty()) {
                        try { tag.deleteField(field.key) } catch (_: Exception) { }
                    } else {
                        tag.setField(field.key, value)
                    }
                }
                when (artwork) {
                    ArtworkChange.Keep -> Unit
                    ArtworkChange.Remove -> tag.deleteArtworkField()
                    is ArtworkChange.Replace -> {
                        val art = ArtworkFactory.getNew()
                        art.binaryData = artwork.data
                        art.mimeType = artwork.mime
                        art.pictureType = 3
                        tag.deleteArtworkField()
                        tag.setField(art)
                    }
                }
                if (rating != null) {
                    val value = if (extension(song) == "mp3") intArrayOf(0, 1, 1, 64, 64, 128, 128, 196, 196, 255, 255)[rating.coerceIn(0, 10)]
                    else rating * 10
                    if (value == 0) tag.deleteField(FieldKey.RATING) else tag.setField(FieldKey.RATING, value.toString())
                }
                af.commit()
                context.contentResolver.openOutputStream(song.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                done++
                onProgress(done)
            } finally {
                tmp.delete()
            }
        }
        rescan(context, songs.map { it.path })
        return done
    }

    fun rescan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    fun uriOf(song: Song): Uri = song.uri
}
