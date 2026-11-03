package de.shakie.playernext.ui.browse

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.view.ActionMode
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.data.BrowserType
import de.shakie.playernext.data.FavoritesStore
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.LibrarySnapshot
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.SmartPlaylist
import de.shakie.playernext.data.SmartPlaylistStore
import de.shakie.playernext.data.Song
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.playback.PlayerHub
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.ui.common.Target
import de.shakie.playernext.ui.playlists.PlaylistIO
import de.shakie.playernext.ui.playlists.SmartPlaylistEditorActivity
import de.shakie.playernext.ui.tags.TagEditorActivity
import de.shakie.playernext.ui.video.VideoPlayerActivity
import de.shakie.playernext.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/** Host interface implemented by MainActivity. */
interface BrowserHost {
    fun open(fragment: BrowseFragment)
    fun onBrowserShown(fragment: BrowseFragment)
    fun startSelectionMode(callback: ActionMode.Callback): ActionMode?
    fun openNowPlaying()
}

/**
 * One fragment class for all of PlayerPro's browsers and detail lists. [kind] selects what
 * is shown, [key] the album/artist/genre/playlist/folder it is restricted to.
 */
class BrowseFragment : Fragment(R.layout.fragment_browse), RowCallbacks {

    companion object {
        const val ALBUMS = "albums"
        const val ALBUMS_OF_ARTIST = "albums_artist"
        const val ALBUMS_OF_GENRE = "albums_genre"
        const val ARTISTS = "artists"
        const val ARTISTS_OF_GENRE = "artists_genre"
        const val COMPOSERS = "composers"
        const val GENRES = "genres"
        const val PLAYLISTS = "playlists"
        const val FOLDERS = "folders"
        const val SONGS = "songs"
        const val VIDEOS = "videos"
        const val SEARCH = "search"
        const val TRACKS_ALBUM = "tracks_album"
        const val TRACKS_ARTIST = "tracks_artist"
        const val TRACKS_COMPOSER = "tracks_composer"
        const val TRACKS_GENRE = "tracks_genre"
        const val TRACKS_PLAYLIST = "tracks_playlist"
        const val TRACKS_SMART = "tracks_smart"

        fun create(kind: String, key: String? = null, query: String? = null) =
            BrowseFragment().apply { arguments = bundleOf("kind" to kind, "key" to key, "query" to query) }

        fun forBrowser(type: BrowserType) = create(
            when (type) {
                BrowserType.SEARCH -> SEARCH
                BrowserType.ALBUMS -> ALBUMS
                BrowserType.ARTISTS -> ARTISTS
                BrowserType.COMPOSERS -> COMPOSERS
                BrowserType.GENRES -> GENRES
                BrowserType.PLAYLISTS -> PLAYLISTS
                BrowserType.FOLDERS -> FOLDERS
                BrowserType.SONGS -> SONGS
                BrowserType.VIDEOS -> VIDEOS
            }
        )

        fun iconFor(type: BrowserType) = when (type) {
            BrowserType.SEARCH -> R.drawable.ic_search
            BrowserType.ALBUMS -> R.drawable.ic_album
            BrowserType.ARTISTS -> R.drawable.ic_artist
            BrowserType.COMPOSERS -> R.drawable.ic_composer
            BrowserType.GENRES -> R.drawable.ic_genre
            BrowserType.PLAYLISTS -> R.drawable.ic_playlist
            BrowserType.FOLDERS -> R.drawable.ic_folder
            BrowserType.SONGS -> R.drawable.ic_music_note
            BrowserType.VIDEOS -> R.drawable.ic_video
        }

        fun titleFor(type: BrowserType) = when (type) {
            BrowserType.SEARCH -> R.string.browser_search
            BrowserType.ALBUMS -> R.string.browser_albums
            BrowserType.ARTISTS -> R.string.browser_artists
            BrowserType.COMPOSERS -> R.string.browser_composers
            BrowserType.GENRES -> R.string.browser_genres
            BrowserType.PLAYLISTS -> R.string.browser_playlists
            BrowserType.FOLDERS -> R.string.browser_folders
            BrowserType.SONGS -> R.string.browser_songs
            BrowserType.VIDEOS -> R.string.browser_videos
        }
    }

    val kind: String get() = requireArguments().getString("kind")!!
    val key: String? get() = requireArguments().getString("key")

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var searchField: EditText
    private lateinit var adapter: RowAdapter
    private var layoutManager: GridLayoutManager? = null
    private var allRows: List<Row> = emptyList()
    private var viewSongs: List<Song> = emptyList()
    private var filter = ""
    private var actionMode: ActionMode? = null
    private val selected = LinkedHashSet<String>()
    private var loadJob: Job? = null
    private var touchHelper: ItemTouchHelper? = null
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private val host get() = activity as? BrowserHost
    private val base get() = requireActivity() as BaseActivity

    /** Browser this fragment belongs to (for the drawer highlight). */
    val browserType: BrowserType
        get() = when (kind) {
            ALBUMS, ALBUMS_OF_ARTIST, ALBUMS_OF_GENRE, TRACKS_ALBUM -> if (kind == ALBUMS || kind == TRACKS_ALBUM) BrowserType.ALBUMS else rootTypeOfKey()
            ARTISTS, TRACKS_ARTIST -> BrowserType.ARTISTS
            ARTISTS_OF_GENRE, GENRES, TRACKS_GENRE -> BrowserType.GENRES
            COMPOSERS, TRACKS_COMPOSER -> BrowserType.COMPOSERS
            PLAYLISTS, TRACKS_PLAYLIST, TRACKS_SMART -> BrowserType.PLAYLISTS
            FOLDERS -> BrowserType.FOLDERS
            SONGS -> BrowserType.SONGS
            VIDEOS -> BrowserType.VIDEOS
            else -> BrowserType.SEARCH
        }

    private fun rootTypeOfKey() = if (kind == ALBUMS_OF_ARTIST) BrowserType.ARTISTS else BrowserType.GENRES

