# Update-Funktion

Wie in Mau-Mau Flip, in Kotlin: `update/Updater.kt` (Ablauf und Status),
`update/UpdateRelease.kt` (Prüfung der GitHub-Antwort),
`update/UpdateInstaller.kt` (Android-Teil), Oberfläche in
`update/UpdateActivity.kt` (Einstellungen → Über → Updates).

## Ablauf

1. **Prüfen** – `GET https://api.github.com/repos/ShakieVan/Player-NEXT/releases/latest`
   (12 s Zeitlimit). Automatisch höchstens einmal am Tag beim App-Start,
   abschaltbar („Automatisch nach Updates suchen“), sonst über „Jetzt prüfen“.
   Der Prüfzeitpunkt wird nur nach einer vollständigen Antwort gespeichert;
   ein Fehlschlag (kein Netz, GitHub-Limit) sperrt die nächste automatische
   Prüfung also nicht für einen Tag. 404 (noch kein Release) gilt als Antwort.
2. **Angebot** – nur ein fertiges Release (kein Entwurf, keine Vorabversion)
   mit Tag `vX.Y.Z` und genau einer APK `Player-NEXT-X.Y.Z.apk`, deren Größe
   (≤ 256 MB) und `digest` (`sha256:…`) GitHub angibt und deren Adresse
   `https://github.com/ShakieVan/Player-NEXT/releases/download/vX.Y.Z/…` ist.
   Neuer heißt: numerisch höhere Version. Die App weist beim Start einmal per
   Snackbar darauf hin.
3. **Herunterladen** – nur auf Knopfdruck, nach `files/updates/<sha256>.apk.part`.
   Abbruch erst, wenn 30 s lang kein Byte ankommt (Lesezeitlimit), nicht nach
   einer Gesamtzeit. SHA-256 wird beim Laden berechnet; passt Größe oder
   Prüfsumme nicht, wird die Datei gelöscht.
4. **Installieren** – Recht „Apps installieren“ (sonst öffnet sich die
   Einstellung), dann prüft `UpdateInstaller.verify`: Paketname, höhere
   `versionCode`, `versionName` gleich der angekündigten Version, identische
   Signatur wie die installierte App. Erst dann startet der Android-Installer
   über den FileProvider. Eine abgelehnte Datei wird gelöscht.
5. **Ausweg** – „Im Browser öffnen“ führt zur Release-Seite; dort lässt sich
   die APK auch ohne den Updater laden.

Alte Downloads werden beim Start entfernt; nur die APK des offenen Updates
bleibt liegen.

## GitHub-Limit

Ohne Anmeldung erlaubt GitHub 60 Anfragen je Stunde und IP-Adresse; im
geteilten WLAN (Hotel, Urlaub) teilen sich alle Geräte dieses Limit. Bei 403
oder 429 zeigt die App „GitHub-Limit erreicht – später erneut versuchen“ mit
der Wartezeit aus `retry-after` bzw. `x-ratelimit-reset`, nicht „Keine
Verbindung“.

## Sperrbildschirm

Systemdialoge (Installer, „Unbekannte Apps installieren“) erscheinen nie über
dem Sperrbildschirm. Liegt ein Debug-Bau dank Testschalter
`PlayerNextTestLock` darüber, nimmt `UpdateInstaller` das vor dem Dialog
zurück, bittet per `requestDismissKeyguard` ums Entsperren und startet den
Dialog erst danach. `BaseActivity` legt die App danach nicht mehr über die
Sperre.

## Release veröffentlichen

1. Version in `app/build.gradle.kts` (`appVersion`) erhöhen; `versionCode`
   ergibt sich als X·1 000 000 + Y·1 000 + Z.
2. `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease`
3. APK als `release-artifacts/Player-NEXT-X.Y.Z.apk` ablegen.
4. `docs/releases/vX.Y.Z.md` schreiben, committen, Tag `vX.Y.Z` pushen.
5. `gh release create vX.Y.Z release-artifacts/Player-NEXT-X.Y.Z.apk --title "Player NEXT X.Y.Z" --notes-file docs/releases/vX.Y.Z.md`

Signiert wird mit dem Release-Schlüssel aus `key.properties` (nicht im
Repository). **Ohne denselben Schlüssel lässt sich kein Update über eine
installierte Version installieren** – den Schlüssel sichern.

Zum Ausprobieren gegen das neueste Release eine ältere Version bauen:
`.\gradlew.bat :app:assembleRelease "-PpnVersion=1.0.99"` – in PowerShell mit
Anführungszeichen, sonst zerlegt PowerShell das Argument am Punkt und es
entsteht still die normale Version.

## Geprüft

03.11.2026 im Emulator (Android 16) gegen das echte Release v1.1.0: 1.0.99
findet beim Start 1.1.0, lädt 7,0 MB, Prüfsumme passt, die Einstellung
„Unbekannte Apps installieren“ öffnet sich, danach Paket-/Signaturprüfung und
Android-Installer → „App wurde installiert.“ Auf dem S21 (Android 15) mit dem
Debug-Bau: Prüfung erreicht GitHub („Noch kein passendes Release
veröffentlicht.“ vor dem ersten Release).

Hinweis für Tests: Nach „Aktualisieren“ prüft Play Protect die APK einige
Sekunden. Wird in dieser Zeit das Installationsrecht der App geändert, beendet
Android die App samt Installer – die Installation bricht dann ab.
