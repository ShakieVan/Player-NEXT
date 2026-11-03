package de.shakie.playernext

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import de.shakie.playernext.data.BrowserType

/**
 * Typed access to the default SharedPreferences. The settings screens write the
 * same keys through androidx.preference, so every list value is stored as String.
 */
object Prefs {
    lateinit var sp: SharedPreferences
        private set

    fun init(context: Context) {
        PreferenceManager.setDefaultValues(context, R.xml.settings_root, true)
        sp = PreferenceManager.getDefaultSharedPreferences(context)
    }

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun int(key: String, def: Int) = try {
        sp.getInt(key, def)
    } catch (_: ClassCastException) {
        sp.getString(key, null)?.toIntOrNull() ?: def
    }
    private fun float(key: String, def: Float) = try {
        sp.getFloat(key, def)
    } catch (_: ClassCastException) {
        sp.getString(key, null)?.toFloatOrNull() ?: def
    }

    // Library
    val musicFolders: Set<String> get() = sp.getStringSet("music_folders", emptySet()) ?: emptySet()
    fun setMusicFolders(folders: Set<String>) = sp.edit().putStringSet("music_folders", folders).apply()
    val includeAllAudio get() = bool("include_all_audio", false)
    val minDurationSec get() = int("min_duration_sec", 0)
    val halfStars get() = bool("ratings_half_stars", false)
    val writeRatingsToTags get() = bool("ratings_write_tags", false)
    val lyricsPreferEmbedded get() = bool("lyrics_prefer_id3", true)

    // Browsers
    fun browserEnabled(type: BrowserType) = bool("use_${type.key}_browser", true)
    var browserOrder: List<BrowserType>
        get() {
            val stored = str("browser_order", "").split(',').mapNotNull { BrowserType.fromKey(it) }
            return stored + BrowserType.entries.filter { it !in stored }
        }
        set(value) = sp.edit().putString("browser_order", value.joinToString(",") { it.key }).apply()
    fun layoutFor(type: BrowserType): String = str("${type.key}_layout", "grid")
    val albumClickAction get() = str("album_click_action", "browse_tracks")
    val artistClickAction get() = str("artist_click_action", "browse_albums")
    val composerClickAction get() = str("composer_click_action", "browse_tracks")
    val genreClickAction get() = str("genre_click_action", "browse_albums")
    val folderClickAction get() = str("folder_click_action", "play_all")
    val songClickAction get() = str("song_click_action", "play_all")
    val artistsUseAlbumArtist get() = bool("artist_use_album_artist", false)
    val artistsOnlyWithAlbum get() = bool("show_album_artist", false)
    val folderShowTitleTag get() = bool("display_title_tag", true)
    val videoShowTitleTag get() = bool("display_title_vid_tag", true)
    val playNextVideo get() = bool("play_next_video", true)
    val smartPlaylists: Set<String>
        get() = sp.getStringSet("smart_playlists", setOf("ra", "tr", "mp", "rp", "lp", "pd")) ?: emptySet()
    fun sortKey(scope: String, def: String) = str("sort_$scope", def)
    fun sortReverse(scope: String) = bool("sort_${scope}_reverse", false)
    fun setSort(scope: String, key: String, reverse: Boolean) =
        sp.edit().putString("sort_$scope", key).putBoolean("sort_${scope}_reverse", reverse).apply()

    // Look & feel
    val skin get() = str("prefskin", "orange")
    val nightSkin get() = str("prefnightskin", "none")
    val nightStart get() = str("nighttime_skin_start", "22:00")
    val nightEnd get() = str("nighttime_skin_end", "07:00")
    val startupScreen get() = str("startup_screen", "startup_library")
    val lockOrientation get() = str("lock_orientation", "lock_none")
    val showBrowsersFirst get() = bool("show_browsers_first", false)
    val playerArt get() = str("player_art", "player_art_normal")
    val preferEmbeddedArt get() = bool("prefer_embedded", false)
    val swipeEffect get() = str("player_swipe_effect", "player_swipe_drawer")
    val playerGesture get() = str("player_gesture", "player_gesture_none")
    val playerVisibleRatings get() = bool("player_visible_ratings", true)
    var displayRemaining
        get() = bool("player_display_remaining", false)
        set(value) = sp.edit().putBoolean("player_display_remaining", value).apply()
    val playerFullScreen get() = bool("player_full_screen", false)
    val playerKeepScreenOn get() = bool("player_keep_screen_on", false)
    var lastBrowser
        get() = BrowserType.fromKey(str("last_browser", BrowserType.ALBUMS.key)) ?: BrowserType.ALBUMS
        set(value) = sp.edit().putString("last_browser", value.key).apply()
    var lastScreenWasPlayer
        get() = bool("last_screen_player", false)
        set(value) = sp.edit().putBoolean("last_screen_player", value).apply()

