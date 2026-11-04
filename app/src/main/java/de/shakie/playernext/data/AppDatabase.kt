package de.shakie.playernext.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "playernext.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE stats (path TEXT PRIMARY KEY, rating INTEGER NOT NULL DEFAULT 0, " +
                "play_count INTEGER NOT NULL DEFAULT 0, skip_count INTEGER NOT NULL DEFAULT 0, " +
                "last_played INTEGER NOT NULL DEFAULT 0, last_skipped INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "created INTEGER NOT NULL, modified INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE playlist_items (playlist_id INTEGER NOT NULL, position INTEGER NOT NULL, path TEXT NOT NULL)")
        db.execSQL("CREATE INDEX playlist_items_pl ON playlist_items(playlist_id, position)")
        db.execSQL("CREATE TABLE smart_playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, definition TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE favorites (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, " +
                "item_key TEXT NOT NULL, title TEXT NOT NULL, position INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE eq_presets (name TEXT PRIMARY KEY, bands TEXT NOT NULL, preamp REAL NOT NULL)")
        createFileRatings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createFileRatings(db)
    }

    /** What the files said about ratings when last read (FileRatings); only a cache, never user data. */
    private fun createFileRatings(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS file_ratings (path TEXT PRIMARY KEY, modified INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, rating INTEGER NOT NULL)"
        )
    }

    companion object {
        @Volatile
        private var instance: AppDatabase? = null
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "db-writer").apply { priority = Thread.MIN_PRIORITY } }
        val main = Handler(Looper.getMainLooper())

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) { instance ?: AppDatabase(context.applicationContext).also { instance = it } }

        fun write(block: () -> Unit) = writer.execute(block)
    }
}

/** Small helper for "something changed" notifications on the main thread. */
open class ChangeNotifier {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    protected fun notifyChanged() = AppDatabase.main.post { listeners.forEach { it() } }
}

data class SongStats(
    val rating: Int = 0,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayed: Long = 0,
    val lastSkipped: Long = 0,
)

/** Ratings and play statistics, cached in memory and persisted asynchronously. */
object StatsStore : ChangeNotifier() {
    private lateinit var db: AppDatabase
    private val cache = HashMap<String, SongStats>()