    val isRoot: Boolean get() = key == null && kind in setOf(ALBUMS, ARTISTS, COMPOSERS, GENRES, PLAYLISTS, FOLDERS, SONGS, VIDEOS, SEARCH)

    val iconRes: Int get() = iconFor(browserType)

    val title: String
        get() {
            val lib = Library.snapshot
            return when (kind) {
                TRACKS_ALBUM -> lib.albumsById[key?.toLongOrNull()]?.title ?: ""
                ALBUMS_OF_ARTIST, TRACKS_ARTIST, TRACKS_COMPOSER, TRACKS_GENRE, ALBUMS_OF_GENRE, ARTISTS_OF_GENRE -> key.orEmpty()
                TRACKS_PLAYLIST -> PlaylistStore.byId(key?.toLongOrNull() ?: -1)?.name.orEmpty()
                TRACKS_SMART -> smartPlaylist()?.name.orEmpty()
                FOLDERS -> key?.let { k -> lib.rootFolder?.let { root -> k.removePrefix(root.path.substringBeforeLast('/')) } }
                    ?: getString(R.string.browser_folders)
                else -> getString(titleFor(browserType))
            }
        }

    private val layoutPref: String
        get() = when (kind) {
            ALBUMS, ALBUMS_OF_ARTIST, ALBUMS_OF_GENRE -> Prefs.layoutFor(BrowserType.ALBUMS)
            ARTISTS, ARTISTS_OF_GENRE -> Prefs.layoutFor(BrowserType.ARTISTS)
            COMPOSERS -> Prefs.layoutFor(BrowserType.COMPOSERS)
            GENRES -> Prefs.layoutFor(BrowserType.GENRES)
            else -> "list"
        }

    // --- lifecycle --------------------------------------------------------------------------

    private val libraryListener: () -> Unit = { reload() }
    private val playerListener: () -> Unit = { updatePlaying() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        empty = view.findViewById(R.id.empty)
        searchField = view.findViewById(R.id.search_field)
        adapter = RowAdapter(this)
        adapter.gridTiles = layoutPref != "list"
        adapter.draggable = kind == TRACKS_PLAYLIST
        list.adapter = adapter
        setupLayoutManager()

        if (kind == SEARCH) setupSearch(requireArguments().getString("query"))
        if (kind == PLAYLISTS) view.findViewById<ImageButton>(R.id.fab).apply {
            visibility = View.VISIBLE
            setOnClickListener { newPlaylistDialog() }
        }
        if (kind == TRACKS_PLAYLIST) setupDrag()

        Library.addListener(libraryListener)
        StatsStore.addListener(libraryListener)
        PlaylistStore.addListener(libraryListener)
        SmartPlaylistStore.addListener(libraryListener)
        PlayerHub.addListener(playerListener)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        reload()
    }

    override fun onResume() {
        super.onResume()
        host?.onBrowserShown(this)
        updatePlaying()
    }

    override fun onDestroyView() {
        Library.removeListener(libraryListener)
        StatsStore.removeListener(libraryListener)
        PlaylistStore.removeListener(libraryListener)
        SmartPlaylistStore.removeListener(libraryListener)
        PlayerHub.removeListener(playerListener)
        actionMode?.finish()
        super.onDestroyView()
    }

