package de.shakie.playernext.ui.tags

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import de.shakie.playernext.R
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.Song
import de.shakie.playernext.tags.TagIO
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.util.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single and batch tag editing (PlayerPro's "ID3-Tag editieren"). */
class TagEditorActivity : BaseActivity() {
    companion object {
        private const val EXTRA_IDS = "ids"

        fun start(context: Context, songs: List<Song>) {
            if (songs.isEmpty()) return
            context.startActivity(Intent(context, TagEditorActivity::class.java).putExtra(EXTRA_IDS, songs.map { it.id }.toLongArray()))
        }
    }

    private lateinit var songs: List<Song>
    private val fields = LinkedHashMap<TagIO.Field, EditText>()
    private val original = HashMap<TagIO.Field, String?>()
    private val dirty = HashSet<TagIO.Field>()
    private var artworkChange: TagIO.ArtworkChange = TagIO.ArtworkChange.Keep
    private lateinit var artView: ImageView
    private lateinit var progress: ProgressBar
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: longArrayOf()
        songs = ids.toList().mapNotNull { Library.snapshot.songsById[it] }
        if (songs.isEmpty()) {
            finish()
            return
        }
        buildUi()
        load()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.pn_surface))
        }
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.pn_surface_low))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(getColor(R.color.pn_on_surface))
            setTitleTextColor(getColor(R.color.pn_on_surface))
            title = if (songs.size == 1) getString(R.string.tag_editor_title) else getString(R.string.tag_editor_batch, songs.size)
            subtitle = if (songs.size == 1) songs[0].path.substringAfterLast('/') else null
            setSubtitleTextColor(getColor(R.color.pn_on_surface_dim))
            setNavigationOnClickListener { finish() }
        }
        setSupportActionBar(toolbar)
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.placeholder_album)
            contentDescription = getString(R.string.tag_artwork_change)
            setOnClickListener { chooseArtwork() }
        }
        form.addView(artView, LinearLayout.LayoutParams(dp(160), dp(160)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        if (songs.size > 1) {
            form.addView(TextView(this).apply {
                setText(R.string.tag_batch_hint)
                setTextColor(getColor(R.color.pn_on_surface_variant))
                setPadding(0, dp(12), 0, dp(4))
            })
        }
        fun field(f: TagIO.Field, label: Int, numeric: Boolean = false, multiline: Boolean = false, parent: LinearLayout = form, weight: Float = 0f) {
            val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = getString(label)
                boxStrokeColor = getColor(R.color.pn_primary)
            }
            val edit = TextInputEditText(til.context).apply {
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
                if (multiline) {
                    minLines = 4
                    maxLines = 12
                    gravity = android.view.Gravity.TOP
                }
            }
            til.addView(edit)
            val lp = if (weight > 0) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            if (weight > 0) lp.marginEnd = dp(8)
            parent.addView(til, lp)
            fields[f] = edit
        }
        fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also {
            form.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        field(TagIO.Field.TITLE, R.string.tag_title)
        field(TagIO.Field.ARTIST, R.string.tag_artist)
        field(TagIO.Field.ALBUM, R.string.tag_album)
        field(TagIO.Field.ALBUM_ARTIST, R.string.tag_album_artist)
        field(TagIO.Field.COMPOSER, R.string.tag_composer)
        field(TagIO.Field.GENRE, R.string.tag_genre)
        row().let { r ->
            field(TagIO.Field.YEAR, R.string.tag_year, numeric = true, parent = r, weight = 1f)
            field(TagIO.Field.BPM, R.string.tag_bpm, numeric = true, parent = r, weight = 1f)
        }
        row().let { r ->
            field(TagIO.Field.TRACK, R.string.tag_track, numeric = true, parent = r, weight = 1f)
            field(TagIO.Field.TRACK_TOTAL, R.string.tag_track_total, numeric = true, parent = r, weight = 1f)
            field(TagIO.Field.DISC, R.string.tag_disc, numeric = true, parent = r, weight = 1f)
            field(TagIO.Field.DISC_TOTAL, R.string.tag_disc_total, numeric = true, parent = r, weight = 1f)
        }
        field(TagIO.Field.GROUPING, R.string.tag_grouping)
        field(TagIO.Field.COMMENT, R.string.tag_comment, multiline = true)
        field(TagIO.Field.LYRICS, R.string.tag_lyrics, multiline = true)
        fields.values.forEach { it.isEnabled = false }

        val scroll = ScrollView(this).apply { addView(form) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun load() {
        lifecycleScope.launch {
            val tags = withContext(Dispatchers.IO) {
                songs.take(200).mapNotNull { s -> try { TagIO.read(this@TagEditorActivity, s) } catch (_: Exception) { null } }
            }
            progress.visibility = View.GONE
            for ((f, edit) in fields) {
                val values = tags.map { it.values[f] ?: "" }.distinct()
                if (values.size == 1) {
                    edit.setText(values[0])
                    original[f] = values[0]
                } else if (values.size > 1) {
                    edit.hint = getString(R.string.tag_multiple)
                    original[f] = null
                }
                edit.isEnabled = true
                edit.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        dirty += f
                    }
                })
            }
            if (songs.size > 1) (fields[TagIO.Field.TITLE]?.parent?.parent as? View)?.visibility = View.GONE
            val art = tags.firstOrNull()?.artwork
            if (art != null && tags.all { it.artwork?.contentEquals(art) == true }) {
                BitmapFactory.decodeByteArray(art, 0, art.size)?.let { artView.setImageBitmap(it) }
            }
        }
    }

    private fun chooseArtwork() {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(getString(R.string.tag_artwork_change), getString(R.string.tag_artwork_remove))) { _, which ->
                if (which == 1) {
                    artworkChange = TagIO.ArtworkChange.Remove
                    artView.setImageResource(R.drawable.placeholder_album)
                } else {
                    pickImage { uri ->
                        uri ?: return@pickImage
                        lifecycleScope.launch {
                            val data = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
                            val mime = contentResolver.getType(uri) ?: "image/jpeg"
                            artworkChange = TagIO.ArtworkChange.Replace(data, mime)
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.let { artView.setImageBitmap(it) }
                        }
                    }
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 0, R.string.save).apply {
            setIcon(R.drawable.ic_check)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            save()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save() {
        if (saving) return
        val changes = HashMap<TagIO.Field, String?>()
        for (f in dirty) {
            val v = fields[f]?.text?.toString()?.trim()
            if (v != original[f]) changes[f] = v
        }
        if (changes.isEmpty() && artworkChange == TagIO.ArtworkChange.Keep) {
            finish()
            return
        }
        saving = true
        lifecycleScope.launch {
            if (!TagIO.requestWriteAccess(this@TagEditorActivity, songs)) {
                saving = false
                ItemActions.toast(this@TagEditorActivity, getString(R.string.tag_permission_denied))
                return@launch
            }
            progress.visibility = View.VISIBLE
            ItemActions.toast(this@TagEditorActivity, getString(R.string.tag_saving))
            val error = withContext(Dispatchers.IO) {
                try {
                    TagIO.write(this@TagEditorActivity, songs, changes, artworkChange)
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            progress.visibility = View.GONE
            saving = false
            if (error == null) {
                ItemActions.toast(this@TagEditorActivity, getString(R.string.tag_saved))
                de.shakie.playernext.art.ArtLoader.invalidate()
                window.decorView.postDelayed({ Library.refresh() }, 1500)
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                ItemActions.toast(this@TagEditorActivity, getString(R.string.tag_failed, error))
            }
        }
    }
}
