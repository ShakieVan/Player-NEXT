package de.shakie.playernext.playback

import de.shakie.playernext.Prefs
import de.shakie.playernext.data.StatsStore
import kotlin.random.Random

/**
 * Random order for shuffling a queue, "shuffle all", the next round with "repeat all" and the
 * party mix. Two modes (Settings → Audio → Zufallswiedergabe):
 *
 * - even: every order equally likely.
 * - long unheard first: each next song is drawn at random from the songs left over that were
 *   heard longest ago (played or skipped; never heard counts as oldest). The pool is a share of
 *   the list (half, quarter or eighth), so the order stays random and a round never just
 *   replays the previous one. Every song still comes exactly once.
 */
object Shuffler {
    /** 0 = even, otherwise the pool is 1/divisor of the list. */
    val divisor: Int get() = if (Prefs.shuffleOldestFirst) Prefs.shuffleWindow else 0

    /** Stored with a saved order (party mix), so a changed setting reorders what is left of it. */
    val modeKey: String get() = divisor.let { if (it == 0) "even" else "oldest$it" }

    fun <T> shuffle(items: List<T>, pathOf: (T) -> String?, random: Random = Random.Default): List<T> {
        val d = divisor
        if (d == 0 || items.size < 2) return items.shuffled(random)
        val ages = LongArray(items.size) { i -> pathOf(items[i])?.let(::lastHeard) ?: 0L }
        return AgeOrder.order(ages, d, random).map { items[it] }
    }

    /** Index of the first song for "shuffle all". */
    fun <T> pickStart(items: List<T>, pathOf: (T) -> String?): Int {
        val d = divisor
        if (d == 0 || items.size < 2) return Random.nextInt(items.size)
        val ages = LongArray(items.size) { i -> pathOf(items[i])?.let(::lastHeard) ?: 0L }
        return AgeOrder.order(ages, d, Random.Default)[0]
    }

    private fun lastHeard(path: String): Long = StatsStore[path].let { maxOf(it.lastPlayed, it.lastSkipped) }
}

/** The pure part of [Shuffler]: a sliding random pick over the list sorted by age. */
internal object AgeOrder {
    /** Small lists are shuffled across at least this many songs, so they stay random. */
    const val MIN_WINDOW = 5

    fun window(n: Int, divisor: Int) = maxOf(minOf(n, MIN_WINDOW), n / divisor)

    /**
     * Permutation of the indices of [ages] (last heard, ms; 0 = never). Equal ages are in random
     * order. Each step takes a random one of the [window] oldest not yet taken, so a song with age
     * rank r never comes before position r - window + 1.
     */
    fun order(ages: LongArray, divisor: Int, random: Random): IntArray {
        val n = ages.size
        val sorted = (0 until n).shuffled(random).sortedBy { ages[it] }
        val w = window(n, divisor)
        val pool = IntArray(w) { sorted[it] }
        var poolSize = w
        var next = w
        return IntArray(n) {
            val pick = random.nextInt(poolSize)
            val taken = pool[pick]
            if (next < n) pool[pick] = sorted[next++] else pool[pick] = pool[--poolSize]
            taken
        }
    }
}
