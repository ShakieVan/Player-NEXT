package de.shakie.playernext.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import de.shakie.playernext.Prefs
import de.shakie.playernext.tags.TagIO
import java.util.concurrent.Executors

/**
 * Ratings stored in the music files (POPM, RATING, rate …), kept in step with the app's own.
 * Each file is read once and then only when MediaStore reports it changed (date or size), in
 * the background after a library change – no timer. The app's rating is never lowered or
 * cleared by this: a file's rating is taken over only where the app has none, or where the app
 * still has what the file said last time (then it was changed in another player).
 */
object FileRatings {
    private class Seen(val modified: Long, val size: Long, val rating: Int)

    class Result(val read: Int, val taken: Int)

    private val seen = HashMap<String, Seen>()
    private val lock = Any()
    private var tableLoaded = false
    private lateinit var appContext: Context
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "file-ratings").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile private var queuedFor: LibrarySnapshot? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        Library.addListener { schedule() }
    }

    /** The app rating after reading [file] from a file that said [last] before; null = keep [app]. */
    fun decide(file: Int, last: Int?, app: Int): Int? = when {
        file <= 0 || file == app -> null
        app == 0 || app == last -> file
        else -> null
    }

    /** Reads new and changed files in the background (setting "Bewertungen aus Dateien lesen"). */
    fun schedule() {
        if (!Prefs.ratingsFromFiles) return
        val snap = Library.snapshot
        if (snap === queuedFor || snap.songs.isEmpty()) return
        queuedFor = snap
        worker.execute {
            if (queuedFor !== snap) return@execute
            // A newer library or the setting turned off ends this pass; the next one picks up the rest.
            run(snap.songs, force = false, copy = false, cancelled = { queuedFor !== snap || !Prefs.ratingsFromFiles })
            if (!Prefs.ratingsFromFiles && queuedFor === snap) queuedFor = null
        }
    }

    /** Settings → "Bewertungen aus Dateien importieren": reads every file again. */
    fun importAll(songs: List<Song>, cancelled: () -> Boolean, onProgress: (Int, Int) -> Unit): Result =
        run(songs, force = true, copy = true, cancelled = cancelled, onProgress = onProgress)

    /** After Player NEXT wrote [rating] into the file itself. */
    fun wrote(song: Song, rating: Int) = synchronized(lock) {
        loadTable()
        seen[song.path] = Seen(-1, -1, rating)
    }

    private fun run(
        songs: List<Song>, force: Boolean, copy: Boolean, cancelled: () -> Boolean, onProgress: ((Int, Int) -> Unit)? = null,
    ): Result = synchronized(lock) {
        loadTable()
        val changes = HashMap<String, Int>()
        val rows = ArrayList<Pair<String, Seen>>()
        var read = 0
        var taken = 0
        var lastFlush = SystemClock.elapsedRealtime()
        fun flush() {
            taken += changes.size
            StatsStore.setRatings(HashMap(changes))
            changes.clear()
            saveRows(ArrayList(rows))
            rows.clear()
            lastFlush = SystemClock.elapsedRealtime()
        }
        for ((i, song) in songs.withIndex()) {
            if (cancelled()) break
            onProgress?.invoke(i, songs.size)
            val old = seen[song.path]
            if (!force && old != null && old.modified == song.dateModified && old.size == song.size) continue
            // Unreadable files count as "no rating" until they change, so they aren't retried each time.
            val file = TagIO.readRating(appContext, song, copy) ?: 0
            read++
            decide(file, old?.rating, StatsStore[song.path].rating)?.let { changes[song.path] = it }
            Seen(song.dateModified, song.size, file).let {
                seen[song.path] = it
                rows += song.path to it
            }
            // Lists redraw on each rating change notification: not more often than every few seconds.
            if (rows.size >= 500 || (changes.isNotEmpty() && SystemClock.elapsedRealtime() - lastFlush > 5000)) flush()
        }
        flush()
        onProgress?.invoke(songs.size, songs.size)
        Result(read, taken)
    }

    private fun loadTable() {
        if (tableLoaded) return
        tableLoaded = true
        AppDatabase.get(appContext).readableDatabase.rawQuery("SELECT path, modified, size, rating FROM file_ratings", null).use { c ->
            while (c.moveToNext()) seen[c.getString(0)] = Seen(c.getLong(1), c.getLong(2), c.getInt(3))
        }
    }

    private fun saveRows(rows: List<Pair<String, Seen>>) {
        if (rows.isEmpty()) return
        AppDatabase.write {
            val w = AppDatabase.get(appContext).writableDatabase
            w.beginTransaction()
            try {
                for ((path, s) in rows) {
                    val v = ContentValues().apply {
                        put("path", path)
                        put("modified", s.modified)
                        put("size", s.size)
                        put("rating", s.rating)
                    }
                    w.insertWithOnConflict("file_ratings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
    }
}
