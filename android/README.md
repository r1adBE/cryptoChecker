# cryptoChecker

**Crypto Checker erklärt dir, was im Markt passiert.** Kurse direkt von der
Börse, Marktüberblick und «Warum bewegt sich das?» – ohne Prognosen, keine
Anlageberatung.

Kursüberwachung für Kryptowährungen: Merkliste, Benachrichtigungen, Alarme,
Sprachausgabe und Startbildschirm-Widgets über 41 Märkte – 32 Börsen (teils
mit Spot und Futures) plus DexScreener für DEX-Token. 31 Sprachen, kein Konto,
keine Werbung, kein Tracking; die Kurse kommen direkt von den Börsen.

r1AD — riad.work@outlook.com

* Anzeigename: **Crypto Checker**
* applicationId: `com.cryptochecker.app`
* Module: `marketdata` (Börsen-Bibliothek), `app` (die App)
* Pakete: `com.cryptochecker.marketdata`, `com.cryptochecker.app`
* Version: **16.2.2** (versionCode 17)
* Quellcode: <https://github.com/r1adBE/cryptoChecker> (öffentlich, MIT; dieser Ordner = `android/`)

## Neu in 16.2.2

### Zuletzt dazugekommen

* **Live-Kurse per WebSocket** — solange die Merkliste offen ist, kommen die Kurse
  von Binance, Bybit, OKX, Coinbase und Kraken live (REST bleibt Rückfall); «LIVE»
  in der Status-Pille, Schalter unter Einstellungen › Aktualisierung
  (`domain/live/LiveFeed.kt`, `data/live/LivePriceStream.kt`, siehe `DEVELOPMENT.md`).
* **4 Tabs** — Merkliste · Markt · Portfolio (optional) · Einstellungen; «Paar
  hinzufügen» öffnet sich über «+» in der Merkliste, kein eigener Such-Tab mehr.
* **Kein Willkommensdialog** — eine Neuinstallation zeigt direkt die Startauswahl;
  «Was die App kann» steht unter Einstellungen › Über.
* **Alarm-Editor als Satz** — «Wenn BTC über … geht» mit Kurs-Vorschlag,
  «Einmal / Jedes Mal», übrige Bedingungen unter «Erweitert»; **Vorlagen** ±1 %,
  ±5 %, neues 30-Tage-Hoch/-Tief, Volumen ×3 mit einem Tipp (mit «Rückgängig»;
  `domain/alarm/AlarmTemplates.kt`).
* **Funding- und Open-Interest-Alarme** für Perpetuals (Binance, Bybit, OKX):
  «Funding über/unter x %» und «Open Interest steigt/fällt um x % in 1/4/24 h»
  (`domain/alarm/DerivativesAlarm.kt`, Room-Version 11).
* **TradFi-Futures** — Kontrakte auf Aktien, Rohstoffe, Devisen und Pre-IPO
  bei Binance, Bybit, OKX, MEXC und Bitget Futures (`marketdata/util/TradFi.kt`) als Perpetuals mit
  Kennzeichen `tradFi`; sichtbar nur mit dem Schalter
  unter Einstellungen › Merkliste (Room-Version 12, `MIGRATION_11_12`).
* **Portfolio-Alarme** — Gesamtwert über/unter einem Betrag, Veränderung heute
  ±x % (`domain/alarm/PortfolioAlarmLogic.kt`); **Beträge verbergen** zeigt Werte im
  Portfolio, im Portfolio-Widget und in Portfolio-Alarmen als «•••».
* **Sicherung mit Passwort** — AES-256-GCM, Schlüssel per PBKDF2 (`data/BackupCrypto.kt`),
  austauschbar mit iOS.
* **Basis der %-Änderung** (wie Binance «Change(%) & Chart Timezone») — letzte 24 Std., seit
  00:00 in der Zone des Geräts oder in einer festen Zone UTC+14 … UTC-12
  (eigene Unterseite in den Einstellungen; `domain/watch/ChangeBasis.kt`).
