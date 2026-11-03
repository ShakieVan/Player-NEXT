package de.shakie.playernext.art

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** What an artwork request refers to. */
sealed class ArtKey(val cacheKey: String) {
    class Album(val albumId: Long) : ArtKey("album:$albumId")
    class Song(val songId: Long, val albumId: Long) : ArtKey("song:$songId")
    class Artist(val name: String, val albumIds: List<Long>) : ArtKey("artist:$name")
    class Collage(val id: String, val albumIds: List<Long>) : ArtKey("collage:$id")
    class Video(val videoId: Long) : ArtKey("video:$videoId")
}

object ArtLoader {
    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "art-loader").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val missing = HashSet<String>()
    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }
    @Volatile
    var generation = 0
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun invalidate() {
        generation++
        cache.evictAll()
        synchronized(missing) { missing.clear() }
    }

    fun load(view: ImageView, key: ArtKey?, sizePx: Int, placeholder: Int = R.drawable.placeholder_album, blur: Boolean = false) {
        if (key == null) {
            view.tag = null
            view.setImageResource(placeholder)
            return
        }
        val tag = "${key.cacheKey}@$sizePx"
        view.tag = tag
        if (blur && Build.VERSION.SDK_INT >= 31) {
            view.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
        }
        cache.get(tag)?.let {
            view.setImageBitmap(it)
            return
        }
        if (synchronized(missing) { tag in missing }) {
            view.setImageResource(placeholder)
            return
        }
        view.setImageResource(placeholder)
        val gen = generation
        executor.execute {
            val bmp = try { decode(key, sizePx) } catch (_: Throwable) { null }
            if (bmp != null) cache.put(tag, bmp) else synchronized(missing) { missing += tag }
            main.post {
                if (view.tag == tag && gen == generation) {
                    if (bmp != null) view.setImageBitmap(bmp) else view.setImageResource(placeholder)
                }
            }
        }
    }

    /** Synchronous variant for notification, widgets and the session; call off the main thread. */
    fun loadBitmap(key: ArtKey, sizePx: Int): Bitmap? {
        val tag = "${key.cacheKey}@$sizePx"
        cache.get(tag)?.let { return it }
        return try { decode(key, sizePx) } catch (_: Throwable) { null }?.also { cache.put(tag, it) }
    }

    fun loadAsync(key: ArtKey, sizePx: Int, callback: (Bitmap?) -> Unit) {
        val tag = "${key.cacheKey}@$sizePx"
        cache.get(tag)?.let { callback(it); return }
        executor.execute {
            val bmp = loadBitmap(key, sizePx)
            main.post { callback(bmp) }
        }
    }

    private fun decode(key: ArtKey, size: Int): Bitmap? = when (key) {
        is ArtKey.Album -> custom("album_${key.albumId}", size) ?: albumThumb(key.albumId, size)
        is ArtKey.Song -> custom("album_${key.albumId}", size).takeUnless { Prefs.preferEmbeddedArt }
            ?: thumb(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key.songId), size)
            ?: albumThumb(key.albumId, size)
        is ArtKey.Artist -> custom("artist_${key.name}", size) ?: firstAvailable(key.albumIds, size)
        is ArtKey.Collage -> custom("collage_${key.id}", size) ?: collage(key.albumIds, size)
        is ArtKey.Video -> thumb(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, key.videoId), size)
    }

    private fun albumThumb(albumId: Long, size: Int): Bitmap? {
        custom("album_$albumId", size)?.let { return it }
        return thumb(ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId), size)
    }

    private fun thumb(uri: Uri, size: Int): Bitmap? = try {
        appContext.contentResolver.loadThumbnail(uri, Size(size, size), null)
    } catch (_: Exception) {
        null
    }

    private fun firstAvailable(albumIds: List<Long>, size: Int): Bitmap? {
        for (id in albumIds.take(6)) albumThumb(id, size)?.let { return it }
        return null
    }

    private fun collage(albumIds: List<Long>, size: Int): Bitmap? {
        val distinct = albumIds.distinct()
        if (distinct.size < 4) return firstAvailable(distinct, size)
        val half = size / 2
        val parts = distinct.asSequence().mapNotNull { albumThumb(it, half) }.take(4).toList()
        if (parts.size < 4) return parts.firstOrNull() ?: firstAvailable(distinct, size)
        val out = Bitmap.createBitmap(half * 2, half * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        parts.forEachIndexed { i, b ->
            val dst = Rect((i % 2) * half, (i / 2) * half, (i % 2 + 1) * half, (i / 2 + 1) * half)
            c.drawBitmap(b, centerSquare(b), dst, null)
        }
        return out
    }

    private fun centerSquare(b: Bitmap): Rect {
        val s = minOf(b.width, b.height)
        val x = (b.width - s) / 2
        val y = (b.height - s) / 2
        return Rect(x, y, x + s, y + s)
    }

    // --- user selected artwork -------------------------------------------------------------

    private fun customFile(name: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        val category = name.substringBefore('_')
        return File(File(appContext.filesDir, "art/$category").apply { mkdirs() }, "$hex.jpg")
    }

    private fun custom(name: String, size: Int): Bitmap? {
        val f = customFile(name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun customName(key: ArtKey): String = when (key) {
        is ArtKey.Album -> "album_${key.albumId}"
        is ArtKey.Song -> "album_${key.albumId}"
        is ArtKey.Artist -> "artist_${key.name}"
        is ArtKey.Collage -> "collage_${key.id}"
        is ArtKey.Video -> "video_${key.videoId}"
    }

    fun hasCustom(key: ArtKey) = customFile(customName(key)).exists()

    fun setCustom(key: ArtKey, source: Uri): Boolean = try {
        val bmp = appContext.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val scaled = if (bmp.width > 1200 || bmp.height > 1200) {
            val f = 1200f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else bmp
        FileOutputStream(customFile(customName(key))).use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        invalidate()
        true
    } catch (_: Exception) {
        false
    }

    fun setCustom(key: ArtKey, bitmap: Bitmap) {
        FileOutputStream(customFile(customName(key))).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        invalidate()
    }

    fun removeCustom(key: ArtKey) {
        customFile(customName(key)).delete()
        invalidate()
    }

    /** Removes every user selected picture of one category ("album", "artist", "collage"). */
    fun deleteAllCustom(category: String) {
        File(appContext.filesDir, "art/$category").listFiles()?.forEach { it.delete() }
        invalidate()
    }
}
