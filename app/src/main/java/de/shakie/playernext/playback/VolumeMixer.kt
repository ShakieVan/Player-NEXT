package de.shakie.playernext.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

/**
 * Combines the independent volume reasons (crossfade, ducking, sleep timer fade) into the
 * single player volume and runs short ramps. No timer runs while nothing is fading.
 */
class VolumeMixer(private val player: () -> Player?) {
    private val handler = Handler(Looper.getMainLooper())
    var fade = 1f
        private set
    var duck = 1f
        private set
    var sleep = 1f
        private set
    private var ramp: Runnable? = null

    private fun apply() {
        player()?.volume = (fade * duck * sleep).coerceIn(0f, 1f)
    }

    fun setDuck(value: Float) {
        duck = value
        apply()
    }

    fun setSleep(value: Float) {
        sleep = value
        apply()
    }

    fun rampFade(from: Float, to: Float, durationMs: Long) {
        ramp?.let { handler.removeCallbacks(it) }
        fade = from
        apply()
        if (durationMs <= 0) {
            fade = to
            apply()
            return
        }
        val start = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                fade = from + (to - from) * t
                apply()
                if (t < 1f) handler.postDelayed(this, 40) else ramp = null
            }
        }
        ramp = r
        handler.post(r)
    }

    fun reset() {
        ramp?.let { handler.removeCallbacks(it) }
        ramp = null
        fade = 1f
        duck = 1f
        sleep = 1f
        apply()
    }
}
