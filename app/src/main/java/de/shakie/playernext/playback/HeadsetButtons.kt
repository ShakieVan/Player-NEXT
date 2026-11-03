package de.shakie.playernext.playback

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.Player
import de.shakie.playernext.Prefs

/**
 * Single/double/triple press and long press on the headset button, as configured.
 * Returns false for events it leaves to Media3's default handling.
 */
class HeadsetButtons(private val player: () -> Player?, private val actions: Actions) {
    interface Actions {
        fun togglePartyShuffle()
        fun openLibrary()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var presses = 0
    private var longPressHandled = false
    private val resolve = Runnable {
        val p = player()
        when (presses) {
            1 -> p?.let { if (it.playWhenReady) it.pause() else it.play() }
            2 -> p?.seekToNextMediaItem()
            else -> p?.seekToPrevious()
        }
        presses = 0
    }

    fun handle(intent: Intent): Boolean {
        if (!Prefs.useHeadset) return false
        val event = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_HEADSETHOOK && code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longPressHandled = false
                } else if (!longPressHandled && (event.isLongPress || event.repeatCount >= 5)) {
                    longPressHandled = true
                    handler.removeCallbacks(resolve)
                    presses = 0
                    onLongPress()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (longPressHandled) return true
                if (!Prefs.headsetMultiPress) {
                    presses = 1
                    resolve.run()
                    return true
                }
                presses++
                handler.removeCallbacks(resolve)
                if (presses >= 3) resolve.run() else handler.postDelayed(resolve, 450)
            }
        }
        return true
    }

    private fun onLongPress() {
        when (Prefs.headsetLongPress) {
            "next" -> player()?.seekToNextMediaItem()
            "partyshuffle" -> actions.togglePartyShuffle()
            "openml" -> actions.openLibrary()
            else -> player()?.let { if (it.playWhenReady) it.pause() else it.play() }
        }
    }
}
