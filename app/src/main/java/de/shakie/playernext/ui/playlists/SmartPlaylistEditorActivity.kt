package de.shakie.playernext.ui.playlists

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import de.shakie.playernext.R
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.SmartPlaylist
import de.shakie.playernext.data.SmartPlaylist.Field
import de.shakie.playernext.data.SmartPlaylist.Kind
import de.shakie.playernext.data.SmartPlaylist.LimitUnit
import de.shakie.playernext.data.SmartPlaylist.Op
import de.shakie.playernext.data.SmartPlaylist.SelectedBy
import de.shakie.playernext.data.SmartPlaylist.TimeUnit
import de.shakie.playernext.data.SmartPlaylistStore
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.util.dp

/** Rule editor for user smart playlists (PlayerPro's smart playlist editor). */
class SmartPlaylistEditorActivity : BaseActivity() {
    companion object {
        fun start(context: Context, id: Long) =
            context.startActivity(Intent(context, SmartPlaylistEditorActivity::class.java).putExtra("id", id))

        private val FIELD_LABELS = mapOf(
            Field.TITLE to "Titel", Field.ALBUM to "Album", Field.ARTIST to "Interpret", Field.ALBUM_ARTIST to "Albuminterpret",
            Field.COMPOSER to "Komponist", Field.GENRE to "Genre", Field.DURATION to "Dauer", Field.YEAR to "Jahr",
            Field.DATE_ADDED to "Hinzugefügt", Field.DATE_MODIFIED to "Geändert", Field.RATING to "Bewertung (Sterne)",
            Field.PLAY_COUNT to "Wiedergabeanzahl", Field.SKIP_COUNT to "Überspringanzahl", Field.LAST_PLAYED to "Zuletzt gespielt",
            Field.PATH to "Dateipfad", Field.PODCAST to "Ist Podcast",
        )
        private val OP_LABELS = mapOf(
            Op.CONTAINS to "enthält", Op.NOT_CONTAINS to "enthält nicht", Op.IS to "ist", Op.IS_NOT to "ist nicht",
            Op.STARTS_WITH to "beginnt mit", Op.ENDS_WITH to "endet mit", Op.GREATER to "liegt über", Op.LESS to "liegt unter",
            Op.AFTER to "liegt nach dem", Op.BEFORE to "liegt vor dem", Op.IN_LAST to "liegt innerhalb der letzten",
            Op.NOT_IN_LAST to "liegt außerhalb der letzten", Op.TRUE to "ja", Op.FALSE to "nein",
        )
        private val UNIT_LABELS = mapOf(
            TimeUnit.SECONDS to "Sekunden", TimeUnit.MINUTES to "Minuten", TimeUnit.HOURS to "Stunden", TimeUnit.DAYS to "Tagen",
            TimeUnit.WEEKS to "Wochen", TimeUnit.MONTHS to "Monaten", TimeUnit.YEARS to "Jahren",
        )
        private val LIMIT_LABELS = mapOf(
            LimitUnit.ITEMS to "Titel", LimitUnit.MINUTES to "Minuten", LimitUnit.HOURS to "Stunden", LimitUnit.MB to "MB", LimitUnit.GB to "GB",
        )
        private val SELECTED_LABELS = mapOf(
            SelectedBy.RANDOM to "Zufall", SelectedBy.TITLE to "Titel", SelectedBy.ALBUM to "Album", SelectedBy.ARTIST to "Interpret",
            SelectedBy.HIGHEST_RATING to "Höchste Bewertung", SelectedBy.LOWEST_RATING to "Niedrigste Bewertung",
            SelectedBy.RECENTLY_PLAYED to "Zuletzt gespielt", SelectedBy.LEAST_RECENTLY_PLAYED to "Am längsten nicht gespielt",
            SelectedBy.MOST_PLAYED to "Meistgespielt", SelectedBy.LEAST_PLAYED to "Am seltensten gespielt",
            SelectedBy.RECENTLY_ADDED to "Zuletzt hinzugefügt", SelectedBy.LEAST_RECENTLY_ADDED to "Am längsten vorhanden",
        )
    }

