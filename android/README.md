# Crypto Checker (Android)

**Crypto Checker ist ein Krypto-Checker: Kurse direkt von deiner Börse; Hinweise, wenn beim Nachsehen eine
Marke erreicht ist – ohne Konto.** Dazu Marktüberblick und als Zusatz «Was bewegt sich mit?» (was sich gleichzeitig
bewegt – keine Ursache) – ohne Prognosen, keine Anlageberatung.

Kursüberwachung für Kryptowährungen: Merkliste, Benachrichtigungen, Alarme,
Sprachausgabe und Startbildschirm-Widgets über 82 Märkte: 78 auf 69 Börsen (teils mit Spot und Futures),
dazu DexScreener für DEX-Token (GeckoTerminal als Reserve) und Referenzkurse von EZB, Nationalbank der Ukraine und
Polnischer Nationalbank für Devisen. 75 Sprachen, kein Konto,
keine Werbung, kein Tracking; die Kurse kommen direkt von den Börsen.

r1AD — riad.work@outlook.com (Verantwortlicher mit Name und Zustelladresse: Datenschutzerklärung `docs/privacy/index.html`)

* Anzeigename: **Crypto Checker**
* applicationId: `com.cryptochecker.app` (Handy und Uhr; Debug-Builds beider mit `.debug`)
* Module: `marketdata` (Börsen-Bibliothek), `domain` (Fachlogik ohne Oberfläche und Speicher), `data` (Datenbanken, Repositories, Datenquellen), `app` (die App), `wear` (Wear-OS-App), `baselineprofile` (erzeugt das gemessene Start-Profil, wird nicht ausgeliefert)
* Pakete: `com.cryptochecker.marketdata`, `com.cryptochecker.app` (`…app.domain`, `…app.data` = Module `domain` und `data`), `com.cryptochecker.wear`
* Version: **16.2.2** (versionCode siehe `app/build.gradle.kts` – steigt mit jedem Stand um eins)
* Quellcode: <https://github.com/r1adBE/cryptoChecker> (öffentlich, MIT; dieser Ordner = `android/`)

## Schwerpunkte von 16.2.2

* **Wear-OS-App** (Modul `wear`) — Kurse und 24 h der Lieblings-Coins vom Handy (die es schon hat) oder ohne Handy
  bzw. mit «Immer selbst abrufen» direkt von den Börsen (Binance, sonst Bybit/OKX), im Intervall des Handys (15–60 Min.,
  `WatchInterval`); Liste «wie Handy» (Favoriten ★) oder eigene (bis 8 Coins), Abgleich über die Wear-Daten-Schicht;
  Komplikationen mit dem Kurs des ersten Coins («84’450», langes Feld mit Paar, Kurs und 24 h) und (nur mit Schalter)
  dem Portfolio-Wert, sonst «am Handy einschalten»; alter Kurs grau mit Alter.
* **Preis-Overlay** (optional, Berechtigung «Über anderen Apps einblenden») — Pille mit bis zu 3 Paaren der dauerhaften
  Kurs-Mitteilung über anderen Apps, verschiebbar, Tippen öffnet die App (`overlay/`).
* **Börsenauswahl in Abschnitten** — Spot · Futures · DEX-Pools · Forex (Referenzkurs), Favoriten oben, Suche über
  alle Abschnitte; innerhalb eines Abschnitts nach Handelsvolumen am Installationstag (einmal aus den öffentlichen
  Börsenlisten von CoinGecko), ohne diese Angabe alphabetisch. Bitunix Futures; DEX-Pools mit GeckoTerminal als
  Reserve; Devisen als Referenzkurse direkt von EZB und NBU (täglich) bzw. NBP (wöchentlich), nie live und ohne
  Alarme, mit Quellzeile; dazu live Fiat-Paare von Kraken und Bitstamp.
* **Einstellungen** — Hauptseite Allgemein (Sprache, Währung, Merkliste, Portfolio, Alarme, Kurs-Aktualisierung) ·
  Darstellung (Hell / Dunkel, Akzentfarbe, Kursfarben, Coin-Logos, Widgets) · Mehr (Sicherung, Über die App) ·
  «App zurücksetzen». «Token & TradFi» und %-Änderung auf der Seite «Merkliste», Markt-Meldungen und Sprachausgabe
  auf «Alarme», Entwickleroptionen (nur Debug-Builds) auf «Über die App»; die Suche findet alles.
