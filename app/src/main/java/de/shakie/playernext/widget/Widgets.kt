package de.shakie.playernext.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.TaskStackBuilder
import androidx.media3.common.Player
import androidx.core.content.ContextCompat
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.Song
import de.shakie.playernext.playback.PlaybackService
import de.shakie.playernext.playback.QueueItems
import de.shakie.playernext.playback.QueueStore
import de.shakie.playernext.ui.MainActivity
import de.shakie.playernext.ui.nowplaying.NowPlayingActivity
import java.util.concurrent.Executors

open class BaseWidget(private val layout: Int, private val artSize: Int, val coverOptional: Boolean = false) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)

    override fun onDeleted(context: Context, ids: IntArray) = WidgetOptions.forget(context, ids)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Widgets.ACTION_SHUFFLE -> PlaybackService.instance?.player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            Widgets.ACTION_REPEAT -> PlaybackService.instance?.player?.let { it.repeatMode = (it.repeatMode + 1) % 3 }
            Widgets.ACTION_PLAY_PAUSE -> Widgets.playPause(context)
            Widgets.ACTION_NEXT -> Widgets.skip(context, 1)
            Widgets.ACTION_PREVIOUS -> Widgets.skip(context, -1)
            else -> super.onReceive(context, intent)
        }
    }

    /** One widget instance; [options] carry its own background opacity and cover choice. */
    internal fun build(context: Context, state: Widgets.State, options: WidgetOptions): RemoteViews {
        // Inside apply {} below, `javaClass` would be RemoteViews; the button broadcasts must target this provider.
        val provider = this.javaClass
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.w_title, state.song?.title ?: context.getString(R.string.app_name))
            setTextViewText(R.id.w_artist, state.song?.artist ?: context.getString(R.string.empty_queue))
            if (layout == R.layout.widget_medium || layout == R.layout.widget_plus) setTextViewText(R.id.w_album, state.song?.album ?: "")
            if (layout == R.layout.widget_plus) {
                setTextViewText(R.id.w_position, if (state.count > 0) "${state.index + 1}/${state.count}" else "")
                setViewVisibility(R.id.w_position, if (state.count > 0) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.w_art, if (options.cover) View.VISIBLE else View.GONE)
            }
            if (!coverOptional || options.cover) {
                if (state.art != null) setImageViewBitmap(R.id.w_art, state.art) else setImageViewResource(R.id.w_art, R.drawable.placeholder_album)
            }
            setInt(R.id.w_bg, "setImageAlpha", options.opacity * 255 / 100)
            setImageViewResource(R.id.w_play, if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
            // The whole widget opens the player; only the buttons below have their own actions.
            val open = Widgets.openPlayer(context)
            setOnClickPendingIntent(R.id.w_root, open)
            setOnClickPendingIntent(R.id.w_art, open)
            setOnClickPendingIntent(R.id.w_text, open)
            setOnClickPendingIntent(R.id.w_play, Widgets.own(context, provider, Widgets.ACTION_PLAY_PAUSE))
            setOnClickPendingIntent(R.id.w_next, Widgets.own(context, provider, Widgets.ACTION_NEXT))
            setOnClickPendingIntent(R.id.w_prev, Widgets.own(context, provider, Widgets.ACTION_PREVIOUS))
            if (layout == R.layout.widget_medium || layout == R.layout.widget_large) {
                setOnClickPendingIntent(R.id.w_shuffle, Widgets.own(context, provider, Widgets.ACTION_SHUFFLE))
                setOnClickPendingIntent(R.id.w_repeat, Widgets.own(context, provider, Widgets.ACTION_REPEAT))
                setInt(R.id.w_shuffle, "setColorFilter", if (state.shuffle) 0xFFFFB36B.toInt() else 0xFF77737B.toInt())
                setImageViewResource(R.id.w_repeat, if (state.repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
                setInt(R.id.w_repeat, "setColorFilter", if (state.repeat != Player.REPEAT_MODE_OFF) 0xFFFFB36B.toInt() else 0xFF77737B.toInt())
            }
            setViewVisibility(R.id.w_artist, View.VISIBLE)
        }
    }

    val requestedArtSize get() = artSize
}

