package de.shakie.playernext.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import de.shakie.playernext.Prefs

/**
 * Crossfading with a short-lived second player: the outgoing song continues on the
 * "fader" from its current position and fades out while the main player already plays
 * the next song and fades in. The session only ever sees the main player.
 */
class Crossfader(
    private val main: ExoPlayer,
    private val mixer: VolumeMixer,
    private val createFader: () -> ExoPlayer,
    private val onAutoAdvance: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var fader: ExoPlayer? = null
    private val releaseFader = Runnable { fader?.release(); fader = null }
    private var faderRamp: Runnable? = null
    private val autoTrigger = Runnable { triggerAuto() }

    /** Re-plans the automatic crossfade; call on play state, position jumps and item changes. */
    fun reschedule() {
        handler.removeCallbacks(autoTrigger)
        if (!Prefs.autoCrossfade || !main.isPlaying) return
        if (Prefs.autoCrossfadeShuffleOnly && !main.shuffleModeEnabled) return
        if (!main.hasNextMediaItem() || main.repeatMode == Player.REPEAT_MODE_ONE) return
        // Right after a transition the new duration is often still unknown; metadata has it.
        val duration = main.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: main.currentMediaItem?.mediaMetadata?.durationMs ?: return
        if (duration < Prefs.autoCrossfadeMs * 3L) return
        val speed = main.playbackParameters.speed.coerceAtLeast(0.1f)
        val delay = ((duration - main.currentPosition - Prefs.autoCrossfadeMs) / speed).toLong()
        if (delay > 0) handler.postDelayed(autoTrigger, delay) else if (delay > -500) handler.post(autoTrigger)
    }

    private fun triggerAuto() {
        if (!main.isPlaying || !main.hasNextMediaItem()) return
        val duration = main.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: main.currentMediaItem?.mediaMetadata?.durationMs ?: return
        val remaining = duration - main.currentPosition
        if (remaining > Prefs.autoCrossfadeMs + 1000) {
            reschedule()
            return
        }
        fadeOutCurrent(Prefs.autoCrossfadeMs.toLong())
        onAutoAdvance()
        main.seekToNextMediaItem()
    }

    /** Hands the current song to the fader player; the caller then changes the main track. */
    fun fadeOutCurrent(durationMs: Long) {
        val item = main.currentMediaItem ?: return
        if (!main.isPlaying || durationMs <= 0) return
        handler.removeCallbacks(releaseFader)
        val f = fader ?: createFader().also { fader = it }
        f.setPlaybackParameters(main.playbackParameters)
        f.setMediaItem(item, main.currentPosition)
        f.volume = main.volume
        f.prepare()
        f.play()
        rampFader(f, f.volume, durationMs)
        mixer.rampFade(0f, 1f, durationMs)
    }

    private fun rampFader(f: ExoPlayer, startVolume: Float, durationMs: Long) {
        faderRamp?.let { handler.removeCallbacks(it) }
        val start = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                f.volume = startVolume * (1f - t)
                if (t < 1f) {
                    handler.postDelayed(this, 40)
                } else {
                    f.stop()
                    f.clearMediaItems()
                    faderRamp = null
                    handler.postDelayed(releaseFader, 30_000)
                }
            }
        }
        faderRamp = r
        handler.post(r)
    }

    fun stopFader() {
        faderRamp?.let { handler.removeCallbacks(it) }
        faderRamp = null
        fader?.stop()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        fader?.release()
        fader = null
    }
}