* **Steuer-Arbeitsunterlage als eigene Seite** — im Portfolio-Menü getrennt vom Stichtag-Export: Vorlage, Stand der
  Regeln mit Warnung für spätere Steuerjahre, die vier Exporte und die Jahresübersicht; Exporte lassen sich abbrechen
  und nennen bei Fehlern die Ursache.
* **Portfolio-Leistung** — grosse CSV-Dateien im Hintergrund mit Fortschritt «Zeile X von Y» und «Abbrechen»; FIFO,
  Durchschnittskosten, Wertverlauf, «7 T»/«1 J» und Steuer-Export bleiben auch mit vielen Transaktionen schnell.
* **Barrierefreiheit** — Charts für TalkBack Punkt für Punkt («Nächster/Vorheriger Punkt»), das Merklisten-Widget zeigt
  bei grosser Schrift den Kurs vollständig; Barrierefreiheits-Prüfungen auch mit doppelter Schriftgrösse und Arabisch
  (rechts nach links).
* **CSV-Import** aus Relai, Swissquote, Kraken, Bitpanda, Binance und Coinbase (dazu eine allgemeine Zuordnung;
  `domain/portfolio/csvimport/`).
* **Coin-Logos** bis 4 MB, damit auch die hochauflösenden Binance-Logos statt Initialen; beim ersten Laden
  «Logos werden geladen · 40 %», danach «N Coins ohne Logo» (schliessbar, Tipp listet sie).
* **Beobachtete Adressen zählen einmal** — je Coin nur, was die Adresse über die eigenen Transaktionen derselben
  Wallet hinaus hält; zeigt eine Umbuchung auf eine andere Wallet, bietet der Hinweis «Ziel ändern» an.
* **Schutz** — Sicherung mit Portfolio-Daten nur mit Passwort; Portfolio-Alarme auf dem Sperrbildschirm immer ohne
  Betrag; ab Android 12 blendet die App fremde Überlagerungen über Portfolio und heiklen Rückfragen aus
  (`HIDE_OVERLAY_WINDOWS`, `ui/lock/HideOverlays.kt`); Rückfragen vor dem Löschen per Wischen, dem Wechsel der
  Einstandsmethode und «Portfolio in Systemsicherung».
* **Schneller Start** — gemessenes Baseline- und Start-Profil liegt bei (`app/src/release/generated/baselineProfiles/`,
  siehe `DEVELOPMENT.md` in diesem Ordner).

Alle Builds: `CHANGELOG.md` im Repository-Hauptordner.

## Funktionen

**Merkliste** — Börse und Handelspaar auf der Seite «Paar hinzufügen» («+» neben der Lupe in der Merkliste) wählen und übernehmen
(oder bei leerer Merkliste die Startauswahl nutzen). Jeder Eintrag speichert
Kurs, Vorkurs und Zeitpunkt in Room, dazu die Veränderung über 24 Stunden
(`change24h`): Die Prozent-Änderung «↗ +1.80% 24h» (auch Aktionen-Kopf,
Puls-Zeile, Widgets, TalkBack «… in 24 Stunden») vergleicht den Kurs mit der
Eröffnung der Stundenkerzen vor 24 h — dieselben Kerzen wie der Mini-Chart. Sie kommen
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
Veränderung in Prozent und Uhrzeit (bei neuen Paaren an, `WatchRepository.DEFAULT_NOTIFICATION_ENABLED`; ausschalten im
Paar-Menü). Wahlweise wegwischbar oder fest («Dauerhaft in den Benachrichtigungen» unter Einstellungen ›
Kurs-Aktualisierung, ab Werk aus).

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
aus der Bibliothek (`Exchange.ttsName`). Sprechtempo einstellbar, wahlweise nur
bei Alarmen.

**Widgets** — Merklisten-Widget für den Startbildschirm: eine scrollbare Liste
aller beobachteten Paare mit Kurs und Veränderung. Dazu das Einzel-Widget
(Kerzen oder Linie, Preise Tief/Mitte/Hoch, aktueller Kurs als Marke) und das
Portfolio-Widget (je nach Grösse Gesamtwert, ≈ USDT, heute, Wertverlauf und die
grössten Positionen) sowie das Widget «Auffällig» (Crypto Pulse). Beim Ablegen wird der
Hintergrund gewählt (transparent mit hellem oder dunklem Text, dunkel, hell,
marineblau). Ein Tipp auf eine Zeile öffnet die App, der Knopf aktualisiert. Nach einem Neustart zeigen die Widgets
graue Platzhalter-Zeilen mit «Kurse werden geladen…» im hellen bzw. dunklen Systemton, ohne Internet «Keine
Verbindung · Stand 14:05» mit den letzten Kursen (`widget/WidgetStatusRule.kt`); kommt das Netz zurück,
aktualisieren sie sich selbst (`work/WidgetReconnectWorker.kt`).

