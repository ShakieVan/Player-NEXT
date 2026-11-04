package de.shakie.playernext.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LevelMeterTest {
    private val rate = 44100
    private val blocks = ArrayList<Triple<Long, Long, FloatArray>>()
    private val block = MeterBlock(rate) { start, end, v -> blocks += Triple(start, end, v.copyOf()) }

    @Before
    fun clear() {
        LevelMeter.clear()
        LevelMeter.onPlaying(false, 0)
    }

    private fun feed(frames: Int, level: Float = 0.5f, gain: Float = 1f) {
        repeat(frames) { block.add(level, -level / 2, level * gain, -level * gain / 2, gain) }
    }

    @Test
    fun blocksCarryTheirMediaTime() {
        block.sync(1_000_000_000_000L)
        feed(rate) // one second
        assertEquals(100, blocks.size)
        assertEquals(1_000_000_000_000L, blocks[0].first)
        assertEquals(1_000_000_000_000L + 10_000, blocks[1].first)
        assertEquals(1_000_000_000_000L + 990_000, blocks[99].first)
        val v = blocks[0].third
        assertEquals(0.5f, v[0], 0f)
        assertEquals(0.25f, v[1], 0f) // absolute value of the right channel
        assertEquals(1f, v[4], 0f)
    }

    @Test
    fun smallLagDoesNotResyncButAJumpDoes() {
        block.sync(0)
        feed(rate / 2)
        // Next buffer claims 30 ms more than counted (trimming lag): keep counting.
        block.sync(500_000 + 30_000)
        feed(rate / 100)
        assertEquals(500_000L, blocks.last().first)
        // A seek far away: start over at the new time.
        block.sync(60_000_000)
        feed(rate / 100)
        assertEquals(60_000_000L, blocks.last().first)
    }

    @Test
    fun collectFoldsTheAudibleWindow() {
        val quiet = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 1f)
        val loud = floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f)
        LevelMeter.publish(0, 10_000, quiet)
        LevelMeter.publish(10_000, 20_000, loud)
        LevelMeter.publish(20_000, 30_000, quiet)
        // Processed a second ahead: blocks long after the window must not show.
        LevelMeter.publish(1_000_000, 1_010_000, floatArrayOf(1f, 1f, 1f, 1f, 0.1f))
        val out = FloatArray(LevelMeter.VALUES)
        assertEquals(2, LevelMeter.collect(0, 15_000, out))
        assertEquals(0.9f, out[0], 0f)
        assertEquals(0.6f, out[3], 0f)
        assertEquals(0.5f, out[4], 0f)
        assertEquals(1, LevelMeter.collect(20_000, 25_000, out))
        assertEquals(0.1f, out[0], 0f)
        assertEquals(1f, out[4], 0f)
        LevelMeter.clear()
        assertEquals(0, LevelMeter.collect(0, 2_000_000, out))
    }

    @Test
    fun audibleTimeFollowsTheSinkPosition() {
        LevelMeter.speed = 1f
        LevelMeter.onPosition(5_000_000, nowNs = 1_000_000_000)
        // Paused: the position stands still.
        assertEquals(5_000_000L, LevelMeter.audibleUs(nowNs = 3_000_000_000))
        LevelMeter.onPlaying(true, nowNs = 3_000_000_000)
        assertEquals(5_250_000L, LevelMeter.audibleUs(nowNs = 3_250_000_000))
        LevelMeter.speed = 2f
        assertEquals(5_500_000L, LevelMeter.audibleUs(nowNs = 3_250_000_000))
        LevelMeter.speed = 1f
        LevelMeter.onPlaying(false, nowNs = 3_500_000_000)
        assertEquals(5_500_000L, LevelMeter.audibleUs(nowNs = 9_000_000_000))
        assertTrue(!LevelMeter.isPlaying)
    }
}