* **Suche in den Einstellungen** — ohne Akzente und Gross-/Kleinschreibung, springt
  zum Punkt und hebt ihn kurz hervor (`settings/SettingsSearch.kt`).
* **«Warum?» als Faktorliste** mit Satz zum Gleichlauf mit Bitcoin; BTC-Paare zeigen
  «1 CHF = n Sats»; Markt-Tab mit Quelle und Alter je Zeile.

### Frühere Runden

* **Theme «Marrs Green»** — fünfte Akzentfarbe (#4BACA5, das Türkis aus der
  G.F-Smith-Umfrage 2017) mit eigenem App-Icon und Logo; die Einstellung heisst jetzt
  **Theme** (vorher «Farbe»). Themes: Orange, Rot, Blau, Grün und Marrs Green.
* **⚡ Ungewöhnliche Aktivität** — erkennt ungewöhnliche Kursbewegungen im
  Vergleich zur normalen Volatilität, Volumen-Spikes, extreme Funding Rates und
  Sprünge im Open Interest; Benachrichtigungen optional (standardmässig aus).
  **Empfindlichkeit** Weniger/Normal/Mehr (Schwellen ×1,5/×1/×0,75, eine Stelle in
  `ActivitySensitivity.kt`, gilt für Karte und Meldungen; «Anpassen» in der Karte).
* **Sprungknopf** in langen Merklisten (> 30 Paare): «Zum Anfang» / «Zum Ende» beim Scrollen (`WatchJump.kt`).
* **💡 «Warum bewegt sich das?»** — Markt vs. Coin, Volumen, Hebel
  (Funding/Open Interest), Volatilität und Fear & Greed. Nur Daten, keine News,
  keine Anlageberatung.
* **Portfolio** — eigener Tab (in den Einstellungen einschaltbar): Käufe und
  Verkäufe mit Datum und Preis, Durchschnittspreis, Gewinn/Verlust, Summe in
  USDT plus Umrechnung in eine wählbare Währung (31 Währungen; Standard
  richtet sich nach der Region des Geräts, z. B. CHF in der Schweiz, EUR in
  Deutschland, sonst USD).
* **«≈ Umrechnung» in der Merkliste** — zweite Zeile «≈ 58 912 EUR» unter
  der Veränderung, in der Umrechnungswährung (Einstellungen → Währung &
  Umrechnung; gilt
  auch für Portfolio, Alarme in deiner Währung, Karte Krypto-Markt und
  Portfolio-Widget).
  Kurse: Frankfurter (EZB), Ersatz open.er-api.com
  (`domain/convert/CurrencyConversion.kt`, `data/portfolio/CurrencyConverter.kt`).
* **Alarme in deiner Währung** — Kursalarme können in einer anderen Währung
  als der Börsen-Währung gesetzt werden (z. B. 60 000 EUR auf BTC/USDT).
  Geprüft wird `Kurs × Wechselkurs`; ohne Kurs wird der Alarm in dieser Runde
  übersprungen (Room-Version 9, Spalte `alarms.currency`).
* **Stichtag-Export** — Portfolio-Wert an einem Datum (z. B. 31.12.) in der
  gewählten Währung als CSV (UTF-8 mit BOM, `;`, Excel-tauglich):
  Tagesschlusskurs (UTC) von Binance/Coinbase, Devisenkurs des Tages von
  Frankfurter. Keine Steuerberatung (`domain/portfolio/CutoffExport.kt`).
* **Barrierefreiheit** (Einstellungen → Darstellung, gilt für App und Widgets):
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
    Flächen inkl. getönter Pillen (`PriceColorSchemeTest`).
  * **TalkBack** — jede Merklisten-Zeile ist ein Satz (Paar, Börse, Kurs,
    Veränderung, ≈ Wert, 24-Std.-Chart, Notiz, Alarme, Zustand); Zyklus-Chart,
    Fear & Greed, Marktphase, Widget-Zeilen und Widget-Chart werden
    beschrieben (`util/A11yText.kt`, `util/ChartSummary.kt`).
