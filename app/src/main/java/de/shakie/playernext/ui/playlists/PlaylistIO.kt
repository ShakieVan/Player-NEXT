package de.shakie.playernext.ui.playlists

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import de.shakie.playernext.R
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.Song
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** M3U import/export through the system document picker. */
object PlaylistIO {
    fun export(activity: BaseActivity, name: String, songs: List<Song>) {
        activity.createDocument("$name.m3u") { uri ->
            if (uri == null) return@createDocument
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { write(activity, uri, songs) }
                ItemActions.toast(activity, if (ok) activity.getString(R.string.playlist_exported) else activity.getString(R.string.failed, name))
            }
        }
    }

    private fun write(activity: BaseActivity, uri: Uri, songs: List<Song>): Boolean = try {
        activity.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { w ->
            w.write("#EXTM3U\n")
            for (s in songs) {
                w.write("#EXTINF:${s.duration / 1000},${s.artist} - ${s.title}\n")
                w.write(s.path + "\n")
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun import(activity: BaseActivity) {
        activity.openDocument(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "text/plain", "*/*")) { uri ->
            if (uri == null) return@openDocument
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { read(activity, uri) }
                if (result != null) {
                    val (name, paths) = result
                    PlaylistStore.create(name, paths)
                    ItemActions.toast(activity, activity.getString(R.string.playlist_imported, paths.size))
                }
            }
        }
    }

    private fun read(activity: BaseActivity, uri: Uri): Pair<String, List<String>>? = try {
        val lib = Library.snapshot
        val byName = lib.songs.groupBy { it.path.substringAfterLast('/').lowercase() }
        val lines = activity.contentResolver.openInputStream(uri)!!.bufferedReader().readLines()
        val paths = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { entry ->
            val normalized = entry.replace('\\', '/')
            lib.songsByPath[normalized]?.path
                ?: lib.songs.firstOrNull { it.path.endsWith("/" + normalized.trimStart('.', '/')) }?.path
                ?: byName[normalized.substringAfterLast('/').lowercase()]?.firstOrNull()?.path
        }
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.substringBeforeLast('.') ?: "Import"
        name to paths
    } catch (_: Exception) {
        null
    }
}
