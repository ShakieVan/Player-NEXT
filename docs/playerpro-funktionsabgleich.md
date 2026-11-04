# Funktionsabgleich PlayerPro → Player NEXT

Grundlage: PlayerPro Music Player 5.39 (`com.tbig.playerprotrial`), im Emulator
installiert und durchgeklickt, dazu die Einstellungsstruktur aus der APK
(`res/xml/playerpro_settings.xml`) und die Play-Store-Beschreibung. Übernommen
wurden Struktur und Verhalten, keine Grafiken oder Texte im Wortlaut.

Legende: ✅ umgesetzt · ➖ bewusst weggelassen (online oder nicht mehr möglich) ·
🔁 anders gelöst

## Bibliothek und Ansichten

| PlayerPro | Player NEXT |
| --- | --- |
| Browser Suche, Alben, Interpreten, Komponisten, Genres, Playlists, Ordner, Titel, Videos | ✅ alle, gleiche Reihenfolge |
| Radios | ➖ online |
| Gitter/Liste je Browser, Auswahlaktion je Browser | ✅ Gitter, Raster klein, Raster extra klein, Liste |
| Sortieren (Titel, Interpret, Jahr, Titelanzahl, umgekehrt) | ✅ als Untermenü mit Radiopunkten wie bei PlayerPro, je Ansicht weitere Kriterien |
| Filter über das Lupen-Symbol in jeder Ansicht | ✅ |
| Navigationsmenü mit Favoriten und Browsern samt Anzahl, Bearbeiten-Stift | ✅ ein-/ausblenden, sortieren, Favoriten wischen zum Entfernen |
| Albumdetail mit Kopfbereich, „Alle abspielen / Zufällig / Alle zu Warteschlange“ | ✅ auch für Interpreten, Genres, Komponisten, Playlists |
| Mehrfachauswahl per langem Druck | ✅ |
| Kontextmenü (⋮) je Element | ✅ gleiche Einträge und Reihenfolge |
| Interpreten-/Komponisten-/Genrebilder aus dem Internet | 🔁 Cover des Interpreten bzw. Collage der Alben; eigenes Bild aus der Galerie wählbar |
| Albumcover laden aus dem Internet | ➖ online; eingebettete Cover und eigene Bilder aus der Galerie |
| Musikordner festlegen, Medien aktualisieren | ✅ mehrere Ordner wählbar, als Ordnerbrowser; gilt auch für Videos; „Scannen“ je Ordner im Kontextmenü |

## Wiedergabe

| PlayerPro | Player NEXT |
| --- | --- |
| Jetzt läuft: Cover wischen, Übergangseffekte, Bewertungsleiste, Lautstärke, Hinzufügen/Teilen/Liedtexte | ✅ |
| Info-Menü (Alle Album-/Interpreten-/Ordnertitel, Details, Albuminfos, Cover, Tags, Klingelton, Löschen) | ✅ |
| Warteschlange mit Drag & Drop | ✅ zusätzlich Wischen zum Entfernen |
| Zufallswiedergabe mischt die sichtbare Warteschlange | ✅ (`QueuePlayer`); zusätzlich garantiert ohne Wiederholung, bis alle Titel einmal liefen – auch bei zehntausenden (PlayerPro-Fehler „immer dieselben ~100 Titel“); zusätzlich Modus „Lange nicht Gehörtes zuerst“ (ab 1.1.1) |
| Halten von ⏪/⏩ zum Spulen | ✅ |
| Partymix, Einschlaf-Timer (mit Ausblenden) | ✅ Partymix über einen gespeicherten Mischbeutel; Timer zusätzlich „Titel zu Ende spielen“ |
| Gapless, Crossfading auto/manuell, ReplayGain, Pause zwischen Titeln | ✅ |
| DSP-Pack: 10-Band-EQ, Preamp, Bass, Stereo-Breite, Balance, Mono, Begrenzer | ✅ eingebaut, kein Zusatz-Download |
| High-Res-Ausgabe 32 bit, Resampler-Wahl, Puffergröße | ➖ Media3 wählt die Ausgabe selbst |
| Hall-Presets | ✅ über Android `PresetReverb` (geräteabhängig) |
| Tonhöhe, Geschwindigkeit | ✅ |
| Audiofokus (dauerhaft, vorübergehend, leiser) | ✅ einzeln schaltbar |
| Chromecast | ➖ online |

## Statistiken, Playlists, Tags

| PlayerPro | Player NEXT |
| --- | --- |
| Bewertungen, Halbsterne, Wiedergabe-/Überspringzähler | ✅ optional zusätzlich in die Datei schreiben; zusätzlich automatisch aus den Dateien lesen, Sterne in allen Listen, im Tag-Editor bearbeitbar |
| Smarte Playlists (5 + Podcasts) und Editor | ✅ ohne Gruppierung/Kommentar/BPM als Kriterium (nicht in MediaStore) |
| Playlists: eigene, M3U Import/Export | ✅ eigene Datenbank, M3U |
| Statistiken exportieren/wiederherstellen | ✅ als Datei |
| Google-Drive-Sicherung | ➖ online; Sicherung als Datei |
| Tag-Editor einzeln und stapelweise, Cover, Liedtext | ✅ |
| Liedtexte aus dem Internet | ➖ online; eingebettet oder `.lrc` |
| Scrobbler | ➖ online |

## System

| PlayerPro | Player NEXT |
| --- | --- |
| Benachrichtigung mit zwei frei belegbaren Tasten | ✅ |
| Eigener Sperrbildschirm | 🔁 Android-Sperrbildschirm-Steuerung; eigene Sperr-Activities startet Android 14+ aus dem Hintergrund nicht mehr zuverlässig |
| Homescreen-Widgets | ✅ 4×1, 2×2, 4×2, 4×4 und „Plus 4×2“ im PlayerPro-Aufbau; Transparenz je Widget einstellbar, Tipp außerhalb der Tasten öffnet den Player |
| Warteschlange mit der ganzen Bibliothek (zehntausende Titel) | ✅ getestet mit 30 000 Titeln; das System (Sperrbildschirm, Bluetooth, Auto) sieht ab 500 Einträgen nur den aktuellen Titel |
| Updates über den Play Store | 🔁 Update-Funktion über GitHub-Releases wie Mau-Mau Flip (einzige Internetnutzung) |
| Headset: Langdruck, Mehrfachdruck, Start bei Verbindung, Stopp bei Trennung | ✅ Start nur solange Player NEXT läuft (Android verbietet Hintergrundstarts) |
| Schüttelsteuerung, Umdrehen zum Pausieren | ✅ |
| Android Auto | ✅ Alben, Interpreten, Playlists, Titel, Suche; Taste „Bewerten“ (1–5 Sterne) |
| Hörbücher | ➕ eigene Kategorie (fehlt in PlayerPro): M4B-Kapitel, kapitelweise ⏮/⏭, Position je Buch |
| Lautheit | ➕ Lautheits-Maximierer wie LoudMax (Look-ahead-Limiter) |
| Skins, Nacht-Skin mit Zeitraum | ✅ fünf Akzentfarben, Nacht-Skin |
| Startbildschirm, Ausrichtung sperren | ✅ |
| Sprachsuche/Google Assistant (MEDIA_PLAY_FROM_SEARCH) | ✅ |
| Kaufen, Bewerten, Forum, Übersetzungen, DSGVO-Dialog, Werbung | ➖ |