* **Mini-Chart** — 24-Std.-Linie in jeder Merklisten-Zeile (stündliche
  Schlusskurse, 15 Min. zwischengespeichert, ab 360 dp Bildschirmbreite;
  abschaltbar). Für TalkBack Teil des Zeilensatzes (Start, Ende, Hoch, Tief).
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
* **Zonen** — Extreme Bear · Bear · Neutral · Bull · Extreme Bull heissen in
  allen 31 Sprachen gleich (Englisch), wie Funding oder Halving.
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
* **Widgets** — kompakte zweizeilige Zeilen mit ▲/▼ (0,00 % grau, ohne Pfeil),
  neutrales Aktualisieren-Symbol; Zeit/Dauer im Kopf wird nach einer
  Hintergrund-Aktualisierung jetzt nachgeführt.
* **Neues App-Icon und Farben** — weisse Glocke mit steigender Kurslinie;
  Themes Orange, Rot, Blau, Grün und Marrs Green, jeweils mit Verlauf und
  passendem App-Icon.
* Marktdaten für Charts, Volumen und Zyklus kommen zusätzlich zu den
  Börsen-APIs von `data-api.binance.vision` (öffentlicher Marktdaten-Spiegel
  von Binance).

### Verstehen statt nur Zahlen (16.2.2)

* **Crypto Pulse «Was gerade auffällt»** — erste Karte im Markt-Tab:
  Schlagzeile mit Leitsatz (z. B. «Bitcoin führt den Markt an.») und einem
  zweiten Satz zu Volumen und Funding, darunter BTC, ETH, SOL (24 h) und ein
  Funding-Chip (nur wenn erhöht/negativ). «Warum? →» klappt «Markt heute»
  (nur Altcoins vs. Bitcoin — die Kurse stehen schon in den Pillen) und die Faktor-Checkliste auf — nur Funding (! / –);
  Volumen, Fear & Greed und Gas stehen nicht doppelt, sie haben eigene Karten
  im Tab. Feste Regeln, keine Prognose (`domain/market/CryptoPulse.kt`,
  `ui/features/info/CryptoPulseCard.kt`).
* **«Heute auffällig»** — Karte direkt unter dem Pulse (Abschnitt «Jetzt», Teil des
  schrittweisen Erscheinens): bis zu fünf der rund 30 grössten Coins (ohne Stablecoins, mit
  …USDT auf Binance), die heute deutlich stärker/schwächer als BTC laufen (≥ 3 Prozentpunkte),
  gegen den Markt (≥ 2 %, andere Richtung als BTC), mit ungewöhnlichem Volumen (≥ 2× üblich) oder
  extremem Funding (≥ 0.05 % / ≤ −0.03 %); je Zeile Plakette, Symbol, 24-h-Pille und ein Satz.
  Sonst «Heute nichts Auffälliges». Daten mit je einem Abruf: 24-h-Ticker (Binance-Spiegel),
  Funding aller Perpetuals (Binance Futures), Marktkapitalisierung aus der CoinGecko-Rangliste
  (24 h, wie die Start-Coins). «Üblich» = eigener Median der Vortage (Tageswert auf dem Gerät),
  anfangs der Median aller Coins — keine Kerzen je Coin. Zwischenspeicher 10 Min.; Tippen öffnet
  «Warum?» (Coin in der Merkliste) oder die Suche auf der Seite «Paar hinzufügen»
  (`domain/market/MarketUnusual.kt`, `data/remote/UnusualDataSource.kt`, `ui/features/info/MarketUnusualCard.kt`).
