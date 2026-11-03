package de.shakie.playernext.playback.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ cookbook biquad in transposed direct form II, one state pair per channel. */
class Biquad(channels: Int) {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private val z1 = FloatArray(channels)
    private val z2 = FloatArray(channels)
    var bypass = true
        private set

    fun peaking(sampleRate: Int, freq: Float, gainDb: Float, q: Float) {
        if (gainDb == 0f || freq >= sampleRate * 0.45f) {
            bypass = true
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosW = cos(w0)
        val a0 = 1 + alpha / a
        set(
            (1 + alpha * a) / a0, -2 * cosW / a0, (1 - alpha * a) / a0,
            -2 * cosW / a0, (1 - alpha / a) / a0
        )
    }

    fun lowShelf(sampleRate: Int, freq: Float, gainDb: Float) {
        if (gainDb == 0f) {
            bypass = true
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sq = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosW + sq
        set(
            a * ((a + 1) - (a - 1) * cosW + sq) / a0,
            2 * a * ((a - 1) - (a + 1) * cosW) / a0,
            a * ((a + 1) - (a - 1) * cosW - sq) / a0,
            -2 * ((a - 1) + (a + 1) * cosW) / a0,
            ((a + 1) + (a - 1) * cosW - sq) / a0
        )
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
        b0 = nb0.toFloat(); b1 = nb1.toFloat(); b2 = nb2.toFloat(); a1 = na1.toFloat(); a2 = na2.toFloat()
        bypass = false
    }

    fun reset() {
        z1.fill(0f)
        z2.fill(0f)
    }

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }
}