**Massenabfrage** — Wer viele Paare derselben Börse beobachtet, bekommt sie in
einer einzigen Anfrage statt in Dutzenden. `Exchange` hat dafür einen optionalen
Haken (`bulkTickersNumOfRequests`, `getBulkTickersUrl`, `parseBulkTickers`);
umgesetzt ist er für alle Märkte ausser Coinbase (DexScreener: Pools derselben Chain gemeinsam über
`/latest/dex/pairs/{chain}/{a,b,…}`, höchstens 30 je Anfrage, Liquiditätsregel je Pool). Märkte
ohne diesen Haken werden weiter Paar für Paar abgefragt, und scheitert die
Massenabfrage, fällt der Refresher automatisch darauf zurück. Ab drei Paaren einer Börse greift der Sammelweg.
Wo die Börse eine Auswahl dokumentiert, werden nur die beobachteten Paare abgefragt
(Binance/Binance.US `symbols`, Upbit/Bithumb `exchanges`, Kraken `pair`, Bitfinex `symbols`;
lange Listen über `BulkPairChunks` auf mehrere URLs unter 2000 Zeichen verteilt);
scheitert die gefilterte Abfrage, folgt einmal die ungefilterte. Coinbase braucht je Paar
eine Anfrage (`/stats`: Kurs, Hoch/Tief, Volumen und Kurs vor 24 h).

**Pünktlich prüfen** (ab Werk aus) — `work/ExactCheckScheduler.kt` setzt mit `SCHEDULE_EXACT_ALARM`
einen exakten Wecker im gewählten Intervall der Hintergrund-Aktualisierung (15/30/60 Min., `ExactCheckReceiver` →
`BackgroundCheck`, gleiche Prüfung wie der Worker), auch im Doze; ohne Berechtigung zurück auf WorkManager. Hinweis bei
Akku-Einschränkung. «Letzte Prüfungen» (`CheckRunLog`, 20 Einträge in `noBackupFilesDir/check_runs.txt`, iOS gleich) zeigt
Auslöser und Ergebnis; zwei Prüfungen innerhalb von 5 Minuten werden zusammengelegt, und solange der Wecker gestellt ist,
läuft WorkManager nur, wenn seit der letzten vollen Prüfung ein Intervall vergangen ist (`BackgroundSchedule`).

**Aktualisierung** — Einstellungen › Kurs-Aktualisierung › «Wie aktuell?» (`domain/settings/UpdateMode.kt`):
**Sparsam** (Standard, empfohlen) aktualisiert bei geschlossener App über WorkManager im gewählten Intervall
(ab 15 Minuten, Android-Grenze, oft später) und bei jedem Öffnen; **Live, solange offen** holt zusätzlich in der
geöffneten Merkliste die Kurse der unterstützten Börsen live per WebSocket. Hintergrund und Widgets bleiben bei REST;
einen Vordergrunddienst gibt es nicht. Für regelmässigere Prüfungen bei ruhendem Gerät siehe «Pünktlich prüfen» oben.
Ein Boot-Receiver plant nach einem Neustart (`BOOT_COMPLETED`) und nach einem App-Update
(`MY_PACKAGE_REPLACED`) die Hintergrundaktualisierung und die exakten Prüfungen neu – nur diese geschützten
System-Broadcasts.

## Aufbau

