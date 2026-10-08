# Entwicklung

## Baseline Profile (Startgeschwindigkeit)

Die App liefert ein Baseline Profile mit: ART kompiliert damit die Klassen und Methoden des
Hauptwegs (Start → Merkliste → Scrollen → Aktionsblatt) schon bei der Installation vor.
`androidx.profileinstaller` installiert es auch, wenn die App nicht aus dem Play Store kommt.

- `app/src/main/baseline-prof.txt` — von Hand geschriebenes Start-Profil (Paket-Platzhalter
  für Merkliste, Navigation, Theme, Room-Daten). Gilt immer, auch ohne Generator.
- `app/src/release/generated/baselineProfiles/` — gemessenes Profil des Generators (Modul
  `:baselineprofile`, `BaselineProfileGenerator`). Wird zusätzlich eingebunden.

### Neu erzeugen

Nötig nach grösseren Änderungen an Start, Merkliste oder Aktionsblatt.

1. Gerät (Android 9 / API 28 oder neuer) anschliessen oder Emulator starten
   (`adb devices` zeigt es). Ein Emulator ohne Google Play genügt.
2. `./gradlew :app:generateBaselineProfile`
   - baut die App als `nonMinifiedRelease`, installiert sie, startet sie mehrmals kalt,
     übernimmt bei leerer Merkliste die Start-Coins (braucht Internet), scrollt und öffnet
     ein Aktionsblatt;
   - schreibt das Ergebnis nach `app/src/release/generated/baselineProfiles/`.
3. Die erzeugten Dateien einchecken. `baseline-prof.txt` von Hand nur anpassen, wenn
   Pakete umbenannt wurden.

Prüfen, ob ein Gerät das Profil bekommen hat:
`adb shell dumpsys package dexopt | grep -A1 com.cryptochecker.app` (Status `speed-profile`).

### Kennungen für den Generator

Der Generator findet die Oberfläche über Compose-`testTag`s, die als Ressourcen-Id sichtbar
sind (`testTagsAsResourceId` im Scaffold der Merkliste): `watchlist` (Liste), `watch_row`
(Zeile), `starter_add` (Start-Coins übernehmen). Beim Umbenennen beide Seiten anpassen.

## App-Start messen

Die App misst lokal (keine Telemetrie) die Zeit vom Start bis zum ersten Bild der Merkliste
aus dem Zwischenspeicher — kalt ab Prozessstart, sonst ab dem Aufbau der Activity — und zeigt
den letzten Wert im Bericht «Letzte Aktualisierung» unter «Ablauf» («App-Start 0,4 s»).
`reportFullyDrawn()` wird zum selben Zeitpunkt gemeldet (`adb logcat | grep Fully`).
