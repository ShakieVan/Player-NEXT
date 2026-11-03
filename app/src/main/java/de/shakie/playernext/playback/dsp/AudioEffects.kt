package de.shakie.playernext.playback.dsp

import android.content.Context
import android.content.SharedPreferences
import de.shakie.playernext.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process wide audio effect parameters. The DSP reads them on the audio thread, the
 * equalizer screen and settings write them on the main thread.
 */
object AudioEffects {
    const val BANDS = 10
    const val MAX_DB = 12f
    val FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = arrayOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

    private var sp: SharedPreferences? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var version = 0
        private set
    @Volatile var eqEnabled = false
        private set
    private val bands = FloatArray(BANDS)
    @Volatile var preampDb = 0f
        private set
    @Volatile var presetName = "Normal"
        private set
    /** 0..1000 like the platform effects. */
    @Volatile var bassStrength = 0
        private set
    @Volatile var virtualizerStrength = 0
        private set
    @Volatile var reverbPreset = 0
        private set

    // Mirrors of audio settings so the audio thread never touches SharedPreferences.
    @Volatile var balance = 0f
        private set
    @Volatile var mono = false
        private set
    @Volatile var limiter = true
        private set

    /** Set per track by the service from ReplayGain metadata. */
    @Volatile var replayGainDb = 0f
        private set
    @Volatile var replayGainPeak = 1f
        private set

    fun init(context: Context) {
        val sp = context.getSharedPreferences("effects", Context.MODE_PRIVATE).also { this.sp = it }
        eqEnabled = sp.getBoolean("eq_enabled", false)
        sp.getString("eq_bands", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.forEachIndexed { i, v ->
            if (i < BANDS) bands[i] = v
        }
        preampDb = sp.getFloat("eq_preamp", 0f)
        presetName = sp.getString("eq_preset", "Normal") ?: "Normal"
        bassStrength = sp.getInt("bass", 0)
        virtualizerStrength = sp.getInt("virtualizer", 0)
        reverbPreset = sp.getInt("reverb", 0)
        reloadAudioPrefs()
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun band(i: Int) = bands[i]
    fun bandsCopy() = bands.copyOf()

    fun setEnabled(enabled: Boolean) {
        eqEnabled = enabled
        commit()
    }

    fun setBand(i: Int, db: Float, preset: String = "Manual") {
        bands[i] = db.coerceIn(-MAX_DB, MAX_DB)
        presetName = preset
        commit()
    }

    fun setPreamp(db: Float) {
        preampDb = db.coerceIn(-MAX_DB, MAX_DB)
        commit()
    }

    fun applyPreset(name: String, values: FloatArray, preamp: Float? = null) {
        values.forEachIndexed { i, v -> if (i < BANDS) bands[i] = v.coerceIn(-MAX_DB, MAX_DB) }
        if (preamp != null) preampDb = preamp
        presetName = name
        commit()
    }

    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        commit()
    }

    fun setVirtualizer(strength: Int) {
        virtualizerStrength = strength.coerceIn(0, 1000)
        commit()
    }

    fun setReverb(preset: Int) {
        reverbPreset = preset
        commit()
    }

    fun setReplayGain(db: Float, peak: Float) {
        if (db == replayGainDb && peak == replayGainPeak) return
        replayGainDb = db
        replayGainPeak = peak
        version++
    }

    fun reloadAudioPrefs() {
        balance = Prefs.balance
        mono = Prefs.monoOutput
        limiter = Prefs.useLimiter
        version++
        listeners.forEach { it() }
    }

    /** True when the software DSP changes the signal; offload is only possible otherwise. */
    fun dspNeeded(): Boolean =
        (eqEnabled && (preampDb != 0f || bands.any { it != 0f })) || bassStrength > 0 || virtualizerStrength > 0 ||
            balance != 0f || mono || (Prefs.replayGain && (replayGainDb != 0f || Prefs.replayGainDefault != 0f || Prefs.replayGainPreamp != 0f))

    private fun commit() {
        version++
        sp?.edit()
            ?.putBoolean("eq_enabled", eqEnabled)
            ?.putString("eq_bands", bands.joinToString(","))
            ?.putFloat("eq_preamp", preampDb)
            ?.putString("eq_preset", presetName)
            ?.putInt("bass", bassStrength)
            ?.putInt("virtualizer", virtualizerStrength)
            ?.putInt("reverb", reverbPreset)
            ?.apply()
        listeners.forEach { it() }
    }

    fun exportState(): Map<String, Any> = mapOf(
        "eq_enabled" to eqEnabled, "eq_bands" to bands.joinToString(","), "eq_preamp" to preampDb,
        "eq_preset" to presetName, "bass" to bassStrength, "virtualizer" to virtualizerStrength, "reverb" to reverbPreset,
    )
}
