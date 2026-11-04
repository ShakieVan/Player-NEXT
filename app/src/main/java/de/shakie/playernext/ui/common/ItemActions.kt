package de.shakie.playernext.ui.common

import android.content.ClipData
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.Album
import de.shakie.playernext.data.FavoritesStore
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.SmartPlaylistStore
import de.shakie.playernext.data.Song
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.data.Video
import de.shakie.playernext.playback.PlayerHub
import de.shakie.playernext.tags.TagIO
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.MainActivity
import de.shakie.playernext.ui.playlists.PlaylistIO
import de.shakie.playernext.ui.playlists.SmartPlaylistEditorActivity
import de.shakie.playernext.ui.tags.TagEditorActivity
import de.shakie.playernext.ui.video.VideoPlayerActivity
import de.shakie.playernext.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something an action can be applied to: a song, album, artist, folder, playlist … */
class Target(
    val type: ItemType,
    val key: String,
    val title: String,
    val art: ArtKey? = null,
    val album: Album? = null,
    val video: Video? = null,
    val songs: () -> List<Song>,
)

/** Shared implementation of PlayerPro's item context menus. */
object ItemActions {
    private const val PLAY = 1
    private const val ENQUEUE = 2
    private const val PLAY_NEXT = 3
    private const val SHUFFLE = 4
    private const val ADD_PLAYLIST = 5
    private const val FAVORITE = 6
    private const val ALBUM_INFO = 7
    private const val MANAGE_ART = 8
    private const val EDIT_TAGS = 9
    private const val SEARCH = 10
    private const val DELETE = 11
    private const val LYRICS = 12
    private const val DETAILS = 13
    private const val SHARE = 14
    private const val RINGTONE = 15
    private const val RENAME = 16
    private const val EXPORT = 17
    private const val EDIT_SMART = 18
    private const val PLAY_VIDEO = 19
    private const val SCAN = 20
    const val EXTRA_BASE = 100

    class Extra(val title: String, val action: () -> Unit)

