# Energie

Ziel ist ein Player, der weniger Akku braucht als PlayerPro. Maßnahmen und
Messwerte:

| Maßnahme | Wirkung / Beleg |
| --- | --- |
| Audio-Offload (Einstellung „Stromsparende Hardware-Wiedergabe“, Standard an) | Dekodierung im Audio-DSP, wenn kein Effekt, keine Geschwindigkeits-/Tonhöhenänderung und kein Crossfade aktiv ist. `PlaybackService.applyOffload()` schaltet bei Effektänderungen automatisch um. |
| DSP-Durchreichung | `DspAudioProcessor` kopiert den Puffer unverändert, wenn alle Parameter neutral sind (Unit-Test `neutralSettingsPassAudioThroughUnchanged`). |
| Keine Positions-Pushes der Session | `setPeriodicPositionUpdateEnabled(false)`; vorher meldete der Dienst alle 3 s den Zustand an System-UI und Medienkarte (auf dem S21 im Log sichtbar). |
| Fortschritt nur im Vordergrund, 1/s auf Sekundenwechsel | „Jetzt läuft“ beim Abspielen: vorher ≈ 9 Bilder/s, jetzt ≈ 1,2 Bilder/s (`dumpsys gfxinfo`, 5 s). Pausiert: 0 Bilder/s. Mini-Player stoppt in `onPause`. |
| UI reagiert nur auf relevante Player-Ereignisse | `PlayerHub` filtert Lade-/Lautstärke-Ereignisse. |
| Widgets und Benachrichtigung nur bei Zustandswechsel | Kein `updatePeriodMillis`, kein Neubau bei Positionsänderungen. |
| Bibliothek nur bei Änderung neu bauen | `ContentObserver` mit 2,5 s Entprellung, ein MediaStore-Query pro Neubau. |
| Cover aus MediaStore-Thumbnails | `loadThumbnail` in passender Größe, LRU-Cache, keine Vollbild-Dekodierung für Listen. |
| Sensoren | Schüttelsteuerung standardmäßig aus; nur während der Wiedergabe registriert, `SENSOR_DELAY_UI`. |
| Wake-Lock | `WAKE_MODE_LOCAL` nur während ExoPlayer spielt. |
| Crossfade-Zweit-Player | Nur während einer Überblendung aktiv, danach nach 30 s freigegeben. |
| Widgets gebündelt | Ereignisse innerhalb von 120 ms ergeben ein Widget-Update; das Cover des laufenden Titels wird nur einmal dekodiert. ⏪/⏩ ohne laufenden Dienst verschieben nur den gespeicherten Titel und starten nichts. |
| Warteschlange speichern | Die Liste (bei 30 000 Titeln einige MB) wird nur bei Änderungen geschrieben, der kleine Zustand bei Titelwechseln – beides auf einem Hintergrund-Thread. Vorher wurde bei jedem Titelwechsel die ganze Liste als JSON auf dem Haupt-Thread geschrieben. |
| Update-Prüfung | Höchstens eine kleine HTTPS-Anfrage am Tag beim Start (abschaltbar), kein Hintergrund-Job. |

| Bedarfsgesteuerte Wiedergabe-Schleife | `experimentalSetDynamicSchedulingEnabled(true)`: ExoPlayers Schleife wacht nicht mehr alle 10 ms auf. Emulator: Wiedergabe-Thread 119 → 38 Ticks pro 30 s. |
| Größerer AudioTrack-Puffer | 0,5–1 s PCM-Puffer, weniger Nachfüllvorgänge. Effektänderungen werden nach höchstens ~1 s hörbar. |

## Messungen

CPU-Zeit in Ticks (1/100 s) pro Minute, App plus Audio-/Medienprozesse des
Systems (`audioserver`, `mediaserver`, `media.swcodec`, Audio-HAL), Musik
läuft im Hintergrund. Werkzeug: `/proc/<pid>/stat` vorher/nachher.

| Gerät | Situation | Summe |
| --- | --- | --- |
| Samsung S21 (Android 15), Bildschirm aus | Player NEXT mit Hardware-Offload | **479** |
| Samsung S21, gleicher Bau | Player NEXT ohne Offload (PCM) | 3409 |
| Emulator (kein Offload) | PlayerPro 5.39 | 576 |
| Emulator (kein Offload) | Player NEXT vor Optimierung | ~750 |
| Emulator (kein Offload) | Player NEXT mit bedarfsgesteuerter Schleife | ~600–630 |

Ohne Offload liegt Player NEXT im Emulator etwa gleichauf mit PlayerPro
(PlayerPro dekodiert über den System-`MediaPlayer`, daher erscheint seine Last
fast nur in `mediaserver`). Der eigentliche Gewinn kommt auf echten Geräten aus
dem Offload: Auf dem S21 sinkt die CPU-Last beim Hören mit ausgeschaltetem
Bildschirm auf ein Siebtel.

### Offload und lückenlose Übergänge

Das S21 meldet für MP3 Offload, aber keine lückenlosen Übergänge im Offload.
Würde Player NEXT lückenlos voraussetzen, fiele der Offload weg. Deshalb ist
„Nur mit lückenlosen Übergängen“ eine Option (Standard aus). Geprüft auf dem
S21: Pause, Fortsetzen, „Weiter“ und automatischer Titelwechsel laufen im
Offload weiter (`dumpsys media.audio_flinger` zeigt einen `OFFLOAD`-Ausgang mit
Format MP3).

Offen: Vergleich mit PlayerPro auf demselben echten Gerät (PlayerPro müsste
dafür auf einem Testhandy installiert werden).
