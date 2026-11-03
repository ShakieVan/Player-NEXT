# Wiedergabe-Architektur

## Dienst und Session

`PlaybackService` ist ein Media3-`MediaLibraryService`. Er besitzt den
ExoPlayer, eine `MediaLibrarySession` (Benachrichtigung, Sperrbildschirm,
Bluetooth, Android Auto) und die Hilfsobjekte. Die UI verbindet sich über
`PlayerHub` mit einem `MediaController`.

Controller schicken nur `mediaId`s. `onAddMediaItems`/`onSetMediaItems`
lösen sie über `QueueItems.resolve` zu abspielbaren Einträgen auf. Jeder
Warteschlangen-Eintrag erhält die ID `songId#uid`, damit derselbe Titel
mehrfach vorkommen kann.

## Sichtbares Mischen (`QueuePlayer`)

PlayerPro mischt die angezeigte Warteschlange. Deshalb sortiert
`QueuePlayer` beim Einschalten die Einträge tatsächlich um (aktueller Titel
zuerst, Rest zufällig) und merkt sich die ursprüngliche Reihenfolge. Beim
Ausschalten wird sie um den laufenden Titel herum wiederhergestellt. Der
laufende Titel wird dabei nie entfernt, die Wiedergabe läuft ohne Aussetzer
weiter. ExoPlayers eigenes Shuffle-Flag bleibt synchron, nutzt aber eine
`UnshuffledShuffleOrder`, damit alle Clients (Benachrichtigung, Auto) den
richtigen Zustand sehen.

Neue Warteschlangen bei aktivem Mischen beginnen mit dem gewählten Titel;
„Als Nächstes“ landet auch in der gespeicherten Originalreihenfolge hinter
dem aktuellen Titel.

## Zufall ohne Wiederholungen (auch bei zehntausenden Titeln)

PlayerPro zeigte bei großen Bibliotheken gefühlt immer dieselben ~100 Titel.
Player NEXT garantiert: Jeder Titel kommt genau einmal, bevor sich einer
wiederholt.

- **Zufällig / Alle zufällig:** Die Warteschlange enthält alle Titel in
  gemischter Reihenfolge – sie ist selbst die Runde. `PlayerHub.play()` schaltet
  das Mischen vor dem Setzen der Liste ein, damit eine Liste mit 30 000
  Einträgen nur einmal gemischt wird.
- **Mit „Alle wiederholen“:** Sobald der letzte Titel einer Runde läuft,
  bekommt alles davor eine neue Reihenfolge (`QueuePlayer.reshuffleBeforeLast`).
  Die nächste Runde ist also neu gemischt und enthält wieder jeden Titel
  einmal; die zuletzt gehörten 20 stehen nicht am Anfang.
- **Partymix:** Statt bei jedem Nachfüllen zufällig aus der Bibliothek zu
  ziehen (was nach wenigen hundert Titeln Wiederholungen bringt), zieht
  `ShuffleBag` aus einer gespeicherten Permutation aller Titel-IDs mit Cursor
  (`files/shuffle_bag.bin`, Cursor in eigenen Einstellungen). Neue Titel
  werden in den noch nicht gespielten Rest gemischt, gelöschte übersprungen.
  Die Reihenfolge übersteht Neustarts. Unit-Tests (`BagOrderTest`) prüfen das
  mit 30 000 Titeln.

## Zufallsmodus „Lange nicht Gehörtes zuerst“ (ab 1.1.1)

Einstellungen → Audio → Zufallswiedergabe: „Gleichmäßig“ (Standard) oder
„Lange nicht Gehörtes zuerst“ mit „Auswahl aus der älteren Hälfte / dem
ältesten Viertel (Standard) / dem ältesten Achtel“. Alle Stellen, an denen
gemischt wird, holen die Reihenfolge aus `Shuffler`: Mischen einer
Warteschlange, „Alle zufällig“ (auch der erste Titel), die neue Runde mit
„Alle wiederholen“ und der Partymix-Beutel.

- **Alter** eines Titels = späterer Zeitpunkt aus „zuletzt gespielt“ und
  „zuletzt übersprungen“ (`StatsStore`); nie gehört = am ältesten. Ein
  übersprungener Titel gilt also auch als gerade gehört.
- **Ablauf** (`AgeOrder.order`): Titel nach Alter sortieren (gleiche Zeit in
  zufälliger Reihenfolge), dann wird jeder nächste Titel zufällig aus den
  `w` ältesten noch übrigen gezogen, `w` = Anteil der Liste, mindestens 5
  (kleine Listen bleiben zufällig). Das ist eine Permutation: Jeder Titel kommt
  genau einmal, die Garantie von oben gilt weiter.
