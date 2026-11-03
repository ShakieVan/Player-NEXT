package de.shakie.playernext.playback

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sleep timer with optional fade out over the last seconds. The service registers itself
 * as executor; the UI only reads the state.
 */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    const val FADE_MS = 15_000L

    var endAt = 0L
        private set
    var fadeOut = true
        private set
    var finishSong = false
        private set
    val active get() = endAt > 0

    /** Implemented by the playback service. */
    var executor: Executor? = null

    interface Executor {
        fun onSleepFadeStep(volume: Float)
        fun onSleepExpired(finishSong: Boolean)
    }

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val left = endAt - System.currentTimeMillis()
            if (left <= 0) return
            executor?.onSleepFadeStep((left.toFloat() / FADE_MS).coerceIn(0f, 1f))
            handler.postDelayed(this, 250)
        }
    }
    private val expireRunnable = Runnable {
        val finish = finishSong
        cancel(restoreVolume = !finish)
        executor?.onSleepExpired(finish)
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun start(minutes: Int, fade: Boolean, finish: Boolean) {
        cancel()
        endAt = System.currentTimeMillis() + minutes * 60_000L
        fadeOut = fade
        finishSong = finish
        handler.postDelayed(expireRunnable, minutes * 60_000L)
        if (fade && !finish) handler.postDelayed(fadeRunnable, (minutes * 60_000L - FADE_MS).coerceAtLeast(0))
        listeners.forEach { it() }
    }

    fun cancel(restoreVolume: Boolean = true) {
        handler.removeCallbacks(expireRunnable)
        handler.removeCallbacks(fadeRunnable)
        endAt = 0
        if (restoreVolume) executor?.onSleepFadeStep(1f)
        listeners.forEach { it() }
    }

    fun remainingMs(): Long = if (active) (endAt - System.currentTimeMillis()).coerceAtLeast(0) else 0
}