* **Hinweis «Wirtschaftsdaten»** — kompakte Zeile über dem Pulse, wenn heute (oder in den
  nächsten 18 h) wichtige US-Daten anstehen: «Heute 14:30: US-Inflationsdaten (CPI) – an solchen
  Tagen schwankt der Markt oft stärker.» (CPI, PPI, Arbeitsmarkt, Fed-Zinsentscheid, PCE; Ortszeit,
  mehrere Termine in einer Zeile; 2 h nach dem Termin «… veröffentlicht» bis Tagesende). Kalender
  einmal am Tag von der App-Webseite (`https://r1adbe.github.io/cryptoChecker/macro/events.json`,
  24 h zwischengespeichert, sonst `assets/macro_events.json`). Optional Morgen-Meldung um 08:00
  (Einstellungen → Alarme & Benachrichtigungen → «Wirtschaftstermine», Standard aus)
  (`domain/macro/MacroCalendar.kt`, `data/MacroCalendarRepository.kt`, `work/MacroNotifyWorker.kt`).
* **«Warum bewegt sich …?» als Checkliste** — oben «Kurz gesagt» in einem Satz,
  darunter die Faktoren (Markt vs. Coin, Bitcoin gibt die Richtung vor,
  Volumen, Hebel, Schwankung 1h, Fear & Greed) mit ✓ (stützt die Bewegung),
  ! (erhöht) oder – (neutral); Rohwerte unter «Details anzeigen», Fusszeile
  «Keine Prognose · Keine Anlageberatung» (`domain/activity/WhySummary.kt`,
  `ui/features/watchlist/WatchWhySheet.kt`, `WatchWhyFactors.kt`).
* **Einstellungen in Gruppen** — Darstellung · Währung & Umrechnung · Alarme &
  Benachrichtigungen · Daten & Aktualisierung · Portfolio · Sicherheit & Backup
  · Erweitert (eingeklappt) · Über (`ui/features/settings/SettingsScreen.kt`), mit
  Suchfeld oben (`ui/features/settings/SettingsSearchUi.kt`).
* **Aktionsblatt** — Tippen auf eine Zeile öffnet die Aktionen; oben die drei
  häufigsten gleich gross: Alarm, Warum?, Favorit. Nach dem Hinzufügen bietet
  der Hinweis «… wird jetzt überwacht» direkt «Alarm setzen», solange die
  neuen Paare noch keinen Alarm haben.
* **Chart im Aktionsblatt** — über Alarm · Warum? · Favorit: 24h · 7T · 30T, Kerzen/Linie (zuletzt gewählt), Geometrie und Kerzen wie das Einzel-Widget, lange drücken/waagrecht ziehen zeigt Kurs, Zeit und Veränderung; DEX-Paare ohne Chart (`ui/features/watchlist/WatchSheetChartView.kt`, `domain/watch/SheetChart.kt`).
* **Stern nur bei Favoriten** — in der Merkliste steht der Stern nur noch vor
  Favoriten, nicht als leerer Umriss in jeder Zeile.
* **Alarm testen** — Einstellungen → Alarme & Benachrichtigungen; gleicher Weg wie ein echter
  Alarm, aber ohne Nachtruhe. Nach dem ersten Alarm: «Alles eingerichtet».
* **Zyklus-Signale** — statt «Top-/Bottom-Score» heisst es «Hinweise auf ein
  mögliches Hoch/Tief», mit «Kein Kursziel und keine Prognose».
* **Willkommen ohne Dialog** — eine Neuinstallation öffnet direkt die leere
  Merkliste mit der Starter-Auswahl; «Was die App kann» (Was passiert? · Wann
  reagieren? · Warum?) steht unter Einstellungen › Über; Beispiel im leeren Portfolio.
* **Kleinere Korrekturen** — Akzentfarben im hellen Modus mit AA-Kontrast,
  Pfeile ▲/▼ bei allen Veränderungen, TalkBack-Aktionen «Nach oben/unten
  verschieben», BGN aus dem festen Euro-Kurs 1,95583, Hinweis beim Export für
  den heutigen Tag, eindeutige PendingIntents für Widgets und Mitteilungen.
* **Einzel-Widget mit Kerzen** — Chart-Art pro Widget wählbar (Kerzen, Standard,
  oder Linie): 24 Stundenkerzen, 42 Kerzen à 4 Std. oder 30 Tageskerzen; Preise
  für Tief, Mitte und Hoch am rechten Rand, aktueller Kurs als Marke in der
  Akzentfarbe, senkrechte Linien je Stunde, Tag bzw. Woche
  (`widget/WidgetChartGeometry.kt`, `WidgetChartRenderer.kt`).