- **Warum ein Fenster:** Streng „Ältestes zuerst“ würde nach einer Runde genau
  dieselbe Reihenfolge noch einmal spielen (das zuerst Gehörte ist dann das
  Älteste). Mit dem Fenster ist jede Runde neu gemischt; ein Titel mit
  Altersrang r kommt frühestens an Position r − w + 1. Nie Gehörtes kommt vor
  allem anderen. Kleineres Fenster = strenger nach Alter.
- **Partymix:** Der Beutel ordnet jede neue Runde nach Alter und speichert den
  Modus (`mode` in den Beutel-Einstellungen). Wechselt der Modus, wird der noch
  nicht gespielte Rest neu geordnet. Innerhalb einer Runde zählt das Alter zu
  ihrem Beginn; ein Titel, der zwischendurch außerhalb des Partymix lief,
  rückt in dieser Runde nicht nach hinten.
- **Aufwand:** einmal Sortieren je Mischen (30 000 Titel: wenige ms), kein
  Hintergrundtakt.

`AgeOrderTest` prüft mit 30 000 Titeln: jede Position genau einmal,
Fenstergrenze, nie Gehörtes zuerst, die zweite Runde wiederholt die erste
nicht, kleine Listen bleiben zufällig, Moduswechsel im Beutel.

## Große Warteschlangen (Lasttest mit 30 020 Titeln)

Gemessen im Emulator (4 GB RAM) mit 30 000 erzeugten Titeln:

| Vorgang | Ergebnis |
| --- | --- |
| „Alle zufällig“ (Kaltstart, Bibliothek laden, mischen, speichern) | spielt, Liste nach ≈ 9 s gespeichert (1,9 MB); die Oberfläche stockt einmal ≈ 1,5 s |
| „Play“ ohne laufenden Dienst (Headset/Widget) | spielt nach ≈ 4 s an derselben Stelle weiter, Reihenfolge unverändert |
| Mischen aus / an | ohne Ruckeln; ursprüngliche Reihenfolge bzw. aktueller Titel vorn |
| Partymix | Mischbeutel mit 30 020 IDs, keine Doppelten |
| Speicher | ≈ 190 MB PSS mit voller Warteschlange |

Befunde, die dabei behoben wurden:

- **System-Warteschlange abgeschnitten.** Media3 übergibt die ganze
  Warteschlange an die System-Mediensitzung (`setQueue`), von dort an
  SystemUI, Bluetooth (AVRCP) und Android Auto – bei jeder Änderung. Mit
  30 000 Einträgen stand der Emulator minutenlang still, der
  Bluetooth-Prozess starb mit `OutOfMemoryError`. Ab
  `PlaybackService.SYSTEM_QUEUE_LIMIT` (500) fehlt dem
  Benachrichtigungs-Controller `COMMAND_GET_TIMELINE`; Media3 gibt ihm dann
  nur den aktuellen Titel (`copyWithSingleWindow`), Benachrichtigung und
  Sperrbildschirm funktionieren weiter. Der Schalter wird vor dem Wachsen der
  Liste gesetzt (`QueuePlayer.Hooks.beforeQueueGrows`, beim Aufbau der Session
  aus der gespeicherten Größe) und erst nach dem Umordnen wieder geprüft.
- **Keine Warteschlangen-Änderung aus `onMediaItemTransition`.** Wird die
  Liste innerhalb des Ereignisses geändert, bekommt die Session ein
  veraltetes Timeline-Ereignis mit neuem Index (`IllegalStateException` in
  `PlayerInfo`). Partymix-Nachfüllen und Neu-Mischen laufen per
  `handler.post` danach; beim Neu-Mischen wird erst eingefügt, dann entfernt.
- **Fortsetzen ohne zweites Mischen.** „Play“ bei beendetem Dienst löste
  Wiederherstellung und Media3-Fortsetzung gleichzeitig aus; die zweite
  Liste wurde neu gemischt. Jetzt stellt `onPlaybackResumption` über
  `restoreNow()` wieder her und gibt genau diese Liste zurück, die
  `QueuePlayer` als unverändert erkennt.
- **`SOURCE_UPDATE` ist keine Änderung.** ExoPlayer meldet beim Vorbereiten
  jedes Titels eine neue Timeline; nur `PLAYLIST_CHANGED` schreibt die Liste
  neu bzw. baut sie in der Oberfläche neu auf.
- `restoreOrder` ohne quadratischen Aufwand, `PlayerHub.queueItems()` baut die
  Liste nur nach einer Änderung neu, „Jetzt läuft“ vergleicht nur dann.

