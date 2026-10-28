# Crypto Checker für iOS

**Crypto Checker ist ein Krypto-Checker: Kurse direkt von deiner Börse; Hinweise, wenn beim Nachsehen eine
Marke erreicht ist – ohne Konto.** Dazu Marktüberblick und als Zusatz «Was bewegt sich mit?» (was sich gleichzeitig
bewegt – keine Ursache) – ohne Prognosen, keine Anlageberatung.

Native SwiftUI-Version der Android-App Crypto Checker (Version 16.2.2, Build 167 – die Build-Nummer steigt mit jedem Stand um eins).
Mindestens iOS 17. Die App enthält eine Widget-Erweiterung (WidgetKit) und eine Apple-Watch-App (watchOS 10, `Watch/`,
Komplikation in `WatchWidgets/`, gemeinsame Listenwahl in `WatchCore/`).

Der Quellcode ist öffentlich (MIT): <https://github.com/r1adBE/cryptoChecker>, Ordner `ios/`
(die Android-App liegt daneben in `android/`). Ein Simulator-Build ohne Signatur lässt sich dort unter
*Actions › iOS › Run workflow* starten; Kontaktdaten aus `fastlane/metadata/review_information` werden nie hochgeladen.
Namen, Logos und Marken Dritter (Coins, Token, Aktien, Börsen) und Marktdaten von Drittanbietern (z. B. CoinGecko)
gehören nicht zum Code und fallen nicht unter die MIT-Lizenz (siehe `LICENSE` im Repository).

- Datenschutz/Support: `https://r1adbe.github.io/cryptoChecker/privacy/` bzw.
  `https://r1adbe.github.io/cryptoChecker/` (`Shared/Util/AppLinks.swift`, `fastlane/metadata`).
- **Keine Spenden in der App** (App-Store-Richtlinie 3.1.1); Hinweise nur in `SUPPORT.md` im Repository-Hauptordner
  auf GitHub.
- Deutsch in Schweizer Schreibweise (ss, «»).

## Stand 16.2.2

Gleicher Funktionsumfang wie Android 16.2.2, u. a.:

- **Börsenauswahl in Abschnitten**: Spot · Futures · DEX-Pools · Forex (Referenzkurs), Favoriten oben, Suche über alle
  Abschnitte; innerhalb eines Abschnitts nach Handelsvolumen am Installationstag (einmal aus den öffentlichen
  Börsenlisten von CoinGecko), ohne diese Angabe alphabetisch. 56 Märkte: 54 auf 45 Börsen, dazu DexScreener für
  DEX-Token (GeckoTerminal als Reserve) und EZB-Referenzkurse für Devisen (täglich, nie live, ohne Alarme).
- **Einstellungen ohne «Erweitert»** (Build 132): Hauptseite Allgemein (Sprache – öffnet die iOS-App Einstellungen –,
  Währung, Merkliste, Portfolio, Alarme, Kurs-Aktualisierung) · Darstellung (Hell / Dunkel, Akzentfarbe, Kursfarben,
  Coin-Logos, Widgets) · Mehr (Sicherung, Über die App) · «App zurücksetzen». «Token & TradFi» auf «Merkliste»,
  Markt-Meldungen und Sprachausgabe auf «Alarme», Entwickleroptionen (nur Debug-Builds) auf «Über die App»; die Suche
  findet alles am neuen Ort (`App/Features/Settings/SettingsScreen.swift`).
- **Steuer-Arbeitsunterlage als eigene Seite** (Build 129): im Portfolio-Menü getrennt vom Stichtag-Export, mit
  Vorlage, Stand der Regeln, den vier Exporten und der Jahresübersicht; Exporte lassen sich abbrechen.
- **Leistung** (Builds 122–131): CSV-Import grosser Dateien ohne Blockieren der App, schnellerer Start (Merkliste wird im
  Hintergrund gelesen und entschlüsselt), FIFO, «7 T»/«1 J» und Steuer-Export auch mit vielen Transaktionen schnell.
