package de.shakie.playernext.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class LoudnessMaximizerTest {
    private val rate = 44100

    private fun run(m: LoudnessMaximizer, input: (Int) -> Float, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val frame = FloatArray(2)
        for (n in 0 until frames) {
            val x = input(n)
            frame[0] = x
            frame[1] = -x * 0.7f
            m.process(frame)
            out[2 * n] = frame[0]
            out[2 * n + 1] = frame[1]
        }
        return out
    }

    @Test
    fun neverAboveTheCeiling() {
        val m = LoudnessMaximizer(2, rate).apply { setParams(thresholdDb = -12f, ceilingDb = -1f) }
        val random = Random(1)
        // Loud music-like signal with sudden transients up to +6 dBFS before the drive.
        val out = run(m, { n ->
            val base = 0.5f * sin(2 * PI * 220 * n / rate).toFloat() + 0.3f * sin(2 * PI * 3100 * n / rate).toFloat()
            if (random.nextInt(2000) == 0) 2f else base
        }, rate * 5)
        val ceiling = 10f.pow(-1f / 20f)
        assertTrue(out.all { abs(it) <= ceiling + 1e-6f })
        assertTrue(out.none { it.isNaN() })
    }

    @Test
    fun quietSignalOnlyGetsLouderAndDelayed() {
        val m = LoudnessMaximizer(2, rate).apply { setParams(thresholdDb = -6f, ceilingDb = 0f) }
        val signal = { n: Int -> 0.1f * sin(2 * PI * 440 * n / rate).toFloat() }
        val out = run(m, signal, 4000)
        val delay = (0.0015 * rate).toInt() // 66 frames at 44.1 kHz, minus one
        val drive = 10f.pow(6f / 20f)
        for (n in 1000 until 4000) assertEquals(signal(n - (delay - 1)) * drive, out[2 * n], 1e-4f)
    }

    @Test
    fun limitingStartsAtTheThreshold() {
        // Threshold −6 dB, output −1 dB: below −6 dB everything is 5 dB louder, above it is limited.
        val below = LoudnessMaximizer(2, rate).apply { setParams(thresholdDb = -6f, ceilingDb = -1f) }
        val quiet = 10f.pow(-9f / 20f)
        val outQuiet = run(below, { n -> quiet * sin(2 * PI * 440 * n / rate).toFloat() }, rate / 2)
        val tailQuiet = outQuiet.copyOfRange(rate / 4 * 2, rate).filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(10f.pow(-4f / 20f), tailQuiet.maxOf { abs(it) }, 0.005f)
        assertEquals(1f, below.gain, 1e-4f)

        val above = LoudnessMaximizer(2, rate).apply { setParams(thresholdDb = -6f, ceilingDb = -1f) }
        val loud = 10f.pow(-3f / 20f)
        run(above, { n -> loud * sin(2 * PI * 440 * n / rate).toFloat() }, rate / 2)
        // A peak 3 dB over the threshold needs about 3 dB of reduction.
        assertEquals(-3f, above.reductionDb, 0.3f)
    }

    @Test
    fun recoversAfterAPeak() {
        val m = LoudnessMaximizer(2, rate).apply { setParams(thresholdDb = 0f, ceilingDb = 0f) }
        // One loud click, then a quiet tone: within half a second the tone is at full level again.
        val out = run(m, { n -> if (n == 100) 4f else 0.2f * sin(2 * PI * 440 * n / rate).toFloat() }, rate)
        val tail = out.copyOfRange(2 * (rate - 2000), 2 * rate).filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(0.2f, tail.maxOf { abs(it) }, 0.005f)
        assertTrue(m.reductionDb > -0.1f)
    }
}
