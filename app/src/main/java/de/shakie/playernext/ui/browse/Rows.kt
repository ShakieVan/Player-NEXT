package de.shakie.playernext.ui.browse

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.Folder
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.data.Song
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.data.Video
import de.shakie.playernext.ui.common.RatingView
import de.shakie.playernext.util.Format
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor

/** Everything a browser list can show. [key] identifies a row for diffing and selection. */
sealed class Row(val key: String) {
    class Header(
        val title: String, val subtitle: String, val detail: String, val count: Int,
        val art: ArtKey?, val placeholder: Int,
    ) : Row("header")

    class Section(val title: String) : Row("section:$title")

    class Tile(
        val type: ItemType, val itemKey: String, val title: String, val subtitle: String?, val detail: String?,
        val art: ArtKey?, val placeholder: Int, val songIds: Set<Long> = emptySet(),
    ) : Row("${type.key}:$itemKey")

    class SongRow(val song: Song, val subtitle: String, val position: Int, val showArt: Boolean = false) :
        Row("song:${song.id}:$position")

    class FolderRow(val folder: Folder) : Row("folder:${folder.path}")

    class VideoRow(val video: Video) : Row("video:${video.id}")
}

interface RowCallbacks {
    fun onRowClick(row: Row, position: Int)
    fun onRowLongClick(row: Row, position: Int): Boolean
    fun onRowMenu(row: Row, anchor: View)
    fun onHeaderPlayAll() {}
    fun onHeaderShuffle() {}
    fun onHeaderQueueAll() {}
    fun onStartDrag(holder: RecyclerView.ViewHolder) {}
}