    fun showMenu(activity: BaseActivity, anchor: View, target: Target, extras: List<Extra> = emptyList()) {
        val popup = PopupMenu(activity, anchor)
        val m = popup.menu
        var order = 0
        var withDelete = false
        fun add(id: Int, title: Int) {
            if (id == DELETE) withDelete = true else m.add(Menu.NONE, id, order++, title)
        }
        val fav = FavoritesStore.contains(target.type, target.key)
        if (target.type == ItemType.VIDEO) {
            add(PLAY_VIDEO, R.string.action_play_video)
            add(FAVORITE, if (fav) R.string.action_remove_favorite else R.string.action_add_favorite)
            add(SHARE, R.string.action_share)
            add(DELETE, R.string.action_delete)
        } else {
            add(PLAY, R.string.action_play)
            add(ENQUEUE, R.string.action_enqueue)
            add(PLAY_NEXT, R.string.action_play_next)
            add(SHUFFLE, R.string.action_shuffle)
            if (target.type != ItemType.PLAYLIST) add(ADD_PLAYLIST, R.string.action_add_to_playlist)
            add(FAVORITE, if (fav) R.string.action_remove_favorite else R.string.action_add_favorite)
            when (target.type) {
                ItemType.SONG -> {
                    add(LYRICS, R.string.action_lyrics)
                    add(DETAILS, R.string.action_details)
                    add(EDIT_TAGS, R.string.action_edit_tags)
                    add(SEARCH, R.string.action_search)
                    add(SHARE, R.string.action_share)
                    add(RINGTONE, R.string.action_ringtone)
                    add(DELETE, R.string.action_delete)
                }
                ItemType.ALBUM -> {
                    add(ALBUM_INFO, R.string.action_album_info)
                    add(MANAGE_ART, R.string.action_manage_art)
                    add(EDIT_TAGS, R.string.action_edit_tags)
                    add(SEARCH, R.string.action_search)
                    add(DELETE, R.string.action_delete)
                }
                ItemType.ARTIST, ItemType.COMPOSER, ItemType.GENRE -> {
                    add(MANAGE_ART, if (target.type == ItemType.ARTIST) R.string.action_manage_artist_art else R.string.action_manage_picture)
                    add(EDIT_TAGS, R.string.action_edit_tags)
                    add(SEARCH, R.string.action_search)
                    add(DELETE, R.string.action_delete)
                }
                ItemType.FOLDER -> {
                    add(SCAN, R.string.action_scan_folder)
                    add(EDIT_TAGS, R.string.action_edit_tags)
                    add(DELETE, R.string.action_delete)
                }
                ItemType.PLAYLIST -> {
                    add(RENAME, R.string.action_rename)
                    add(EXPORT, R.string.action_export)
                    add(DELETE, R.string.action_delete)
                }
                ItemType.SMART_PLAYLIST -> if ((target.key.toLongOrNull() ?: -1) > 0) {
                    add(EDIT_SMART, R.string.action_edit_smart)
                    add(EXPORT, R.string.action_export)
                    add(DELETE, R.string.action_delete)
                } else {
                    add(EXPORT, R.string.action_export)
                }
                ItemType.VIDEO -> Unit
            }
        }
        extras.forEachIndexed { i, e -> m.add(Menu.NONE, EXTRA_BASE + i, order++, e.title) }
        if (withDelete) m.add(Menu.NONE, DELETE, order, R.string.action_delete)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId >= EXTRA_BASE) extras[item.itemId - EXTRA_BASE].action() else run(activity, item.itemId, target)
            true
        }
        popup.show()
    }

    private fun run(activity: BaseActivity, id: Int, target: Target) {
        when (id) {
            PLAY -> PlayerHub.play(target.songs())
            ENQUEUE -> enqueue(activity, target.songs())
            PLAY_NEXT -> playNext(activity, target.songs())
            SHUFFLE -> PlayerHub.play(target.songs(), shuffle = true)
            ADD_PLAYLIST -> addToPlaylist(activity, target.songs())
            FAVORITE -> toggleFavorite(activity, target)
            ALBUM_INFO -> target.album?.let { albumInfo(activity, it) }
            MANAGE_ART -> target.art?.let { manageArt(activity, it) }
            EDIT_TAGS -> TagEditorActivity.start(activity, target.songs())
            SEARCH -> activity.startActivity(
                Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_SEARCH).putExtra("query", target.title)
            )
            DELETE -> when (target.type) {
                ItemType.PLAYLIST -> confirm(activity, activity.getString(R.string.delete_playlist_confirm, target.title)) {
                    target.key.toLongOrNull()?.let { PlaylistStore.delete(it) }
                    FavoritesStore.remove(target.type, target.key)
                }
                ItemType.SMART_PLAYLIST -> confirm(activity, activity.getString(R.string.delete_playlist_confirm, target.title)) {
                    target.key.toLongOrNull()?.let { SmartPlaylistStore.delete(it) }
                    FavoritesStore.remove(target.type, target.key)
                }
                ItemType.VIDEO -> target.video?.let { deleteUris(activity, listOf(it.uri)) {} }
                else -> deleteSongs(activity, target.songs())
            }
            LYRICS -> target.songs().firstOrNull()?.let { showLyrics(activity, it) }
            DETAILS -> target.songs().firstOrNull()?.let { details(activity, it) }
            SHARE -> if (target.video != null) shareUris(activity, listOf(target.video.uri), "video/*") else share(activity, target.songs())
            RINGTONE -> target.songs().firstOrNull()?.let { setRingtone(activity, it) }
            RENAME -> target.key.toLongOrNull()?.let { pid ->
                prompt(activity, R.string.action_rename, target.title) { name -> PlaylistStore.rename(pid, name) }
            }
            EXPORT -> PlaylistIO.export(activity, target.title, target.songs())
            EDIT_SMART -> target.key.toLongOrNull()?.let { SmartPlaylistEditorActivity.start(activity, it) }
            PLAY_VIDEO -> target.video?.let { VideoPlayerActivity.start(activity, it) }
            SCAN -> scanFolder(activity, target.key, target.title)
        }
    }

    /** Media scanner over the folder and everything below it: new, changed and removed files. */
    fun scanFolder(activity: BaseActivity, path: String, name: String) {
        val app = activity.applicationContext
        toast(activity, activity.getString(R.string.folder_scan_started, name))
        android.media.MediaScannerConnection.scanFile(app, arrayOf(path), null) { _, _ ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Library.refresh()
                Toast.makeText(app, app.getString(R.string.folder_scan_done, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun enqueue(activity: BaseActivity, songs: List<Song>) {
        PlayerHub.enqueue(songs)
        toast(activity, activity.getString(R.string.enqueued, songs.size))
    }

    fun playNext(activity: BaseActivity, songs: List<Song>) {
        PlayerHub.playNext(songs)
        toast(activity, activity.getString(R.string.play_next_added, songs.size))
    }

    fun toggleFavorite(activity: BaseActivity, target: Target) {
        val added = FavoritesStore.toggle(target.type, target.key, target.title)
        toast(activity, activity.getString(if (added) R.string.added_favorite else R.string.removed_favorite))
    }

    fun addToPlaylist(activity: BaseActivity, songs: List<Song>) {
        if (songs.isEmpty()) return
        val playlists = PlaylistStore.all()
        val names = arrayOf(activity.getString(R.string.new_playlist)) + playlists.map { it.name }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.choose_playlist)
            .setItems(names) { _, which ->
                if (which == 0) {
                    prompt(activity, R.string.new_playlist, "") { name ->
                        PlaylistStore.create(name, songs.map { it.path })
                        toast(activity, activity.getString(R.string.added_to_playlist, songs.size, name))
                    }
                } else {
                    val p = playlists[which - 1]
                    PlaylistStore.add(p.id, songs.map { it.path })
                    toast(activity, activity.getString(R.string.added_to_playlist, songs.size, p.name))
                }
            }
            .show()
    }

    fun prompt(activity: BaseActivity, title: Int, initial: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            hint = activity.getString(R.string.playlist_name)
            setSingleLine()
        }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    fun confirm(activity: BaseActivity, message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun deleteSongs(activity: BaseActivity, songs: List<Song>) {
        if (songs.isEmpty()) return
        deleteUris(activity, songs.map { it.uri }) { ok ->
            if (ok) {
                songs.forEach { StatsStore.clear(it.path) }
                toast(activity, activity.getString(R.string.deleted_songs, songs.size))
                Library.refresh()
            }
        }
    }

    private fun deleteUris(activity: BaseActivity, uris: List<Uri>, done: (Boolean) -> Unit) {
        val request = MediaStore.createDeleteRequest(activity.contentResolver, uris)
        activity.runIntentSender(request.intentSender, done)
    }

    fun share(activity: BaseActivity, songs: List<Song>) = shareUris(activity, songs.map { it.uri }, "audio/*")

    private fun shareUris(activity: BaseActivity, uris: List<Uri>, type: String) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, null))
    }

    /** Shares the "now playing" text like PlayerPro's share button. */
    fun shareText(activity: BaseActivity, song: Song) {
        val text = "♪ ${song.title} – ${song.artist}" + if (song.album.isNotBlank()) " (${song.album})" else ""
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    fun setRingtone(activity: BaseActivity, song: Song) {
        if (!Settings.System.canWrite(activity)) {
            toast(activity, activity.getString(R.string.ringtone_permission))
            activity.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}")))
            return
        }
        RingtoneManager.setActualDefaultRingtoneUri(activity, RingtoneManager.TYPE_RINGTONE, song.uri)
        toast(activity, activity.getString(R.string.ringtone_set, song.title))
    }

    fun manageArt(activity: BaseActivity, key: ArtKey) {
        val items = mutableListOf(activity.getString(R.string.art_choose))
        val hasCustom = ArtLoader.hasCustom(key)
        if (hasCustom) items += activity.getString(R.string.art_remove)
        MaterialAlertDialogBuilder(activity)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    activity.pickImage { uri ->
                        if (uri == null) return@pickImage
                        activity.lifecycleScope.launch {
                            val ok = withContext(Dispatchers.IO) { ArtLoader.setCustom(key, uri) }
                            toast(activity, activity.getString(if (ok) R.string.art_saved else R.string.art_failed))
                            Library.notifyArtChanged()
                        }
                    }
                } else {
                    ArtLoader.removeCustom(key)
                    Library.notifyArtChanged()
                }
            }
            .show()
    }

    fun details(activity: BaseActivity, song: Song) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_details, null)
        val table = view.findViewById<android.widget.TableLayout>(R.id.table)
        val stats = StatsStore[song.path]
        fun row(label: Int, value: String) {
            if (value.isBlank()) return
            val r = activity.layoutInflater.inflate(R.layout.item_detail_row, table, false)
            r.findViewById<android.widget.TextView>(R.id.label).setText(label)
            r.findViewById<android.widget.TextView>(R.id.value).text = value
            table.addView(r)
        }
        val headerRow = activity.layoutInflater.inflate(R.layout.item_detail_row, table, false)
        row(R.string.details_path, song.path)
        headerRow.findViewById<android.widget.TextView>(R.id.label).setText(R.string.details_header)
        val headerValue = headerRow.findViewById<android.widget.TextView>(R.id.value)
        headerValue.text = "${song.mimeType}, ${Format.duration(song.duration)}, ${song.bitrate / 1000} kBps, ${Format.size(song.size)}"
        table.addView(headerRow)
        row(R.string.details_title_label, song.title)
        row(R.string.details_album, song.album)
        row(R.string.details_artist, song.artist)
        row(R.string.details_album_artist, song.effectiveAlbumArtist)
        row(R.string.details_composer, song.composer)
        row(R.string.details_genre, song.genre)
        if (song.track > 0) row(R.string.details_track, song.track.toString())
        if (song.disc > 0) row(R.string.details_disc, song.disc.toString())
        if (song.year > 0) row(R.string.details_year, song.year.toString())
        row(R.string.details_added, "${Format.date(song.dateAdded * 1000)} (${Format.relative(song.dateAdded * 1000)})")
        row(R.string.details_last_played, if (stats.lastPlayed > 0) "${Format.date(stats.lastPlayed)} (${Format.relative(stats.lastPlayed)})" else activity.getString(R.string.details_never))
        row(R.string.details_rating, if (stats.rating > 0) Format.stars(stats.rating) else activity.getString(R.string.details_not_rated))
        row(R.string.details_play_count, stats.playCount.toString())
        row(R.string.details_skip_count, stats.skipCount.toString())
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.details_title)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.details_clear_stats) { _, _ -> StatsStore.clear(song.path) }
            .show()
        activity.lifecycleScope.launch {
            val header = withContext(Dispatchers.IO) { try { TagIO.read(activity, song).header } catch (_: Exception) { null } }
            if (header != null) headerValue.text = "$header, ${Format.size(song.size)}"
        }
    }

    fun albumInfo(activity: BaseActivity, album: Album) {
        val genres = album.songs.map { it.genre }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val folders = album.songs.map { it.folder }.distinct().joinToString("\n")
        val text = buildString {
            appendLine(album.title)
            appendLine(album.artist)
            if (album.year > 0) appendLine(activity.getString(R.string.released_in, album.year))
            appendLine(activity.resources.getQuantityString(R.plurals.n_songs, album.songCount, album.songCount) + " · " + Format.duration(album.duration))
            if (genres.isNotEmpty()) appendLine(genres)
            appendLine()
            append(folders)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.album_info_title)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    fun showLyrics(activity: BaseActivity, song: Song) {
        activity.lifecycleScope.launch {
            val lyrics = withContext(Dispatchers.IO) { TagIO.readLyrics(activity, song, Prefs.lyricsPreferEmbedded) }
            MaterialAlertDialogBuilder(activity)
                .setTitle(song.title)
                .setMessage(lyrics ?: activity.getString(R.string.no_lyrics))
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.edit) { _, _ -> LyricsEditor.edit(activity, song, lyrics.orEmpty()) }
                .show()
        }
    }

    fun toast(activity: BaseActivity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
