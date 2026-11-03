package de.shakie.playernext.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.CopyOnWriteArrayList

/** Immutable view of the music library, rebuilt whenever MediaStore changes. */
class LibrarySnapshot(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val composers: List<NamedGroup>,
    val genres: List<NamedGroup>,
    val rootFolder: Folder?,
    val videos: List<Video>,
) {
    val songsById: Map<Long, Song> = songs.associateBy { it.id }
    val songsByPath: Map<String, Song> = songs.associateBy { it.path }
    val albumsById: Map<Long, Album> = albums.associateBy { it.id }
    val artistsByName: Map<String, Artist> = artists.associateBy { it.name }
    val composersByName: Map<String, NamedGroup> = composers.associateBy { it.name }
    val genresByName: Map<String, NamedGroup> = genres.associateBy { it.name }
    val folderCount: Int by lazy { countFolders(rootFolder) }

    fun findFolder(path: String): Folder? {
        fun walk(f: Folder): Folder? {
            if (f.path == path) return f
            if (!path.startsWith(f.path)) return null
            for (sub in f.subfolders) walk(sub)?.let { return it }
            return null
        }
        return rootFolder?.let(::walk)
    }

    private fun countFolders(f: Folder?): Int =
        if (f == null) 0 else (if (f.songs.isNotEmpty()) 1 else 0) + f.subfolders.sumOf { countFolders(it) }

    companion object {
        val EMPTY = LibrarySnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList())
    }
}

object Library {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    @Volatile
    var snapshot: LibrarySnapshot = LibrarySnapshot.EMPTY
        private set

    @Volatile
    var loaded = false
        private set

    private var observerRegistered = false
    private val refreshRunnable = Runnable { refresh() }

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // MediaStore fires bursts of changes during scans; coalesce them.
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, 2500)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Custom pictures changed; lists rebind their artwork. */
    fun notifyArtChanged() = listeners.forEach { it() }

    fun ensureLoaded() {
        if (!loaded) refresh()
    }

    fun refresh(onDone: (() -> Unit)? = null) {
        if (!hasPermission()) return
        if (!observerRegistered) {
            appContext.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            appContext.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            observerRegistered = true
        }
        scope.launch {
            val built = withContext(Dispatchers.IO) { build(appContext) }
            snapshot = built
            loaded = true
            listeners.forEach { it() }
            onDone?.invoke()
        }
    }

    fun hasPermission(): Boolean = de.shakie.playernext.util.Permissions.hasAudio(appContext)

    private fun build(context: Context): LibrarySnapshot {
        val unknownArtist = context.getString(R.string.unknown_artist)
        val unknownAlbum = context.getString(R.string.unknown_album)
        val unknownGenre = context.getString(R.string.unknown_genre)
        val various = context.getString(R.string.various_artists)
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

        val rawSongs = MediaStoreScanner.scanSongs(context)
        val songs = rawSongs.map { s ->
            if (s.artist.isBlank() || s.album.isBlank()) {
                s.copy(artist = s.artist.ifBlank { unknownArtist }, album = s.album.ifBlank { unknownAlbum })
            } else s
        }.sortedWith(compareBy(collator) { it.title })

        val albums = songs.groupBy { it.albumId }.map { (id, list) ->
            val ordered = list.sortedWith(compareBy<Song>({ it.disc }, { it.track }).thenBy(collator) { it.title })
            val albumArtists = ordered.map { it.albumArtist }.filter { it.isNotBlank() }
            val artist = albumArtists.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: ordered.map { it.artist }.distinct().let { if (it.size == 1) it[0] else various }
            Album(id, ordered.first().album, artist, ordered.maxOf { it.year }, ordered)
        }.sortedWith(compareBy(collator) { it.title })

        val useAlbumArtist = Prefs.artistsUseAlbumArtist
        val artistNames = if (useAlbumArtist) songs.groupBy { it.effectiveAlbumArtist } else songs.groupBy { it.artist }
        val albumsByArtist = albums.groupBy { it.artist }
        val albumsById = albums.associateBy { it.id }
        val artists = artistNames.map { (name, list) ->
            val ownAlbums = if (useAlbumArtist) albumsByArtist[name].orEmpty()
            else list.map { it.albumId }.distinct().mapNotNull { id -> albumsById[id] }
            Artist(name, ownAlbums.sortedBy { it.year }, list)
        }.let { all ->
            if (Prefs.artistsOnlyWithAlbum) all.filter { a -> albumsByArtist.containsKey(a.name) } else all
        }.sortedWith(compareBy(collator) { it.name })

        val composers = songs.filter { it.composer.isNotBlank() }
            .groupBy { it.composer }
            .map { (name, list) -> NamedGroup(name, list) }
            .sortedWith(compareBy(collator) { it.name })

        val genreMap = LinkedHashMap<String, MutableList<Song>>()
        for (s in songs) {
            val names = s.genre.split(';').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(unknownGenre) }
            for (g in names) genreMap.getOrPut(g) { ArrayList() } += s
        }
        val genres = genreMap.map { (name, list) -> NamedGroup(name, list) }.sortedWith(compareBy(collator) { it.name })

        val videos = try {
            if (de.shakie.playernext.util.Permissions.hasVideo(context)) MediaStoreScanner.scanVideos(context) else emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        return LibrarySnapshot(songs, albums, artists, composers, genres, buildFolders(songs, collator), videos)
    }

    private fun buildFolders(songs: List<Song>, collator: Collator): Folder? {
        if (songs.isEmpty()) return null
        val byFolder = songs.groupBy { it.folder }
        // Find the deepest common parent so the tree starts where music actually lives.
        var common = byFolder.keys.first()
        for (path in byFolder.keys) {
            while (!(path == common || path.startsWith("$common/"))) {
                common = common.substringBeforeLast('/', "")
                if (common.isEmpty()) break
            }
        }
        val children = HashMap<String, MutableSet<String>>()
        for (path in byFolder.keys) {
            var p = path
            while (p.length > common.length) {
                val parent = p.substringBeforeLast('/')
                children.getOrPut(parent) { HashSet() } += p
                p = parent
            }
        }
        fun make(path: String): Folder {
            val subs = children[path].orEmpty().map { make(it) }.sortedWith(compareBy(collator) { it.name })
            val own = byFolder[path].orEmpty().sortedWith(compareBy<Song>({ it.disc }, { it.track }).thenBy { it.displayName })
            return Folder(path, path.substringAfterLast('/'), subs, own)
        }
        return make(common)
    }
}