## Crossfading

Media3 kann nicht überblenden. `Crossfader` übergibt den auslaufenden Titel
an einen kurzlebigen zweiten ExoPlayer (gleiche DSP-Kette) ab der aktuellen
Position und blendet ihn aus, während der Haupt-Player schon den nächsten
Titel spielt und eingeblendet wird. Die Session sieht nur den Haupt-Player.

- Automatisch: einmalig geplanter Rückruf `Dauer − Position − Überblendzeit`
  (kein Polling). Direkt nach einem Wechsel ist die Dauer oft unbekannt, dann
  gilt die Dauer aus den Metadaten; zusätzlich wird bei `STATE_READY` neu
  geplant.
- Manuell: `QueuePlayer` ruft vor jedem Nutzer-Sprung `beforeManualSkip`.
- Der Zweit-Player wird 30 s nach der letzten Überblendung freigegeben.

Geprüft im Emulator: Während der Überblendung laufen zwei Audiospuren der App
gleichzeitig (`dumpsys audio`).

## Lautstärke-Gründe (`VolumeMixer`)

Überblenden, Ducking (Audiofokus) und Einschlaf-Timer setzen getrennte
Faktoren; der Player bekommt ihr Produkt. Rampen laufen nur während eines
Übergangs.

## Audiofokus

ExoPlayer verwaltet den Fokus nicht selbst (`handleAudioFocus = false`), weil
PlayerPros drei Schalter einzeln wirken sollen. `AudioFocusController`
unterscheidet eigene Pausen (Fokusverlust) von Nutzerpausen, damit nach einem
vorübergehenden Verlust automatisch fortgesetzt wird.

## Statistik

`PlayStatsTracker` zählt eine Wiedergabe, sobald die Hälfte (oder vier
Minuten) tatsächlich gehört wurde, und einen Sprung, wenn der Nutzer vorher
weiterschaltet. Gezählt wird gehörte Zeit, nicht die Position.

## Warteschlange speichern

`QueueStore` speichert Pfade (nicht MediaStore-IDs), Index, Position,
Zufalls- und Wiederholmodus sowie die Originalreihenfolge. Wiederherstellung
beim Dienststart und für die Media-Wiedergabe-Fortsetzung
(`onPlaybackResumption`).

Seit 1.1.0 in zwei Dateien, beide auf einem Hintergrund-Thread in
Aufrufreihenfolge geschrieben:

- `queue_items.txt` – ein Pfad je Zeile, danach `#original` und die
  ungemischte Reihenfolge als Positionen. Nur neu geschrieben, wenn sich die
  Warteschlange ändert (bei 30 000 Titeln ≈ 2–3 MB).
- `queue_state.json` – Index, Position, Modi, Anzahl, aktueller Pfad; bei
  jedem Titelwechsel und beim Pausieren. Widgets lesen nur diese Datei.

`queue.json` aus 1.0.0 wird beim ersten Speichern ersetzt.

## Widgets

Fünf Widgets (4×1, 2×2, 4×2, 4×4 und „Plus 4×2“ im Aufbau von PlayerPro:
Position, Titel, Interpret, Album, ⏪ ▶ ⏩). Hintergrund und Cover je Widget
einstellbar (`WidgetConfigActivity`, beim Platzieren, über „Einstellungen“
des Launchers und für alle Widgets in den App-Einstellungen). Der Hintergrund
ist ein eigenes `ImageView` (`w_bg`), dessen Bild-Alpha gesetzt wird.

Ein Tipp außerhalb der Tasten öffnet „Jetzt läuft“ (`w_root`). Die Tasten
schicken Broadcasts an den eigenen Provider, nicht an Media3s
`MediaButtonReceiver`: der verwirft ohne laufenden Dienst alles außer „Play“,
und Play/Pause liefe durch die Headset-Logik. Läuft der Dienst, steuert der
Provider den Player direkt; sonst startet „Play“ den Dienst über dessen
Medientasten-Pfad im Vordergrund, und ⏪/⏩ verschieben nur den gespeicherten
Titel (`QueueStore.step`). Aktualisierungen werden 120 ms gebündelt, das
Cover des aktuellen Titels bleibt zwischengespeichert.

## Benachrichtigung

Media3 erzeugt Zurück/Play/Weiter selbst. Player NEXT liefert nur die zwei
konfigurierbaren Zusatztasten als `CommandButton`s. „Zurück-Taste aus“ wird
umgesetzt, indem dem Benachrichtigungs-Controller die Zurück-Befehle fehlen.
