package de.shakie.playernext.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val path: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: Long,
    val composer: String,
    val genre: String,
    val year: Int,
    val track: Int,
    val disc: Int,
    val duration: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val mimeType: String,
    val bitrate: Int,
    val isPodcast: Boolean,
    /** In an audiobooks folder or an M4B file. */
    val isAudiobook: Boolean = false,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val folder: String get() = path.substringBeforeLast('/', "")
    val effectiveAlbumArtist: String get() = albumArtist.ifBlank { artist }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val songCount: Int get() = songs.size
    val duration: Long get() = songs.sumOf { it.duration }
    val key: String get() = "$id"
}

data class Artist(
    val name: String,
    val albums: List<Album>,
    val songs: List<Song>,
) {
    val key: String get() = name
}

/** Composer and genre share the same shape: a name with songs. */
data class NamedGroup(
    val name: String,
    val songs: List<Song>,
) {
    val albums: List<Long> by lazy { songs.map { it.albumId }.distinct() }
    val duration: Long get() = songs.sumOf { it.duration }
}

data class Folder(
    val path: String,
    val name: String,
    val subfolders: List<Folder>,
    val songs: List<Song>,
) {
    /** All songs in this folder and every subfolder, folder order first. */
    val allSongs: List<Song> by lazy { songs + subfolders.flatMap { it.allSongs } }
    val totalDuration: Long get() = allSongs.sumOf { it.duration }
}

data class Video(
    val id: Long,
    val path: String,
    val title: String,
    val displayName: String,
    val duration: Long,
    val width: Int,
    val height: Int,
    val size: Long,
    val dateAdded: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

data class Playlist(
    val id: Long,
    val name: String,
    val songPaths: List<String>,
    val modified: Long,
)

enum class BrowserType(val key: String) {
    SEARCH("search"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    COMPOSERS("composers"),
    GENRES("genres"),
    PLAYLISTS("playlists"),
    FOLDERS("folders"),
    SONGS("songs"),
    VIDEOS("videos"),
    AUDIOBOOKS("audiobooks");

    companion object {
        fun fromKey(key: String?): BrowserType? = entries.firstOrNull { it.key == key }
    }
}

/** Something that can be bookmarked as favorite or targeted by an action. */
enum class ItemType(val key: String) {
    SONG("song"),
    ALBUM("album"),
    ARTIST("artist"),
    COMPOSER("composer"),
    GENRE("genre"),
    FOLDER("folder"),
    PLAYLIST("playlist"),
    SMART_PLAYLIST("smart"),
    VIDEO("video");

    companion object {
        fun fromKey(key: String?): ItemType? = entries.firstOrNull { it.key == key }
    }
}
