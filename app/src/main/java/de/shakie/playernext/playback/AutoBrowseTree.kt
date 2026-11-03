package de.shakie.playernext.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.shakie.playernext.R
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.SmartPlaylist
import de.shakie.playernext.data.Song
import de.shakie.playernext.data.StatsStore

/** Browse tree for Android Auto and other media browsers. */
class AutoBrowseTree(private val context: Context) {
    companion object {
        const val ROOT = "root"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val PLAYLISTS = "playlists"
        const val SONGS = "songs"
    }

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name))

    fun children(parentId: String): List<MediaItem> {
        val lib = Library.snapshot
        return when {
            parentId == ROOT -> listOf(
                folder(ALBUMS, context.getString(R.string.browser_albums)),
                folder(ARTISTS, context.getString(R.string.browser_artists)),
                folder(PLAYLISTS, context.getString(R.string.browser_playlists)),
                folder(SONGS, context.getString(R.string.browser_songs)),
            )
            parentId == ALBUMS -> lib.albums.map {
                folder("album:${it.id}", it.title, it.artist, QueueItems.albumArtUri(it.id), MediaMetadata.MEDIA_TYPE_ALBUM)
            }
            parentId == ARTISTS -> lib.artists.map { folder("artist:${it.name}", it.name, null, null, MediaMetadata.MEDIA_TYPE_ARTIST) }
            parentId == PLAYLISTS -> SmartPlaylist.BUILTIN_KEYS.map { key ->
                folder("smart:$key", smartName(key), null, null, MediaMetadata.MEDIA_TYPE_PLAYLIST)
            } + PlaylistStore.all().map { folder("playlist:${it.id}", it.name, null, null, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            parentId == SONGS -> songs(SONGS, lib.songs.take(500))
            else -> songs(parentId, songsOf(parentId))
        }
    }

    fun songsOf(parentId: String): List<Song> {
        val lib = Library.snapshot
        val value = parentId.substringAfter(':')
        return when (parentId.substringBefore(':')) {
            "album" -> lib.albumsById[value.toLongOrNull()]?.songs.orEmpty()
            "artist" -> lib.artistsByName[value]?.songs.orEmpty()
            "playlist" -> PlaylistStore.byId(value.toLongOrNull() ?: -1)?.songPaths?.mapNotNull { lib.songsByPath[it] }.orEmpty()
            "smart" -> SmartPlaylist.builtin(value, smartName(value)).evaluate(lib, StatsStore.all())
            SONGS -> lib.songs
            else -> emptyList()
        }
    }

    private fun songs(parentId: String, list: List<Song>) = list.map { s ->
        MediaItem.Builder().setMediaId("$parentId|song:${s.id}").setMediaMetadata(QueueItems.metadata(s)).build()
    }

    private fun smartName(key: String) = when (key) {
        SmartPlaylist.RECENTLY_ADDED -> context.getString(R.string.smart_recently_added)
        SmartPlaylist.TOP_RATED -> context.getString(R.string.smart_top_rated)
        SmartPlaylist.MOST_PLAYED -> context.getString(R.string.smart_most_played)
        SmartPlaylist.RECENTLY_PLAYED -> context.getString(R.string.smart_recently_played)
        SmartPlaylist.LEAST_PLAYED -> context.getString(R.string.smart_least_played)
        else -> context.getString(R.string.smart_podcasts)
    }

    private fun folder(
        id: String, title: String, subtitle: String? = null, art: android.net.Uri? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setArtworkUri(art)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build()
        )
        .build()
}