* **Brasilianisches Portugiesisch** (`values-pt-rBR`), damit 31 Sprachen.
* **Keine Spenden in der App** — siehe «Unterstützen» unten.

### Bedienung und Alltag (16.2.2)

* **Mit einem Tipp starten** — leere Merkliste bietet die fünf grössten Coins
  nach Marktkapitalisierung ohne Stablecoins (CoinGecko, 24 h zwischengespeichert,
  Ersatz BTC, ETH, XRP, BNB, SOL), Binance USDT bzw. in den USA Coinbase USD
  (`domain/starter/StarterCoins.kt`). Jede Zeile mit Live-Kurs und
  24-Std.-Veränderung, alle vorausgewählt, ein Knopf «Zur Merkliste hinzufügen (5)». Das Hinzufügen ist kurz inszeniert: Zeile
  blendet ein, Mini-Chart zeichnet sich, Häkchen, Haptik, «… wird jetzt
  überwacht» (bei reduzierter Bewegung ohne Animation).
* **Wischen in der Merkliste** — nach links = löschen mit «Rückgängig», nach
  rechts = Favorit (`ui/features/watchlist/WatchSwipe.kt`). Nicht im Sortiermodus.
  Ein Tipp öffnet die Aktionen sofort; kein Doppeltippen (es hätte jeden Tipp
  um ~0,3 s verzögert) — Favorit auch per Stern oder im Aktionen-Menü. «Löschen» im
  Aktionen-Menü wirkt wie Wischen: sofort, mit «Rückgängig», ohne Rückfrage.
* **Akku-Hinweis erst nach dem ersten Alarm** — nie beim ersten Start oder beim
  Hinzufügen der ersten Coins; er erscheint, sobald man nach dem ersten Alarm
  wieder auf einem Haupt-Tab ist (`ui/features/about/BatteryOptimizationDialog.kt`).
