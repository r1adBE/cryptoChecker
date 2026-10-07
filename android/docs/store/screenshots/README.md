# Screenshot-Kit für Google Play und App Store

Version 16.2.2 (Build 17). Dieses Kit enthält alles, um die Store-Screenshots in allen Sprachen gleich aussehen zu lassen:

| Datei | Zweck |
|---|---|
| `demo-backup.json` | Demo-Daten zum Wiederherstellen (Android und iOS) |
| `captions.json` | Titel (max. 40 Zeichen) und Untertitel (max. 60 Zeichen) pro Szene, 31 Sprachen (inkl. pt-BR) |
| `../../../tools/frame_screenshots.py` | setzt Hintergrund, Text und Screenshot zum fertigen Store-Bild zusammen |
| `raw/<locale>/<NN>.png` | deine rohen Screenshots (nicht im Repo) |
| `out/<plattform>/<locale>/<NN>.png` | fertige Bilder (nicht im Repo) |

## Die 6 Szenen

| Nr. | Szene | Was zu sehen ist |
|---|---|---|
| 01 | Merkliste mit CHF + Mini-Chart | Tab «Merkliste»: BTC/USDT, ETH/USDT, SOL/USDT (Binance), zweite Zeile «≈ … CHF», Mini-Chart in jeder Zeile, Gruppe «Layer 1» |
| 02 | Alarm-Editor mit Satz | Alarm von BTC/USDT öffnen: «Sag mir Bescheid, wenn BTC unter 90 000 CHF fällt.» – Währung CHF, Knopf «Alarm testen» sichtbar |
| 03 | «Warum bewegt sich das?» | Bei BTC oder SOL «Warum bewegt sich das?» öffnen, «Kurz gesagt» (Kurzfazit) oben im Bild |
| 04 | Crypto Pulse | Karte «Crypto Pulse – Was passiert gerade?» aufgeklappt: BTC/ETH/SOL, Altcoins gegenüber Bitcoin, Volumen, Fear & Greed, Funding, ETH-Gas, Fazit, Hinweis «Keine Prognose» |
| 05 | Portfolio | Tab «Portfolio»: 0,05 BTC, 1,2 ETH, 10 SOL, Gesamtwert in CHF |
| 06 | Widgets | Startbildschirm mit Merklisten-, Portfolio- und Einzel-Widget (iOS zusätzlich Sperrbildschirm mit Live-Aktivität) |

Dateinamen: `01.png` … `06.png`, pro Sprache ein Ordner, z. B. `raw/de-DE/01.png`. Als Ordnernamen gehen die Namen aus `fastlane/metadata/android` (z. B. `de-DE`, `iw-IL`, `zh-CN`) und die App-Store-Namen (z. B. `he`, `zh-Hans`, `ar-SA`); das Skript ordnet sie selbst zu.

## Vorbereitung

### 1. Demo-Daten wiederherstellen

1. `demo-backup.json` aufs Gerät kopieren (Android: z. B. `adb push demo-backup.json /sdcard/Download/`; iOS-Simulator: Datei ins Simulator-Fenster ziehen oder in «Dateien» ablegen).
2. In der App: Einstellungen → Sichern & Wiederherstellen → Wiederherstellen → Datei wählen.

Achtung: Die Wiederherstellung **ersetzt** Merkliste, Alarme, Favoriten, Portfolio und Einstellungen. Nur auf einem Test-Gerät oder Simulator machen.

Inhalt der Sicherung:

- Merkliste: BTC/USDT, ETH/USDT, SOL/USDT auf Binance (Börsen-Schlüssel `Binance`, Paar-Id `BTCUSDT` usw.), alle in der Gruppe «Layer 1»
- Notiz bei BTC: «Ø 77 500 USDT» (sprachneutral)
- Alarm: BTC/USDT unter 90 000 CHF (Alarmwährung CHF)
- Portfolio: Kauf 0,05 BTC zu 77 500 USDT (8.4.2025), 1,2 ETH zu 2400 USDT (20.6.2025), 10 SOL zu 165 USDT (1.8.2025)
- Einstellungen: Umrechnungswährung CHF, umgerechnete Kurse an, Mini-Chart an, Portfolio an, heller Modus, Akzent Orange, Kursfarben Grün/Rot, keine dauernde Kurs-Mitteilung, Portfolio-Sperre aus, Nachtruhe aus

«Farben tauschen» ist bewusst **nicht** in der Sicherung: In China, Japan, Korea und Taiwan bleibt so die Gerätevorgabe (Rot steigend) erhalten. Wer für `ja-JP`, `ko-KR` und `zh-CN` die ostasiatischen Farben zeigen will, prüft das in den Einstellungen.

Kurse kommen live von Binance. Binance sperrt Anfragen aus den USA – Geräte oder Simulatoren mit US-IP zeigen dann keine Kurse. In dem Fall über ein Netz ausserhalb der USA aufnehmen.

### 2. Umrechnungswährung und Darstellung prüfen