```
marketdata/   Börsen, Handelspaare, Ticker-Parser
domain/       Fachlogik ohne Oberfläche und Datenbank-Zugriff: Regeln und
              Berechnungen (Alarme, Portfolio mit FIFO, Steuer-Engines und CSV-Import, «Warum bewegt
              es sich?», Frische, Fehlerarten, Markt-Kennzahlen, Futures-Quellen) und die Fachmodelle (ohne Room,
              ohne OkHttp); alles im Paket com.cryptochecker.app.domain.*
data/         Speicher und Netz: Room-Datenbanken mit DAOs, Tabellenzeilen und Verschlüsselung, Einstellungen,
              Repositories, Börsen-, Kerzen- und Adress-Datenquellen, Sicherung; alles im Paket
              com.cryptochecker.app.data.*; Schemas weiter unter app/schemas
app/
  data/       Was noch an Mitteilungen, Widgets oder der App-Sperre hängt (Sicherung, Logo-Abgleich, Charts im Blatt)
  activity/, alarm/, diagnostics/, gas/, refresh/
              Abläufe, die Speicher, Netz oder Mitteilungen brauchen: Aktivitäts-Prüfung (ActivityMonitor), Depeg-
              und Gas-Alarme, Diagnosebericht, DayReferences (24-h-/Tages-Bezüge) und die Hüllen der
              Aktualisierung (RefreshPortAdapters, PriceRefresherAdapters) um die Regeln aus domain/
  di/         Hilt-Module
  lock/       Portfolio-Sperre (Regeln)
  notification/ Kanäle und Benachrichtigungen
  settings/   Einstellungs-Hilfen der Oberfläche (Sprachen, Akzentfarbe, Kurzwerte, Rückfragen, Einstellungs-Suche);
              DataStore liegt in data/settings, «Wie aktuell?» in domain/settings
  overlay/    Preis-Overlay über anderen Apps
  tts/        Sprachausgabe
  util/       Hilfen (Zahlen-Eingabe, TalkBack-Texte, Chart-Beschreibungen)
  wearable/   Abgleich mit der Uhr (Wear-Daten-Schicht)
  work/       WorkManager, exakte Prüfungen («Pünktlich prüfen»), Boot-Receiver
  widget/     Widget-Provider und Einrichtung; je Widget-Art ein Renderer
              (ListWidgetRenderer, SingleWidgetRenderer, PortfolioWidgetRenderer,
              PulseWidgetRenderer), WidgetUpdater entscheidet, was neu muss;
              Zeilen der Liste aus ListWidgetRows (ab Android 12 direkt im Widget)
  ui/         Compose: Merkliste, Börsen, Alarme, Markt, Portfolio, Einstellungen
wear/         Wear-OS-App mit Komplikationen (Kurs, Portfolio-Wert)
baselineprofile/  Generator für das gemessene Baseline Profile (nur Werkzeug)
```

Die Schichtgrenzen prüft `LayerBoundaryTest` am Quelltext; per Hilt erzeugte Klassen bekommen ihre Dispatcher
über `@IoDispatcher`/`@DefaultDispatcher` (`di/DispatcherModule.kt`). Einzelheiten: `DEVELOPMENT.md` im
Repository-Hauptordner, Abschnitt 20.

Grosse Bildschirme sind nach Aufgaben aufgeteilt, mit denselben Dateinamen wie in iOS:
Merkliste (`WatchlistScreen` setzt zusammen; `WatchlistHeader`, `WatchlistStatus`,
`WatchlistSearch`, `WatchlistList`, `WatchlistRow`, `WatchlistSheets`, `WatchlistJump`,
`WatchlistBanner` …), Markt-Karten (`Exchange…Card`, `MarketCycleChart`, `MarketCardParts`),
Einstellungen (`SettingsScreen`, `SettingsComponents`, `SettingsPickers`, je Gruppe
`Settings…Pages`), Alarme (`AlarmsScreen`, `AlarmEditorSheet`, `AlarmAdvancedOptions`),
«Paar hinzufügen» (`ExplorerScreen`, `ExplorerSearchUi`, `ExplorerSteps`, `ExplorerBulk`,
`ExplorerDex`), Paarseite (`PairScreen`, `PairPageContent`, `PairHeader`, `PairActions`, `PairState`,
`WatchSheetFutures`, `WatchSheetSpot`, `WatchSheetChartView`, `WatchSheetChartCanvas`), Portfolio
(`PortfolioScreen`, `PortfolioTotalCard`, `PortfolioRows`, `PortfolioHistoryCard`,
`PortfolioHistoryChart`). ViewModels bleiben je Bildschirm eine Klasse.

## Bauen

```bash
./gradlew testDebugUnitTest
./gradlew :app:assembleDebug
```

**JDK 17** (Android Studio bringt es mit), compileSdk 37, targetSdk 36, minSdk 26.
Beim ersten Öffnen lädt Android Studio Gradle 9.9.1 und, falls nötig, die
SDK-Plattform 37.

