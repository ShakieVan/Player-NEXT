package de.shakie.playernext.ui.equalizer

import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.R
import de.shakie.playernext.data.EqPresetStore
import de.shakie.playernext.playback.dsp.AudioEffects
import de.shakie.playernext.playback.dsp.EqPresets
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor
import java.util.Locale

/**
 * 10-band equalizer with preamp, reverb, bass and virtualizer knobs and the media volume.
 * Values go straight into [AudioEffects]; the DSP in the playback service picks them up.
 */
class EqualizerActivity : BaseActivity() {
    private val sliders = ArrayList<VerticalSlider>()
    private val dots = ArrayList<View>()
    private lateinit var presetButton: TextView
    private lateinit var preamp: SeekBar
    private lateinit var preampValue: TextView
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_equalizer)
        audioManager = getSystemService(AudioManager::class.java)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<ToggleButton>(R.id.eq_toggle).apply {
            isChecked = AudioEffects.eqEnabled
            setOnCheckedChangeListener { _, checked -> AudioEffects.setEnabled(checked); refreshDots() }
        }
        presetButton = findViewById(R.id.preset_button)
        presetButton.setOnClickListener { choosePreset() }
        preamp = findViewById(R.id.preamp)
        preampValue = findViewById(R.id.preamp_value)
        preamp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val db = (p - 24) / 2f
                preampValue.text = dbText(db)
                if (fromUser) AudioEffects.setPreamp(db)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        buildBands()

        findViewById<TextView>(R.id.reverb_button).apply {
            text = EqPresets.reverbNames[AudioEffects.reverbPreset.coerceIn(0, 6)]
            setOnClickListener { chooseReverb(this) }
        }
        findViewById<KnobView>(R.id.knob_bass).apply {
            value = AudioEffects.bassStrength
            onChange = { v, final -> if (final) AudioEffects.setBass(v) }
        }
        findViewById<KnobView>(R.id.knob_virtualizer).apply {
            value = AudioEffects.virtualizerStrength
            onChange = { v, final -> if (final) AudioEffects.setVirtualizer(v) }
        }
        findViewById<SeekBar>(R.id.volume).apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        refresh()
    }

    private fun dbText(db: Float) = String.format(Locale.GERMANY, "%+.1f dB", db)

    private fun buildBands() {
        val container = findViewById<LinearLayout>(R.id.bands)
        val accent = themeColor(R.attr.pnAccent)
        for (i in 0 until AudioEffects.BANDS) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(TextView(this).apply {
                text = AudioEffects.LABELS[i]
                setTextAppearance(R.style.EqLabel)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
            val slider = VerticalSlider(this)
            slider.onChange = { v, _ ->
                AudioEffects.setBand(i, v, getString(R.string.eq_manual))
                presetButton.text = AudioEffects.presetName
                refreshDots()
            }
            column.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            val dot = View(this).apply {
                background = getDrawable(R.drawable.position_pill)
                backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            column.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { topMargin = dp(6); bottomMargin = dp(4) })
            container.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            sliders += slider
            dots += dot
        }
    }

    private fun refresh() {
        sliders.forEachIndexed { i, s -> s.value = AudioEffects.band(i) }
        presetButton.text = AudioEffects.presetName
        preamp.progress = (AudioEffects.preampDb * 2 + 24).toInt()
        preampValue.text = dbText(AudioEffects.preampDb)
        refreshDots()
    }

    private fun refreshDots() {
        val on = AudioEffects.eqEnabled
        dots.forEachIndexed { i, d ->
            d.alpha = if (on && AudioEffects.band(i) != 0f) 1f else 0.15f
        }
        sliders.forEach { it.alpha = if (on) 1f else 0.5f }
    }

    private fun allPresets(): LinkedHashMap<String, Pair<FloatArray, Float>> {
        val map = LinkedHashMap<String, Pair<FloatArray, Float>>()
        EqPresets.builtin.forEach { (k, v) -> map[k] = v to 0f }
        EqPresetStore.userPresets().forEach { (k, v) -> map[k] = v }
        return map
    }

    private fun choosePreset() {
        val presets = allPresets()
        val names = presets.keys.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.eq_select_preset)
            .setItems(names) { _, which ->
                val (bands, pre) = presets.getValue(names[which])
                AudioEffects.applyPreset(names[which], bands, pre)
                if (!AudioEffects.eqEnabled) {
                    AudioEffects.setEnabled(true)
                    findViewById<ToggleButton>(R.id.eq_toggle).isChecked = true
                }
                refresh()
            }
            .show()
    }

    private fun chooseReverb(button: TextView) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.eq_select_reverb)
            .setItems(EqPresets.reverbNames.toTypedArray()) { _, which ->
                AudioEffects.setReverb(which)
                button.text = EqPresets.reverbNames[which]
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        fun add(id: Int, title: Int, icon: Int) = menu.add(Menu.NONE, id, id, title).apply {
            setIcon(icon)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        add(1, R.string.eq_rename_preset, R.drawable.ic_edit)
        add(2, R.string.eq_save_preset, R.drawable.ic_save)
        add(3, R.string.eq_reset, R.drawable.ic_reset)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> renamePreset()
            2 -> ItemActions.prompt(this, R.string.eq_save_preset, AudioEffects.presetName.takeIf { it !in EqPresets.builtin } ?: "") { name ->
                EqPresetStore.save(name, AudioEffects.bandsCopy(), AudioEffects.preampDb)
                AudioEffects.applyPreset(name, AudioEffects.bandsCopy(), AudioEffects.preampDb)
                refresh()
            }
            3 -> {
                AudioEffects.applyPreset("Normal", FloatArray(AudioEffects.BANDS), 0f)
                refresh()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun renamePreset() {
        val current = AudioEffects.presetName
        if (current !in EqPresetStore.userPresets()) {
            ItemActions.toast(this, getString(R.string.eq_builtin_readonly))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(current)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) {
                    ItemActions.prompt(this, R.string.eq_rename_preset, current) { name ->
                        EqPresetStore.rename(current, name)
                        AudioEffects.applyPreset(name, AudioEffects.bandsCopy())
                        refresh()
                    }
                } else {
                    EqPresetStore.delete(current)
                    AudioEffects.applyPreset(getString(R.string.eq_manual), AudioEffects.bandsCopy())
                    refresh()
                }
            }
            .show()
    }
}