class WidgetSmall : BaseWidget(R.layout.widget_small, 160)
class WidgetSquare : BaseWidget(R.layout.widget_square, 400)
class WidgetMedium : BaseWidget(R.layout.widget_medium, 320)
class WidgetLarge : BaseWidget(R.layout.widget_large, 600)
class WidgetPlus : BaseWidget(R.layout.widget_plus, 320, coverOptional = true)

/** Per-widget look: background opacity in percent and (Plus widget) whether the cover is shown. */
data class WidgetOptions(val opacity: Int, val cover: Boolean) {
    companion object {
        private const val FILE = "widgets"
        private const val DEFAULT_OPACITY = 45

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun defaults(context: Context): WidgetOptions = prefs(context).let {
            WidgetOptions(it.getInt("opacity_default", DEFAULT_OPACITY), it.getBoolean("cover_default", false))
        }

        fun of(context: Context, id: Int): WidgetOptions {
            val d = defaults(context)
            return prefs(context).let { WidgetOptions(it.getInt("opacity_$id", d.opacity), it.getBoolean("cover_$id", d.cover)) }
        }

        /** Saves the options for [id]; the last choice also becomes the default for newly placed widgets. */
        fun save(context: Context, id: Int?, options: WidgetOptions) {
            prefs(context).edit().apply {
                if (id != null) putInt("opacity_$id", options.opacity).putBoolean("cover_$id", options.cover)
                putInt("opacity_default", options.opacity).putBoolean("cover_default", options.cover)
            }.apply()
        }

        /** Applies [options] to every placed widget (settings screen). */
        fun saveAll(context: Context, options: WidgetOptions) {
            val editor = prefs(context).edit()
            Widgets.placedIds(context).forEach { id -> editor.putInt("opacity_$id", options.opacity).putBoolean("cover_$id", options.cover) }
            editor.putInt("opacity_default", options.opacity).putBoolean("cover_default", options.cover).apply()
        }

        fun forget(context: Context, ids: IntArray) {
            prefs(context).edit().apply { ids.forEach { remove("opacity_$it"); remove("cover_$it") } }.apply()
        }
    }
}

/** Pushes the playback state to all placed widgets; called only on real state changes. */
object Widgets {
    const val ACTION_SHUFFLE = "de.shakie.playernext.widget.SHUFFLE"
    const val ACTION_REPEAT = "de.shakie.playernext.widget.REPEAT"
    const val ACTION_PLAY_PAUSE = "de.shakie.playernext.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "de.shakie.playernext.widget.NEXT"
    const val ACTION_PREVIOUS = "de.shakie.playernext.widget.PREVIOUS"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val providers = listOf(WidgetSmall(), WidgetSquare(), WidgetMedium(), WidgetLarge(), WidgetPlus())
    private var appContext: Context? = null
    private val pending = Runnable { appContext?.let(::updateNow) }

    // The cover of the current song is kept, so position or play-state changes don't decode it again.
    @Volatile private var cachedArt: Pair<Long, Bitmap?>? = null

    class State(
        val song: Song?, val playing: Boolean, val shuffle: Boolean, val repeat: Int,
        val index: Int, val count: Int, val art: Bitmap?,
    )

    fun providerFor(context: Context, id: Int): BaseWidget? {
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return null
        return providers.firstOrNull { it.javaClass.name == info.provider.className }
    }

    fun placedIds(context: Context): List<Int> {
        val manager = AppWidgetManager.getInstance(context)
        return providers.flatMap { manager.getAppWidgetIds(ComponentName(context, it.javaClass)).toList() }
    }