- Einstellungen → Umrechnungswährung: **CHF**, «Umgerechnete Kurse» an (kommt aus der Sicherung, trotzdem kurz prüfen).
- Heller Modus (Sicherung setzt ihn; Systemeinstellung des Geräts ebenfalls hell).
- Akzentfarbe **Orange** (Einstellungen → Darstellung; kommt aus der Sicherung). Orange ist die Markenfarbe – siehe «Markenfarbe» unten.
- Gerätesprache auf die jeweilige Sprache stellen, App neu starten.
- Keine echten Benachrichtigungen, keine privaten Daten im Bild.

### 3. Statusleiste aufräumen

**Android (Emulator oder Gerät mit Entwickleroptionen):**

```sh
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# Aufnahme:
adb exec-out screencap -p > raw/de-DE/01.png
# danach:
adb shell am broadcast -a com.android.systemui.demo -e command exit
```

**iOS-Simulator:**

```sh
xcrun simctl status_bar booted override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100
xcrun simctl io booted screenshot raw/de-DE/01.png
xcrun simctl status_bar booted clear
```

Sprache im Simulator: `xcrun simctl spawn booted defaults write -g AppleLanguages -array de-CH` (danach App neu starten) oder in den Einstellungen des Simulators.

## Grössen

Das Skript erzeugt die Zielgrössen selbst; die rohen Screenshots sollten mindestens so scharf sein wie das Ziel (am besten direkt vom passenden Gerät/Simulator).

| Store | Plattform-Name im Skript | Grösse (px) | Pflicht? |
|---|---|---|---|
| Google Play – Telefon | `play-phone` | 1080 × 1920 (9:16, kurze Seite ≥ 1080) | ja, 2–8 Bilder |
| Google Play – Tablet 7" | `play-tablet7` | 1200 × 1920 | optional |
| Google Play – Tablet 10" | `play-tablet10` | 1600 × 2560 | optional |
| App Store – iPhone 6,9" | `ios-iphone69` | 1320 × 2868 | ja |
| App Store – iPad 13" | `ios-ipad13` | 2064 × 2752 | nur wenn iPad aktiviert bleibt |

Rohe Aufnahmen dafür: Android-Telefon (z. B. Pixel-Emulator 1080 × 2400), iPhone 16 Pro Max / 17 Pro Max Simulator (1320 × 2868), iPad Pro 13" Simulator (2064 × 2752). Die fertigen PNGs haben keinen Alpha-Kanal (App Store lehnt Transparenz ab).

## Rahmen setzen

```sh
cd cryptoChecker
python3 -m pip install Pillow          # einmalig; Wheels von PyPI enthalten libraqm
python3 tools/frame_screenshots.py                                    # alle Sprachen in raw/, Telefon + iPhone 6,9"
python3 tools/frame_screenshots.py --platforms play-phone play-tablet10 ios-iphone69 ios-ipad13
python3 tools/frame_screenshots.py --locales de-DE en-US ar           # nur einzelne Sprachen
python3 tools/frame_screenshots.py --font /pfad/NotoSans-Bold.ttf     # eigene Schrift
python3 tools/frame_screenshots.py --align center                     # Text zentriert statt bündig
```

- Schrift: automatisch über fontconfig – Inter (Latein, Griechisch, Kyrillisch), Noto Sans CJK (ja/ko/zh), Noto Sans Arabic/Hebrew/Devanagari/Thai, wenn installiert, sonst DejaVu Sans, FreeSans bzw. Loma. Fehlt fontconfig (macOS, Windows), `--font` angeben.
- Rechts-nach-links: Bei `ar`, `fa`, `iw-IL`/`he` steht der Text rechtsbündig und wird mit libraqm korrekt gesetzt.
- Lange Titel werden erst verkleinert, dann auf zwei Zeilen umbrochen.
- Texte ändern: nur `captions.json` bearbeiten (Titel ≤ 40, Untertitel ≤ 60 Zeichen).

## Markenfarbe

Orange ist die Erkennungsfarbe von Crypto Checker: App-Icon (Glocke), Standard-Akzent bei neuen Installationen, Rahmen-Hintergrund von `frame_screenshots.py` (#DD6F48 → #BE532C) und die Feature-Grafiken in `docs/store/`. **Alle Marketing-Bilder** (Screenshots, Feature-Grafik, Promo-Bilder) zeigen deshalb den Akzent Orange und das orange Icon.

Blau, Grün, Rot und Marrs Green sind optionale Themes, die man in der App wählen kann. Sie können im Beschreibungstext erwähnt werden, gehören aber nicht in die Store-Bilder. Die alten blauen Globus-Grafiken sind nicht mehr Teil der Marke und dürfen nicht verwendet werden.

## Inhaltliche Regeln

- **Keine Börsen-Logos** und keine Geräterahmen echter Marken. Börsennamen nur als Text, so wie die App sie zeigt.
- Nur Funktionen zeigen, die der Build wirklich hat. Vor der Aufnahme prüfen, ob Crypto Pulse, «Alarm testen», das Kurzfazit bei «Warum bewegt sich das?» und (iOS) die Live-Aktivität in genau diesem Build vorhanden sind – sonst Szene weglassen oder ersetzen.
- Beobachtend formulieren: keine «Kaufsignale», «Trading-Chancen» oder Gewinnversprechen. Crypto Pulse und der Markt-Tab zeigen den Markt, sie sagen nichts voraus.
- Keine Spenden-Adressen, keine echten Bestände, keine persönlichen Mitteilungen im Bild.
