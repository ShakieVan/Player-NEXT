# Tag-Editor

## Ablauf

1. `TagIO.read` liest direkt aus dem Dateipfad (Lesezugriff über
   `READ_MEDIA_AUDIO`), sonst über eine private Kopie.
2. Beim Speichern fragt `TagIO.requestWriteAccess` mit
   `MediaStore.createWriteRequest` einmal für alle gewählten Titel nach
   Erlaubnis. Eine breite Speicherberechtigung ist nicht nötig.
3. `TagIO.write` kopiert jede Datei in den Cache, ändert sie dort mit
   jaudiotagger, schreibt sie über `openOutputStream(uri, "wt")` zurück und
   stößt den Medienscanner an. Die Originaldatei wird nie halb geschrieben.

Stapelbearbeitung: Felder mit unterschiedlichen Werten zeigen
„&lt;verschiedene&gt;“ und werden nur geschrieben, wenn sie geändert wurden.

## Geprüft

- Emulator (Android 16): Titel geändert, Android-Dialog „Darf … diese
  Audiodatei ändern?“ bestätigt, MediaStore meldet danach den neuen Titel,
  die Liste aktualisiert sich.
- Release-Build mit R8: Lesen funktioniert (Keep-Regeln für jaudiotagger in
  `proguard-rules.pro`).

## Hinweise

- jaudiotagger läuft im Android-Modus (`TagOptionSingleton.isAndroid = true`),
  damit Cover ohne `java.awt` verarbeitet werden.
- Bewertungen können optional als POPM (MP3, Skala 0–255 wie Windows Media
  Player) bzw. `RATING` (0–100) geschrieben werden.
- Liedtexte: eingebettet (`LYRICS`/USLT) oder `.lrc` neben der Datei; die
  Zeitstempel einer `.lrc` werden für die Anzeige entfernt.
- Auf gesperrten Testhandys erscheint der Freigabedialog nicht über der
  Sperre; Schreibtests deshalb im Emulator.