    /** Coalesces bursts of events (queue additions, mode toggles) into one widget update. */
    fun update(context: Context) {
        if (appContext == null) {
            // Custom covers or a rescan may change the current song's art.
            Library.addListener { cachedArt = null; appContext?.let(::update) }
        }
        appContext = context.applicationContext
        main.removeCallbacks(pending)
        main.postDelayed(pending, 120)
    }

    private fun updateNow(app: Context) {
        val manager = AppWidgetManager.getInstance(app)
        val placed = providers.associateWith { manager.getAppWidgetIds(ComponentName(app, it.javaClass)) }.filterValues { it.isNotEmpty() }
        if (placed.isEmpty()) return
        // After a reboot the library isn't loaded yet; its listener brings us back once it is.
        if (!Library.loaded && Library.hasPermission()) {
            Library.ensureLoaded()
            return
        }
        val state = currentState(app)
        executor.execute {
            val song = state.song
            val cached = cachedArt
            val art = when {
                song == null -> null
                cached != null && cached.first == song.id -> cached.second
                else -> ArtLoader.loadBitmap(ArtKey.Song(song.id, song.albumId), placed.keys.maxOf { it.requestedArtSize })
                    .also { cachedArt = song.id to it }
            }
            val full = State(state.song, state.playing, state.shuffle, state.repeat, state.index, state.count, art)
            main.post {
                placed.forEach { (provider, ids) ->
                    ids.forEach { id -> manager.updateAppWidget(id, provider.build(app, full, WidgetOptions.of(app, id))) }
                }
            }
        }
    }

    /** Playback state without the cover (cheap, main thread). */
    fun currentState(context: Context): State {
        val service = PlaybackService.instance
        if (service != null) {
            val exo = service.exo
            return State(QueueItems.songOf(exo.currentMediaItem), exo.playWhenReady, exo.shuffleModeEnabled, exo.repeatMode,
                exo.currentMediaItemIndex, exo.mediaItemCount, null)
        }
        val saved = QueueStore.loadState(context)
        return State(saved?.currentPath?.let { Library.snapshot.songsByPath[it] }, false, saved?.shuffle == true,
            saved?.repeatMode ?: 0, saved?.index ?: 0, saved?.count ?: 0, null)
    }

    /** Builds a widget's views for the configuration preview. */
    fun preview(context: Context, provider: BaseWidget, options: WidgetOptions, art: Bitmap?): RemoteViews {
        val s = currentState(context)
        return provider.build(context, State(s.song, s.playing, s.shuffle, s.repeat, s.index, s.count, art), options)
    }

    // Widget buttons come here instead of going through media3's MediaButtonReceiver: that one drops
    // everything but "play" when it has to start the service, and play/pause would pass the headset logic.

    fun playPause(context: Context) {
        val service = PlaybackService.instance
        if (service != null && service.exo.playWhenReady) {
            service.player.pause()
            return
        }
        // Starting playback goes through the service's media button handling: it starts in the foreground
        // (allowed after a widget tap) and resumes the saved queue when nothing is loaded.
        val play = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            ContextCompat.startForegroundService(context, play)
        } catch (_: IllegalStateException) {
        }
    }

    /** Next/previous; without the service only the saved song moves (nothing starts playing). */
    fun skip(context: Context, delta: Int) {
        val service = PlaybackService.instance
        if (service != null && service.exo.mediaItemCount > 0) {
            if (delta > 0) service.player.seekToNextMediaItem() else service.player.seekToPrevious()
        } else {
            QueueStore.step(context, delta)
            update(context)
        }
    }

    fun own(context: Context, cls: Class<*>, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, action.hashCode() + cls.name.hashCode(), Intent(context, cls).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun openPlayer(context: Context): PendingIntent = TaskStackBuilder.create(context)
        .addNextIntent(Intent(context, MainActivity::class.java))
        .addNextIntent(Intent(context, NowPlayingActivity::class.java))
        .getPendingIntent(7, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)!!
}