    private fun setupLayoutManager() {
        val widthDp = resources.configuration.screenWidthDp
        val tileDp = when (layoutPref) {
            "grid_small" -> 120
            "grid_xsmall" -> 88
            else -> 175
        }
        val spans = if (adapter.gridTiles) (widthDp / tileDp).coerceAtLeast(2) else 1
        val lm = GridLayoutManager(requireContext(), spans)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position < adapter.itemCount && adapter.getItemViewType(position) == RowAdapter.TYPE_TILE_GRID) 1 else spans
        }
        layoutManager = lm
        list.layoutManager = lm
    }

    fun setFilter(text: String) {
        filter = text
        applyFilter()
    }

    private fun reload() {
        if (view == null) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val query = if (kind == SEARCH) searchField.text.toString() else ""
            val result = withContext(Dispatchers.Default) { build(Library.snapshot, query) }
            allRows = result.first
            viewSongs = result.second
            applyFilter()
            host?.onBrowserShown(this@BrowseFragment)
        }
    }

    private fun applyFilter() {
        val rows = if (filter.isBlank()) allRows else allRows.filter { r ->
            when (r) {
                is Row.Header, is Row.Section -> true
                is Row.Tile -> r.title.contains(filter, true) || r.subtitle?.contains(filter, true) == true
                is Row.SongRow -> r.song.title.contains(filter, true) || r.subtitle.contains(filter, true)
                is Row.FolderRow -> r.folder.name.contains(filter, true)
                is Row.VideoRow -> r.video.title.contains(filter, true)
            }
        }
        adapter.submitList(rows)
        val showEmpty = rows.none { it !is Row.Header && it !is Row.Section }
        empty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        empty.text = when {
            !Library.loaded -> getString(R.string.loading)
            kind == SEARCH && searchField.text.isNullOrBlank() -> ""
            kind == SEARCH -> getString(R.string.empty_search)
            Library.snapshot.songs.isEmpty() && kind != VIDEOS -> getString(R.string.empty_library)
            else -> getString(R.string.empty_list)
        }
    }

    private fun updatePlaying() {
        if (!::adapter.isInitialized) return
        adapter.playingSongId = PlayerHub.currentSong()?.id ?: -1
    }

    // --- building rows -----------------------------------------------------------------------

    private fun build(lib: LibrarySnapshot, query: String): Pair<List<Row>, List<Song>> {
        val res = requireContext().resources
        fun songsLabel(n: Int) = res.getQuantityString(R.plurals.n_songs, n, n)
        fun albumsLabel(n: Int) = res.getQuantityString(R.plurals.n_albums, n, n)
        val listMode = layoutPref == "list"

        fun albumTile(a: de.shakie.playernext.data.Album) = Row.Tile(
            ItemType.ALBUM, a.id.toString(), a.title, if (listMode) a.artist else null,
            if (listMode) Format.duration(a.duration) else null, ArtKey.Album(a.id), R.drawable.placeholder_album,
            a.songs.mapTo(HashSet()) { it.id }
        )
        fun artistTile(a: de.shakie.playernext.data.Artist) = Row.Tile(
            ItemType.ARTIST, a.name, a.name, if (listMode) "${albumsLabel(a.albums.size)}, ${songsLabel(a.songs.size)}" else null,
            null, ArtKey.Artist(a.name, a.albums.map { it.id }), R.drawable.placeholder_artist, a.songs.mapTo(HashSet()) { it.id }
        )
        fun groupTile(type: ItemType, g: de.shakie.playernext.data.NamedGroup, placeholder: Int) = Row.Tile(
            type, g.name, g.name, if (listMode) songsLabel(g.songs.size) else null, null,
            ArtKey.Collage("${type.key}:${g.name}", g.albums), placeholder, g.songs.mapTo(HashSet()) { it.id }
        )
        fun songRows(songs: List<Song>, subtitle: (Song) -> String, art: Boolean = false) =
            songs.mapIndexed { i, s -> Row.SongRow(s, subtitle(s), i, art) }
        val artistAlbum: (Song) -> String = { s -> if (s.album.isNotBlank()) "${s.artist} · ${s.album}" else s.artist }

        return when (kind) {
            ALBUMS -> sortAlbums(lib.albums).map(::albumTile) to emptyList()
            ALBUMS_OF_ARTIST -> {
                val artist = lib.artistsByName[key] ?: return emptyList<Row>() to emptyList()
                val songs = artist.songs
                val header = Row.Header(artist.name, albumsLabel(artist.albums.size), "${songsLabel(songs.size)} · ${Format.duration(songs.sumOf { it.duration })}",
                    songs.size, ArtKey.Artist(artist.name, artist.albums.map { it.id }), R.drawable.placeholder_artist)
                (listOf<Row>(header) + sortAlbums(artist.albums).map(::albumTile)) to orderGroupSongs(songs)
            }
            ALBUMS_OF_GENRE -> {
                val genre = lib.genresByName[key] ?: return emptyList<Row>() to emptyList()
                val albums = genre.albums.mapNotNull { lib.albumsById[it] }
                val header = Row.Header(genre.name, albumsLabel(albums.size), "${songsLabel(genre.songs.size)} · ${Format.duration(genre.duration)}",
                    genre.songs.size, ArtKey.Collage("genre:${genre.name}", genre.albums), R.drawable.placeholder_album)
                (listOf<Row>(header) + sortAlbums(albums).map(::albumTile)) to orderGroupSongs(genre.songs)
            }
            ARTISTS -> sortArtists(lib.artists).map(::artistTile) to emptyList()
            ARTISTS_OF_GENRE -> {
                val genre = lib.genresByName[key] ?: return emptyList<Row>() to emptyList()
                val names = genre.songs.map { if (Prefs.artistsUseAlbumArtist) it.effectiveAlbumArtist else it.artist }.toSet()
                sortArtists(lib.artists.filter { it.name in names }).map(::artistTile) to genre.songs
            }
            COMPOSERS -> sortGroups(lib.composers, "composers").map { groupTile(ItemType.COMPOSER, it, R.drawable.placeholder_artist) } to emptyList()
            GENRES -> sortGroups(lib.genres, "genres").map { groupTile(ItemType.GENRE, it, R.drawable.placeholder_album) } to emptyList()
            PLAYLISTS -> buildPlaylists(lib) to emptyList()
            FOLDERS -> {
                val folder = if (key == null) lib.rootFolder else lib.findFolder(key!!)
                if (folder == null) emptyList<Row>() to emptyList() else {
                    val songs = sortFolderSongs(folder.songs)
                    val showTitle = Prefs.folderShowTitleTag
                    (folder.subfolders.map { Row.FolderRow(it) } + songs.mapIndexed { i, s ->
                        Row.SongRow(if (showTitle) s else s.copy(title = s.displayName), s.artist, i)
                    }) to songs
                }
            }
            SONGS -> {
                val songs = sortSongs(lib.songs, "songs", "title")
                songRows(songs, artistAlbum) to songs
            }
            VIDEOS -> sortVideos(lib.videos).map { v ->
                Row.VideoRow(if (Prefs.videoShowTitleTag) v else v.copy(title = v.displayName))
            } to emptyList()
            SEARCH -> buildSearch(lib, query) to emptyList()
            TRACKS_ALBUM -> {
                val album = lib.albumsById[key?.toLongOrNull()] ?: return emptyList<Row>() to emptyList()
                val songs = sortSongs(album.songs, "album_tracks", "track")
                val detail = if (album.year > 0) getString(R.string.released_in, album.year) else Format.duration(album.duration)
                val header = Row.Header(album.title, album.artist, detail, songs.size, ArtKey.Album(album.id), R.drawable.placeholder_album)
                (listOf<Row>(header) + songRows(songs, { it.artist })) to songs
            }
            TRACKS_ARTIST, TRACKS_COMPOSER, TRACKS_GENRE -> {
                val (songs, art, placeholder) = when (kind) {
                    TRACKS_ARTIST -> lib.artistsByName[key].let { a ->
                        Triple(a?.songs.orEmpty(), a?.let { ArtKey.Artist(it.name, it.albums.map { al -> al.id }) }, R.drawable.placeholder_artist)
                    }
                    TRACKS_COMPOSER -> lib.composersByName[key].let { g ->
                        Triple(g?.songs.orEmpty(), g?.let { ArtKey.Collage("composer:${it.name}", it.albums) }, R.drawable.placeholder_artist)
                    }
                    else -> lib.genresByName[key].let { g ->
                        Triple(g?.songs.orEmpty(), g?.let { ArtKey.Collage("genre:${it.name}", it.albums) }, R.drawable.placeholder_album)
                    }
                }
                val ordered = sortSongs(songs, "group_tracks", "album")
                val albums = ordered.map { it.albumId }.distinct().size
                val header = Row.Header(key.orEmpty(), albumsLabel(albums), "${songsLabel(ordered.size)} · ${Format.duration(ordered.sumOf { it.duration })}",
                    ordered.size, art, placeholder)
                (listOf<Row>(header) + songRows(ordered, artistAlbum)) to ordered
            }
            TRACKS_PLAYLIST -> {
                val p = PlaylistStore.byId(key?.toLongOrNull() ?: -1) ?: return emptyList<Row>() to emptyList()
                val raw = p.songPaths.mapNotNull { lib.songsByPath[it] }
                val songs = sortSongs(raw, "playlist_tracks", "order")
                adapterDraggableFor(Prefs.sortKey("playlist_tracks", "order") == "order")
                val header = Row.Header(p.name, getString(R.string.browser_playlists), "${songsLabel(songs.size)} · ${Format.duration(songs.sumOf { it.duration })}",
                    songs.size, ArtKey.Collage("playlist:${p.id}", songs.map { it.albumId }.distinct()), R.drawable.placeholder_playlist)
                (listOf<Row>(header) + songRows(songs, artistAlbum, art = true)) to songs
            }
            TRACKS_SMART -> {
                val sp = smartPlaylist() ?: return emptyList<Row>() to emptyList()
                val raw = sp.evaluate(lib, StatsStore.all())
                val songs = sortSongs(raw, "playlist_tracks", "order")
                val header = Row.Header(sp.name, getString(R.string.browser_playlists), "${songsLabel(songs.size)} · ${Format.duration(songs.sumOf { it.duration })}",
                    songs.size, ArtKey.Collage("smart:${sp.builtinKey ?: sp.id}", songs.map { it.albumId }.distinct()), R.drawable.placeholder_smart)
                (listOf<Row>(header) + songRows(songs, artistAlbum, art = true)) to songs
            }
            else -> emptyList<Row>() to emptyList()
        }
    }

    private fun adapterDraggableFor(enabled: Boolean) {
        list.post { adapter.draggable = enabled }
    }

    private fun smartPlaylist(): SmartPlaylist? {
        val k = key ?: return null
        return if (k in SmartPlaylist.BUILTIN_KEYS) SmartPlaylist.builtin(k, smartName(k))
        else SmartPlaylistStore.byId(k.toLongOrNull() ?: -1)
    }

    fun smartName(k: String) = getString(
        when (k) {
            SmartPlaylist.RECENTLY_ADDED -> R.string.smart_recently_added
            SmartPlaylist.TOP_RATED -> R.string.smart_top_rated
            SmartPlaylist.MOST_PLAYED -> R.string.smart_most_played
            SmartPlaylist.RECENTLY_PLAYED -> R.string.smart_recently_played
            SmartPlaylist.LEAST_PLAYED -> R.string.smart_least_played
            else -> R.string.smart_podcasts
        }
    )

    private fun buildPlaylists(lib: LibrarySnapshot): List<Row> {
        val res = requireContext().resources
        val stats = StatsStore.all()
        val rows = ArrayList<Row>()
        for (k in SmartPlaylist.BUILTIN_KEYS) {
            if (k !in Prefs.smartPlaylists) continue
            val sp = SmartPlaylist.builtin(k, smartName(k))
            val songs = sp.evaluate(lib, stats)
            rows += Row.Tile(ItemType.SMART_PLAYLIST, k, sp.name, res.getQuantityString(R.plurals.n_songs, songs.size, songs.size),
                Format.duration(songs.sumOf { it.duration }), ArtKey.Collage("smart:$k", songs.map { it.albumId }.distinct().take(8)),
                R.drawable.placeholder_smart)
        }
        for (sp in SmartPlaylistStore.all()) {
            val songs = sp.evaluate(lib, stats)
            rows += Row.Tile(ItemType.SMART_PLAYLIST, sp.id.toString(), sp.name, res.getQuantityString(R.plurals.n_songs, songs.size, songs.size),
                Format.duration(songs.sumOf { it.duration }), ArtKey.Collage("smart:${sp.id}", songs.map { it.albumId }.distinct().take(8)),
                R.drawable.placeholder_smart)
        }
        for (p in PlaylistStore.all()) {
            val songs = p.songPaths.mapNotNull { lib.songsByPath[it] }
            rows += Row.Tile(ItemType.PLAYLIST, p.id.toString(), p.name, res.getQuantityString(R.plurals.n_songs, songs.size, songs.size),
                Format.duration(songs.sumOf { it.duration }), ArtKey.Collage("playlist:${p.id}", songs.map { it.albumId }.distinct().take(8)),
                R.drawable.placeholder_playlist)
        }
        return rows
    }

    private fun buildSearch(lib: LibrarySnapshot, q: String): List<Row> {
        if (q.isBlank()) return emptyList()
        val res = requireContext().resources
        val rows = ArrayList<Row>()
        fun section(title: Int, items: List<Row>) {
            if (items.isEmpty()) return
            rows += Row.Section(getString(title) + " (${items.size})")
            rows += items
        }
        section(R.string.browser_artists, lib.artists.filter { it.name.contains(q, true) }.map { a ->
            Row.Tile(ItemType.ARTIST, a.name, a.name, res.getQuantityString(R.plurals.n_albums, a.albums.size, a.albums.size), null,
                ArtKey.Artist(a.name, a.albums.map { it.id }), R.drawable.placeholder_artist, a.songs.mapTo(HashSet()) { it.id })
        })
        section(R.string.browser_albums, lib.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.map { a ->
            Row.Tile(ItemType.ALBUM, a.id.toString(), a.title, a.artist, null, ArtKey.Album(a.id), R.drawable.placeholder_album,
                a.songs.mapTo(HashSet()) { it.id })
        })
        section(R.string.browser_composers, lib.composers.filter { it.name.contains(q, true) }.map { g ->
            Row.Tile(ItemType.COMPOSER, g.name, g.name, res.getQuantityString(R.plurals.n_songs, g.songs.size, g.songs.size), null,
                ArtKey.Collage("composer:${g.name}", g.albums), R.drawable.placeholder_artist)
        })
        section(R.string.browser_genres, lib.genres.filter { it.name.contains(q, true) }.map { g ->
            Row.Tile(ItemType.GENRE, g.name, g.name, res.getQuantityString(R.plurals.n_songs, g.songs.size, g.songs.size), null,
                ArtKey.Collage("genre:${g.name}", g.albums), R.drawable.placeholder_album)
        })
        section(R.string.browser_playlists, PlaylistStore.all().filter { it.name.contains(q, true) }.map { p ->
            Row.Tile(ItemType.PLAYLIST, p.id.toString(), p.name, res.getQuantityString(R.plurals.n_songs, p.songPaths.size, p.songPaths.size),
                null, null, R.drawable.placeholder_playlist)
        })
        val songs = lib.songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
        section(R.string.browser_songs, songs.mapIndexed { i, s -> Row.SongRow(s, "${s.artist} · ${s.album}", i, true) })
        section(R.string.browser_videos, lib.videos.filter { it.title.contains(q, true) }.map { Row.VideoRow(it) })
        return rows
    }

    // --- sorting -----------------------------------------------------------------------------

    private fun sortAlbums(albums: List<de.shakie.playernext.data.Album>): List<de.shakie.playernext.data.Album> {
        val sorted = when (Prefs.sortKey("albums", "title")) {
            "artist" -> albums.sortedWith(compareBy<de.shakie.playernext.data.Album, String>(collator) { it.artist }.thenBy { it.year })
            "year" -> albums.sortedWith(compareBy<de.shakie.playernext.data.Album> { it.year }.thenBy(collator) { it.title })
            "count" -> albums.sortedByDescending { it.songCount }
            else -> albums.sortedWith(compareBy(collator) { it.title })
        }
        return if (Prefs.sortReverse("albums")) sorted.reversed() else sorted
    }

    private fun sortArtists(artists: List<de.shakie.playernext.data.Artist>): List<de.shakie.playernext.data.Artist> {
        val sorted = when (Prefs.sortKey("artists", "name")) {
            "album_count" -> artists.sortedByDescending { it.albums.size }
            "count" -> artists.sortedByDescending { it.songs.size }
            else -> artists.sortedWith(compareBy(collator) { it.name })
        }
        return if (Prefs.sortReverse("artists")) sorted.reversed() else sorted
    }

    private fun sortGroups(groups: List<de.shakie.playernext.data.NamedGroup>, scope: String): List<de.shakie.playernext.data.NamedGroup> {
        val sorted = when (Prefs.sortKey(scope, "name")) {
            "count" -> groups.sortedByDescending { it.songs.size }
            else -> groups.sortedWith(compareBy(collator) { it.name })
        }
        return if (Prefs.sortReverse(scope)) sorted.reversed() else sorted
    }

    private fun sortSongs(songs: List<Song>, scope: String, default: String): List<Song> {
        val sorted = when (Prefs.sortKey(scope, default)) {
            "order" -> songs
            "track" -> songs.sortedWith(compareBy<Song>({ it.disc }, { it.track }).thenBy(collator) { it.title })
            "artist" -> songs.sortedWith(compareBy<Song, String>(collator) { it.artist }.thenBy(collator) { it.album }.thenBy { it.track })
            "album" -> songs.sortedWith(compareBy<Song, String>(collator) { it.album }.thenBy { it.disc }.thenBy { it.track })
            "duration" -> songs.sortedBy { it.duration }
            "date_added" -> songs.sortedByDescending { it.dateAdded }
            "year" -> songs.sortedWith(compareBy<Song> { it.year }.thenBy(collator) { it.album }.thenBy { it.track })
            "filename" -> songs.sortedWith(compareBy(collator) { it.displayName })
            else -> songs.sortedWith(compareBy(collator) { it.title })
        }
        return if (Prefs.sortReverse(scope)) sorted.reversed() else sorted
    }

    private fun orderGroupSongs(songs: List<Song>) = sortSongs(songs, "group_tracks", "album")

    private fun sortFolderSongs(songs: List<Song>) = sortSongs(songs, "folders", "track")

    private fun sortVideos(videos: List<de.shakie.playernext.data.Video>): List<de.shakie.playernext.data.Video> {
        val sorted = when (Prefs.sortKey("videos", "date_added")) {
            "title" -> videos.sortedWith(compareBy(collator) { it.title })
            "duration" -> videos.sortedBy { it.duration }
            else -> videos.sortedByDescending { it.dateAdded }
        }
        return if (Prefs.sortReverse("videos")) sorted.reversed() else sorted
    }

    private fun sortScope(): String? = when (kind) {
        ALBUMS, ALBUMS_OF_ARTIST, ALBUMS_OF_GENRE -> "albums"
        ARTISTS, ARTISTS_OF_GENRE -> "artists"
        COMPOSERS -> "composers"
        GENRES -> "genres"
        SONGS -> "songs"
        TRACKS_ALBUM -> "album_tracks"
        TRACKS_ARTIST, TRACKS_COMPOSER, TRACKS_GENRE -> "group_tracks"
        TRACKS_PLAYLIST, TRACKS_SMART -> "playlist_tracks"
        FOLDERS -> "folders"
        VIDEOS -> "videos"
        else -> null
    }

    private fun sortOptions(scope: String): List<Pair<String, Int>> = when (scope) {
        "albums" -> listOf("title" to R.string.sort_title, "artist" to R.string.sort_artist, "year" to R.string.sort_year, "count" to R.string.sort_track_count)
        "artists" -> listOf("name" to R.string.sort_name, "album_count" to R.string.sort_album_count, "count" to R.string.sort_track_count)
        "composers", "genres" -> listOf("name" to R.string.sort_name, "count" to R.string.sort_track_count)
        "songs" -> listOf("title" to R.string.sort_title, "artist" to R.string.sort_artist, "album" to R.string.sort_album,
            "duration" to R.string.sort_duration, "date_added" to R.string.sort_date_added, "year" to R.string.sort_year)
        "album_tracks" -> listOf("track" to R.string.sort_track, "title" to R.string.sort_title, "duration" to R.string.sort_duration)
        "group_tracks" -> listOf("album" to R.string.sort_album, "title" to R.string.sort_title, "artist" to R.string.sort_artist,
            "year" to R.string.sort_year, "date_added" to R.string.sort_date_added)
        "playlist_tracks" -> listOf("order" to R.string.layout_list, "title" to R.string.sort_title, "artist" to R.string.sort_artist,
            "album" to R.string.sort_album, "duration" to R.string.sort_duration)
        "folders" -> listOf("track" to R.string.sort_track, "filename" to R.string.sort_filename, "title" to R.string.sort_title)
        "videos" -> listOf("date_added" to R.string.sort_date_added, "title" to R.string.sort_title, "duration" to R.string.sort_duration)
        else -> emptyList()
    }

    private fun defaultSort(scope: String) = sortOptions(scope).firstOrNull()?.first ?: "title"

    /** PlayerPro style sort submenu: radio items plus a "reverse" checkbox. */
    private fun addSortSubmenu(menu: Menu) {
        val scope = sortScope() ?: return
        val options = sortOptions(scope)
        val current = Prefs.sortKey(scope, defaultSort(scope))
        val sub = menu.addSubMenu(Menu.NONE, R.id.menu_sort, 3, R.string.sort)
        options.forEachIndexed { i, (k, res) ->
            val label = if (k == "order") getString(R.string.sort_playlist_order) else getString(res)
            sub.add(R.id.group_sort, SORT_BASE + i, i, label).isChecked = k == current
        }
        sub.setGroupCheckable(R.id.group_sort, true, true)
        sub.add(Menu.NONE, SORT_REVERSE, options.size, R.string.sort_reverse).apply {
            isCheckable = true
            isChecked = Prefs.sortReverse(scope)
        }
    }

    private fun onSortItem(id: Int): Boolean {
        val scope = sortScope() ?: return false
        val options = sortOptions(scope)
        when (id) {
            SORT_REVERSE -> Prefs.setSort(scope, Prefs.sortKey(scope, defaultSort(scope)), !Prefs.sortReverse(scope))
            in SORT_BASE until SORT_BASE + options.size -> Prefs.setSort(scope, options[id - SORT_BASE].first, Prefs.sortReverse(scope))
            else -> return false
        }
        requireActivity().invalidateOptionsMenu()
        reload()
        return true
    }

    private val SORT_BASE = 5000
    private val SORT_REVERSE = 5999

    private fun showLayoutDialog() {
        val type = when (kind) {
            ALBUMS, ALBUMS_OF_ARTIST, ALBUMS_OF_GENRE -> BrowserType.ALBUMS
            ARTISTS, ARTISTS_OF_GENRE -> BrowserType.ARTISTS
            COMPOSERS -> BrowserType.COMPOSERS
            GENRES -> BrowserType.GENRES
            else -> return
        }
        val values = listOf("grid", "grid_small", "grid_xsmall", "list")
        val labels = arrayOf(getString(R.string.layout_grid), getString(R.string.layout_grid_small), getString(R.string.layout_grid_xsmall), getString(R.string.layout_list))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.layout)
            .setSingleChoiceItems(labels, values.indexOf(Prefs.layoutFor(type)).coerceAtLeast(0)) { d, which ->
                Prefs.sp.edit().putString("${type.key}_layout", values[which]).apply()
                adapter.gridTiles = values[which] != "list"
                setupLayoutManager()
                list.adapter = adapter
                reload()
                d.dismiss()
            }
            .show()
    }

    // --- menus -------------------------------------------------------------------------------

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            if (kind == SEARCH || kind == PLAYLISTS || kind == VIDEOS) {
                if (kind == PLAYLISTS) {
                    menu.add(Menu.NONE, R.id.menu_new_smart, 1, R.string.new_smart_playlist)
                    menu.add(Menu.NONE, R.id.menu_import_playlist, 2, R.string.import_playlist)
                }
            } else {
                menu.add(Menu.NONE, R.id.menu_play_all, 1, R.string.play_all)
                menu.add(Menu.NONE, R.id.menu_shuffle_all, 2, R.string.shuffle_all)
            }
            addSortSubmenu(menu)
            if (kind in setOf(ALBUMS, ALBUMS_OF_ARTIST, ALBUMS_OF_GENRE, ARTISTS, ARTISTS_OF_GENRE, COMPOSERS, GENRES)) {
                menu.add(Menu.NONE, R.id.menu_layout, 4, R.string.layout)
            }
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.menu_play_all -> PlayerHub.play(allSongsOfView())
                R.id.menu_shuffle_all -> PlayerHub.play(allSongsOfView(), shuffle = true)
                R.id.menu_sort -> return false
                R.id.menu_layout -> showLayoutDialog()
                R.id.menu_new_smart -> SmartPlaylistEditorActivity.start(requireActivity(), 0)
                R.id.menu_import_playlist -> PlaylistIO.import(base)
                else -> return onSortItem(item.itemId)
            }
            return true
        }
    }

    /** Songs of the whole view (for "play all"), in display order. */
    private fun allSongsOfView(): List<Song> {
        if (viewSongs.isNotEmpty()) return viewSongs
        return adapter.currentList.flatMap { songsOf(it) }.distinctBy { it.id }
    }

    // --- row interaction -------------------------------------------------------------------

    override fun onRowClick(row: Row, position: Int) {
        if (actionMode != null) {
            toggleSelection(row)
            return
        }
        when (row) {
            is Row.Tile -> openTile(row)
            is Row.FolderRow -> host?.open(create(FOLDERS, row.folder.path))
            is Row.VideoRow -> VideoPlayerActivity.start(requireContext(), row.video)
            is Row.SongRow -> onSongClick(row)
            else -> Unit
        }
    }

    private fun onSongClick(row: Row.SongRow) {
        val songs = adapter.currentList.filterIsInstance<Row.SongRow>().map { it.song }
        val index = songs.indexOfFirst { it.id == row.song.id }.coerceAtLeast(0)
        val action = if (kind == FOLDERS) Prefs.folderClickAction else Prefs.songClickAction
        when (action) {
            "shuffle_all" -> {
                PlayerHub.play(songs, shuffle = true)
                host?.openNowPlaying()
            }
            "play_next" -> ItemActions.playNext(base, listOf(row.song))
            "play_last" -> ItemActions.enqueue(base, listOf(row.song))
            else -> {
                PlayerHub.play(songs, index)
                host?.openNowPlaying()
            }
        }
    }

    private fun openTile(row: Row.Tile) {
        val songs = { songsOf(row) }
        fun act(action: String, browse: () -> Unit) = when (action) {
            "play" -> PlayerHub.play(songs())
            "enqueue" -> ItemActions.enqueue(base, songs())
            "play_next" -> ItemActions.playNext(base, songs())
            else -> browse()
        }
        when (row.type) {
            ItemType.ALBUM -> act(Prefs.albumClickAction) { host?.open(create(TRACKS_ALBUM, row.itemKey)) }
            ItemType.ARTIST -> {
                val a = Prefs.artistClickAction
                act(a) { host?.open(create(if (a == "browse_tracks") TRACKS_ARTIST else ALBUMS_OF_ARTIST, row.itemKey)) }
            }
            ItemType.COMPOSER -> act(Prefs.composerClickAction) { host?.open(create(TRACKS_COMPOSER, row.itemKey)) }
            ItemType.GENRE -> {
                val a = Prefs.genreClickAction
                act(a) {
                    host?.open(create(when (a) {
                        "browse_artists" -> ARTISTS_OF_GENRE
                        "browse_tracks" -> TRACKS_GENRE
                        else -> ALBUMS_OF_GENRE
                    }, row.itemKey))
                }
            }
            ItemType.PLAYLIST -> host?.open(create(TRACKS_PLAYLIST, row.itemKey))
            ItemType.SMART_PLAYLIST -> host?.open(create(TRACKS_SMART, row.itemKey))
            else -> Unit
        }
    }

    override fun onRowLongClick(row: Row, position: Int): Boolean {
        if (row is Row.Header || row is Row.Section) return false
        if (actionMode == null) startSelection()
        toggleSelection(row)
        return true
    }

    override fun onRowMenu(row: Row, anchor: View) {
        val target = targetFor(row) ?: return
        val extras = ArrayList<ItemActions.Extra>()
        if (row is Row.SongRow && kind == TRACKS_PLAYLIST) {
            extras += ItemActions.Extra(getString(R.string.action_remove_from_playlist)) {
                val pid = key?.toLongOrNull() ?: return@Extra
                val p = PlaylistStore.byId(pid) ?: return@Extra
                PlaylistStore.setItems(pid, p.songPaths.filterIndexed { i, _ -> i != row.position })
            }
        }
        if (row is Row.SongRow) {
            extras += ItemActions.Extra(getString(R.string.action_show_album)) { host?.open(create(TRACKS_ALBUM, row.song.albumId.toString())) }
            extras += ItemActions.Extra(getString(R.string.action_show_artist)) {
                host?.open(create(ALBUMS_OF_ARTIST, if (Prefs.artistsUseAlbumArtist) row.song.effectiveAlbumArtist else row.song.artist))
            }
        }
        ItemActions.showMenu(base, anchor, target, extras)
    }

    override fun onHeaderPlayAll() = PlayerHub.play(allSongsOfView())
    override fun onHeaderShuffle() = PlayerHub.play(allSongsOfView(), shuffle = true)
    override fun onHeaderQueueAll() = ItemActions.enqueue(base, allSongsOfView())

    private fun songsOf(row: Row): List<Song> {
        val lib = Library.snapshot
        return when (row) {
            is Row.SongRow -> listOf(row.song)
            is Row.FolderRow -> row.folder.allSongs
            is Row.Tile -> when (row.type) {
                ItemType.ALBUM -> lib.albumsById[row.itemKey.toLongOrNull()]?.songs.orEmpty()
                ItemType.ARTIST -> lib.artistsByName[row.itemKey]?.let { a -> a.albums.flatMap { it.songs }.ifEmpty { a.songs } }.orEmpty()
                ItemType.COMPOSER -> lib.composersByName[row.itemKey]?.songs.orEmpty()
                ItemType.GENRE -> lib.genresByName[row.itemKey]?.songs.orEmpty()
                ItemType.PLAYLIST -> PlaylistStore.byId(row.itemKey.toLongOrNull() ?: -1)?.songPaths?.mapNotNull { lib.songsByPath[it] }.orEmpty()
                ItemType.SMART_PLAYLIST -> (if (row.itemKey in SmartPlaylist.BUILTIN_KEYS) SmartPlaylist.builtin(row.itemKey, row.title)
                    else SmartPlaylistStore.byId(row.itemKey.toLongOrNull() ?: -1))?.evaluate(lib, StatsStore.all()).orEmpty()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun targetFor(row: Row): Target? {
        val lib = Library.snapshot
        return when (row) {
            is Row.SongRow -> Target(ItemType.SONG, row.song.path, row.song.title, ArtKey.Album(row.song.albumId)) { listOf(row.song) }
            is Row.FolderRow -> Target(ItemType.FOLDER, row.folder.path, row.folder.name) { row.folder.allSongs }
            is Row.VideoRow -> Target(ItemType.VIDEO, row.video.path, row.video.title, video = row.video) { emptyList() }
            is Row.Tile -> {
                val album = if (row.type == ItemType.ALBUM) lib.albumsById[row.itemKey.toLongOrNull()] else null
                val art = when (row.type) {
                    ItemType.ALBUM -> ArtKey.Album(row.itemKey.toLong())
                    else -> row.art
                }
                Target(row.type, row.itemKey, row.title, art, album) { songsOf(row) }
            }
            else -> null
        }
    }

    // --- multi selection -------------------------------------------------------------------

    private fun startSelection() {
        actionMode = host?.startSelectionMode(selectionCallback)
    }

    private fun toggleSelection(row: Row) {
        if (!selected.add(row.key)) selected.remove(row.key)
        adapter.selection = selected.toSet()
        if (selected.isEmpty()) actionMode?.finish()
        else actionMode?.title = resources.getQuantityString(R.plurals.n_selected, selected.size, selected.size)
    }

    private fun selectedRows() = adapter.currentList.filter { it.key in selected }
    private fun selectedSongs() = selectedRows().flatMap { songsOf(it) }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            fun add(id: Int, title: Int, icon: Int, always: Boolean) = menu.add(Menu.NONE, id, id, title).apply {
                setIcon(icon)
                setShowAsAction(if (always) MenuItem.SHOW_AS_ACTION_IF_ROOM else MenuItem.SHOW_AS_ACTION_NEVER)
            }
            add(1, R.string.action_play, R.drawable.ic_play, true)
            add(2, R.string.action_enqueue, R.drawable.ic_queue, true)
            add(3, R.string.action_play_next, R.drawable.ic_play_next, false)
            add(4, R.string.action_shuffle, R.drawable.ic_shuffle, false)
            add(5, R.string.action_add_to_playlist, R.drawable.ic_playlist_add, true)
            add(6, R.string.action_add_favorite, R.drawable.ic_heart_border, false)
            add(7, R.string.action_edit_tags, R.drawable.ic_edit, false)
            add(8, R.string.action_share, R.drawable.ic_share, false)
            if (kind == TRACKS_PLAYLIST) add(10, R.string.action_remove_from_playlist, R.drawable.ic_close, false)
            add(9, R.string.action_delete, R.drawable.ic_delete, false)
            add(11, R.string.action_select_all, R.drawable.ic_select_all, true)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val songs = selectedSongs()
            when (item.itemId) {
                1 -> PlayerHub.play(songs)
                2 -> ItemActions.enqueue(base, songs)
                3 -> ItemActions.playNext(base, songs)
                4 -> PlayerHub.play(songs, shuffle = true)
                5 -> ItemActions.addToPlaylist(base, songs)
                6 -> selectedRows().mapNotNull { targetFor(it) }.forEach { FavoritesStore.add(it.type, it.key, it.title) }
                    .also { ItemActions.toast(base, getString(R.string.added_favorite)) }
                7 -> TagEditorActivity.start(requireActivity(), songs)
                8 -> ItemActions.share(base, songs)
                9 -> ItemActions.deleteSongs(base, songs)
                10 -> {
                    val pid = key?.toLongOrNull()
                    val p = pid?.let { PlaylistStore.byId(it) }
                    if (p != null) {
                        val positions = selectedRows().filterIsInstance<Row.SongRow>().map { it.position }.toSet()
                        PlaylistStore.setItems(p.id, p.songPaths.filterIndexed { i, _ -> i !in positions })
                    }
                }
                11 -> {
                    adapter.currentList.filter { it !is Row.Header && it !is Row.Section }.forEach { selected += it.key }
                    adapter.selection = selected.toSet()
                    mode.title = resources.getQuantityString(R.plurals.n_selected, selected.size, selected.size)
                    return true
                }
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            selected.clear()
            if (::adapter.isInitialized) adapter.selection = emptySet()
        }
    }

    // --- playlists ---------------------------------------------------------------------------

    private fun newPlaylistDialog() {
        val options = arrayOf(getString(R.string.new_playlist), getString(R.string.new_smart_playlist), getString(R.string.import_playlist))
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> ItemActions.prompt(base, R.string.new_playlist, "") { name ->
                        PlaylistStore.create(name)
                        ItemActions.toast(base, getString(R.string.playlist_created, name))
                    }
                    1 -> SmartPlaylistEditorActivity.start(requireActivity(), 0)
                    else -> PlaylistIO.import(base)
                }
            }
            .show()
    }

    private fun setupDrag() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            private var from = -1
            private var to = -1
            override fun isLongPressDragEnabled() = false
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (vh.itemViewType == RowAdapter.TYPE_SONG && adapter.draggable) super.getMovementFlags(rv, vh) else 0

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (target.itemViewType != RowAdapter.TYPE_SONG) return false
                if (from == -1) from = vh.bindingAdapterPosition
                to = target.bindingAdapterPosition
                val current = adapter.currentList.toMutableList()
                val moved = current.removeAt(vh.bindingAdapterPosition)
                current.add(target.bindingAdapterPosition, moved)
                adapter.submitList(current)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (from != -1 && to != -1 && from != to) {
                    val pid = key?.toLongOrNull() ?: return
                    val paths = adapter.currentList.filterIsInstance<Row.SongRow>().map { it.song.path }
                    PlaylistStore.setItems(pid, paths)
                }
                from = -1
                to = -1
            }
        })
        helper.attachToRecyclerView(list)
        touchHelper = helper
    }

    override fun onStartDrag(holder: RecyclerView.ViewHolder) {
        touchHelper?.startDrag(holder)
    }

    // --- search ------------------------------------------------------------------------------

    private var searchJob: Job? = null

    private fun setupSearch(initial: String?) {
        searchField.visibility = View.VISIBLE
        if (!initial.isNullOrEmpty()) searchField.setText(initial)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(220)
                    reload()
                }
            }
        })
        if (initial.isNullOrEmpty()) {
            searchField.requestFocus()
            searchField.post {
                requireContext().getSystemService(InputMethodManager::class.java)
                    .showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun focusSearch() {
        if (kind != SEARCH) return
        searchField.requestFocus()
        requireContext().getSystemService(InputMethodManager::class.java).showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }
}
