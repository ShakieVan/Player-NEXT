# Hörbücher

## Was als Hörbuch gilt

`Song.isAudiobook`: MediaStore `IS_AUDIOBOOK` (Ordner „Audiobooks“), Endung
`.m4b` oder unter „Hörbücher“ selbst hinzugefügt (`Prefs.audiobookFiles`).
MediaStore nimmt M4B als `audio/mp4` auf (geprüft, Android 16). Hinzugefügte
Bücher gehören zur Bibliothek, auch außerhalb der Musikordner
(`MediaStoreScanner`).

Hörbücher bleiben beim Partymix (`ShuffleBag`) und beim Mischen von Musik
(`PlayerHub.play` mit `shuffle`) außen vor; eine Liste nur aus Hörbüchern wird
normal gemischt.

## Kategorie „Hörbücher“ (`ui/browse/AudiobookLibrary`)

- Liste aller Hörbücher mit „Autor · 41 % gehört · 12 Kapitel“.
- ⋮ → „Hörbücher suchen“: Medienscan des Ordners „Audiobooks“, dann alle
  Hörbücher, die MediaStore kennt (`IS_AUDIOBOOK` oder `.m4b`), die noch nicht
  in der Bibliothek sind – zum Abhaken, alle vorausgewählt.
- ⋮ → „Datei hinzufügen“: Dateiauswahl von Android (mehrere, `audio/*`). Der
  Pfad kommt aus der Dokument-ID (`externalstorage`, `media`, `downloads` mit
  `raw:`/`msf:`), sonst über Name und Größe in MediaStore. Danach Medienscan.
- Kontextmenü „Aus Hörbüchern entfernen“ für selbst hinzugefügte.

## Kapitel (`playback/Mp4Chapters`, `ChapterStore`, `ChapterNav`)

- Gelesen wird die QuickTime-Kapitelspur (Textspur über `tref/chap`; iTunes,
  Audible-Umwandlungen, ffmpeg), sonst Nero `udta/chpl`. Nur Atom-Köpfe und
  die wenigen Kapitel-Samples werden gelesen, einmal je Datei beim
  Titelwechsel im Hintergrund; der Speicher hält die letzten 64 Dateien.
- ⏮/⏭ (Einstellung „⏮/⏭ springen zwischen Kapiteln“, Standard an): ⏭ zum
  nächsten Kapitel, im letzten zum nächsten Titel; ⏮ zum Kapitelanfang, in
  den ersten 3 s zum vorigen. Gilt für App, Medientasten, Kopfhörer,
  Benachrichtigung und Android Auto (`QueuePlayer.seekToNext…`).
- Ein einzelnes Hörbuch hat keinen nächsten Titel; Media3 würde ⏭ sperren.
  `QueuePlayer.getAvailableCommands` meldet bei Kapiteln Weiter/Zurück als
  verfügbar; die Plattform-Sitzung (Auto, Bluetooth) baut ihre Aktionen bei
  jedem Ereignis daraus neu. Die App selbst springt in `PlayerHub.next/previous`
  direkt per `seekTo`.
- Player: Zeile „1/3 · Kapiteltitel“ unter den Tasten, Tippen öffnet die
  Kapitelliste (auch ⋮ → „Kapitel“).

Achtung, geprüft und verworfen: Ein Listener-Wrapper mit Kotlin-Delegation
(`object : Player.Listener by listener`) reicht die Java-Default-Methoden
von `Player.Listener` nicht weiter – die Sitzung bekam dann gar keine
Ereignisse mehr („BUFFERING, Position 0“ für immer).

## Position merken (`AudiobookPositions`)

Einstellung „Position merken“ (Standard an). Gespeichert wird beim Pausieren
und beim Verlassen des Buchs (anderer Titel, andere Warteschlange). Startet
ein Hörbuch am Anfang, springt es an die gemerkte Stelle. In den letzten 30 s
gilt es als fertig gehört und wird vergessen; Positionen unter 10 s ändern
nichts (beim Warteschlangenwechsel meldet Media3 das neue Buch kurz bei 0).

## Geprüft (Emulator, 03.11.2026)

Test-M4B mit 3 Kapiteln (ffmpeg): Kapitelzeile, Liste, ⏮/⏭ in der App und
über Medientasten (0 → 20 → 45 s und zurück), Sitzung meldet Weiter bei nur
einem Titel. Position 22,1 s gemerkt, Musik gehört, Buch setzt bei 22,1 s
fort. „Hörbücher suchen“ fand das Buch in „Audiobooks“ (außerhalb des
Musikordners), „Datei hinzufügen“ ein MP3 aus „Download“, beides in der
Bibliothek; „Aus Hörbüchern entfernen“ nimmt es wieder heraus.
