package de.shakie.playernext.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.PresetReverb
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.TaskStackBuilder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.data.FavoritesStore
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.Song
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.playback.dsp.AudioEffects
import de.shakie.playernext.playback.dsp.DspAudioProcessor
import de.shakie.playernext.ui.MainActivity
import de.shakie.playernext.ui.nowplaying.NowPlayingActivity
import de.shakie.playernext.widget.Widgets
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
class PlaybackService : MediaLibraryService(), QueuePlayer.Hooks, SleepTimer.Executor,
    HeadsetButtons.Actions, ShakeDetector.Callback {

    companion object {
        /** Larger queues are not handed to the system, see [beforeQueueGrows]. */
        const val SYSTEM_QUEUE_LIMIT = 500
        private const val RATE_ACTION = "custom_action_rate"
        @Volatile
        var instance: PlaybackService? = null
            private set
        private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
        fun addStateListener(l: () -> Unit) = stateListeners.add(l)
        fun removeStateListener(l: () -> Unit) = stateListeners.remove(l)
    }

    lateinit var exo: ExoPlayer
        private set
    lateinit var player: QueuePlayer
        private set
    private lateinit var session: MediaLibrarySession
    private val mixer = VolumeMixer { if (::exo.isInitialized) exo else null }
    private lateinit var focus: AudioFocusController
    private lateinit var crossfader: Crossfader
    private val stats = PlayStatsTracker { if (::exo.isInitialized) exo else null }
    private lateinit var headset: HeadsetButtons
    private lateinit var shake: ShakeDetector
    private lateinit var browseTree: AutoBrowseTree
    private val handler = Handler(Looper.getMainLooper())
    private var reverb: PresetReverb? = null
    private var pendingManualSkip = false
    private var restoring = false
    private var queueRestored = false
    private val systemQueueCheck = Runnable { updateSystemQueue() }
    /** Whether the system (platform session) gets the queue; see [beforeQueueGrows]. */
    private var systemQueue = true
    /** The queue list changed since it was last written (the small state is written every time). */
    private var queueDirty = true
    private var lastOffload: String? = null
    private var sleepFinishPending = false

    /** PlayerPro's "Partymix": keeps the queue filled with random songs. */
    var partyShuffle = false
        private set

    private val saveQueueRunnable = Runnable { saveQueue() }
    private val statsTick = object : Runnable {
        override fun run() {
            stats.tick()
            if (exo.isPlaying) handler.postDelayed(this, 30_000)
        }
    }

    // --- lifecycle -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        browseTree = AutoBrowseTree(this)
        exo = buildPlayer(main = true)
        player = QueuePlayer(exo, this)
        // Decided before the session exists: it takes the platform session's commands from the start.
        systemQueue = (QueueStore.loadState(this)?.count ?: 0) <= SYSTEM_QUEUE_LIMIT
        focus = AudioFocusController(this, { exo }, mixer)
        crossfader = Crossfader(exo, mixer, { buildPlayer(main = false) }) { stats.finishCurrent(skipped = false) }
        headset = HeadsetButtons({ player }, this)
        shake = ShakeDetector(this, this)

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity())
            .setBitmapLoader(CacheBitmapLoader(ArtBitmapLoader()))
            .setMediaButtonPreferences(buildButtons())
            // PlaybackState already carries position, speed and timestamp; periodic pushes would
            // wake the system UI every few seconds for nothing.
            .setPeriodicPositionUpdateEnabled(false)
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_player) })

        exo.addListener(playerListener)
        SleepTimer.executor = this
        AudioEffects.addListener(effectsListener)
        ChapterStore.addListener(chaptersListener)
        StatsStore.addListener(ratingListener)
        Prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        FavoritesStore.addListener(favoritesListener)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceCallback, handler)
        applyAudioSettings()
        restoreQueue()
    }

    private fun buildPlayer(main: Boolean): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(DspAudioProcessor(metered = main)))
                // Larger PCM buffer: fewer refills and wakeups. Effect changes become audible
                // after at most this delay, which is fine for a music player.
                .setAudioTrackBufferSizeProvider(
                    DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(500_000)
                        .setMaxPcmBufferDurationUs(1_000_000)
                        .build()
                )
                .build()
                .let { if (main) MeteringAudioSink(it) else it }
        }
        return ExoPlayer.Builder(this, renderers)
            // The playback loop otherwise wakes every 10 ms; dynamic scheduling only wakes
            // when a renderer can make progress (biggest CPU saving for audio-only playback).
            .experimentalSetDynamicSchedulingEnabled(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false
            )
            .setHandleAudioBecomingNoisy(main && Prefs.headsetBtAutoStop)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    private fun sessionActivity(): PendingIntent =
        TaskStackBuilder.create(this)
            .addNextIntent(Intent(this, MainActivity::class.java))
            .addNextIntent(Intent(this, NowPlayingActivity::class.java))
            .getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)!!

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        if (Prefs.notifRemoveOnPause && !exo.playWhenReady) {
            getSystemService(NotificationManager::class.java).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        QueueStore.flush()
        instance = null
        SleepTimer.executor = null
        handler.removeCallbacksAndMessages(null)
        AudioEffects.removeListener(effectsListener)
        ChapterStore.removeListener(chaptersListener)
        StatsStore.removeListener(ratingListener)
        Prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        FavoritesStore.removeListener(favoritesListener)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        shake.stop()
        focus.abandon()
        crossfader.release()
        reverb?.release()
        session.release()
        exo.release()
        stateListeners.forEach { it() }
        super.onDestroy()
    }

    // --- player events -------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                if (!focus.request()) {
                    handler.post { exo.pause() }
                    return
                }
            } else {
                focus.onPaused()
                crossfader.stopFader()
                if (sleepFinishPending) {
                    sleepFinishPending = false
                    applyAudioSettings()
                }
                scheduleSave(0)
            }
            updateShake()
            notifyState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) rememberAudiobook(exo.currentMediaItem, exo.currentPosition)
            stats.onPlayingChanged(isPlaying)
            handler.removeCallbacks(statsTick)
            if (isPlaying) handler.postDelayed(statsTick, 30_000)
            crossfader.reschedule()
            Widgets.update(this@PlaybackService)
            notifyState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (restoring) return
            stats.finishCurrent(skipped = pendingManualSkip)
            pendingManualSkip = false
            val song = QueueItems.songOf(mediaItem)
            stats.startItem(song?.path ?: mediaItem?.mediaMetadata?.extras?.getString(QueueItems.EXTRA_PATH), song?.duration ?: 0)
            ChapterStore.request(song?.path)
            resumeAudiobook(mediaItem, song, reason)
            AudioEffects.setReplayGain(if (Prefs.replayGain) Prefs.replayGainDefault + Prefs.replayGainPreamp else 0f, 1f)
            crossfader.reschedule()
            // Queue edits wait until this event round is over: changed inside it, the session would get a
            // timeline event that no longer matches the current index (IllegalStateException in PlayerInfo).
            handler.post {
                topUpPartyShuffle()
                // Shuffle + repeat all: the last song of a round prepares a fresh order for the next one.
                if (!partyShuffle && exo.shuffleModeEnabled && exo.repeatMode == Player.REPEAT_MODE_ALL) player.reshuffleBeforeLast()
            }
            refreshButtons()
            Widgets.update(this@PlaybackService)
            scheduleSave()
            notifyState()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (restoring) return
            crossfader.reschedule()
            // SOURCE_UPDATE only means a song was prepared (its duration is known); the list is unchanged.
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            queueDirty = true
            // Not right away: reordering (shuffle on/off) briefly shrinks the queue to one item before
            // adding the rest back; only the final size counts.
            handler.removeCallbacks(systemQueueCheck)
            handler.post(systemQueueCheck)
            scheduleSave()
            Widgets.update(this@PlaybackService)
            notifyState()
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyReplayGain(tracks)
            exo.duration.takeIf { it > 0 }?.let { stats.updateDuration(it) }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Leaving an audiobook (next song, other queue, end of the book): remember where.
            if (old.mediaItem != null && old.mediaItem?.mediaId != new.mediaItem?.mediaId) rememberAudiobook(old.mediaItem, old.positionMs)
            crossfader.reschedule()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) crossfader.reschedule()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = crossfader.reschedule()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            queueDirty = true
            refreshButtons()
            crossfader.reschedule()
            scheduleSave()
            Widgets.update(this@PlaybackService)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            refreshButtons()
            crossfader.reschedule()
            scheduleSave()
            Widgets.update(this@PlaybackService)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = attachReverb()

        override fun onPlayerError(error: PlaybackException) {
            val unsupported = error.errorCode in listOf(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            )
            if (unsupported && Prefs.skipUnsupported && exo.mediaItemCount > 1) {
                val idx = exo.currentMediaItemIndex
                exo.removeMediaItem(idx)
                exo.prepare()
                exo.play()
            }
        }
    }

    private fun notifyState() = stateListeners.forEach { it() }

    /**
     * Queues above [SYSTEM_QUEUE_LIMIT] are not handed to the system (lock screen, Bluetooth, Android
     * Auto): media3 would copy every entry into several system processes on each change, which with
     * tens of thousands of songs stalls the whole phone. The system then only sees the current song.
     */
    override fun beforeQueueGrows(count: Int) {
        if (count > SYSTEM_QUEUE_LIMIT && systemQueue) {
            systemQueue = false
            refreshNotificationCommands()
        }
    }

    /** After the queue shrank: small queues are shown to the system again. */
    private fun updateSystemQueue() {
        if (!systemQueue && exo.mediaItemCount <= SYSTEM_QUEUE_LIMIT) {
            systemQueue = true
            refreshNotificationCommands()
        }
    }

    override fun beforeManualSkip(forward: Boolean) {
        pendingManualSkip = forward
        if (Prefs.manualCrossfade && exo.isPlaying) crossfader.fadeOutCurrent(Prefs.manualCrossfadeMs.toLong())
    }

    // --- ReplayGain ---------------------------------------------------------------------

    private fun applyReplayGain(tracks: Tracks) {
        if (!Prefs.replayGain) {
            AudioEffects.setReplayGain(0f, 1f)
            return
        }
        var trackGain: Float? = null
        var albumGain: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || group.length == 0) continue
            val meta = group.getTrackFormat(0).metadata ?: continue
            for (i in 0 until meta.length()) {
                val (key, value) = when (val e = meta.get(i)) {
                    is TextInformationFrame -> (e.description ?: "") to e.value
                    is VorbisComment -> e.key to e.value
                    else -> continue
                }
                val number = value.lowercase().replace("db", "").trim().replace(',', '.').toFloatOrNull() ?: continue
                when (key.uppercase()) {
                    "REPLAYGAIN_TRACK_GAIN" -> trackGain = number
                    "REPLAYGAIN_ALBUM_GAIN" -> albumGain = number
                    "REPLAYGAIN_TRACK_PEAK" -> trackPeak = number
                    "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = number
                }
            }
        }
        val gain = (if (Prefs.replayGainAlbum) albumGain ?: trackGain else trackGain ?: albumGain)
        val peak = (if (Prefs.replayGainAlbum) albumPeak ?: trackPeak else trackPeak ?: albumPeak) ?: 1f
        AudioEffects.setReplayGain((gain ?: Prefs.replayGainDefault) + Prefs.replayGainPreamp, peak)
        applyOffload()
    }

    // --- settings -------------------------------------------------------------------------

    private val effectsListener: () -> Unit = {
        applyOffload()
        attachReverb()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "audio_balance", "mono_output", "use_limiter", "loudmax", "loudmax_threshold", "loudmax_output" -> AudioEffects.reloadAudioPrefs()
            "statusbar_custom_action_1", "statusbar_custom_action_2", "auto_rate_button" -> refreshButtons()
            "statusbar_use_prev" -> refreshNotificationCommands()
            "use_shake", "shake_sensitivity", "shake_pause_upsidedown" -> { shake.stop(); updateShake() }
            "auto_cfade", "auto_cfade_time", "auto_cfade_shuffle" -> crossfader.reschedule()
            "shuffle_mode", "shuffle_window" -> {
                if (!partyShuffle) player.reshuffleUpcoming()
                refreshButtons()
                Widgets.update(this)
            }
        }
        applyAudioSettings()
    }

    private fun applyAudioSettings() {
        exo.setHandleAudioBecomingNoisy(Prefs.headsetBtAutoStop)
        exo.pauseAtEndOfMediaItems = Prefs.pauseBetweenSongs || sleepFinishPending
        val params = PlaybackParameters(Prefs.speed, Prefs.pitch)
        if (exo.playbackParameters != params) exo.playbackParameters = params
        applyOffload()
    }

    /** Hardware offload saves power but bypasses the software DSP, so only use it when idle. */
    private fun applyOffload() {
        val want = Prefs.audioOffload && !AudioEffects.dspNeeded() && Prefs.speed == 1f && Prefs.pitch == 1f &&
            !Prefs.autoCrossfade && !Prefs.manualCrossfade
        val key = "$want/${Prefs.offloadRequireGapless}"
        if (key == lastOffload) return
        lastOffload = key
        val prefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (want) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            )
            .setIsGaplessSupportRequired(Prefs.offloadRequireGapless)
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(prefs).build()
    }

    private fun attachReverb() {
        val preset = AudioEffects.reverbPreset
        if (preset == 0) {
            reverb?.release()
            reverb = null
            return
        }
        try {
            val sessionId = exo.audioSessionId
            if (reverb == null && sessionId != C.AUDIO_SESSION_ID_UNSET) reverb = PresetReverb(0, sessionId)
            reverb?.preset = preset.toShort()
            reverb?.enabled = true
        } catch (_: Exception) {
            reverb = null
        }
    }

    private val favoritesListener: () -> Unit = { refreshButtons() }

    // --- notification buttons ---------------------------------------------------------------

    private fun refreshButtons() {
        if (::session.isInitialized) session.setMediaButtonPreferences(buildButtons())
    }

    /**
     * The two configurable extra buttons, plus "rate" for Android Auto (it shows the buttons of the
     * platform session, which are the notification's); previous/play/next come from Media3 itself.
     */
    private fun buildButtons(): List<CommandButton> {
        val list = ArrayList<CommandButton>()
        customButton(Prefs.notifCustomLeft)?.let { list += it }
        customButton(Prefs.notifCustomRight)?.let { list += it }
        if (Prefs.rateButton && RATE_ACTION != Prefs.notifCustomLeft && RATE_ACTION != Prefs.notifCustomRight) customButton(RATE_ACTION)?.let { list += it }
        return list
    }

    /** The notification hides "previous" by withholding the command from its controller. */
    private fun notificationPlayerCommands(): Player.Commands {
        val all = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
        if (!Prefs.notifUsePrev) all.removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        // Without the timeline the controller still gets the current item (media3 copyWithSingleWindow).
        if (!systemQueue) all.remove(Player.COMMAND_GET_TIMELINE)
        return all.build()
    }

    private fun refreshNotificationCommands() {
        if (!::session.isInitialized) return
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        SessionCommands.all.forEach { sessionCommands.add(SessionCommand(it, Bundle.EMPTY)) }
        session.connectedControllers.filter { session.isMediaNotificationController(it) }.forEach {
            session.setAvailableCommands(it, sessionCommands.build(), notificationPlayerCommands())
        }
    }

    private val ratingIcons = intArrayOf(
        R.drawable.ic_rating_0, R.drawable.ic_rating_1, R.drawable.ic_rating_2,
        R.drawable.ic_rating_3, R.drawable.ic_rating_4, R.drawable.ic_rating_5,
    )

    // Ratings set elsewhere (player, lists, tag editor) update the stars on the button.
    private val ratingListener: () -> Unit = { refreshButtons() }

    private fun customButton(action: String): CommandButton? {
        val song = if (::exo.isInitialized) QueueItems.songOf(exo.currentMediaItem) else null
        val (icon, command, name) = when (action) {
            "custom_action_shuffle" -> Triple(
                when {
                    !::exo.isInitialized || !exo.shuffleModeEnabled -> CommandButton.ICON_SHUFFLE_OFF
                    Prefs.shuffleOldestFirst -> CommandButton.ICON_UNDEFINED
                    else -> CommandButton.ICON_SHUFFLE_ON
                },
                SessionCommands.command(SessionCommands.SHUFFLE), R.string.shuffle
            )
            "custom_action_repeat" -> Triple(
                when (if (::exo.isInitialized) exo.repeatMode else Player.REPEAT_MODE_OFF) {
                    Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                    Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                    else -> CommandButton.ICON_REPEAT_OFF
                },
                SessionCommands.command(SessionCommands.REPEAT), R.string.repeat
            )
            // One tap = one star more, after five none; the icon shows the current number of stars.
            RATE_ACTION -> Triple(CommandButton.ICON_UNDEFINED, SessionCommands.command(SessionCommands.RATE), R.string.rate)
            "custom_action_favorite" -> Triple(
                if (song != null && FavoritesStore.contains(ItemType.SONG, song.path)) CommandButton.ICON_HEART_FILLED
                else CommandButton.ICON_HEART_UNFILLED,
                SessionCommands.command(SessionCommands.FAVORITE), R.string.favorite
            )
            "custom_action_stop" -> Triple(CommandButton.ICON_STOP, SessionCommands.command(SessionCommands.STOP), R.string.stop)
            "custom_action_rewind_5" -> Triple(CommandButton.ICON_SKIP_BACK_5, SessionCommands.seek(SessionCommands.REWIND, 5), R.string.rewind)
            "custom_action_rewind_10" -> Triple(CommandButton.ICON_SKIP_BACK_10, SessionCommands.seek(SessionCommands.REWIND, 10), R.string.rewind)
            "custom_action_rewind_30" -> Triple(CommandButton.ICON_SKIP_BACK_30, SessionCommands.seek(SessionCommands.REWIND, 30), R.string.rewind)
            "custom_action_skip_5" -> Triple(CommandButton.ICON_SKIP_FORWARD_5, SessionCommands.seek(SessionCommands.SKIP, 5), R.string.fast_forward)
            "custom_action_skip_10" -> Triple(CommandButton.ICON_SKIP_FORWARD_10, SessionCommands.seek(SessionCommands.SKIP, 10), R.string.fast_forward)
            "custom_action_skip_30" -> Triple(CommandButton.ICON_SKIP_FORWARD_30, SessionCommands.seek(SessionCommands.SKIP, 30), R.string.fast_forward)
            else -> return null
        }
        val stars = if (action == RATE_ACTION && song != null) (StatsStore[song.path].rating + 1) / 2 else 0
        return CommandButton.Builder(icon)
            .apply {
                if (icon == CommandButton.ICON_UNDEFINED) {
                    setCustomIconResId(if (action == RATE_ACTION) ratingIcons[stars] else R.drawable.ic_shuffle_history)
                }
            }
            .setSessionCommand(command)
            .setDisplayName(if (action == RATE_ACTION) resources.getQuantityString(R.plurals.rate_stars, stars, stars) else getString(name))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    }

    // --- custom commands ------------------------------------------------------------------

    fun handleCustomCommand(action: String, args: Bundle): Boolean {
        val song = QueueItems.songOf(exo.currentMediaItem)
        when (action) {
            SessionCommands.FAVORITE -> song?.let { FavoritesStore.toggle(ItemType.SONG, it.path, it.title) }
            SessionCommands.STOP -> stopPlayback()
            SessionCommands.RATE -> song?.let {
                val r = StatsStore[it.path].rating
                StatsStore.setRating(it.path, if (r >= 10) 0 else (r / 2 + 1) * 2)
                refreshButtons()
            }
            SessionCommands.SHUFFLE -> cycleShuffle()
            SessionCommands.REPEAT -> player.repeatMode = (player.repeatMode + 1) % 3
            SessionCommands.REWIND -> exo.seekTo((exo.currentPosition - args.getInt(SessionCommands.EXTRA_SECONDS, 10) * 1000L).coerceAtLeast(0))
            SessionCommands.SKIP -> exo.seekTo(exo.currentPosition + args.getInt(SessionCommands.EXTRA_SECONDS, 10) * 1000L)
            SessionCommands.PARTY_SHUFFLE -> togglePartyShuffle()
            SessionCommands.CLEAR_QUEUE -> clearQueue()
            else -> return false
        }
        return true
    }

    fun stopPlayback() {
        exo.pause()
        exo.seekTo(0)
        saveQueue()
        focus.abandon()
        pauseAllPlayersAndStopSelf()
    }

    fun clearQueue() {
        partyShuffle = false
        exo.stop()
        player.clearMediaItems()
        saveQueue()
        notifyState()
    }

    // Chapters known: the notification buttons (⏮/⏭ availability) are rebuilt.
    private val chaptersListener: (String) -> Unit = { path ->
        if (QueueItems.songOf(exo.currentMediaItem)?.path == path) refreshButtons()
    }

    private fun rememberAudiobook(item: MediaItem?, positionMs: Long) {
        val song = QueueItems.songOf(item) ?: return
        if (song.isAudiobook && Prefs.audiobookResume) AudiobookPositions.save(this, song.path, positionMs, song.duration)
    }

    /** An audiobook continues where it was left, unless it starts somewhere on purpose. */
    private fun resumeAudiobook(item: MediaItem?, song: Song?, reason: Int) {
        if (song == null || !song.isAudiobook || !Prefs.audiobookResume || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        val saved = AudiobookPositions.get(this, song.path) ?: return
        handler.post {
            if (exo.currentMediaItem === item && exo.currentPosition < 3000) exo.seekTo(saved)
        }
    }

    /**
     * The shuffle button (player, widget, notification): off, evenly random, "long unheard
     * first", off again. Switching between the two random modes reorders what is still to come
     * (prefListener).
     */
    fun cycleShuffle() {
        when {
            !player.shuffleModeEnabled -> {
                Prefs.shuffleOldestFirst = false
                player.shuffleModeEnabled = true
            }
            !Prefs.shuffleOldestFirst -> Prefs.shuffleOldestFirst = true
            else -> player.shuffleModeEnabled = false
        }
    }

    // --- party shuffle ---------------------------------------------------------------------

    override fun togglePartyShuffle() = setPartyShuffle(!partyShuffle)

    fun setPartyShuffle(enabled: Boolean) {
        partyShuffle = enabled
        if (enabled) {
            if (exo.mediaItemCount == 0) {
                val songs = ShuffleBag.next(this, 10)
                if (songs.isNotEmpty()) {
                    player.setMediaItems(songs.map { QueueItems.build(it) }, 0, 0)
                    player.prepare()
                    player.play()
                }
            }
            topUpPartyShuffle()
        }
        notifyState()
    }

    /**
     * Keeps ten songs ahead. They come from [ShuffleBag], so even in a library of tens of
     * thousands of songs none repeats before all were played once.
     */
    private fun topUpPartyShuffle() {
        if (!partyShuffle) return
        val upcoming = exo.mediaItemCount - exo.currentMediaItemIndex - 1
        if (upcoming < 10) {
            val inQueue = HashSet<Long>()
            for (i in 0 until exo.mediaItemCount) QueueItems.songIdOf(exo.getMediaItemAt(i).mediaId)?.let(inQueue::add)
            player.addMediaItems(ShuffleBag.next(this, 10 - upcoming, inQueue).map { QueueItems.build(it) })
        }
        // Keep a short history only.
        val history = exo.currentMediaItemIndex
        if (history > 20) exo.removeMediaItems(0, history - 20)
    }

    override fun openLibrary() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- sleep timer --------------------------------------------------------------------------

    override fun onSleepFadeStep(volume: Float) = mixer.setSleep(volume)

    override fun onSleepExpired(finishSong: Boolean) {
        if (finishSong && exo.isPlaying) {
            // Let the current song end, then pause (reset in onPlayWhenReadyChanged).
            sleepFinishPending = true
            applyAudioSettings()
            return
        }
        exo.pause()
        handler.postDelayed({ mixer.setSleep(1f) }, 500)
        notifyState()
    }

    // --- shake ------------------------------------------------------------------------------

    private fun updateShake() {
        if (Prefs.useShake && exo.playWhenReady) {
            shake.sensitivity = Prefs.shakeSensitivity
            shake.flipPause = Prefs.shakeFlipPause
            shake.start()
        } else {
            shake.stop()
        }
    }

    override fun onShake(next: Boolean) {
        if (next) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
    }

    override fun onFaceDown() {
        if (Prefs.shakeFlipPause) exo.pause()
    }

    // --- headset connect auto start -----------------------------------------------------------

    private val deviceCallback = object : AudioDeviceCallback() {
        private var initial = true
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // The callback reports existing devices right after registration; ignore those.
            if (initial) {
                initial = false
                return
            }
            val wired = addedDevices.any {
                it.isSink && it.type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
            }
            val bt = addedDevices.any { it.isSink && it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            if ((wired && Prefs.headsetAutoStart) || (bt && Prefs.headsetBtAutoStart)) {
                if (exo.mediaItemCount > 0 && !exo.playWhenReady) {
                    exo.prepare()
                    player.play()
                }
            }
        }
    }

    // --- queue persistence ---------------------------------------------------------------------

    private fun scheduleSave(delay: Long = 1500) {
        handler.removeCallbacks(saveQueueRunnable)
        handler.postDelayed(saveQueueRunnable, delay)
    }

    private fun saveQueue() {
        if (restoring || !::exo.isInitialized) return
        val count = exo.mediaItemCount
        if (queueDirty) {
            queueDirty = false
            QueueStore.saveItems(this, (0 until count).map { pathOf(exo.getMediaItemAt(it)) }, player.originalPositions())
        }
        QueueStore.saveState(this, QueueStore.State(
            exo.currentMediaItemIndex, exo.currentPosition, exo.shuffleModeEnabled, exo.repeatMode, count,
            exo.currentMediaItem?.let(::pathOf),
        ))
    }

    private fun pathOf(item: MediaItem): String =
        QueueItems.songOf(item)?.path ?: item.mediaMetadata.extras?.getString(QueueItems.EXTRA_PATH) ?: ""

    private fun restoreQueue() {
        if (Library.loaded) restoreNow() else Library.refresh { restoreNow() }
    }

    /**
     * Puts the saved queue into the empty player, once per service start. Playback resumption (a
     * "play" button while the service wasn't running) calls this too and then hands back exactly
     * this queue, so a shuffled queue is never shuffled a second time.
     */
    private fun restoreNow() {
        if (queueRestored) return
        queueRestored = true
        if (exo.mediaItemCount > 0) return
        val saved = QueueStore.load(this) ?: return
        if (saved.paths.isEmpty()) return
        val lib = Library.snapshot
        val items = ArrayList<MediaItem>()
        var index = 0
        saved.paths.forEachIndexed { i, p ->
            val s = lib.songsByPath[p] ?: return@forEachIndexed
            if (i == saved.index) index = items.size
            items += QueueItems.build(s)
        }
        if (items.isNotEmpty() && exo.mediaItemCount == 0) {
            beforeQueueGrows(items.size)
            restoring = true
            exo.setMediaItems(items, index.coerceIn(0, items.size - 1), saved.positionMs)
            exo.repeatMode = saved.repeatMode
            val idByPath = HashMap<String, ArrayDeque<String>>()
            items.forEach { idByPath.getOrPut(QueueItems.songOf(it)?.path ?: "") { ArrayDeque() }.add(it.mediaId) }
            val original = saved.originalPaths?.mapNotNull { idByPath[it]?.removeFirstOrNull() }
            player.restoreShuffleState(saved.shuffle, original)
            exo.prepare()
            restoring = false
            // Songs that are gone from the library were dropped: then the stored list needs rewriting.
            queueDirty = items.size != saved.paths.size
            val song = QueueItems.songOf(exo.currentMediaItem)
            stats.startItem(song?.path, song?.duration ?: 0)
            Widgets.update(this)
            notifyState()
        }
    }

    // --- session callback ----------------------------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            SessionCommands.all.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setMediaButtonPreferences(buildButtons())
            if (session.isMediaNotificationController(controller)) builder.setAvailablePlayerCommands(notificationPlayerCommands())
            return builder.build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            val merged = Bundle(customCommand.customExtras).apply { putAll(args) }
            val ok = handleCustomCommand(customCommand.customAction, merged)
            return Futures.immediateFuture(SessionResult(if (ok) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
            headset.handle(intent)

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            val resolve = { future.set(mediaItems.mapNotNull { QueueItems.resolve(it) }.toMutableList()) }
            if (Library.loaded) resolve() else Library.refresh { resolve() }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val resolve = {
                // A song picked in a browsed list (Android Auto) plays its whole list.
                val single = mediaItems.singleOrNull()?.mediaId
                if (single != null && single.contains("|song:")) {
                    val parent = single.substringBefore("|song:")
                    val id = single.substringAfter("|song:").toLongOrNull()
                    val songs = browseTree.songsOf(parent)
                    val start = songs.indexOfFirst { it.id == id }.coerceAtLeast(0)
                    future.set(MediaSession.MediaItemsWithStartPosition(songs.map { QueueItems.build(it) }, start, 0))
                } else {
                    val items = mediaItems.mapNotNull { QueueItems.resolve(it) }
                    future.set(MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs))
                }
            }
            if (Library.loaded) resolve() else Library.refresh { resolve() }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val resolve = {
                restoreNow()
                if (exo.mediaItemCount == 0) {
                    future.setException(IllegalStateException("no queue"))
                } else {
                    val items = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }
                    future.set(MediaSession.MediaItemsWithStartPosition(items, exo.currentMediaItemIndex, exo.currentPosition))
                }
            }
            if (Library.loaded) resolve() else Library.refresh { resolve() }
            return future
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val resolve = {
                val all = browseTree.children(parentId)
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size)
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
            }
            if (Library.loaded) resolve() else Library.refresh { resolve() }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val song = QueueItems.songIdOf(mediaId.substringAfter("|song:"))?.let { Library.snapshot.songsById[it] }
            return Futures.immediateFuture(
                if (song != null) LibraryResult.ofItem(QueueItems.build(song), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val count = Library.snapshot.songs.count { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
            session.notifySearchResultChanged(browser, query, count, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val hits = Library.snapshot.songs
                .filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
                .drop(page * pageSize).take(pageSize)
                .map { QueueItems.build(it) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(hits), params))
        }
    }
}
