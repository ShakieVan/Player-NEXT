package de.shakie.playernext.playback.dsp

import kotlin.math.abs
import kotlin.math.max

/**
 * Peak meter for the equalizer screen. While [enabled] is set, the DSP of the main player
 * measures 10 ms blocks — input before the maximizer, output after it and the gain it applied —
 * and stores them with their media time. The audio track buffer holds up to a second, so the
 * screen does not show the newest block but the ones audible now, taken from the sink's
 * playback position ([MeteringAudioSink][de.shakie.playernext.playback.MeteringAudioSink]).
 */
object LevelMeter {
    const val NONE = Long.MIN_VALUE

    /** Set by the equalizer while its meters are on screen; nothing is measured otherwise. */
    @Volatile var enabled = false

    /** Media time (sink time base) of the buffer the sink is handling right now. */
    @Volatile var bufferTimeUs = NONE

    @Volatile private var positionUs = NONE
    @Volatile private var positionAtNs = 0L
    @Volatile var isPlaying = false
        private set
    @Volatile var speed = 1f

    private const val SIZE = 512 // 5 s of 10 ms blocks, well beyond the 1 s track buffer
    const val VALUES = 5 // in L, in R, out L, out R (peaks, linear), gain (linear, ≤ 1)

    private val lock = Any()
    private val starts = LongArray(SIZE) { NONE }
    private val ends = LongArray(SIZE)
    private val values = FloatArray(SIZE * VALUES)
    private var next = 0

    /** The sink's position: media time of what leaves the speaker now. */
    fun onPosition(us: Long, nowNs: Long = System.nanoTime()) {
        if (us == NONE) return
        positionUs = us
        positionAtNs = nowNs
    }

    fun onPlaying(playing: Boolean, nowNs: Long = System.nanoTime()) {
        if (playing == isPlaying) return
        // Freeze (or restart) the extrapolation at the moment of the change.
        val p = audibleUs(nowNs)
        if (p != NONE) positionUs = p
        positionAtNs = nowNs
        isPlaying = playing
    }

    /** Estimated media time that is audible at [nowNs], or [NONE]. */
    fun audibleUs(nowNs: Long = System.nanoTime()): Long {
        val p = positionUs
        if (p == NONE) return NONE
        return if (isPlaying) p + ((nowNs - positionAtNs) / 1000 * speed).toLong() else p
    }

    /** Drops all blocks (seek, new stream). */
    fun clear() = synchronized(lock) {
        starts.fill(NONE)
        next = 0
    }

    fun publish(startUs: Long, endUs: Long, v: FloatArray) = synchronized(lock) {
        starts[next] = startUs
        ends[next] = endUs
        System.arraycopy(v, 0, values, next * VALUES, VALUES)
        next = (next + 1) % SIZE
    }

    /**
     * Folds every block that overlaps (fromUs, toUs] into [out]: peaks as maximum, gain as
     * minimum. Returns the number of blocks found.
     */
    fun collect(fromUs: Long, toUs: Long, out: FloatArray): Int = synchronized(lock) {
        for (i in 0 until 4) out[i] = 0f
        out[4] = 1f
        var found = 0
        for (i in 0 until SIZE) {
            val start = starts[i]
            if (start == NONE || start > toUs || ends[i] <= fromUs) continue
            found++
            val o = i * VALUES
            for (k in 0 until 4) if (values[o + k] > out[k]) out[k] = values[o + k]
            if (values[o + 4] < out[4]) out[4] = values[o + 4]
        }
        found
    }
}

/**
 * Collects the peaks of 10 ms blocks on the audio thread and hands them on. The block clock
 * counts frames from the time of the buffer the sink is handling; it only resyncs on jumps,
 * so the small lag of upstream processors (gapless trimming) does not make it jitter.
 */
internal class MeterBlock(
    private val sampleRate: Int,
    private val publish: (Long, Long, FloatArray) -> Unit = LevelMeter::publish,
) {
    private val size = max(1, sampleRate / 100)
    private var baseUs = LevelMeter.NONE
    private var frames = 0L
    private var count = 0
    private var startUs = 0L
    private val v = FloatArray(LevelMeter.VALUES)

    val ready get() = baseUs != LevelMeter.NONE

    private fun timeUs() = baseUs + frames * 1_000_000L / sampleRate

    fun sync(bufferUs: Long) {
        if (bufferUs == LevelMeter.NONE) return
        if (ready && abs(bufferUs - timeUs()) <= RESYNC_US) return
        baseUs = bufferUs
        frames = 0
        count = 0
    }

    fun reset() {
        baseUs = LevelMeter.NONE
        frames = 0
        count = 0
    }

    /** One frame: signal before and after the limiting stage and the gain it applied. */
    fun add(inL: Float, inR: Float, outL: Float, outR: Float, gain: Float) {
        if (count == 0) {
            startUs = timeUs()
            for (i in 0 until 4) v[i] = 0f
            v[4] = 1f
        }
        val a = abs(inL); if (a > v[0]) v[0] = a
        val b = abs(inR); if (b > v[1]) v[1] = b
        val c = abs(outL); if (c > v[2]) v[2] = c
        val d = abs(outR); if (d > v[3]) v[3] = d
        if (gain < v[4]) v[4] = gain
        frames++
        if (++count == size) {
            publish(startUs, timeUs(), v)
            count = 0
        }
    }

    companion object {
        const val RESYNC_US = 200_000L
    }
}
