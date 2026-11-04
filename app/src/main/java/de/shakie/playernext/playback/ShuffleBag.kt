package de.shakie.playernext.playback

import android.content.Context
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.LibrarySnapshot
import de.shakie.playernext.data.Song
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Endless random order over the whole library for the party shuffle: every song comes exactly
 * once before any song repeats, no matter how large the library is. The order is a stored
 * permutation of song ids plus a cursor, so it survives restarts. New songs are mixed into the
 * part not yet played; deleted songs are skipped. The order follows the shuffle mode
 * ([Shuffler]); when the mode changes, the part not yet played is reordered.
 */
object ShuffleBag {
    private const val FILE = "shuffle_bag.bin"
    private const val PREFS = "shuffle_bag"

    private val bag = BagOrder()
    private var loaded = false
    private var syncedWith: LibrarySnapshot? = null
    /** Shuffle mode the stored order was made with. */
    private var orderMode = ""
    private lateinit var appContext: Context

    /** Takes the next [count] songs; ids in [skip] (already in the queue) are passed over. */
    @Synchronized
    fun next(context: Context, count: Int, skip: Set<Long> = emptySet()): List<Song> {
        appContext = context.applicationContext
        val lib = Library.snapshot
        if (lib.songs.isEmpty()) return emptyList()
        load()
        // A ten-hour audiobook is no party song.
        val ids = lib.songs.filter { !it.isAudiobook }.map { it.id }
        var orderChanged = false
        val mode = Shuffler.modeKey
        bag.arrange = if (Shuffler.divisor == 0) null else { list -> Shuffler.shuffle(list, { lib.songsById[it]?.path }) }
        if (syncedWith !== lib) {
            syncedWith = lib
            orderChanged = bag.addMissing(ids)
        }
        if (mode != orderMode) {
            orderMode = mode
            bag.rearrangeRest()
            orderChanged = true
        }
        val drawn = bag.draw(count, ids, { id -> lib.songsById[id]?.isAudiobook == false }, skip)
        if (orderChanged || bag.takeNewRound()) saveOrder() else saveCursor()
        return drawn.mapNotNull { lib.songsById[it] }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val order = try {
            DataInputStream(File(appContext.filesDir, FILE).inputStream().buffered()).use { input ->
                LongArray(input.readInt()) { input.readLong() }
            }
        } catch (_: Exception) {
            LongArray(0)
        }
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A cursor that doesn't belong to the stored order starts a new round.
        val cursor = if (prefs.getInt("size", -1) == order.size) prefs.getInt("cursor", 0) else order.size
        // Orders from before 1.1.1 were evenly random.
        orderMode = prefs.getString("mode", "even") ?: "even"
        bag.restore(order, cursor)
    }

    private fun saveOrder() {
        val file = File(appContext.filesDir, FILE)
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(bag.order.size)
            bag.order.forEach { out.writeLong(it) }
        }
        tmp.renameTo(file)
        saveCursor()
    }

    private fun saveCursor() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("size", bag.order.size).putInt("cursor", bag.cursor).putString("mode", orderMode).apply()
    }
}

/** The pure part of [ShuffleBag]: a permutation of song ids and how far it has been played. */
internal class BagOrder(private val random: Random = Random.Default) {
    var order = LongArray(0)
        private set
    var cursor = 0
        private set
    private var newRound = false
    /** Orders a round or the rest of one ("long unheard first"); null = evenly at random. */
    var arrange: ((List<Long>) -> List<Long>)? = null

    fun restore(order: LongArray, cursor: Int) {
        this.order = order
        this.cursor = cursor.coerceIn(0, order.size)
    }

    /** Songs not in the order yet join the not-yet-played rest. Returns true if the order changed. */
    fun addMissing(ids: List<Long>): Boolean {
        val known = HashSet<Long>(order.size * 2)
        order.forEach { known += it }
        val added = ids.filter { it !in known }
        if (added.isEmpty()) return false
        setRest(order.copyOfRange(cursor, order.size).toList() + added)
        return true
    }

    /** New order for the part not yet played (after a change of the shuffle mode). */
    fun rearrangeRest() = setRest(order.copyOfRange(cursor, order.size).toList())

    private fun setRest(rest: List<Long>) {
        val arranged = arrange?.invoke(rest) ?: rest.shuffled(random)
        order = order.copyOfRange(0, cursor) + arranged.toLongArray()
    }

    /**
     * Next [count] ids out of [ids] (the library). Ids for which [exists] is false (deleted) and
     * those in [skip] are passed over; at the end of the order a new round begins.
     */
    fun draw(count: Int, ids: List<Long>, exists: (Long) -> Boolean, skip: Set<Long>): List<Long> {
        val out = LinkedHashSet<Long>()
        var guard = order.size + ids.size + count
        while (out.size < count && guard-- > 0) {
            if (cursor >= order.size) startRound(ids)
            if (order.isEmpty()) break
            val id = order[cursor++]
            if (exists(id) && id !in skip) out += id
        }
        return out.toList()
    }

    /** True once after a new round was started (the order then needs saving). */
    fun takeNewRound(): Boolean = newRound.also { newRound = false }

    /** All songs again in a fresh order; the ones played last don't come right at the start. */
    private fun startRound(ids: List<Long>) {
        cursor = 0
        newRound = true
        arrange?.let {
            // Sorted by when they were heard, the ones played last are at the end anyway.
            order = it(ids).toLongArray()
            return
        }
        val keepAway = minOf(20, ids.size / 2)
        val recent = order.takeLast(keepAway).toHashSet()
        val fresh = ids.filter { it !in recent }.shuffled(random).toMutableList()
        ids.filter { it in recent }.forEach { fresh.add(random.nextInt(minOf(keepAway, fresh.size), fresh.size + 1), it) }
        order = fresh.toLongArray()
    }
}