    // Notification
    val notifRemoveOnPause get() = bool("statusbar_remove_pause", false)
    val notifCustomLeft get() = str("statusbar_custom_action_1", "custom_action_favorite")
    val notifCustomRight get() = str("statusbar_custom_action_2", "custom_action_stop")
    val notifUsePrev get() = bool("statusbar_use_prev", true)

    // Lockscreen
    val lockscreenEnabled get() = bool("lockscreen_playerpro", false)
    val lockscreenClock get() = bool("lockscreen_show_clock", true)
    val lockscreenHideControls get() = bool("lockscreen_hide_pcontrols", false)
    val lockscreenHideRatings get() = bool("lockscreen_hide_ratings", false)
    val lockscreenHideShuffleRepeat get() = bool("lockscreen_hide_shufflerepeat", false)
    val lockscreenBackground get() = str("lockscreen_bg", "wallpaper")
    val lockscreenSwipe get() = bool("lockscreen_enable_swipe", true)
    val lockscreenFullScreen get() = bool("lockscreen_full_screen", false)

    // Audio
    val balance get() = int("audio_balance", 0) / 100f
    val monoOutput get() = bool("mono_output", false)
    val useLimiter get() = bool("use_limiter", true)
    val pauseBetweenSongs get() = bool("pause_between_songs", false)
    val pitch get() = int("audio_pitch", 100) / 100f
    val speed get() = int("audio_speed", 100) / 100f
    val skipUnsupported get() = bool("skip_unsupported_audio", true)
    val focusPauseOnLoss get() = bool("audio_focus_loss", true)
    val focusPauseOnTransient get() = bool("audio_focus_transient_loss", true)
    val focusDuck get() = bool("audio_focus_transient_loss_can_duck", true)
    val audioOffload get() = bool("audio_offload", true)
    val offloadRequireGapless get() = bool("audio_offload_gapless", false)
    val autoCrossfade get() = bool("auto_cfade", false)
    val autoCrossfadeMs get() = int("auto_cfade_time", 5000)
    val autoCrossfadeShuffleOnly get() = bool("auto_cfade_shuffle", true)
    val manualCrossfade get() = bool("manual_cfade", false)
    val manualCrossfadeMs get() = int("manual_cfade_time", 1000)
    val replayGain get() = bool("rg", true)
    val replayGainAlbum get() = str("rg_source", "album") == "album"
    val replayGainDefault get() = int("rg_dg", 0).toFloat()
    val replayGainPreamp get() = int("rg_preamp", 0).toFloat()
    val replayGainClipping get() = bool("rg_clipping", true)

    // Headset
    val useHeadset get() = bool("use_headset", true)
    val headsetLongPress get() = str("headset_long_press", "none")
    val headsetMultiPress get() = bool("headset_use_db_press", false)
    val headsetAutoStart get() = bool("headset_auto_start", false)
    val headsetBtAutoStart get() = bool("headset_bt_auto_start", true)
    val headsetBtAutoStop get() = bool("headset_bt_auto_stop", true)

    // Updates (the only internet use; see update/Updater.kt)
    var updateAutoCheck
        get() = bool("update_auto_check", true)
        set(value) = sp.edit().putBoolean("update_auto_check", value).apply()

    // Shake
    val useShake get() = bool("use_shake", false)
    val shakeSensitivity get() = int("shake_sensitivity", 50)
    val shakeFlipPause get() = bool("shake_pause_upsidedown", false)

    fun float01(key: String, def: Float) = float(key, def)
}