| Baustein | Version |
|---|---|
| Android Gradle Plugin | 9.5.1 (Kotlin eingebaut, ohne `org.jetbrains.kotlin.android`) |
| Gradle | 9.9.1 |
| Kotlin | 2.4.21 (K2, Compose-Compiler-Plugin, kotlinx-serialization) |
| KSP | 2.3.13 (ersetzt kapt) |
| Compose BOM | 2026.09.00 |
| Hilt | 2.60.1 |
| androidx.hilt | 1.4.0 |
| core-ktx / AppCompat / Activity | 1.19.1 / 1.8.0 / 1.13.0 |
| Coroutines | 1.11.0 |
| Room | 2.8.5 |
| WorkManager | 2.12.0 |
| OkHttp | 5.5.1 |
| SQLCipher | 4.20.0 |
| DataStore / Lifecycle / Navigation | 1.3.0 / 2.11.0 / 2.10.2 |
| kotlinx-serialization | 1.12.0 |
| Wear Compose / play-services-wearable | 1.7.1 / 20.0.1 |

Alle Versionen, Bibliotheken und Plugins stehen im Versionskatalog `gradle/libs.versions.toml`;
die Buildskripte sind in Kotlin DSL (`settings.gradle.kts`, `build.gradle.kts` und je Modul `build.gradle.kts`:
`app`, `domain`, `data`, `marketdata`, `wear`, `baselineprofile`).

Hinweise zu AGP 9: Kotlin kommt über den Klassenpfad im Wurzel-`build.gradle.kts`
(`kotlin-gradle-plugin`), `jvmTarget` folgt `compileOptions` (Java 17). R8
packt Klassen ab AGP 9.1 standardmässig in andere Pakete um; die Börsenklassen
sind über `marketdata/consumer-rules.pro` und `proguard-rules.pro` geschützt. Ihr Schlüssel
(`Exchange.key`) ist ein fester Text je Börse (gleich dem früheren Klassennamen).

**Room-Schemas – nach dem nächsten Build `app/schemas/…` committen:** Beide Datenbanken stehen bei Version 1,
ohne Migration; im Repository liegt je das Schema 1 (`app/schemas/com.cryptochecker.app.data.local.AppDatabase/1.json`,
`app/schemas/com.cryptochecker.app.data.portfolio.PortfolioDatabase/1.json`).
`exportSchema = true` und `room { schemaDirectory("$rootDir/app/schemas") }` (in `data/build.gradle.kts`, wo
beide Datenbanken liegen) sind gesetzt;
der Build (z. B. `./gradlew :app:assembleDebug`) schreibt das Schema der aktuellen
Datenbank-Version (`AppDatabase.VERSION`, `PortfolioDatabase.VERSION`). Diese JSON-Dateien mitcommitten und nie von
Hand schreiben. Eine künftige Schema-Änderung braucht eine neue Version und eine Migration: Schema, Migration und
Migrationstest mit Daten (`RoomSchemaRulesTest`).

## Umfang

* 82 Märkte: 78 auf 69 Börsen (teils mit Spot und Futures), dazu DexScreener für DEX-Token (GeckoTerminal als
  Reserve) und Referenzkurse für Devisen von EZB (täglich), Nationalbank der Ukraine (täglich) und Polnischer
  Nationalbank (wöchentlich) — jede aktuelle Währung aus genau einer Quelle, gegen USD und EUR. Unter «Devisen» dazu
  echte Fiat-Paare von Kraken und Bitstamp live (mit Alarmen). Die vollständige Liste in Anzeigereihenfolge steht in
  `marketdata/src/main/java/com/cryptochecker/marketdata/config/ExchangesConfig.kt`.
  Xcalibra ist seit Januar 2025 geschlossen.
* Belegt abgeschaltete Dienste entfernt: die alte Poloniex-Legacy-API (28.02.2023; Poloniex selbst läuft über die
  API v3, `Poloniex.kt`),
  Uniswap V2 (The-Graph-Hosted-Service 12.06.2024), Bitpanda Pro
  (Umbenennung zu One Trading, alte Domain 04.01.2024).
* App-Icon: weisse Glocke mit steigender Kurslinie auf orangem Verlauf (Markenfarbe), als adaptives Vektor-Icon mit
  Varianten je Akzentfarbe (`res/drawable/ic_launcher_*`); Store-Icon
  `docs/store/icon_512.png`.
