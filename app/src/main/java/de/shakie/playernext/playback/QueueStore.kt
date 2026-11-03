package de.shakie.playernext.playback

import android.content.Context
import androidx.media3.common.Player
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Persists the play queue by file path so it survives rescans and restarts.
 *
 * Queues can hold the whole library (tens of thousands of songs), so the list is kept apart
 * from the small playback state: `queue_items.txt` (one path per line, then the unshuffled
 * order as indices) is only rewritten when the queue itself changes, `queue_state.json`
 * (index, position, modes) on every song change. Both are written on one background thread
 * in call order.
 */
object QueueStore {
    data class Saved(
        val paths: List<String>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val originalPaths: List<String>?,
        val repeatMode: Int,
    )

    /** The small part: enough for widgets and the start screen without reading the list. */
    data class State(
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
        val count: Int,
        val currentPath: String?,
    )

    private const val ORIGINAL_MARK = "#original"
    private val writer = Executors.newSingleThreadExecutor()

    private fun itemsFile(context: Context) = File(context.filesDir, "queue_items.txt")
    private fun stateFile(context: Context) = File(context.filesDir, "queue_state.json")
    private fun legacyFile(context: Context) = File(context.filesDir, "queue.json")

    /** [original] lists, for a shuffled queue, the positions of [paths] in unshuffled order. */
    fun saveItems(context: Context, paths: List<String>, original: IntArray?) {
        val target = itemsFile(context)
        val legacy = legacyFile(context)
        writer.execute {
            legacy.delete()
            write(target) { out ->
                paths.forEach { out.append(it.replace('\n', ' ')).append('\n') }
                if (original != null) {
                    out.append(ORIGINAL_MARK).append('\n')
                    original.forEach { out.append(it.toString()).append('\n') }
                }
            }
        }
    }

    fun saveState(context: Context, state: State) {
        val target = stateFile(context)
        val json = JSONObject().apply {
            put("index", state.index)
            put("position", state.positionMs)
            put("shuffle", state.shuffle)
            put("repeat", state.repeatMode)
            put("count", state.count)
            put("current", state.currentPath ?: "")
        }.toString()
        writer.execute { write(target) { it.append(json) } }
    }

    /** Blocks until pending writes are done (service shutdown). */
    fun flush() {
        try {
            writer.submit {}.get()
        } catch (_: Exception) {
        }
    }

    private fun write(target: File, body: (Appendable) -> Unit) {
        val tmp = File(target.path + ".tmp")
        tmp.bufferedWriter().use(body)
        tmp.renameTo(target)
    }

    fun loadState(context: Context): State? = try {
        val o = JSONObject(stateFile(context).readText())
        State(o.optInt("index"), o.optLong("position"), o.optBoolean("shuffle"), o.optInt("repeat"), o.optInt("count"),
            o.optString("current").ifEmpty { null })
    } catch (_: Exception) {
        loadLegacy(context)?.let { State(it.index, it.positionMs, it.shuffle, it.repeatMode, it.paths.size, it.paths.getOrNull(it.index)) }
    }

    fun load(context: Context): Saved? {
        val state = loadState(context) ?: return null
        val file = itemsFile(context)
        // Not rewritten since 1.0.0: the list is still in the old file, the state may be newer.
        if (!file.exists()) return loadLegacy(context)?.copy(index = state.index, positionMs = state.positionMs)
        return try {
            val paths = ArrayList<String>(state.count.coerceAtLeast(16))
            val original = ArrayList<Int>()
            var inOriginal = false
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when {
                        inOriginal -> line.toIntOrNull()?.let(original::add)
                        line == ORIGINAL_MARK -> inOriginal = true
                        line.isNotEmpty() -> paths += line
                    }
                }
            }
            val originalPaths = if (inOriginal) original.mapNotNull { paths.getOrNull(it) } else null
            Saved(paths, state.index, state.positionMs, state.shuffle, originalPaths, state.repeatMode)
        } catch (_: Exception) {
            null
        }
    }

    /** Format up to 1.0.0: everything in one JSON file. */
    private fun loadLegacy(context: Context): Saved? = try {
        val o = JSONObject(legacyFile(context).readText())
        fun arr(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }
        Saved(
            arr("paths").orEmpty(), o.optInt("index"), o.optLong("position"), o.optBoolean("shuffle"),
            arr("original"), o.optInt("repeat")
        )
    } catch (_: Exception) {
        null
    }

    /** Moves the saved current song by [delta] while the service isn't running (widget buttons). */
    fun step(context: Context, delta: Int) {
        val saved = load(context) ?: return
        val n = saved.paths.size
        if (n == 0) return
        val next = if (saved.repeatMode == Player.REPEAT_MODE_ALL) Math.floorMod(saved.index + delta, n) else (saved.index + delta).coerceIn(0, n - 1)
        saveState(context, State(next, 0, saved.shuffle, saved.repeatMode, n, saved.paths[next]))
    }

    fun hasQueue(context: Context): Boolean = (loadState(context)?.count ?: 0) > 0
}
