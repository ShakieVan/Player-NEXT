package de.shakie.playernext.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.Player
import de.shakie.playernext.Prefs

/** Audio focus handling with PlayerPro's three configurable reactions. */
class AudioFocusController(context: Context, private val player: () -> Player?, private val mixer: VolumeMixer) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private var pausedByTransientLoss = false
    private var internalPause = false

    private fun pauseInternally(p: Player) {
        internalPause = true
        try {
            p.pause()
        } finally {
            internalPause = false
        }
    }

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val p = player() ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pausedByTransientLoss = false
                if (Prefs.focusPauseOnLoss) pauseInternally(p)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (Prefs.focusPauseOnTransient && p.playWhenReady) {
                    pausedByTransientLoss = true
                    pauseInternally(p)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (Prefs.focusDuck) {
                    mixer.setDuck(0.2f)
                } else if (Prefs.focusPauseOnTransient && p.playWhenReady) {
                    pausedByTransientLoss = true
                    pauseInternally(p)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                mixer.setDuck(1f)
                if (pausedByTransientLoss) {
                    pausedByTransientLoss = false
                    p.play()
                }
            }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener)
        .build()

    /** Returns false when focus was refused and playback must not start. */
    fun request(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    /** A pause not caused by focus loss gives the focus back to other apps. */
    fun onPaused() {
        if (internalPause || pausedByTransientLoss) return
        abandon()
    }

    fun abandon() {
        pausedByTransientLoss = false
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(request)
        hasFocus = false
    }
}
