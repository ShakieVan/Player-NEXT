package de.shakie.playernext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BagOrderTest {
    private val library = (1L..30_000L).toList()
    private val all: (Long) -> Boolean = { true }

    private fun drawOneByOne(bag: BagOrder, ids: List<Long>, n: Int, exists: (Long) -> Boolean = all): List<Long> =
        (0 until n).flatMap { bag.draw(1, ids, exists, emptySet()) }

    @Test
    fun everySongOnceBeforeAnyRepeatsInALargeLibrary() {
        val bag = BagOrder(Random(1))
        bag.addMissing(library)
        val round = drawOneByOne(bag, library, library.size)
        assertEquals(library.size, round.toSet().size)
        assertEquals(library.toSet(), round.toSet())
    }

    @Test
    fun nextRoundAgainHasEverySongAndDoesNotStartWithTheLastOnes() {
        val bag = BagOrder(Random(2))
        bag.addMissing(library)
        val first = drawOneByOne(bag, library, library.size)
        val second = drawOneByOne(bag, library, library.size)
        assertEquals(library.toSet(), second.toSet())
        val lastPlayed = first.takeLast(20).toSet()
        assertTrue(second.take(20).none { it in lastPlayed })
    }

    @Test
    fun drawsInBatchesCoverTheLibraryWithoutRepeats() {
        val bag = BagOrder(Random(3))
        bag.addMissing(library)
        val drawn = ArrayList<Long>()
        while (drawn.size < library.size) drawn += bag.draw(10, library, all, emptySet())
        assertEquals(library.size, drawn.size)
        assertEquals(library.size, drawn.toSet().size)
    }

    @Test
    fun songsAddedMidRoundComeBeforeTheRoundEnds() {
        val small = (1L..1000L).toList()
        val bag = BagOrder(Random(4))
        bag.addMissing(small)
        val firstHalf = drawOneByOne(bag, small, 500)
        val grown = small + (1001L..1100L)
        assertTrue(bag.addMissing(grown))
        val rest = drawOneByOne(bag, grown, 600)
        assertEquals(grown.toSet(), (firstHalf + rest).toSet())
        assertEquals(1100, (firstHalf + rest).toSet().size)
    }

    @Test
    fun deletedAndQueuedSongsAreSkipped() {
        val small = (1L..100L).toList()
        val bag = BagOrder(Random(5))
        bag.addMissing(small)
        val deleted = (1L..10L).toSet()
        val queued = setOf(50L, 51L)
        val drawn = ArrayList<Long>()
        // 100 songs minus 10 deleted and 2 queued: one round has 88.
        while (drawn.size < 88) drawn += bag.draw(1, small, { it !in deleted }, queued)
        assertTrue(drawn.none { it in deleted || it in queued })
        assertEquals(drawn.size, drawn.toSet().size)
    }

    @Test
    fun restoredCursorContinuesWhereItStopped() {
        val bag = BagOrder(Random(6))
        bag.addMissing(library)
        val before = drawOneByOne(bag, library, 12_345)
        val restored = BagOrder(Random(7)).apply { restore(bag.order, bag.cursor) }
        val after = drawOneByOne(restored, library, library.size - before.size)
        assertEquals(library.toSet(), (before + after).toSet())
    }
}
