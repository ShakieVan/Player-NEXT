package de.shakie.playernext.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object SessionCommands {
    const val FAVORITE = "pn.favorite"
    const val STOP = "pn.stop"
    const val RATE = "pn.rate"
    const val SHUFFLE = "pn.shuffle"
    const val REPEAT = "pn.repeat"
    const val REWIND = "pn.rewind"
    const val SKIP = "pn.skip"
    const val PARTY_SHUFFLE = "pn.party"
    const val CLEAR_QUEUE = "pn.clear"
    const val EXTRA_SECONDS = "seconds"

    val all = listOf(FAVORITE, STOP, RATE, SHUFFLE, REPEAT, REWIND, SKIP, PARTY_SHUFFLE, CLEAR_QUEUE)

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)

    fun seek(action: String, seconds: Int) = SessionCommand(action, Bundle().apply { putInt(EXTRA_SECONDS, seconds) })
}
