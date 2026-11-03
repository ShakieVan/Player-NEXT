package de.shakie.playernext

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import de.shakie.playernext.playback.dsp.AudioEffects
import de.shakie.playernext.playback.dsp.DspAudioProcessor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DspAudioProcessorTest {
    private val rate = 44100

    @After
    fun reset() {
        AudioEffects.applyPreset("Normal", FloatArray(AudioEffects.BANDS), 0f)
        AudioEffects.setEnabled(false)
        AudioEffects.setBass(0)
        AudioEffects.setVirtualizer(0)
    }

    private fun sine(freq: Double, amp: Double, frames: Int): ShortArray =
        ShortArray(frames * 2) { i -> (sin(2 * PI * freq * (i / 2) / rate) * amp * 32767).toInt().toShort() }

    private fun process(input: ShortArray): ShortArray {
        val p = DspAudioProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val buf = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { buf.putShort(it) }
        buf.flip()
        val out = ArrayList<Short>()
        while (buf.hasRemaining()) {
            val chunk = buf.slice().order(ByteOrder.nativeOrder())
            chunk.limit(minOf(4096, buf.remaining()))
            p.queueInput(chunk)
            buf.position(buf.position() + chunk.position())
            val o = p.output
            while (o.hasRemaining()) out += o.short
        }
        return out.toShortArray()
    }

    /** RMS of the second half, after filters have settled. */
    private fun rms(s: ShortArray): Double {
        val tail = s.copyOfRange(s.size / 2, s.size)
        return sqrt(tail.sumOf { (it / 32768.0) * (it / 32768.0) } / tail.size)
    }

    @Test
    fun neutralSettingsPassAudioThroughUnchanged() {
        val input = sine(440.0, 0.5, 8192)
        assertArrayEquals(input, process(input))
    }

    @Test
    fun boostedBandRaisesOnlyItsFrequency() {
        val bands = FloatArray(AudioEffects.BANDS)
        bands[5] = 12f // 1 kHz
        AudioEffects.applyPreset("Test", bands, 0f)
        AudioEffects.setEnabled(true)

        val at1k = sine(1000.0, 0.1, 22050)
        val gain1k = rms(process(at1k)) / rms(at1k)
        assertEquals("1 kHz should rise by about 12 dB", 3.98, gain1k, 0.4)

        val at60 = sine(60.0, 0.1, 22050)
        val gain60 = rms(process(at60)) / rms(at60)
        assertEquals("60 Hz should stay unchanged", 1.0, gain60, 0.05)
    }

    @Test
    fun limiterKeepsBoostedSignalBelowFullScale() {
        val bands = FloatArray(AudioEffects.BANDS) { 12f }
        AudioEffects.applyPreset("Loud", bands, 12f)
        AudioEffects.setEnabled(true)
        val out = process(sine(1000.0, 0.9, 22050))
        val peak = out.copyOfRange(out.size / 2, out.size).maxOf { kotlin.math.abs(it.toInt()) } / 32768.0
        assertTrue("peak $peak must stay below full scale", peak <= 0.99)
    }

    @Test
    fun bassKnobLiftsLowFrequencies() {
        AudioEffects.setBass(1000)
        val at60 = sine(60.0, 0.05, 22050)
        val gain = rms(process(at60)) / rms(at60)
        assertTrue("bass boost gain $gain", gain > 2.5)
    }
}