- **Barrierefreiheit** (Builds 129–131): Widgets und Live-Aktivität wachsen mit grösserer Schrift bis 1,3×, Charts mit
  Audio Graph und Punkt-für-Punkt-Wischen für VoiceOver, Titel im Grosser-Inhalt-Viewer, blockierende
  Barrierefreiheits-Prüfung in der CI.
- **CSV-Import** aus Relai, Swissquote, Kraken, Bitpanda, Binance und Coinbase (dazu eine allgemeine Zuordnung;
  `Shared/Portfolio/CsvImport*.swift`).

Alle Builds: `CHANGELOG.md` im Repository-Hauptordner.

## Ordnerstruktur

| Ordner | Inhalt |
|---|---|
| `Shared/` | Code und Ressourcen für **beide** Targets (App und Widgets), u. a. `Resources/Localizable.xcstrings` |
| `App/` | nur App: Oberfläche, Hintergrund-Aktualisierung, `Info.plist`, Entitlements, `Resources/Assets.xcassets` (App-Icons, Logos) |
| `Widgets/` | nur Widget-Erweiterung: Widgets, `Info.plist`, Entitlements, eigener Asset-Katalog |
| `Tests/` | Unit-Tests (Testziel `CryptoCheckerTests`, ⌘U bzw. `xcodebuild test -scheme CryptoChecker`); `Tests/Parity/` = gemeinsame Testfälle, vom Generator aus `android/testdata/parity` kopiert; `*PerformanceTests` = Zeitmessungen, zusätzlich im Release-Build per Workflow `ios-performance.yml` (`DEVELOPMENT.md` im Repository-Hauptordner, «iOS performance tests in Release») |
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
(sonst `--android-res PFAD`).

```sh
python3 tools/convert_strings.py   # strings_*.xml (31 Sprachen, inkl. pt-BR) + tools/ios_extra_strings.json -> Localizable.xcstrings, InfoPlist.xcstrings
python3 tools/gen_assets.py        # VectorDrawables -> App-Icons, Logos, Farben (beide Asset-Kataloge)
python3 tools/gen_xcodeproj.py     # CryptoChecker.xcodeproj neu erzeugen (findet neue .swift-Dateien selbst, kopiert die gemeinsamen Testfälle nach Tests/Parity)
```

Neue Swift-Dateien einfach in `Shared/`, `App/`, `Widgets/` oder `Tests/` ablegen und
`tools/gen_xcodeproj.py` erneut ausführen. iOS-eigene Texte gehören in
`tools/ios_extra_strings.json` (alle 31 Sprachen, inkl. `pt-BR`), danach `convert_strings.py` laufen lassen.

## Swift 6

Alle Ziele (App, Widgets, Unit- und UI-Tests) bauen im Swift-6-Sprachmodus (`SWIFT_VERSION = 6.0`,
`SWIFT_STRICT_CONCURRENCY = complete`, in `tools/gen_xcodeproj.py` und `project.yml`). Concurrency-Befunde sind damit
Fehler, nicht mehr Warnungen; dazu prüft Swift 6 die Isolation auch zur Laufzeit.

Regeln für neuen Code:

- Kein neues `nonisolated(unsafe)` ohne Sperre. Globale Einstellungen und Merker gehören in
  `LockedBox` (`Shared/Util/Theme.swift`), nicht in ein nacktes `static var`. Jede verbleibende Stelle trägt
  einen Kommentar, welche Sperre sie schützt.
- Jedes `@unchecked Sendable` trägt auf derselben Zeile einen Kommentar, welche Sperre den Zustand schützt (auch in
  den Tests).
- Kein `@MainActor` nachträglich auf Typen, die aus Hintergrund-Aufgaben, Widgets oder UIKit-Farb-Closures
  gelesen werden — dort lieber `LockedBox`.
- Synchrone Rückrufe des Systems, die nicht garantiert auf dem Haupt-Thread kommen (`BGTaskScheduler`,
  `NWPathMonitor`, `UIColor { traits in … }`, URLSession-Rückrufe, `NotificationCenter` mit `queue: nil`), nur in
  nicht isolierten Typen anlegen oder die Closure ausdrücklich `@Sendable` machen; SwiftUI-`onReceive` mit
  Mitteilungen aus beliebigen Threads über `.receive(on: DispatchQueue.main)`. `MainActor.assumeIsolated` nur, wo
  Apple den Haupt-Thread zusagt (z. B. Ablauf von `beginBackgroundTask`).
