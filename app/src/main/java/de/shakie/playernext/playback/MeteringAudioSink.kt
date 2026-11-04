package de.shakie.playernext.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import de.shakie.playernext.playback.dsp.LevelMeter
import java.nio.ByteBuffer

/**
 * Hands the main player's sink timing to [LevelMeter]: the media time of each buffer before
 * the DSP sees it, and the playback position, so the equalizer's meters show what is audible
 * rather than what was processed up to a second earlier.
 */
class MeteringAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (LevelMeter.enabled) LevelMeter.bufferTimeUs = presentationTimeUs
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also { LevelMeter.onPosition(it) }

    override fun play() {
        super.play()
        LevelMeter.onPlaying(true)
    }

    override fun pause() {
        super.pause()
        LevelMeter.onPlaying(false)
    }

    override fun flush() {
        super.flush()
        LevelMeter.clear()
    }

    override fun reset() {
        super.reset()
        LevelMeter.onPlaying(false)
        LevelMeter.clear()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        LevelMeter.speed = playbackParameters.speed
    }
}
