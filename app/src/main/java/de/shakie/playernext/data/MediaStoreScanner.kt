package de.shakie.playernext.data

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import de.shakie.playernext.Prefs

/** Reads audio and video metadata from MediaStore in one pass each. */
object MediaStoreScanner {

    private val audioProjection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM_ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.COMPOSER,
        MediaStore.Audio.Media.GENRE,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DISC_NUMBER,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.BITRATE,
        MediaStore.Audio.Media.IS_PODCAST,
    )

    fun scanSongs(context: Context): List<Song> {
        val selection = if (Prefs.includeAllAudio) {
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_ALARM} = 0"
        } else {
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_PODCAST} != 0 OR ${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0)"
        }
        val folders = Prefs.musicFolders.map { it.trimEnd('/') + "/" }
        val minDuration = Prefs.minDurationSec * 1000L
        val result = ArrayList<Song>(2048)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioProjection, selection, null, null
        )?.use { c ->
            val idx = audioProjection.associateWith { c.getColumnIndexOrThrow(it) }
            while (c.moveToNext()) {
                val path = c.str(idx[MediaStore.Audio.Media.DATA]!!)
                if (folders.isNotEmpty() && folders.none { path.startsWith(it) }) continue
                val duration = c.getLong(idx[MediaStore.Audio.Media.DURATION]!!)
                if (duration < minDuration) continue
                val displayName = c.str(idx[MediaStore.Audio.Media.DISPLAY_NAME]!!)
                val rawTrack = c.getInt(idx[MediaStore.Audio.Media.TRACK]!!)
                val discText = c.str(idx[MediaStore.Audio.Media.DISC_NUMBER]!!)
                val disc = discText.substringBefore('/').trim().toIntOrNull()
                    ?: (rawTrack / 1000).takeIf { it > 0 } ?: 0
                result += Song(
                    id = c.getLong(idx[MediaStore.Audio.Media._ID]!!),
                    path = path,
                    displayName = displayName,
                    title = c.str(idx[MediaStore.Audio.Media.TITLE]!!).ifBlank { displayName.substringBeforeLast('.') },
                    artist = c.str(idx[MediaStore.Audio.Media.ARTIST]!!).unknownToBlank(),
                    albumArtist = c.str(idx[MediaStore.Audio.Media.ALBUM_ARTIST]!!).unknownToBlank(),
                    album = c.str(idx[MediaStore.Audio.Media.ALBUM]!!).unknownToBlank(),
                    albumId = c.getLong(idx[MediaStore.Audio.Media.ALBUM_ID]!!),
                    composer = c.str(idx[MediaStore.Audio.Media.COMPOSER]!!),
                    genre = c.str(idx[MediaStore.Audio.Media.GENRE]!!),
                    year = c.getInt(idx[MediaStore.Audio.Media.YEAR]!!),
                    track = rawTrack % 1000,
                    disc = disc,
                    duration = duration,
                    dateAdded = c.getLong(idx[MediaStore.Audio.Media.DATE_ADDED]!!),
                    dateModified = c.getLong(idx[MediaStore.Audio.Media.DATE_MODIFIED]!!),
                    size = c.getLong(idx[MediaStore.Audio.Media.SIZE]!!),
                    mimeType = c.str(idx[MediaStore.Audio.Media.MIME_TYPE]!!),
                    bitrate = c.getInt(idx[MediaStore.Audio.Media.BITRATE]!!),
                    isPodcast = c.getInt(idx[MediaStore.Audio.Media.IS_PODCAST]!!) != 0,
                )
            }
        }
        return result
    }

    fun scanVideos(context: Context): List<Video> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = ArrayList<Video>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val displayName = c.str(3)
                result += Video(
                    id = c.getLong(0),
                    path = c.str(1),
                    title = c.str(2).ifBlank { displayName.substringBeforeLast('.') },
                    displayName = displayName,
                    duration = c.getLong(4),
                    width = c.getInt(5),
                    height = c.getInt(6),
                    size = c.getLong(7),
                    dateAdded = c.getLong(8),
                )
            }
        }
        return result
    }

    private fun Cursor.str(index: Int): String = if (isNull(index)) "" else getString(index) ?: ""

    private fun String.unknownToBlank() = if (this == MediaStore.UNKNOWN_STRING) "" else this
}