    private var playlistId = 0L
    private lateinit var nameEdit: EditText
    private lateinit var matchSpinner: Spinner
    private lateinit var rulesBox: LinearLayout
    private lateinit var limitCheck: CheckBox
    private lateinit var limitValue: EditText
    private lateinit var limitUnit: Spinner
    private lateinit var selectedBy: Spinner
    private lateinit var preview: TextView
    private val ruleRows = ArrayList<RuleRow>()

    private inner class RuleRow(val view: View, val field: Spinner, val op: Spinner, val value: EditText, val unit: Spinner)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = intent.getLongExtra("id", 0)
        val existing = if (playlistId > 0) SmartPlaylistStore.byId(playlistId) else null
        buildUi()
        val sp = existing ?: SmartPlaylist(0, "", rules = listOf(SmartPlaylist.Rule(Field.ARTIST, Op.CONTAINS, "")))
        nameEdit.setText(sp.name)
        matchSpinner.setSelection(if (sp.matchAll) 0 else 1)
        sp.rules.forEach { addRule(it) }
        limitCheck.isChecked = sp.limitEnabled
        limitValue.setText(sp.limitValue.toString())
        limitUnit.setSelection(LimitUnit.entries.indexOf(sp.limitUnit))
        selectedBy.setSelection(SelectedBy.entries.indexOf(sp.selectedBy))
        updatePreview()
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@SmartPlaylistEditorActivity, android.R.layout.simple_spinner_dropdown_item, items)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updatePreview()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = updatePreview()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.pn_on_surface_dim))
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.pn_surface)) }
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.pn_surface_low))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(getColor(R.color.pn_on_surface))
            setTitleTextColor(getColor(R.color.pn_on_surface))
            setTitle(R.string.spl_title)
        }
        setSupportActionBar(toolbar)
        // After setSupportActionBar, which replaces the navigation click listener with its own.
        toolbar.setNavigationOnClickListener { finish() }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        form.addView(label(getString(R.string.spl_name)))
        nameEdit = EditText(this).apply { setSingleLine() }
        form.addView(nameEdit)
        form.addView(label(getString(R.string.spl_match)))
        matchSpinner = spinner(listOf(getString(R.string.spl_match_all), getString(R.string.spl_match_any)))
        form.addView(matchSpinner)
        rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(rulesBox)
        form.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.spl_add_rule)
            setOnClickListener { addRule(SmartPlaylist.Rule(Field.TITLE, Op.CONTAINS, "")); updatePreview() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        limitCheck = CheckBox(this).apply {
            setText(R.string.spl_limit)
            setOnCheckedChangeListener { _, _ -> updatePreview() }
        }
        form.addView(limitCheck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val limitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limitValue = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; addTextChangedListener(watcher) }
        limitRow.addView(limitValue, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        limitUnit = spinner(LimitUnit.entries.map { LIMIT_LABELS.getValue(it) })
        limitRow.addView(limitUnit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(limitRow)
        form.addView(label(getString(R.string.spl_selected_by)))
        selectedBy = spinner(SelectedBy.entries.map { SELECTED_LABELS.getValue(it) })
        form.addView(selectedBy)
        preview = TextView(this).apply {
            setTextColor(getColor(R.color.pn_primary))
            setPadding(0, dp(18), 0, 0)
        }
        form.addView(preview)
        root.addView(ScrollView(this).apply { addView(form) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun addRule(rule: SmartPlaylist.Rule) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setBackgroundColor(getColor(R.color.pn_surface_low))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fieldSpinner = spinner(Field.entries.map { FIELD_LABELS.getValue(it) })
        top.addView(fieldSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val remove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.pn_on_surface_dim))
            background = getDrawable(R.drawable.round_ripple)
        }
        top.addView(remove, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(top)
        val opSpinner = Spinner(this)
        row.addView(opSpinner)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val valueEdit = EditText(this).apply { setSingleLine(); addTextChangedListener(watcher) }
        bottom.addView(valueEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val unitSpinner = spinner(TimeUnit.entries.map { UNIT_LABELS.getValue(it) })
        bottom.addView(unitSpinner, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(bottom)
        val holder = RuleRow(row, fieldSpinner, opSpinner, valueEdit, unitSpinner)

        fun configureFor(field: Field, op: Op?) {
            val ops = Op.forKind(field.kind)
            opSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ops.map { OP_LABELS.getValue(it) })
            opSpinner.setSelection(ops.indexOf(op).coerceAtLeast(0))
            valueEdit.visibility = if (field.kind == Kind.BOOL) View.GONE else View.VISIBLE
            valueEdit.inputType = when (field.kind) {
                Kind.NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> InputType.TYPE_CLASS_TEXT
            }
            valueEdit.hint = if (field.kind == Kind.DATE) getString(R.string.spl_date_hint) else getString(R.string.spl_value)
        }
        fun updateUnit() {
            val field = Field.entries[fieldSpinner.selectedItemPosition.coerceAtLeast(0)]
            val ops = Op.forKind(field.kind)
            val op = ops.getOrNull(opSpinner.selectedItemPosition)
            unitSpinner.visibility = if (field == Field.DURATION || op == Op.IN_LAST || op == Op.NOT_IN_LAST) View.VISIBLE else View.GONE
            if (field.kind == Kind.DATE && (op == Op.IN_LAST || op == Op.NOT_IN_LAST)) {
                valueEdit.inputType = InputType.TYPE_CLASS_NUMBER
                valueEdit.hint = getString(R.string.spl_value)
            } else if (field.kind == Kind.DATE) {
                valueEdit.inputType = InputType.TYPE_CLASS_TEXT
                valueEdit.hint = getString(R.string.spl_date_hint)
            }
            updatePreview()
        }
        fieldSpinner.setSelection(Field.entries.indexOf(rule.field))
        configureFor(rule.field, rule.op)
        valueEdit.setText(rule.value)
        unitSpinner.setSelection(TimeUnit.entries.indexOf(rule.unit))
        fieldSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var first = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; updateUnit(); return }
                configureFor(Field.entries[pos], null)
                updateUnit()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        opSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateUnit()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        remove.setOnClickListener {
            rulesBox.removeView(row)
            ruleRows.remove(holder)
            updatePreview()
        }
        rulesBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        ruleRows += holder
    }

    private fun current(): SmartPlaylist {
        val rules = ruleRows.map { r ->
            val field = Field.entries[r.field.selectedItemPosition.coerceAtLeast(0)]
            val op = Op.forKind(field.kind).getOrElse(r.op.selectedItemPosition) { Op.forKind(field.kind).first() }
            SmartPlaylist.Rule(field, op, r.value.text.toString().trim(), TimeUnit.entries[r.unit.selectedItemPosition.coerceAtLeast(0)])
        }
        return SmartPlaylist(
            playlistId, nameEdit.text.toString().trim(), matchSpinner.selectedItemPosition == 0, rules, limitCheck.isChecked,
            limitValue.text.toString().toIntOrNull() ?: 25, LimitUnit.entries[limitUnit.selectedItemPosition.coerceAtLeast(0)],
            SelectedBy.entries[selectedBy.selectedItemPosition.coerceAtLeast(0)],
        )
    }

    private fun updatePreview() {
        if (!::preview.isInitialized || !::selectedBy.isInitialized) return
        val n = current().evaluate(Library.snapshot, StatsStore.all()).size
        preview.text = getString(R.string.spl_preview, n)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 0, R.string.save).apply {
            setIcon(R.drawable.ic_check)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        val sp = current()
        if (sp.name.isBlank()) {
            nameEdit.error = getString(R.string.playlist_name)
            return true
        }
        SmartPlaylistStore.save(sp)
        ItemActions.toast(this, getString(R.string.playlist_created, sp.name))
        finish()
        return true
    }
}
