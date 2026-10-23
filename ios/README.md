# Crypto Checker für iOS

**Crypto Checker ist ein Krypto-Checker: Kurse direkt von deiner Börse; Hinweise, wenn beim Nachsehen eine
Marke erreicht ist – ohne Konto.** Dazu Marktüberblick und als Zusatz «Was bewegt sich mit?» (was sich gleichzeitig
bewegt – keine Ursache) – ohne Prognosen, keine Anlageberatung.

Native SwiftUI-Version der Android-App Crypto Checker (Version 16.2.2, Build 116 – die Build-Nummer steigt mit jedem Stand um eins).
Mindestens iOS 17. Die App enthält eine Widget-Erweiterung (WidgetKit).

Der Quellcode ist öffentlich (MIT): <https://github.com/r1adBE/cryptoChecker>, Ordner `ios/`
(die Android-App liegt daneben in `android/`). Ein Simulator-Build ohne Signatur lässt sich dort unter
*Actions › iOS › Run workflow* starten; Kontaktdaten aus `fastlane/metadata/review_information` werden nie hochgeladen.
Namen, Logos und Marken Dritter (Coins, Token, Aktien, Börsen) und Marktdaten von Drittanbietern (z. B. CoinGecko)
gehören nicht zum Code und fallen nicht unter die MIT-Lizenz (siehe `LICENSE` im Repository).

## Neu in 16.2.2

Gleicher Funktionsumfang wie Android 16.2.2, u. a.:

Zuletzt dazugekommen:

- **Build 46 – Portfolio als echtes Kontobuch** — neue Arten Umbuchung, Belohnung, Einzahlung und
  Auszahlung sowie Gebühr in einem dritten Coin; gemeinsame Regeln in `PortfolioLedger` (Kotlin/Swift, Paritäts-Fixture
  `portfolio.json`), Portfolio-Datenbank 3 (`MIGRATION_2_3`: Umbuchung von/nach, Gebühr-Coin, `importKey`, Tabelle für
  beobachtete Adressen). Beobachtete BTC-/ETH-Adressen nur lesend (`AddressValidation`, `AddressBalance`,
  `WatchedAddressSource`; mempool.space/blockstream.info, publicnode.com/llamarpc.com), zählen ohne Kaufpreis.
  CSV-Import Relai/Swissquote/Bitpanda/Kraken-Ledger/Binance/Coinbase (`csvimport/`, Kraken: `CsvImportKraken`, Vorschau, Duplikate per `importKey`, Tageskurs-Umrechnung).
  Stichtag-CSV exakt (BigDecimal/Decimal). Portfolio-Widgets mit Menge vor dem Kürzel (`WidgetQuantityFormat`).
  Store-Texte zugespitzt. Wertverlauf mit Umschalter «Wert | Gewinn/Verlust» (Gewinnkurve = Wert minus netto
  eingesetztes Geld, Kopfzeile «Wert · Gewinn»); Coin-Zeilen in App und Widgets zeigen den eigenen Gewinn/Verlust
  der Position («24h +1'250 CHF · +1.5 %») statt der Marktveränderung des Tokens; Portfolio-Listen-Widget mit
  fixer Zeile «Gesamt» unten. FIFO als Standard-Einstandsmethode (Ø wählbar, `PortfolioFifo`), Steuer-Export je Jahr
  (`TaxExport`: realisierte Gewinne je Lot, Einkünfte, Bestand 31.12.), Einzahlung mit ursprünglichem Kaufdatum,
  Adress-Beobachtungsverlauf statt Rückdatierung, Korrekturen aus dem Review (Alarme bei fehlendem Kurs,
  Auszahlung ohne Preis, Binance-Futures-Kontrakt, Frische ohne `closeTime`). Tausch als eigene Art, FIFO je Wallet
  (Option), Importe rückgängig, offene Kauf-Posten, Jahresübersicht DE, Preis in CHF/EUR; beobachtete Adressen mit
  xpub, EVM-Netzen und Tokens, Solana, TRON, XRP, Litecoin, TON, Cardano; eine UTC-Tagesgrenze; Token-Identität;
  warmer Hellmodus, kurze Tab-Namen, «Erweitert» in den Einstellungen.
  Namen und Alpha sofort (Listen zuerst, Startliste und CC0-Icons in der App, `coin_meta.json` als Ersatz),
  Arbeitsunterlage mit Länder-Vorlagen (DE, AT, CH, UK, US, CA, AU, FR, JP), ruhigere Portfolio-Karte.
- **Build 45 – nur geprüfte Kurse zählen, ruhigere Zeilen, strengere Sicherung** — Transaktion erfassen: der Kurs
  ist nur bei einer Transaktion von heute vorausgefüllt, ein anderes Datum leert ihn mit dem Hinweis «Kurs vom
  Kauftag eintragen» (`Shared/Portfolio/PortfolioTxForm.swift`). Ungeprüfte Kerzenkurse zählen nicht mehr als Geld
  (Position «—», Zeile «Kurs ungeprüft», in der «ohne …»-Liste genannt); vorher gilt der Kurs eines
  Merklisten-Paars desselben Coins an einer anderen Spot-Börse (`Shared/Portfolio/PortfolioWatchPair.swift`).
  Wertverlauf, 7 T, 1 J und Portfolio-Widget aus den Tageskerzen der eingetragenen Börse
  (`App/Features/Portfolio/PortfolioHistorySource.swift`); hochgerechnetes Binance nur als Ersatz mit aktuellem
  Bezug, sonst «ohne …», genannt unter Chart und Karte. Volumen-Alarme und «Was bewegt sich mit?» mit derselben Kerzen-Prüfung
  wie der Chart, Laufzeit-Futures nur mit den Kerzen ihres Kontrakts, nie des Perpetuals. «≈» zeigt die Uhrzeit des
  Devisenkurses, wenn er älter als 3 h ist; Devisenkurse alle 3 h neu (vorher 6 h); «≈» nur für USD/USDT = 1.
  Import mit denselben Zahlengrenzen wie Android; Verkaufswarnung zählt die Coin-Gebühr mit; COIN-M-Kontraktwert
  aus `exchangeInfo` (sonst 100/10 USD); «Ø von … mit Kaufpreis» beim Teil mit Kaufpreis; Teilungen auf 34 gültige
  Stellen wie Android (`PortfolioDecimal.significantDigits`), nicht realisierter Gewinn exakt dezimal. Stichtag-CSV
  mit Trenn-/Dezimalzeichen je Region. Deaktivierte Zeilen und veraltete Summe mit Farbe statt Deckkraft;
  schlankere Zeile (nur die Alarmglocke, und nur mit aktivem Alarm; Herkunft von Änderung/Mini-Chart steht im
  Aktionsblatt); Gesamtkarte mit Hinweisen im Block «Hinweise» darunter und ruhigerem Verlauf; Aktionsblatt öffnet
  halb hoch (`WatchActionsDetents`); Sortierpfeile 11 pt ohne Deckkraft; leere Alarmliste mit Beispielsatz; Widgets flach wie Android (kein Verlauf, gleiche Masse, deaktiviert grau). Sicherung:
  unverschlüsselte Datei warnt beim Einspielen und kann die Portfolio-Sperre nicht ausschalten; neue Passwörter
  mindestens 12 Zeichen, leicht zu erratende gesperrt (ältere Dateien bleiben lesbar); ungültige Einträge aus dem
  Geräte-Backup werden einmal gemeldet (`App/Services/PortfolioRestoreNotice.swift`).
- **Build 44 – ehrliche Frische, eigene Kerzen** — «Live» in der Status-Pille nur, wenn alle sichtbaren gehandelten
  Paare am Live-Strom und frisch sind; «veraltet» je Paar (am Strom 2 Min., abgefragt 3 × Hintergrund-Intervall,
  mind. 15 Min.; `Shared/Util/LiveStatus.swift`). Chart, Mini-Chart, Einzel-Widget, 24-h-Ersatzbezug und «Was bewegt sich mit?»
  nehmen zuerst die Kerzen der eigenen Börse des Paars (`Shared/Services/Candles/`, `ExchangeCandleRegistry`), die
  Binance-Kette nur als gekennzeichneter Ersatz mit Token-Prüfung (passt eine Quelle nicht, ist die nächste dran;
  Perpetuals zuerst Futures; TradFi nie über die Kette; DexScreener nur der gemerkte Pool). «Gewinn heute» lässt
  Coins ohne Anfangskurs aus wie 7 T und nennt sie (Widget «Heute ohne …»), 24-h-Verlauf mit Stundenkursen der
  Stablecoins (USDT = 1), zugeklappter Wertverlauf ohne Käufe/Verkäufe (seit Build 46 Umschalter Wert | Gewinn/Verlust). `exchangeTime`/`priceChangedAt` je Paar:
  Börsen ohne eigene Zeit zeigen «Kurs unverändert seit HH:MM» (`PriceFreshness`, Widgets `WidgetFreshness`).
  Gas-/Aktivitäts-Hinweise gelten erst als verbraucht, wenn sie zustellbar sind; Hintergrund in der Reihenfolge
  Kurse → Portfolio → Alarme mit Zusatzdaten → Aktivität → Gas, die nächste Aufgabe wird gleich zu Beginn vorgemerkt;
  mit Portfolio-Sperre neutrale Mitteilung beim Portfolio-Alarm. Widget-Chart mindestens 0,2 % hoch (Stablecoins
  1 %). Portfolio-Tab bei Neuinstallation aus (bestehende behalten ihn), «Zum Portfolio hinzufügen» ist dann
  ausgeblendet; «Wie aktuell?» ab Werk Sparsam (empfohlen), Live «für Profis»; Textdurchsicht DE/FR/IT (kein
  «Aktionsblatt» in der App, FR durchgehend «vous», ehrliche Hinweise zu Live/Sparsam).
- **Build 43 – Zahlen und Lesbarkeit** — Hintergrund mit `RefreshDeadline` (Kurse zuerst speichern, Zusatzdaten nur
  mit Restzeit), Vordergrund speichert vor dem Melden, «zustellbar» nur mit Banner/Sperrbildschirm/Mitteilungszentrale;
  Stichtag-CSV mit Token-Prüfung, Einzel-Widget nur mit passenden Kerzen; Akzentfarben hell AA, Fear & Greed mit
  `MarketScalePalette`, Widget-Schrift mit Dynamic Type; Namen und Umrechnung ab Werk an, Alpha aus.
- **Build 42 – CoinGecko direkt, ehrliche Alarme, ruhiger Start** — CoinGecko-Daten nicht mehr gespiegelt
  (API-Bedingungen): Release «data» nur noch mit `fng.txt`, `onchain.txt`, `btcprice.txt`, `altseason.txt`; `/global`
  (30 Min.), Top 30/40 (24 h) und Top 40 für Spot-Tab und «Was bewegt sich mit?»-Marktbreite (eine gemeinsame Abfrage, 10 Min.)
  direkt bei CoinGecko (`DataMirror.swift`), kein `COINGECKO_API_KEY`; kein eigenes Logo-Paket mehr — Rangliste
  (Seiten 1–10), Binance-/Alpha-Liste und Bilder direkt von CoinGecko/Binance (`CoinLogos.swift`, `CoinLogoStore`),
  Aktiennamen von Nasdaq; «Powered by CoinGecko» (`DataFreshness.poweredByCoinGecko`, ≥ 10 pt) unter CoinGecko-Karten,
  bei Coin-Logos, in Über und Lizenzen; `LICENSE`: Namen, Logos, Marken und Marktdaten Dritter nicht unter MIT.
  Alarme: Vorschlag ±5 % vom Kurs (`AlarmTemplates.suggestedThresholdText`), Rückfrage «Alarm löst sofort aus»
  (`AlarmEdit.firesNow`), Bearbeiten stellt nur bei geänderter Bedingung neu scharf (`AlarmEdit`), «Konnte nicht
  geprüft werden (kein Devisenkurs / keine Kerzendaten)» in der Alarmliste (`AlarmCheckStatus`, nur im Speicher).
  Neue Installationen: neue Paare ohne Kurs-Mitteilung (`newPairNotificationEnabled`), «Nicht wegwischbar» wie Android
  aus (ohne Wirkung auf iOS); «+ Paar hinzufügen» mit Text, Glocke immer sichtbar; veraltete Kurse in
  `onSurfaceVariant` statt halb durchsichtig.
- **Build 41 – Alarme, Charts, ruhigere Screens** — einmaliger Portfolio-Alarm bleibt ohne Mitteilungs-Erlaubnis
  scharf (`evaluatePortfolioAlarms` async), wiederholende Alarme ebenso (`QuietHours.mayFire`); Fremd-Kerzen nur bei
  gleichem Token (`WatchSheetChart`, Mini-Chart in `WatchlistRow`); «Kurs vorlesen» schaltet die Sprachausgabe ein;
  «Wie aktuell?» unter Alarme; Markt-Tab mit `MarketSectionPage`; Zurück aus «Paar hinzufügen» in den Markt-Tab
  (`AppRouter.showMarketExplorer`); Hinweis «Durchschnitt, nicht FIFO».
- **Build 40 – Portfolio-Zahlen, ehrliche Zeilen, Alarme** — `PortfolioExchangeRoute.scale` nur bei gleichem Token,
  `scaleRanges`; kein alter Devisenkurs; Kauf ohne Preis im 7-T-Fenster lässt den Coin aus; Paritätsdatei
  `portfolio.json`; Zeilen-Hinweis «Änderung/Chart: Börse»; Kursfarben, %-Änderung, Logos und Widgets unter
  «Darstellung»; `BackgroundOffBanner` (Hintergrundaktualisierung aus / Stromsparmodus); `RefreshBudget` und
  `flushSnapshotWrites` im Hintergrund; Alarme in der Sicherung geprüft (`BackupManager.alarmFromJson`);
  Binance-Gruppenpause; gregorianischer Kalender im Portfolio; `WatchlistRestoreCleanup`; Privacy-Manifest mit
  FileTimestamp (C617.1).
- **Build 39 – Datensicherheit** — `portfolio.json` wird bei Lesefehler nicht mehr leer überschrieben
  (`PortfolioStore.loadChecked`, `preserveUnreadable`, `AppData.currentPortfolioFile`); Sicherung: ungültige
  Portfolio-Einträge in der Rückfrage, `BackupLimits` (16 MB, Tiefe 32), Notiz 120 Zeichen; Live-Strom mit
  Generationszähler nach `await flush`; Gas-Alarm nach Fehler in 2 Min.; `BinanceIntervalCache` mit Lock.
- **Build 38 – Alarme und Futures wie im Code** — Hintergrund-Aktualisierung bleibt vorgemerkt statt bei
  jedem Öffnen neu gelegt (`BackgroundRefresh.scheduleRefresh`, `RefreshSchedule`); Binance COIN-M von `dapi`;
  unbekanntes Funding-Intervall ohne Alarm; Portfolio-Alarm über Devisenkurs (`prefetchPortfolioAlarmRates`),
  %-Alarm mit echter Rückkehr; Gebühren-Kanten; Kerzen «Binance Futures»; Kurs-Hinweis bis Faktor 10.
- **Build 37 – ehrliche Zahlen** — «Heute / 7 T / 1 J» lässt einen Coin ohne Anfangskurs aus (`PortfolioHistory`,
  `skipped`); ein «Heute» für Karte, Widget und Alarm (`PortfolioWidgetStore.compute`); Portfolio-Alarm nach Anteil
  frischer Kurse (80 %); Funding-Intervall je Kontrakt; Kurs von der eingetragenen Börse (`PortfolioExchangeRoute`,
  `PortfolioRouteResolver`, App Group), Zeile «Kurse: Binance · USDT = 1»; Bitcoin-Kennzahlen mit Einordnung seit
  2016 (`IndicatorHistoryMath`), Pi-Cycle-Abstand, Halving aus der Blockhöhe (`HalvingMath`), eigene Grenzen als
  Meldung (`IndicatorAlertRules`, `ZoneCheck`).
- **Build 36 – Import** — nach dem Einspielen einer Sicherung fragt die App nach der Erlaubnis für
  Mitteilungen und holt gleich Kurse (`refreshAll`); Token-Symbol als Raute (`TokenIconShape`).
- **Build 35 – exaktes Portfolio-Journal** — Mengen, Kaufpreise und Gebühren werden exakt gerechnet
  (`Decimal`, `Shared/Portfolio/PortfolioDecimal.swift`; bis 18 Nachkommastellen, 1 BTC = 100 000 000 Satoshi,
  0.00000001 BTC bleibt 0.00000001). Gespeichert als Text in `portfolio.json` (ältere Dateien mit Zahlen werden gelesen). Bewertung mit Marktkursen bleibt `Double`. Sicherung und Portfolio-Datei schreiben
  zusätzlich `amountText`/`priceText`/`feeText` (Zahlenfelder bleiben für ältere Versionen; beim Lesen gewinnt der Text).
- **Gebühr und Börse pro Transaktion** — im Transaktions-Blatt unter «Gebühr und Börse»: Gebühr in USDT (erhöht den
  Einstand, beim Verkauf senkt sie den Erlös) oder im Coin (verringert die Menge); Börse nur zur Übersicht.
  Durchschnittskosten (seit Build 46: FIFO Standard, Ø wählbar), Detailzeile «Gebühr …» (`Shared/Portfolio/PortfolioCalculator.swift`, `PortfolioTxSheet.swift`).
- **Ehrlicher Gewinn** — Heute · 7 T · 1 J rechnen Käufe/Verkäufe im Zeitraum heraus (Wert Ende − Wert Anfang −
  Zuflüsse; ein Kauf ist kein Gewinn), «Seit Kauf [USDT]» zählt nur Coins mit Kaufpreis *und* Kurs, die übrigen stehen
  im Hinweis «Ohne N Coins ohne Kaufpreis (…)»; (i) neben «Gewinn/Verlust» erklärt die Rechnung
  (`PortfolioPeriodChange.gain`/`todayAdjusted`, `PortfolioTotalCard.swift`, Test `PortfolioLedgerTests`).
- **Markt-Tab neutral** — statt Marktphase, Zonen und Top-/Bottom-Hinweisen die Karte «Bitcoin-Kennzahlen» mit
  gemessenen Werten (Mayer Multiple, 200-Wochen-Schnitt, Abstand zum Hoch, 30 Tage, SMA 50/200, RSI Woche/Tag,
  MVRV, Puell, Hashrate 30/60) je mit einem Satz Erklärung, ohne Bewertung (`Shared/Insights/CycleModel.swift`);
  Coin-Karte: RSI, Trend und Stärke gegenüber Bitcoin (`CoinCycleModel.swift`); Halving nur als Monat «~Apr 2028»
  (`BitcoinCycle.swift`, Intervall 1458 Tage); «Altcoins gegenüber Bitcoin» als Anzahl statt «Altcoin-Saison».
  Zonen-Meldungen entfernt (alter Sicherungs-Schlüssel wird ignoriert), Markt-Meldungen: Fear & Greed, Gas,
  «Auffällig», Wirtschaftstermine.
- **«Wie aktuell?»** — ersetzt die Schalter «Häufig aktualisieren»/«Live-Kurse»: Sparsam (empfohlen, Standard seit Build 43) · Live, solange offen
  (für Profis); «Immer kurz» gibt es seit Build 40 auch auf Android nicht mehr. Merkliste zeigt «Live» nur, wenn alle
  sichtbaren gehandelten Paare am Live-Strom und frisch sind (seit Build 44, `LiveStatus.allLive`).
- **Kleinere Korrekturen** — Aktionsblatt «Mehr» klappt pro Coin auf, Akzent-Buttons in Einstellungen mit
  `accent.primary`, Abstand unten 96 pt im Portfolio, «Akzentfarbe», «Hell / Dunkel», klarere
  Stand-Texte («Aktuell · …», «Veraltet · Stand …»), Portfolio-Hinweis überarbeitet.
- **Farbige Schrift statt Pillen** (wie Android): in der App keine Änderungs-Pillen mehr – Pfeil + Wert mit Vorzeichen
  (echtes Minus U+2212) in der Kursfarbe, grau bei 0/unbekannt/deaktiviert, Durchschnitt «Ø» in der gelben
  Warn-Schriftfarbe, Zeitfenster klein und grau. Merklisten-Zeilen, Puls-Zeile («↗ 233 steigen ↘ 295 fallen
  Ø +0.32%»), Aktionsblatt («↗ +41.71% · 24h · Aktualisiert …»), Marktdaten (Funding als farbige Schrift ohne Pfeil),
  «Was bewegt sich mit?» (Faktoren unter «Kurz gesagt» als eine graue Textzeile), Markt-Karten (Fear-&-Greed-Zahl in der
  Stufenfarbe, «Krypto-Markt 2.82 Bio. USD ↘ −2.77% · 24h», «Auffällig» mit grauem «24h») und Portfolio
  (Heute · 7 T · Gesamt als farbige Schrift, Beträge mit Pfeil «↗ +12.40 USDT»; neue Zeile unter dem Gesamtwert mit
  der Veränderung über ein Jahr «↘ −38'412.95 USDT −27.85% · 1J», bei weniger als einem Jahr Verlauf ausgeblendet,
  `App/Features/Portfolio/PortfolioTotalCard.swift`). `ChangePill` / `TonePill` (`App/Components/ChangePill.swift`)
  zeichnen nur noch Text.
- **Einstellung «Schrift auf Pillen und Buttons» entfernt**: Buttons mit Themenfarbe als Hintergrund nutzen wieder die
  normale Kontrastfarbe des Themas; alte Sicherungen mit `pillTextColor` lassen sich weiter einspielen (Feld wird
  ignoriert, `App/Services/BackupManager.swift`), der Export schreibt es nicht mehr.
- **Einstellungs-Symbole einfarbig**: Symbole der Hauptseite in der Textfarbe (dunkel weiss, hell schwarz), Alarme mit
  Glocke, Markt-Meldungen mit Blitz; nur «App zurücksetzen» bleibt in der Fehlerfarbe. Die Themenfarbe bleibt für
  Titel-Striche, Schalter und Auswahl.
- **USDT zum Marktkurs** (wie Android, Owner-Entscheid Okt. 2026): USDT bleibt die interne Rechenwährung, in Fiat gilt
  sein Marktkurs USDT/USD: live die Messung von `DepegAlarmCheck` (Kraken USDT/USD, Coinbase USDT-USD, Binance USDC/USDT nur als dritte Quelle; Einbruch nur bestätigt, `DepegAlarm.measure`; gemessen, sobald
  ein Portfolio da ist, auch ohne Depeg-Alarm; `DepegAlarmCheck.liveUsdtUsd`, höchstens 6 Std. alt), für vergangene
  Tage der Kraken-Tagesschluss USDTZUSD (`PortfolioHistorySource.usdtUsdDaily`, 12 h gemerkt). Gesamtkarte, Zeilen,
  Wertverlauf, «7 T»/«1 J», Widget, Portfolio-Alarme, «≈ Umrechnung», Kurse eigener Börsen in USD/Fiat, Steuer- und
  Stichtag-Export rechnen damit (`UsdtUsdRates`, `PortfolioStables.usdtTo`). Ohne Kurs gilt 1 als Näherung und ist
  markiert («USDT = 1 angenommen», im CSV je Zeile). Auch in USD: Karte, Verlauf und Widget zeigen USD (USDT × USDT/USD), nicht mehr USDT 1:1.
- **Ehrliche Portfolio-Kurse** (wie Android): USDT ist die Bewertungsbasis (1 USDT = 1 USDT, in Fiat zum Marktkurs, siehe oben); USDC, DAI … ohne Marktkurs
  haben keinen Wert («—», «ohne Kurs», zählt unter «ohne N Coins», Gesamtgewinn unbekannt;
  `Shared/Portfolio/PortfolioStables.swift`), im Wertverlauf landen solche Stablecoins ohne Kerzen unter «ohne …»;
  die ≈-Umrechnung der Merkliste zeigt für sie seit Build 45 ohne Marktkurs keinen Wert (nur USD/USDT = 1). Kerzenkurs für Coins, die Binance Spot nicht führt, nur
  nach Identitätsprüfung 1/3…3 gegen den Median der Merklisten-Kurse desselben Coins (USD-stabile Spot-Paare, aktiv,
  höchstens 30 Tage alt) bzw. den letzten guten gemerkten Kurs (`PricePlausibility.candleReference` / `acceptCandle`,
  `Shared/Portfolio/PortfolioPriceSource.swift`, Faktor 3 über `sameToken`); passt er nicht → der letzte gute Kurs
  bleibt mit seiner «Stand»-Zeit (ohne gemerkten Kurs «—»); ohne Bezug seit Build 45 kein Kurs («—», «Kurs
  ungeprüft»), vorher gilt ein Merklisten-Paar an einer anderen Spot-Börse. Nur Daten auf dem Gerät, keine neue Abfrage (Tests in `PortfolioNumbersTests`).
- **Ehrlicher Alarm-Takt**: der Hinweis «Wann Alarme geprüft werden» erscheint einmal nach dem Speichern des ersten
  Alarms (nie zwei Dialoge nacheinander) und steht dauerhaft hinter dem (i) neben «Bei geschlossener App…» im
  Alarm-Editor und im Portfolio-Alarm-Blatt. Der Text ist jetzt ehrlich für iOS: «Bei geschlossener App prüft iOS im
  Hintergrund unregelmässig, oft seltener als alle 15 Minuten … (seit Build 55 ein einziger Satz, `alarm_timing_hint_ios`: zuverlässig bei geöffneter App, im Hintergrund
  entscheidet iOS» (früher `alarm_timing_text_ios_open`; der Schalter
  zählt auf iOS nicht für den Hinweis, `AlarmTimingHint.liveServiceCoversClosedApp = false`) (`AlarmTimingHint` in `Shared/Services/AlarmLogic.swift`,
  `ClosedAppHintRow` in `App/Features/Alarms/AlarmEditorSheet.swift`, `AlarmLogicTests`). Merker
  `alarmTimingHintShown` nur auf dem Gerät, nicht in der Sicherung.
- **Portfolio-Tab: neu aus, bestehend an**: seit Build 44 bei Neuinstallationen ab Werk aus
  (`AppSettings.portfolioEnabled = false`); gespeicherte Einstellungen ohne den Wert behalten den bisherigen Standard an
  (`legacyPortfolioEnabled`). Für Einstellungen von vor «Portfolio ab Werk an» (ältere Versionen schrieben das frühere
  «aus» mit) schaltet der einmalige Merker `portfolioDefaultV2` (`Shared/Models/AppSettings.swift`) den Tab einmal an,
  danach bleibt jede Wahl; Sicherung ohne Feld → an. Solange aus, ist «Zum Portfolio hinzufügen» ausgeblendet
  (`SettingsLogicTests`).
- **Aktionsblatt ruhiger**: doppelte Änderungs-Zeile entfernt; unter dem Kurs eine Zeile «↗ +41.71% · 24h · Aktualisiert …»,
  dann direkt die Kacheln Alarm · Warum? · Favorit, dann Chart, Zeitraum, Marktdaten, Mehr; am Zeitraum keine zweite
  24h-Änderung (bei 7T/30T/1J bleibt sie als farbige Schrift)
  (`App/Features/Watchlist/WatchActionsSheet.swift`).
- **Einstellungen mit «Erweitert»**: Hauptseite Allgemein (Sprache – öffnet die iOS-App Einstellungen –, Währung, Merkliste,
  Portfolio), Darstellung (Hell/Dunkel, Akzentfarbe), Alarme (Alarme, Markt-Meldungen), Daten (Sicherung), Zeile
  «Erweitert» (`AdvancedSettingsPage` in `App/Features/Settings/SettingsScreen.swift`: Aktualisierung, Kursfarben,
  %-Änderung, Coin-Logos, Widgets, Sprachausgabe), Über, «App zurücksetzen» als eigene
  Karte ganz unten (`resetCard`); die Suche findet alles, Punkte der Seite «Erweitert» mit dem Pfad «Erweitert › …»
  (`SettingsSearchUi.swift`).
- **«Was bewegt sich mit?» kurz zuerst**: Karte «Kurz gesagt · Stand HH:MM» mit einem Satz, bis zu 3 auffälligen Faktoren als eine
  graue Textzeile und «Sicherheit»; zugeklappt «Alle Werte und Quellen» (Beobachtung, Mögliche Einordnung, Quellen, langer
  Hinweis); kurze Fusszeile «Zeitgleich heisst nicht Ursache · Keine Anlageberatung»
  (`App/Features/Watchlist/WatchWhySheet.swift`).
- **Widgets**: Trennlinie im Merklisten- und Portfolio-Listen-Widget neutral (Haupttext-Farbe 25 %, bei hohem
  Kontrast 45 %) statt Akzentfarbe (`WidgetStyle.divider` in `Widgets/WidgetStyle.swift`); Widget-Hintergrund ohne
  Leuchten in der Akzentfarbe, seit Build 45 flach wie Android (`surfaceContainer`, kein Verlauf); Vorschau-Daten der Widget-Galerie mit aktuellen
  Beispielwerten (BTC 83'142.00, ETH 2'505.49, SOL 109.60, XRP 1.394; `Widgets/WidgetData.swift`).
- **Coin-Logos nach Offline-Erststart**: ein ohne Netz gescheiterter Abgleich sperrt keine weiteren Versuche mehr;
  `AppData` holt ihn nach, sobald wieder Netz da ist, mit wachsender Pause (5 s → 15 s → 30 s → 60 s … höchstens
  15 Min., `CoinLogoRetry.backoffMillis`), und beim Wechsel in den Vordergrund; nie zwei Abgleiche gleichzeitig (`CoinLogoRetry` in
  `Shared/Util/CoinLogos.swift`, `CoinLogosTests`).

Davor:

- **«Was bewegt sich mit?» relativ zur eigenen Schwankung** (wie Android, gemeinsame Testfälle `Tests/Parity/why_analysis.json`,
  `WhyAnalysisParityTests`): eine 24-h-Bewegung gilt erst ab k · σ der Stunden-Renditen (bis 72 h vor dem
  24-h-Fenster; Coin k = 2 mit Untergrenze 2 %, Markt k = 1,5 mit 1 %; zu wenig Verlauf → feste Schwellen;
  `RelativeMove` in `Shared/Activity/WhyContext.swift`). Breiter Markt neben BTC/ETH: Median der 24-h-Veränderung
  der Top 40 (direkt bei CoinGecko, dieselbe Abfrage wie der Spot-Tab, 10 Min. im Speicher) ohne Stablecoins/BTC/ETH
  (mind. 10 Coins, höchstens 3 h alt;
  `Shared/Activity/BroadMarket.swift`), «marktweit» bzw. «nur dieser Coin» berücksichtigt alle drei. Je Faktor Quelle
  und Alter («Kerzen: Kraken · 14:00» — seit Build 44 die eigene Börse des Paars, «Marktbreite: CoinGecko · 13:05», «Futures: Bybit · OI vor 2 Std. verglichen»,
  «Fear & Greed: heute»; Ausweichquelle/«Richtwert» gekennzeichnet). Open-Interest-Verlauf mit bis zu 5 Messungen
  (≥ 30 Min. Abstand, ≤ 12 h, gleiche Quelle, Median, gegenläufig → «uneinheitlich»; alte Einzelmessung übernommen;
  `OiSeries.swift`), nahe am 30-Tage-Tief, Lage in 3 Tagen, Funding-Trend «seit 24 Std. steigend»
  (`FundingTrend.swift`, höchstens alle 10 Min. je Paar), Stablecoins nach Abweichung von 1:1 ohne Markt- und
  Schwankungsfaktoren, Volumen «geschätzt» ohne Quote-Volumen bzw. weggelassen bei mehr als 6 von 24 Std. ohne
  Handel, Schwankung 1 h erst ab 0,5 % Bewegung; Kerzen mit `limit=100` (vorher 30), dazu ETHUSDT-Stundenkerzen.
- **Chart im Aktionsblatt** (`App/Features/Watchlist/WatchSheetChart.swift`, `Shared/Insights/PriceChart.swift`):
  zuerst die Kerzen der eigenen Börse (seit Build 44, `OwnCandleDataSource`), sonst die Binance-Kette nur bei gleichem
  Token und mit Quelle in der Zeile; Fiat-Rückfall mit historischem Tageskurs je Kerze (Frankfurter-Zeitreihe wie im Portfolio, höchstens einmal je
  Währung in 6 h); Zwischenspeicher verworfen bei manueller Aktualisierung, nach unten ziehen oder Kurssprung > 3 %;
  Zeile unter dem Chart (Börse, «umgerechnet mit Tageskursen» bzw. «(aktueller Kurs)», «Stand 14:00»);
  Eröffnung/Hoch/Tief/Schluss beim Ziehen; Achse bei Stablecoins mind. ±0,5 % (sonst ±0,1 %); Geometrie nicht auf
  dem Hauptthread; angenäherte Volumen-Balken halb so kräftig mit «≈».
- **Futures-Daten je Börse** (`FuturesSources` in `Shared/Insights/FuturesExchanges.swift`, `FuturesParsersTests`):
  eigene Funding-/OI-Daten für WOO X, Bitget, MEXC, HTX, Hyperliquid und Deribit (dazu Binance, Bybit, OKX); andere
  Börsen wie Phemex bzw. ausgefallene eigene Quelle → Richtwert von Binance → Bybit → OKX mit Hinweis «Richtwert von
  Binance Futures – Funding und Open Interest gelten je Börse, bei … können sie abweichen.»; Spot-Paare «Richtwert …
  (USDT-Perpetual des Coins)». Funding-Alarme auf Richtwert melden mit « · Richtwert Binance Futures»,
  Open-Interest-Alarme pausieren; Hyperliquid-Funding «pro Std.», für Alarme und Trend × 8
  (`Shared/Services/DerivativesAlarm.swift`).
- **Alpha-Tokens**: `AssetKind` (Token / TradFi / Alpha, `Shared/Models/WatchFilter.swift`), Chip «α» neben
  ★/⚡/Token/TradFi (Alpha zählt auch als Token), Schalter «inkl. Alpha-Tokens (α)» unter «Token & TradFi» (ab Werk
  an, bei «Nur TradFi» gesperrt wie QTLY, in Sicherung und Einstellungs-Suche), Symbol der Art vor der Glocke
  (`AssetKindIconView` in `WatchlistGroups.swift`, grau bzw. Akzentfarbe bei gewähltem Chip, VoiceOver
  «TradFi»/«Alpha-Token»); Tests `CoinLogosTests`, `WatchFilterTests`, `SettingsLogicTests`.
- **Portfolio und Widgets**: Gesamtwert «Gesamt [USDT]» (Widget «Gesamt [CHF]» usw.); Merklisten-Widget zeichnet
  nach dem Einrichten sofort aus den gespeicherten Kursen neu (geänderte Gruppe sofort sichtbar), «α» im Einrichten
  (`WidgetIntents.swift`), gemeinsame Auswahl `WidgetGroupSelection.pick` / `options` (`Shared/Storage/WidgetGroupSelection.swift`,
  `WidgetGroupSelectionTests`).
- **Widgets: Zeitraum und passende Zeilen** (wie Android): Merklisten-Widget wartet bis zu 3 s, bis die Kurs-Datei
  mindestens so neu ist wie die Uhrzeit der Aktualisierung, sonst zeichnet es nach 60 s neu (`WidgetDataFreshness`
  in `Shared/Storage/WidgetGroupSelection.swift`, `Widgets/WidgetData.swift`, `WidgetDataFreshnessTests`) – nie neue Zeit
  über alten Zeilen. Portfolio-Widget «Liste» mit Zeitraum vor der Änderung («24h ↘ −0.25 %», «heute» bei Basis seit
  00:00); Puls-Widget: Fear-&-Greed-Zahl in den fünf Stufenfarben, «↗ 21» grün / «↘ 9» rot, «Top 30 · 24h», «24h» vor
  den Coins.
- **Markt und Portfolio mit Status-Pille** (`App/Components/DataStatusPill.swift`, wie Android): Markt mit «24h» über
  den Coins, «Top 30 · 24h», Marktkap. als «Krypto-Markt … ↘ −2.77% · 24h», Fear-&-Greed-Zahl als farbige Schrift und
  «Offline · Stand HH:MM» (letzte Daten bleiben, offline kein Neuladen, abgelaufene Daten laden nach, sobald das Netz
  zurück ist). Portfolio: «Alles aktuell» / «Kurse veraltet · Stand HH:MM» / «Offline · Stand HH:MM»; letzter Kurs je
  Coin mit Zeitpunkt gemerkt (`Shared/Portfolio/PortfolioPriceFallback.swift`, Datei `portfolio_last_prices.txt` in
  der App Group, immer vom Geräte-Backup ausgenommen) – offline bzw. nach dem Start der letzte Wert statt 0.00,
  veraltete blass; veraltet nach 3 × Hintergrund-Intervall (mind. 15 Min.), dann nicht in Widget-Stand, Verlauf und
  Alarm-Prüfung; nie in einer Sicherung, mit «Portfolio leeren» gelöscht (`PortfolioPriceFallbackTests`).

- **Sperre, «Heute»/«7 T», Futures-Daten, Portfolio-Alarme im Backup** (wie Android): Sperrt sich die Sitzung wieder
  (> 60 s im Hintergrund), schliessen sich Export-Optionen, Passwort-Blatt beim Wiederherstellen und die Rückfrage
  «Ersetzen?» wie die Erfassen-Blätter; ein schon bestätigter Export bzw. ein schon schreibendes Wiederherstellen läuft
  fertig, eine erst nach dem Sperren gewählte Datei oder fertige Passwort-Prüfung wird verworfen (erneut tippen und
  entsperren). «7 T»: ein Stablecoin ohne Tagesschluss zählt nur als 1, wenn sein aktueller Kurs höchstens 0,1 % von 1
  abweicht, sonst «—» (USDT immer 1; `PortfolioStables.fallbackWithoutClose`, Test in `PortfolioNumbersTests`).
  «Heute»: die 30-Minuten-Stichproben nutzen die Kurse nach der Stablecoin-Regel wie Android (5 000 USDC zu 1.00 +
  1 BTC 5 000 → 5 500 = +5 % auf beiden, vorher unter iOS +10 %; USDC ohne Kurs fehlt in der Stichprobe); alte Stichproben fallen innerhalb von 48 h weg.
  Futures-Daten: eine leere Bybit-Antwort (weder Funding noch Open Interest) geht wie unter Android zur nächsten Quelle
  weiter (Funding-/OI-Alarme bleiben nicht mehr stumm). Schlägt das Schreiben von `portfolio_alarms.json` fehl, bleiben
  die Schwellen in `watchlist.json` und das nächste Speichern versucht es erneut; solange ist `watchlist.json` bei
  ausgeschalteter «Portfolio in Systemsicherung» vom Geräte-Backup ausgenommen (`SharedStorage.writeFiles`).

- **Paarlisten, «nicht mehr gehandelt», Portfolio-Zahlen, Sicherung** (wie Android): OKX Futures lädt die Paarliste aus
  `/api/v5/public/instruments?instType=SWAP` (nur «live»; `instCategory` macht NVDA, AAPL, XAU zu TradFi), Kurse weiter
  aus `market/tickers`; Bybit folgt bei `instruments-info` dem `nextPageCursor` (höchstens 10 Seiten). Fehlt ein Paar
  in der vollständigen Sammelabfrage, wird es einmal einzeln abgefragt und nur bei einer Antwort ohne Kurs als «nicht
  mehr gehandelt» markiert (`NotTraded.askSingleWhenMissing` / `confirmedBySingle`); Netzfehler und Rate-Limits bleiben
  gewöhnliche Fehler. Bybit live: Delta ohne `lastPrice` erneuert die Zeit nicht. Wertverlauf und «7 T» werfen einen
  Coin nur bei einem Tagesschluss ausserhalb 1/3…3 des aktuellen Kurses aus (`PricePlausibility.sameToken`; die
  25-%-Regel der %-Änderung bleibt). Realisierter Gewinn/Verlust mit Käufen ohne Preis: unbekannter Anteil als «+ —»
  bzw. «—» (`realizedUnknownAmount`), ganz verkaufte Coins immer unter «Geschlossen». ≈-Zeile: andere Stablecoins mit
  dem zwischengespeicherten Portfolio-Kurs (`PortfolioPriceSource.cached`), ohne ihn seit Build 45 kein ≈-Wert. Sicherung: neue Dateien mit
  600 000 PBKDF2-Durchläufen (ältere lesbar), schwache Passwörter seit Build 45 gesperrt; Ver- und Entschlüsseln laufen nicht mehr
  auf dem Hauptthread, «Weiter» ist während der Prüfung gesperrt. Stundenkurse, Verlauf und Kerzen-Versuche des
  Portfolio-Widgets liegen jetzt als Dateien in der App Group (`portfolio_widget_hourly.json`,
  `portfolio_widget_history.json`, `portfolio_widget_candle_attempts.json`), immer vom Backup ausgenommen (einmalige
  Übernahme aus den App-Group-Einstellungen). `project.yml` trägt dieselbe Build-Nummer; `tools/gen_xcodeproj.py`
  bricht ab, wenn `project.yml` und seine Konstanten abweichen.

- **Suche nach Coin-Namen**: Hinweis «Paar, Name oder Börse suchen» bei «Paar hinzufügen», in der Merkliste und beim
  Bearbeiten einer Gruppe; «bitcoin» findet BTC, «ether» ETH, ohne Akzente, Name mit Quote («ethereum eur»);
  Reihenfolge genaues Kürzel → Kürzel-Anfang → Name-Anfang → Name enthält → Börse; «Paar hinzufügen» sucht auch nach
  Börse («kraken», «btc kraken», ab 3 Zeichen). Namen nur aus Daten auf dem Gerät (Logo-/Namensliste, Aktiennamen),
  keine neue Abfrage (`App/Features/Explorer/ExplorerPairSearch.swift`, `App/Features/Watchlist/WatchlistSearch.swift`;
  Tests `ExplorerPairSearchTests`).
- **Karte «Marktdaten» im Aktionsblatt**: Register «Spot | Futures» (vorher «Futures-Daten», nur bei Futures-Paaren) bei
  allen Krypto-Paaren (nicht bei deaktivierten Paaren, DEX-Pools, TradFi-Spot). Spot (CoinGecko, USD, kompakt):
  Marktkapitalisierung mit 24h-Änderung (farbige Schrift), Volumen 24h, Vol./Marktkap., FDV, Umlauf/Gesamt/Max. mit Balken, «Quelle: CoinGecko
  · Stand …», «Powered by CoinGecko»; zuerst aus den Top 40 (direkt bei CoinGecko, für alle gleich), sonst eine Abfrage
  `coins/markets?symbols=…`, 10 Min. im Speicher
  (`Shared/Util/SpotMarket.swift`, `Shared/Insights/SpotDataSource.swift`, `App/Features/Watchlist/WatchSheetSpot.swift`,
  `Tests/SpotMarketTests.swift` mit `Tests/Parity/spot_market.json`). Futures: Funding (farbige Schrift ohne Pfeil) mit Countdown, Open Interest;
  bei Spot-Paaren das USDT-Perpetual des Coins (Binance → Bybit → OKX, als «Richtwert» gekennzeichnet) erst beim Öffnen des Registers
  (`WatchSheetFutures.swift`). Start-Register je nach Paar, für die Sitzung gemerkt; (i) erklärt das offene Register.
  Im Kopf wird der grosse Kurs kleiner statt abgeschnitten.
- **Ehrliche Meldungen**: Markt-Meldungen (Marktphase, Fear & Greed) beachten Nachtruhe und Mitteilungs-Erlaubnis
  (`MarketNoticeGate` in `Shared/Insights/ZoneCheck.swift`; «zuletzt gemeldet» rückt nur nach einer gezeigten Meldung
  vor); Banner nach dem Speichern eines Alarms, ehrlich bei ausgeschalteten Mitteilungen (`AlarmSavedText`); Quelle
  der Tagesveränderung «Binance USDT» mit Devisen-Prüfung für Nicht-USDT-Quotes (gemeinsame Testfälle
  `Tests/Parity/day_change.json`).
- **Portfolio-Widget mit Optionen** (`PortfolioWidgetIntent`): Ansicht «Übersicht / Chart / Liste» (`PortfolioWidgetMode`, `PortfolioWidgetViews`
  in `Shared/Portfolio/PortfolioWidgetViews.swift`), Anzeige «Nur USDT / Nur Umrechnung / Umrechnung + USDT»,
  Zeitraum 24 h / 7 T / 30 T / 1 J, Kerzen oder Linie; Chart wie in der App (Hilfslinien, «Hoch»-Marke, Kursfarben);
  7 T / 30 T / 1 J aus dem Tagesverlauf des Portfolio-Tabs, sonst 24 h mit Hinweis; Coins ohne Kurs werden gezählt
  («ohne 2 Coins»). Gesamtwert-Karte «Heute · 7 T · Gesamt» (`PortfolioPeriodChange` in
  `Shared/Portfolio/PortfolioHistory.swift`), Volumen-Balken im Coin-Chart.
- **Einheitliche Zahlen und Zeichen**: jede %-Änderung und jeder Wert mit Vorzeichen als farbige Schrift ohne Pille
  (`App/Components/ChangePill.swift`: `ChangePill`, `TonePill`, `PillTone` — Pfeil + «+» bzw. «−» (U+2212) in der
  Kursfarbe, grau für 0/unbekannt, gelbe Warn-Schriftfarbe für neutrale Durchschnitte); schräge Pfeile ↗ ↘ statt ▲/▼; ein Aufklapp-Pfeil (`chevron.down` in
  der Themenfarbe, dreht beim Öffnen); Quote-Währung nach dem Kurs klein und grau (`PriceWithQuote`, auch in der
  Alarm-Übersicht); Coin-Listen als eine Einheit (`ListSegment`; Wisch-Hintergrund an der ersten/letzten Zeile
  bleibt eckig, Systemgrenze); (i) mit kurzer Erklärung bei jeder Zeile der Markt-Karten; Einstellungen mit Wert
  unter dem Titel; Tab-Leiste mit Namen in Textfarbe, aktives Icon in Akzentfarbe und 10 % grösser; Tab-Icon vor dem Titel bei Markt, Portfolio und Einstellungen (`.tabTitle`); Währung nach Beträgen klein und grau auch in allen Widgets (`Text.quoted`) und im Portfolio; Entwickleroptionen (7 Tipps auf die
  Version) mit Countdown bei den letzten 3.
- **Paar hinzufügen**: «Zuletzt gesucht» nur auf dem Gerät (`ExplorerRecentSearches` in `ExplorerPairSearch.swift`,
  nicht in der Sicherungsdatei) und Schnellfilter Gewinner / Verlierer / Volumen bei «Alle …-Paare» aus einer
  Sammelabfrage der Börse, höchstens einmal pro Minute. Die Sicherung enthält jetzt auch die Spalten-Sortierung der
  Merkliste.
- **Paar deaktivieren und Nachtruhe**: `Watch.disabled` (`QuickView.disabled`), deaktivierte Paare ohne Abfragen und Meldungen; Alarme warten in der Nachtruhe (`QuietHours.holdsAlarms`); Hinweis zur geschlossenen App im Alarm-Editor.
- **Einheitlich für den Live-Test**: Chips mit Namenshinweis und Token/TradFi-Filter, Einstellung «Token & TradFi» (`AssetScope` in `AppSettings.swift`), Portfolio-Chart wie der Paar-Chart (`PortfolioHistoryChart.swift`), Markt und Einstellungen als Karten (`MarketRow`, `SettingsCard`), Glocke/Glühbirne gefüllt nur mit Alarm bzw. Aktivität.
- **Kursfarben, Portfolio-Vergleich, Markt-Register**: drei Stile mit Kerzen-Vorschau (neu `.TRADITIONAL`), Wertverlauf «CHF | USDT | Vergleich» (`Shared/Portfolio/PortfolioCompare.swift`), Markt mit Registern «Jetzt | Einordnung | Daten» (`RegisterTabs` in `ExplorerComponents.swift`; seit Build 41 eine Liste mit Unterseiten, `MarketSectionPage.swift`).
- **Befunde der technischen Prüfung behoben**: Alarme wie Android (gleitendes Fenster, Hoch/Tief-Marke), Marktphase/
  Fear & Greed laufen wieder (`BackgroundRefresh.swift`), `watchlist.json` mit `NSFileCoordinator` und tolerantem Lesen
  (nie leer überschrieben), Portfolio-Alarme in `portfolio_alarms.json`, keine Abstürze bei NaN/∞ (`JSON.swift`),
  Live-Kurs 1 nicht mehr verworfen. «Auffällig» öffnet das Aktionsblatt (Vorschau), langes Drücken = Auswählen,
  Gruppen-Chips mit Rand in der Themenfarbe.
- **Portfolio-Zahlen und Sicherung**: eine Stablecoin-Regel (`Shared/Portfolio/PortfolioStables.swift`), Wertverlauf
  mit EZB-Tageskursen (`PortfolioHistoryFx.swift`), 25-%-Sperre für den Stichtag, im Verlauf nur die Identitätsprüfung 1/3…3 (`PricePlausibility.swift`), Hinweis «Veränderung aus
  …-Kerzen»; `portfolio.json` standardmässig vom Geräte-Backup ausgenommen (`Shared/Storage/BackupExclusion.swift`,
  Schalter «Portfolio in Systemsicherung»); Börsen-Vertragstests (`Tests/ExchangeContractTests.swift`, Daten aus
  `android/testdata/exchanges`, von `gen_xcodeproj.py` nach `Tests/Exchanges` kopiert).
- **Listen einheitlich**: Merkliste, Start-Auswahl, «Auffällig» und Portfolio mit gleichem Logo (36 pt) und
  gleicher Schrift; die Liste wirkt wie eine Karte: keine Fuge, Trennlinien über die ganze Breite, nur erste und
  letzte Zeile aussen 16 pt gerundet, ein Rand um die ganze Liste (`App/Components/ListSegment.swift`). Markt: Status-Pille «Alles aktuell · vor …» statt Titel «Jetzt».
- **Feinschliff vor dem Store-Test**: eine Frische-Schwelle für Pille, Zeile und VoiceOver; Merkliste speichert im
  Hintergrund (`SharedStorage.saveSnapshotInBackground`); «Favoriten»-Chip und Gruppen-«+» nur bei Bedarf; kein
  Sortieren per langem Drücken (⋯ › Sortieren); Gesten-Hinweis mit Richtung; 44-pt-Tippflächen; Favorit-Stern ohne
  Logos; leere Alarm-Übersicht mit «Neuer Alarm»; Suche ohne Treffer mit «Börse und Paar selbst wählen»; Hinweis
  «Mitteilungen sind aus» bei den Alarmen; Überschriften mit Strich statt Orange-Schrift, keine bräunlichen Flächen
  im Dunkelmodus; ★- und ⚡-Chip bei den Gruppen statt Aktivitätskarte (`QuickView.swift`), Tipp auf «veraltet»
  zeigt nur diese, Mehrfachauswahl (⋯ › Auswählen: Favorit · Gruppe · Löschen, `WatchlistSelection.swift`), Zeit
  in der Zeile nur wenn nicht aktuell, «Aktualisieren» grau statt «Gerade aktualisiert»; in der Zeile seit Build 45 nur
  noch die Alarmglocke mit Wellen (`bell.and.waves.left.and.right`), und nur mit aktivem Alarm, die Kurs-Mitteilung
  steht am Schalter im Aktionsblatt; Glocke oben immer sichtbar (seit Build 42; mit aktiven Alarmen
  mit Wellen), ohne Zahl («Alle Alarme» auch im ⋯-Menü); neue Paare und Start-Paare ab Build 42 ohne Kurs-Mitteilung.

- **Coin-Logos** statt nur Initialen (Merkliste, Aktionsblatt, Portfolio, Markt,
  «Paar hinzufügen», Widgets Merkliste und Einzel-Coin). Kein eigenes Logo-Paket (CoinGecko-API-Bedingungen): jede
  Installation lädt gleich die CoinGecko-Rangliste (Seiten 1–4 = Top 1000; Lücken wie Gold, Silber, bStocks aus der
  Binance-Symbolliste, kleine Futures-Token wie AIA/AGT/AIO aus der Binance-Alpha-Tokenliste, danach Rang 1001–2500 =
  Seiten 5–10, 6 s Pause je Seite, wöchentlich neu, unvollständige Liste nach 6 h erneut; Aktien-Logos von Binance
  `static/stock/…png` für alle TradFi-Kürzel der gespeicherten Paarlisten, Aktiennamen aus der Nasdaq-Symbolliste);
  Bilder nur von den Bildservern von CoinGecko und Binance, auf einmal geladen (nie einzeln) und im App-Group-Ordner
  gespeichert. Schalter «In der App» / «Im Portfolio» (ab Werk an) / «In Widgets» (ab Werk aus) unter
  Einstellungen › Erweitert › Coin-Logos, dort «Powered by CoinGecko»
  (`Shared/Util/CoinLogos.swift`, `Shared/Storage/CoinLogoStore.swift`, `Widgets/WidgetLogos.swift`).
- **Daten-Spiegel**: Marktdaten, die für alle gleich sind (Fear & Greed, Coin-Metrics-On-Chain, BTC-Kursverlauf,
  fertig berechnete Altcoin-Saison), stündlich von `market-data.yml` als Anhänge der Release «data» (`fng.txt`,
  `onchain.txt`, `btcprice.txt`, `altseason.txt`); `MarketHTTP.call` fragt für genau diese Adressen zuerst den Spiegel,
  sonst den Anbieter (`Shared/Core/DataMirror.swift`). CoinGecko-Daten werden nie gespiegelt (API-Bedingungen) — die
  App fragt CoinGecko direkt.
- **Live-Kurse per WebSocket** in der geöffneten Merkliste (Binance, Bybit, OKX, Coinbase, Kraken;
  REST bleibt Rückfall), «Live» in der Status-Pille nur, wenn alle sichtbaren gehandelten Paare live und frisch sind
  (Build 44), je Tick wird nur die betroffene Zeile neu
  gezeichnet (`App/Services/LivePriceStream.swift`, `Shared/Services/LiveFeed.swift`).
- **4 Tabs**: Merkliste · Markt · Portfolio (optional; bei Neuinstallation seit Build 44 aus, unter Einstellungen ›
  Portfolio einschaltbar, Zeile unter «Allgemein») · Einstellungen; «Paar hinzufügen» über «+»
  in der Merkliste. **Kein Willkommensdialog** mehr – direkt die Startauswahl; «Was die App kann»
  unter Einstellungen › Über die App.
- **Alarm-Editor als Satz** («Wenn BTC über … geht», «Einmal / Jedes Mal»; gleich sichtbar nur Kurs über/unter und
  Steigt/Fällt um, alle übrigen Bedingungen unter «Mehr Bedingungen», Währung, Ton und Vorlesen unter «Erweitert») mit
  **Vorlagen** ±1 %, ±5 %, neues 30-Tage-Hoch/-Tief, Volumen ×3 (`Shared/Services/AlarmTemplates.swift`); ein neuer
  Kursalarm schlägt 5 % über bzw. unter dem Kurs vor, eine schon erreichte Marke fragt beim Speichern nach
  («Alarm löst sofort aus», `Shared/Services/AlarmEdit.swift`); Bearbeiten stellt nur bei geänderter Bedingung neu
  scharf; nicht geprüfte Alarme (kein Devisenkurs, keine Kerzendaten) sind in der Liste markiert
  (`Shared/Services/AlarmCheckStatus.swift`);
  **Funding- und Open-Interest-Alarme** für Perpetuals (Binance, Bybit, OKX, WOO X, Bitget, MEXC, HTX, Hyperliquid, Deribit);
  **Portfolio-Alarme** (Gesamtwert über/unter, Veränderung heute; `Shared/Services/PortfolioAlarmLogic.swift`).
- **Beträge verbergen** (Portfolio, Portfolio-Widget, Portfolio-Alarme), **Sicherung mit Passwort**
  (Format v3: AES-256-GCM mit Argon2id und dem Kopf als AAD, ältere v2-Sicherungen mit PBKDF2 lesbar; `App/Services/BackupCrypto.swift`), **Basis der %-Änderung** (24 Std.,
  seit letzter Aktualisierung, seit 00:00 in der Zone des Geräts oder fest UTC+14 … UTC-12), **Suche in den Einstellungen**
  (`App/Features/Settings/SettingsSearch.swift`).
- **Portfolio wie Merkliste**: Coin oder Transaktion nach links wischen löscht (mit «Rückgängig»), ⋯ › «Aktualisieren» / «Portfolio leeren», 24-h-Änderung und Anzahl Transaktionen je Coin-Zeile.
- **Gleiches ⋯-Menü** in Merkliste, Markt und Portfolio: App-Logo und Name (→ «Über»), «Aktualisieren», dann die Einträge des Tabs (`App/Components/AppMenuHead.swift`).
- **App zurücksetzen** (eigene Karte ganz unten in den Einstellungen, mit Rückfrage): `AppData.resetApp()` leert Einstellungen, Dateien, Mitteilungen, Kennzeichen, Live-Aktivitäten und Hintergrund-Aufgaben; danach Startauswahl wie nach der Installation.
- **Überschriften einheitlich** klein, fett, in Textfarbe mit kurzem Strich in der Themenfarbe davor (`sectionTitleStyle()` in `App/Components/Components.swift`).
- **Keine bräunlichen Flächen im Dunkelmodus**: `AccentColor.container` dunkel neutral grau mit Schrift in der Themenfarbe, schwache Akzent-Füllungen über `accent.tint(_:)` (dunkel grau), Aufteilungs-Balken zur hellen Schrift hin gemischt; hell unverändert.
- **Zurück** aus «Alarme» oder «Was bewegt sich mit?» öffnet wieder das Aktionsblatt des Paars; Merkliste mit «Namen anzeigen»: drei Zeilen links/rechts auf einer Linie, ohne Namen «–»; Aktien ohne bStock mit Namen aus der Nasdaq-Symbolliste (`CoinLogoStore.refreshStockNames`).
- **Zahl am App-Symbol** (Einstellungen › Alarme, ab Werk an): neue Alarme und Marktmeldungen zählen am Kennzeichen, beim Öffnen der App wieder 0 (`Notifier.badge`).
- **«Was bewegt sich mit?» als Faktorliste** mit Satz zum Gleichlauf mit Bitcoin, «1 CHF = n Sats» bei BTC-Paaren,
  Quelle und Alter je Zeile im Markt-Tab.

Frühere Runden:

- **Theme «Marrs Green»** (#4BACA5) als fünfte Akzentfarbe mit alternativem App-Icon
  (`AppIconMarrsGreen`) und Logo; die Einstellung heisst jetzt **Theme** (vorher «Farbe»).
- **15 neue Börsen** (40 Märkte auf 32 Börsen plus DexScreener), **Notiz pro Coin**, **Alarmton** zur Auswahl,
  **Netzwerkgebühren** (Ethereum, L2s, Bitcoin) mit Gas-Alarm im Markt-Tab.
- **Zyklus-Vergleich** mit Doppel-Top/-Bottom (`Shared/Insights/CycleExtremes.swift`).
- **«≈ Umrechnung»** in der Merkliste und **Alarme in deiner Währung** (31 Währungen, Standard nach
  Region des Geräts; `Shared/Portfolio/CurrencyConverter.swift`, Kurse von Frankfurter/EZB, Ersatz
  open.er-api.com, alle 3 h neu; älter als 3 h → Uhrzeit des Kurses in der ≈-Zeile). Ohne Wechselkurs wird ein
  Alarm in dieser Runde übersprungen.
- **Alarm «Nahe am Hoch/Tief»**: meldet, wenn der Kurs höchstens x % (Standard 2 %) unter dem Hoch bzw. über dem
  Tief der letzten 30 Tage / 90 Tage / 1 Jahr liegt, und bei einem neuen Hoch/Tief (Tageskerzen, 6 h zwischengespeichert;
  `Shared/Services/NearExtreme.swift`).
- **Portfolio «Wertverlauf»**: Wertkurve mit 7 T / 30 T / 1 J über den Positionen, berechnet aus den Transaktionen ×
  Tagesschlusskursen, umgerechnet zum heutigen Kurs.
- **Stichtag-Export** des Portfolios als CSV (`App/Features/Portfolio/PortfolioCutoffExport.swift`),
  gleiches Format wie Android: UTF-8 mit BOM, `cryptochecker-stichtag-JJJJ-MM-TT.csv`; Trenn-/Dezimalzeichen je
  Region (Schweiz/Liechtenstein `;` mit dem Dezimalzeichen der Region, Länder mit Dezimalkomma `;` und `,`, sonst
  `,` und `.`).
- **Mini-Chart** (24 Std.) in jeder Zeile der Merkliste (Einstellungen › Merkliste, bei Neuinstallation aus); seit
  Build 44 Stundenkerzen der eigenen Börse (`OwnHourlyCandles.swift`), sonst gekennzeichnet aus der Binance-Kette.
- **Sprungknopf** in langen Merklisten (> 30 Paare): «Zum Anfang» / «Zum Ende» beim Scrollen (`WatchlistJump.swift`); **Empfindlichkeit** von «Auffällig» Weniger/Normal/Mehr (`Shared/Activity/ActivitySensitivity.swift`, Karte und Mitteilungen gleich).
- **Barrierefreiheit** (Einstellungen › Darstellung › Kursfarben, auch in Widgets): Kursfarben Grün/Rot oder
  Blau/Orange (bei jeder Farbsehschwäche unterscheidbar, Richtung immer zusätzlich als +/−);
  **Farben tauschen** (Rot steigend wie in China, Japan, Korea, Taiwan; dort standardmässig an);
  **Hoher Kontrast** (Kursfarben und Akzent ≥ 7:1, Nebentexte wie Haupttext; folgt auch «Kontrast
  erhöhen» von iOS, über `traitOverrides` auf allen Fenstern); **VoiceOver**: jede Zeile, jeder
  Chart und jedes Widget als ein Satz (`Shared/Util/A11y.swift`).
- **Mit einem Tipp starten** (die fünf grössten Coins ohne Stablecoins, je mit Live-Kurs und
  24-Std.-Veränderung, alle vorausgewählt, ein Knopf «Zur Merkliste hinzufügen (5)»), **Nachtruhe** für Alarme (Alarme warten bis zum Ende und
  melden dann mit Ton, `QuietHours.holdsAlarms`; keine Markt-Meldungen in der Nachtruhe, `MarketNoticeGate`), **Portfolio-Sperre** (Face ID / Touch ID / Code; sperrt nur den
  Portfolio-Tab samt Detailansicht, Erfassen-Blättern und Stichtag-Export, «Zum Portfolio hinzufügen»,
  Sichern mit Portfolio-Daten und das Portfolio-Widget — Merkliste, Hinzufügen, Markt-Tab, Einstellungen und
  die übrigen Widgets bleiben frei; einmal je Sitzung entsperren, nach > 60 s im Hintergrund wieder
  gesperrt, offene Erfassen-Blätter und Sicherungs-Blätter schliessen sich dann; Abdeckung im App-Umschalter; Zeile in den Einstellungen nur mit eingeschaltetem Portfolio;
  `App/Services/AppLock.swift`, Regeln in `Shared/Portfolio/PortfolioLockPolicy.swift`),
  **Wertverlauf** im Portfolio (standardmässig zugeklappt: eine Zeile «Wertverlauf · 30 T +4.20%» als farbige Schrift;
  aufgeklappt Chips 7 T / 30 T / 1 J / ∞ (Seit 1. Kauf, Name erscheint beim Tippen kurz darunter) und
  Kerzen/Linie unter dem Chart — «∞» lädt so viele Tageskerzen wie nötig in Stücken von höchstens 1000,
  ohne 5-Jahres-Grenze (Kurse erst ab 17.08.2017, sonst Hinweis); Coins mit eingetragener Börse aus deren eigenen
  Tageskerzen, hochgerechnetes Binance nur als Ersatz mit aktuellem Bezug, sonst «ohne …»; Zustand und Zeitraum
  gemerkt, nicht in der Sicherung),
  **Portfolio-Widget** (Home klein/mittel/gross und Sperrbildschirm, `Widgets/PortfolioWidget.swift`:
  klein Gesamtwert und wählbar «≈ … USDT», mittel dazu «heute» und Wertverlauf bis 48 h, gross
  dazu die bis 5 grössten Positionen mit Anteil und Veränderung; Einrichtung `PortfolioWidgetIntent` mit Anzeige,
  Zeitraum und Kerzen/Linie, siehe oben),
  **Widget «Auffällig»** (klein/mittel, `Widgets/PulseWidget.swift`: Schlagzeile, Leitsatz,
  Coins als farbiger Text, BTC bzw. BTC/ETH/SOL; Daten über `CryptoPulseSource` mit gemeinsamem 5-Min.-Stand in der
  App Group; Tippen öffnet den Markt-Tab), **Alarm als
  Satz**, **veralteter Kurs sichtbar**, Erklärungen (i) zu jeder Zeile der Markt-Karten (u. a. Funding, Open Interest, RSI, Pi-Cycle),
  Einstellungen «Erweitert» (eigene Seite). Hinweis unter dem Intervall: iOS plant den Hintergrund selbst.
- **Crypto Pulse «Auffällig»** (bis Build 84 «Was gerade auffällt») (erste Karte im Markt-Tab): Schlagzeile mit Leitsatz und
  einem Satz zu Volumen/Funding, BTC/ETH/SOL, Funding-Chip (nur wenn erhöht/negativ), Marktbreite
  «Top 30 ↗ · ↘» als farbige Schrift und Krypto-Markt gesamt (Satz zur Marktbreite nur in deutlichen Fällen); «Warum? ⌄»
  klappt «Markt heute» (nur Altcoins vs. Bitcoin) und die Faktor-Checkliste auf — nur Funding (! / –); Volumen, Fear & Greed
  und Gas stehen nicht doppelt, sie haben eigene Karten im Tab
  (`App/Features/Cycle/CryptoPulseCard.swift`, `Shared/Insights/CryptoPulse.swift`).
- **«Auffällig» (Karte im Markt-Tab)** (bis Build 84 «Heute auffällig») (seit Build 83 im zugeklappten Teil «Mehr Marktdaten», Teil des schrittweisen Erscheinens): bis zu fünf der rund
  30 grössten Coins (ohne Stablecoins, mit …USDT auf Binance), die heute deutlich stärker/schwächer als BTC
  laufen, gegen den Markt, mit ungewöhnlichem Volumen (≥ 2× üblich) oder extremem Funding; sonst «Heute
  nichts Auffälliges». Je ein Abruf: 24-h-Ticker (Binance-Spiegel), Funding aller Perpetuals (Binance Futures),
  Marktkapitalisierung aus der CoinGecko-Rangliste (24 h). Zwischenspeicher 10 Min.; Tippen öffnet «Was bewegt sich mit?»
  (Coin in der Merkliste) oder die Suche auf der Seite «Paar hinzufügen» (`Shared/Insights/MarketUnusual.swift`,
  `App/Features/Cycle/MarketUnusualSource.swift`, `App/Features/Cycle/MarketUnusualCard.swift`).
- **Hinweis «Wirtschaftsdaten»** (kompakte Zeile über dem Pulse): wichtige US-Daten heute oder in den nächsten
  18 h («Heute 14:30: US-Inflationsdaten (CPI) – an solchen Tagen schwankt der Markt oft stärker.»), Ortszeit,
  2 h danach «… veröffentlicht» bis Tagesende. Kalender einmal am Tag von der App-Webseite (24 h), sonst
  `Shared/Resources/macro_events.json`. Optional Morgen-Mitteilung um 08:00 («Wirtschaftstermine», Standard
  aus; lokal geplant) (`Shared/Insights/MacroCalendar.swift`, `App/Services/MacroNotifications.swift`).
- **«Was bewegt sich mit …?» als Checkliste**: oben «Kurz gesagt» (heute als Karte, siehe oben), die Faktoren
  mit ✓ / ! / – unter «Alle Werte und Quellen» (`App/Features/Watchlist/WatchWhySheet.swift`, `WatchWhyFactors.swift`).
- **Einstellungen in Gruppen** wie Android: Allgemein · Darstellung · Alarme · Daten · Zeile «Erweitert»
  (eigene Seite) · Über · «App zurücksetzen».
- **Aktionsblatt**: Tippen auf eine Zeile, unter dem Kurs die Zeile «24h · Aktualisiert: …», gleich darunter gleich gross Alarm, Warum?, Favorit
  (`App/Features/Watchlist/WatchActionsSheet.swift`); nach dem Hinzufügen bietet der Hinweis
  «… wird jetzt überwacht» direkt **«Alarm setzen»**, solange die neuen Paare keinen Alarm haben.
- **Chart im Aktionsblatt**: unter Alarm · Warum? · Favorit, 24h · 7T · 30T und Kerzen/Linie (zuletzt gewählt), gezeichnet wie das Einzel-Widget (`Shared/Insights/PriceChart.swift`), kurz drücken und ziehen zeigt Kurs, Zeit und Veränderung; DEX-Paare ohne Chart (`App/Features/Watchlist/WatchSheetChartView.swift`).
  Favoriten: mit Coin-Logos ein kleiner Stern am Logo (kein eigener Stern-Knopf, kein Akzent-Rand mehr; ausgewählte Zeilen getönt). Im Merklisten-Widget trennt eine dünne neutrale Linie die Paare
  (nicht unter dem letzten; `Widgets/WatchlistWidget.swift`).
- **Alarm testen** und «Alarm gespeichert» nach dem ersten Alarm, **Zyklus-Signale** statt
  Top-/Bottom-Score, «Was die App kann» mit drei Fragen (Was passiert? · Wann reagieren? · Warum?)
  unter Einstellungen › Über die App, Beispiel im leeren Portfolio.
- **Einzel-Widget mit Kerzen**: Chart-Art pro Widget (Kerzen oder Linie), Preise Tief/Mitte/Hoch,
  aktueller Kurs als Marke in der Akzentfarbe, Raster je Stunde/Tag/Woche (`Widgets/WidgetChart.swift`); Kerzen seit
  Build 44 von der eigenen Börse des Paars, sonst gekennzeichnet aus der Binance-Kette; Achse mindestens 0,2 % hoch
  (Stablecoins 1 %).
- **Wischen in der Merkliste**: nach rechts = Favorit, nach links = Löschen mit «Rückgängig»;
  ein Tipp öffnet die Aktionen sofort (kein Doppeltippen, das jeden Tipp ~0,3 s verzögert hätte);
  «Löschen» im Aktionsblatt wirkt wie Wischen (sofort, mit «Rückgängig», ohne Rückfrage).
  Nicht im Sortiermodus und nicht mit VoiceOver (dort Aktionen)
  (`App/Features/Watchlist/WatchlistEditing.swift`).
- **Markt-Tab in drei Abschnitten**: oben ohne Titel mit Status-Pille (seit Build 83: Hauptkarte Crypto Pulse mit «Kurz gesagt», darunter Fear & Greed kompakt und Gewinner/Verlierer 3+3, zugeklappt «Mehr Marktdaten» mit «Auffällig» und Makro), «Einordnung»
  (Marktphase, Dominanz mit Altcoin-Saison, Halving), «Daten» (Krypto-Markt, Gas, Wirtschaftsdaten, Coin); Überschriften für VoiceOver.
- **Markt-Tab sofort da**: zeigt die zuletzt gespeicherten Daten («Stand … · wird
  aktualisiert …») und lädt im Hintergrund neu (`App/Features/Cycle/CycleScreen.swift`).
- **Fragen & Wünsche**: Link zu GitHub-Issues unter Einstellungen › Über die App.
- **Krypto-Markt**: Marktkapitalisierung und Volumen (24 Std.) im Abschnitt «Daten» des Markt-Tabs (erste Karte); erstes Hinzufügen kurz
  inszeniert (Einblenden, Mini-Chart zeichnet sich, Häkchen, Haptik, Ansage).
- **Live-Aktivität**: ein Paar auf dem Sperrbildschirm und in der Dynamic Island
  (`Widgets/PriceLiveActivityWidget.swift`, `App/Services/LiveActivityController.swift`); aktualisiert,
  wenn die App Kurse prüft, nach 30 Min. als veraltet markiert; kein Push-Server.
- **Dynamic Type**: feste Schriftgrössen in `App/` folgen jetzt der Systemschrift
  (`App/Components/ScaledFont.swift`), Änderungsangaben und Symbole mit Obergrenze.
- **Keine Spenden in der App** (App-Store-Richtlinie 3.1.1); Hinweise nur in
  `SUPPORT.md` des Repositorys auf GitHub (Ordner `public/`).
- Deutsch in Schweizer Schreibweise (ss, «»).
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

1. Mac mit **Xcode 26 oder neuer** (wie die CI; Xcode 16 reicht für die getönten App-Icons, aber App-Store-Uploads verlangen das aktuelle SDK).
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
`tools/gen_xcodeproj.py` ändern und das Projekt neu generieren. `project.yml` (XcodeGen-Fallback) muss
dieselben Werte tragen: `gen_xcodeproj.py` (auch mit `--check`) bricht mit einer Meldung ab, wenn
`MARKETING_VERSION` oder `CURRENT_PROJECT_VERSION` dort abweichen.

## Generatoren erneut ausführen

Alle Skripte brauchen nur Python 3 (für die Grafiken zusätzlich Pillow und die Systembibliothek libcairo)
und können beliebig oft laufen; sie lesen die Android-Ressourcen aus `../android/app/src/main/res`
oder – im GitHub-Repository und im ZIP – aus `../android/app/src/main/res` (sonst `--android-res PFAD`).

```sh
python3 tools/convert_strings.py   # strings_*.xml (31 Sprachen, inkl. pt-BR) + tools/ios_extra_strings.json -> Localizable.xcstrings, InfoPlist.xcstrings
python3 tools/gen_assets.py        # VectorDrawables -> App-Icons, Logos, Farben (beide Asset-Kataloge)
python3 tools/gen_xcodeproj.py     # CryptoChecker.xcodeproj neu erzeugen (findet neue .swift-Dateien selbst, kopiert die gemeinsamen Testfälle nach Tests/Parity)
```

Neue Swift-Dateien einfach in `Shared/`, `App/`, `Widgets/` oder `Tests/` ablegen und
`tools/gen_xcodeproj.py` erneut ausführen. iOS-eigene Texte gehören in
`tools/ios_extra_strings.json` (alle 31 Sprachen, inkl. `pt-BR`), danach `convert_strings.py` laufen lassen.

## Swift 6 Migration

Alle Ziele bauen im Swift-5-Modus (`SWIFT_VERSION = 5.0`) mit voller Concurrency-Prüfung
(`SWIFT_STRICT_CONCURRENCY = complete`, in `tools/gen_xcodeproj.py` und `project.yml`). Im Swift-5-Modus sind
die Befunde Warnungen, keine Fehler — der CI-Build bleibt grün, die Warnungen stehen im Build-Log.

Regeln für neuen Code:

- Kein neues `nonisolated(unsafe)` ohne Sperre. Globale Einstellungen und Merker gehören in
  `LockedBox` (`Shared/Util/Theme.swift`), nicht in ein nacktes `static var`.
- Jedes `@unchecked Sendable` trägt auf derselben Zeile einen Kommentar, welche Sperre den Zustand schützt.
- Kein `@MainActor` nachträglich auf Typen, die aus Hintergrund-Aufgaben, Widgets oder UIKit-Farb-Closures
  gelesen werden — dort lieber `LockedBox`.

Was vor dem Umstellen auf `SWIFT_VERSION = 6.0` noch fehlt:

1. **Warnungen auswerten.** Der erste CI-Lauf mit `complete` liefert die Liste (`grep -E "warning: .*(Sendable|isolat|concurrency)" xcode-build.log`);
   ohne Xcode wurden nur die offensichtlichen Stellen behoben. Erst wenn das Log keine Concurrency-Warnungen
   mehr zeigt, kann Swift 6 kommen.
2. **Portfolio.** `Shared/Portfolio/PortfolioPriceSource.swift`, `Shared/Portfolio/FxRateSource.swift`,
   `App/Features/Portfolio/PortfolioHistorySource.swift` und `HistoricPriceSource.swift` haben zusammen 13
   `nonisolated(unsafe) static var`-Zwischenspeicher, die noch nicht geprüft und kommentiert sind.
3. **Nicht prüfbares `@unchecked Sendable`.** Rund 30 Klassen sind mit `NSLock` geschützt und kommentiert,
   der Compiler kann das aber nicht nachprüfen. Ersatz: `OSAllocatedUnfairLock` mit `Sendable`-Zustand (ab
   iOS 16, also schon möglich) oder `Mutex` (Synchronization, erst ab iOS 18). Besonders:
   `Market` (nicht `final`, gilt nur, solange keine Unterklasse veränderlichen Zustand bekommt),
   `LockedBox<Value>` und `FirstResult<T>` (Typparameter ohne `Sendable`-Einschränkung) und
   `BackgroundTaskCompletion` (hält ein `BGTask`, das nicht `Sendable` ist).
4. **Laufzeitprüfungen.** Swift 6 prüft die Isolation auch zur Laufzeit: Closures, die als `@MainActor`
   gelten, aber vom System auf einem anderen Thread gerufen werden (Mitteilungs-Delegate, `BGTaskScheduler`,
   `NWPathMonitor`, UIKit-Farb-Closures), brechen dann ab. Diese Einstiegspunkte vorher einzeln prüfen und
   wo nötig mit `nonisolated` bzw. `@Sendable` kennzeichnen.
5. **Widgets und App Intents.** Die statischen Eigenschaften der Intents sind jetzt `let`; `perform()`,
   Timeline-Provider und `EntityQuery` müssen im Swift-6-Modus noch auf ihre Isolation geprüft werden.
6. **Test-Fakes.** `FakeKeychain` (`Tests/ProtectedFileTests.swift`) und `FakeStore`
   (`Tests/AlarmChainIntegrationTests.swift`) sind ohne Sperre und nur nacheinander benutzbar.

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

- **Keine dauerhafte Kurs-Mitteilung.** Android kann den Kurs fest in der Statusleiste zeigen (bei neuen
  Installationen aus); unter iOS
  erscheint bei jeder Aktualisierung eine normale Mitteilung, die sich wegwischen lässt. Die Option
  «Nicht wegwischbar» hat keine Entsprechung.
- **Hintergrund-Aktualisierung bestimmt iOS.** Das eingestellte Intervall ist nur ein frühester
  Zeitpunkt; iOS entscheidet je nach Nutzung, Akku und Netz, wann tatsächlich aktualisiert wird –
  oft deutlich später, nach einem erzwungenen Beenden der App gar nicht mehr.
- **«Wie aktuell?» ohne «Immer kurz».** Kurze Intervalle und Live-Kurse laufen nur, solange die App im
  Vordergrund ist («Live, solange offen»); einen dauerhaft laufenden Dienst gibt es unter iOS nicht. Eine
  Android-Sicherung mit «Immer kurz» wird als «Live, solange offen» übernommen.
- **Vibration:** Kein eigener Schalter – iOS steuert die Vibration von Mitteilungen ausschliesslich über
  die Systemeinstellungen. Der Wert aus Android-Sicherungen bleibt erhalten.
- **Sprache** wird in der iOS-App *Einstellungen › Crypto Checker › Sprache* gewählt, nicht in der App.
- **App-Icon:** Wechselt nur, wenn du in den Einstellungen eine Akzentfarbe wählst; iOS bestätigt das
  mit einem kurzen Hinweis. Standard und Dunkel zeigen das dunkle Icon (weisses Symbol, wie bisher);
  die getönte Fassung (ab iOS 18) liefert der Asset-Katalog selbst (`tools/gen_assets.py`).
- **Widgets** werden über den Startbildschirm hinzugefügt (lange drücken, «+», Crypto Checker); iOS
  aktualisiert sie nach eigenem Zeitplan.
- **Alarmton:** Eigene Audiodateien erlaubt iOS für Mitteilungen nicht. Zur Wahl stehen der
  Standardton und vier mitgelieferte Töne (`App/Resources/Sounds/*.wav`). Die Töne sind selbst erzeugt
  (`tools/gen_alarm_sounds.py`, Sinus-Teiltöne, kein Fremdmaterial) und gemeinfrei (CC0 1.0).

## Sicherungen

Sicherungsdateien sind zwischen Android und iOS **austauschbar**: Eine auf Android erstellte Sicherung
lässt sich unter iOS wiederherstellen und umgekehrt. Die JSON-Datei (`cryptochecker-backup-JJJJ-MM-TT`)
enthält Merkliste (inkl. Notizen und Gruppen), Alarme (inkl. Alarmwährung), Favoriten, Portfolio und
Einstellungen (inkl. Umrechnung, Kursfarben, Farbtausch, hoher Kontrast, Mini-Chart, Nachtruhe, Portfolio-Sperre, Spalten-Sortierung der Merkliste, «inkl. Alpha-Tokens») im selben Format wie unter Android. Ist die Portfolio-Sperre
gesperrt, verlangen Sichern (mit Portfolio-Daten) und Wiederherstellen zuerst das Entsperren. Sperrt sich die Sitzung
wieder, während Export-Optionen, Passwort-Blatt oder die Rückfrage «Ersetzen?» offen sind, schliessen sie sich; ein schon
bestätigter Export bzw. ein schon schreibendes Wiederherstellen läuft fertig, eine danach gewählte Datei oder fertige
Passwort-Prüfung wird verworfen.

Mit Passwort wird die Datei mit AES-256-GCM verschlüsselt (Format v3), der Schlüssel per Argon2id (RFC 9106) abgeleitet:
64 MiB, 3 Durchgänge, 1 Bahn, 16 Bytes Salt — reines Swift ohne Fremdbibliothek (`App/Services/Argon2id.swift`, gleich wie
`Argon2id.kt`). Der Kopf der Datei (format, v, enc, kdf, m, t, p, salt, iv) ist als kanonisches kompaktes JSON (UTF-8)
die AAD von AES-GCM: ein veränderter Kopf scheitert wie ein falsches Passwort. Beim Lesen gelten Obergrenzen
(m ≤ 256 MiB, t ≤ 10, p ≤ 4), damit eine manipulierte Datei das Gerät nicht lähmt; reicht der Arbeitsspeicher nicht,
erscheint eine klare Meldung (kein Rückfall auf schwächere Parameter). Ältere v2-Dateien (PBKDF2-HMAC-SHA256 mit
600 000 bzw. 210 000 Durchläufen, ohne AAD) bleiben lesbar. Gemeinsame Prüfwerte: `Tests/Parity/backup_crypto.json`. Ein
neues Passwort braucht mindestens 12 Zeichen; ein leicht zu erratendes (nur Ziffern, ein wiederholtes Zeichen, häufige
Folge wie «12345678» oder «passwort») ist für neue Sicherungen gesperrt. Ältere Dateien (damals mindestens 8 Zeichen)
lassen sich weiter öffnen. Eine Sicherung ohne Passwort zeigt beim Einspielen eine Warnung (nennt die vier geschützten
Einstellungen mit ihren Namen) und senkt nie den Schutz (`BackupRestorePolicy.swift`, gleich wie Android): Sie schaltet die
Portfolio-Sperre und «Beträge verbergen» nie aus, «Portfolio in Systemsicherung» nie ein und setzt, ersetzt oder löscht
den eigenen Bitcoin-Server nie; eine beibehaltene Sperre bzw. ein beibehaltener Server steht in der Meldung danach.
Ver- und Entschlüsseln laufen im Hintergrund, «Weiter» bleibt während der Prüfung gesperrt.
