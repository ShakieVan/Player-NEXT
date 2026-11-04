package de.shakie.playernext.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.BrowserType
import de.shakie.playernext.data.Favorite
import de.shakie.playernext.data.FavoritesStore
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.SmartPlaylist
import de.shakie.playernext.data.SmartPlaylistStore
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.playback.PlayerHub
import de.shakie.playernext.playback.QueueStore
import de.shakie.playernext.update.UpdateActivity
import de.shakie.playernext.update.Updater
import com.google.android.material.snackbar.Snackbar
import de.shakie.playernext.tags.TagIO
import de.shakie.playernext.ui.browse.BrowseFragment
import de.shakie.playernext.ui.browse.BrowserHost
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.ui.common.SleepTimerDialog
import de.shakie.playernext.ui.equalizer.EqualizerActivity
import de.shakie.playernext.ui.nowplaying.NowPlayingActivity
import de.shakie.playernext.ui.settings.SettingsActivity
import de.shakie.playernext.ui.video.VideoPlayerActivity
import de.shakie.playernext.util.Permissions
import de.shakie.playernext.util.dp

class MainActivity : BaseActivity(), BrowserHost {
    companion object {
        const val ACTION_OPEN = "de.shakie.playernext.OPEN"
        // Version already pointed out in this app run.
        private var offeredUpdate: String? = null
    }

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var toolbarTitle: TextView
    private lateinit var toolbarIcon: ImageView
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var miniPlayer: View
    private val handler = Handler(Looper.getMainLooper())
    private var searchItem: MenuItem? = null
    private var permissionView: View? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionResult()
    }

    private val currentBrowser: BrowseFragment?
        get() = supportFragmentManager.findFragmentById(R.id.content) as? BrowseFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        drawer = findViewById(R.id.drawer)
        toolbar = findViewById(R.id.toolbar)
        toolbarTitle = findViewById(R.id.toolbar_title)
        toolbarIcon = findViewById(R.id.toolbar_icon)
        miniPlayer = findViewById(R.id.mini_player)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener {
            if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
            else drawer.openDrawer(GravityCompat.START)
        }
        setupDrawer()
        setupMiniPlayer()
        supportFragmentManager.addOnBackStackChangedListener { updateToolbar() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    searchItem?.isActionViewExpanded == true -> searchItem?.collapseActionView()
                    supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        PlayerHub.connect(this)
        Library.addListener(libraryListener)
        FavoritesStore.addListener(libraryListener)
        PlaylistStore.addListener(libraryListener)
        SmartPlaylistStore.addListener(libraryListener)
        PlayerHub.addListener(playerListener)
        Updater.addListener(updateListener)
        Updater.autoCheck(this)
        offerUpdate()

        if (Permissions.hasAudio(this)) {
            if (savedInstanceState == null) {
                showRoot(Prefs.lastBrowser.takeIf { Prefs.browserEnabled(it) } ?: firstEnabledBrowser())
                handleIntent(intent, fresh = true)
            }
        } else {
            showPermissionScreen()
        }
    }

    private val updateListener: () -> Unit = { offerUpdate() }

    /** A newer version (found now or earlier) is pointed out once per app run. */
    private fun offerUpdate() {
        val rel = Updater.release ?: return
        if (Updater.busy || offeredUpdate == rel.version) return
        offeredUpdate = rel.version
        Snackbar.make(findViewById(R.id.content), getString(R.string.update_snackbar, rel.version), Snackbar.LENGTH_LONG)
            .setAnchorView(R.id.mini_player)
            .setAction(R.string.update_snackbar_action) { startActivity(Intent(this, UpdateActivity::class.java)) }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fresh = false)
    }

    override fun onResume() {
        super.onResume()
        updateMiniPlayer()
        refreshDrawer()
        Prefs.lastScreenWasPlayer = false
    }

    override fun onPause() {
        // The mini player progress only ticks while the library is visible.
        handler.removeCallbacks(progressTick)
        super.onPause()
    }

    override fun onDestroy() {
        Updater.removeListener(updateListener)
        Library.removeListener(libraryListener)
        FavoritesStore.removeListener(libraryListener)
        PlaylistStore.removeListener(libraryListener)
        SmartPlaylistStore.removeListener(libraryListener)
        PlayerHub.removeListener(playerListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- permissions -------------------------------------------------------------------------

    private fun showPermissionScreen() {
        val content = findViewById<android.widget.FrameLayout>(R.id.content)
        val v = layoutInflater.inflate(R.layout.view_permission, content, false)
        content.addView(v)
        permissionView = v
        toolbar.visibility = View.GONE
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        v.findViewById<View>(R.id.perm_grant).setOnClickListener { permissionLauncher.launch(Permissions.required(this)) }
        v.findViewById<View>(R.id.perm_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun onPermissionResult() {
        if (!Permissions.hasAudio(this)) {
            permissionView?.findViewById<View>(R.id.perm_settings)?.visibility = View.VISIBLE
            return
        }
        permissionView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        permissionView = null
        toolbar.visibility = View.VISIBLE
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        Library.refresh()
        showRoot(firstEnabledBrowser().let { if (Prefs.browserEnabled(BrowserType.ALBUMS)) BrowserType.ALBUMS else it })
    }

    // --- navigation --------------------------------------------------------------------------

    private fun firstEnabledBrowser() = Prefs.browserOrder.firstOrNull { Prefs.browserEnabled(it) } ?: BrowserType.ALBUMS

    fun showRoot(type: BrowserType) {
        Prefs.lastBrowser = type
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.content, BrowseFragment.forBrowser(type))
        }
        drawer.closeDrawer(GravityCompat.START)
    }

    override fun open(fragment: BrowseFragment) {
        searchItem?.collapseActionView()
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            replace(R.id.content, fragment)
            addToBackStack(fragment.kind)
        }
        drawer.closeDrawer(GravityCompat.START)
    }

    override fun onBrowserShown(fragment: BrowseFragment) {
        if (fragment != currentBrowser) return
        updateToolbar()
    }

    private fun updateToolbar() {
        val f = currentBrowser ?: return
        toolbarTitle.text = f.title
        toolbarIcon.setImageResource(f.iconRes)
        toolbar.setNavigationIcon(if (supportFragmentManager.backStackEntryCount > 0) R.drawable.ic_back else R.drawable.ic_menu)
        toolbar.navigationContentDescription = getString(if (supportFragmentManager.backStackEntryCount > 0) R.string.back else R.string.open_menu)
        drawerAdapter.activeBrowser = f.browserType
        invalidateOptionsMenu()
    }

    override fun startSelectionMode(callback: ActionMode.Callback): ActionMode? = startSupportActionMode(callback)

    override fun openNowPlaying() {
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun handleIntent(intent: Intent?, fresh: Boolean) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { playExternal(it) }
            Intent.ACTION_SEARCH -> open(BrowseFragment.create(BrowseFragment.SEARCH, query = intent.getStringExtra("query")))
            ACTION_OPEN -> intent.getStringExtra("kind")?.let { kind -> open(BrowseFragment.create(kind, intent.getStringExtra("key"))) }
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> playFromSearch(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty())
            "de.shakie.playernext.SHUFFLE_ALL" -> afterLibrary { PlayerHub.play(Library.snapshot.songs, shuffle = true); openNowPlaying() }
            "de.shakie.playernext.NOW_PLAYING" -> openNowPlaying()
            "de.shakie.playernext.SEARCH" -> showRoot(BrowserType.SEARCH)
            else -> if (fresh) {
                val start = Prefs.startupScreen
                val goPlayer = when (start) {
                    "startup_last_visited" -> Prefs.lastScreenWasPlayer
                    "startup_player_if_playing" -> PlayerHub.isPlaying
                    "startup_player_if_queue" -> QueueStore.hasQueue(this)
                    else -> false
                }
                if (goPlayer) openNowPlaying()
            }
        }
    }

    private fun afterLibrary(block: () -> Unit) {
        if (Library.loaded) block() else Library.refresh { block() }
    }

    private fun playExternal(uri: Uri) = afterLibrary {
        val lib = Library.snapshot
        val id = if (uri.authority == MediaStore.AUTHORITY) uri.lastPathSegment?.toLongOrNull() else null
        val song = id?.let { lib.songsById[it] }
            ?: (if (uri.scheme == "file") lib.songsByPath[uri.path] else null)
            ?: queryPath(uri)?.let { lib.songsByPath[it] }
        if (song != null) {
            val album = lib.albumsById[song.albumId]?.songs ?: listOf(song)
            PlayerHub.play(album, album.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
            openNowPlaying()
        } else {
            ItemActions.toast(this, getString(R.string.empty_search))
        }
    }

    private fun queryPath(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun playFromSearch(query: String) = afterLibrary {
        val lib = Library.snapshot
        val songs = if (query.isBlank()) lib.songs.shuffled() else {
            lib.artistsByName.entries.firstOrNull { it.key.equals(query, true) }?.value?.songs
                ?: lib.albums.firstOrNull { it.title.equals(query, true) }?.songs
                ?: lib.songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
        }
        if (songs.isNotEmpty()) PlayerHub.play(songs)
    }

    // --- drawer ------------------------------------------------------------------------------

    private fun setupDrawer() {
        val listView = findViewById<RecyclerView>(R.id.drawer_list)
        drawerAdapter = DrawerAdapter(::onDrawerItem)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = drawerAdapter
        findViewById<ImageButton>(R.id.drawer_edit).setOnClickListener {
            drawerAdapter.editMode = !drawerAdapter.editMode
            findViewById<View>(R.id.drawer_hint).visibility = if (drawerAdapter.editMode) View.VISIBLE else View.GONE
            (it as ImageButton).setImageResource(if (drawerAdapter.editMode) R.drawable.ic_check else R.drawable.ic_edit)
            refreshDrawer()
        }
        // Drag & drop for favorites and browsers, swipe to remove favorites (edit mode only).
        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = drawerAdapter.editMode
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (!drawerAdapter.editMode) return 0
                return when (drawerAdapter.items.getOrNull(vh.bindingAdapterPosition)) {
                    is DrawerItem.Fav -> makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END)
                    is DrawerItem.Browser -> makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                    else -> 0
                }
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val items = drawerAdapter.items.toMutableList()
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (items[from]::class != items[to]::class) return false
                items.add(to, items.removeAt(from))
                drawerAdapter.items = items
                return true
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val items = drawerAdapter.items
                FavoritesStore.reorder(items.filterIsInstance<DrawerItem.Fav>().map { it.favorite })
                Prefs.browserOrder = items.filterIsInstance<DrawerItem.Browser>().map { it.type }
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                (drawerAdapter.items.getOrNull(vh.bindingAdapterPosition) as? DrawerItem.Fav)?.let {
                    FavoritesStore.remove(it.favorite.type, it.favorite.key)
                }
            }
        }).attachToRecyclerView(listView)
        refreshDrawer()
    }

    private fun refreshDrawer() {
        if (!::drawerAdapter.isInitialized) return
        val lib = Library.snapshot
        val items = ArrayList<DrawerItem>()
        val favs = FavoritesStore.all().map { favoriteItem(it) }
        val browsers = Prefs.browserOrder.filter { drawerAdapter.editMode || Prefs.browserEnabled(it) }.map { t ->
            val count = when (t) {
                BrowserType.SEARCH -> lib.songs.size + lib.albums.size + lib.artists.size
                BrowserType.ALBUMS -> lib.albums.size
                BrowserType.ARTISTS -> lib.artists.size
                BrowserType.COMPOSERS -> lib.composers.size
                BrowserType.GENRES -> lib.genres.size
                BrowserType.PLAYLISTS -> Prefs.smartPlaylists.size + PlaylistStore.all().size + SmartPlaylistStore.all().size
                BrowserType.FOLDERS -> lib.folderCount
                BrowserType.SONGS -> lib.songs.size
                BrowserType.VIDEOS -> lib.videos.size
                BrowserType.AUDIOBOOKS -> lib.songs.count { it.isAudiobook }
            }
            DrawerItem.Browser(t, count, Prefs.browserEnabled(t))
        }
        val favSection = ArrayList<DrawerItem>().apply {
            add(DrawerItem.Header(getString(R.string.drawer_favorites)))
            if (favs.isEmpty()) add(DrawerItem.Hint(getString(R.string.drawer_no_favorites))) else addAll(favs)
        }
        val browserSection = listOf(DrawerItem.Header(getString(R.string.drawer_browsers))) + browsers
        if (Prefs.showBrowsersFirst) {
            items += browserSection; items += favSection
        } else {
            items += favSection; items += browserSection
        }
        drawerAdapter.items = items
    }

    private fun favoriteItem(f: Favorite): DrawerItem.Fav {
        val lib = Library.snapshot
        return when (f.type) {
            ItemType.SONG -> DrawerItem.Fav(f, 1, lib.songsByPath[f.key]?.let { ArtKey.Album(it.albumId) })
            ItemType.ALBUM -> lib.albumsById[f.key.toLongOrNull()].let { DrawerItem.Fav(f, it?.songCount ?: 0, it?.let { a -> ArtKey.Album(a.id) }) }
            ItemType.ARTIST -> lib.artistsByName[f.key].let { DrawerItem.Fav(f, it?.songs?.size ?: 0, it?.let { a -> ArtKey.Artist(a.name, a.albums.map { al -> al.id }) }) }
            ItemType.COMPOSER -> lib.composersByName[f.key].let { DrawerItem.Fav(f, it?.songs?.size ?: 0, it?.let { g -> ArtKey.Collage("composer:${g.name}", g.albums) }) }
            ItemType.GENRE -> lib.genresByName[f.key].let { DrawerItem.Fav(f, it?.songs?.size ?: 0, it?.let { g -> ArtKey.Collage("genre:${g.name}", g.albums) }) }
            ItemType.FOLDER -> lib.findFolder(f.key).let { DrawerItem.Fav(f, it?.allSongs?.size ?: 0, it?.allSongs?.firstOrNull()?.let { s -> ArtKey.Album(s.albumId) }) }
            ItemType.PLAYLIST -> PlaylistStore.byId(f.key.toLongOrNull() ?: -1).let { p ->
                val songs = p?.songPaths?.mapNotNull { lib.songsByPath[it] }.orEmpty()
                DrawerItem.Fav(f, songs.size, songs.firstOrNull()?.let { ArtKey.Album(it.albumId) })
            }
            ItemType.SMART_PLAYLIST -> {
                val sp = if (f.key in SmartPlaylist.BUILTIN_KEYS) SmartPlaylist.builtin(f.key, f.title) else SmartPlaylistStore.byId(f.key.toLongOrNull() ?: -1)
                val songs = sp?.evaluate(lib, StatsStore.all()).orEmpty()
                DrawerItem.Fav(f, songs.size, songs.firstOrNull()?.let { ArtKey.Album(it.albumId) })
            }
            ItemType.VIDEO -> DrawerItem.Fav(f, 1, lib.videos.firstOrNull { it.path == f.key }?.let { ArtKey.Video(it.id) })
        }
    }

    private fun onDrawerItem(item: DrawerItem) {
        when (item) {
            is DrawerItem.Browser -> if (drawerAdapter.editMode) {
                Prefs.sp.edit().putBoolean("use_${item.type.key}_browser", !item.enabled).apply()
                refreshDrawer()
            } else {
                showRoot(item.type)
            }
            is DrawerItem.Fav -> if (drawerAdapter.editMode) {
                FavoritesStore.remove(item.favorite.type, item.favorite.key)
            } else {
                openFavorite(item.favorite)
            }
            else -> Unit
        }
    }

    private fun openFavorite(f: Favorite) {
        val lib = Library.snapshot
        when (f.type) {
            ItemType.SONG -> lib.songsByPath[f.key]?.let { PlayerHub.play(listOf(it)); openNowPlaying() }
            ItemType.ALBUM -> open(BrowseFragment.create(BrowseFragment.TRACKS_ALBUM, f.key))
            ItemType.ARTIST -> open(BrowseFragment.create(BrowseFragment.ALBUMS_OF_ARTIST, f.key))
            ItemType.COMPOSER -> open(BrowseFragment.create(BrowseFragment.TRACKS_COMPOSER, f.key))
            ItemType.GENRE -> open(BrowseFragment.create(BrowseFragment.ALBUMS_OF_GENRE, f.key))
            ItemType.FOLDER -> open(BrowseFragment.create(BrowseFragment.FOLDERS, f.key))
            ItemType.PLAYLIST -> open(BrowseFragment.create(BrowseFragment.TRACKS_PLAYLIST, f.key))
            ItemType.SMART_PLAYLIST -> open(BrowseFragment.create(BrowseFragment.TRACKS_SMART, f.key))
            ItemType.VIDEO -> lib.videos.firstOrNull { it.path == f.key }?.let { VideoPlayerActivity.start(this, it) }
        }
        drawer.closeDrawer(GravityCompat.START)
    }

    private val libraryListener: () -> Unit = {
        refreshDrawer()
        updateToolbar()
    }

    // --- mini player -------------------------------------------------------------------------

    private val playerListener: () -> Unit = { updateMiniPlayer() }
    private var miniSongId = -1L
    private val progressTick = object : Runnable {
        override fun run() {
            val c = PlayerHub.controller ?: return
            val d = c.duration
            miniPlayer.findViewById<ProgressBar>(R.id.mini_progress).progress =
                if (d > 0) (c.currentPosition * 1000 / d).toInt() else 0
            if (c.isPlaying) handler.postDelayed(this, 1000)
        }
    }

    private fun setupMiniPlayer() {
        miniPlayer.setOnClickListener { openNowPlaying() }
        miniPlayer.findViewById<ImageButton>(R.id.mini_play).setOnClickListener { PlayerHub.togglePlayPause() }
    }

    private fun updateMiniPlayer() {
        val song = PlayerHub.currentSong()
        if (song == null || !PlayerHub.hasQueue()) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        if (song.id != miniSongId) {
            miniSongId = song.id
            miniPlayer.findViewById<TextView>(R.id.mini_title).text = song.title
            miniPlayer.findViewById<TextView>(R.id.mini_artist).text = song.artist
            ArtLoader.load(miniPlayer.findViewById(R.id.mini_art), ArtKey.Song(song.id, song.albumId), dp(48))
            ArtLoader.load(miniPlayer.findViewById(R.id.mini_bg), ArtKey.Song(song.id, song.albumId), dp(48), blur = true)
        }
        val playing = PlayerHub.playWhenReady
        miniPlayer.findViewById<ImageButton>(R.id.mini_play).apply {
            setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = getString(if (playing) R.string.pause else R.string.play)
        }
        handler.removeCallbacks(progressTick)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) handler.post(progressTick)
    }

    // --- options menu ------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (permissionView != null) return false
        val search = menu.add(Menu.NONE, R.id.menu_search, 0, R.string.action_search)
        search.setIcon(R.drawable.ic_search)
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        if (currentBrowser?.kind != BrowseFragment.SEARCH) {
            val sv = SearchView(this)
            sv.queryHint = getString(R.string.filter_hint)
            sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    currentBrowser?.setFilter(newText.orEmpty())
                    return true
                }
            })
            search.actionView = sv
            search.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem) = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    currentBrowser?.setFilter("")
                    return true
                }
            })
        }
        searchItem = search
        menu.add(Menu.NONE, R.id.menu_equalizer, 20, R.string.equalizer)
        menu.add(Menu.NONE, R.id.menu_settings, 21, R.string.settings)
        menu.add(Menu.NONE, R.id.menu_save_queue, 22, R.string.save_queue)
        menu.add(Menu.NONE, R.id.menu_clear_queue, 23, R.string.clear_queue)
        menu.add(Menu.NONE, R.id.menu_party, 24, if (PlayerHub.partyShuffle) R.string.party_off else R.string.party_on)
        menu.add(Menu.NONE, R.id.menu_sleep, 25, R.string.sleep_timer)
        menu.add(Menu.NONE, R.id.menu_exit, 26, R.string.exit)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_party)?.setTitle(if (PlayerHub.partyShuffle) R.string.party_off else R.string.party_on)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_search -> if (currentBrowser?.kind == BrowseFragment.SEARCH) currentBrowser?.focusSearch() else return false
            R.id.menu_equalizer -> startActivity(Intent(this, EqualizerActivity::class.java))
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_save_queue -> ItemActions.prompt(this, R.string.save_queue, "") { name ->
                PlayerHub.saveQueueAsPlaylist(name)
                ItemActions.toast(this, getString(R.string.queue_saved, name))
            }
            R.id.menu_clear_queue -> PlayerHub.clearQueue()
            R.id.menu_party -> PlayerHub.togglePartyShuffle()
            R.id.menu_sleep -> SleepTimerDialog.show(this)
            R.id.menu_exit -> exitApp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun exitApp() {
        PlayerHub.stop()
        handler.postDelayed({
            PlayerHub.disconnect()
            finishAffinity()
        }, 300)
    }

    @Suppress("unused")
    private fun rescan() {
        TagIO.rescan(this, Library.snapshot.songs.map { it.folder }.distinct())
        ItemActions.toast(this, getString(R.string.rescan_started))
    }
}