* **Markt-Tab in drei Abschnitten** — «Jetzt» (Crypto Pulse, «Heute auffällig»,
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
* **Nachtruhe** — Alarme (Kurs, Volumen, Gas, ungewöhnliche Aktivität) kommen
  im gewählten Zeitraum (Standard 23–7 Uhr, aus) lautlos über den Kanal
  `alarms_quiet`, ohne Sprachausgabe (`domain/alarm/QuietHours.kt`).
* **Portfolio-Sperre** — `androidx.biometric`: Fingerabdruck, Gesicht oder Geräte-PIN;
  sperrt nur das Portfolio (Tab, Coin-Detail, Erfassen-Blätter, Stichtag-Export,
  «Zum Portfolio hinzufügen» aus der Merkliste, Sichern mit Portfolio-Daten,
  Wiederherstellen und Ausschalten der Sperre, Portfolio-Widget) — Merkliste,
  Hinzufügen, Markt-Tab, Einstellungen und die übrigen Widgets bleiben frei. Einmal je
  Sitzung entsperren; wieder gesperrt beim Neustart und nach > 60 s im Hintergrund;
  gesperrt zeigt der Tab einen ruhigen Zustand (Schloss, «Entsperren»). Zeile in den
  Einstellungen nur mit eingeschaltetem Portfolio (Wert bleibt erhalten); ab Android 13 kein
  Vorschaubild in den letzten Apps (`lock/`, Regeln in `lock/PortfolioLockPolicy.kt`,
  Oberfläche `ui/lock/PortfolioLock.kt`).
* **Wertverlauf** im Portfolio — standardmässig zugeklappt: eine Zeile «Wertverlauf ·
  30 T ▲ +4.20%» (nur der gewählte Zeitraum wird geladen und gerechnet); aufgeklappt
  Chips 7 T / 30 T / 1 J / Seit 1. Kauf. «Seit 1. Kauf» lädt so viele Tageskerzen wie
  nötig in Stücken von höchstens 1000 (`PortfolioHistory.candleChunks`), höchstens
  5 Jahre zurück (Hinweis darunter). Auf-/Zuklappen und Zeitraum werden gemerkt
  (nicht in der Sicherung).
* **Portfolio-Widget** — passt sich der Grösse an: klein Gesamtwert in der
  Umrechnungswährung und (je Widget wählbar, «Umrechnung + USDT») «≈ … USDT»;
  ab 110 dp Höhe dazu die Veränderung heute als Pille (Vergleich mit gespeicherten
  Kursen ≥ 20 h alt, Käufe/Verkäufe zählen nicht als Gewinn) und der Wertverlauf
  des heutigen Bestands (bis 48 h, Linie ohne Achsen); ab 250 × 180 dp die
  grössten Positionen (bis 5, Wert, Anteil, Veränderung, «+ n weitere»). Einrichten
  optional (`PortfolioWidgetConfigureActivity`); bei Portfolio-Sperre nur Titel,
  Schloss und «Gesperrt – in der App entsperren», ohne Werte
  (`widget/Portfolio*`, Regeln in `domain/portfolio/PortfolioWidgetMath.kt`).
  Klein (z. B. 2 × 1) kompakter, damit «≈ … USDT» noch passt; ist «heute» zu
  breit, nur der Prozentwert.
* **Widget «Was gerade auffällt»** — Crypto Pulse auf dem Startbildschirm:
  Schlagzeile mit ▲/▼ in der Kursfarbe, Leitsatz (2 Zeilen), Coin-Chips (so viele
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
* **Erklärungen** — ⓘ bei Funding Rate, Open Interest, RSI und Pi-Cycle;
  DEX-Intro und «Gewinn/Verlust, noch nicht verkauft» verständlicher.
* **Einstellungen «Erweitert»** — «Häufig aktualisieren», feste Mitteilung, Futures und
  HTTP-Log eingeklappt am Ende.
* **Ruhiger Feinschliff** — Puls-Zeile ganz oben in der Merkliste («▲ 7 steigen · ▼ 3
  fallen · Ø ▲ +1.80% 24h»; Grundlage ist derselbe Wert wie die Prozent-Pille
  der Zeilen, also die Veränderung über 24 Stunden; Paare ohne 24-h-Wert zählen nicht; folgt der Gruppe,
  aus beim Suchen/Sortieren, `domain/watch/WatchPulse.kt`), Mini-Chart 28 dp mit
  sanfter Fläche, rollende Ziffern bei Kurs und Portfolio-Summe
  (`ui/components/RollingNumberText.kt`, ohne Animationen ein normaler Text),
  grosse Portfolio-Summe mit «heute»-Pille, Beträge in Rubik mit gleich breiten
  Ziffern, Widgets mit ▲/▼ in der Liste; im Listen-Widget eine dünne Trennlinie
  (1 dp, Akzentfarbe mit 25 %, bei hohem Kontrast 45 % Deckkraft, eingerückt wie
  der Text) zwischen den Paaren, nicht unter dem letzten.
* **Android 15** — Live-Dienst (dataSync) beendet sich beim 6-Stunden-Limit
  sauber (`PriceService.onTimeout`) und wird nach einem Neustart nicht mehr aus
  `BOOT_COMPLETED` gestartet, sondern beim Öffnen der App.

## Funktionen

**Merkliste** — Börse und Handelspaar auf der Seite «Paar hinzufügen» («+» neben der Lupe in der Merkliste) wählen und übernehmen
(oder bei leerer Merkliste die Startauswahl nutzen). Jeder Eintrag speichert
Kurs, Vorkurs und Zeitpunkt in Room, dazu die Veränderung über 24 Stunden
(`change24h`, Room v10): Die Prozent-Pille «▲ +1.80% 24h» (auch Aktionen-Kopf,
Puls-Zeile, Widgets, TalkBack «… in 24 Stunden») vergleicht den Kurs mit der
Eröffnung der Stundenkerzen vor 24 h — dieselben Kerzen wie der Mini-Chart
(`SparklineRepository`, je Paar in seiner Quote; USD-artige teilen die USDT-Reihe,
Fiat-Quotes ohne eigene Kerzen nehmen den Verlauf der USDT-Reihe). Ohne Kerzen
zeigt die Pille grau «—» (`domain/watch/DayChange.kt`). Tippen öffnet die Aktionen (Alarm, Warum?,
Favorit oben), wischen: links löschen, rechts Favorit, halten zum Sortieren.

**Benachrichtigungen** — je Paar eine lautlose Dauerbenachrichtigung mit Kurs,
Veränderung in Prozent und Uhrzeit. Wahlweise wegwischbar oder fest.

**Alarme** — Kurs über/unter einem Wert, Anstieg oder Fall um x Prozent,
Bewegung um x % in y Stunden, Volumen-Spike, nahe am Hoch/Tief, Funding und Open
Interest (Perpetuals von Binance, Bybit, OKX); wahlweise in deiner Währung,
angezeigt als Satz, mit Vorlagen (±1 %, ±5 %, neues 30-Tage-Hoch/-Tief,
Volumen ×3), Nachtruhe und «Alarm testen». Dazu Portfolio-Alarme (Gesamtwert
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
grössten Positionen) sowie das Widget «Was gerade auffällt» (Crypto Pulse). Beim Ablegen wird der
Hintergrund gewählt (transparent mit hellem oder dunklem Text, dunkel, hell,
marineblau). Ein Tipp auf eine Zeile öffnet die App, der Knopf aktualisiert.

**Massenabfrage** — Wer viele Paare derselben Börse beobachtet, bekommt sie in
einer einzigen Anfrage statt in Dutzenden. `Market` hat dafür einen optionalen
Haken (`bulkTickersNumOfRequests`, `getBulkTickersUrl`, `parseBulkTickers`);
umgesetzt ist er für alle Märkte ausser Coinbase und DexScreener. Märkte
ohne diesen Haken werden weiter Paar für Paar abgefragt, und scheitert die
Massenabfrage, fällt der Refresher automatisch darauf zurück. Ab drei Paaren einer Börse greift der Sammelweg.
Wo die Börse eine Auswahl dokumentiert, werden nur die beobachteten Paare abgefragt
(Binance/Binance.US `symbols`, Upbit/Bithumb `markets`, Kraken `pair`, Bitfinex `symbols`;
lange Listen über `BulkPairChunks` auf mehrere URLs unter 2000 Zeichen verteilt);
scheitert die gefilterte Abfrage, folgt einmal die ungefilterte. Coinbase braucht je Paar
eine Anfrage (`/stats`: Kurs, Hoch/Tief, Volumen und Kurs vor 24 h).

**Aktualisierung** — WorkManager im Hintergrund (ab 15 Minuten, Android-Grenze)
und ein Vordergrunddienst «Häufig aktualisieren» für kurze Intervalle ab 15 Sekunden.
In der geöffneten Merkliste kommen die Kurse der unterstützten Börsen live per
WebSocket (Schalter «Live-Kurse»); Hintergrund und Widgets bleiben bei REST.
Ein Boot-Receiver plant nach einem Neustart die Hintergrundaktualisierung neu;
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
              PulseWidgetRenderer), WidgetUpdater entscheidet, was neu muss
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
`WatchSheetFutures`, `WatchSheetChartView`, `WatchSheetChartCanvas`), Portfolio
(`PortfolioScreen`, `PortfolioTotalCard`, `PortfolioRows`, `PortfolioHistoryCard`,
`PortfolioHistoryChart`). ViewModels bleiben je Bildschirm eine Klasse.

