package de.shakie.playernext.ui.nowplaying

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.FavoritesStore
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.Song
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.playback.PlayerHub
import de.shakie.playernext.playback.QueueItems
import de.shakie.playernext.tags.TagIO
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.MainActivity
import de.shakie.playernext.ui.browse.BrowseFragment
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.ui.common.LyricsEditor
import de.shakie.playernext.ui.common.RatingView
import de.shakie.playernext.ui.common.SleepTimerDialog
import de.shakie.playernext.ui.equalizer.EqualizerActivity
import de.shakie.playernext.ui.settings.SettingsActivity
import de.shakie.playernext.ui.tags.TagEditorActivity
import de.shakie.playernext.util.Format
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class NowPlayingActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pager: ViewPager2
    private lateinit var artAdapter: ArtAdapter
    private lateinit var queueList: RecyclerView
    private lateinit var queueAdapter: QueueAdapter
    private lateinit var rating: RatingView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var overlayTop: View
    private lateinit var overlayBottom: View
    private lateinit var lyricsPanel: View
    private lateinit var volumePanel: View
    private var userSeeking = false
    private var pagerProgrammatic = false
    private var currentSongId = -1L
    private var overlayVisible = true
    private var queueItems: List<MediaItem> = emptyList()
    private var hubQueue: List<MediaItem>? = null
    private lateinit var audioManager: AudioManager
    private var touchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)
        audioManager = getSystemService(AudioManager::class.java)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        pager = findViewById(R.id.art_pager)
        rating = findViewById(R.id.rating)
        seek = findViewById(R.id.seek)
        elapsed = findViewById(R.id.elapsed)
        total = findViewById(R.id.total)
        overlayTop = findViewById(R.id.overlay_top)
        overlayBottom = findViewById(R.id.overlay_bottom)
        lyricsPanel = findViewById(R.id.lyrics_panel)
        volumePanel = findViewById(R.id.volume_panel)
        queueList = findViewById(R.id.queue_list)

        setupPager()
        setupQueue()
        setupControls()
        setupOverlay()
        applyWindowPrefs()
        PlayerHub.connect(this)
    }

    override fun onResume() {
        super.onResume()
        PlayerHub.addListener(playerListener)
        StatsStore.addListener(statsListener)
        Prefs.sp.registerOnSharedPreferenceChangeListener(shuffleModeListener)
        de.shakie.playernext.playback.ChapterStore.addListener(chapterListener)
        Prefs.lastScreenWasPlayer = true
        overlayVisible = Prefs.playerVisibleRatings
        applyOverlayVisibility()
        refreshAll()
    }

    override fun onPause() {
        PlayerHub.removeListener(playerListener)
        StatsStore.removeListener(statsListener)
        Prefs.sp.unregisterOnSharedPreferenceChangeListener(shuffleModeListener)
        de.shakie.playernext.playback.ChapterStore.removeListener(chapterListener)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    // Switching between the two random modes changes no player state, only this setting.
    private val shuffleModeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shuffle_mode") refreshAll()
    }

    private fun applyWindowPrefs() {
        if (Prefs.playerKeepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Prefs.playerFullScreen) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // --- state --------------------------------------------------------------------------------

    private val playerListener: () -> Unit = { refreshAll() }
    private val statsListener: () -> Unit = { PlayerHub.currentSong()?.let { rating.rating = StatsStore[it.path].rating } }

    private fun refreshAll() {
        val c = PlayerHub.controller
        val items = PlayerHub.queueItems()
        // PlayerHub hands out the same list until the queue changes, so long queues are only compared after a
        // change. A drag already moved the local copy; then the lists match and the drag isn't interrupted.
        if (items !== hubQueue) {
            hubQueue = items
            if (items.size != queueItems.size || items.indices.any { items[it].mediaId != queueItems[it].mediaId }) {
                queueItems = items
                artAdapter.notifyDataSetChanged()
                queueAdapter.notifyDataSetChanged()
            }
        }
        val index = c?.currentMediaItemIndex ?: 0
        findViewById<View>(R.id.empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty() && pager.currentItem != index) {
            pagerProgrammatic = true
            pager.setCurrentItem(index, abs(pager.currentItem - index) == 1)
            pagerProgrammatic = false
        }
        queueAdapter.current = index

        val song = PlayerHub.currentSong()
        findViewById<TextView>(R.id.position).text = if (items.isEmpty()) "0/0" else "${index + 1}/${items.size}"
        findViewById<TextView>(R.id.title).apply {
            text = song?.title ?: c?.mediaMetadata?.title ?: ""
            isSelected = true
        }
        findViewById<TextView>(R.id.subtitle).text = song?.let { listOf(it.artist, it.album).filter { s -> s.isNotBlank() }.joinToString(" - ") } ?: ""
        if (song?.id != currentSongId) {
            currentSongId = song?.id ?: -1
            ArtLoader.load(findViewById(R.id.art_backdrop), song?.let { ArtKey.Song(it.id, it.albumId) }, dp(64), R.drawable.placeholder_album, blur = true)
            rating.rating = song?.let { StatsStore[it.path].rating } ?: 0
            if (lyricsPanel.visibility == View.VISIBLE) loadLyrics()
        }
        updateFormat(song)

        val playing = PlayerHub.playWhenReady
        findViewById<ImageView>(R.id.play_icon).setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        findViewById<View>(R.id.btn_play).contentDescription = getString(if (playing) R.string.pause else R.string.play)
        val accent = themeColor(R.attr.pnAccent)
        val dim = getColor(R.color.pn_on_surface_faint)
        findViewById<ImageButton>(R.id.btn_shuffle).apply {
            val on = c?.shuffleModeEnabled == true
            val oldest = on && Prefs.shuffleOldestFirst
            setImageResource(if (oldest) R.drawable.ic_shuffle_history else R.drawable.ic_shuffle)
            imageTintList = android.content.res.ColorStateList.valueOf(if (on) accent else dim)
            contentDescription = getString(if (oldest) R.string.shuffle_oldest else if (on) R.string.shuffle_on else R.string.shuffle_off)
        }
        findViewById<ImageButton>(R.id.btn_repeat).apply {
            val mode = c?.repeatMode ?: Player.REPEAT_MODE_OFF
            setImageResource(if (mode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
            imageTintList = android.content.res.ColorStateList.valueOf(if (mode == Player.REPEAT_MODE_OFF) dim else accent)
            contentDescription = getString(when (mode) {
                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                else -> R.string.repeat_off
            })
        }
        updateProgress()
        if (PlayerHub.partyShuffle != partyShown) {
            partyShown = PlayerHub.partyShuffle
            invalidateOptionsMenu()
        }
    }

    private var partyShown = false

    private fun updateFormat(song: Song?) {
        val c = PlayerHub.controller
        val format = c?.currentTracks?.groups?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }?.getTrackFormat(0)
        val parts = ArrayList<String>()
        val mime = format?.sampleMimeType ?: song?.mimeType
        mime?.let { parts += it.substringAfter('/').removePrefix("x-") }
        format?.sampleRate?.takeIf { it > 0 }?.let { parts += String.format(Locale.GERMANY, "%.1fkHz", it / 1000f).replace(",0k", "k") }
        val bitrate = format?.bitrate?.takeIf { it > 0 } ?: song?.bitrate?.takeIf { it > 0 }
        bitrate?.let { parts += "${it / 1000}kBps" }
        format?.channelCount?.takeIf { it > 0 }?.let { parts += if (it == 1) "mono" else "stereo" }
        findViewById<TextView>(R.id.format).text = parts.joinToString(" ")
    }

    private val progressTick = Runnable { updateProgress() }

    /** "Kapitel 3/12: …" under the controls of an audiobook; tapping it opens the chapter list. */
    private fun updateChapter(positionMs: Long) {
        val view = findViewById<TextView>(R.id.chapter)
        val chapters = PlayerHub.currentChapters()
        if (chapters.size < 2) {
            view.visibility = View.GONE
            return
        }
        val i = de.shakie.playernext.playback.ChapterNav.indexAt(chapters, positionMs)
        val text = getString(R.string.chapter_current, i + 1, chapters.size, chapters[i].title)
        if (view.text != text) view.text = text
        view.visibility = View.VISIBLE
    }

    private fun showChapters() {
        val chapters = PlayerHub.currentChapters()
        if (chapters.isEmpty()) return
        val current = de.shakie.playernext.playback.ChapterNav.indexAt(chapters, PlayerHub.controller?.currentPosition ?: 0)
        val names = chapters.map { "${Format.duration(it.startMs)}   ${it.title}" }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chapters)
            .setSingleChoiceItems(names, current) { d, which ->
                PlayerHub.seekTo(chapters[which].startMs)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val chapterListener: (String) -> Unit = { path ->
        if (PlayerHub.currentSong()?.path == path) {
            updateChapter(PlayerHub.controller?.currentPosition ?: 0)
            invalidateOptionsMenu()
        }
    }

    /** Updates time and seek bar, then waits for the next full second of the song (1 frame/s). */
    private fun updateProgress() {
        handler.removeCallbacks(progressTick)
        val c = PlayerHub.controller ?: return
        val dur = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (PlayerHub.currentSong()?.duration ?: 0)
        val pos = c.currentPosition
        if (!userSeeking) seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        elapsed.text = Format.duration(pos)
        updateChapter(pos)
        total.text = if (Prefs.displayRemaining) "-" + Format.duration((dur - pos).coerceAtLeast(0)) else Format.duration(dur)
        if (c.isPlaying) {
            val speed = c.playbackParameters.speed.coerceAtLeast(0.1f)
            handler.postDelayed(progressTick, ((1000 - pos % 1000) / speed).toLong().coerceIn(200, 1000))
        }
    }

    // --- art pager ----------------------------------------------------------------------------

    private fun setupPager() {
        artAdapter = ArtAdapter()
        pager.adapter = artAdapter
        pager.offscreenPageLimit = 1
        (pager.getChildAt(0) as? RecyclerView)?.apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        PageTransformers.forKey(Prefs.swipeEffect)?.let { pager.setPageTransformer(it) }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (pagerProgrammatic) return
                val c = PlayerHub.controller ?: return
                if (position != c.currentMediaItemIndex && position < c.mediaItemCount) {
                    // Swipe left/right skips to the next/previous song.
                    if (position == c.currentMediaItemIndex + 1) PlayerHub.next()
                    else if (position == c.currentMediaItemIndex - 1) c.seekToPreviousMediaItem()
                    else PlayerHub.playAt(position)
                }
            }
        })
    }

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            overlayVisible = !overlayVisible
            applyOverlayVisibility()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (Prefs.playerGesture == "player_gesture_doubletap") PlayerHub.togglePlayPause()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (Prefs.playerGesture == "player_gesture_longpress") PlayerHub.togglePlayPause()
        }
    }

    inner class ArtAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val detector = GestureDetector(this@NowPlayingActivity, gestureListener)

        override fun getItemCount() = queueItems.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                when (Prefs.playerArt) {
                    "player_art_padded" -> {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val p = dp(24)
                        setPadding(p, p + dp(40), p, p + dp(48))
                    }
                    "player_art_zoomed" -> {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        scaleX = 1.15f
                        scaleY = 1.15f
                    }
                    else -> scaleType = ImageView.ScaleType.CENTER_CROP
                }
                setOnTouchListener { _, e -> detector.onTouchEvent(e) }
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val song = QueueItems.songOf(queueItems[position])
            val size = (resources.displayMetrics.widthPixels).coerceAtMost(1080)
            ArtLoader.load(holder.itemView as ImageView, song?.let { ArtKey.Song(it.id, it.albumId) }, size, R.drawable.placeholder_album)
        }
    }

    // --- overlay (rating bar and common actions) ----------------------------------------------

    private fun setupOverlay() {
        rating.onRatingChanged = { value ->
            PlayerHub.currentSong()?.let { s ->
                StatsStore.setRating(s.path, value)
                if (Prefs.writeRatingsToTags) writeRatingTag(s, value)
            }
        }
        findViewById<View>(R.id.btn_overlay_close).setOnClickListener {
            overlayVisible = false
            applyOverlayVisibility()
        }
        findViewById<View>(R.id.btn_volume).setOnClickListener {
            volumePanel.visibility = if (volumePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val vs = findViewById<SeekBar>(R.id.volume_seek)
            vs.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            vs.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        findViewById<SeekBar>(R.id.volume_seek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        findViewById<View>(R.id.btn_add_to).setOnClickListener { v ->
            val song = PlayerHub.currentSong() ?: return@setOnClickListener
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, R.string.action_add_to_playlist)
                menu.add(0, 2, 1, if (FavoritesStore.contains(ItemType.SONG, song.path)) R.string.action_remove_favorite else R.string.action_add_favorite)
                setOnMenuItemClickListener {
                    if (it.itemId == 1) ItemActions.addToPlaylist(this@NowPlayingActivity, listOf(song))
                    else ItemActions.toggleFavorite(this@NowPlayingActivity, songTarget(song))
                    true
                }
            }.show()
        }
        findViewById<View>(R.id.btn_share).setOnClickListener { v ->
            val song = PlayerHub.currentSong() ?: return@setOnClickListener
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, R.string.share)
                menu.add(0, 2, 1, R.string.action_share)
                setOnMenuItemClickListener {
                    if (it.itemId == 1) ItemActions.shareText(this@NowPlayingActivity, song)
                    else ItemActions.share(this@NowPlayingActivity, listOf(song))
                    true
                }
            }.show()
        }
        findViewById<View>(R.id.btn_lyrics).setOnClickListener {
            if (lyricsPanel.visibility == View.VISIBLE) {
                lyricsPanel.visibility = View.GONE
            } else {
                queueList.visibility = View.GONE
                lyricsPanel.visibility = View.VISIBLE
                loadLyrics()
            }
        }
        findViewById<View>(R.id.lyrics_edit).setOnClickListener {
            val song = PlayerHub.currentSong() ?: return@setOnClickListener
            val current = findViewById<TextView>(R.id.lyrics_text).tag as? String ?: ""
            LyricsEditor.edit(this, song, current) { loadLyrics() }
        }
    }

    private fun writeRatingTag(song: Song, value: Int) {
        lifecycleScope.launch {
            if (!TagIO.requestWriteAccess(this@NowPlayingActivity, listOf(song))) return@launch
            withContext(Dispatchers.IO) {
                try {
                    TagIO.write(this@NowPlayingActivity, listOf(song), emptyMap(), TagIO.ArtworkChange.Keep, rating = value)
                    de.shakie.playernext.data.FileRatings.wrote(song, value)
                } catch (_: Exception) { }
            }
        }
    }

    private fun applyOverlayVisibility() {
        val v = if (overlayVisible && queueList.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        overlayTop.visibility = v
        overlayBottom.visibility = v
        if (!overlayVisible) volumePanel.visibility = View.GONE
    }

    private fun loadLyrics() {
        val song = PlayerHub.currentSong() ?: return
        val tv = findViewById<TextView>(R.id.lyrics_text)
        tv.text = getString(R.string.loading)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { TagIO.readLyrics(this@NowPlayingActivity, song, Prefs.lyricsPreferEmbedded) }
            if (PlayerHub.currentSong()?.id != song.id) return@launch
            tv.tag = text
            tv.text = text ?: getString(R.string.no_lyrics)
        }
    }

    // --- controls -----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        findViewById<View>(R.id.btn_play).setOnClickListener { PlayerHub.togglePlayPause() }
        findViewById<View>(R.id.btn_shuffle).setOnClickListener {
            // Says which of the three states comes next; the icons alone are close.
            val next = when {
                PlayerHub.controller?.shuffleModeEnabled != true -> R.string.shuffle_on
                !Prefs.shuffleOldestFirst -> R.string.shuffle_oldest
                else -> R.string.shuffle_off
            }
            PlayerHub.toggleShuffle()
            ItemActions.toast(this, getString(next))
        }
        findViewById<View>(R.id.btn_repeat).setOnClickListener { PlayerHub.cycleRepeat() }
        findViewById<View>(R.id.btn_queue).setOnClickListener { toggleQueue() }
        findViewById<View>(R.id.title_box).setOnClickListener { toggleQueue() }
        findViewById<View>(R.id.btn_info).setOnClickListener { showInfoMenu(it) }
        findViewById<View>(R.id.chapter).setOnClickListener { showChapters() }
        total.setOnClickListener {
            Prefs.displayRemaining = !Prefs.displayRemaining
            updateProgress()
        }
        setupSkipButton(findViewById(R.id.btn_prev), forward = false)
        setupSkipButton(findViewById(R.id.btn_next), forward = true)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = PlayerHub.controller?.duration?.takeIf { it > 0 } ?: return
                elapsed.text = Format.duration(dur * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                userSeeking = false
                val dur = PlayerHub.controller?.duration?.takeIf { it > 0 } ?: return
                PlayerHub.seekTo(dur * seek.progress / 1000)
            }
        })
    }

    /** Tap skips; holding the button scans through the song like PlayerPro. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSkipButton(button: View, forward: Boolean) {
        var scanning = false
        val scan = object : Runnable {
            override fun run() {
                scanning = true
                PlayerHub.seekBy(if (forward) 5_000 else -5_000)
                handler.postDelayed(this, 250)
            }
        }
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scanning = false
                    handler.postDelayed(scan, 500)
                    v.isPressed = true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(scan)
                    v.isPressed = false
                    if (!scanning) {
                        if (forward) PlayerHub.next() else PlayerHub.previous()
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(scan)
                    v.isPressed = false
                }
            }
            true
        }
    }

    private fun toggleQueue() {
        if (queueList.visibility == View.VISIBLE) {
            queueList.visibility = View.GONE
            applyOverlayVisibility()
        } else {
            lyricsPanel.visibility = View.GONE
            queueList.visibility = View.VISIBLE
            overlayTop.visibility = View.GONE
            overlayBottom.visibility = View.GONE
            volumePanel.visibility = View.GONE
            val c = PlayerHub.controller
            (queueList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset((c?.currentMediaItemIndex ?: 0).coerceAtLeast(0), dp(80))
        }
    }

    private fun songTarget(song: Song) =
        de.shakie.playernext.ui.common.Target(ItemType.SONG, song.path, song.title, ArtKey.Album(song.albumId)) { listOf(song) }

    private fun openInLibrary(kind: String, key: String) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN)
                .putExtra("kind", kind).putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun showInfoMenu(anchor: View) {
        val song = PlayerHub.currentSong() ?: return
        val album = Library.snapshot.albumsById[song.albumId]
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 1, R.string.action_show_album)
            menu.add(0, 2, 2, R.string.action_show_artist)
            menu.add(0, 3, 3, R.string.action_show_folder)
            menu.add(0, 4, 4, R.string.action_details)
            if (album != null) menu.add(0, 5, 5, R.string.action_album_info)
            menu.add(0, 6, 6, R.string.action_manage_art)
            menu.add(0, 7, 7, R.string.action_edit_tags)
            menu.add(0, 8, 8, R.string.action_search)
            menu.add(0, 9, 9, R.string.action_ringtone)
            menu.add(0, 10, 10, R.string.action_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openInLibrary(BrowseFragment.TRACKS_ALBUM, song.albumId.toString())
                    2 -> openInLibrary(BrowseFragment.ALBUMS_OF_ARTIST, if (Prefs.artistsUseAlbumArtist) song.effectiveAlbumArtist else song.artist)
                    3 -> openInLibrary(BrowseFragment.FOLDERS, song.folder)
                    4 -> ItemActions.details(this@NowPlayingActivity, song)
                    5 -> album?.let { a -> ItemActions.albumInfo(this@NowPlayingActivity, a) }
                    6 -> ItemActions.manageArt(this@NowPlayingActivity, ArtKey.Album(song.albumId))
                    7 -> TagEditorActivity.start(this@NowPlayingActivity, listOf(song))
                    8 -> startActivity(Intent(this@NowPlayingActivity, MainActivity::class.java).setAction(Intent.ACTION_SEARCH).putExtra("query", song.title))
                    9 -> ItemActions.setRingtone(this@NowPlayingActivity, song)
                    10 -> ItemActions.deleteSongs(this@NowPlayingActivity, listOf(song))
                }
                true
            }
        }.show()
    }

    // --- toolbar menu ------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, R.id.menu_equalizer, 0, R.string.equalizer).apply {
            setIcon(R.drawable.ic_equalizer)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (PlayerHub.currentChapters().size > 1) menu.add(Menu.NONE, R.id.menu_chapters, 1, R.string.chapters)
        menu.add(Menu.NONE, R.id.menu_settings, 1, R.string.settings)
        menu.add(Menu.NONE, R.id.menu_save_queue, 2, R.string.save_queue)
        menu.add(Menu.NONE, R.id.menu_clear_queue, 3, R.string.clear_queue)
        menu.add(Menu.NONE, R.id.menu_party, 4, if (PlayerHub.partyShuffle) R.string.party_off else R.string.party_on)
        menu.add(Menu.NONE, R.id.menu_sleep, 5, R.string.sleep_timer)
        menu.add(Menu.NONE, R.id.menu_exit, 6, R.string.exit)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_equalizer -> startActivity(Intent(this, EqualizerActivity::class.java))
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_chapters -> showChapters()
            R.id.menu_save_queue -> ItemActions.prompt(this, R.string.save_queue, "") { name ->
                PlayerHub.saveQueueAsPlaylist(name)
                ItemActions.toast(this, getString(R.string.queue_saved, name))
            }
            R.id.menu_clear_queue -> PlayerHub.clearQueue()
            R.id.menu_party -> PlayerHub.togglePartyShuffle()
            R.id.menu_sleep -> SleepTimerDialog.show(this)
            R.id.menu_exit -> {
                PlayerHub.stop()
                handler.postDelayed({ PlayerHub.disconnect(); finishAffinity() }, 300)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // --- queue --------------------------------------------------------------------------------

    private fun setupQueue() {
        queueAdapter = QueueAdapter()
        queueList.layoutManager = LinearLayoutManager(this)
        queueList.adapter = queueAdapter
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            private var from = -1
            private var to = -1
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val a = vh.bindingAdapterPosition
                val b = target.bindingAdapterPosition
                if (from == -1) from = a
                to = b
                queueItems = queueItems.toMutableList().apply { add(b, removeAt(a)) }
                queueAdapter.notifyItemMoved(a, b)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                queueItems = queueItems.toMutableList().apply { removeAt(pos) }
                queueAdapter.notifyItemRemoved(pos)
                PlayerHub.removeAt(pos)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (from != -1 && to != -1 && from != to) PlayerHub.move(from, to)
                from = -1
                to = -1
            }
        })
        helper.attachToRecyclerView(queueList)
        touchHelper = helper
    }

    inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {
        var current = -1
            set(value) {
                if (field == value) return
                val old = field
                field = value
                if (old in 0 until itemCount) notifyItemChanged(old)
                if (value in 0 until itemCount) notifyItemChanged(value)
            }

        override fun getItemCount() = queueItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val title = view.findViewById<TextView>(R.id.title)
            private val subtitle = view.findViewById<TextView>(R.id.subtitle)
            private val duration = view.findViewById<TextView>(R.id.duration)
            private val playing = view.findViewById<ImageView>(R.id.playing)
            private val drag = view.findViewById<ImageView>(R.id.drag)
            private val art = view.findViewById<ImageView>(R.id.art)

            @SuppressLint("ClickableViewAccessibility")
            fun bind(position: Int) {
                val item = queueItems[position]
                val song = QueueItems.songOf(item)
                title.text = song?.title ?: item.mediaMetadata.title
                subtitle.text = song?.let { "${it.artist} · ${it.album}" } ?: ""
                duration.text = song?.let { Format.duration(it.duration) } ?: ""
                drag.visibility = View.VISIBLE
                art.visibility = View.VISIBLE
                ArtLoader.load(art, song?.let { ArtKey.Album(it.albumId) }, dp(44))
                val isCurrent = position == current
                playing.visibility = if (isCurrent) View.VISIBLE else View.GONE
                title.setTextColor(if (isCurrent) themeColor(R.attr.pnAccent) else getColor(R.color.pn_on_surface))
                itemView.setOnClickListener { bindingAdapterPosition.takeIf { it >= 0 }?.let { PlayerHub.playAt(it) } }
                drag.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) touchHelper?.startDrag(this)
                    false
                }
                itemView.findViewById<View>(R.id.menu).setOnClickListener { v ->
                    val s = song ?: return@setOnClickListener
                    ItemActions.showMenu(this@NowPlayingActivity, v, songTarget(s), listOf(
                        ItemActions.Extra(getString(R.string.action_remove_from_queue)) {
                            bindingAdapterPosition.takeIf { it >= 0 }?.let { PlayerHub.removeAt(it) }
                        }
                    ))
                }
            }
        }
    }
}
