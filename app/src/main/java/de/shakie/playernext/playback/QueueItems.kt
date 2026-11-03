package de.shakie.playernext.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.Song
import java.util.concurrent.atomic.AtomicLong

/**
 * Queue entries carry "songId#uid" as mediaId so the same song can appear twice in the
 * queue and still be told apart when reordering or shuffling.
 */
object QueueItems {
    private val uid = AtomicLong(System.nanoTime())
    const val EXTRA_PATH = "pn.path"
    const val ART_SCHEME = "playernext"

    fun songIdOf(mediaId: String?): Long? = mediaId?.substringBefore('#')?.removePrefix("song:")?.toLongOrNull()

    fun songOf(item: MediaItem?): Song? = songIdOf(item?.mediaId)?.let { Library.snapshot.songsById[it] }

    fun request(song: Song): MediaItem = MediaItem.Builder().setMediaId(song.id.toString()).build()

    fun build(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId("${song.id}#${uid.incrementAndGet()}")
        .setUri(song.uri)
        .setMimeType(song.mimeType.ifBlank { null })
        .setMediaMetadata(metadata(song))
        .build()

    fun metadata(song: Song, browsable: Boolean = false): MediaMetadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setAlbumTitle(song.album)
        .setAlbumArtist(song.effectiveAlbumArtist)
        .setComposer(song.composer.ifBlank { null })
        .setGenre(song.genre.ifBlank { null })
        .setTrackNumber(song.track.takeIf { it > 0 })
        .setDiscNumber(song.disc.takeIf { it > 0 })
        .setReleaseYear(song.year.takeIf { it > 0 })
        .setDurationMs(song.duration)
        .setArtworkUri(artUri(song))
        .setIsBrowsable(browsable)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(Bundle().apply { putString(EXTRA_PATH, song.path) })
        .build()

    fun artUri(song: Song): Uri = Uri.parse("$ART_SCHEME://song/${song.id}/${song.albumId}")
    fun albumArtUri(albumId: Long): Uri = Uri.parse("$ART_SCHEME://album/$albumId")

    /** Turns controller requests (mediaId only) into playable items. */
    fun resolve(item: MediaItem): MediaItem? {
        if (item.localConfiguration != null && item.mediaId.contains('#')) return item
        val id = songIdOf(item.mediaId) ?: return null
        val song = Library.snapshot.songsById[id]
        return if (song != null) build(song) else MediaItem.Builder()
            .setMediaId("$id#${uid.incrementAndGet()}")
            .setUri(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id))
            .build()
    }
}
