# AGENTS.md – Player NEXT

## Zweck

Player NEXT baut Bedienung, Verhalten und Aufbau von PlayerPro Music Player
nach – ohne Online-Funktionen, mit geringem Energieverbrauch und im Design von
Tube NEXT. Was übernommen, angepasst oder weggelassen wurde, steht in
[`docs/playerpro-funktionsabgleich.md`](docs/playerpro-funktionsabgleich.md).

## Verbindliche Entscheidungen

- Keine Online-Funktionen (Cover-/Bilder-Downloads, Liedtext-Suche, Radios,
  Cast, Scrobbling, Cloud-Sicherung). Einzige Ausnahme ist die
  Update-Funktion (`update/`, GitHub-Releases, Wunsch des Nutzers); kein
  anderer Code darf das Internet nutzen.
- Keine Grafiken, Texte im Wortlaut oder Code von PlayerPro.
- Design: Farben und Wortmarke wie Tube NEXT (`pn_*`-Farben, Montserrat Bold,
  „NEXT“ im Rot→Orange-Verlauf). Eigene Drawables verwenden die Skin-Attribute
  `?attr/pnAccent`, `?attr/pnAccentStrong`, `?attr/pnActiveBg`, damit Skins
  greifen.
- Wiedergabe ausschließlich über Media3 (`PlaybackService` als
  `MediaLibraryService`). Die UI spricht über `PlayerHub` (MediaController) mit
  dem Dienst; zusätzliche In-Prozess-Zugriffe nur für DSP-Parameter
  (`AudioEffects`) und den Einschlaf-Timer.

Bei Konflikten gilt: 1. Stabilität, 2. Daten des Nutzers (Tags, Statistiken,
Playlists) nie verlieren, 3. Akku, 4. Nähe zu PlayerPro, 5. Komfort.

## Architektur

- `data/` – `MediaStoreScanner` liest Musik/Videos, `Library` hält einen
  unveränderlichen `LibrarySnapshot` und baut ihn bei MediaStore-Änderungen
  neu (entprellt). `AppDatabase` enthält Statistiken, Playlists, smarte
  Playlists, Favoriten und EQ-Presets; die Stores cachen im Speicher und
  schreiben asynchron.
- `playback/` – `PlaybackService` (ExoPlayer, Session, Benachrichtigung,
  Android Auto), `QueuePlayer` (sichtbares Mischen), `Crossfader`
  (Überblenden mit kurzlebigem Zweit-Player), `AudioFocusController`,
  `PlayStatsTracker`, `SleepTimer`, `HeadsetButtons`, `ShakeDetector`,
  `QueueStore`, `dsp/` (10-Band-EQ und Effekte als `AudioProcessor`).
- `ui/` – `MainActivity` (Navigationsmenü, Mini-Player) mit einem generischen
  `BrowseFragment` für alle Browser und Detailansichten, `NowPlayingActivity`,
  `EqualizerActivity`, `SettingsActivity`, `TagEditorActivity`,
  `SmartPlaylistEditorActivity`, `VideoPlayerActivity`.
- `tags/TagIO` – jaudiotagger über eine Arbeitskopie und die
  MediaStore-Schreibfreigabe.
- `widget/` – fünf Widgets (darunter „Plus 4×2“ im PlayerPro-Aufbau),
  Transparenz je Widget (`WidgetConfigActivity`), Tasten über den eigenen
  Provider, Aktualisierung nur bei Zustandswechseln.
- `update/` – Update-Funktion wie Mau-Mau Flip (siehe
  [`docs/technical-notes/updates.md`](docs/technical-notes/updates.md)).
- Zufall: `QueuePlayer` mischt sichtbar, `ShuffleBag` liefert den Partymix;
  kein Titel wiederholt sich, bevor alle einmal liefen (Unit-Test
  `BagOrderTest`).

Vor Änderungen an Wiedergabe, Energie oder Tags die Notizen in
[`docs/technical-notes/`](docs/technical-notes/) lesen.

## Energie-Regeln

- Keine periodischen Timer im Hintergrund. UI-Takte (Fortschritt) nur im
  Vordergrund und höchstens 1/s, auf Sekundenwechsel ausgerichtet.
- `MediaSession` sendet keine periodischen Positionsmeldungen
  (`setPeriodicPositionUpdateEnabled(false)`).
- Der DSP reicht bei neutralen Einstellungen unverändert durch; Audio-Offload
  nur, wenn weder DSP noch Geschwindigkeit/Tonhöhe noch Crossfade aktiv sind.
- Sensoren nur, wenn die Funktion an ist und gerade gespielt wird.
- Listener nur auf relevante Player-Ereignisse reagieren lassen (`PlayerHub`).

## Gerätetests

- Testhandys nur nach Eintrag auf der Reservierungsseite nutzen (siehe
  globale Regeln des Nutzers).
- Debug-Builds zeigen sich über dem Sperrbildschirm, wenn
  `adb shell setprop log.tag.PlayerNextTestLock DEBUG` gesetzt ist. Danach
  **immer** wieder `INFO` setzen.
- Systemdialoge (z. B. die Schreibfreigabe des Tag-Editors) erscheinen nicht
  über der Sperre; solche Abläufe im Emulator prüfen.
- Testmusik in einen eigenen Ordner legen und danach löschen.
- Im fensterlosen Emulator mit `-gpu host` werden Vektor-Symbole teils als
  Kästen gezeichnet; das ist ein Emulatorfehler, kein App-Fehler.

## Veröffentlichen

- Repository: github.com/ShakieVan/Player-NEXT (öffentlich, damit der
  Updater Releases ohne Anmeldung findet).
- Release-Ablauf und Regeln für die APK (Name, Tag, Signatur) in
  [`docs/technical-notes/updates.md`](docs/technical-notes/updates.md);
  Release-Notizen unter `docs/releases/`.
- `key.properties` und der Schlüssel unter `~/.keystores/` werden nie
  committet.

## Arbeitsweise

- Deutsch für Oberfläche und Doku, Kotlin-Code mit knappen englischen
  Kommentaren wie in Tube NEXT.
- Kleine, prüfbare Schritte; nach Änderungen `assembleDebug` und
  `testDebugUnitTest`.
- Dauerhafte Entscheidungen unter `docs/decisions/`, technisches Wissen unter
  `docs/technical-notes/` festhalten.
