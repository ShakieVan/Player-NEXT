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
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.data.EqPresetStore
import de.shakie.playernext.playback.PlayerHub
import de.shakie.playernext.playback.dsp.AudioEffects
import de.shakie.playernext.playback.dsp.LevelMeter
import de.shakie.playernext.playback.dsp.EqPresets
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.util.dp
import de.shakie.playernext.util.themeColor
import java.util.Locale

/**
 * 10-band equalizer with preamp, reverb, bass and virtualizer knobs, the loudness maximizer
 * with its meters and the media volume. Values go straight into [AudioEffects] (maximizer:
 * [Prefs], mirrored by the service); the DSP in the playback service picks them up. The meters
 * run only while this screen is in the foreground.
 */
class EqualizerActivity : BaseActivity() {
    private val sliders = ArrayList<VerticalSlider>()
    private val dots = ArrayList<View>()
    private lateinit var presetButton: TextView
    private lateinit var preamp: SeekBar
    private lateinit var preampValue: TextView
    private lateinit var audioManager: AudioManager
    private lateinit var meter: LevelMeterView
    private lateinit var loudThreshold: SeekBar
    private lateinit var loudOutput: SeekBar
    private lateinit var loudThresholdValue: TextView
    private lateinit var loudOutputValue: TextView
    private lateinit var loudSummary: TextView
    private val playerListener: () -> Unit = { meter.wake() }

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
        setupLoudness()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        LevelMeter.enabled = true
        PlayerHub.addListener(playerListener)
        meter.start()
    }

    override fun onPause() {
        meter.stop()
        PlayerHub.removeListener(playerListener)
        LevelMeter.enabled = false
        super.onPause()
    }

    private fun dbText(db: Float) = String.format(Locale.GERMANY, "%+.1f dB", db)

    private fun wholeDb(db: Int) = if (db < 0) "−${-db} dB" else if (db > 0) "+$db dB" else "0 dB"

    private fun setupLoudness() {
        meter = findViewById(R.id.level_meter)
        loudThreshold = findViewById(R.id.loud_threshold)
        loudOutput = findViewById(R.id.loud_output)
        loudThresholdValue = findViewById(R.id.loud_threshold_value)
        loudOutputValue = findViewById(R.id.loud_output_value)
        loudSummary = findViewById(R.id.loud_summary)
        findViewById<ToggleButton>(R.id.loud_toggle).apply {
            isChecked = Prefs.loudness
            setOnCheckedChangeListener { _, checked ->
                Prefs.loudness = checked
                refreshLoudness()
            }
        }
        // Threshold −30 … 0 dB, output ceiling −12 … 0 dB, whole dB like the settings.
        loudThreshold.progress = Prefs.loudnessThresholdDb.toInt() + 30
        loudOutput.progress = Prefs.loudnessOutputDb.toInt() + 12
        loudThreshold.setOnSeekBarChangeListener(loudListener { Prefs.loudnessThresholdDb = (it - 30).toFloat() })
        loudOutput.setOnSeekBarChangeListener(loudListener { Prefs.loudnessOutputDb = (it - 12).toFloat() })
        refreshLoudness()
    }

    private fun loudListener(write: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) write(p)
            refreshLoudness()
        }
        override fun onStartTrackingTouch(s: SeekBar?) = Unit
        override fun onStopTrackingTouch(s: SeekBar?) = Unit
    }

    private fun refreshLoudness() {
        val on = Prefs.loudness
        val threshold = loudThreshold.progress - 30
        val output = loudOutput.progress - 12
        loudThresholdValue.text = wholeDb(threshold)
        loudOutputValue.text = wholeDb(output)
        loudThreshold.alpha = if (on) 1f else 0.5f
        loudOutput.alpha = if (on) 1f else 0.5f
        loudSummary.text = if (on) {
            getString(R.string.eq_loud_summary_on, wholeDb(output - threshold), wholeDb(threshold), wholeDb(output))
        } else {
            getString(R.string.eq_loud_summary_off)
        }
        meter.setLoudness(on, threshold.toFloat(), output.toFloat())
    }

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