## Bauen

```bash
./gradlew test
./gradlew :app:assembleDebug
```

**JDK 17** (Android Studio bringt es mit), compileSdk 37, targetSdk 36, minSdk 26.
Beim ersten Öffnen lädt Android Studio Gradle 9.8.0 und, falls nötig, die
SDK-Plattform 37.

| Baustein | Version |
|---|---|
| Android Gradle Plugin | 9.2.1 (Kotlin eingebaut, ohne `org.jetbrains.kotlin.android`) |
| Gradle | 9.8.0 |
| Kotlin | 2.3.21 (K2, Compose-Compiler-Plugin) |
| KSP | 2.3.11 (ersetzt kapt) |
| Compose BOM | 2025.10.01 |
| Hilt | 2.60.1 |
| androidx.hilt | 1.4.0 |
| core-ktx / AppCompat / Activity | 1.19.1 / 1.8.0 / 1.13.0 |
| Coroutines | 1.11.0 |
| Room | 2.8.5 |
| WorkManager | 2.12.0 |
| OkHttp | 5.5.0 |

Hinweise zu AGP 9: Kotlin kommt über den Klassenpfad im Wurzel-`build.gradle`
(`kotlin-gradle-plugin`), `jvmTarget` folgt `compileOptions` (Java 17). R8
packt Klassen ab AGP 9.1 standardmässig in andere Pakete um; die Börsenklassen
sind über `proguard-rules.pro` geschützt, weil ihr Klassenname als Schlüssel dient.

