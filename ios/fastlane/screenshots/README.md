# Screenshots für den App Store

In diesem Ordner liegen **bewusst keine** Bilder. Apple verlangt, dass Screenshots
die App so zeigen, wie sie tatsächlich läuft (Richtlinie 2.3.3). Nachgebaute oder
erfundene Oberflächen führen zur Ablehnung – deshalb müssen die Screenshots aus
der echten App (Simulator oder iPhone/iPad) stammen.

Fertige Bilder hier ablegen, dann kann *fastlane deliver* sie hochladen. Von Hand
in App Store Connect hineinziehen geht genauso.

## Welche Grössen nötig sind

Das Projekt ist **nur für das iPhone** eingestellt
(`TARGETED_DEVICE_FAMILY = "1"`, Entscheid für die erste Einreichung). Darum
verlangt App Store Connect nur iPhone-Screenshots:

| Gerät | Pflicht? | Auflösung (Hochformat, Pixel) | Simulator |
|---|---|---|---|
| iPhone 6,9" | **ja** | 1320 × 2868 (oder 1290 × 2796, 1260 × 2736) | iPhone 16 Pro Max / iPhone 17 Pro Max |
| iPhone 6,5" | nur wenn keine 6,9"-Bilder vorhanden | 1284 × 2778 oder 1242 × 2688 | iPhone 11 Pro Max / XS Max |
| iPad 13" | nein (erst nötig, wenn iPad später aktiviert wird) | 2064 × 2752 oder 2048 × 2732 | iPad Pro 13-inch (M4 oder neuer) |

* Je Gerätegruppe und Sprache **1 bis 10** Bilder, PNG oder JPEG, **ohne
  Transparenz** (RGB, flach).
* Kleinere iPhones und iPads skaliert Apple automatisch aus den grossen Bildern.
* Fehlen Screenshots für eine Sprache, zeigt der Store die der Hauptsprache
  (en-US). Für den Anfang reichen also Englisch und Deutsch.
* iPad später aktivieren: `TARGETED_DEVICE_FAMILY` auf `"1,2"` in
  `tools/gen_xcodeproj.py` und `project.yml`, dann iPad-Screenshots 13" ergänzen.
  Einmal mit iPad veröffentlicht, lässt sich das nicht mehr zurücknehmen.

## Ordnerstruktur für fastlane

```
fastlane/screenshots/
  en-US/
    01_watchlist.png        (1320 × 2868 → iPhone 6,9")
    02_why.png
    ...
  de-DE/
    01_watchlist.png
    ...
```

Die Ordnernamen sind dieselben Sprachcodes wie unter `fastlane/metadata/`.
fastlane erkennt das Gerät an der Bildgrösse; die Reihenfolge ergibt sich aus dem
Dateinamen.

## So entstehen die Bilder (Simulator)

1. In Xcode oben das Ziel **iPhone 16 Pro Max** (bzw. 17 Pro Max) wählen, App mit
   ⌘R starten.
2. Statusleiste aufräumen (Uhrzeit 9:41, voller Akku, volles Netz) – im Terminal:

   ```sh
   xcrun simctl status_bar booted override --time 9:41 --batteryState charged \
     --batteryLevel 100 --cellularBars 4 --wifiBars 3
   ```

3. Demo-Daten einspielen: `demo-backup.json` aus dem Screenshot-Kit ins
   Simulator-Fenster ziehen und unter Einstellungen › Sichern & Wiederherstellen
   wiederherstellen (Merkliste BTC/ETH/SOL, Alarm, Portfolio, CHF).
4. Screenshot: im Simulator **⌘S** (Datei landet auf dem Schreibtisch, in voller
   Auflösung) oder im Terminal:

   ```sh
   xcrun simctl io booted screenshot ~/Desktop/01_watchlist.png
   ```

5. Für andere Sprachen: *Product › Scheme › Edit Scheme… › Run › Options › App
   Language* umstellen, oder im Simulator *Einstellungen › Crypto Checker ›
   Sprache*.
6. (Nur falls iPad später aktiviert wird: dasselbe mit dem Simulator **iPad Pro 13-inch**.)
7. Transparenz entfernen (Simulator-PNGs können einen Alphakanal haben), z. B.
   auf dem Mac:

   ```sh
   for f in *.png; do sips -s format jpeg -s formatOptions 95 "$f" --out "${f%.png}.jpg"; done
   ```

## Motive (Reihenfolge)

Dieselben fünf Szenen wie bei Google Play – Demo-Daten, Beschriftungen in allen
Sprachen (`captions.json`, Tabellen im Kit-README) und das Rahmen-Skript liegen im Screenshot-Kit
`../../../android/docs/store/screenshots/README.md`. Leitidee: «Crypto Checker erklärt dir,
was im Markt passiert.»

1. «Den Markt verstehen, ohne Lärm» – Merkliste mit «≈ … CHF» und Mini-Chart (vorher unter Einstellungen › Merkliste den Mini-Chart einschalten – bei Neuinstallation aus; Coin-Logos «In der App» sind ab Werk an)
2. «Warum bewegt sich das?» – das Blatt mit «Kurz gesagt»
3. «Deine Merkliste, deine Börsen» – Seite «Paar hinzufügen» («+» neben der Lupe in der Merkliste) mit der Börsenauswahl
4. «Alarme, wenn es zählt» – Alarm-Editor mit Satz und «Alarm testen»
5. «Dein Portfolio – geschützt» – Portfolio-Tab mit eingeschalteter Portfolio-Sperre
   (Face ID/Touch ID oder Code; die Demo-Sicherung lässt die Sperre aus, also vorher einschalten);
   das Auge oben («Beträge verbergen», Beträge als «•••») darf mit im Bild sein

Dateinamen für fastlane z. B. `01_watchlist.png`, `02_why.png`, `03_exchanges.png`,
`04_alarm.png`, `05_portfolio.png`.

Tipps: keine privaten Mitteilungen im Bild, keine Gewinnversprechen in den
Beschriftungen. Text auf den Bildern (Rahmen, Überschriften) ist erlaubt, solange
die echte App-Oberfläche zu sehen ist.

## Markenfarbe Orange

Orange ist die Erkennungsfarbe von Crypto Checker (App-Icon, Standard-Akzent bei
neuen Installationen). **Alle Marketing-Bilder** – Screenshots, Promo-Bilder,
Rahmen – zeigen den Akzent **Orange** (Einstellungen › Darstellung › Farbe; die
Demo-Sicherung `../android/docs/store/screenshots/demo-backup.json` setzt
ihn) und das orange Icon. Blau, Grün und Rot sind optionale Themes; sie dürfen im
Text erwähnt werden, gehören aber nicht in die Store-Bilder. Die alten blauen
Globus-Grafiken sind nicht mehr Teil der Marke.

## App-Icon

`fastlane/metadata/app_icon.png` (orange Glocke) ist eine Kopie von
`App/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png` (1024 × 1024,
RGB, ohne Alphakanal) – nur als Referenz für Marketing. App Store Connect nimmt
das Icon seit Xcode 9 **aus dem hochgeladenen Build** (Asset-Katalog); es muss
nicht separat hochgeladen werden.
