package de.shakie.playernext.playback.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Loudness maximizer in the manner of LoudMax: a look-ahead brickwall limiter. The input is
 * raised by -[thresholdDb] and no output peak ever exceeds [ceilingDb]. The gain needed for a
 * peak is known 1.5 ms before the peak arrives (the audio is delayed by that much), so it can
 * fade in instead of clipping; afterwards it recovers smoothly. Both channels share one gain,
 * so the stereo image stays put.
 *
 * Gain path per frame: required gain → minimum over the look-ahead window → instant attack /
 * exponential release → moving average over the window. Minimum and average of the same length
 * guarantee that the gain at the delayed peak is never above what that peak needs.
 */
class LoudnessMaximizer(private val channels: Int, sampleRate: Int, releaseMs: Float = 120f) {
    private val window = max(1, (0.0015 * sampleRate).roundToInt())
    private val delay = window - 1
    private val releaseCoef = exp(-1.0 / (releaseMs / 1000.0 * sampleRate)).toFloat()

    private var drive = 1f
    private var ceiling = 1f

    // Audio delay line (delay frames).
    private val line = FloatArray(max(1, delay) * channels)
    private var linePos = 0

    // Sliding minimum of the required gain (monotonic queue over the last `window` frames).
    private val minValue = FloatArray(window + 1)
    private val minIndex = LongArray(window + 1)
    private var minHead = 0
    private var minSize = 0
    private var frameIndex = 0L

    private var released = 1f

    // Moving average of the released gain.
    private val avg = FloatArray(window) { 1f }
    private var avgPos = 0
    private var avgSum = window.toDouble()

    /** Gain reduction of the last frame in dB (≤ 0), for display. */
    var reductionDb = 0f
        private set

    fun setParams(thresholdDb: Float, ceilingDb: Float) {
        drive = 10f.pow(-thresholdDb.coerceIn(-30f, 0f) / 20f)
        ceiling = 10f.pow(ceilingDb.coerceIn(-12f, 0f) / 20f)
    }

    fun reset() {
        line.fill(0f)
        linePos = 0
        minHead = 0
        minSize = 0
        frameIndex = 0
        released = 1f
        avg.fill(1f)
        avgPos = 0
        avgSum = window.toDouble()
        reductionDb = 0f
    }

    /** Processes one frame (one sample per channel) in place. */
    fun process(frame: FloatArray) {
        var peak = 0f
        for (ch in 0 until channels) {
            frame[ch] *= drive
            peak = max(peak, abs(frame[ch]))
        }
        val required = if (peak > ceiling) ceiling / peak else 1f

        // Sliding minimum.
        while (minSize > 0 && minValue[(minHead + minSize - 1) % minValue.size] >= required) minSize--
        val slot = (minHead + minSize) % minValue.size
        minValue[slot] = required
        minIndex[slot] = frameIndex
        minSize++
        while (minIndex[minHead] <= frameIndex - window) {
            minHead = (minHead + 1) % minValue.size
            minSize--
        }
        frameIndex++
        val held = minValue[minHead]

        // Instant attack, smooth release: never above the held minimum.
        released = if (held < released) held else held - (held - released) * releaseCoef

        avgSum += released - avg[avgPos]
        avg[avgPos] = released
        avgPos = (avgPos + 1) % window
        val gain = (avgSum / window).toFloat().coerceAtMost(1f)
        reductionDb = if (gain < 1f) 20f * kotlin.math.log10(gain) else 0f

        for (ch in 0 until channels) {
            val delayed: Float
            if (delay == 0) {
                delayed = frame[ch]
            } else {
                val i = linePos * channels + ch
                delayed = line[i]
                line[i] = frame[ch]
            }
            // The guarantee holds up to rounding; the clamp catches the last bit.
            frame[ch] = (delayed * gain).coerceIn(-ceiling, ceiling)
        }
        if (delay > 0) linePos = (linePos + 1) % delay
    }
}