**Room-Schemas – nach dem ersten Build `app/schemas/…` committen:** Im Repository liegen
bisher nur die Schemas 1–3 (`app/schemas/com.cryptochecker.app.data.local.AppDatabase/`).
`exportSchema = true` und `room { schemaDirectory "$projectDir/schemas" }` sind gesetzt;
der erste Build (z. B. `./gradlew :app:assembleDebug`) schreibt die fehlenden Schemas ab 4
bis zur aktuellen Datenbank-Version (`AppDatabase.VERSION`). Diese JSON-Dateien mitcommitten und nie von Hand
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
  `ui/features/explorer/ExplorerViewModel.kt`).
* Künstliche Ladeverzögerung beim Start der Börsenliste entfernt.
* Oberfläche englisch, Übersetzungen in `values-*` (31 Sprachen insgesamt,
  Portugiesisch als `values-pt` für Portugal und `values-pt-rBR` für Brasilien);
  die deutsche in `values-de` ist vollständig.

## Dokumentation

* Datenschutzerklärung (Android und iOS, DE/EN): [`docs/privacy/index.html`](docs/privacy/index.html)
* Anleitung Google Play: [`docs/PLAY_STORE.md`](docs/PLAY_STORE.md)
* Store-Texte und Versionshinweise: `fastlane/metadata/android/`

## Unterstützen

Die Spendenadressen stehen nur auf der Seite `SUPPORT.md` des Repositorys auf
GitHub (github.com/r1adBE/cryptoChecker), nicht in der App, nicht auf der
Startseite und nicht in den Store-Texten (Apple 3.1.1, Google Play Billing).

## Öffentliches Repository

Der komplette Quellcode ist öffentlich unter MIT:
<https://github.com/r1adBE/cryptoChecker> (dieser Ordner als `android/`, die
iOS-App als `ios/`, dazu Datenschutzseite unter `docs/`). Anleitung für neue
Börsen: `DEVELOPMENT.md` im Repository (lokal `public/DEVELOPMENT.md`).
GitHub Actions bauen und testen die App bei jeder Änderung. Hochladen mit
`setup-github-public.ps1` im Hauptordner, siehe dort; Schlüssel,
`keystore.properties` und `local.properties` werden nie hochgeladen.

## Lizenz

MIT, siehe `LICENSE`.

Schrift für Beträge: **Rubik** (Copyright 2015 The Rubik Project Authors,
SIL Open Font License 1.1), aus google/fonts `ofl/rubik`, als Schnitte
Regular/Medium/SemiBold aus der variablen Schrift erzeugt und auf Latein,
Kyrillisch, Hebräisch und Satzzeichen beschränkt (`app/src/main/res/font/rubik_*.ttf`,
zusammen rund 360 KB). Lizenztext: `app/src/main/assets/licenses/Rubik-OFL.txt`.
