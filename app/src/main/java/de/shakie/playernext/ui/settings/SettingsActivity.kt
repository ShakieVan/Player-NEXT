package de.shakie.playernext.ui.settings

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.shakie.playernext.BuildConfig
import de.shakie.playernext.Prefs
import de.shakie.playernext.R
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.EqPresetStore
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.SongStats
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.playback.PlaybackService
import de.shakie.playernext.playback.dsp.AudioEffects
import de.shakie.playernext.tags.TagIO
import de.shakie.playernext.ui.BaseActivity
import de.shakie.playernext.ui.common.ItemActions
import de.shakie.playernext.ui.equalizer.EqualizerActivity
import de.shakie.playernext.update.UpdateActivity
import de.shakie.playernext.update.Updater
import de.shakie.playernext.widget.WidgetConfigActivity
import de.shakie.playernext.ui.playlists.PlaylistIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SettingsActivity : BaseActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.pn_surface_low))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(getColor(R.color.pn_on_surface))
            setTitleTextColor(getColor(R.color.pn_on_surface))
            title = getString(R.string.settings)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val tv = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)))
        val container = FrameLayout(this).apply { id = R.id.content_frame }
        root.addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(R.id.content_frame, SettingsFragment.create(null)) }
        }
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
    }

    private fun updateTitle() {
        val f = supportFragmentManager.findFragmentById(R.id.content_frame) as? PreferenceFragmentCompat
        toolbar.title = f?.preferenceScreen?.title ?: getString(R.string.settings)
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        supportFragmentManager.commit {
            setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            replace(R.id.content_frame, SettingsFragment.create(pref.key))
            addToBackStack(pref.key)
        }
        toolbar.title = pref.title
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            fun create(rootKey: String?) = SettingsFragment().apply {
                arguments = Bundle().apply { putString(ARG_PREFERENCE_ROOT, rootKey) }
            }
        }

        private val base get() = requireActivity() as BaseActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_root, rootKey)
            fun click(key: String, action: () -> Unit) {
                findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
            }
            click("music_folder") { chooseMusicFolders() }
            click("trigger_refresh_mediastore") { rescan() }
            click("import_metadata") { importRatings() }
            click("music_stats_export") { exportStats() }
            click("restore_music_stats") { restoreStats() }
            click("delete_album_art") { ArtLoader.deleteAllCustom("album"); Library.notifyArtChanged(); toast(R.string.art_deleted) }
            click("delete_artist_art") { ArtLoader.deleteAllCustom("artist"); Library.notifyArtChanged(); toast(R.string.art_deleted) }
            click("delete_genre_art") { ArtLoader.deleteAllCustom("collage"); Library.notifyArtChanged(); toast(R.string.art_deleted) }
            click("export_playlists") { exportPlaylists() }
            click("import_playlists") { PlaylistIO.import(base) }
            click("open_equalizer") { startActivity(Intent(requireContext(), EqualizerActivity::class.java)) }
            click("use_system_sound_effects") { openSystemEffects() }
            click("reset_eq_presets") {
                EqPresetStore.clear()
                AudioEffects.applyPreset("Normal", FloatArray(AudioEffects.BANDS), 0f)
                toast(R.string.eq_presets_reset)
            }
            click("updates") { startActivity(Intent(requireContext(), UpdateActivity::class.java)) }
            findPreference<CheckBoxPreference>("update_beta")?.apply {
                Updater.init(requireContext())
                isChecked = Updater.beta
                setOnPreferenceChangeListener { _, value -> Updater.setBeta(value as Boolean); true }
            }
            findPreference<ListPreference>("shuffle_mode")?.let { mode ->
                val window = findPreference<Preference>("shuffle_window")
                window?.isEnabled = mode.value == "oldest"
                mode.setOnPreferenceChangeListener { _, value -> window?.isEnabled = value == "oldest"; true }
            }
            click("widget_settings") { startActivity(Intent(requireContext(), WidgetConfigActivity::class.java)) }
            click("export_settings") { exportSettings() }
            click("restore_settings") { restoreSettings() }
            click("ppo_licenses") {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Lizenzen").setMessage(R.string.licenses_text)
                    .setPositiveButton(R.string.ok, null).show()
            }
            findPreference<Preference>("ppo_version")?.summary = getString(R.string.version_summary, BuildConfig.VERSION_NAME)
            updateFolderSummary()
            // Library shape changes need a rebuild of the in-memory snapshot.
            listOf("include_all_audio", "min_duration_sec", "artist_use_album_artist", "show_album_artist").forEach { k ->
                findPreference<Preference>(k)?.setOnPreferenceChangeListener { _, _ ->
                    view?.post { Library.refresh() }
                    true
                }
            }
        }

        private fun toast(res: Int) = ItemActions.toast(base, getString(res))

        private fun updateFolderSummary() {
            val folders = Prefs.musicFolders
            findPreference<Preference>("music_folder")?.summary =
                if (folders.isEmpty()) getString(R.string.music_folders_all) else folders.joinToString("\n")
        }

        /** Offers every folder that contains audio (and its parents) for restricting the library. */
        private fun chooseMusicFolders() {
            viewLifecycleOwner.lifecycleScope.launch {
                val candidates = withContext(Dispatchers.IO) {
                    val dirs = HashSet<String>()
                    requireContext().contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media.DATA), null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            var p = c.getString(0)?.substringBeforeLast('/') ?: continue
                            while (p.count { it == '/' } >= 4) {
                                dirs += p
                                p = p.substringBeforeLast('/')
                            }
                        }
                    }
                    dirs.sorted()
                }
                if (candidates.isEmpty()) return@launch
                val current = Prefs.musicFolders
                val checked = BooleanArray(candidates.size) { candidates[it] in current }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.music_folders_title)
                    .setMultiChoiceItems(candidates.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        Prefs.setMusicFolders(candidates.filterIndexed { i, _ -> checked[i] }.toSet())
                        updateFolderSummary()
                        Library.refresh()
                    }
                    .setNeutralButton(R.string.music_folders_all) { _, _ ->
                        Prefs.setMusicFolders(emptySet())
                        updateFolderSummary()
                        Library.refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        private fun rescan() {
            val dirs = Library.snapshot.songs.map { it.path }.ifEmpty { listOf("/storage/emulated/0/Music") }
            TagIO.rescan(requireContext(), dirs)
            toast(R.string.rescan_started)
            view?.postDelayed({ Library.refresh() }, 4000)
        }

        private fun importRatings() {
            val ctx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                val count = withContext(Dispatchers.IO) {
                    var n = 0
                    for (s in Library.snapshot.songs) {
                        val r = TagIO.readRating(ctx, s) ?: continue
                        if (StatsStore[s.path].rating == 0) {
                            StatsStore.setRating(s.path, r)
                            n++
                        }
                    }
                    n
                }
                ItemActions.toast(base, getString(R.string.stats_imported, count))
            }
        }

        private fun exportStats() {
            base.createDocument("player-next-statistiken.json") { uri ->
                uri ?: return@createDocument
                val json = JSONObject()
                StatsStore.all().forEach { (path, s) ->
                    json.put(path, JSONObject().put("rating", s.rating).put("plays", s.playCount).put("skips", s.skipCount)
                        .put("last_played", s.lastPlayed).put("last_skipped", s.lastSkipped))
                }
                writeText(uri, json.toString(1))
                toast(R.string.stats_exported)
            }
        }

        private fun restoreStats() {
            base.openDocument(arrayOf("application/json", "text/*", "*/*")) { uri ->
                uri ?: return@openDocument
                try {
                    val json = JSONObject(readText(uri))
                    val map = HashMap<String, SongStats>()
                    json.keys().forEach { k ->
                        val o = json.getJSONObject(k)
                        map[k] = SongStats(o.optInt("rating"), o.optInt("plays"), o.optInt("skips"), o.optLong("last_played"), o.optLong("last_skipped"))
                    }
                    StatsStore.replaceAll(map)
                    ItemActions.toast(base, getString(R.string.stats_restored, map.size))
                } catch (e: Exception) {
                    ItemActions.toast(base, getString(R.string.failed, e.message ?: ""))
                }
            }
        }

        private fun exportPlaylists() {
            base.openFolder { tree ->
                tree ?: return@openFolder
                val dir = DocumentFile.fromTreeUri(requireContext(), tree) ?: return@openFolder
                val lib = Library.snapshot
                var n = 0
                for (p in PlaylistStore.all()) {
                    val file = dir.createFile("audio/x-mpegurl", "${p.name}.m3u") ?: continue
                    val text = buildString {
                        append("#EXTM3U\n")
                        p.songPaths.mapNotNull { lib.songsByPath[it] }.forEach { s ->
                            append("#EXTINF:${s.duration / 1000},${s.artist} - ${s.title}\n${s.path}\n")
                        }
                    }
                    writeText(file.uri, text)
                    n++
                }
                ItemActions.toast(base, getString(R.string.playlist_exported) + " ($n)")
            }
        }

        private fun openSystemEffects() {
            val sessionId = PlaybackService.instance?.exo?.audioSessionId ?: 0
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, requireContext().packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            val handlers = requireContext().packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != requireContext().packageName }
            if (handlers.isEmpty()) {
                ItemActions.toast(base, getString(R.string.failed, "kein System-Panel vorhanden"))
            } else {
                intent.setClassName(handlers[0].activityInfo.packageName, handlers[0].activityInfo.name)
                startActivity(intent)
            }
        }

        private fun exportSettings() {
            base.createDocument("player-next-einstellungen.json") { uri ->
                uri ?: return@createDocument
                val json = JSONObject()
                val prefs = JSONObject()
                Prefs.sp.all.forEach { (k, v) ->
                    prefs.put(k, when (v) {
                        is Set<*> -> org.json.JSONArray(v.toList())
                        else -> v
                    })
                }
                json.put("prefs", prefs)
                json.put("effects", JSONObject(AudioEffects.exportState()))
                json.put("eq_presets", JSONObject().apply {
                    EqPresetStore.userPresets().forEach { (n, p) -> put(n, JSONObject().put("bands", p.first.joinToString(",")).put("preamp", p.second.toDouble())) }
                })
                writeText(uri, json.toString(1))
                toast(R.string.settings_exported)
            }
        }

        private fun restoreSettings() {
            base.openDocument(arrayOf("application/json", "text/*", "*/*")) { uri ->
                uri ?: return@openDocument
                try {
                    val json = JSONObject(readText(uri))
                    val prefs = json.getJSONObject("prefs")
                    val e = Prefs.sp.edit()
                    prefs.keys().forEach { k ->
                        when (val v = prefs.get(k)) {
                            is Boolean -> e.putBoolean(k, v)
                            is Int -> e.putInt(k, v)
                            is Long -> e.putLong(k, v)
                            is Double -> e.putFloat(k, v.toFloat())
                            is org.json.JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                            else -> e.putString(k, v.toString())
                        }
                    }
                    e.apply()
                    json.optJSONObject("effects")?.let { fx ->
                        requireContext().getSharedPreferences("effects", android.content.Context.MODE_PRIVATE).edit().apply {
                            fx.keys().forEach { k ->
                                when (val v = fx.get(k)) {
                                    is Boolean -> putBoolean(k, v)
                                    is Int -> putInt(k, v)
                                    is Double -> putFloat(k, v.toFloat())
                                    else -> putString(k, v.toString())
                                }
                            }
                        }.commit()
                        AudioEffects.init(requireContext().applicationContext)
                    }
                    json.optJSONObject("eq_presets")?.let { p ->
                        p.keys().forEach { n ->
                            val o = p.getJSONObject(n)
                            EqPresetStore.save(n, o.getString("bands").split(',').map { it.toFloat() }.toFloatArray(), o.optDouble("preamp").toFloat())
                        }
                    }
                    toast(R.string.settings_restored)
                    requireActivity().recreate()
                } catch (ex: Exception) {
                    ItemActions.toast(base, getString(R.string.failed, ex.message ?: ""))
                }
            }
        }

        private fun writeText(uri: Uri, text: String) {
            requireContext().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
        }

        private fun readText(uri: Uri): String =
            requireContext().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    }
}