* Sammelaktion «Alle …-Paare dieser Börse» mit wählbarer Gegenwährung
  (`domain/model/ExchangePairsInfo.kt`,
  `ui/features/explorer/ExplorerViewModel.kt`), mit Schnellfiltern Gewinner / Verlierer / Volumen
  (eine Sammelabfrage der Börse beim Öffnen, höchstens einmal pro Minute).
* Künstliche Ladeverzögerung beim Start der Börsenliste entfernt.
* Oberfläche englisch, Übersetzungen in `values-*` (75 Sprachen insgesamt,
  Portugiesisch als `values-pt` für Portugal und `values-pt-rBR` für Brasilien, Chinesisch als `values-zh`
  vereinfacht und `values-b+zh+Hant` traditionell, Kantonesisch als `values-b+yue`, Filipino als `values-fil`,
  Serbisch kyrillisch als `values-sr` und lateinisch als `values-b+sr+Latn` – erzeugt aus `values-sr` mit
  `tools/sr_latn.py`, nie von Hand ändern);
  die deutsche in `values-de` ist vollständig. Jeder Ordner hat dieselben Bereichsdateien
  `strings_<bereich>.xml` (common, watchlist, explorer, onboarding, pair_sheet, why, market,
  indicators, alarms, alarm_log, delivery, notifications, refresh, portfolio, portfolio_cost,
  portfolio_hints, addresses, csv, tax, backup, settings, widgets, a11y, a11y_charts); ein Schlüssel steht in jeder Sprache in derselben Datei, nach Präfix gruppiert.

## Dokumentation

* Datenschutzerklärung (Android und iOS, DE/EN): [`docs/privacy/index.html`](docs/privacy/index.html)
* Datenschutz/Support online: `https://r1adbe.github.io/cryptoChecker/privacy/` bzw.
  `https://r1adbe.github.io/cryptoChecker/` (Quelle: `docs/`)
* Deutsch in Schweizer Schreibweise (ss, «»); keine Spenden in der App (siehe «Unterstützen»)
* Veröffentlichung bei Google Play: Store-Texte und Versionshinweise liegen unter `fastlane/metadata/android/`; Signieren siehe `keystore.properties.example`

## Unterstützen

Die Spendenadressen stehen nur auf der Seite `SUPPORT.md` des Repositorys auf
GitHub (github.com/r1adBE/cryptoChecker), nicht in der App, nicht auf der
Startseite und nicht in den Store-Texten (Apple 3.1.1, Google Play Billing).

## Öffentliches Repository

Der komplette Quellcode ist öffentlich unter MIT:
<https://github.com/r1adBE/cryptoChecker> (dieser Ordner als `android/`, die
iOS-App als `ios/`, dazu Datenschutzseite unter `docs/`). Anleitung für neue
Börsen: `DEVELOPMENT.md` im Repository-Hauptordner.
GitHub Actions bauen und testen die App bei jeder Änderung. Die instrumentierten Tests auf dem Emulator
(Workflow «Android device tests», `.github/workflows/android-device-tests.yml`) laufen bei jedem Push auf `main`,
der die Android-App betrifft, bei jedem Release-Tag und von Hand; bei Pull Requests nur die Datenbank- bzw.
Release-Smoke-Tests (`migration-tests`, `release-smoke`), wenn Datenbank bzw. Release-Build betroffen sind. Schlüssel,
`keystore.properties` und `local.properties` liegen nie im Repository.

## Lizenz

MIT, siehe `LICENSE`. Namen, Logos und Marken von Coins, Token, Aktien, Börsen und anderen Dritten sowie
Marktdaten von Drittanbietern (z. B. CoinGecko, Binance, alternative.me, Coin Metrics) gehören nicht zum Code
und fallen nicht unter die MIT-Lizenz.

Schrift für Beträge: **Rubik** (Copyright 2015 The Rubik Project Authors,
SIL Open Font License 1.1), aus google/fonts `ofl/rubik`, als Schnitte
Regular/Medium/SemiBold aus der variablen Schrift erzeugt und auf Latein,
Kyrillisch, Hebräisch und Satzzeichen beschränkt (`app/src/main/res/font/rubik_*.ttf`,
zusammen rund 360 KB). Lizenztext: `app/src/main/assets/licenses/Rubik-OFL.txt`.