- Tests, die `@MainActor`-Code synchron nutzen (`AppData.shared`, SwiftUI-Views), tragen `@MainActor` an Klasse
  oder Methode (`XCTestCase` ist nicht isoliert).
- Keine nackten Regex-Literale `/…/` (im Swift-6-Modus möglich): `#/…/#` oder `Regex(…)`, damit die Quelltext-Wächter
  (`ios_release_audit.py`, `WatchlistConfirmCallGuard`) exakt bleiben.

Die CI (Workflow «iOS», `.github/workflows/ios.yml`) baut die App im Swift-6-Modus und führt Unit- und UI-Tests bei
jedem Pull Request und jedem Push auf `main`, der die iOS-App betrifft, aus; Isolationsfehler zur Laufzeit fallen dort auf. Die
`@unchecked Sendable`-Stellen (Obergrenze in `swift_escape_hatches.py`, derzeit 103 – davon die meisten die
Börsen-Adapter ohne veränderlichen Zustand) sind kommentiert bzw. mit einer Sperre geschützt – das kann der Compiler
nicht nachprüfen; ein späterer Ersatz ist `OSAllocatedUnfairLock` oder `Mutex` (ab iOS 18).

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

- **Keine dauerhafte Kurs-Mitteilung.** Android kann den Kurs fest in der Statusleiste zeigen (ab Werk
  aus); unter iOS
  erscheint bei jeder Aktualisierung eine normale Mitteilung, die sich wegwischen lässt. Die Option
  «Dauerhaft in den Benachrichtigungen» hat keine Entsprechung.
- **Hintergrund-Aktualisierung bestimmt iOS.** Das eingestellte Intervall ist nur ein frühester
  Zeitpunkt; iOS entscheidet je nach Nutzung, Akku und Netz, wann tatsächlich aktualisiert wird –
  oft deutlich später, nach einem erzwungenen Beenden der App gar nicht mehr.
- **Vibration:** Kein eigener Schalter – iOS steuert die Vibration von Mitteilungen ausschliesslich über
  die Systemeinstellungen. Der Wert aus Android-Sicherungen bleibt erhalten.
- **Sprache** wird in der iOS-App *Einstellungen › Crypto Checker › Sprache* gewählt, nicht in der App.
- **App-Icon:** Wechselt nur, wenn du in den Einstellungen eine Akzentfarbe wählst; iOS bestätigt das
  mit einem kurzen Hinweis. Standard und Dunkel zeigen das dunkle Icon (weisses Symbol);
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
erscheint eine klare Meldung (kein Rückfall auf schwächere Parameter). v3 ist das einzige verschlüsselte Format; eine
Datei mit anderer Version lässt sich nicht öffnen. Gemeinsame Prüfwerte: `Tests/Parity/backup_crypto.json`. Ein
neues Passwort braucht mindestens 12 Zeichen; ein leicht zu erratendes (nur Ziffern, ein wiederholtes Zeichen, häufige
Folge wie «12345678» oder «passwort») ist für neue Sicherungen gesperrt; beim Öffnen gilt keine Mindestlänge. Eine
Sicherung ohne Passwort zeigt beim Einspielen eine Warnung (nennt die vier geschützten
Einstellungen mit ihren Namen) und senkt nie den Schutz (`BackupRestorePolicy.swift`, gleich wie Android): Sie schaltet die
Portfolio-Sperre und «Beträge verbergen» nie aus, «Portfolio in Systemsicherung» nie ein und setzt, ersetzt oder löscht
den eigenen Bitcoin-Server nie; eine beibehaltene Sperre bzw. ein beibehaltener Server steht in der Meldung danach.
Ver- und Entschlüsseln laufen im Hintergrund, «Weiter» bleibt während der Prüfung gesperrt.
