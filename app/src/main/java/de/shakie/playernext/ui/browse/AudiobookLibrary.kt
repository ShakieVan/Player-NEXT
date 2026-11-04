package de.shakie.playernext.ui.browse

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.data.Library
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The "Hörbücher" category: audiobooks in the library (M4B files and the Audiobooks folder) plus
 * books added here – those belong to the library even outside the music folders.
 */
object AudiobookLibrary {
    private class Found(val path: String, val title: String, val duration: Long)

    /** Searches the device (after a media scan of the Audiobooks folder) and offers what is not in the library yet. */
    fun scan(activity: BaseActivity) {
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val books = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_AUDIOBOOKS)
                if (books.isDirectory) scanPaths(activity, listOf(books.path))
                queryBooks(activity)
            }
            val inLibrary = Library.snapshot.songs.map { it.path }.toHashSet()
            val fresh = found.filter { it.path !in inLibrary }
            if (fresh.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.audiobooks_scan)
                    .setMessage(activity.getString(R.string.audiobooks_none_new, found.size))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                return@launch
            }
            val checked = BooleanArray(fresh.size) { true }
            val names = fresh.map { "${it.title}  (${Format.duration(it.duration)})\n${it.path.substringBeforeLast('/').substringAfter("/0/")}" }
            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.resources.getQuantityString(R.plurals.audiobooks_found, fresh.size, fresh.size))
                .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(R.string.audiobooks_add) { _, _ -> add(activity, fresh.filterIndexed { i, _ -> checked[i] }.map { it.path }) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Android's file dialog; picked files become audiobooks (also MP3 books). */
    fun pickFiles(activity: BaseActivity) {
        activity.openDocuments(arrayOf("audio/*")) { uris ->
            if (uris.isEmpty()) return@openDocuments
            activity.lifecycleScope.launch {
                val paths = withContext(Dispatchers.IO) {
                    val resolved = uris.mapNotNull { pathOf(activity, it) }.filter { File(it).exists() || it.startsWith("/storage/") }
                    // Files MediaStore doesn't know yet are scanned first, so the library finds them.
                    scanPaths(activity, resolved)
                    resolved
                }
                if (paths.isEmpty()) {
                    Toast.makeText(activity, R.string.audiobooks_not_resolved, Toast.LENGTH_LONG).show()
                } else {
                    add(activity, paths)
                }
            }
        }
    }

    fun add(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        Prefs.audiobookFiles = Prefs.audiobookFiles + paths
        Library.refresh()
        Toast.makeText(context, context.resources.getQuantityString(R.plurals.audiobooks_added, paths.size, paths.size), Toast.LENGTH_SHORT).show()
    }

    fun remove(path: String) {
        Prefs.audiobookFiles = Prefs.audiobookFiles - path
        Library.refresh()
    }

    /** Every audiobook MediaStore knows, wherever it lies. */
    private fun queryBooks(context: Context): List<Found> {
        val result = ArrayList<Found>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION),
            "${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0 OR ${MediaStore.Audio.Media.DATA} LIKE '%.m4b'", null,
            MediaStore.Audio.Media.TITLE,
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                result += Found(path, c.getString(1)?.ifBlank { null } ?: path.substringAfterLast('/'), c.getLong(2))
            }
        }
        return result
    }

    /** Media scan of [paths]; waits for it, but not forever. */
    private suspend fun scanPaths(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                var left = paths.size
                MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null) { _, _ ->
                    synchronized(paths) { if (--left == 0 && cont.isActive) cont.resume(Unit) }
                }
            }
        }
    }

    /** File path behind a document from the file dialog (storage, downloads or media documents). */
    private fun pathOf(context: Context, uri: Uri): String? {
        val id = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { null }
        when (uri.authority) {
            "com.android.externalstorage.documents" -> if (id != null) {
                val volume = id.substringBefore(':')
                val rel = id.substringAfter(':', "")
                return if (volume == "primary") "${Environment.getExternalStorageDirectory()}/$rel" else "/storage/$volume/$rel"
            }
            "com.android.providers.media.documents" -> id?.substringAfter(':')?.toLongOrNull()?.let { mediaId ->
                return queryData(context, MediaStore.Files.getContentUri("external"), "${MediaStore.Files.FileColumns._ID} = ?", arrayOf(mediaId.toString()))
            }
            "com.android.providers.downloads.documents" -> if (id != null) {
                if (id.startsWith("raw:")) return id.removePrefix("raw:")
                id.substringAfter("msf:", "").toLongOrNull()?.let { mediaId ->
                    queryData(context, MediaStore.Files.getContentUri("external"), "${MediaStore.Files.FileColumns._ID} = ?", arrayOf(mediaId.toString()))
                        ?.let { return it }
                }
            }
        }
        // Other providers: the audio file with the same name and size.
        val (name, size) = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) to c.getLong(1)) else null
        } ?: return null
        return queryData(
            context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.SIZE} = ?", arrayOf(name, size.toString()),
        )
    }

    private fun queryData(context: Context, table: Uri, selection: String, args: Array<String>): String? =
        context.contentResolver.query(table, arrayOf(MediaStore.MediaColumns.DATA), selection, args, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
