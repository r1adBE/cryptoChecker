# cryptoChecker

**Crypto Checker ist ein Krypto-Checker: Kurse direkt von deiner Börse; Hinweise, wenn beim Nachsehen eine
Marke erreicht ist – ohne Konto.** Dazu Marktüberblick und als Zusatz «Was bewegt sich mit?» (was sich gleichzeitig
bewegt – keine Ursache) – ohne Prognosen, keine Anlageberatung.

Kursüberwachung für Kryptowährungen: Merkliste, Benachrichtigungen, Alarme,
Sprachausgabe und Startbildschirm-Widgets über 53 Märkte – 44 Börsen (teils
mit Spot und Futures) plus DexScreener für DEX-Token. 31 Sprachen, kein Konto,
keine Werbung, kein Tracking; die Kurse kommen direkt von den Börsen.

r1AD — riad.work@outlook.com (Verantwortlicher mit Name und Zustelladresse: Datenschutzerklärung `docs/privacy/index.html`)

* Anzeigename: **Crypto Checker**
* applicationId: `com.cryptochecker.app`
* Module: `marketdata` (Börsen-Bibliothek), `app` (die App)
* Pakete: `com.cryptochecker.marketdata`, `com.cryptochecker.app`
* Version: **16.2.2** (versionCode 91 – wird mit jedem Stand um eins erhöht)
* Quellcode: <https://github.com/r1adBE/cryptoChecker> (öffentlich, MIT; dieser Ordner = `android/`)

## Neu in 16.2.2

### Zuletzt dazugekommen

* **Build 46 – Portfolio als echtes Kontobuch** — neue Arten Umbuchung, Belohnung, Einzahlung und
  Auszahlung sowie Gebühr in einem dritten Coin; gemeinsame Regeln in `PortfolioLedger` (Kotlin/Swift, Paritäts-Fixture
  `portfolio.json`), Portfolio-Datenbank 3 (`MIGRATION_2_3`: Umbuchung von/nach, Gebühr-Coin, `importKey`, Tabelle für
  beobachtete Adressen). Beobachtete BTC-/ETH-Adressen nur lesend (`AddressValidation`, `AddressBalance`,
  `WatchedAddressSource`; mempool.space/blockstream.info, publicnode.com/llamarpc.com), zählen ohne Kaufpreis.
  CSV-Import Relai/Swissquote/Kraken-Ledger (`csvimport/`, Kraken: `CsvImportKraken`, Vorschau, Duplikate per `importKey`, Tageskurs-Umrechnung).
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
* **Build 45 – nur geprüfte Kurse zählen, ruhigere Zeilen, strengere Sicherung** — Transaktion erfassen: der Kurs
  ist nur bei einer Transaktion von heute vorausgefüllt, ein anderes Datum leert ihn mit dem Hinweis «Kurs vom
  Kauftag eintragen» (`domain/portfolio/PortfolioTxForm.kt`). Ungeprüfte Kerzenkurse zählen nicht mehr als Geld
  (Position «—», Zeile «Kurs ungeprüft», in der «ohne …»-Liste genannt; `PricePlausibility.candleUnchecked`);
  vorher gilt der Kurs eines Merklisten-Paars desselben Coins an einer anderen Spot-Börse
  (`domain/portfolio/PortfolioWatchPair.kt`). Wertverlauf, 7 T, 1 J und Portfolio-Widget aus den Tageskerzen der
  eingetragenen Börse (`data/portfolio/PortfolioHistorySource.kt`); hochgerechnetes Binance nur als Ersatz mit
  aktuellem Bezug, sonst «ohne …», genannt unter Chart und Karte (`portfolio_history_scaled`,
  `portfolio_period_scaled`). Volumen-Alarme und «Was bewegt sich mit?» mit derselben Kerzen-Prüfung wie der Chart
  (`VolumeDataSource.pairCandles`), Laufzeit-Futures nur mit den Kerzen ihres Kontrakts, nie des Perpetuals.
  «≈» zeigt die Uhrzeit des Devisenkurses, wenn er älter als 3 h ist; Devisenkurse alle 3 h neu (vorher 6 h).
  «≈» nur für USD/USDT = 1, andere Stablecoins ohne Marktkurs ohne ≈-Wert; Verkaufswarnung zählt die Coin-Gebühr
  mit; COIN-M-Kontraktwert aus `exchangeInfo` (sonst 100/10 USD); «Ø von … mit Kaufpreis» beim Teil mit
  Kaufpreis; nicht realisierter Gewinn exakt dezimal. Stichtag-CSV mit Trenn-/Dezimalzeichen je Region
  (`CutoffCsvFormat.forLocale`). Deaktivierte Zeilen und veraltete Summe mit Farbe statt Deckkraft; schlankere
  Zeile (nur die Alarmglocke, und nur mit aktivem Alarm; Herkunft von Änderung/Mini-Chart steht im Aktionsblatt);
  Gesamtkarte mit Hinweisen im Block «Hinweise» darunter und ruhigerem Verlauf; Aktionsblatt öffnet halb hoch;
  Sortierpfeile 11 sp mit 48-dp-Tippfläche; leere Alarmliste mit Beispielsatz. Sicherung: unverschlüsselte Datei
  warnt beim Einspielen und kann die Portfolio-Sperre nicht ausschalten; neue Passwörter mindestens 12 Zeichen,
  leicht zu erratende gesperrt (ältere Dateien bleiben lesbar); Wiederherstellen aus der Systemsicherung zählt
  ungültige Einträge und meldet sie einmal (`PortfolioRestoreNoticeDialog.kt`).
* **Build 44 – ehrliche Frische, eigene Kerzen** — «Live» in der Status-Pille nur, wenn alle sichtbaren gehandelten
  Paare am Live-Strom und frisch sind; «veraltet» je Paar (am Strom 2 Min., abgefragt 3 × Hintergrund-Intervall,
  mind. 15 Min.; `domain/watch/LiveStatus.kt`, der tote `liveService`-Zweig fällt aus der Veraltet-Logik). Chart,
  Mini-Chart, Einzel-Widget, 24-h-Ersatzbezug und «Was bewegt sich mit?» nehmen zuerst die Kerzen der eigenen Börse des Paars
  (`data/remote/candles/`, `ExchangeCandleRegistry`, Quellen in `ExchangeCandleSources*.kt`), die Binance-Kette nur
  als gekennzeichneter Ersatz mit Token-Prüfung (passt eine Quelle nicht, ist die nächste dran; Perpetuals zuerst
  Futures; TradFi nie über die Kette; DexScreener nur der gemerkte Pool). «Gewinn heute» lässt Coins ohne
  Anfangskurs aus wie 7 T und nennt sie (Widget «Heute ohne …»), 24-h-Verlauf mit Stundenkursen der Stablecoins
  (USDT = 1), zugeklappter Wertverlauf ohne Käufe/Verkäufe (seit Build 46 Umschalter Wert | Gewinn/Verlust). Room 14 → 15
  (`MIGRATION_14_15`: `exchangeTime`, `priceChangedAt`; `schemas/…/15.json` von Hand, `identityHash` erst ein echter
  Build richtig): Börsen ohne eigene Zeit zeigen «Kurs unverändert seit HH:MM» (`PriceFreshness`, Widgets
  `WidgetFreshness`). Gas-/Aktivitäts-Hinweise gelten erst als verbraucht, wenn sie zustellbar sind (offene
  Aktivitätstypen je Paar); Portfolio-Alarm mit Compare-and-set; grosser Kurs im Blatt in Rubik; Widget-Chart
  mindestens 0,2 % hoch (Stablecoins 1 %). Portfolio-Tab bei Neuinstallation aus (bestehende behalten ihn),
  «Zum Portfolio hinzufügen» ist dann ausgeblendet; «Wie aktuell?» ab Werk Sparsam (empfohlen), Live «für Profis»;
  Textdurchsicht DE/FR/IT (kein «Aktionsblatt» in der App, FR durchgehend «vous», ehrliche Hinweise zu Live/Sparsam).
* **Build 43 – Zahlen und Lesbarkeit** — Stichtag-CSV mit Token-Prüfung («ungeprüft»), `PortfolioDecimal` begrenzt,
  Einzel-Widget nur mit passenden Kerzen, CoinGecko-Zuordnung 24 Std.; Widget-Receiver mit `ReceiverAsync`, Worker
  ohne veralteten Offline-Status, Gemini-Paare; Akzentfarben hell AA auf allen Karten, Fear & Greed mit
  `MarketScalePalette`, Widget-Schrift ≥ 11 sp; Namen und Umrechnung ab Werk an, Alpha aus; feste Wortliste DE/EN.
* **Build 42 – CoinGecko direkt, ehrliche Alarme, ruhiger Start** — CoinGecko-Daten nicht mehr gespiegelt
  (API-Bedingungen): Release «data» nur noch mit `fng.txt`, `onchain.txt`, `btcprice.txt`, `altseason.txt`; `/global`
  (30 Min.), Top 30/40 (24 h) und Top 40 für Spot-Tab und «Was bewegt sich mit?»-Marktbreite (eine gemeinsame Abfrage, 10 Min.)
  direkt bei CoinGecko, kein `COINGECKO_API_KEY`; kein eigenes Logo-Paket mehr (`build_logos.py`, `coin-logos.yml`,
  `docs/logos`, `logos.pack`, `stocks.txt` entfernt) — Rangliste (Seiten 1–10), Binance-/Alpha-Liste und Bilder direkt
  von CoinGecko/Binance, Aktiennamen von Nasdaq; «Powered by CoinGecko» (`DataFreshness.POWERED_BY_COINGECKO`,
  ≥ 10 sp) unter CoinGecko-Karten, bei Coin-Logos, in Über und Lizenzen; `LICENSE`: Namen, Logos, Marken und Marktdaten
  Dritter nicht unter MIT. Alarme: Vorschlag ±5 % vom Kurs (`AlarmTemplates.suggestedThresholdText`), Rückfrage «Alarm
  löst sofort aus» (`AlarmEdit.firesNow`), Bearbeiten stellt nur bei geänderter Bedingung neu scharf (`AlarmEdit.merged`),
  «Konnte nicht geprüft werden (kein Devisenkurs / keine Kerzendaten)» in der Alarmliste (`AlarmCheckStatus`, nur im
  Speicher). Neue Installationen: «Nicht wegwischbar» aus, neue Paare ohne Kurs-Benachrichtigung (bestehende behalten
  ihren Wert, `OngoingNotificationsMigration`); «+ Paar hinzufügen» mit Text, Glocke immer sichtbar; veraltete Kurse
  in `onSurfaceVariant` statt halb durchsichtig.
* **Build 41 – Alarme, Charts, ruhigere Screens** — Alarme warten auf eine zustellbare Mitteilung, auch wiederholende
  (`QuietHours.mayFire`); Fremd-Kerzen im Aktionsblatt und Mini-Chart nur bei gleichem Token (`SheetChart.accept`,
  `sparklineFor`, Paritätsdatei `sheet_chart.json`); `sameToken` streng; «Kurs vorlesen» schaltet die Sprachausgabe
  ein; «Wie aktuell?» unter Alarme; Markt-Tab mit Unterseiten «Einordnung»/«Daten» (`MarketSectionScreen.kt`);
  Zurück aus «Paar hinzufügen» in den Markt-Tab; Hinweis «Durchschnitt, nicht FIFO» an «Seit Kauf».
* **Build 40 – Portfolio-Zahlen, ehrliche Zeilen, Alarme** — `PortfolioExchangeRoute.scale` rechnet Binance-Kerzen nur
  bei gleichem Token hoch (`PricePlausibility.sameToken`), `scaleRanges` auch für Hoch/Tief/Eröffnung; kein alter
  Devisenkurs (`FxRateSource.cached` mit 24-Std.-Grenze); Kauf ohne Preis im 7-T-Fenster lässt den Coin aus
  (`PortfolioPeriodChange.parts(skipUnpricedTrades)`); Paritätsdatei `portfolio.json`; Portfolio-Alarm bei App-Sperre
  ohne Beträge; Zeilen-Hinweis «Änderung/Chart: Börse» (`DayChange.rowSource`); Kursfarben, %-Änderung, Logos und
  Widgets unter «Darstellung»; «Immer kurz» und `PriceService` entfernt (kein Vordergrunddienst); Alarme in der
  Sicherung geprüft (`AlarmJson`, `BackupManager.invalidEntries`); Binance-Gruppenpause; `NotificationIds`.
* **Build 39 – Datensicherheit** — Sicherung: ungültige Portfolio-Einträge werden gezählt und in der Rückfrage
  genannt (`PortfolioJson.parseTxs`, `BackupManager.invalidPortfolioEntries`, `restore(…, acceptInvalid)`),
  Lesen mit Grenze (`BackupLimits`: 16 MB, Tiefe 32), Portfolio-Notiz 120 Zeichen; Live-Strom leert den Zustand
  vor dem Speichern (`LivePriceStream.stopAll`); Alarm speichern und melden in einem `NonCancellable`-Schritt
  (`RefreshEffects.report`, `PortfolioAlarmChecker`); Portfolio-DB mit `secure_delete` und einmaligem VACUUM;
  Gas-Alarm nach Fehler in 2 Min. erneut.
