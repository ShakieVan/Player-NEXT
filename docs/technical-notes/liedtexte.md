# Liedtexte

## Quellen und Vorrang

`TagIO.readLyrics` liest beide Quellen und gibt ein `tags/Lyrics` zurück:

1. **Eingebettet:** das Liedtext-Feld des Tags (`FieldKey.LYRICS`: MP3 `USLT`,
   FLAC/OGG `LYRICS`, M4A `©lyr`). Ersatzweise `UNSYNCEDLYRICS`
   (foobar2000) und ein `TXXX`-Rahmen „lyrics…“. Den schreibt ffmpeg mit
   `-metadata lyrics-deu=…` in MP3 statt eines USLT; im Test fiel das zuerst
   als „keine Liedtexte“ auf.
2. **`.lrc` neben der Datei** mit gleichem Namen (`Lied.mp3` → `Lied.lrc`).
   Kodierung: UTF-8 oder UTF-16 mit BOM, sonst UTF-8, ersatzweise
   Windows-1252.

Mitlaufende Texte (mit Zeitstempeln) gehen vor festen. Sind beide gleich,
entscheidet die Einstellung „Liedtexte aus dem ID3-Tag bevorzugen“.

**Zugriff auf `.lrc` ab Android 11:** MediaStore führt `.lrc` als Untertitel
(`application/lrc`, `media_type` 5). Mit `READ_MEDIA_AUDIO` darf die App sie
über den Dateipfad lesen, ohne breiten Speicherzugriff. Das ist im Emulator
mit Android 16 geprüft, auf einem Samsung noch nicht.

## Format (LRC)

```
[ti:Titel]
[ar:Interpret]
[offset:+200]
Zeile ohne Zeit
[00:12.00]Erste Zeile
[00:17.20]Zweite Zeile
[00:21.10][01:45.30]Refrain (zweimal)
Folgezeile ohne eigene Zeit
[01:02.00]
```

Diese Regeln setzt `Lyrics.parse` um, die Unit-Tests stehen in `LyricsTest`:

- Zeitstempel `[m:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]` und
  `[mm:ss:xx]`, Minuten bis dreistellig. Mehrere Zeitstempel am Zeilenanfang
  lassen die Zeile mehrfach erscheinen.
- Kopfzeilen (`ti`, `ar`, `al`, `au`, `by`, `length`, `re`, `ve`, `tool`, `la`,
  `id`, `offset`) werden nicht angezeigt. Ein positiver `offset` (ms) zieht
  alle Zeilen nach vorn.
- Wort-Zeitstempel `<mm:ss.xx>` (Enhanced LRC) werden entfernt. Hervorgehoben
  wird zeilenweise.
- Eine Zeile ohne Zeit innerhalb eines mitlaufenden Textes gehört zur Zeile
  mit Zeit darüber und leuchtet mit ihr. Zeilen vor dem ersten Zeitstempel
  leuchten nie.
- Eine leere Zeile mit Zeitstempel markiert Zwischenspiel oder Ende: Dann
  leuchtet keine Zeile.
- Als mitlaufend gilt ein Text nur, wenn mindestens so viele Zeilen einen
  Zeitstempel tragen wie nicht leere Zeilen keinen. Abschnittsmarken wie
  `[Verse]` sind keine Zeitstempel.

Der Dialog „Liedtext anzeigen“ zeigt den Text ohne Zeitstempel in der
Reihenfolge der Datei. Der Editor zeigt den gespeicherten Text samt
Zeitstempeln, damit Speichern die Synchronisierung erhält. Er schreibt immer
in das eingebettete Feld.

## Anzeige in „Jetzt läuft“

- Eine Zeile je Liedzeile in einer `TextView`. Die laufende Zeile färbt ein
  einziger `ForegroundColorSpan` in `?attr/pnAccent`, die übrigen sind gedimmt.
  Da sich nur die Farbe ändert, wird nichts neu umbrochen.
- Die laufende Zeile scrollt sanft auf ein Drittel der Höhe. Oben und unten
  ist Polster, damit auch die erste und die letzte Zeile dorthin kommen. Wer
  selbst scrollt, behält seine Stelle für 4 s.
- Kein fester Takt: `syncLyrics` berechnet die aktuelle Zeile aus der Position
  und stellt einen einzigen Rückruf auf den Beginn der nächsten Zeile (geteilt
  durch die Geschwindigkeit). Play, Pause, Spulen und Titelwechsel kommen über
  `refreshAll` und rechnen neu. Ist der Text zugeklappt, die Warteschlange
  offen oder der Bildschirm nicht im Vordergrund, läuft nichts.
- Messung im Emulator (`dumpsys gfxinfo`, 10 s): Liedtext sichtbar mit drei
  Zeilenwechseln 62 Bilder, zugeklappt 10, pausiert 0. Ein Zeilenwechsel
  kostet also rund 17 Bilder (Scroll-Animation). Ohne Scrollbalken; mit ihm
  waren es 110 Bilder, weil er bei jedem Wechsel ein- und ausblendete.

Nicht umgesetzt: ID3-`SYLT` (synchronisierte Liedtexte als eigener Rahmen,
kaum verbreitet) und Antippen einer Zeile zum Springen.
