# Bewertungen

Player NEXT führt Bewertungen in halben Sternen (0–10) in `StatsStore`
(Tabelle `stats`). Dateien tragen eigene Bewertungen; beides wird abgeglichen,
ohne dass eine in Player NEXT gesetzte Bewertung verloren geht.

## Formate (`tags/RatingTags`)

| Format | Feld | Skala |
| --- | --- | --- |
| MP3 | `POPM` (alle Rahmen; „Windows Media Player 9 Series“ zuerst, sonst der höchste) | 1/64/128/196/255 = 1–5 Sterne (WMP, MusicBee, MediaMonkey, PlayerPro), Halbsterne 13/54/118/186/242 (MediaMonkey); andere Werte nach WMP-Bändern |
| MP3 | `TXXX:RATING` (foobar2000) / `TXXX:FMPS_Rating` | 1–5 bzw. 0–100 / 0,0–1,0 |
| FLAC, Ogg, Opus | `RATING` / `FMPS_RATING` | 1–5 (auch „4.5“) oder 0–100 / 0,0–1,0 |
| MP4/M4A | `rate` | 0–100 |

Schreiben behält die Schreibweise der Datei: Alle vorhandenen POPM-Rahmen
bekommen den neuen Wert (Zähler bleibt), ohne POPM entsteht einer mit
„Windows Media Player 9 Series“; `RATING` bleibt auf 1–5, wenn es vorher so
stand, sonst 0–100. `RatingTagsTest` prüft das mit echten Dateien
(`app/src/test/resources/tags/`).

## Automatisch lesen (`data/FileRatings`)

Einstellung „Bewertungen automatisch aus Dateien lesen“ (Standard an). Nach
jeder Bibliotheksänderung liest ein Hintergrund-Thread niedriger Priorität
nur neue oder geänderte Dateien (MediaStore-Datum oder -Größe anders als beim
letzten Lesen). Was in der Datei stand, merkt sich die Tabelle `file_ratings`
(nur Zwischenspeicher, keine Nutzerdaten). Regel (`FileRatings.decide`):

- Datei hat eine Bewertung, Player NEXT keine → übernehmen.
- Player NEXT hat noch den Wert, den die Datei beim letzten Lesen hatte → der
  neue Dateiwert gilt (in einem anderen Player geändert).
- Sonst bleibt die Bewertung von Player NEXT. Eine Datei ohne Bewertung
  (z. B. Tag neu geschrieben) löscht nie eine vorhandene.

Unlesbare Dateien zählen als „keine Bewertung“, bis sie sich ändern; im
Hintergrund wird nie eine Kopie angelegt. Listen werden höchstens alle 5 s
benachrichtigt.

„Bewertungen aus Dateien importieren“ liest alle Dateien neu, mit
Fortschritt und Abbrechen, nach derselben Regel. Vor 1.1.3 las der Import nur
den ersten POPM-Rahmen als Zahl und rechnete 1–5-Skalen (foobar2000, FLAC)
zu 0 Sternen um; es gab keinen Fortschritt.

## Anzeige und Bearbeitung

- Listen: kleine Sterne vor der Dauer in allen Titelzeilen (Titel, Ordner,
  Alben, Interpreten, Playlists, Suche), abschaltbar („Bewertungen in Listen
  anzeigen“). Änderungen zeichnen nur die Sterne neu (`RowAdapter.refreshState`).
- Tag-Editor: „Bewertung (in der Datei)“ zeigt den Dateiwert, darunter den
  Wert von Player NEXT, wenn er abweicht. Speichern schreibt die Datei und
  setzt Player NEXT auf denselben Wert.
- Android Auto: zusätzliche Taste „Bewerten“ (Einstellung „Bewerten in
  Android Auto“, Standard an); jedes Tippen ein Stern mehr, nach fünf keiner,
  das Symbol (`ic_rating_0` … `_5`) zeigt die aktuellen Sterne. Android Auto
  zeigt die Tasten der Plattform-Sitzung, also die der Benachrichtigung; am
  Handy blendet Android 16 nur zwei Zusatztasten ein.

## Geprüft (Emulator, 03.11.2026)

foobar `TXXX:RATING=4` → 4 Sterne, FLAC `RATING=60` → 3, Ogg
`FMPS_RATING=0.8` → 4, ohne Zutun nach dem Start; Sterne in Titel- und
Ordnerliste; im Tag-Editor 4 → 5 Sterne gespeichert: Datei danach `POPM`
„Windows Media Player 9 Series“ = 255 und `TXXX:RATING` = „5“. Taste in der
Sitzung: „Bewertung: 3 Sterne“ nach Bewerten im Player.
