# Crypto Checker für iOS

**Crypto Checker erklärt dir, was im Markt passiert.** Kurse direkt von der
Börse, Marktüberblick und «Warum bewegt sich das?» – ohne Prognosen, keine
Anlageberatung.

Native SwiftUI-Version der Android-App Crypto Checker (Version 16.2.2, Build 17).
Mindestens iOS 17. Die App enthält eine Widget-Erweiterung (WidgetKit).

Der Quellcode ist öffentlich (MIT): <https://github.com/r1adBE/cryptoChecker>, Ordner `ios/`
(die Android-App liegt daneben in `android/`). Ein Simulator-Build ohne Signatur lässt sich dort unter
*Actions › iOS › Run workflow* starten; Kontaktdaten aus `fastlane/metadata/review_information` werden nie hochgeladen.

## Neu in 16.2.2

Gleicher Funktionsumfang wie Android 16.2.2, u. a.:

Zuletzt dazugekommen:

- **Coin-Logos** von CoinGecko statt nur Initialen (Merkliste, Aktionsblatt, Portfolio, Markt,
  «Paar hinzufügen», Widgets Merkliste und Einzel-Coin), alle Logos der Rangliste (Top 1000, Lücken wie Gold,
  Silber, bStocks aus der Binance-Symbolliste, danach Rang 1001–2500; Aktien-Logos von Binance `static/stock/…png`
  für alle TradFi-Kürzel der gespeicherten Paarlisten) auf einmal geladen (nie einzeln) und im App-Group-Ordner gespeichert,
  Schalter «In der App» / «Im Portfolio» (ab Werk an) / «In Widgets» (ab Werk aus) unter
  Einstellungen › Darstellung › Coin-Logos
  (`Shared/Util/CoinLogos.swift`, `Shared/Storage/CoinLogoStore.swift`, `Widgets/WidgetLogos.swift`).
- **Live-Kurse per WebSocket** in der geöffneten Merkliste (Binance, Bybit, OKX, Coinbase, Kraken;
  REST bleibt Rückfall), «LIVE» in der Status-Pille, je Tick wird nur die betroffene Zeile neu
  gezeichnet (`App/Services/LivePriceStream.swift`, `Shared/Services/LiveFeed.swift`).
- **4 Tabs**: Merkliste · Markt · Portfolio (optional) · Einstellungen; «Paar hinzufügen» über «+»
  in der Merkliste. **Kein Willkommensdialog** mehr – direkt die Startauswahl; «Was die App kann»
  unter Einstellungen › Über.
- **Alarm-Editor als Satz** («Wenn BTC über … geht», «Einmal / Jedes Mal», «Erweitert») mit
  **Vorlagen** ±1 %, ±5 %, neues 30-Tage-Hoch/-Tief, Volumen ×3 (`Shared/Services/AlarmTemplates.swift`);
  **Funding- und Open-Interest-Alarme** für Perpetuals (Binance, Bybit, OKX);
  **Portfolio-Alarme** (Gesamtwert über/unter, Veränderung heute; `Shared/Services/PortfolioAlarmLogic.swift`).
- **Beträge verbergen** (Portfolio, Portfolio-Widget, Portfolio-Alarme), **Sicherung mit Passwort**
  (AES-256-GCM, PBKDF2; `App/Services/BackupCrypto.swift`), **Basis der %-Änderung** (24 Std.,
  seit letzter Aktualisierung, seit 00:00 in der Zone des Geräts oder fest UTC+14 … UTC-12), **Suche in den Einstellungen**
  (`App/Features/Settings/SettingsSearch.swift`).
- **Portfolio wie Merkliste**: Coin oder Transaktion nach links wischen löscht (mit «Rückgängig»), ⋯ › «Aktualisieren» / «Portfolio leeren», 24-h-Änderung und Anzahl Transaktionen je Coin-Zeile.
- **Gleiches ⋯-Menü** in Merkliste, Markt und Portfolio: App-Logo und Name (→ «Über»), «Aktualisieren», dann die Einträge des Tabs (`App/Components/AppMenuHead.swift`).
- **App zurücksetzen** (Einstellungen › Daten, mit Rückfrage): `AppData.resetApp()` leert Einstellungen, Dateien, Mitteilungen, Kennzeichen, Live-Aktivitäten und Hintergrund-Aufgaben; danach Startauswahl wie nach der Installation.
- **Überschriften einheitlich** klein, fett, in der Themenfarbe (`sectionTitleStyle()` in `App/Components/Components.swift`).
- **Zurück** aus «Alarme» oder «Warum?» öffnet wieder das Aktionsblatt des Paars; Merkliste mit «Namen anzeigen»: drei Zeilen links/rechts auf einer Linie, ohne Namen «–»; Aktien ohne bStock mit Namen aus der Nasdaq-Symbolliste (`CoinLogoStore.refreshStockNames`).
- **Zahl am App-Symbol** (Einstellungen › Alarme, ab Werk an): neue Alarme und Marktmeldungen zählen am Kennzeichen, beim Öffnen der App wieder 0 (`Notifier.badge`).
- **«Warum?» als Faktorliste** mit Satz zum Gleichlauf mit Bitcoin, «1 CHF = n Sats» bei BTC-Paaren,
  Quelle und Alter je Zeile im Markt-Tab.

