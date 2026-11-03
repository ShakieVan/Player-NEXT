package de.shakie.playernext.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtKey
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.BrowserType
import de.shakie.playernext.data.Favorite
import de.shakie.playernext.data.ItemType
import de.shakie.playernext.ui.browse.BrowseFragment
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor

sealed class DrawerItem {
    class Header(val title: String) : DrawerItem()
    class Fav(val favorite: Favorite, val count: Int, val art: ArtKey?) : DrawerItem()
    class Browser(val type: BrowserType, val count: Int, val enabled: Boolean) : DrawerItem()
    class Hint(val text: String) : DrawerItem()
}

class DrawerAdapter(
    private val onClick: (DrawerItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<DrawerItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var editMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var activeBrowser: BrowserType? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is DrawerItem.Header, is DrawerItem.Hint -> 0
        else -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val v = inf.inflate(if (viewType == 0) R.layout.item_drawer_header else R.layout.item_drawer, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val v = holder.itemView
        val ctx = v.context
        when (val item = items[position]) {
            is DrawerItem.Header -> v.findViewById<TextView>(R.id.title).apply {
                text = item.title
                setTextColor(ctx.themeColor(R.attr.pnAccent))
                textSize = 13f
            }
            is DrawerItem.Hint -> v.findViewById<TextView>(R.id.title).apply {
                text = item.text
                setTextColor(ctx.getColor(R.color.pn_on_surface_faint))
                textSize = 13f
            }
            is DrawerItem.Fav -> {
                val icon = v.findViewById<ImageView>(R.id.icon)
                icon.imageTintList = null
                val placeholder = when (item.favorite.type) {
                    ItemType.ARTIST, ItemType.COMPOSER -> R.drawable.placeholder_artist
                    ItemType.FOLDER -> R.drawable.placeholder_folder
                    ItemType.PLAYLIST -> R.drawable.placeholder_playlist
                    ItemType.SMART_PLAYLIST -> R.drawable.placeholder_smart
                    ItemType.VIDEO -> R.drawable.placeholder_video
                    else -> R.drawable.placeholder_album
                }
                ArtLoader.load(icon, item.art, ctx.dp(40), placeholder)
                v.findViewById<TextView>(R.id.title).text = item.favorite.title
                v.findViewById<TextView>(R.id.count).text = item.count.toString()
                v.findViewById<ImageView>(R.id.edit_toggle).apply {
                    visibility = if (editMode) View.VISIBLE else View.GONE
                    setImageResource(R.drawable.ic_close)
                    imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.pn_on_surface_dim))
                }
                v.background = ctx.getDrawable(R.drawable.list_item_background)
                v.alpha = 1f
                v.setOnClickListener { onClick(item) }
            }
            is DrawerItem.Browser -> {
                val icon = v.findViewById<ImageView>(R.id.icon)
                icon.setImageResource(BrowseFragment.iconFor(item.type))
                icon.imageTintList = android.content.res.ColorStateList.valueOf(ctx.themeColor(R.attr.pnAccent))
                v.findViewById<TextView>(R.id.title).setText(BrowseFragment.titleFor(item.type))
                v.findViewById<TextView>(R.id.count).text = item.count.toString()
                v.findViewById<ImageView>(R.id.edit_toggle).apply {
                    visibility = if (editMode) View.VISIBLE else View.GONE
                    setImageResource(if (item.enabled) R.drawable.ic_check else R.drawable.ic_close)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        if (item.enabled) ctx.themeColor(R.attr.pnAccent) else ctx.getColor(R.color.pn_on_surface_faint)
                    )
                }
                v.alpha = if (!editMode || item.enabled) 1f else 0.45f
                v.background = if (!editMode && item.type == activeBrowser) ctx.getDrawable(R.drawable.drawer_item_active)
                else ctx.getDrawable(R.drawable.list_item_background)
                v.setOnClickListener { onClick(item) }
            }
        }
    }
}
