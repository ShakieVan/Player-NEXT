package de.shakie.playernext.playback

import android.os.SystemClock
import androidx.media3.common.Player
import de.shakie.playernext.data.StatsStore

/**
 * Counts a play once at least half of a song (or four minutes) was heard, and a skip
 * when the user leaves a song early. Only listened wall time counts, not seeking.
 */
class PlayStatsTracker(private val player: () -> Player?) {
    private var path: String? = null
    private var duration = 0L
    private var listenedMs = 0L
    private var playingSince = 0L
    private var counted = false

    fun onPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playingSince = SystemClock.elapsedRealtime()
        } else {
            accumulate()
            playingSince = 0
        }
        checkThreshold()
    }

    private fun accumulate() {
        if (playingSince > 0) {
            val now = SystemClock.elapsedRealtime()
            listenedMs += now - playingSince
            playingSince = now
        }
    }

    private fun checkThreshold() {
        val p = path ?: return
        if (counted || duration <= 0) return
        if (listenedMs >= duration / 2 || listenedMs >= 240_000) {
            counted = true
            StatsStore.recordPlay(p)
        }
    }

    /** Call before the current item changes. */
    fun finishCurrent(skipped: Boolean) {
        accumulate()
        checkThreshold()
        val p = path
        if (p != null && skipped && !counted && listenedMs < duration / 2) StatsStore.recordSkip(p)
    }

    fun startItem(newPath: String?, newDuration: Long) {
        path = newPath
        duration = newDuration
        listenedMs = 0
        counted = false
        playingSince = if (player()?.isPlaying == true) SystemClock.elapsedRealtime() else 0
    }

    fun updateDuration(d: Long) {
        if (d > 0) duration = d
    }

    /** Periodic check (cheap) so a play is counted without waiting for the next transition. */
    fun tick() {
        accumulate()
        checkThreshold()
    }
}