* **Build 38 – Alarme und Futures wie im Code** — Binance COIN-M mit eigenen Kennzahlen von `dapi`
  (`FuturesParsers.binanceCoinM`, `FuturesSources.own(marketKey, pairId)`); unbekanntes Funding-Intervall
  nicht mehr als 8 h (`FuturesSources.fundingIntervalHours` → null, kein Funding-Alarm), 8-h-Wert in der
  Futures-Karte; Portfolio-Alarm über Devisenkurs bei anderer Währung (`PortfolioReading.usdTo`), %-Alarm
  erst nach deutlicher Rückkehr scharf (`changeRearmMargin`); Gebühr eines Verkaufs ohne Bestand zählt nicht;
  Kerzen «Binance Futures» getrennt von Spot; `ThresholdParser` Kurs-Hinweis nur bis Faktor 10.
* **Build 37 – ehrliche Zahlen** — «Heute / 7 T / 1 J» lässt einen Coin ohne Anfangskurs aus statt alles zu
  streichen (`PortfolioPeriodChange`, `skipped`); ein «Heute» für Karte, Widget und Alarm (`withHonestToday`);
  Portfolio-Alarm nach Anteil frischer Kurse (`PortfolioPriceFallback.freshShare`, 80 %); Funding-Intervall je
  Kontrakt (`FuturesInfo.fundingIntervalHours`); Kurs von der eingetragenen Börse (`PortfolioExchangeRoute`,
  `PortfolioPriceSource`), Zeile «Kurse: Binance · USDT = 1»; Bitcoin-Kennzahlen mit Einordnung seit 2016 und
  «bei gleichem Kurs in 30 Tagen» (`IndicatorHistoryMath`), Pi-Cycle-Abstand, Halving aus der Blockhöhe
  (`HalvingMath`), eigene Grenzen als Meldung (`IndicatorAlertRules`, `ZoneCheckWorker`).
* **Build 36 – Import** — nach dem Einspielen einer Sicherung fragt die App nach der Erlaubnis für
  Mitteilungen (Android 13+; «App zurücksetzen» löscht sie mit `clearApplicationUserData`) und holt gleich
  Kurse (`scheduler.refreshNow`); Merklisten-Widget blendet «Noch keine Paare» selbst ein/aus
  (`ListWidgetRows.count`, statt `setEmptyView`); Token-Symbol als Raute (`ic_token.xml`).
* **Build 35 – exaktes Portfolio-Journal** — Mengen, Kaufpreise und Gebühren werden exakt gerechnet
  (`BigDecimal`, `domain/portfolio/PortfolioDecimal.kt`; bis 18 Nachkommastellen, 1 BTC = 100 000 000 Satoshi,
  0.00000001 BTC bleibt 0.00000001). Room-Portfolio-Datenbank Version 2: Spalten als Text, Migration 1→2 baut die
  Tabelle um (`MIGRATION_1_2`, Test `portfolioVersion1BecomesExactTextWithFeeAndExchange`; `schemas/…/2.json`
  schreibt Room beim Build neu). Bewertung mit Marktkursen bleibt `Double`. Sicherung und Portfolio-Datei schreiben
  zusätzlich `amountText`/`priceText`/`feeText` (Zahlenfelder bleiben für ältere Versionen; beim Lesen gewinnt der Text).
* **Gebühr und Börse pro Transaktion** — im Transaktions-Blatt unter «Gebühr und Börse»: Gebühr in USDT (erhöht den
  Einstand, beim Verkauf senkt sie den Erlös) oder im Coin (verringert die Menge); Börse nur zur Übersicht.
  Durchschnittskosten (seit Build 46: FIFO Standard, Ø wählbar), Detailzeile «Gebühr …» (`PortfolioCalculator.kt`, `PortfolioTxSheet.kt`).
* **Ehrlicher Gewinn** — Heute · 7 T · 1 J rechnen Käufe/Verkäufe im Zeitraum heraus (Wert Ende − Wert Anfang −
  Zuflüsse; ein Kauf ist kein Gewinn), «Seit Kauf [USDT]» zählt nur Coins mit Kaufpreis *und* Kurs, die übrigen stehen
  im Hinweis «Ohne N Coins ohne Kaufpreis (…)»; (i) neben «Gewinn/Verlust» erklärt die Rechnung
  (`PortfolioPeriodChange.gain`/`todayAdjusted`, `PortfolioTotalCard.kt`, Test `PortfolioLedgerTest`).
* **Markt-Tab neutral** — statt Marktphase, Zonen und Top-/Bottom-Hinweisen die Karte «Bitcoin-Kennzahlen» mit
  gemessenen Werten (Mayer Multiple, 200-Wochen-Schnitt, Abstand zum Hoch, 30 Tage, SMA 50/200, RSI Woche/Tag,
  MVRV, Puell, Hashrate 30/60) je mit einem Satz Erklärung, ohne Bewertung (`domain/market/CycleModel.kt`);
  Coin-Karte: RSI, Trend und Stärke gegenüber Bitcoin (`CoinCycleModel.kt`); Halving nur als Monat «~Apr 2028»
  (`BitcoinCycle.kt`, Intervall 1458 Tage); «Altcoins gegenüber Bitcoin» als Anzahl statt «Altcoin-Saison».
  Zonen-Meldungen entfernt (alter Sicherungs-Schlüssel wird ignoriert), Markt-Meldungen: Fear & Greed, Gas,
  «Auffällig», Wirtschaftstermine.
* **«Wie aktuell?»** — ersetzt die Schalter «Häufig aktualisieren»/«Live-Kurse»: Sparsam (empfohlen, Standard seit Build 43, 15 Min.) · Live, solange offen
  (für Profis); «Immer kurz» seit Build 40 entfernt, alte Werte werden zu «Live, solange offen» (`settings/UpdateMode.kt`, DataStore-Migration aus den alten Schaltern; die
  Sicherung schreibt `updateMode` und weiter die alten Felder). Merkliste zeigt «Live» nur, wenn alle sichtbaren
  gehandelten Paare am Live-Strom und frisch sind (seit Build 44, `LiveStatus.allLive`).
* **Kleinere Korrekturen** — Aktionsblatt «Mehr» klappt pro Coin auf, Akzent-Buttons in Einstellungen mit
  `accentButtonColor`, Widget-Vorschau ohne abgeschnittene Zeile, «Akzentfarbe», «Hell / Dunkel», klarere
  Stand-Texte («Aktuell · …», «Veraltet · Stand …»), Portfolio-Hinweis überarbeitet.
