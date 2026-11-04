package de.shakie.playernext.ui.settings

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.R
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor

/**
 * Folder browser for "Musikordner": go into folders, tick the ones to use. A ticked folder
 * includes everything below it; nothing ticked means all folders.
 */
class FolderPicker(
    private val context: Context,
    private val tree: FolderTree,
    selected: Set<String>,
    private val onDone: (Set<String>) -> Unit,
) {
    private val selection = FolderTree.Selection(selected)
    /** Null = the list of storage volumes (only shown with more than one). */
    private var current: FolderTree.Node? = tree.roots.singleOrNull()
    private val pathText = TextView(context).apply {
        setTextColor(context.getColor(R.color.pn_on_surface_dim))
        textSize = 13f
        setPadding(context.dp(24), context.dp(4), context.dp(24), context.dp(4))
    }
    private val summaryText = TextView(context).apply {
        setTextColor(context.themeColor(R.attr.pnAccent))
        textSize = 13f
        setPadding(context.dp(24), 0, context.dp(24), context.dp(8))
    }
    private val adapter = Adapter()
    private lateinit var dialog: AlertDialog

    fun show() {
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@FolderPicker.adapter
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathText)
            addView(summaryText)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(360)))
        }
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.music_folders_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> onDone(selection.paths.toSet()) }
            .setNeutralButton(R.string.music_folders_all) { _, _ -> onDone(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Back goes up one folder before it closes the dialog.
        dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && canGoUp()) {
                goUp()
                true
            } else {
                code == KeyEvent.KEYCODE_BACK && canGoUp()
            }
        }
        refresh()
        dialog.show()
    }

    private fun canGoUp() = current != null && (current?.parent != null || tree.roots.size > 1)

    private fun goUp() {
        current = current?.parent
        refresh()
    }

    private fun open(node: FolderTree.Node) {
        current = node
        refresh()
    }

    private fun refresh() {
        val chain = generateSequence(current) { it.parent }.toList().reversed()
        pathText.text = if (chain.isEmpty()) context.getString(R.string.folder_picker_volumes) else chain.joinToString(" › ") { it.name }
        summaryText.text = if (selection.paths.isEmpty()) {
            context.getString(R.string.music_folders_all)
        } else {
            context.getString(R.string.folder_picker_selected, selection.paths.sorted().joinToString(", ") { it.substringAfterLast('/') })
        }
        adapter.notifyDataSetChanged()
    }

    private val entries: List<FolderTree.Node> get() = current?.children ?: tree.roots

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private val upRow get() = if (canGoUp()) 1 else 0

        override fun getItemCount() = entries.size + upRow

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(context))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position < upRow) holder.bindUp() else holder.bind(entries[position - upRow])
        }

        inner class Holder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
            private val check = CheckBox(context)
            private val icon = ImageView(context)
            private val name = TextView(context).apply {
                setTextColor(context.getColor(R.color.pn_on_surface))
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            private val detail = TextView(context).apply {
                setTextColor(context.getColor(R.color.pn_on_surface_dim))
                textSize = 12f
            }
            private val chevron = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(context.getColor(R.color.pn_on_surface_dim))
            }

            init {
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.minimumHeight = context.dp(56)
                row.setPadding(context.dp(12), 0, context.dp(12), 0)
                row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                row.setBackgroundResource(R.drawable.list_item_background)
                row.addView(check)
                row.addView(icon, LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(12) })
                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name)
                    addView(detail)
                }
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(chevron, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            }

            fun bindUp() {
                check.visibility = View.INVISIBLE
                icon.setImageResource(R.drawable.ic_folder_up)
                icon.setColorFilter(context.getColor(R.color.pn_on_surface_dim))
                name.setText(R.string.folder_picker_up)
                detail.visibility = View.GONE
                chevron.visibility = View.GONE
                check.setOnClickListener(null)
                row.setOnClickListener { goUp() }
            }

            fun bind(node: FolderTree.Node) {
                check.visibility = View.VISIBLE
                check.setOnCheckedChangeListener(null)
                check.isChecked = selection.covered(node.path)
                // Ticked through a parent folder: shown, but only the parent can be unticked.
                check.isEnabled = !selection.coveredByParent(node.path)
                check.setOnClickListener {
                    selection.toggle(node.path)
                    refresh()
                }
                icon.setImageResource(R.drawable.ic_folder)
                icon.setColorFilter(context.themeColor(R.attr.pnAccent))
                name.text = node.name
                detail.visibility = View.VISIBLE
                val songs = context.resources.getQuantityString(R.plurals.n_songs, node.songs, node.songs)
                detail.text = if (selection.partly(node.path)) context.getString(R.string.folder_picker_partly, songs) else songs
                chevron.visibility = if (node.children.isNotEmpty()) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    if (node.children.isNotEmpty()) open(node) else if (check.isEnabled) {
                        selection.toggle(node.path)
                        refresh()
                    }
                }
            }
        }
    }
}
