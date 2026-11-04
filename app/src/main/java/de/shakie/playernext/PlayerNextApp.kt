package de.shakie.playernext

import android.app.Application
import de.shakie.playernext.art.ArtLoader
import de.shakie.playernext.data.EqPresetStore
import de.shakie.playernext.data.FavoritesStore
import de.shakie.playernext.data.FileRatings
import de.shakie.playernext.data.Library
import de.shakie.playernext.data.PlaylistStore
import de.shakie.playernext.data.SmartPlaylistStore
import de.shakie.playernext.data.StatsStore
import de.shakie.playernext.playback.dsp.AudioEffects

class PlayerNextApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        ArtLoader.init(this)
        AudioEffects.init(this)
        StatsStore.init(this)
        FileRatings.init(this)
        PlaylistStore.init(this)
        FavoritesStore.init(this)
        SmartPlaylistStore.init(this)
        EqPresetStore.init(this)
        if (Library.hasPermission()) Library.refresh()
    }
}