    fun init(context: Context) {
        db = AppDatabase.get(context)
        db.readableDatabase.rawQuery("SELECT path, rating, play_count, skip_count, last_played, last_skipped FROM stats", null).use { c ->
            synchronized(cache) {
                while (c.moveToNext()) {
                    cache[c.getString(0)] = SongStats(c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5))
                }
            }
        }
    }

    operator fun get(path: String): SongStats = synchronized(cache) { cache[path] } ?: SongStats()

    fun all(): Map<String, SongStats> = synchronized(cache) { HashMap(cache) }

    /** Rating in half-star units (0..10). */
    fun setRating(path: String, rating: Int) = update(path) { it.copy(rating = rating.coerceIn(0, 10)) }

    /** Several ratings at once (import from files): one write and one change notification. */
    fun setRatings(ratings: Map<String, Int>) {
        if (ratings.isEmpty()) return
        val rows = synchronized(cache) {
            ratings.map { (path, r) -> path to (cache[path] ?: SongStats()).copy(rating = r.coerceIn(0, 10)).also { cache[path] = it } }
        }
        AppDatabase.write {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                rows.forEach { (p, s) -> w.insertWithOnConflict("stats", null, values(p, s), SQLiteDatabase.CONFLICT_REPLACE) }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        notifyChanged()
    }

    fun recordPlay(path: String) = update(path) { it.copy(playCount = it.playCount + 1, lastPlayed = System.currentTimeMillis()) }

    fun recordSkip(path: String) = update(path) { it.copy(skipCount = it.skipCount + 1, lastSkipped = System.currentTimeMillis()) }

    fun clear(path: String) = update(path) { SongStats() }

    fun replaceAll(data: Map<String, SongStats>) {
        synchronized(cache) {
            cache.clear()
            cache.putAll(data)
        }
        AppDatabase.write {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                w.delete("stats", null, null)
                data.forEach { (p, s) -> w.insert("stats", null, values(p, s)) }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        notifyChanged()
    }

    fun movePath(from: String, to: String) {
        val s = synchronized(cache) { cache.remove(from) } ?: return
        update(to) { s }
        AppDatabase.write { db.writableDatabase.delete("stats", "path = ?", arrayOf(from)) }
    }

    private fun update(path: String, change: (SongStats) -> SongStats) {
        val next = synchronized(cache) {
            change(cache[path] ?: SongStats()).also { cache[path] = it }
        }
        AppDatabase.write {
            db.writableDatabase.insertWithOnConflict("stats", null, values(path, next), SQLiteDatabase.CONFLICT_REPLACE)
        }
        notifyChanged()
    }

    private fun values(path: String, s: SongStats) = ContentValues().apply {
        put("path", path)
        put("rating", s.rating)
        put("play_count", s.playCount)
        put("skip_count", s.skipCount)
        put("last_played", s.lastPlayed)
        put("last_skipped", s.lastSkipped)
    }
}

object PlaylistStore : ChangeNotifier() {
    private lateinit var db: AppDatabase
    private val cache = ArrayList<Playlist>()

    fun init(context: Context) {
        db = AppDatabase.get(context)
        reload()
    }

    private fun reload() {
        val list = ArrayList<Playlist>()
        val r = db.readableDatabase
        r.rawQuery("SELECT id, name, modified FROM playlists ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val paths = ArrayList<String>()
                r.rawQuery("SELECT path FROM playlist_items WHERE playlist_id = ? ORDER BY position", arrayOf(id.toString())).use { ic ->
                    while (ic.moveToNext()) paths += ic.getString(0)
                }
                list += Playlist(id, c.getString(1), paths, c.getLong(2))
            }
        }
        synchronized(cache) {
            cache.clear()
            cache.addAll(list)
        }
    }

    fun all(): List<Playlist> = synchronized(cache) { ArrayList(cache) }
    fun byId(id: Long): Playlist? = synchronized(cache) { cache.firstOrNull { it.id == id } }
    fun byName(name: String): Playlist? = synchronized(cache) { cache.firstOrNull { it.name.equals(name, true) } }

    fun create(name: String, paths: List<String> = emptyList()): Long {
        val now = System.currentTimeMillis()
        val w = db.writableDatabase
        val id = w.insert("playlists", null, ContentValues().apply {
            put("name", name); put("created", now); put("modified", now)
        })
        writeItems(id, paths)
        reload()
        notifyChanged()
        return id
    }

    fun rename(id: Long, name: String) {
        db.writableDatabase.update("playlists", ContentValues().apply {
            put("name", name); put("modified", System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString()))
        reload()
        notifyChanged()
    }

    fun delete(id: Long) {
        val w = db.writableDatabase
        w.delete("playlist_items", "playlist_id = ?", arrayOf(id.toString()))
        w.delete("playlists", "id = ?", arrayOf(id.toString()))
        reload()
        notifyChanged()
    }

    fun add(id: Long, paths: List<String>) {
        val current = byId(id) ?: return
        setItems(id, current.songPaths + paths)
    }

    fun setItems(id: Long, paths: List<String>) {
        writeItems(id, paths)
        db.writableDatabase.update("playlists", ContentValues().apply { put("modified", System.currentTimeMillis()) },
            "id = ?", arrayOf(id.toString()))
        reload()
        notifyChanged()
    }

    fun replacePath(from: String, to: String) {
        db.writableDatabase.update("playlist_items", ContentValues().apply { put("path", to) }, "path = ?", arrayOf(from))
        reload()
        notifyChanged()
    }

    private fun writeItems(id: Long, paths: List<String>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete("playlist_items", "playlist_id = ?", arrayOf(id.toString()))
            paths.forEachIndexed { i, p ->
                w.insert("playlist_items", null, ContentValues().apply {
                    put("playlist_id", id); put("position", i); put("path", p)
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }
}

data class Favorite(val id: Long, val type: ItemType, val key: String, val title: String)

object FavoritesStore : ChangeNotifier() {
    private lateinit var db: AppDatabase
    private val cache = ArrayList<Favorite>()

    fun init(context: Context) {
        db = AppDatabase.get(context)
        reload()
    }

    private fun reload() {
        val list = ArrayList<Favorite>()
        db.readableDatabase.rawQuery("SELECT id, type, item_key, title FROM favorites ORDER BY position", null).use { c ->
            while (c.moveToNext()) {
                val type = ItemType.fromKey(c.getString(1)) ?: continue
                list += Favorite(c.getLong(0), type, c.getString(2), c.getString(3))
            }
        }
        synchronized(cache) { cache.clear(); cache.addAll(list) }
    }

    fun all(): List<Favorite> = synchronized(cache) { ArrayList(cache) }

    fun contains(type: ItemType, key: String) = synchronized(cache) { cache.any { it.type == type && it.key == key } }

    fun add(type: ItemType, key: String, title: String) {
        if (contains(type, key)) return
        db.writableDatabase.insert("favorites", null, ContentValues().apply {
            put("type", type.key); put("item_key", key); put("title", title); put("position", all().size)
        })
        reload()
        notifyChanged()
    }

    fun remove(type: ItemType, key: String) {
        db.writableDatabase.delete("favorites", "type = ? AND item_key = ?", arrayOf(type.key, key))
        reload()
        notifyChanged()
    }

    fun toggle(type: ItemType, key: String, title: String): Boolean =
        if (contains(type, key)) { remove(type, key); false } else { add(type, key, title); true }

    fun reorder(ordered: List<Favorite>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            ordered.forEachIndexed { i, f ->
                w.update("favorites", ContentValues().apply { put("position", i) }, "id = ?", arrayOf(f.id.toString()))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        reload()
        notifyChanged()
    }

    fun replaceKey(type: ItemType, from: String, to: String) {
        db.writableDatabase.update("favorites", ContentValues().apply { put("item_key", to) },
            "type = ? AND item_key = ?", arrayOf(type.key, from))
        reload()
        notifyChanged()
    }
}

object SmartPlaylistStore : ChangeNotifier() {
    private lateinit var db: AppDatabase
    private val cache = ArrayList<SmartPlaylist>()

    fun init(context: Context) {
        db = AppDatabase.get(context)
        reload()
    }

    private fun reload() {
        val list = ArrayList<SmartPlaylist>()
        db.readableDatabase.rawQuery("SELECT id, name, definition FROM smart_playlists ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                SmartPlaylist.fromJson(c.getLong(0), c.getString(1), c.getString(2))?.let { list += it }
            }
        }
        synchronized(cache) { cache.clear(); cache.addAll(list) }
    }

    fun all(): List<SmartPlaylist> = synchronized(cache) { ArrayList(cache) }
    fun byId(id: Long) = synchronized(cache) { cache.firstOrNull { it.id == id } }

    fun save(sp: SmartPlaylist): Long {
        val values = ContentValues().apply { put("name", sp.name); put("definition", sp.toJson()) }
        val w = db.writableDatabase
        val id = if (sp.id > 0) {
            w.update("smart_playlists", values, "id = ?", arrayOf(sp.id.toString())); sp.id
        } else {
            w.insert("smart_playlists", null, values)
        }
        reload()
        notifyChanged()
        return id
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("smart_playlists", "id = ?", arrayOf(id.toString()))
        reload()
        notifyChanged()
    }
}

object EqPresetStore : ChangeNotifier() {
    private lateinit var db: AppDatabase
    private val cache = LinkedHashMap<String, Pair<FloatArray, Float>>()

    fun init(context: Context) {
        db = AppDatabase.get(context)
        db.readableDatabase.rawQuery("SELECT name, bands, preamp FROM eq_presets ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                val bands = c.getString(1).split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
                cache[c.getString(0)] = bands to c.getFloat(2)
            }
        }
    }

    fun userPresets(): Map<String, Pair<FloatArray, Float>> = synchronized(cache) { LinkedHashMap(cache) }

    fun save(name: String, bands: FloatArray, preamp: Float) {
        synchronized(cache) { cache[name] = bands.copyOf() to preamp }
        AppDatabase.write {
            db.writableDatabase.insertWithOnConflict("eq_presets", null, ContentValues().apply {
                put("name", name); put("bands", bands.joinToString(",")); put("preamp", preamp)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        notifyChanged()
    }

    fun delete(name: String) {
        synchronized(cache) { cache.remove(name) }
        AppDatabase.write { db.writableDatabase.delete("eq_presets", "name = ?", arrayOf(name)) }
        notifyChanged()
    }

    fun rename(from: String, to: String) {
        val p = synchronized(cache) { cache[from] } ?: return
        delete(from)
        save(to, p.first, p.second)
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
        AppDatabase.write { db.writableDatabase.delete("eq_presets", null, null) }
        notifyChanged()
    }
}
