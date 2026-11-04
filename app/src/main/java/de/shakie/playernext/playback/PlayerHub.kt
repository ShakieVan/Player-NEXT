package de.shakie.playernext.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.shakie.playernext.Prefs
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.Song
import java.util.concurrent.CopyOnWriteArrayList

/** UI side access to the playback service through a MediaController. */
object PlayerHub {
    private var future: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set
    private val pending = ArrayList<(MediaController) -> Unit>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private lateinit var appContext: Context

    /** Events that change what the UI shows; loading/volume chatter is ignored. */
    private val relevant = intArrayOf(
        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED,
        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_TRACKS_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    )

    /** Increases whenever the queue's content or order changes; lets views skip comparing long queues. */
    var queueVersion = 0
        private set
    private var cachedQueue: List<MediaItem> = emptyList()
    private var cachedVersion = -1

    private val forward = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // A prepared song (SOURCE_UPDATE) leaves the list as it is; only real edits rebuild it.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) queueVersion++
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(*relevant)) notifyListeners()
        }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyListeners() = listeners.forEach { it() }

    fun connect(context: Context) {
        appContext = context.applicationContext
        if (future != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val f = MediaController.Builder(appContext, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (_: Exception) { null }
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            queueVersion++
            c.addListener(forward)
            val queued = ArrayList(pending)
            pending.clear()
            queued.forEach { it(c) }
            notifyListeners()
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Called when the service died (e.g. after "Beenden"); the next action reconnects. */
    fun disconnect() {
        controller?.removeListener(forward)
        controller?.release()
        controller = null
        future = null
        queueVersion++
        notifyListeners()
    }

    private fun with(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) {
            block(c)
        } else {
            if (c != null) disconnect()
            pending += block
            connect(appContext)
        }
    }

    // --- queue building ----------------------------------------------------------------------

    fun play(all: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        // Shuffling music leaves audiobooks out; a list of only audiobooks shuffles as it is.
        val songs = if (shuffle && all.any { !it.isAudiobook }) all.filter { !it.isAudiobook } else all
        if (songs.isEmpty()) return
        with { c ->
            val items = songs.map { QueueItems.request(it) }
            if (shuffle && !c.shuffleModeEnabled) {
                // Shuffle goes on before the new list arrives, so the session shuffles it once (start song
                // first) instead of storing it in order and reordering all of it again.
                c.clearMediaItems()
                c.shuffleModeEnabled = true
            }
            c.setMediaItems(items, if (shuffle) Shuffler.pickStart(songs) { it.path } else startIndex.coerceIn(0, songs.size - 1), 0)
            c.prepare()
            c.play()
        }
    }

    fun enqueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        with { c ->
            val wasEmpty = c.mediaItemCount == 0
            c.addMediaItems(songs.map { QueueItems.request(it) })
            if (wasEmpty) {
                c.prepare()
                c.play()
            }
        }
    }

    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        with { c ->
            if (c.mediaItemCount == 0) {
                play(songs)
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { QueueItems.request(it) })
            }
        }
    }

    fun togglePlayPause() = with { c ->
        if (c.mediaItemCount == 0) return@with
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
        if (c.playWhenReady) c.pause() else c.play()
    }

    /** ⏭; inside an audiobook with chapters the next chapter (in-process, like the session's own player). */
    fun next() = with { c ->
        val target = if (Prefs.chapterSkip) ChapterNav.jumpTarget(currentChapters(), c.currentPosition, forward = true) else null
        if (target != null) c.seekTo(target) else c.seekToNextMediaItem()
    }

    /** Chapters of the song playing, if it has any (audiobooks). */
    fun currentChapters(): List<de.shakie.playernext.playback.Chapter> =
        ChapterStore.cached(currentSong()?.path).orEmpty()

    fun previous() = with { c ->
        val target = if (Prefs.chapterSkip) ChapterNav.jumpTarget(currentChapters(), c.currentPosition, forward = false) else null
        if (target != null) c.seekTo(target) else c.seekToPrevious()
    }
    fun seekTo(ms: Long) = with { it.seekTo(ms) }
    fun seekBy(deltaMs: Long) = with { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) }
    /** Off, evenly random, "long unheard first", off again (PlaybackService.cycleShuffle). */
    fun toggleShuffle() = with { it.sendCustomCommand(SessionCommands.command(SessionCommands.SHUFFLE), Bundle.EMPTY) }
    fun cycleRepeat() = with {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun playAt(index: Int) = with {
        it.seekTo(index, 0)
        it.play()
    }
    fun removeAt(index: Int) = with { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = with { it.moveMediaItem(from, to) }

    fun clearQueue() = with { it.sendCustomCommand(SessionCommands.command(SessionCommands.CLEAR_QUEUE), Bundle.EMPTY) }
    fun togglePartyShuffle() = with { it.sendCustomCommand(SessionCommands.command(SessionCommands.PARTY_SHUFFLE), Bundle.EMPTY) }
    fun stop() = with { it.sendCustomCommand(SessionCommands.command(SessionCommands.STOP), Bundle.EMPTY) }

    // --- state -----------------------------------------------------------------------------

    val isPlaying: Boolean get() = controller?.isPlaying == true
    val playWhenReady: Boolean get() = controller?.playWhenReady == true
    val partyShuffle: Boolean get() = PlaybackService.instance?.partyShuffle == true

    fun currentSong(): Song? = QueueItems.songOf(controller?.currentMediaItem)

    fun queueItems(): List<MediaItem> {
        val c = controller ?: return emptyList()
        if (cachedVersion != queueVersion || cachedQueue.size != c.mediaItemCount) {
            cachedQueue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
            cachedVersion = queueVersion
        }
        return cachedQueue
    }

    fun queueSongs(): List<Song> = queueItems().mapNotNull { QueueItems.songOf(it) }

    fun hasQueue(): Boolean = (controller?.mediaItemCount ?: 0) > 0

    fun saveQueueAsPlaylist(name: String): Long {
        val paths = queueSongs().map { it.path }
        val existing = PlaylistStore.byName(name)
        return if (existing != null) {
            PlaylistStore.setItems(existing.id, paths); existing.id
        } else PlaylistStore.create(name, paths)
    }
}
