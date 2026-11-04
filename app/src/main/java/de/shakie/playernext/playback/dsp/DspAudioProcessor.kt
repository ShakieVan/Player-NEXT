package de.shakie.playernext.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Equalizer, preamp, bass shelf, stereo widening, mono, balance, ReplayGain and a peak
 * limiter (or the loudness maximizer instead) in one pass. With neutral settings it copies
 * the buffer unchanged.
 */
class DspAudioProcessor : BaseAudioProcessor() {
    private var channels = 2
    private var sampleRate = 44100
    private var isFloat = false
    private var appliedVersion = -1
    private var eqFilters: Array<Biquad> = emptyArray()
    private var bassFilter = Biquad(2)
    private var activeEq = BooleanArray(AudioEffects.BANDS)
    private var gain = 1f
    private var targetGain = 1f
    private var width = 0f
    private var mono = false
    private var balL = 1f
    private var balR = 1f
    private var limiter = true
    private var loudness = false
    private var maximizer = LoudnessMaximizer(2, 44100)
    private var neutral = true
    private var limiterGain = 1f
    private var releaseCoef = 0.9995f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        isFloat = enc == C.ENCODING_PCM_FLOAT
        eqFilters = Array(AudioEffects.BANDS) { Biquad(channels) }
        bassFilter = Biquad(channels)
        maximizer = LoudnessMaximizer(channels, sampleRate)
        releaseCoef = exp(-1.0 / (0.15 * sampleRate)).toFloat()
        appliedVersion = -1
        return inputAudioFormat
    }

    private fun refreshParams() {
        val v = AudioEffects.version
        if (v == appliedVersion) return
        appliedVersion = v
        val eqOn = AudioEffects.eqEnabled
        for (i in 0 until AudioEffects.BANDS) {
            val db = if (eqOn) AudioEffects.band(i) else 0f
            eqFilters[i].peaking(sampleRate, AudioEffects.FREQUENCIES[i], db, 1.41f)
            activeEq[i] = !eqFilters[i].bypass
        }
        bassFilter.lowShelf(sampleRate, 90f, AudioEffects.bassStrength / 1000f * 12f)
        width = if (channels == 2) AudioEffects.virtualizerStrength / 1000f * 1.5f else 0f
        mono = AudioEffects.mono && channels == 2
        val b = AudioEffects.balance
        balL = if (b > 0) 1f - b else 1f
        balR = if (b < 0) 1f + b else 1f
        limiter = AudioEffects.limiter
        if (AudioEffects.loudness && !loudness) maximizer.reset()
        loudness = AudioEffects.loudness
        maximizer.setParams(AudioEffects.loudnessThresholdDb, AudioEffects.loudnessOutputDb)
        var db = (if (eqOn) AudioEffects.preampDb else 0f) + AudioEffects.replayGainDb
        if (AudioEffects.replayGainDb != 0f && de.shakie.playernext.Prefs.replayGainClipping) {
            // Never amplify the track peak above full scale.
            val maxDb = (20 * kotlin.math.log10(1f / AudioEffects.replayGainPeak.coerceAtLeast(0.01f)))
            if (AudioEffects.replayGainDb > maxDb) db -= AudioEffects.replayGainDb - maxDb
        }
        targetGain = 10f.pow(db / 20f)
        neutral = activeEq.none { it } && bassFilter.bypass && width == 0f && !mono && balL == 1f && balR == 1f &&
            targetGain == 1f && gain == 1f && !loudness
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        refreshParams()
        val out = replaceOutputBuffer(remaining)
        if (neutral) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = remaining / (bytesPerSample * channels)
        val gainStep = (targetGain - gain) / frames.coerceAtLeast(1)
        val inp = inputBuffer.order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                frame[ch] = if (isFloat) inp.float else inp.short / 32768f
            }
            gain += gainStep
            for (ch in 0 until channels) {
                var x = frame[ch] * gain
                for (i in 0 until AudioEffects.BANDS) if (activeEq[i]) x = eqFilters[i].process(x, ch)
                if (!bassFilter.bypass) x = bassFilter.process(x, ch)
                frame[ch] = x
            }
            if (channels == 2) {
                var l = frame[0]
                var r = frame[1]
                if (width != 0f) {
                    val mid = (l + r) * 0.5f
                    val side = (l - r) * 0.5f * (1f + width)
                    l = mid + side
                    r = mid - side
                }
                if (mono) {
                    val m = (l + r) * 0.5f
                    l = m; r = m
                }
                frame[0] = l * balL
                frame[1] = r * balR
            }
            if (loudness) {
                maximizer.process(frame)
            } else if (limiter) {
                var peak = 0f
                for (ch in 0 until channels) peak = maxOf(peak, abs(frame[ch]))
                val needed = if (peak * limiterGain > 0.98f) 0.98f / peak else 1f
                limiterGain = if (needed < limiterGain) needed else 1f - (1f - limiterGain) * releaseCoef
                if (limiterGain < 1f) for (ch in 0 until channels) frame[ch] *= limiterGain
            }
            for (ch in 0 until channels) {
                val y = frame[ch]
                if (isFloat) out.putFloat(y) else out.putShort((y.coerceIn(-1f, 0.99997f) * 32768f).toInt().toShort())
            }
        }
        gain = targetGain
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        eqFilters.forEach { it.reset() }
        bassFilter.reset()
        limiterGain = 1f
        maximizer.reset()
        gain = targetGain
    }

    override fun onReset() {
        appliedVersion = -1
        gain = 1f
        targetGain = 1f
    }
}
