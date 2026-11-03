package de.shakie.playernext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AgeOrderTest {
    private val n = 30_000

    /** Age rank of every index: 0 = heard longest ago. */
    private fun ranks(ages: LongArray): IntArray {
        val r = IntArray(ages.size)
        ages.indices.sortedBy { ages[it] }.forEachIndexed { rank, i -> r[i] = rank }
        return r
    }

    @Test
    fun everySongExactlyOnce() {
        val random = Random(1)
        val ages = LongArray(n) { random.nextLong(0, 1_000_000) }
        val order = AgeOrder.order(ages, 4, Random(2))
        assertEquals(n, order.size)
        assertEquals((0 until n).toSet(), order.toSet())
    }

    @Test
    fun aSongNeverComesBeforeItsWindow() {
        // Distinct ages: index i was heard at time i, so index 0 is the oldest.
        val ages = LongArray(n) { it.toLong() + 1 }
        for (divisor in listOf(2, 4, 8)) {
            val w = AgeOrder.window(n, divisor)
            val order = AgeOrder.order(ages, divisor, Random(divisor))
            val r = ranks(ages)
            order.forEachIndexed { pos, i -> assertTrue("rank ${r[i]} at $pos", r[i] <= pos + w - 1) }
        }
    }

    @Test
    fun neverHeardSongsComeFirst() {
        // 10 000 heard (ages 1..), 20 000 never heard (0).
        val ages = LongArray(n) { if (it < 10_000) it.toLong() + 1 else 0L }
        val order = AgeOrder.order(ages, 4, Random(3))
        val w = AgeOrder.window(n, 4)
        // The first 20 000 - w + 1 picks can only be never-heard songs.
        assertTrue(order.take(20_000 - w + 1).all { it >= 10_000 })
    }

    @Test
    fun theNextRoundIsNotTheSameAsTheLast() {
        // Round 2 is made from the times of round 1; it must not just replay it.
        val first = AgeOrder.order(LongArray(n), 4, Random(4))
        val ages = LongArray(n)
        first.forEachIndexed { pos, i -> ages[i] = pos.toLong() + 1 }
        val second = AgeOrder.order(ages, 4, Random(5))
        assertNotEquals(first.toList(), second.toList())
        val same = first.indices.count { first[it] == second[it] }
        assertTrue("same position: $same", same < n / 100)
        // Still: what came first last time tends to come first again, the last ones come late.
        val lastOnes = first.takeLast(1000).toSet()
        assertTrue(second.take(n / 2).none { it in lastOnes })
    }

    @Test
    fun smallListsStayRandom() {
        assertEquals(4, AgeOrder.window(4, 8))
        assertEquals(5, AgeOrder.window(20, 8))
        assertEquals(250, AgeOrder.window(1000, 4))
        val ages = longArrayOf(1, 2, 3, 4)
        val firsts = (0 until 200).map { AgeOrder.order(ages, 8, Random(it))[0] }.toSet()
        assertEquals(setOf(0, 1, 2, 3), firsts)
    }

    @Test
    fun bagFollowsTheArrangementAndKeepsEverySongOnce() {
        val ids = (1L..1000L).toList()
        val heard = HashMap<Long, Long>()
        var clock = 0L
        val bag = BagOrder(Random(6))
        val random = Random(7)
        bag.arrange = { list ->
            val ages = LongArray(list.size) { heard[list[it]] ?: 0L }
            AgeOrder.order(ages, 4, random).map { list[it] }
        }
        bag.addMissing(ids)
        val first = (0 until 1000).flatMap { bag.draw(1, ids, { true }, emptySet()) }
        first.forEach { heard[it] = ++clock }
        val second = (0 until 1000).flatMap { bag.draw(1, ids, { true }, emptySet()) }
        assertEquals(ids.toSet(), first.toSet())
        assertEquals(ids.toSet(), second.toSet())
        val lastOnes = first.takeLast(100).toSet()
        assertTrue(second.take(500).none { it in lastOnes })
    }

    @Test
    fun changingTheModeReordersOnlyTheRest() {
        val ids = (1L..1000L).toList()
        val bag = BagOrder(Random(8))
        bag.addMissing(ids)
        val played = (0 until 300).flatMap { bag.draw(1, ids, { true }, emptySet()) }
        // Switch to "long unheard first": here ids heard in reverse order, so small ids are newest.
        bag.arrange = { list -> AgeOrder.order(LongArray(list.size) { 2000 - list[it] }, 4, Random(9)).map { list[it] } }
        bag.rearrangeRest()
        val rest = (0 until 700).flatMap { bag.draw(1, ids, { true }, emptySet()) }
        assertEquals(ids.toSet(), (played + rest).toSet())
        assertEquals(1000, (played + rest).size)
        // The oldest (largest ids) of the rest come first.
        val restSorted = rest.sortedDescending()
        assertTrue(rest.take(10).all { it in restSorted.take(AgeOrder.window(700, 4) + 10) })
    }
}
