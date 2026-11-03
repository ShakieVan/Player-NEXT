# Entscheidung: offline, ohne eigene Sperrbildschirm-App

## Offline

Player NEXT enthält keine Online-Funktionen: keine Cover-, Interpreten- oder
Genrebilder aus dem Internet, keine Liedtext-Suche, keine Radios, kein
Chromecast, keine Scrobbler, keine Google-Drive-Sicherung.

**Einzige Ausnahme (ab 1.1.0, Wunsch des Nutzers): die Update-Funktion** wie
in Mau-Mau Flip. Nur `update/Updater.kt` nutzt das Internet: höchstens einmal
am Tag beim Start (abschaltbar) eine Anfrage an
`api.github.com/repos/ShakieVan/Player-NEXT/releases/latest` und, nur auf
Wunsch, der Download der APK von GitHub. Dafür deklariert die App
`INTERNET` und `REQUEST_INSTALL_PACKAGES`. Es werden keine Daten über den
Nutzer oder die Musik übertragen; GitHub sieht nur die Anfrage selbst
(IP-Adresse, `User-Agent: Player-NEXT/<Version>`). Siehe
[`../technical-notes/updates.md`](../technical-notes/updates.md).

Ersatz: eingebettete Cover und eigene Bilder aus der Galerie, Collagen für
Genres/Komponisten/Playlists, Liedtexte aus Tags oder `.lrc`, Sicherung als
Datei über den Systemdialog.

## Kein eigener Sperrbildschirm

PlayerPro bietet eine eigene Sperrbildschirm-Activity. Unter Android 14+
dürfen Apps Activities nicht mehr aus dem Hintergrund starten (auch nicht aus
einem Medien-Vordergrunddienst), und Vollbild-Intents sind Telefon-/Wecker-Apps
vorbehalten. Player NEXT nutzt daher die Mediensteuerung des Android-
Sperrbildschirms, die über die `MediaSession` Cover, Titel und Tasten zeigt.

## Autostart bei Kopfhörer/Bluetooth

Die Wiedergabe startet beim Verbinden nur, solange der Dienst läuft. Android
erlaubt einen Vordergrunddienst-Start aus einem Verbindungs-Broadcast nicht;
die Medientaste (Bluetooth „Play“) setzt die letzte Warteschlange dagegen über
`onPlaybackResumption` fort.