class RowAdapter(private val callbacks: RowCallbacks) : ListAdapter<Row, RowAdapter.Holder>(Diff) {
    var gridTiles = true
    var draggable = false
    var selection: Set<String> = emptySet()
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
        }
    /** Ratings changed: redraw the stars without rebuilding the rows. */
    fun refreshState() = notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)

    var playingSongId: Long = -1
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
        }

    companion object {
        private const val PAYLOAD_STATE = "state"
        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_TILE_GRID = 2
        const val TYPE_TILE_LIST = 3
        const val TYPE_SONG = 4
        const val TYPE_FOLDER = 5
        const val TYPE_VIDEO = 6
    }

    object Diff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.key == newItem.key
        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: Row, newItem: Row) = when {
            oldItem is Row.SongRow && newItem is Row.SongRow -> oldItem.song == newItem.song && oldItem.subtitle == newItem.subtitle
            oldItem is Row.Tile && newItem is Row.Tile -> oldItem.title == newItem.title && oldItem.subtitle == newItem.subtitle &&
                oldItem.detail == newItem.detail
            else -> oldItem === newItem
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Section -> TYPE_SECTION
        is Row.Tile -> if (gridTiles) TYPE_TILE_GRID else TYPE_TILE_LIST
        is Row.SongRow -> TYPE_SONG
        is Row.FolderRow -> TYPE_FOLDER
        is Row.VideoRow -> TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inf = LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            TYPE_HEADER -> R.layout.item_header
            TYPE_SECTION -> R.layout.item_section
            TYPE_TILE_GRID -> R.layout.item_tile_grid
            TYPE_FOLDER -> R.layout.item_folder
            TYPE_SONG -> R.layout.item_song
            else -> R.layout.item_tile_list
        }
        return Holder(inf.inflate(layout, parent, false), viewType)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STATE)) holder.bindState(getItem(position)) else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View, private val viewType: Int) : RecyclerView.ViewHolder(view) {
        private val title: TextView? = view.findViewById(R.id.title)
        private val subtitle: TextView? = view.findViewById(R.id.subtitle)
        private val detail: TextView? = view.findViewById(R.id.detail)
        private val duration: TextView? = view.findViewById(R.id.duration)
        private val art: ImageView? = view.findViewById(R.id.art)
        private val playing: ImageView? = view.findViewById(R.id.playing)
        private val menu: ImageButton? = view.findViewById(R.id.menu)
        private val check: ImageView? = view.findViewById(R.id.check)
        private val drag: ImageView? = view.findViewById(R.id.drag)
        private val rating: RatingView? = view.findViewById(R.id.rating)
        private val accent = view.context.themeColor(R.attr.pnAccent)
        private val normal = view.context.getColor(R.color.pn_on_surface)

        init {
            if (viewType != TYPE_HEADER && viewType != TYPE_SECTION) {
                view.setOnClickListener { pos()?.let { callbacks.onRowClick(getItem(it), it) } }
                view.setOnLongClickListener { pos()?.let { callbacks.onRowLongClick(getItem(it), it) } ?: false }
                menu?.setOnClickListener { v -> pos()?.let { callbacks.onRowMenu(getItem(it), v) } }
            }
            if (viewType == TYPE_HEADER) {
                view.findViewById<View>(R.id.play_all).setOnClickListener { callbacks.onHeaderPlayAll() }
                view.findViewById<View>(R.id.shuffle).setOnClickListener { callbacks.onHeaderShuffle() }
                view.findViewById<View>(R.id.queue_all).setOnClickListener { callbacks.onHeaderQueueAll() }
            }
            @SuppressLint("ClickableViewAccessibility")
            drag?.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) callbacks.onStartDrag(this)
                false
            }
        }

        private fun pos(): Int? = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }

        fun bind(row: Row) {
            val ctx = itemView.context
            when (row) {
                is Row.Header -> {
                    title?.text = row.title
                    subtitle?.text = row.subtitle
                    detail?.text = row.detail
                    itemView.findViewById<TextView>(R.id.count).text = row.count.toString()
                    ArtLoader.load(art!!, row.art, ctx.dp(96), row.placeholder)
                    ArtLoader.load(itemView.findViewById(R.id.background), row.art, ctx.dp(96), row.placeholder, blur = true)
                }
                is Row.Section -> title?.text = row.title
                is Row.Tile -> {
                    title?.text = row.title
                    subtitle?.text = row.subtitle
                    subtitle?.visibility = if (row.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
                    detail?.text = row.detail
                    val size = if (viewType == TYPE_TILE_GRID) ctx.dp(200) else ctx.dp(56)
                    ArtLoader.load(art!!, row.art, size, row.placeholder)
                }
                is Row.SongRow -> {
                    title?.text = row.song.title
                    subtitle?.text = row.subtitle
                    duration?.text = Format.duration(row.song.duration)
                    drag?.visibility = if (draggable) View.VISIBLE else View.GONE
                    art?.visibility = if (row.showArt) View.VISIBLE else View.GONE
                    if (row.showArt) ArtLoader.load(art!!, ArtKey.Album(row.song.albumId), ctx.dp(44))
                }
                is Row.FolderRow -> {
                    title?.text = row.folder.name.ifEmpty { "/" }
                    val songs = row.folder.allSongs
                    val subs = row.folder.subfolders.size
                    subtitle?.text = buildString {
                        if (subs > 0) append(ctx.resources.getQuantityString(R.plurals.n_folders, subs, subs)).append(", ")
                        append(ctx.resources.getQuantityString(R.plurals.n_songs, songs.size, songs.size))
                    }
                    detail?.text = Format.duration(row.folder.totalDuration)
                    ArtLoader.load(art!!, songs.firstOrNull()?.let { ArtKey.Album(it.albumId) }, ctx.dp(26), R.drawable.placeholder_album)
                }
                is Row.VideoRow -> {
                    title?.text = row.video.title
                    subtitle?.text = "${row.video.width}×${row.video.height} · ${Format.size(row.video.size)}"
                    detail?.text = Format.duration(row.video.duration)
                    ArtLoader.load(art!!, ArtKey.Video(row.video.id), ctx.dp(56), R.drawable.placeholder_video)
                }
            }
            bindState(row)
        }

        fun bindState(row: Row) {
            val selected = row.key in selection
            itemView.isActivated = selected
            check?.visibility = if (selected) View.VISIBLE else View.GONE
            val isPlaying = when (row) {
                is Row.SongRow -> row.song.id == playingSongId
                is Row.Tile -> playingSongId in row.songIds
                is Row.FolderRow -> row.folder.allSongs.any { it.id == playingSongId }
                else -> false
            }
            playing?.visibility = if (isPlaying) View.VISIBLE else View.GONE
            if (row is Row.SongRow && rating != null) {
                val r = if (Prefs.ratingsInLists) StatsStore[row.song.path].rating else 0
                rating.rating = r
                rating.visibility = if (r > 0) View.VISIBLE else View.GONE
            }
            if (row is Row.SongRow || row is Row.FolderRow || (row is Row.Tile && viewType == TYPE_TILE_LIST)) {
                title?.setTextColor(if (isPlaying) accent else normal)
            }
        }
    }
}
