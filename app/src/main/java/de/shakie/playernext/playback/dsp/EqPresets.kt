package de.shakie.playernext.playback.dsp

/** Built-in 10-band presets (31 Hz … 16 kHz, dB). */
object EqPresets {
    val builtin: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Normal" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Akustik" to floatArrayOf(4.5f, 4.5f, 3.5f, 1f, 1.5f, 1.5f, 3f, 3.5f, 3f, 1.5f),
        "Bass Boost" to floatArrayOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Bass reduzieren" to floatArrayOf(-6f, -5f, -4f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f),
        "Bass & Höhen" to floatArrayOf(5f, 4f, 2.5f, 0f, -1f, -1f, 0f, 2.5f, 4f, 5f),
        "Klassik" to floatArrayOf(4.5f, 3.5f, 3f, 2.5f, -1.5f, -1.5f, 0f, 2f, 3f, 3.5f),
        "Dance" to floatArrayOf(3.5f, 6.5f, 5f, 0f, 2f, 3.5f, 5f, 4.5f, 3.5f, 0f),
        "Deep" to floatArrayOf(5f, 3.5f, 1.5f, 1f, 3f, 2.5f, 1.5f, -2f, -3.5f, -4.5f),
        "Elektronisch" to floatArrayOf(4f, 3.5f, 1f, 0f, -2f, 2f, 1f, 1.5f, 4f, 4.5f),
        "Flach" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Folk" to floatArrayOf(3f, 2.5f, 1.5f, 0f, -0.5f, 1f, 2f, 2.5f, 2f, 1f),
        "Heavy Metal" to floatArrayOf(4.5f, 4f, 3f, 1f, -1.5f, 1f, 3f, 4f, 4.5f, 4f),
        "Hip-Hop" to floatArrayOf(5f, 4f, 1.5f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f),
        "Jazz" to floatArrayOf(4f, 3f, 1.5f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f),
        "Latin" to floatArrayOf(4.5f, 3f, 0f, 0f, -1.5f, -1.5f, -1.5f, 0f, 3f, 4.5f),
        "Lautheit" to floatArrayOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -5f, 5f, 1f),
        "Lounge" to floatArrayOf(-3f, -1.5f, -0.5f, 1.5f, 4f, 2.5f, 0f, -1.5f, 2f, 1f),
        "Piano" to floatArrayOf(3f, 2f, 0f, 2.5f, 3f, 1.5f, 3.5f, 4.5f, 3f, 3.5f),
        "Pop" to floatArrayOf(-1.5f, -1f, 0f, 2f, 4f, 4f, 2f, 0f, -1f, -1.5f),
        "R&B" to floatArrayOf(2.5f, 7f, 5.5f, 1.5f, -2.5f, -1.5f, 2.5f, 3f, 3f, 3.5f),
        "Rock" to floatArrayOf(5f, 4f, 3f, 1.5f, -0.5f, -1f, 0.5f, 2.5f, 3.5f, 4.5f),
        "Soft Rock" to floatArrayOf(3f, 3f, 2f, 0f, -2f, -3f, -1.5f, 0f, 2f, 4f),
        "Kleine Lautsprecher" to floatArrayOf(5.5f, 4f, 3.5f, 2.5f, 1f, 0f, -1f, -2f, -2.5f, -3f),
        "Sprache" to floatArrayOf(-3.5f, -1f, 0f, 1f, 3.5f, 4.5f, 4.5f, 3.5f, 2f, 0f),
        "Höhen Boost" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 4.5f, 6f),
        "Höhen reduzieren" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -1.5f, -3f, -4.5f, -6f),
        "Gesang" to floatArrayOf(-1.5f, -3f, -3f, 1.5f, 3.5f, 3.5f, 3f, 1.5f, 0f, -1.5f),
    )

    /** Reverb presets mirrored by android.media.audiofx.PresetReverb (index = preset id). */
    val reverbNames = listOf("Ohne Halleffekt", "Kleiner Raum", "Mittlerer Raum", "Großer Raum", "Mittlere Halle", "Große Halle", "Hallplatte")
}
