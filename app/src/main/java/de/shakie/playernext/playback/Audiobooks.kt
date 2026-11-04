package de.shakie.playernext.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Chapters of the files played (MP4 family only), read once in the background and kept in
 * memory. Main thread only, apart from the reading itself.
 */
object ChapterStore {
    private val extensions = setOf("m4b", "m4a", "mp4", "m4p")
    private const val MAX_FILES = 64
    private val cache = object : LinkedHashMap<String, List<Chapter>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Chapter>>) = size > MAX_FILES
    }
    private val loading = HashSet<String>()
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "chapters").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)

    /** Known chapters of [path]; null while not read yet. */
    fun cached(path: String?): List<Chapter>? = path?.let { cache[it] }

    /** Reads the chapters of [path] unless known; listeners hear about it when done. */
    fun request(path: String?) {
        if (path == null || path in cache || path in loading) return
        if (path.substringAfterLast('.', "").lowercase() !in extensions) {
            cache[path] = emptyList()
            return
        }
        loading += path
        reader.execute {
            val chapters = Mp4Chapters.read(File(path))
            main.post {
                loading -= path
                cache[path] = chapters
                listeners.forEach { it(path) }
            }
        }
    }
}

/** Where each audiobook was left, so it continues there after other music (setting, on by default). */
object AudiobookPositions {
    private const val PREFS = "audiobook_positions"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, path: String): Long? = prefs(context).getLong(path, -1).takeIf { it > 0 }

    /**
     * Listened to the end: forgotten. Near the start nothing changes – a queue change reports the
     * new item at 0 before it moves to the saved place, which must not wipe it.
     */
    fun save(context: Context, path: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && durationMs - positionMs <= 30_000
        when {
            finished -> prefs(context).edit().remove(path).apply()
            positionMs > 10_000 -> prefs(context).edit().putLong(path, positionMs).apply()
        }
    }
}
