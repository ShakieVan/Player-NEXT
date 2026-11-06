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

    /** [rating] in half stars as stored in the file (0 = none). */
    class Tags(val values: Map<Field, String>, val artwork: ByteArray?, val header: String, val rating: Int)

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

    /** Opens the file directly when readable, otherwise through a private copy (unless [copy] is false). */
    private fun <T> withReadableFile(context: Context, song: Song, copy: Boolean = true, block: (File) -> T): T {
        val direct = File(song.path)
        if (direct.canRead()) {
            try {
                return block(direct)
            } catch (e: Exception) {
                if (e !is java.io.FileNotFoundException && e !is SecurityException) throw e
            }
        }
        if (!copy) throw java.io.FileNotFoundException(song.path)
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
        Tags(values, tag?.firstArtwork?.binaryData, header, try { RatingTags.read(tag) } catch (_: Exception) { 0 })
    }

    /**
     * Lyrics embedded in the file or from an .lrc file of the same name. Synchronized lyrics win over plain
     * ones; otherwise [preferEmbedded] decides.
     */
    fun readLyrics(context: Context, song: Song, preferEmbedded: Boolean): Lyrics? {
        val embedded = try {
            withReadableFile(context, song) { Lyrics.fieldOf(AudioFileIO.read(it).tag) }
        } catch (_: Exception) {
            null
        }?.let(Lyrics::parse)?.takeIf { it.plain.isNotBlank() }
        val lrc = File(song.path.substringBeforeLast('.') + ".lrc").takeIf { it.canRead() }?.let {
            try { Lyrics.decode(it.readBytes()) } catch (_: Exception) { null }
        }?.let(Lyrics::parse)?.takeIf { it.plain.isNotBlank() }
        val (first, second) = if (preferEmbedded) embedded to lrc else lrc to embedded
        return when {
            first?.synced == false && second?.synced == true -> second
            else -> first ?: second
        }
    }

    /** Rating in half stars (0..10) stored in the file, 0 if none; null if the file can't be read. */
    fun readRating(context: Context, song: Song, copy: Boolean = true): Int? = try {
        withReadableFile(context, song, copy) { file -> RatingTags.readFile(file, extension(song)) }
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
                if (rating != null) RatingTags.write(tag, rating)
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