* **Farbige Schrift statt Pillen** — in der App keine Änderungs-Pillen mehr: Pfeil + Wert mit Vorzeichen (echtes
  Minus U+2212) in der Kursfarbe, grau bei 0/unbekannt/deaktiviert, Durchschnitt «Ø» in der gelben Warn-Schriftfarbe
  (`AppColors.warningText`), Zeitfenster klein und grau. Merklisten-Zeilen, Puls-Zeile («↗ 233 steigen ↘ 295 fallen
  Ø +0.32%»), Aktionsblatt («↗ +41.71% · 24h · Aktualisiert …»), Marktdaten (Funding als farbige Schrift ohne
  Pfeil), «Was bewegt sich mit?» (Faktoren unter «Kurz gesagt» als eine graue Textzeile), Markt-Karten (Fear-&-Greed-Zahl in der
  Stufenfarbe, «Krypto-Markt 2.82 Bio. USD ↘ −2.77% · 24h», «Auffällig» mit grauem «24h») und Portfolio
  (Heute · 7 T · Gesamt als farbige Schrift, Beträge mit Pfeil «↗ +12.40 USDT»; neue Zeile unter dem Gesamtwert mit
  der Veränderung über ein Jahr «↘ −38'412.95 USDT −27.85% · 1J», bei weniger als einem Jahr Verlauf ausgeblendet).
  `ChangePill` / `TonePill` (`ui/components/ChangePill.kt`) zeichnen nur noch Text, Farbe aus `pillContent(PillTone)`.
* **Einstellung «Schrift auf Pillen und Buttons» entfernt** — Buttons mit Themenfarbe als Hintergrund nutzen wieder
  die normale Kontrastfarbe des Themas (`AppColors.onAccent`); alte Sicherungen mit `pillTextColor` lassen sich
  weiter einspielen (Feld wird ignoriert, `data/BackupSettingsCodec.kt`), der Export schreibt es nicht mehr.
* **Einstellungs-Symbole einfarbig** — Symbole der Hauptseite in der Textfarbe (dunkel weiss, hell schwarz), Alarme
  mit Glocke, Markt-Meldungen mit Blitz; nur «App zurücksetzen» bleibt in der Fehlerfarbe. Die Themenfarbe bleibt
  für Titel-Striche, Schalter und Auswahl.
* **USDT zum Marktkurs** (Owner-Entscheid Okt. 2026) — USDT bleibt die interne Rechenwährung, in Fiat gilt sein
  Marktkurs USDT/USD: live die Messung von `DepegAlarmChecker` (Kraken USDT/USD, Coinbase USDT-USD, Binance USDC/USDT nur als dritte Quelle; Einbruch nur bestätigt, `DepegAlarm.measure`; gemessen, sobald ein
  Portfolio da ist, auch ohne Depeg-Alarm; `DepegReadingStore.liveUsdtUsd`, höchstens 6 Std. alt), für vergangene Tage
  der Kraken-Tagesschluss USDTZUSD (`PortfolioHistorySource.usdtUsdDaily`, 12 h gemerkt). Gesamtkarte, Zeilen,
  Wertverlauf, «7 T»/«1 J», Widget, Portfolio-Alarme, «≈ Umrechnung», Kurse eigener Börsen in USD/Fiat, Steuer- und
  Stichtag-Export rechnen damit (`UsdtUsdRates`, `PortfolioStables.usdtTo`). Ohne Kurs gilt 1 als Näherung und ist
  markiert («USDT = 1 angenommen», im CSV je Zeile). Auch in USD: Karte, Verlauf und Widget zeigen USD (USDT × USDT/USD), nicht mehr USDT 1:1.
* **Ehrliche Portfolio-Kurse** — USDT ist die Bewertungsbasis (1 USDT = 1 USDT, in Fiat zum Marktkurs, siehe oben); USDC, DAI … ohne Marktkurs haben keinen
  Wert (Position «—» und «ohne Kurs», zählt unter «ohne N Coins», Gesamtgewinn unbekannt; `PortfolioStables.price`
  gibt `null`), im Wertverlauf landen solche Stablecoins ohne Kerzen unter «ohne …»; die ≈-Umrechnung der Merkliste
  zeigt für sie seit Build 45 ohne Marktkurs keinen Wert (nur USD/USDT = 1). Kerzenkurs für Coins, die Binance Spot nicht führt, nur nach Identitätsprüfung 1/3…3
  gegen den Median der Merklisten-Kurse desselben Coins (USD-stabile Spot-Paare, aktiv, höchstens 30 Tage alt) bzw.
  den letzten guten gemerkten Kurs (`PricePlausibility.candleReference` / `acceptCandle` in
  `data/portfolio/PortfolioPriceSource.kt`, Faktor 3 über `sameToken`); passt er nicht → der letzte gute Kurs bleibt
  mit seiner «Stand»-Zeit (ohne gemerkten Kurs «—»); ohne Bezug seit Build 45 kein Kurs («—», «Kurs ungeprüft»,
  `PortfolioPrices.unchecked`), vorher gilt ein Merklisten-Paar an einer anderen Spot-Börse. Nur Daten auf dem
  Gerät, keine neue Abfrage. Tests
  `PortfolioStablesTest`, `PricePlausibilityTest`.
* **Ehrlicher Alarm-Takt** — der Hinweis zum Prüftakt erscheint einmal nach dem Speichern des ersten Alarms (in der
  Erst-Bestätigung «… nicht öfter als alle 15 Minuten und oft später …»; nie zwei Dialoge nacheinander; sonst
  einmal «Wann Alarme geprüft werden», solange «Häufig aktualisieren» aus ist) und steht dauerhaft hinter dem (i)
  neben «Bei geschlossener App…» im Alarm-Editor und im Portfolio-Alarm-Blatt. Pfad im Text: «Einstellungen ›
  Erweitert › Aktualisierung › Häufig aktualisieren» (`domain/alarm/AlarmTimingHint.kt`, `AlarmTimingHintTest`). Merker `alarm_timing_hint_shown` nur auf dem Gerät, nicht in der Sicherung.
* **Portfolio-Tab: neu aus, bestehend an** — seit Build 44 bei Neuinstallationen ab Werk aus
  (`AppSettings.portfolioEnabled = false`); bestehende Installationen behalten den bisherigen Standard an
  (`LEGACY_PORTFOLIO_ENABLED`, einmalig festgeschrieben von `InstallDefaultsMigration`), eine eigene Wahl bleibt;
  Sicherung ohne Feld → an. Solange aus, ist «Zum Portfolio hinzufügen» im Blatt eines Paars ausgeblendet (`PortfolioDefaultTest`).
* **Aktionsblatt ruhiger** — doppelte Änderungs-Zeile entfernt; unter dem Kurs eine Zeile «↗ +41.71% · 24h · Aktualisiert …»,
  dann direkt die Kacheln Alarm · Warum? · Favorit, dann Chart, Zeitraum, Marktdaten, Mehr; am Zeitraum keine zweite
  24h-Änderung (bei 7T/30T/1J bleibt sie als farbige Schrift, sie zeigt die Veränderung des Zeitraums) (`WatchActionsSheet.kt`).
* **Einstellungen mit «Erweitert»** — Hauptseite Allgemein (Sprache, Währung, Merkliste, Portfolio), Darstellung
  (Hell/Dunkel, Akzentfarbe), Alarme (Alarme, Markt-Meldungen), Daten (Sicherung), Zeile «Erweitert»
  (`AdvancedSettingsPage` in `ui/features/settings/SettingsScreen.kt`: Aktualisierung, Kursfarben, %-Änderung, Coin-Logos, Widgets, Sprachausgabe), Über, «App zurücksetzen» als eigene Karte ganz unten; die
  Suche findet alles, Punkte der Seite «Erweitert» mit dem Pfad «Erweitert › …» (`SettingsSearchUi.kt`).
* **«Was bewegt sich mit?» kurz zuerst** — oben «Kurz gesagt · Stand HH:MM» mit einem Satz, bis zu 3 auffälligen Faktoren als
  eine graue Textzeile und «Sicherheit»; zugeklappt «Alle Werte und Quellen» (Beobachtung, Mögliche Einordnung, Quellen,
  langer Hinweis); kurze Fusszeile «Zeitgleich heisst nicht Ursache · Keine Anlageberatung»
  (`ui/features/watchlist/WatchWhySheet.kt`).
* **Widgets** — Vorschauen neu gezeichnet mit aktuellen Beispielwerten (BTC 83'142.00, ETH 2'505.49, SOL 109.60,
  XRP 1.394): PNG `drawable-nodpi/widget_preview_*_img.png`, Android-12+-Vorschau `layout/widget_preview_*.xml` und
  deren Werte in `values/widget_preview_samples.xml`; Trennlinie im Merklisten- und Portfolio-Listen-Widget neutral
  (Textfarbe 25 %, bei hohem Kontrast 45 %) statt Akzentfarbe (`WidgetContrast.dividerColor`, `WidgetContrastTest`).
* **Coin-Logos nach Offline-Erststart** — ein ohne Netz gescheiterter Abgleich sperrt keine weiteren Versuche mehr;
  er läuft wieder, sobald die Verbindung zurück ist, mit wachsender Pause (5 s → 15 s → 30 s → 60 s … höchstens
  15 Min.), und beim Wechsel in den Vordergrund; nie zwei Abgleiche gleichzeitig (`domain/logos/CoinLogoRetry.kt`,
  `data/CoinLogoSync.kt` mit `ConnectivityMonitor`, `CoinLogoRetryTest`).

* **«Was bewegt sich mit?» relativ zur eigenen Schwankung** — eine 24-h-Bewegung gilt erst ab k · σ der Stunden-Renditen
  (bis 72 h vor dem 24-h-Fenster; Coin k = 2, Untergrenze 2 %, Markt k = 1,5, Untergrenze 1 %; zu wenig Verlauf
  → feste Schwellen; `RelativeMove` in `domain/activity/WhyContext.kt`). Neben BTC und ETH der breite Markt:
  Median der 24-h-Veränderung der Top 40 (direkt bei CoinGecko, dieselbe Abfrage wie der Spot-Tab, 10 Min. im
  Speicher) ohne Stablecoins/BTC/ETH (mind. 10 Coins, höchstens
  3 h alt, `domain/activity/BroadMarket.kt`); «marktweit» bzw. «nur dieser Coin» berücksichtigt alle drei. Je
  Faktor Quelle und Alter («Kerzen: Binance · 14:00», «Marktbreite: CoinGecko · 13:05», «Futures: Bybit · OI vor
  2 Std. verglichen», «Fear & Greed: heute»; Ausweichquelle/«Richtwert» gekennzeichnet, `WhyFreshness`).
  Open-Interest-Verlauf mit bis zu 5 Messungen (≥ 30 Min. Abstand, ≤ 12 h, gleiche Quelle, Median, gegenläufig →
  «uneinheitlich»; alte Einzelmessung übernommen; `OiSeries.kt`); nahe am 30-Tage-Tief; Lage in 3 Tagen
  (`Position3d`); Funding-Trend «seit 24 Std. steigend» (`FundingTrend.kt`, letzte 4 Abrechnungen, höchstens
  alle 10 Min. je Paar); Stablecoins nach Abweichung von 1:1 (`StablePeg`) ohne Markt- und Schwankungsfaktoren;
  Volumen «geschätzt» ohne Quote-Volumen, entfällt bei mehr als 6 von 24 Std. ohne Handel; Schwankung 1 h erst
  ab 0,5 % Bewegung. Kerzen für «Was bewegt sich mit?» mit `limit=100` (vorher 30), dazu ETHUSDT-Stundenkerzen über die
  bestehende Kerzen-Kette. Seit Build 44 kommen die Coin-Kerzen für «Was bewegt sich mit?» zuerst von der eigenen Börse des Paars
  («Kerzen: Kraken · 14:00»); die Binance-Kette nur als gekennzeichneter Ersatz mit Token-Prüfung. Gemeinsame
  Testfälle `why_analysis.json` (`WhyAnalysisParityTest`, `WhyAnalysisTest`).
* **Chart im Aktionsblatt** — zuerst die Kerzen der eigenen Börse (seit Build 44, `OwnCandleDataSource`), sonst die
  Binance-Kette nur bei gleichem Token und mit Quelle in der Zeile; Fiat-Paare ohne eigene Kerzen rechnen die USDT-Kerzen mit dem historischen
  Tageskurs je Kerze um (Frankfurter-Zeitreihe wie im Portfolio, höchstens einmal je Währung in 6 h,
  `data/SheetChartRepository.kt`); Zwischenspeicher verworfen bei manueller Aktualisierung, nach unten ziehen
  oder Kurssprung > 3 % (`SheetChart.PRICE_JUMP`); Zeile unter dem Chart mit Börse, «umgerechnet mit
  Tageskursen» bzw. «(aktueller Kurs)» und «Stand 14:00»; Eröffnung/Hoch/Tief/Schluss beim Ziehen; Achse bei
  Stablecoins mind. ±0,5 % (sonst ±0,1 % bei flacher Reihe); Geometrie ausserhalb des Hauptthreads;
  angenäherte Volumen-Balken halb so kräftig mit «≈».
* **Futures-Daten je Börse** — Funding und Open Interest von der beobachteten Börse für WOO X, Bitget, MEXC,
  HTX, Hyperliquid und Deribit (dazu wie bisher Binance, Bybit, OKX; `data/remote/FuturesExchanges.kt`,
  `FuturesSources`, Tests `FuturesParsersTest`); Phemex und andere bzw. ausgefallene eigene Quelle: Richtwert
  von Binance → Bybit → OKX mit Hinweis «Richtwert von Binance Futures – Funding und Open Interest gelten je
  Börse, bei … können sie abweichen.»; Spot-Paare «Richtwert … (USDT-Perpetual des Coins)». Funding-Alarme
  auf Richtwert melden mit « · Richtwert Binance Futures», Open-Interest-Alarme pausieren (kein gemischter
  OI-Verlauf, `DerivativesAlarmData`); Hyperliquid-Funding «pro Std.», für Alarme und Trend × 8
  (`DerivativesAlarm.fundingPer8h`).
* **Alpha-Tokens** — `domain/watch/AssetKind.kt` (Token / TradFi / Alpha; Alpha = Binance-Alpha-Liste ohne
  regulär an Binance Spot gelistete Kürzel, nie Binance-Spot-Paare oder DEX-Pools); Chip «α» neben
  ★/⚡/Token/TradFi (`WatchFilter.ALPHA`, Alpha zählt auch als Token); Schalter «inkl. Alpha-Tokens (α)» unter
  «Token & TradFi» (ab Werk an, bei «Nur TradFi» gesperrt wie QTLY, in Sicherung und Einstellungs-Suche,
  `AssetScope` mit `hiddenAlpha`); Symbol der Art vor der Glocke in der Zeile (`ui/components/AssetKindIcon.kt`,
  grau, in der Akzentfarbe bei gewähltem Chip, TalkBack «TradFi»/«Alpha-Token»). Tests `AssetKindTest`,
  `CoinLogosAlphaTest`, `AssetScopeTest`, `WatchFilterTest`.
* **Portfolio und Widgets** — Gesamtwert mit Einheit «Gesamt [USDT]» (Widget «Gesamt [CHF]» usw.);
  Merklisten-Widget übernimmt eine geänderte Gruppe sofort nach dem Einrichten (Zeichnen nacheinander),
  «α» im Einrichten, gemeinsame Auswahl `widget/WidgetGroupSelection.kt` (`pick` / `options`,
  `WidgetGroupSelectionTest`).
* **Widgets: Zeitraum, passende Zeilen** — Listen-Zeilen des Portfolio-Widgets mit Zeitraum vor der Änderung («24h ↘ −0.25 %»,
  «heute» bei Basis seit 00:00), passend zur verwendeten %-Basis. Merklisten-Widget: Zeilen tragen eine
  Zeichen-Version (`widget/ListWidgetDataVersion.kt`, Token in der Adapter-Adresse; der Zeilen-Dienst lädt neu,
  wenn er dahinter ist, `ListWidgetDataVersionTest`) – nie mehr neue Zeit über alten Zeilen. Puls-Widget:
  Fear-&-Greed-Zahl in den fünf Stufenfarben, «↗ 21» grün / «↘ 9» rot, «Top 30 · 24h», «24h» vor den Coins.
* **Markt und Portfolio mit Status-Pille** — gemeinsame `ui/components/DataStatusPill.kt` (aktuell grün,
  offline/lädt grau, veraltet rot). Markt: «Auffällig» mit «24h» über den Coins, «Top 30 · 24h»,
  Marktkap. als «Krypto-Markt … ↘ −2.77% · 24h»; Fear-&-Greed-Zahl als farbige Schrift; «Offline · Stand HH:MM» behält die
  letzten Daten, lädt offline nichts und holt abgelaufene Daten nach, sobald das Netz zurück ist. Portfolio:
  «Alles aktuell» / «Kurse veraltet · Stand HH:MM» / «Offline · Stand HH:MM»; letzter Kurs je Coin mit Zeitpunkt
  gemerkt (`domain/portfolio/PortfolioPriceFallback.kt`, Datei `portfolio_last_prices.txt` in
  `noBackupFilesDir` über `PortfolioPriceSource`, höchstens 30 Tage und 300 Coins) – offline bzw. nach dem Start
  der letzte Wert statt 0.00, veraltete blass; veraltet nach 3 × Hintergrund-Intervall (mind. 15 Min.), dann
  nicht in Widget-Stand, Wertverlauf und Alarm-Prüfung; nie in einer Sicherung, mit «Portfolio leeren»
  gelöscht (`PortfolioPriceFallbackTest`).

* **Sperre, Datenbank, Boot-Receiver, «7 T»** — Sperrt sich die Sitzung wieder (> 60 s im Hintergrund),
  schliessen sich offene Sicherungs-Dialoge (Sichern-Dialog und Wiederherstellen-Schritt) wie die
  Erfassen-Blätter; ein schon bestätigter Export bzw. ein schon schreibendes Wiederherstellen läuft fertig,
  eine erst nach dem Sperren gewählte Datei wird verworfen (erneut tippen und entsperren). Die
  Hauptdatenbank setzt bei jedem Öffnen `PRAGMA secure_delete = ON` (gelöschte bzw. überschriebene Inhalte
  werden mit Nullen überschrieben, `di/LocalDataModule.kt`); nach einem Update von DB v12 wird das VACUUM der
  entfernten alten Portfolio-Tabellen bei jedem Start wiederholt, bis es gelingt (Merker `legacy_vacuum_done`
  in `portfolio_meta`; WAL-Checkpoint → VACUUM → Checkpoint; `PortfolioLegacyPlan.vacuumPending` mit Test,
  `DatabaseMigrationTest` prüft den Merker). `work/BootReceiver` hört nur noch auf die geschützten
  System-Broadcasts `BOOT_COMPLETED` und `MY_PACKAGE_REPLACED` (kein `QUICKBOOT_POWERON` mehr, eine
  ungeschützte Hersteller-Aktion) und plant nur neu ein. «7 T» im Portfolio: ein Stablecoin ohne
  Tagesschluss zählt nur als 1, wenn sein aktueller Kurs höchstens 0,1 % von 1 abweicht, sonst «—» (USDT
  immer 1; `PortfolioStables.fallbackWithoutClose`, Test in `PortfolioPeriodChangeTest`).

* **Paarlisten, «nicht mehr gehandelt», Portfolio-Zahlen, Sicherung** — OKX Futures lädt die Paarliste aus
  `/api/v5/public/instruments?instType=SWAP` (nur `state` «live»; `instCategory` macht Aktien und Rohstoffe wie
  NVDA, AAPL, XAU zu TradFi statt Token), Kurse weiter aus `market/tickers`; Bybit folgt bei
  `instruments-info` dem `nextPageCursor` (höchstens 10 Seiten); Vertragstests mit Fallart «pairs». Fehlt ein Paar
  in der vollständigen Sammelabfrage, wird es einmal einzeln abgefragt und nur als «nicht mehr gehandelt»
  markiert, wenn die Börse ohne Kurs antwortet (Fehlertext, keine Daten, HTTP 4xx ausser
  401/403/407/408/418/429/451); Netzfehler und Rate-Limits bleiben gewöhnliche Fehler, bereits markierte Paare
  werden nicht erneut gefragt und kehren zurück, sobald die Sammelabfrage sie wieder führt
  (`NotTraded.askSingleWhenMissing` / `confirmedBySingle`, `not_traded.json` erweitert). Bybit live: ein Delta
  ohne `lastPrice` erneuert die Zeit nicht mehr. Börsen ohne Handelszeit zeigten damals die Abrufzeit (seit Build 44
  ersetzt: `exchangeTime`/`priceChangedAt`, «Kurs unverändert seit HH:MM», `PriceFreshness`). Wertverlauf und «7 T» werfen einen Coin nur noch aus,
  wenn sein jüngster Tagesschluss ausserhalb 1/3…3 des aktuellen Kurses liegt (anderer Token,
  `PricePlausibility.sameToken`), nicht nach einem normalen Tag mit 30 %; die 25-%-Regel der %-Änderung
  (`DayChange`) bleibt. Realisierter Gewinn/Verlust bei Käufen ohne Preis: der Anteil mit Preis zum
  Durchschnitt der Käufe mit Preis, der Rest (und Verkäufe ohne Preis) als unbekannt
  (`realizedUnknownAmount` / `realizedIncomplete`, Anzeige «+X USDT + —» bzw. «—»); ganz verkaufte Coins stehen
  immer unter «Geschlossen». ≈-Zeile der Merkliste: andere Stablecoins als USD/USDT mit dem zwischengespeicherten
  Marktpreis des Portfolios (`PortfolioPriceSource.cached`), ohne ihn seit Build 45 kein ≈-Wert, ohne neue Abfrage. Präfix «1000» bewusst
  unverändert (1000SATS = 1000 × SATS, 1000CAT = 1000 × Simon's Cat; Parity-Fälle). Sicherung: neue Dateien mit
  PBKDF2-HMAC-SHA256 und 600 000 Durchläufen (bisher 210 000), ältere bleiben lesbar («iter» aus der Datei);
  seit Build 45 neue Passwörter mindestens 10, seit Build 69 12 Zeichen, schwache (nur Ziffern, Wiederholung, häufige Folge) gesperrt,
  ältere Dateien bleiben lesbar (`BackupCrypto.ITERATIONS`, `MIN_PASSWORD_LENGTH`, `isWeak`).

* **Suche nach Coin-Namen** — Hinweis «Paar, Name oder Börse suchen» bei «Paar hinzufügen», in der
  Merkliste und beim Bearbeiten einer Gruppe; «bitcoin» findet BTC, «ether» ETH, ohne Akzente, Name mit
  Quote («ethereum eur»); Reihenfolge genaues Kürzel → Kürzel-Anfang → Name-Anfang → Name enthält →
  Börse; «Paar hinzufügen» sucht auch nach Börse («kraken», «btc kraken», Börsenwort ab 3 Zeichen).
  Namen nur aus Daten auf dem Gerät (Logo-/Namensliste, Aktiennamen), keine neue Abfrage
  (`CoinLogoRepository.loadSavedNames` liest die gespeicherte Datei offline;
  `ui/features/explorer/ExplorerPairSearch.kt`, `ui/features/watchlist/WatchlistSearch.kt`; Tests
  `PairSearchTest`, `WatchSearchTest`).
* **Karte «Marktdaten» im Aktionsblatt** — Register «Spot | Futures» (vorher «Futures-Daten», nur bei
  Futures-Paaren) bei allen Krypto-Paaren (nicht bei deaktivierten Paaren, DEX-Pools, TradFi-Spot).
  Spot (CoinGecko, USD, kompakt): Marktkapitalisierung mit 24h-Änderung (farbige Schrift), Volumen 24h, Vol./Marktkap., FDV,
  Umlauf/Gesamt/Max. mit Balken, «Quelle: CoinGecko · Stand …», «Powered by CoinGecko»; zuerst aus den Top 40
  (direkt bei CoinGecko, für alle gleich), sonst eine Abfrage `coins/markets?symbols=…`, 10 Min. im Speicher (`domain/spot/SpotMarket.kt`,
  `data/remote/SpotDataSource.kt`, `WatchSheetSpot.kt`, gemeinsame Testfälle `spot_market.json` /
  `SpotMarketParityTest`). Futures: Funding (farbige Schrift ohne Pfeil) mit Countdown, Open Interest; bei Spot-Paaren das
  USDT-Perpetual des Coins (`fetchForBase`, Binance → Bybit → OKX, als «Richtwert» gekennzeichnet) erst beim
  Öffnen des Registers (`WatchSheetFutures.kt`). Start-Register Futures bzw. Spot je nach Paar, für die Sitzung gemerkt;
  (i) erklärt das offene Register. Im Kopf wird der grosse Kurs kleiner statt
  abgeschnitten.
* **Ehrliche Meldungen und Gerätetests** — Markt-Meldungen (Marktphase, Fear & Greed) beachten Nachtruhe
  und Mitteilungs-Erlaubnis (`domain/market/MarketNoticeGate.kt`; «zuletzt gemeldet» rückt nur nach einer
  gezeigten Meldung vor); `PriceUpdateWorker` startet einen beendeten «Häufig aktualisieren»-Dienst neu
  (`service/LiveServiceGate.kt`); Banner nach dem Speichern eines Alarms, ehrlich bei ausgeschalteten
  Mitteilungen (`domain/alarm/AlarmSavedText.kt`); Quelle der Tagesveränderung «Binance USDT» mit
  Devisen-Prüfung für Nicht-USDT-Quotes (`FxRateSource`); instrumentierte Tests auf dem Emulator über den
  Workflow «Android device tests» (von Hand, siehe `DEVELOPMENT.md`).
* **Portfolio-Widget mit Optionen** — je Widget einstellbar und nachträglich änderbar
  (`PortfolioWidgetConfigureActivity`): Ansicht «Übersicht / Chart / Liste» (`widget/PortfolioWidgetViews.kt`),
  Anzeige «Nur USDT / Nur Umrechnung / Umrechnung + USDT», Zeitraum
  24 h / 7 T / 30 T / 1 J, Kerzen oder Linie; Chart wie in der App (Hilfslinien, «Hoch»-Marke, Kursfarben);
  7 T / 30 T / 1 J aus dem Tagesverlauf, den der Portfolio-Tab schon gespeichert hat, sonst 24 h mit Hinweis;
  Coins ohne Kurs werden gezählt («ohne 2 Coins») statt still eine zu tiefe Summe. Gesamtwert-Karte
  «Heute · 7 T · Gesamt» (`domain/portfolio/PortfolioPeriodChange.kt`), Volumen-Balken im Coin-Chart.
* **Einheitliche Zahlen und Zeichen** — jede %-Änderung und jeder Wert mit Vorzeichen als farbige Schrift ohne Pille
  (`ui/components/ChangePill.kt`: `ChangePill`, `TonePill`, `PillTone` — Pfeil + «+» bzw. «−» (U+2212) in der
  Kursfarbe, grau für 0/unbekannt, gelbe Warn-Schriftfarbe für neutrale Durchschnitte); schräge Pfeile ↗ ↘ statt ▲/▼; ein Aufklapp-Pfeil in
  der Themenfarbe (`ExpandChevronIcon`); Quote-Währung nach dem Kurs klein und grau (`PriceWithQuote`);
  Merkliste und die übrigen Coin-Listen als eine Einheit (`ListSegment`); (i) mit kurzer Erklärung bei
  jeder Zeile der Markt-Karten (`market_info_*` in `strings_market.xml`); Einstellungen mit Wert unter dem Titel;
  Tab-Leiste mit Namen in Textfarbe, aktives Icon in Akzentfarbe und 10 % grösser; Tab-Icon vor dem Titel bei Markt, Portfolio und Einstellungen (`ui/components/TabTitle.kt`); Währung nach Beträgen klein und grau auch in allen Widgets (`widget/WidgetQuoteText.kt`) und im Portfolio; Entwickleroptionen (7 Tipps auf die Version) mit Countdown bei den letzten 3.
* **Paar hinzufügen** — «Zuletzt gesucht» nur auf dem Gerät (`ExplorerRecentSearches.kt`, nicht in
  Sicherungen) und Schnellfilter Gewinner / Verlierer / Volumen bei «Alle …-Paare» aus einer Sammelabfrage
  der Börse, höchstens einmal pro Minute (`ExplorerQuickFilter.kt`). Die Sicherung enthält jetzt auch die
  Spalten-Sortierung der Merkliste.
* **Paar deaktivieren und Nachtruhe** — `WatchEntity.disabled` (DB v14, `QuickView.DISABLED`), deaktivierte Paare ohne Abfragen und Meldungen; Alarme warten in der Nachtruhe (`QuietHours.holdsAlarms`); Hinweis zur geschlossenen App im Alarm-Editor.
* **Einheitlich für den Live-Test** — Chips mit Namenshinweis und Token/TradFi-Filter (`WatchFilter.TOKENS`), Einstellung «Token & TradFi» (`settings/AssetScope.kt`), Portfolio-Chart wie der Paar-Chart (`domain/portfolio/PortfolioChart.kt`, `ui/components/ChartTypeSwitch.kt`), Markt und Einstellungen als Karten (`MarketRow`, `SettingsSection`), Glocke/Glühbirne gefüllt nur mit Alarm bzw. Aktivität.
* **Kursfarben, Portfolio-Vergleich, Markt-Register** — drei Stile mit Kerzen-Vorschau (`ui/features/settings/PriceStyleCards.kt`, neu `TRADITIONAL`), Wertverlauf «CHF | USDT | Vergleich» (`domain/portfolio/PortfolioCompare.kt`), Markt mit Registern «Jetzt | Einordnung | Daten» (`ui/components/RegisterTabs.kt`, `ui/features/info/MarketRegister.kt`; seit Build 41 eine Liste mit Unterseiten «Einordnung»/«Daten», `MarketSectionScreen.kt`).
* **Befunde der technischen Prüfung behoben** — Alarme (gleitendes Fenster `domain/alarm/MoveWindow.kt`,
  speichern vor melden, Hoch/Tief-Marke, ohne Mitteilungs-Erlaubnis nicht verbraucht), Wiederherstellung prüft
  zuerst (`data/WatchlistRestoreCleanup.kt`), DataStore übersteht beschädigte Datei, Zahlen-Eingabe im Portfolio
  wie Alarm-Schwellen (`ThresholdParser.parseAllowingZero`, `util/DecimalText.kt`), Börsen-Parser (OKX, Gate,
  Kraken, Volumen). «Auffällig» öffnet das Aktionsblatt (Vorschau für nicht beobachtete Coins,
  `ui/features/info/MarketCoinSheets.kt`); langes Drücken = Auswählen; Gruppen-Chips mit Rand in der Themenfarbe.
* **Portfolio-Zahlen und Sicherung** — eine Stablecoin-Regel (`domain/portfolio/PortfolioStables.kt`),
  Wertverlauf mit EZB-Tageskursen (`PortfolioHistoryFx.kt`), 25-%-Sperre für den Stichtag, im Verlauf
  nur noch die Identitätsprüfung 1/3…3 (`PricePlausibility.kt`), Hinweis «Veränderung aus …-Kerzen» bei fremden Kerzen; Portfolio in eigener
  Datenbank `cryptochecker_portfolio.db` (`data/portfolio/PortfolioDatabase.kt`, Übernahme aus
  AppDatabase v13 durch `PortfolioLegacyImport`), standardmässig nicht in der Systemsicherung (Schalter
  «Portfolio in Systemsicherung», `PortfolioBackupMirror`); Börsen-Vertragstests mit
  `android/testdata/exchanges` (`ExchangeContractTest`); ehrlicher Text beim ersten Alarm.
* **Listen einheitlich** — Merkliste, Start-Auswahl, «Auffällig» und Portfolio mit gleichem
  Logo (36 dp) und gleicher Schrift; die Liste wirkt wie eine Karte: keine Fuge, Trennlinien über die
  ganze Breite, nur erste und letzte Zeile aussen 16 dp gerundet, ein Rand um die ganze Liste
  (`ui/components/ListSegment.kt`). Markt: Status-Pille
  «Alles aktuell · vor …» statt Titel «Jetzt» (`MarketStatusPill`).
* **Feinschliff vor dem Store-Test** — eine Frische-Schwelle für Pille, Zeile und Screenreader
  (`OutdatedRule`); «Häufig aktualisieren» bei dunklem Bildschirm höchstens alle 5 Minuten
  (`LiveInterval`, Hintergrund-15-Min.-Takt unverändert); «Favoriten»-Chip und Gruppen-«+» nur
  bei Bedarf; kein Sortieren per langem Drücken (⋯ › Sortieren); Gesten-Hinweis mit Richtung;
  48-dp-Tippflächen; Favorit-Stern ohne Logos; leere Alarm-Übersicht mit «Neuer Alarm»; Suche
  ohne Treffer mit «Börse und Paar selbst wählen»; Hinweis «Mitteilungen sind aus» bei den Alarmen;
  Überschriften mit Strich statt Orange-Schrift, keine bräunlichen Flächen im Dunkelmodus;
  ★- und ⚡-Chip bei den Gruppen statt Aktivitätskarte (`QuickView`), Tipp auf «veraltet» zeigt
  nur diese, Mehrfachauswahl (⋯ › Auswählen: Favorit · Gruppe · Löschen), Zeit in der Zeile nur
  wenn nicht aktuell, «Aktualisieren» grau statt «Gerade aktualisiert»;
  in der Zeile seit Build 45 nur noch die Alarmglocke mit Wellen (`ic_notifications_active`), und nur mit aktivem
  Alarm; die Kurs-Benachrichtigung steht am Schalter im Aktionsblatt; Glocke oben immer sichtbar (seit Build 42; gefüllt mit aktiven Alarmen),
  ohne Zahl («Alle Alarme» auch im ⋯-Menü); neue Paare und Start-Paare ab Build 42 ohne Kurs-Benachrichtigung
  (einschalten im Paar-Menü).

* **Coin-Logos** — echte Logos in Merkliste, Aktionsblatt, Portfolio,
  Markt-Karten und in den Widgets Merkliste und Einzel-Coin. Kein eigenes Logo-Paket (CoinGecko-API-Bedingungen):
  Jede Installation lädt gleich die CoinGecko-Rangliste (Seiten 1–4 = rund 1000 grösste Coins), füllt Lücken
  (Gold, Silber, bStocks) aus der Binance-Symbolliste und der Binance-Alpha-Tokenliste (AIA, AGT, AIO …), danach
  Rang 1001–2500 (Seiten 5–10; 6 s Pause je Seite; wöchentlich neu, unvollständige Liste nach 6 h erneut); Bilder
  nur von den Bildservern von CoinGecko und Binance, Aktien-Logos von Binance (`static/stock/…png`) für alle
  TradFi-Kürzel der gespeicherten Paarlisten, Aktiennamen aus der Nasdaq-Symbolliste — alles auf einmal geladen
  (nie einzeln, niemand sieht eine Merkliste) und auf dem Gerät gespeichert, sonst Initialen-Kreis (DEX-Pools immer).
  Schalter «In der App» und «Im Portfolio» (ab Werk an) und «In Widgets» (ab Werk aus) unter
  Einstellungen › Darstellung › Coin-Logos, dort «Powered by CoinGecko»
  (`domain/logos/CoinLogos.kt`, `data/CoinLogoRepository.kt`, `ui/components/CoinBadge.kt`,
  `data/CoinLogoSync.kt`, `widget/WidgetCoinLogos.kt`).
* **Daten-Spiegel** — Marktdaten, die für alle gleich sind (Fear & Greed, Coin-Metrics-On-Chain,
  BTC-Kursverlauf, fertig berechnete Altcoin-Saison), stündlich von `market-data.yml` als Anhänge der Release «data»
  (`fng.txt`, `onchain.txt`, `btcprice.txt`, `altseason.txt`); `callMarket` fragt für genau diese Adressen zuerst den
  Spiegel, sonst den Anbieter (`domain/mirror/DataMirror.kt`). CoinGecko-Daten werden nie gespiegelt
  (API-Bedingungen) — die App fragt CoinGecko direkt.
* **Live-Kurse per WebSocket** — solange die Merkliste offen ist, kommen die Kurse
  von Binance, Bybit, OKX, Coinbase und Kraken live (REST bleibt Rückfall); «Live»
  in der Status-Pille nur, wenn alle sichtbaren gehandelten Paare live und frisch sind (Build 44), Schalter unter Einstellungen › Erweitert › Aktualisierung
  (`domain/live/LiveFeed.kt`, `data/live/LivePriceStream.kt`, siehe `DEVELOPMENT.md`).
* **4 Tabs** — Merkliste · Markt · Portfolio (optional; bei Neuinstallation seit Build 44 aus, unter Einstellungen ›
  Einstellungen › Portfolio auf der Startseite, Gruppe «Allgemein», einschaltbar – seit Build 83 der einzige Ort) · Einstellungen; «Paar
  hinzufügen» öffnet sich über «+» in der Merkliste, kein eigener Such-Tab mehr.
* **Kein Willkommensdialog** — eine Neuinstallation zeigt direkt die Startauswahl;
  «Was die App kann» steht unter Einstellungen › Über.
* **Alarm-Editor als Satz** — «Wenn BTC über … geht» mit Kurs-Vorschlag,
  «Einmal / Jedes Mal»; gleich sichtbar nur Kurs über/unter und Steigt/Fällt um, alle übrigen Bedingungen
  unter «Mehr Bedingungen», Währung, Ton und Vorlesen unter «Erweitert»; **Vorlagen** ±1 %,
  ±5 %, neues 30-Tage-Hoch/-Tief, Volumen ×3 mit einem Tipp (mit «Rückgängig»;
  `domain/alarm/AlarmTemplates.kt`).
* **Funding- und Open-Interest-Alarme** für Perpetuals (Börsen mit eigenen Futures-Daten, siehe oben):
  «Funding über/unter x %» und «Open Interest steigt/fällt um x % in 1/4/24 h»
  (`domain/alarm/DerivativesAlarm.kt`, Room-Version 11).
* **TradFi-Futures** — Kontrakte auf Aktien, Rohstoffe, Devisen und Pre-IPO
  bei Binance, Bybit, OKX, MEXC und Bitget Futures (`marketdata/util/TradFi.kt`) als Perpetuals mit
  Kennzeichen `tradFi`; sichtbar nur mit dem Schalter
  unter Einstellungen › Merkliste (Room-Version 12, `MIGRATION_11_12`).
* **Portfolio-Alarme** — Gesamtwert über/unter einem Betrag, Veränderung heute
  ±x % (`domain/alarm/PortfolioAlarmLogic.kt`); **Beträge verbergen** zeigt Werte im
  Portfolio, im Portfolio-Widget und in Portfolio-Alarmen als «•••».
* **Sicherung mit Passwort** — Format v3: AES-256-GCM, Schlüssel per Argon2id (RFC 9106; 64 MiB, t = 3,
  p = 1, eigene Umsetzung ohne Fremdbibliothek in `data/Argon2id.kt`), der Kopf der Datei (format, v, enc, kdf,
  m, t, p, salt, iv) als AAD gebunden — ein veränderter Kopf scheitert wie ein falsches Passwort. Ältere
  v2-Sicherungen (PBKDF2-HMAC-SHA256, 600 000 bzw. 210 000 Durchläufe, ohne AAD) bleiben lesbar
  (`data/BackupCrypto.kt`). Hinweis bei schwachem Passwort, austauschbar mit iOS (Prüfwerte in
  `testdata/parity/backup_crypto.json`).
* **Basis der %-Änderung** (wie Binance «Change(%) & Chart Timezone») — letzte 24 Std., seit
  letzter Aktualisierung, seit 00:00 in der Zone des Geräts oder in einer festen Zone UTC+14 … UTC-12
  (Einstellungen › Darstellung › %-Änderung; `domain/watch/ChangeBasis.kt`).
* **Suche in den Einstellungen** — ohne Akzente und Gross-/Kleinschreibung, springt
  zum Punkt und hebt ihn kurz hervor (`settings/SettingsSearch.kt`).
* **«Was bewegt sich mit?» als Faktorliste** mit Satz zum Gleichlauf mit Bitcoin; BTC-Paare zeigen
  «1 CHF = n Sats»; Markt-Tab mit Quelle und Alter je Zeile.

### Frühere Runden

* **Theme «Marrs Green»** — fünfte Akzentfarbe (#4BACA5, das Türkis aus der
  G.F-Smith-Umfrage 2017) mit eigenem App-Icon und Logo; die Einstellung heisst jetzt
  **Theme** (vorher «Farbe»). Themes: Orange, Rot, Blau, Grün und Marrs Green.
* **⚡ «Auffällig» je Paar** (bis Build 84 «Ungewöhnliche Aktivität») — erkennt ungewöhnliche Kursbewegungen im
  Vergleich zur normalen Volatilität, Volumen-Spikes, extreme Funding Rates und
  Sprünge im Open Interest; Benachrichtigungen optional (standardmässig aus).
  **Empfindlichkeit** Weniger/Normal/Mehr (Schwellen ×1,5/×1/×0,75, eine Stelle in
  `ActivitySensitivity.kt`, gilt für Karte und Meldungen; «Anpassen» in der Karte).
* **Sprungknopf** in langen Merklisten (> 30 Paare): «Zum Anfang» / «Zum Ende» beim Scrollen (`WatchJump.kt`).
* **💡 «Was bewegt sich mit?»** — Markt vs. Coin, Volumen, Hebel
  (Funding/Open Interest), Volatilität und Fear & Greed. Nur Daten, keine News,
  keine Anlageberatung.
* **Gleiches ⋯-Menü** in Merkliste, Markt und Portfolio: App-Logo und Name (→ «Über»),
  «Aktualisieren», dann die Einträge des Tabs (`ui/components/AppMenuItems.kt`).
* **App zurücksetzen** (eigene Karte ganz unten in den Einstellungen, mit Rückfrage): löscht alle Daten wie
  «Speicher löschen» (`clearApplicationUserData`), danach startet die App wie neu installiert.
* **Überschriften einheitlich** klein, fett, in Textfarbe mit kurzem Strich in der Themenfarbe davor
  (`SectionTitle` + `sectionTitleMarker()` in `ui/components/Sections.kt`): Einstellungen, Portfolio,
  Markt, Bericht, Lizenzen.
* **Keine bräunlichen Flächen im Dunkelmodus** — Auswahl (Chips, Segmente, Sprung-Knopf) neutral grau
  mit Schrift in der Themenfarbe (`withCleanContainers` in `AppColorSchemes.kt`), schwache
  Akzent-Füllungen dunkel grau statt orange (`AppColors.accentTint`), Aufteilungs-Balken zur hellen
  Schrift hin gemischt; hell unverändert.
* **Zurück** aus «Alarme» oder «Was bewegt sich mit?» öffnet wieder das Aktionsblatt des Paars.
* **Namen in der Merkliste** («Namen anzeigen»): Paar/Kurs, Name/%-Änderung und Börse/≈ Umrechnung
  je auf einer Linie, ohne Namen «–». Aktien ohne bStock bekommen ihren Namen aus der
  offiziellen Nasdaq-Symbolliste (`CoinLogos.parseSymbolDirectory`, höchstens wöchentlich).
* **Zahl am App-Symbol** (Einstellungen › Alarme, ab Werk an): jede Alarm- und
  Marktmeldung zählt 1; ob Zahl oder Punkt, entscheidet der Startbildschirm
  (Samsung «Mit Zahl», Pixel nur Punkt). Kurs- und Dienst-Mitteilung zählen nie.
* **Portfolio** — eigener Tab (Einstellungen › Portfolio auf der Startseite, Gruppe «Allgemein» – seit Build 83 der einzige Ort, ein- und ausschaltbar; bei
  Neuinstallation seit Build 44 aus, bestehende Installationen behalten ihn): Käufe und
  Verkäufe mit Datum und Preis, Durchschnittspreis, Gewinn/Verlust, Summe in
  USDT plus Umrechnung in eine wählbare Währung (31 Währungen; Standard
  richtet sich nach der Region des Geräts, z. B. CHF in der Schweiz, EUR in
  Deutschland, sonst USD).
  Bedienung wie die Merkliste: Coin (nach Rückfrage) oder Transaktion nach links wischen löscht
  (mit «Rückgängig»), ⋯ › «Aktualisieren» / «Portfolio leeren», nach unten
  ziehen aktualisiert; jede Coin-Zeile zeigt die 24-h-Änderung und
  die Anzahl Transaktionen.
* **«≈ Umrechnung» in der Merkliste** — zweite Zeile «≈ 58 912 EUR» unter
  der Veränderung, in der Umrechnungswährung (Einstellungen › Währung; gilt
  auch für Portfolio, Alarme in deiner Währung, Karte Krypto-Markt und
  Portfolio-Widget).
  Kurse: Frankfurter (EZB), Ersatz open.er-api.com, alle 3 h neu geholt; ist der Kurs älter als 3 h, steht
  seine Uhrzeit dabei («· Kurs 08:15»)
  (`domain/convert/CurrencyConversion.kt`, `data/portfolio/CurrencyConverter.kt`).
* **Alarme in deiner Währung** — Kursalarme können in einer anderen Währung
  als der Börsen-Währung gesetzt werden (z. B. 60 000 EUR auf BTC/USDT).
  Geprüft wird `Kurs × Wechselkurs`; ohne Kurs wird der Alarm in dieser Runde
  übersprungen (Room-Version 9, Spalte `alarms.currency`).
* **Stichtag-Export** — Portfolio-Wert an einem Datum (z. B. 31.12.) in der
  gewählten Währung als CSV (UTF-8 mit BOM, Excel-tauglich; Trenn-/Dezimalzeichen je Region: Schweiz/Liechtenstein
  `;` mit dem Dezimalzeichen der Region, Länder mit Dezimalkomma `;` und `,`, sonst `,` und `.`):
  Tagesschlusskurs (UTC) von Binance/Coinbase, Devisenkurs des Tages von
  Frankfurter. Keine Steuerberatung (`domain/portfolio/CutoffExport.kt`).
* **Barrierefreiheit** (Einstellungen › Darstellung › Kursfarben, gilt für App und Widgets):
  * Kursfarben Grün/Rot oder Blau/Orange. Per Simulation (Machado 2009)
    geprüft: Blau/Orange bleibt bei jeder Farbsehschwäche (Rot-Grün und
    Blau-Gelb) unterscheidbar. Die Richtung steht zusätzlich immer als +/−,
    auch für Menschen ohne Farbsehen.
  * **Farben tauschen** — Rot steigend, Grün fallend wie in China, Japan,
    Korea und Taiwan; dort (Region CN/TW/HK/MO/JP/KR) standardmässig an.
    Vorzeichen und Ansagen bleiben unverändert.
  * **Hoher Kontrast** — kräftigere Kursfarben (≥ 7:1), Nebentexte und Ränder
    wie Haupttext, Akzentfarbe ≥ 7:1; auch aktiv, wenn Android 14+ mehr
    Kontrast verlangt. Normale Kursfarben erreichen WCAG AA (≥ 4,5:1) auf allen
    Flächen (`PriceColorSchemeTest`).
  * **TalkBack** — jede Merklisten-Zeile ist ein Satz (Paar, Börse, Kurs,
    Veränderung, ≈ Wert, 24-Std.-Chart, Notiz, Alarme, Zustand); Zyklus-Chart,
    Fear & Greed, Bitcoin-Kennzahlen, Widget-Zeilen und Widget-Chart werden
    beschrieben (`util/A11yText.kt`, `util/ChartSummary.kt`).
* **Mini-Chart** — 24-Std.-Linie in jeder Merklisten-Zeile (stündliche
  Schlusskurse der eigenen Börse, sonst gekennzeichnet aus der Binance-Kette, 15 Min. zwischengespeichert, ab 360 dp Bildschirmbreite;
  unter Einstellungen › Merkliste, bei Neuinstallation aus). Für TalkBack Teil des Zeilensatzes (Start, Ende, Hoch, Tief).
* **15 neue Börsen** (siehe «Umfang»).
* **Notiz pro Coin** — kurze eigene Notiz unter dem Paar,
  durchsuchbar und Teil der Sicherung (Room-Version 8).
* **Eigener Alarmton** — System-Ton oder eigene Audiodatei
  (ab Android 10; wird nach `Notifications/CryptoChecker` kopiert, damit Android
  sie abspielen darf). Je Ton ein eigener Mitteilungskanal (`alarms_v<n>`).
* **Netzwerkgebühren** — Karte im Markt-Tab für Ethereum,
  Base, Arbitrum, Polygon, BNB Chain (öffentliche RPC-Knoten, `eth_feeHistory`)
  und Bitcoin (mempool.space), mit Gas-Alarm (meldet einmal, wieder scharf ab
  +25 %).
* **Zyklus-Vergleich** — Hoch nur in den ersten 1000 Tagen nach dem Halving
  (vorher fiel beim 2020er-Zyklus der März 2024 hinein und das Tief fehlte);
  dazu Doppel-Top/-Bottom (zweites Hoch/Tief innerhalb 20 %, mind. 90 Tage
  Abstand; `domain/market/CycleExtremes.kt`).
* **Übersetzungen** — Sprachansage sagt «gestiegen/gefallen»; Deutsch
  durchgehend Schweizer Schreibweise (ss, «»).
* **Datenschutz/Support** — `https://r1adbe.github.io/cryptoChecker/privacy/`
  bzw. `https://r1adbe.github.io/cryptoChecker/` (Quelle: `docs/`).
* **Mitteilungs-Symbol** — alle Mitteilungen mit der Alarm-Glocke.
* **Gruppen** — Chips «Alle · …» oben in der Merkliste; das Widget kann eine
  Gruppe zeigen.
* **Volumen-Spike-Alarm** — ×2/×3/×5/×10 (Daten von Binance).
* **Alarm «Nahe am Hoch/Tief»** — meldet, wenn der Kurs höchstens x % (Standard 2 %) unter dem Hoch bzw. über dem Tief der letzten 30 Tage / 90 Tage / 1 Jahr liegt, und bei einem neuen Hoch/Tief (Tageskerzen, 6 h zwischengespeichert; ohne Migration, `domain/alarm/NearExtreme.kt`).
* **Portfolio «Wertverlauf»** — Wertkurve mit 7 T / 30 T / 1 J über den Positionen, berechnet aus den Transaktionen × Tagesschlusskursen, umgerechnet zum heutigen Kurs.
* **Einzel-Widget** — Chart-Zeitraum 24 h, 7 Tage oder 30 Tage.
* **Widgets** — kompakte zweizeilige Zeilen mit ↗/↘ (0,00 % grau, ohne Pfeil),
  neutrales Aktualisieren-Symbol; Zeit/Dauer im Kopf wird nach einer
  Hintergrund-Aktualisierung jetzt nachgeführt.
* **Neues App-Icon und Farben** — weisse Glocke mit steigender Kurslinie;
  Themes Orange, Rot, Blau, Grün und Marrs Green, jeweils mit Verlauf und
  passendem App-Icon.
* Marktdaten für Charts, Volumen und Zyklus kommen zusätzlich zu den
  Börsen-APIs von `data-api.binance.vision` (öffentlicher Marktdaten-Spiegel
  von Binance).

### Verstehen statt nur Zahlen (16.2.2)

* **Crypto Pulse «Auffällig»** (bis Build 84 «Was gerade auffällt») — erste Karte im Markt-Tab:
  Schlagzeile mit Leitsatz (z. B. «Bitcoin führt den Markt an.») und einem
  zweiten Satz zu Volumen und Funding, darunter BTC, ETH, SOL (24 h), ein
  Funding-Chip (nur wenn erhöht/negativ), die Marktbreite «Top 30 ↗ 22 · ↘ 8» als farbige Schrift (grösste Coins
  ohne Stablecoins, 24 h) und der Krypto-Markt gesamt mit Veränderung; ein Satz zur Marktbreite
  nur in deutlichen Fällen. «Warum? ⌄» klappt «Markt heute»
  (nur Altcoins vs. Bitcoin — die Kurse stehen schon darüber) und die Faktor-Checkliste auf — nur Funding (! / –);
  Volumen, Fear & Greed und Gas stehen nicht doppelt, sie haben eigene Karten
  im Tab. Feste Regeln, keine Prognose (`domain/market/CryptoPulse.kt`,
  `ui/features/info/CryptoPulseCard.kt`).
* **«Auffällig» (Karte im Markt-Tab)** (bis Build 84 «Heute auffällig») — seit Build 83 im zugeklappten Teil «Mehr Marktdaten» (Abschnitt «Jetzt», Teil des
  schrittweisen Erscheinens): bis zu fünf der rund 30 grössten Coins (ohne Stablecoins, mit
  …USDT auf Binance), die heute deutlich stärker/schwächer als BTC laufen (≥ 3 Prozentpunkte),
  gegen den Markt (≥ 2 %, andere Richtung als BTC), mit ungewöhnlichem Volumen (≥ 2× üblich) oder
  extremem Funding (≥ 0.05 % / ≤ −0.03 %); je Zeile Plakette, Symbol, 24-h-Änderung als farbige Schrift mit kleinem grauem «24h» und ein Satz.
  Sonst «Heute nichts Auffälliges». Daten mit je einem Abruf: 24-h-Ticker (Binance-Spiegel),
  Funding aller Perpetuals (Binance Futures), Marktkapitalisierung aus der CoinGecko-Rangliste
  (24 h, wie die Start-Coins). «Üblich» = eigener Median der Vortage (Tageswert auf dem Gerät),
  anfangs der Median aller Coins — keine Kerzen je Coin. Zwischenspeicher 10 Min.; Tippen öffnet
  «Was bewegt sich mit?» (Coin in der Merkliste) oder die Suche auf der Seite «Paar hinzufügen»
  (`domain/market/MarketUnusual.kt`, `data/remote/UnusualDataSource.kt`, `ui/features/info/MarketUnusualCard.kt`).
* **Hinweis «Wirtschaftsdaten»** — kompakte Zeile über dem Pulse, wenn heute (oder in den
  nächsten 18 h) wichtige US-Daten anstehen: «Heute 14:30: US-Inflationsdaten (CPI) – an solchen
  Tagen schwankt der Markt oft stärker.» (CPI, PPI, Arbeitsmarkt, Fed-Zinsentscheid, PCE; Ortszeit,
  mehrere Termine in einer Zeile; 2 h nach dem Termin «… veröffentlicht» bis Tagesende). Kalender
  einmal am Tag von der App-Webseite (`https://r1adbe.github.io/cryptoChecker/macro/events.json`,
  24 h zwischengespeichert, sonst `assets/macro_events.json`). Optional Morgen-Meldung um 08:00
  (Einstellungen › Alarme › Markt-Meldungen › «Wirtschaftstermine», Standard aus)
  (`domain/macro/MacroCalendar.kt`, `data/MacroCalendarRepository.kt`, `work/MacroNotifyWorker.kt`).
* **«Was bewegt sich mit …?» als Checkliste** — oben «Kurz gesagt» in einem Satz
  (heute als Karte «Kurz gesagt · Stand HH:MM», siehe «Zuletzt dazugekommen»), die Faktoren
  (Markt vs. Coin, Bitcoin gibt die Richtung vor, Volumen, Hebel, Schwankung 1h, Fear & Greed)
  mit ✓ (stützt die Bewegung), ! (erhöht) oder – (neutral) unter «Alle Werte und Quellen»,
  Fusszeile «Zeitgleich heisst nicht Ursache · Keine Anlageberatung» (`domain/activity/WhySummary.kt`,
  `ui/features/watchlist/WatchWhySheet.kt`, `WatchWhyFactors.kt`).
* **Einstellungen in Gruppen** — Allgemein · Darstellung · Alarme · Daten · Zeile «Erweitert»
  (eigene Seite) · Über · «App zurücksetzen» (`ui/features/settings/SettingsScreen.kt`), mit Suchfeld oben
  (`ui/features/settings/SettingsSearchUi.kt`).
* **Aktionsblatt** — Tippen auf eine Zeile öffnet die Aktionen; unter dem Kurs die Zeile «24h · Aktualisiert: …»,
  gleich darunter die drei häufigsten gleich gross: Alarm, Warum?, Favorit. Nach dem Hinzufügen bietet
  der Hinweis «… wird jetzt überwacht» direkt «Alarm setzen», solange die
  neuen Paare noch keinen Alarm haben.
* **Chart im Aktionsblatt** — unter Alarm · Warum? · Favorit: 24h · 7T · 30T, Kerzen/Linie (zuletzt gewählt), Geometrie und Kerzen wie das Einzel-Widget, lange drücken/waagrecht ziehen zeigt Kurs, Zeit und Veränderung; DEX-Paare ohne Chart (`ui/features/watchlist/WatchSheetChartView.kt`, `domain/watch/SheetChart.kt`).
* **Favoriten ohne Stern-Knopf** — Favoriten erkennt man mit eingeschalteten Coin-Logos an
  einem kleinen Stern am Logo (kein Akzent-Rand mehr; ausgewählte Zeilen sind getönt); die Zeile
  verliert keine Breite mehr an einen eigenen Stern.
* **«*» an der Änderung erklärt sich** — Tippen auf das «*» (Änderung aus fremden Kerzen, Tippfläche ≥ 48 dp ohne höhere Zeile) zeigt «Aus Binance-Kerzen berechnet – die Börse liefert diesen Wert nicht selbst», TalkBack/VoiceOver als Aktion der Zeile (Build 85, `WatchlistDayChangePill.kt`).
* **Coin löschen mit Rückfrage** — Wischen bzw. «Löschen» auf einem Portfolio-Coin fragt erst «Alle Transaktionen von BTC löschen?» mit der Zahl der Transaktionen; «Abbrechen» lässt die Zeile stehen, danach Banner «BTC aus dem Portfolio entfernt (N Transaktionen)» mit «Rückgängig» (Build 85, `DeleteCoinDialog`, `SwipeActionsRow(confirmsDelete)`; iOS `askDeleteCoin`).
* **Alarm testen** — Einstellungen › Alarme; gleicher Weg wie ein echter
  Alarm, aber ohne Nachtruhe. Nach dem ersten Alarm: «Alarm gespeichert» mit Hauptknopf «Prüfen, ob Alarme ankommen», der gleich die Prüfliste «Damit Alarme ankommen» öffnet (Build 85, `FirstAlarmDialog`; iOS als Blatt).
* **Willkommen ohne Dialog** — eine Neuinstallation öffnet direkt die leere
  Merkliste mit der Starter-Auswahl; «Was die App kann» (Was passiert? · Wann
  reagieren? · Warum?) steht unter Einstellungen › Über; Beispiel im leeren Portfolio.
* **Kleinere Korrekturen** — Akzentfarben im hellen Modus mit AA-Kontrast,
  schräge Pfeile ↗/↘ bei allen Veränderungen, TalkBack-Aktionen «Nach oben/unten
  verschieben», BGN aus dem festen Euro-Kurs 1,95583, Hinweis beim Export für
  den heutigen Tag, eindeutige PendingIntents für Widgets und Mitteilungen.
* **Einzel-Widget mit Kerzen** — Chart-Art pro Widget wählbar (Kerzen, Standard,
  oder Linie): 24 Stundenkerzen, 42 Kerzen à 4 Std. oder 30 Tageskerzen (seit Build 44 von der eigenen Börse des
  Paars, sonst gekennzeichnet aus der Binance-Kette; Achse mindestens 0,2 % hoch, Stablecoins 1 %); Preise
  für Tief, Mitte und Hoch am rechten Rand, aktueller Kurs als Marke in der
  Akzentfarbe, senkrechte Linien je Stunde, Tag bzw. Woche
  (`widget/WidgetChartGeometry.kt`, `WidgetChartRenderer.kt`).
* **Brasilianisches Portugiesisch** (`values-pt-rBR`), damit 31 Sprachen.
* **Keine Spenden in der App** — siehe «Unterstützen» unten.

### Bedienung und Alltag (16.2.2)

* **Mit einem Tipp starten** — leere Merkliste bietet die fünf grössten Coins
  nach Marktkapitalisierung ohne Stablecoins (CoinGecko, 24 h zwischengespeichert,
  Ersatz BTC, ETH, XRP, BNB, SOL), Binance USDT bzw. in den USA Binance.US USDT
  (`domain/starter/StarterCoins.kt`). Jede Zeile mit Live-Kurs und
  24-Std.-Veränderung, alle vorausgewählt, ein Knopf «Zur Merkliste hinzufügen (5)». Das Hinzufügen ist kurz inszeniert: Zeile
  blendet ein, Mini-Chart zeichnet sich, Häkchen, Haptik, «… wird jetzt
  überwacht» (bei reduzierter Bewegung ohne Animation).
* **Wischen in der Merkliste** — nach links = löschen mit «Rückgängig», nach
  rechts = Favorit (`ui/features/watchlist/WatchSwipe.kt`). Nicht im Sortiermodus.
  Ein Tipp öffnet die Aktionen sofort; kein Doppeltippen (es hätte jeden Tipp
  um ~0,3 s verzögert) — Favorit auch im Aktionsblatt. «Löschen» im
  Aktionen-Menü wirkt wie Wischen: sofort, mit «Rückgängig», ohne Rückfrage.
* **Akku-Hinweis erst nach dem ersten Alarm** — nie beim ersten Start oder beim
  Hinzufügen der ersten Coins; er erscheint, sobald man nach dem ersten Alarm
  wieder auf einem Haupt-Tab ist (`ui/features/about/BatteryOptimizationDialog.kt`).
* **Markt-Tab in drei Abschnitten** — oben ohne Titel mit Status-Pille (seit Build 83: Hauptkarte Crypto Pulse mit «Kurz gesagt», darunter ruhig Fear & Greed kompakt und Gewinner/Verlierer 3+3, zugeklappt «Mehr Marktdaten» mit «Auffällig» und Makro; früher Crypto Pulse, «Auffällig»,
  Fear & Greed), «Einordnung» (Marktphase, Dominanz mit Altcoin-Saison, Halving)
  und «Daten» (Krypto-Markt, Gas, Wirtschaftsdaten, Coin; Wirtschaftsdaten nur bei einem Termin in ±2 h oben); die Überschriften sind für TalkBack Überschriften.
* **Markt-Tab sofort da** — zeigt beim Öffnen die zuletzt gespeicherten Daten
  («Stand … · wird aktualisiert …») und lädt im Hintergrund neu
  (`ui/features/info/MarketPhaseScreen.kt`).
* **Fragen & Wünsche** — Link «GitHub» unter Über (`FEEDBACK_URL`, öffnet
  github.com/r1adBE/cryptoChecker/issues/new/choose).
* **Krypto-Markt** — erste Karte im Abschnitt «Daten» des Markt-Tabs: gesamte Marktkapitalisierung mit
  24-Std.-Veränderung und 24-Std.-Volumen in der Umrechnungswährung (CoinGecko
  `/global`, dieselbe Abfrage wie die Bitcoin-Dominanz).
* **Nachtruhe** — Alarme warten im gewählten Zeitraum (Standard 23–7 Uhr, aus) und
  melden nach dem Ende mit Ton (`domain/alarm/QuietHours.kt`, `holdsAlarms`); Markt-Meldungen
  (Marktphase, Fear & Greed) beachten die Nachtruhe ebenfalls (`MarketNoticeGate`).
* **Portfolio-Sperre** — `androidx.biometric`: Fingerabdruck, Gesicht oder Geräte-PIN;
  sperrt nur das Portfolio (Tab, Coin-Detail, Erfassen-Blätter, Stichtag-Export,
  «Zum Portfolio hinzufügen» aus der Merkliste, Sichern mit Portfolio-Daten,
  Wiederherstellen und Ausschalten der Sperre, Portfolio-Widget) — Merkliste,
  Hinzufügen, Markt-Tab, Einstellungen und die übrigen Widgets bleiben frei. Einmal je
  Sitzung entsperren; wieder gesperrt beim Neustart und nach > 60 s im Hintergrund;
  gesperrt zeigt der Tab einen ruhigen Zustand (Schloss, «Entsperren»); offene Erfassen-Blätter
  und Sicherungs-Dialoge schliessen sich beim erneuten Sperren. Zeile in den
  Einstellungen nur mit eingeschaltetem Portfolio (Wert bleibt erhalten); ab Android 13 kein
  Vorschaubild in den letzten Apps (`lock/`, Regeln in `lock/PortfolioLockPolicy.kt`,
  Oberfläche `ui/lock/PortfolioLock.kt`).
* **Wertverlauf** im Portfolio — standardmässig zugeklappt: eine Zeile «Wertverlauf ·
  30 T +4.20%» (farbige Schrift) (nur der gewählte Zeitraum wird geladen und gerechnet); aufgeklappt
  Chips 7 T / 30 T / 1 J / ∞ (Seit 1. Kauf; ein Tipp zeigt den Namen kurz darunter) und
  Kerzen/Linie unter dem Chart. «∞» lädt so viele Tageskerzen wie nötig in Stücken von
  höchstens 1000 (`PortfolioHistory.candleChunks`), ohne 5-Jahres-Grenze; nur vor dem
  17.08.2017 (Beginn der Binance-Tageskurse) gibt es keine Kurse (Hinweis darunter). Coins mit eingetragener Börse
  nehmen deren eigene Tageskerzen; hochgerechnetes Binance nur als Ersatz mit aktuellem Bezug (darunter genannt),
  sonst «ohne …». Auf-/Zuklappen und Zeitraum werden gemerkt
  (nicht in der Sicherung).
* **Portfolio-Widget** — passt sich der Grösse an: klein Gesamtwert in der
  Umrechnungswährung und (je Widget wählbar, «Umrechnung + USDT») «≈ … USDT»;
  ab 110 dp Höhe dazu die Veränderung heute als farbige Schrift (Vergleich mit gespeicherten
  Kursen ≥ 20 h alt, Käufe/Verkäufe zählen nicht als Gewinn) und der Wertverlauf
  des heutigen Bestands (bis 48 h, Linie ohne Achsen); ab 250 × 180 dp die
  grössten Positionen (bis 5, Wert, Anteil, Veränderung, «+ n weitere»). Einrichten
  optional (`PortfolioWidgetConfigureActivity`); bei Portfolio-Sperre nur Titel,
  Schloss und «Gesperrt – in der App entsperren», ohne Werte
  (`widget/Portfolio*`, Regeln in `domain/portfolio/PortfolioWidgetMath.kt`).
  Klein (z. B. 2 × 1) kompakter, damit «≈ … USDT» noch passt; ist «heute» zu
  breit, nur der Prozentwert. Anzeige, Zeitraum (24 h / 7 T / 30 T / 1 J) und Kerzen/Linie
  je Widget, siehe «Zuletzt dazugekommen».
* **Widget «Auffällig»** — Crypto Pulse auf dem Startbildschirm:
  Schlagzeile mit ↗/↘ in der Kursfarbe, Leitsatz (2 Zeilen), Coins als farbiger Text (so viele
  von BTC/ETH/SOL, wie ganz passen); Titel und Schlagzeile werden kleiner bzw.
  zweizeilig statt abgeschnitten; Tippen öffnet den Markt-Tab. Daten aus dem
  Zwischenspeicher des Markt-Tabs (5 Min.), sonst über dieselbe Quelle
  (`widget/PulseWidget*`, `PulseWidgetRenderer`).
* **Widget-Ecken** — alle Widgets mit runden Ecken (ab Android 12 Radius des
  Systems): eingefärbte Fläche als ImageView unter dem Inhalt, Wurzel
  `@android:id/background`. Die Kopfzeile der Liste zeigt Logo · «Merkliste» ·
  «Uhrzeit · Dauer» und wird nie abgeschnitten: erst fällt die Dauer weg, dann
  wird der Titel kleiner, dann fällt die Uhrzeit weg (`widget/ListWidgetHeader.kt`);
  im Einzel-Widget steht bei wenig Platz nur die Basis («BTC»).
* **Alarm als Satz** — «Sag mir Bescheid, wenn BTC unter 60 000 CHF fällt.» in
  der Alarmliste; der Editor beginnt mit «Wenn BTC über … geht» (`domain/alarm/AlarmSentence.kt`).
* **Veralteter Kurs sichtbar** — «vor 41 Min · Binance nicht erreichbar» bzw.
  «veraltet» ab 3 × Intervall (mind. 15 Min), auch für TalkBack. Mit «Häufig aktualisieren»
  schon nach 2 Minuten.
* **Erklärungen** — ⓘ bei jeder Zeile der Markt-Karten (u. a. Funding Rate, Open Interest, RSI, Pi-Cycle);
  DEX-Intro und «Gewinn/Verlust, noch nicht verkauft» verständlicher.
* **Einstellungen «Erweitert»** — eigene Seite mit Aktualisierung («Häufig aktualisieren», feste
  Mitteilung, Live-Kurse), Kursfarben, %-Änderung, Coin-Logos, Widgets und Sprachausgabe.
* **Ruhiger Feinschliff** — Puls-Zeile ganz oben in der Merkliste als farbige Schrift («↗ 233 steigen» grün,
  «↘ 295 fallen» rot und «Ø +0.32%» in der gelben Warn-Schriftfarbe; Grundlage ist derselbe Wert wie die Prozent-Änderung
  der Zeilen, also die Veränderung über 24 Stunden; Paare ohne 24-h-Wert zählen nicht; folgt der Gruppe,
  aus beim Suchen/Sortieren, `domain/watch/WatchPulse.kt`), Mini-Chart 28 dp mit
  sanfter Fläche, rollende Ziffern bei Kurs und Portfolio-Summe
  (`ui/components/RollingNumberText.kt`, ohne Animationen ein normaler Text),
  grosse Portfolio-Summe mit «heute»-Änderung als farbige Schrift, Beträge in Rubik mit gleich breiten
  Ziffern, Widgets mit ↗/↘ in der Liste; im Listen-Widget eine dünne Trennlinie
  (1 dp, neutral in der Haupttext-Farbe mit 25 %, bei hohem Kontrast 45 % Deckkraft, eingerückt wie
  der Text) zwischen den Paaren, nicht unter dem letzten.
* **Android 15** — Live-Dienst (dataSync) beendet sich beim 6-Stunden-Limit
  sauber (`PriceService.onTimeout`) und wird nach einem Neustart nicht mehr aus
  `BOOT_COMPLETED` gestartet, sondern beim Öffnen der App.

## Funktionen

**Merkliste** — Börse und Handelspaar auf der Seite «Paar hinzufügen» («+» neben der Lupe in der Merkliste) wählen und übernehmen
(oder bei leerer Merkliste die Startauswahl nutzen). Jeder Eintrag speichert
Kurs, Vorkurs und Zeitpunkt in Room, dazu die Veränderung über 24 Stunden
(`change24h`, Room v10): Die Prozent-Änderung «↗ +1.80% 24h» (auch Aktionen-Kopf,
Puls-Zeile, Widgets, TalkBack «… in 24 Stunden») vergleicht den Kurs mit der
Eröffnung der Stundenkerzen vor 24 h — dieselben Kerzen wie der Mini-Chart. Seit Build 44 kommen sie
zuerst von der eigenen Börse des Paars (`data/remote/candles/OwnHourlyCandles.kt`, `ExchangeCandleRegistry`);
nur ohne eigene Kerzen springt die Binance-Kette ein (`SparklineRepository`, gekennzeichnet und nur bei gleichem
Token; USD-artige teilen die USDT-Reihe, Fiat-Quotes ohne eigene Kerzen nehmen den Verlauf der USDT-Reihe; TradFi
und DEX-Pools nie über die Kette). Ohne Kerzen
zeigt sie grau «—» (`domain/watch/DayChange.kt`). Tippen öffnet die Aktionen (Alarm, Warum?,
Favorit oben), wischen: links löschen, rechts Favorit; sortieren über ⋯ › Sortieren (ziehen am
Griff oder lange drücken, nur im Sortiermodus).
Im Sortiermodus ordnen «Nach Coin ordnen» bzw. «Nach Börse ordnen» die gezeigten Paare einmal (Favoriten
oben) und speichern das als eigene Reihenfolge (`domain/watch/AutoOrder.kt`, iOS `AutoOrder.swift`).

**Benachrichtigungen** — je Paar wahlweise eine lautlose Kurs-Benachrichtigung mit Kurs,
Veränderung in Prozent und Uhrzeit (neue Paare ab Build 42 ohne; einschalten im Paar-Menü).
Wahlweise wegwischbar oder fest («Nicht wegwischbar», bei neuen Installationen aus).

**Alarme** — Kurs über/unter einem Wert, Anstieg oder Fall um x Prozent,
Bewegung um x % in y Stunden, Volumen-Spike, nahe am Hoch/Tief, Funding und Open
Interest (Perpetuals von Binance, Bybit, OKX, WOO X, Bitget, MEXC, HTX, Hyperliquid, Deribit); wahlweise in deiner Währung,
angezeigt als Satz, mit Vorlagen (±1 %, ±5 %, neues 30-Tage-Hoch/-Tief,
Volumen ×3), Nachtruhe und «Alarm testen». Ein neuer Kursalarm schlägt 5 % über bzw. unter dem
Kurs vor; ist die Marke beim Speichern schon erreicht, fragt die App nach («Alarm löst sofort aus»).
Bearbeiten stellt den Alarm nur neu scharf, wenn sich Bedingung, Schwellwert, Währung, Paar oder
Zeitfenster ändern. Konnte ein Alarm nicht geprüft werden (kein Devisenkurs, keine Kerzendaten),
steht das in seiner Zeile. Dazu Portfolio-Alarme (Gesamtwert
über/unter, Veränderung heute).
Einmalig oder wiederholend, mit Ton (wählbar), Vibration und optionaler Ansage.
Eine einstellbare Ruhezeit verhindert Dauerfeuer. Prozentalarme messen ab dem
Kurs, der beim Anlegen galt, und setzen den Bezugspunkt beim Auslösen neu.

**Sprachausgabe** — liest Kurse und Alarme vor, mit dem sprechbaren Börsennamen
aus der Bibliothek (`Market.ttsName`). Sprechtempo einstellbar, wahlweise nur
bei Alarmen.

**Widgets** — Merklisten-Widget für den Startbildschirm: eine scrollbare Liste
aller beobachteten Paare mit Kurs und Veränderung. Dazu das Einzel-Widget
(Kerzen oder Linie, Preise Tief/Mitte/Hoch, aktueller Kurs als Marke) und das
Portfolio-Widget (je nach Grösse Gesamtwert, ≈ USDT, heute, Wertverlauf und die
grössten Positionen) sowie das Widget «Auffällig» (Crypto Pulse). Beim Ablegen wird der
Hintergrund gewählt (transparent mit hellem oder dunklem Text, dunkel, hell,
marineblau). Ein Tipp auf eine Zeile öffnet die App, der Knopf aktualisiert.

**Massenabfrage** — Wer viele Paare derselben Börse beobachtet, bekommt sie in
einer einzigen Anfrage statt in Dutzenden. `Market` hat dafür einen optionalen
Haken (`bulkTickersNumOfRequests`, `getBulkTickersUrl`, `parseBulkTickers`);
umgesetzt ist er für alle Märkte ausser Coinbase (DexScreener seit Build 82: Pools derselben Chain gemeinsam über
`/latest/dex/pairs/{chain}/{a,b,…}`, höchstens 30 je Anfrage, Liquiditätsregel je Pool). Märkte
ohne diesen Haken werden weiter Paar für Paar abgefragt, und scheitert die
Massenabfrage, fällt der Refresher automatisch darauf zurück. Ab drei Paaren einer Börse greift der Sammelweg.
Wo die Börse eine Auswahl dokumentiert, werden nur die beobachteten Paare abgefragt
(Binance/Binance.US `symbols`, Upbit/Bithumb `markets`, Kraken `pair`, Bitfinex `symbols`;
lange Listen über `BulkPairChunks` auf mehrere URLs unter 2000 Zeichen verteilt);
scheitert die gefilterte Abfrage, folgt einmal die ungefilterte. Coinbase braucht je Paar
eine Anfrage (`/stats`: Kurs, Hoch/Tief, Volumen und Kurs vor 24 h).

**Pünktlich prüfen** (Build 46, ab Werk aus) — `work/ExactCheckScheduler.kt` setzt mit `SCHEDULE_EXACT_ALARM`
einen exakten Wecker etwa alle 15 Minuten (`ExactCheckReceiver` → `BackgroundCheck`, gleiche Prüfung wie der Worker),
auch im Doze; ohne Berechtigung zurück auf WorkManager. Hinweis bei Akku-Einschränkung. «Letzte Prüfungen»
(`CheckRunLog`, 20 Einträge in `noBackupFilesDir/check_runs.txt`, iOS gleich) zeigt Auslöser und Ergebnis; zwei Prüfungen
innerhalb von 5 Minuten werden zusammengelegt (`BackgroundSchedule`).

**Aktualisierung** — WorkManager im Hintergrund (ab 15 Minuten, Android-Grenze)
und ein Vordergrunddienst «Häufig aktualisieren» für kurze Intervalle ab 15 Sekunden.
In der geöffneten Merkliste kommen die Kurse der unterstützten Börsen live per
WebSocket (Schalter «Live-Kurse»); Hintergrund und Widgets bleiben bei REST.
Ein Boot-Receiver plant nach einem Neustart (`BOOT_COMPLETED`) und nach einem App-Update
(`MY_PACKAGE_REPLACED`) die Hintergrundaktualisierung neu – nur diese geschützten System-Broadcasts;
«Häufig aktualisieren» startet die App ab Android 15 erst beim nächsten Öffnen.

## Aufbau

```
marketdata/   Börsen, Handelspaare, Ticker-Parser
app/
  data/       Room (watches, alarms, markets), Repositories, OkHttp
  domain/     AlarmEvaluator; PriceRefresher steuert die Aktualisierung, dazu
              PriceFetcher (Kurse holen), DayReferences (24-h-/Tages-Bezüge),
              RefreshEffects (Alarme, Kurs-Meldungen, Ansagen)
  notification/ Kanäle und Benachrichtigungen
  tts/        Sprachausgabe
  service/    Vordergrunddienst
  work/       WorkManager, Boot-Receiver
  widget/     Widget-Provider und Einrichtung; je Widget-Art ein Renderer
              (ListWidgetRenderer, SingleWidgetRenderer, PortfolioWidgetRenderer,
              PulseWidgetRenderer), WidgetUpdater entscheidet, was neu muss;
              Zeilen der Liste aus ListWidgetRows (ab Android 12 direkt im Widget)
  ui/         Compose: Merkliste, Börsen, Alarme, Markt, Portfolio, Einstellungen
```

Grosse Bildschirme sind nach Aufgaben aufgeteilt, mit denselben Dateinamen wie in iOS:
Merkliste (`WatchlistScreen` setzt zusammen; `WatchlistHeader`, `WatchlistStatus`,
`WatchlistSearch`, `WatchlistList`, `WatchlistRow`, `WatchlistSheets`, `WatchlistJump`,
`WatchlistBanner` …), Markt-Karten (`Market…Card`, `MarketCycleChart`, `MarketCardParts`),
Einstellungen (`SettingsScreen`, `SettingsComponents`, `SettingsPickers`, je Gruppe
`Settings…Pages`), Alarme (`AlarmsScreen`, `AlarmEditorSheet`, `AlarmAdvancedOptions`),
«Paar hinzufügen» (`ExplorerScreen`, `ExplorerSearchUi`, `ExplorerSteps`, `ExplorerBulk`,
`ExplorerDex`), Aktionsblatt (`WatchActionsSheet`, `WatchSheetActions`,
`WatchSheetFutures`, `WatchSheetSpot`, `WatchSheetChartView`, `WatchSheetChartCanvas`), Portfolio
(`PortfolioScreen`, `PortfolioTotalCard`, `PortfolioRows`, `PortfolioHistoryCard`,
`PortfolioHistoryChart`). ViewModels bleiben je Bildschirm eine Klasse.

## Bauen

```bash
./gradlew test
./gradlew :app:assembleDebug
```

**JDK 17** (Android Studio bringt es mit), compileSdk 37, targetSdk 36, minSdk 26.
Beim ersten Öffnen lädt Android Studio Gradle 9.8.1 und, falls nötig, die
SDK-Plattform 37.

| Baustein | Version |
|---|---|
| Android Gradle Plugin | 9.4.1 (Kotlin eingebaut, ohne `org.jetbrains.kotlin.android`) |
| Gradle | 9.8.1 |
| Kotlin | 2.4.21 (K2, Compose-Compiler-Plugin, kotlinx-serialization) |
| KSP | 2.3.12 (ersetzt kapt) |
| Compose BOM | 2026.09.00 |
| Hilt | 2.60.1 |
| androidx.hilt | 1.4.0 |
| core-ktx / AppCompat / Activity | 1.19.1 / 1.8.0 / 1.13.0 |
| Coroutines | 1.11.0 |
| Room | 2.8.5 |
| WorkManager | 2.12.0 |
| OkHttp | 5.5.0 |

Alle Versionen, Bibliotheken und Plugins stehen im Versionskatalog `gradle/libs.versions.toml`;
die Buildskripte sind in Kotlin DSL (`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`marketdata/build.gradle.kts`, `baselineprofile/build.gradle.kts`).

Hinweise zu AGP 9: Kotlin kommt über den Klassenpfad im Wurzel-`build.gradle.kts`
(`kotlin-gradle-plugin`), `jvmTarget` folgt `compileOptions` (Java 17). R8
packt Klassen ab AGP 9.1 standardmässig in andere Pakete um; die Börsenklassen
sind über `marketdata/consumer-rules.pro` und `proguard-rules.pro` geschützt. Ihr Schlüssel
(`Market.key`) ist ein fester Text je Börse (gleich dem früheren Klassennamen).

**Room-Schemas – nach dem nächsten Build `app/schemas/…` committen:** Im Repository liegen
die Schemas 1–3, 13, 14 und 15 (`app/schemas/com.cryptochecker.app.data.local.AppDatabase/`). **Ausnahme 15
(Build 44):** von Hand geschrieben (Spalten `exchangeTime`, `priceChangedAt`); ihr `identityHash` stimmt erst,
wenn ein echter Build die Datei neu erzeugt hat — danach die erzeugte Fassung committen.
`exportSchema = true` und `room { schemaDirectory("$projectDir/schemas") }` sind gesetzt;
der Build (z. B. `./gradlew :app:assembleDebug`) schreibt das Schema der aktuellen
Datenbank-Version (`AppDatabase.VERSION`); 4–12 lassen sich nicht mehr erzeugen. Diese JSON-Dateien mitcommitten und nie von Hand
schreiben – sie sind die Grundlage für Migrationstests und für jede weitere Migration.

## Umfang

* 41 Märkte: Coinbase, Kraken, Gate.io, Binance, Binance.US, KuCoin,
  OKX (+ Futures), Binance Futures, Bybit (+ Futures), Bitget (+ Futures),
  MEXC (+ Futures), HTX (+ Futures), Bitfinex, Bitstamp, Bitvavo, Crypto.com,
  Gemini, Hyperliquid, WOO X (+ Futures), Deribit (Perpetuals), Phemex
  (+ Futures), Poloniex (API v3), One Trading (ehem. Bitpanda Pro), Upbit,
  Bithumb, bitFlyer, BtcTurk, Bitso, Indodax, ZebPay, Independent Reserve,
  LATOKEN, NonKYC und DexScreener (`marketdata/.../config/MarketsConfig.kt`).
  Xcalibra ist seit Januar 2025 geschlossen.
* Belegt abgeschaltete Dienste entfernt: Poloniex (Legacy-API 28.02.2023),
  Uniswap V2 (The-Graph-Hosted-Service 12.06.2024), Bitpanda Pro
  (Umbenennung zu One Trading, alte Domain 04.01.2024).
* App-Icon: weisse Glocke mit steigender Kurslinie auf orangem Verlauf (Markenfarbe), als adaptives Vektor-Icon mit
  Varianten je Akzentfarbe (`res/drawable/ic_launcher_*`); Store-Icon
  `docs/store/icon_512.png`. Die alten blauen Globus-Grafiken (`docs/branding`)
  sind entfernt.
* Sammelaktion «Alle …-Paare dieser Börse» mit wählbarer Gegenwährung
  (früher «Alle USDT-PERPS»; `domain/model/MarketPairsInfo.kt`,
  `ui/features/explorer/ExplorerViewModel.kt`), mit Schnellfiltern Gewinner / Verlierer / Volumen
  (eine Sammelabfrage der Börse beim Öffnen, höchstens einmal pro Minute).
* Künstliche Ladeverzögerung beim Start der Börsenliste entfernt.
* Oberfläche englisch, Übersetzungen in `values-*` (31 Sprachen insgesamt,
  Portugiesisch als `values-pt` für Portugal und `values-pt-rBR` für Brasilien);
  die deutsche in `values-de` ist vollständig. Jeder Ordner hat dieselben Bereichsdateien
  `strings_<bereich>.xml` (common, watchlist, explorer, onboarding, pair_sheet, why, market,
  indicators, alarms, alarm_log, delivery, notifications, refresh, portfolio, portfolio_cost,
  addresses, csv, tax, backup, settings, widgets, a11y); ein Schlüssel steht in jeder Sprache in derselben Datei, nach Präfix gruppiert.

## Dokumentation

* Datenschutzerklärung (Android und iOS, DE/EN): [`docs/privacy/index.html`](docs/privacy/index.html)
* Veröffentlichung bei Google Play: Store-Texte und Versionshinweise liegen unter `fastlane/metadata/android/`; Signieren siehe `keystore.properties.example`

## Unterstützen

Die Spendenadressen stehen nur auf der Seite `SUPPORT.md` des Repositorys auf
GitHub (github.com/r1adBE/cryptoChecker), nicht in der App, nicht auf der
Startseite und nicht in den Store-Texten (Apple 3.1.1, Google Play Billing).

## Öffentliches Repository

Der komplette Quellcode ist öffentlich unter MIT:
<https://github.com/r1adBE/cryptoChecker> (dieser Ordner als `android/`, die
iOS-App als `ios/`, dazu Datenschutzseite unter `docs/`). Anleitung für neue
Börsen: `DEVELOPMENT.md` im Repository (lokal `public/DEVELOPMENT.md`).
GitHub Actions bauen und testen die App bei jeder Änderung; die instrumentierten Tests laufen von Hand
auf einem Emulator (Actions › «Android device tests», `.github/workflows/android-device-tests.yml`). Hochladen mit
`setup-github-public.ps1` im Hauptordner, siehe dort; Schlüssel,
`keystore.properties` und `local.properties` werden nie hochgeladen.

## Lizenz

MIT, siehe `LICENSE`. Portions derived from BitcoinChecker by mobnetic and Aneonex Software (MIT): deren
ursprüngliche Copyright-Vermerke stehen in `LICENSE` und auf der Lizenzseite beider Apps. Namen, Logos und Marken von Coins, Token, Aktien, Börsen und anderen Dritten sowie
Marktdaten von Drittanbietern (z. B. CoinGecko, Binance, alternative.me, Coin Metrics) gehören nicht zum Code
und fallen nicht unter die MIT-Lizenz.

Schrift für Beträge: **Rubik** (Copyright 2015 The Rubik Project Authors,
SIL Open Font License 1.1), aus google/fonts `ofl/rubik`, als Schnitte
Regular/Medium/SemiBold aus der variablen Schrift erzeugt und auf Latein,
Kyrillisch, Hebräisch und Satzzeichen beschränkt (`app/src/main/res/font/rubik_*.ttf`,
zusammen rund 360 KB). Lizenztext: `app/src/main/assets/licenses/Rubik-OFL.txt`.