Frühere Runden:

- **Theme «Marrs Green»** (#4BACA5) als fünfte Akzentfarbe mit alternativem App-Icon
  (`AppIconMarrsGreen`) und Logo; die Einstellung heisst jetzt **Theme** (vorher «Farbe»).
- **15 neue Börsen** (41 Märkte, 32 Börsen), **Notiz pro Coin**, **Alarmton** zur Auswahl,
  **Netzwerkgebühren** (Ethereum, L2s, Bitcoin) mit Gas-Alarm im Markt-Tab.
- **Zyklus-Vergleich** mit Doppel-Top/-Bottom (`Shared/Insights/CycleExtremes.swift`).
- **«≈ Umrechnung»** in der Merkliste und **Alarme in deiner Währung** (31 Währungen, Standard nach
  Region des Geräts; `Shared/Portfolio/CurrencyConverter.swift`, Kurse von Frankfurter/EZB, Ersatz
  open.er-api.com). Ohne Wechselkurs wird ein Alarm in dieser Runde übersprungen.
- **Alarm «Nahe am Hoch/Tief»**: meldet, wenn der Kurs höchstens x % (Standard 2 %) unter dem Hoch bzw. über dem
  Tief der letzten 30 Tage / 90 Tage / 1 Jahr liegt, und bei einem neuen Hoch/Tief (Tageskerzen, 6 h zwischengespeichert;
  `Shared/Services/NearExtreme.swift`).
- **Portfolio «Wertverlauf»**: Wertkurve mit 7 T / 30 T / 1 J über den Positionen, berechnet aus den Transaktionen ×
  Tagesschlusskursen, umgerechnet zum heutigen Kurs.
- **Stichtag-Export** des Portfolios als CSV (`App/Features/Portfolio/PortfolioCutoffExport.swift`),
  gleiches Format wie Android: UTF-8 mit BOM, `;`, `cryptochecker-stichtag-JJJJ-MM-TT.csv`.
- **Mini-Chart** (24 Std.) in jeder Zeile der Merkliste (Einstellungen › Merkliste, bei Neuinstallation aus).
- **Sprungknopf** in langen Merklisten (> 30 Paare): «Zum Anfang» / «Zum Ende» beim Scrollen (`WatchlistJump.swift`); **Empfindlichkeit** der ungewöhnlichen Aktivität Weniger/Normal/Mehr (`Shared/Activity/ActivitySensitivity.swift`, Karte und Mitteilungen gleich).
- **Barrierefreiheit** (Einstellungen › Darstellung, auch in Widgets): Kursfarben Grün/Rot oder
  Blau/Orange (bei jeder Farbsehschwäche unterscheidbar, Richtung immer zusätzlich als +/−);
  **Farben tauschen** (Rot steigend wie in China, Japan, Korea, Taiwan; dort standardmässig an);
  **Hoher Kontrast** (Kursfarben und Akzent ≥ 7:1, Nebentexte wie Haupttext; folgt auch «Kontrast
  erhöhen» von iOS, über `traitOverrides` auf allen Fenstern); **VoiceOver**: jede Zeile, jeder
  Chart und jedes Widget als ein Satz (`Shared/Util/A11y.swift`).
- **Mit einem Tipp starten** (die fünf grössten Coins ohne Stablecoins, je mit Live-Kurs und
  24-Std.-Veränderung, alle vorausgewählt, ein Knopf «Zur Merkliste hinzufügen (5)»), **Nachtruhe** für Alarme (lautlos,
  `interruptionLevel = .passive`, ohne Sprache), **Portfolio-Sperre** (Face ID / Touch ID / Code; sperrt nur den
  Portfolio-Tab samt Detailansicht, Erfassen-Blättern und Stichtag-Export, «Zum Portfolio hinzufügen»,
  Sichern mit Portfolio-Daten und das Portfolio-Widget — Merkliste, Hinzufügen, Markt-Tab, Einstellungen und
  die übrigen Widgets bleiben frei; einmal je Sitzung entsperren, nach > 60 s im Hintergrund wieder
  gesperrt; Abdeckung im App-Umschalter; Zeile in den Einstellungen nur mit eingeschaltetem Portfolio;
  `App/Services/AppLock.swift`, Regeln in `Shared/Portfolio/PortfolioLockPolicy.swift`),
  **Wertverlauf** im Portfolio (standardmässig zugeklappt: eine Zeile «Wertverlauf · 30 T ▲ +4.20%»;
  aufgeklappt Chips 7 T / 30 T / 1 J / Seit 1. Kauf — «Seit 1. Kauf» lädt so viele Tageskerzen wie
  nötig in Stücken von höchstens 1000, höchstens 5 Jahre zurück mit Hinweis; Zustand und Zeitraum
  gemerkt, nicht in der Sicherung),
  **Portfolio-Widget** (Home klein/mittel/gross und Sperrbildschirm, `Widgets/PortfolioWidget.swift`:
  klein Gesamtwert und wählbar «≈ … USDT», mittel dazu «heute» und Wertverlauf bis 48 h, gross
  dazu die bis 5 grössten Positionen mit Anteil und Veränderung; Einrichtung `PortfolioWidgetIntent`),
  **Widget «Was gerade auffällt»** (klein/mittel, `Widgets/PulseWidget.swift`: Schlagzeile, Leitsatz,
  Coin-Chips BTC bzw. BTC/ETH/SOL; Daten über `CryptoPulseSource` mit gemeinsamem 5-Min.-Stand in der
  App Group; Tippen öffnet den Markt-Tab), **Alarm als
  Satz**, **veralteter Kurs sichtbar**, Erklärungen zu Funding, Open Interest, RSI, Pi-Cycle,
  Einstellungen «Erweitert». Hinweis unter dem Intervall: iOS plant den Hintergrund selbst.
- **Crypto Pulse «Was gerade auffällt»** (erste Karte im Markt-Tab): Schlagzeile mit Leitsatz und
  einem Satz zu Volumen/Funding, BTC/ETH/SOL, Funding-Chip (nur wenn erhöht/negativ), Marktbreite
  «Top 30 ▲ · ▼» und Krypto-Markt gesamt (Satz zur Marktbreite nur in deutlichen Fällen); «Warum? →»
  klappt «Markt heute» (nur Altcoins vs. Bitcoin) und die Faktor-Checkliste auf — nur Funding (! / –); Volumen, Fear & Greed
  und Gas stehen nicht doppelt, sie haben eigene Karten im Tab
  (`App/Features/Cycle/CryptoPulseCard.swift`, `Shared/Insights/CryptoPulse.swift`).
- **«Heute auffällig»** (Karte unter dem Pulse, Teil des schrittweisen Erscheinens): bis zu fünf der rund
  30 grössten Coins (ohne Stablecoins, mit …USDT auf Binance), die heute deutlich stärker/schwächer als BTC
  laufen, gegen den Markt, mit ungewöhnlichem Volumen (≥ 2× üblich) oder extremem Funding; sonst «Heute
  nichts Auffälliges». Je ein Abruf: 24-h-Ticker (Binance-Spiegel), Funding aller Perpetuals (Binance Futures),
  Marktkapitalisierung aus der CoinGecko-Rangliste (24 h). Zwischenspeicher 10 Min.; Tippen öffnet «Warum?»
  (Coin in der Merkliste) oder die Suche auf der Seite «Paar hinzufügen» (`Shared/Insights/MarketUnusual.swift`,
  `App/Features/Cycle/MarketUnusualSource.swift`, `App/Features/Cycle/MarketUnusualCard.swift`).
- **Hinweis «Wirtschaftsdaten»** (kompakte Zeile über dem Pulse): wichtige US-Daten heute oder in den nächsten
  18 h («Heute 14:30: US-Inflationsdaten (CPI) – an solchen Tagen schwankt der Markt oft stärker.»), Ortszeit,
  2 h danach «… veröffentlicht» bis Tagesende. Kalender einmal am Tag von der App-Webseite (24 h), sonst
  `Shared/Resources/macro_events.json`. Optional Morgen-Mitteilung um 08:00 («Wirtschaftstermine», Standard
  aus; lokal geplant) (`Shared/Insights/MacroCalendar.swift`, `App/Services/MacroNotifications.swift`).
- **«Warum bewegt sich …?» als Checkliste**: oben «Kurz gesagt» in einem Satz, darunter die Faktoren
  mit ✓ / ! / –, Rohwerte unter «Details anzeigen» (`App/Features/Watchlist/WatchWhySheet.swift`, `WatchWhyFactors.swift`).
- **Einstellungen in Gruppen** wie Android: Darstellung · Währung & Umrechnung · Alarme &
  Benachrichtigungen · Daten & Aktualisierung · Portfolio · Sicherheit & Backup · Erweitert
  (eingeklappt) · Über.
- **Aktionsblatt**: Tippen auf eine Zeile, oben gleich gross Alarm, Warum?, Favorit
  (`App/Features/Watchlist/WatchActionsSheet.swift`); nach dem Hinzufügen bietet der Hinweis
  «… wird jetzt überwacht» direkt **«Alarm setzen»**, solange die neuen Paare keinen Alarm haben.
- **Chart im Aktionsblatt**: über Alarm · Warum? · Favorit, 24h · 7T · 30T und Kerzen/Linie (zuletzt gewählt), gezeichnet wie das Einzel-Widget (`Shared/Insights/PriceChart.swift`), kurz drücken und ziehen zeigt Kurs, Zeit und Veränderung; DEX-Paare ohne Chart (`App/Features/Watchlist/WatchSheetChartView.swift`).
  Favoriten: Akzent-Rand, mit Coin-Logos ein kleiner Stern am Logo (kein eigener Stern-Knopf). Im Merklisten-Widget trennt eine dünne Linie die Paare
  (nicht unter dem letzten; `Widgets/WatchlistWidget.swift`).
- **Alarm testen** und «Alles eingerichtet» nach dem ersten Alarm, **Zyklus-Signale** statt
  Top-/Bottom-Score, «Was die App kann» mit drei Fragen (Was passiert? · Wann reagieren? · Warum?)
  unter Einstellungen › Über, Beispiel im leeren Portfolio.
- **Einzel-Widget mit Kerzen**: Chart-Art pro Widget (Kerzen oder Linie), Preise Tief/Mitte/Hoch,
  aktueller Kurs als Marke in der Akzentfarbe, Raster je Stunde/Tag/Woche (`Widgets/WidgetChart.swift`).
- **Wischen in der Merkliste**: nach rechts = Favorit, nach links = Löschen mit «Rückgängig»;
  ein Tipp öffnet die Aktionen sofort (kein Doppeltippen, das jeden Tipp ~0,3 s verzögert hätte);
  «Löschen» im Aktionsblatt wirkt wie Wischen (sofort, mit «Rückgängig», ohne Rückfrage).
  Nicht im Sortiermodus und nicht mit VoiceOver (dort Aktionen)
  (`App/Features/Watchlist/WatchlistEditing.swift`).
- **Markt-Tab in drei Abschnitten**: «Jetzt» (Crypto Pulse, «Heute auffällig», Fear & Greed), «Einordnung»
  (Marktphase, Dominanz mit Altcoin-Saison, Halving), «Daten» (Krypto-Markt, Gas, Wirtschaftsdaten, Coin); Überschriften für VoiceOver.
- **Markt-Tab sofort da**: zeigt die zuletzt gespeicherten Daten («Stand … · wird
  aktualisiert …») und lädt im Hintergrund neu (`App/Features/Cycle/CycleScreen.swift`).
- **Fragen & Wünsche**: Link zu GitHub-Issues unter Einstellungen › Über.
- **Krypto-Markt**: Marktkapitalisierung und Volumen (24 Std.) im Abschnitt «Daten» des Markt-Tabs (erste Karte); erstes Hinzufügen kurz
  inszeniert (Einblenden, Mini-Chart zeichnet sich, Häkchen, Haptik, Ansage).
- **Live-Aktivität**: ein Paar auf dem Sperrbildschirm und in der Dynamic Island
  (`Widgets/PriceLiveActivityWidget.swift`, `App/Services/LiveActivityController.swift`); aktualisiert,
  wenn die App Kurse prüft, nach 30 Min. als veraltet markiert; kein Push-Server.
- **Dynamic Type**: feste Schriftgrössen in `App/` folgen jetzt der Systemschrift
  (`App/Components/ScaledFont.swift`), Pillen und Symbole mit Obergrenze.
- **Keine Spenden in der App** (App-Store-Richtlinie 3.1.1); Hinweise nur in
  `SUPPORT.md` des Repositorys auf GitHub (Ordner `public/`).
- Zonen heissen in allen Sprachen Englisch (Extreme Bear … Extreme Bull); Deutsch in Schweizer
  Schreibweise (ss, «»).
- Datenschutz/Support: `https://r1adbe.github.io/cryptoChecker/privacy/` bzw.
  `https://r1adbe.github.io/cryptoChecker/` (`Shared/Util/AppLinks.swift`, `fastlane/metadata`).

## Ordnerstruktur

| Ordner | Inhalt |
|---|---|
| `Shared/` | Code und Ressourcen für **beide** Targets (App und Widgets), u. a. `Resources/Localizable.xcstrings` |
| `App/` | nur App: Oberfläche, Hintergrund-Aktualisierung, `Info.plist`, Entitlements, `Resources/Assets.xcassets` (App-Icons, Logos) |
| `Widgets/` | nur Widget-Erweiterung: Widgets, `Info.plist`, Entitlements, eigener Asset-Katalog |
| `Tests/` | Unit-Tests (Testziel `CryptoCheckerTests`, ⌘U bzw. `xcodebuild test -scheme CryptoChecker`); `Tests/Parity/` = gemeinsame Testfälle, vom Generator aus `android/testdata/parity` kopiert |
| `tools/` | Generatoren (Python 3) für Texte, Grafiken und das Xcode-Projekt |
| `CryptoChecker.xcodeproj` | generiertes Xcode-Projekt |
| `project.yml` | gleichwertige XcodeGen-Spezifikation (Fallback) |

## Auf dem iPhone starten

1. Mac mit **Xcode 16 oder neuer** (nötig für die getönten App-Icons).
2. `CryptoChecker.xcodeproj` mit Doppelklick in Xcode öffnen.
3. Links das Projekt anklicken, dann für **beide** Targets – `CryptoChecker` und
   `CryptoCheckerWidgetsExtension` – unter **Signing & Capabilities** das eigene **Team** wählen.
4. Die App Group **`group.com.cryptochecker.app`** muss bei beiden Targets aktiv sein
   (App und Widgets teilen darüber Paare und Kurse). Das setzt ein **kostenpflichtiges
   Apple-Developer-Konto** voraus.
   - Mit einem kostenlosen Konto bei beiden Targets die Capability **App Groups** entfernen
     (Minus-Symbol bzw. Eintrag in der `.entitlements`-Datei löschen). Die App läuft dann normal,
     die **Widgets bleiben aber leer**, weil sie die Daten der App nicht lesen können.
   - Meldet Xcode beim Signieren einen Fehler zu *Time Sensitive Notifications*, auch diese
     Capability beim Target `CryptoChecker` entfernen; Alarme kommen dann als normale Mitteilungen.
   - Ist die Bundle-ID bereits vergeben, `com.cryptochecker.app` (und passend
     `com.cryptochecker.app.widgets`) auf eine eigene ID ändern, z. B. `ch.meinname.cryptochecker`.
     Die App-Group-ID in beiden `.entitlements`-Dateien und im Code (`group.com.cryptochecker.app`)
     muss dann ebenfalls angepasst werden.
5. iPhone per Kabel anschliessen (beim ersten Mal auf dem iPhone «Vertrauen» bestätigen und unter
   *Einstellungen › Datenschutz & Sicherheit* den **Entwicklermodus** einschalten).
6. Oben in Xcode das Schema **CryptoChecker** und das iPhone als Ziel wählen, dann **Run** (▶︎ bzw. ⌘R).
7. Bei einem kostenlosen Konto auf dem iPhone unter *Einstellungen › Allgemein › VPN und
   Geräteverwaltung* dem Entwicklerzertifikat vertrauen. Solche Installationen laufen nach 7 Tagen ab.

## Version ändern

Die Version steht in den Build-Einstellungen beider Targets (die `Info.plist`-Dateien
verweisen nur darauf):

- `MARKETING_VERSION` – sichtbare Version, z. B. `16.2.2` (entspricht Androids `versionName`)
- `CURRENT_PROJECT_VERSION` – Build-Nummer, z. B. `17` (entspricht `versionCode`)

In Xcode: Target › **General** › *Identity* › Version / Build – bei **beiden** Targets gleich
setzen, sonst warnt Xcode beim Archivieren. Alternativ die Konstanten oben in
`tools/gen_xcodeproj.py` ändern und das Projekt neu generieren.

## Generatoren erneut ausführen

Alle Skripte brauchen nur Python 3 (für die Grafiken zusätzlich Pillow und die Systembibliothek libcairo)
und können beliebig oft laufen; sie lesen die Android-Ressourcen aus `../android/app/src/main/res`
oder – im GitHub-Repository und im ZIP – aus `../android/app/src/main/res` (sonst `--android-res PFAD`).

```sh
python3 tools/convert_strings.py   # strings.xml (31 Sprachen, inkl. pt-BR) + tools/ios_extra_strings.json -> Localizable.xcstrings, InfoPlist.xcstrings
python3 tools/gen_assets.py        # VectorDrawables -> App-Icons, Logos, Farben (beide Asset-Kataloge)
python3 tools/gen_xcodeproj.py     # CryptoChecker.xcodeproj neu erzeugen (findet neue .swift-Dateien selbst, kopiert die gemeinsamen Testfälle nach Tests/Parity)
```

Neue Swift-Dateien einfach in `Shared/`, `App/`, `Widgets/` oder `Tests/` ablegen und
`tools/gen_xcodeproj.py` erneut ausführen. iOS-eigene Texte gehören in
`tools/ios_extra_strings.json` (alle 31 Sprachen, inkl. `pt-BR`), danach `convert_strings.py` laufen lassen.

## Falls sich das Projekt nicht öffnen lässt

Das Projekt kann auch mit [XcodeGen](https://github.com/yonaskolb/XcodeGen) aus `project.yml`
erzeugt werden (überschreibt `CryptoChecker.xcodeproj`):

```sh
brew install xcodegen
cd ios
xcodegen
```

## Unterschiede zu Android

iOS erlaubt einiges nicht, was die Android-App kann:

- **Keine dauerhafte Kurs-Mitteilung.** Android zeigt den Kurs fest in der Statusleiste; unter iOS
  erscheint bei jeder Aktualisierung eine normale Mitteilung, die sich wegwischen lässt. Die Option
  «Nicht wegwischbar» hat keine Entsprechung.
- **Hintergrund-Aktualisierung bestimmt iOS.** Das eingestellte Intervall ist nur ein frühester
  Zeitpunkt; iOS entscheidet je nach Nutzung, Akku und Netz, wann tatsächlich aktualisiert wird –
  oft deutlich später, nach einem erzwungenen Beenden der App gar nicht mehr.
- **«Häufig aktualisieren» nur bei geöffneter App.** Kurze Intervalle (Sekunden) laufen nur, solange die App im
  Vordergrund ist; einen dauerhaft laufenden Dienst gibt es unter iOS nicht.
- **Vibration:** Kein eigener Schalter – iOS steuert die Vibration von Mitteilungen ausschliesslich über
  die Systemeinstellungen. Der Wert aus Android-Sicherungen bleibt erhalten.
- **Sprache** wird in der iOS-App *Einstellungen › Crypto Checker › Sprache* gewählt, nicht in der App.
- **App-Icon:** Wechselt nur, wenn du in den Einstellungen eine Akzentfarbe wählst; iOS bestätigt das
  mit einem kurzen Hinweis. Standard und Dunkel zeigen das dunkle Icon (weisses Symbol, wie bisher);
  die getönte Fassung (ab iOS 18) liefert der Asset-Katalog selbst (`tools/gen_assets.py`).
- **Widgets** werden über den Startbildschirm hinzugefügt (lange drücken, «+», Crypto Checker); iOS
  aktualisiert sie nach eigenem Zeitplan.
- **Alarmton:** Eigene Audiodateien erlaubt iOS für Mitteilungen nicht. Zur Wahl stehen der
  Standardton und vier mitgelieferte Töne (`App/Resources/Sounds/*.wav`).

## Sicherungen

Sicherungsdateien sind zwischen Android und iOS **austauschbar**: Eine auf Android erstellte Sicherung
lässt sich unter iOS wiederherstellen und umgekehrt. Die JSON-Datei (`cryptochecker-backup-JJJJ-MM-TT`)
enthält Merkliste (inkl. Notizen und Gruppen), Alarme (inkl. Alarmwährung), Favoriten, Portfolio und
Einstellungen (inkl. Umrechnung, Kursfarben, Farbtausch, hoher Kontrast, Mini-Chart, Nachtruhe, Portfolio-Sperre) im selben Format wie unter Android. Ist die Portfolio-Sperre
gesperrt, verlangen Sichern (mit Portfolio-Daten) und Wiederherstellen zuerst das Entsperren.
