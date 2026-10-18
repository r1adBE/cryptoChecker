import Foundation

/// Momentaufnahme aller Einstellungen — wie `AppSettings.kt`.
///
/// Unterschiede zu Android (iOS erlaubt keinen Dauerdienst):
/// - `backgroundIntervalMinutes`: iOS führt die Hintergrund-Aktualisierung frühestens nach dem
///   Intervall aus, oft später.
/// - `updateMode` kennt kein «Immer kurz» (`UpdateMode.IMMER_KURZ` wird zu `LIVE_OFFEN`);
///   `liveIntervalSeconds` bleibt nur für Sicherungen erhalten.
/// - `ongoingNotifications` hat auf iOS keine Wirkung, bleibt aber für Sicherungen erhalten.
struct AppSettings: Codable, Equatable, Sendable {
    /// «Wie aktuell?»: Sparsam oder Live, solange offen (Standard) — wie Android (`UpdateMode`).
    /// Ersetzt die früheren Schalter «backgroundUpdates», «liveService» und «liveWebSocket»; die
    /// Hintergrund-Aktualisierung läuft in jeder Wahl.
    var updateMode: UpdateMode = .default
    var backgroundIntervalMinutes: Int = 15
    var liveIntervalSeconds: Int = 60
    var priceNotifications: Bool = true
    /// Ohne Wirkung auf iOS; Standard wie Android (ab Build 42 aus), gespeicherte Werte bleiben.
    var ongoingNotifications: Bool = false
    /// Mindestveränderung in Prozent für eine Kurs-Mitteilung (0 = jede Aktualisierung).
    var notificationChangePercent: Double = 5.0
    var ttsEnabled: Bool = false
    var ttsAlarmsOnly: Bool = true
    /// Sprechgeschwindigkeit (0.5 – 2.0), 1 = normal.
    var ttsSpeechRate: Double = 1.0
    /// Ruhezeit, bevor derselbe Alarm erneut auslöst (Minuten).
    var alarmCooldownMinutes: Int = 0
    var includeRollingFutures: Bool = false
    /// «Token & TradFi»: Was die Paar-Auswahl zeigt — nur Token (Krypto, Standard), nur TradFi
    /// (Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO) oder beides. Gemerkte Paare bleiben in
    /// der Merkliste. Ersetzt den früheren Schalter «includeTradFiFutures» (siehe `AssetScope`).
    var assetScope: AssetScope = .default
    /// «inkl. Alpha-Tokens» (bei «Token & TradFi»): Alpha-Tokens (`AssetKind.ALPHA`) in der
    /// Paar-Auswahl zeigen. Ab Build 43 für neue Installationen aus; gespeicherte Einstellungen ohne
    /// den Wert behalten den bisherigen Standard an (`legacyIncludeAlphaTokens`). Gemerkte Paare
    /// bleiben immer — wie Android `includeAlphaTokens`.
    var includeAlphaTokens: Bool = false
    var accentColor: AccentColor = .default
    /// nil = wie das System.
    var darkMode: Bool? = nil
    var showHttpLog: Bool = false
    var developerUnlocked: Bool = false
    /// Fear & Greed: melden unter/über diesem Wert (0 = aus).
    var fearGreedBelow: Int = 0
    var fearGreedAbove: Int = 0
    /// Eigene Grenzen der Bitcoin-Kennzahlen (`IndicatorAlertKind`, Schlüssel = Name), 0/fehlt = aus — wie Android.
    var indicatorAlerts: [String: Int] = [:]
    /// Gas-Alarm Ethereum in Zehntel-gwei (0 = aus) — wie `gasAlertEthTenths`.
    var gasAlertEthTenths: Int = 0
    /// Gas-Alarm Bitcoin in sat/vB (0 = aus).
    var gasAlertBtc: Int = 0
    /// USDT-Depeg melden (`DepegAlarm`): ab Werk aus (zusätzliche Abfragen nur, wenn eingeschaltet).
    /// Bewertungen rechnen mit dem gemessenen USDT/USD-Marktkurs (gemessen auch ohne Alarm, sobald ein Portfolio da ist) — wie `depegAlarm` (Android).
    var depegAlarm: Bool = false
    /// Schwelle des Depeg-Alarms in Zehntel-Prozent (3 / 5 / 10 / 20), Standard 0.5 %.
    var depegThresholdTenths: Int = DepegAlarm.defaultThresholdTenths
    /// Alarmton: Name eines mitgelieferten Tons (`AlarmSounds`), nil = Standardton.
    var alarmSound: String? = nil
    /// «Alarm-Signal» (Schlüssel wie Android). iOS: nur `SYSTEM` oder `SILENT`, siehe `AlarmSignal`.
    var alarmSignal: AlarmSignal = .default
    var gestureHintSeen: Bool = false
    /// Die Karte «Ersten Alarm setzen» über der Merkliste wurde geschlossen (wie Android).
    var firstAlarmCardDismissed: Bool = false
    /// Verschiedene Tage, an denen die App offen war (`UsageDays`): ab 3 zeigt die Merkliste auch ohne Alarm Puls und
    /// Hinweise (`WatchlistOnboarding`). Gleicher Schlüssel wie Android; nur auf diesem Gerät, nicht in der Sicherung.
    var usageDays: Int = 0
    /// Lokaler Epoch-Tag des letzten gezählten Öffnens (`usageDays`); nil = noch nie.
    var usageLastDay: Int? = nil
    var aboutSeen: Bool = false
    /// Gewählte Gruppe der Merkliste; nil = «Alle».
    var watchlistGroup: String? = nil
    /// Ungewöhnliche Aktivität (Bewegung, Volumen, Futures) als Mitteilung melden.
    var activityAlerts: Bool = false
    /// Empfindlichkeit von «Ungewöhnliche Aktivität» (Karte und Mitteilungen); Standard = bisherige Schwellen.
    var activitySensitivity: ActivitySensitivity = .NORMAL
    /// Morgen-Mitteilung (08:00) an Tagen mit wichtigen US-Wirtschaftsdaten.
    var macroNotifications: Bool = false
    /// Optionaler Bereich «Portfolio» (eigener Tab vor den Optionen). Ab Build 44 für neue Installationen
    /// ab Werk aus; gespeicherte Einstellungen ohne den Wert behalten den bisherigen Standard an
    /// (`legacyPortfolioEnabled`) — wie Android.
    var portfolioEnabled: Bool = false
    /// Merker der einmaligen Umstellung «Portfolio ab Werk an». iOS speichert alle Einstellungen als
    /// Ganzes — frühere Versionen schrieben dabei auch den damaligen Standard «aus» mit, ohne dass der
    /// Nutzer umgeschaltet hätte; ein gespeichertes «aus» ist darum nicht als Wahl erkennbar. Fehlt der
    /// Merker (Einstellungen von vor der Umstellung), gilt «an»; danach bleibt jede Wahl stehen.
    /// Android braucht das nicht: Dort wird der Schlüssel nur beim Umschalten geschrieben.
    var portfolioDefaultV2: Bool = true
    /// Umrechnungswährung: Zielwährung der Umrechnungszeile im Portfolio und der
    /// umgerechneten Kurse in der Merkliste (USD = keine Umrechnung im Portfolio).
    var portfolioCurrency: String = AppSettings.defaultCurrency()
    /// Kurse in der Merkliste zusätzlich in der Umrechnungswährung zeigen («≈ 61’234 CHF»). Ab Build 43
    /// für neue Installationen an; gespeicherte Einstellungen ohne den Wert: bisher aus (`legacyShowConverted`).
    var showConverted: Bool = true
    /// Kursfarben steigend/fallend: Grün/Rot oder Blau/Orange (Rot-Grün-Sehschwäche).
    var priceColorScheme: PriceColorScheme = .default
    /// Merker «Ruhig ist Standard» (Build 46): fehlt er, ist ein gespeichertes «Frisch» (GREEN_RED) der
    /// frühere, mitgespeicherte Standard und keine erkennbare Wahl → einmalig «Ruhig». Wie Android: dort
    /// steht ohne eigene Wahl gar nichts gespeichert.
    var priceColorDefaultV2: Bool = true
    /// Mini-Chart (24-Stunden-Verlauf) in den Zeilen der Merkliste.
    var watchlistSparkline: Bool = false
    /// Name unter dem Paar in der Merkliste («Bitcoin», «NVIDIA»). Ab Build 43 für neue Installationen an;
    /// gespeicherte Einstellungen ohne den Wert: bisher aus (`legacyWatchlistNames`).
    var watchlistNames: Bool = true
    /// Zeitraum der %-Änderung («24h», «heute», «letzter Stand») klein neben der Änderung, Puls,
    /// Einzel-Widget und Live-Aktivität; ab Werk aus. VoiceOver nennt ihn immer.
    var showChangePeriod: Bool = false
    /// «Zahl am App-Symbol»: neue Alarme und Marktmeldungen zählen am Kennzeichen, beim Öffnen
    /// der App wieder 0 (`Notifier.badge`); ab Werk an — wie `appIconBadge` (Android).
    var appIconBadge: Bool = true
    /// Karte «Hier passiert gerade etwas» über der Merkliste. Nur die Anzeige: Mitteilungen dazu
    /// stellt man unter Markt-Meldungen ein, das ⚡ an den Paaren bleibt.
    var watchlistActivityCard: Bool = true
    /// Echte Coin-Logos (alle auf einmal von CoinGecko geladen, auf dem Gerät gespeichert) in
    /// Merkliste, Aktionsblatt, Markt und «Paar hinzufügen». Aus (Standard): Initialen.
    var coinLogos: Bool = true
    /// Coin-Logos im Portfolio (Positionen, Coin-Details, Auswertungen) — ab Werk an.
    var portfolioCoinLogos: Bool = true
    /// Coin-Logos in den Widgets «Merkliste» und «Einzelner Coin» — ab Werk aus.
    var widgetCoinLogos: Bool = false
    /// «Basis der %-Änderung»: rollende 24 Stunden (Standard), seit 00:00 UTC oder seit 00:00
    /// Ortszeit — für Änderung, Puls, Aktionsblatt, Widgets und Live Activity; Alarme unabhängig davon.
    var changeBasis: ChangeBasis = .default
    /// Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte (zusätzlich zu
    /// «Kontrast erhöhen» des Systems).
    var highContrast: Bool = false
    /// «Farben tauschen»: Rot = steigend, Grün = fallend (bzw. Orange/Blau). Ohne
    /// gespeicherten Wert gilt der Standard der Geräte-Region.
    var priceColorsInverted: Bool = AppSettings.defaultPriceColorsInverted()
    /// Nachtruhe: Alarme warten in dieser Zeit (siehe `QuietHours`). Zeiten in
    /// Minuten seit Mitternacht, Standard 23:00–07:00.
    var quietHoursEnabled: Bool = false
    var quietHoursStart: Int = QuietHours.defaultStart
    var quietHoursEnd: Int = QuietHours.defaultEnd
    /// Portfolio-Sperre (Schlüssel «appLock» wie bisher): Portfolio-Tab, Unterseiten, Sicherung mit
    /// Portfolio-Daten und Portfolio-Widget erst nach Face ID / Touch ID / Code — beim Start und
    /// nach mehr als einer Minute im Hintergrund. Die übrige App ist nie gesperrt.
    var appLock: Bool = false
    /// «Beträge verbergen»: Portfolio-Beträge und -Werte als «•••» (Prozente bleiben) — im Portfolio,
    /// im Portfolio-Widget (liest die Einstellung aus der App Group) und in Portfolio-Alarmen.
    /// Gleicher Schlüssel wie Android («hidePortfolioAmounts»), auch in der Sicherung.
    var hidePortfolioAmounts: Bool = false
    /// «Portfolio in Systemsicherung»: an = `portfolio.json` kommt in iCloud-/Geräte-Backups, aus
    /// (Standard) = mit `isExcludedFromBackup` ausgenommen (`PortfolioStore.applyBackupPolicy`). Gleicher
    /// Schlüssel wie Android («portfolioSystemBackup»), auch in der Sicherung.
    var portfolioSystemBackup: Bool = false
    /// Bestätigung nach dem ersten gespeicherten Alarm schon gezeigt (oder es gab
    /// schon Alarme). Gleicher Schlüssel wie Android; nicht in der Sicherung.
    var firstAlarmShown: Bool = false
    /// Hinweis «Wann Alarme geprüft werden» (`AlarmTimingHint`) wurde einmal gezeigt. Reiner Merker der
    /// Oberfläche: nur auf diesem Gerät, nicht in der Sicherung — wie Android.
    var alarmTimingHintShown: Bool = false
    /// Erstes Paar überhaupt hinzugefügt (Start-Tipp oder Hinzufügen-Tab) — danach
    /// kein «Erst-Hinzufügen»-Moment mehr. Gleicher Schlüssel wie Android; nicht in der Sicherung.
    var firstPairAdded: Bool = false
    /// Chart im Aktionsblatt eines Paars als Linie statt Kerzen (zuletzt gewählt, für alle Paare
    /// gleich), wie `sheetChartLine` in Android; nicht in der Sicherung.
    var sheetChartLine: Bool = false
    /// Karte «Wertverlauf» im Portfolio aufgeklappt (Standard zu), wie Android; nicht in der Sicherung.
    var portfolioHistoryExpanded: Bool = false
    /// Zuletzt gewählter Zeitraum des Wertverlaufs, wie Android; nicht in der Sicherung.
    var portfolioHistoryRange: PortfolioHistoryRange = .month
    /// Darstellung des Wertverlaufs bei einer Umrechnungswährung («CHF | USDT | Vergleich», Standard
    /// in der Währung), wie Android; nicht in der Sicherung.
    var portfolioHistoryView: PortfolioHistoryView = .currency
    /// Wertverlauf als Kerzen statt Linie (Kerzen | Linie wie im Aktionsblatt, Standard Linie), wie
    /// Android («portfolioHistoryCandles»); nicht in der Sicherung.
    var portfolioHistoryCandles: Bool = false
    /// Wertverlauf zeigt «Gewinn/Verlust» statt «Wert» (Chips über dem Chart, Standard Wert), wie Android
    /// («portfolioHistoryPnl»); nicht in der Sicherung.
    var portfolioHistoryPnl: Bool = false
    /// Einstandsmethode des Portfolios (FIFO | Ø): Einstand, Ø, «Seit Kauf», realisiert und Steuer-Export. Standard
    /// FIFO für alle (neue und bestehende Installationen), wie Android («costMethod»); in der App-Sicherung
    /// (ältere Sicherungen ohne den Schlüssel → FIFO).
    var costMethod: CostMethod = .defaultMethod
    /// Steuer-Vorlage der Arbeitsunterlage (Neutral | DE | AT | CH | UK | US | CA | AU | FR | JP), Standard Neutral — wie
    /// Android («tax_template»). Die Region des Geräts wird nur vorgeschlagen, nie still übernommen. In der App-Sicherung
    /// («taxTemplate»; ältere Sicherungen ohne den Schlüssel lassen die Einstellung stehen).
    var taxTemplate: TaxTemplate = .defaultTemplate
    /// Hinweis «Beta, vereinfachte Regeln …» zu den Länder-Vorlagen einmal mit OK bestätigt — wie Android
    /// («tax_template_confirmed»); nur auf diesem Gerät, nicht in der Sicherung.
    var taxTemplateConfirmed: Bool = false
    /// Der Markt-Tab wurde schon einmal gesehen (mindestens 3 s sichtbar): «Einordnung» und
    /// «Daten» beginnen danach zugeklappt. Gleicher Schlüssel wie Android; nicht in der Sicherung.
    var marketTabSeen: Bool = false
    /// Markt-Tab: «Mehr Marktdaten» (Zeilen zu «Stimmung & Zyklus» und «Marktdaten») aufgeklappt
    /// (Standard zu), wie Android («market_more_expanded»); nur auf diesem Gerät, nicht in der Sicherung.
    var marketMoreExpanded: Bool = false

    static let minBackgroundIntervalMinutes = 15
    static let minLiveIntervalSeconds = 15
    /// Obergrenzen (weit über den Wahlmöglichkeiten): Werte aus Sicherungen und gespeicherten Einstellungen
    /// werden darauf gekürzt — riesige Werte liessen sonst Zeitrechnungen überlaufen (wie Android).
    static let maxBackgroundIntervalMinutes = 1_440
    static let maxLiveIntervalSeconds = 3_600
    static let maxAlarmCooldownMinutes = 1_440

    static func clampBackgroundInterval(_ minutes: Int) -> Int {
        min(max(minutes, minBackgroundIntervalMinutes), maxBackgroundIntervalMinutes)
    }
    static func clampLiveInterval(_ seconds: Int) -> Int {
        min(max(seconds, minLiveIntervalSeconds), maxLiveIntervalSeconds)
    }
    static func clampAlarmCooldown(_ minutes: Int) -> Int {
        min(max(minutes, 0), maxAlarmCooldownMinutes)
    }
    static let backgroundIntervalChoices = [15, 30, 60]
    static let alarmCooldownChoices = [0, 5, 15, 60]
    static let fearGreedBelowChoices = [0, 10, 20, 25]
    static let fearGreedAboveChoices = [0, 75, 80, 90]
    /// Gas-Alarm Ethereum in Zehntel-gwei: aus, 0.5, 1, 2, 5.
    static let gasEthChoices = [0, 5, 10, 20, 50]
    /// Gas-Alarm Bitcoin in sat/vB.
    static let gasBtcChoices = [0, 1, 2, 5, 10]
    static let notificationChangeChoices: [Double] = [0, 3, 5, 7]

    /// Bisherige Standards (bis Build 42) der in Build 43 geänderten Schalter — wie Android.
    static let legacyWatchlistNames = false
    static let legacyShowConverted = false
    static let legacyIncludeAlphaTokens = true
    /// Bisheriger Standard (bis Build 43) des Portfolio-Tabs; auch für Sicherungen ohne das Feld.
    static let legacyPortfolioEnabled = true

    /// Geänderter Standard, wenn der Wert fehlt: bestehende Installation → bisheriger Standard, neue →
    /// neuer Standard — wie Android `defaultForMigration`. iOS: gespeicherte Einstellungen zu lesen
    /// heisst bestehende Installation (eine neue hat noch keine und nimmt `AppSettings()`).
    static func defaultForMigration(existingInstall: Bool, legacy: Bool, current: Bool) -> Bool {
        existingInstall ? legacy : current
    }

    init() {}

    // Fehlende Felder (ältere Versionen) bekommen den Standardwert.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        // Alle Felder tolerant (`try?`): Ein unbekannter Wert (z. B. eine Akzentfarbe aus einer
        // neueren Version nach einem Downgrade) setzt nur dieses Feld zurück, nicht alle Einstellungen.
        // Fehlt «updateMode» (Update): einmalig aus den früheren Schaltern übernehmen — beim nächsten
        // Speichern steht dann der neue Wert da (iOS speichert alle Einstellungen als Ganzes)
        let legacy = try? decoder.container(keyedBy: LegacyCodingKeys.self)
        updateMode = UpdateMode.resolve(
            stored: (try? c.decodeIfPresent(String.self, forKey: .updateMode)) ?? nil,
            legacyLiveService: (try? legacy?.decodeIfPresent(Bool.self, forKey: .liveService)) ?? nil,
            legacyWebSocket: (try? legacy?.decodeIfPresent(Bool.self, forKey: .liveWebSocket)) ?? nil,
            legacyBackground: (try? legacy?.decodeIfPresent(Bool.self, forKey: .backgroundUpdates)) ?? nil,
            existingInstall: false, serviceAvailable: false)
        // Grenzen wie bei der Sicherung (ältere/fremde Werte)
        backgroundIntervalMinutes = Self.clampBackgroundInterval(
            (try? c.decodeIfPresent(Int.self, forKey: .backgroundIntervalMinutes)) ?? d.backgroundIntervalMinutes)
        liveIntervalSeconds = Self.clampLiveInterval(
            (try? c.decodeIfPresent(Int.self, forKey: .liveIntervalSeconds)) ?? d.liveIntervalSeconds)
        priceNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .priceNotifications)) ?? d.priceNotifications
        ongoingNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .ongoingNotifications)) ?? d.ongoingNotifications
        notificationChangePercent = (try? c.decodeIfPresent(Double.self, forKey: .notificationChangePercent)) ?? d.notificationChangePercent
        ttsEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .ttsEnabled)) ?? d.ttsEnabled
        ttsAlarmsOnly = (try? c.decodeIfPresent(Bool.self, forKey: .ttsAlarmsOnly)) ?? d.ttsAlarmsOnly
        ttsSpeechRate = (try? c.decodeIfPresent(Double.self, forKey: .ttsSpeechRate)) ?? d.ttsSpeechRate
        alarmCooldownMinutes = Self.clampAlarmCooldown(
            (try? c.decodeIfPresent(Int.self, forKey: .alarmCooldownMinutes)) ?? d.alarmCooldownMinutes)
        includeRollingFutures = (try? c.decodeIfPresent(Bool.self, forKey: .includeRollingFutures)) ?? d.includeRollingFutures
        // Fehlt der neue Wert (Update): einmalig aus dem früheren Schalter übernehmen
        let legacyTradFi = (try? legacy?.decodeIfPresent(Bool.self, forKey: .includeTradFiFutures)) ?? nil
        assetScope = AssetScope.resolve(stored: (try? c.decodeIfPresent(String.self, forKey: .assetScope)) ?? nil,
                                        legacyTradFi: legacyTradFi)
        // Fehlt (vor dem Schalter gespeichert): bisheriger Standard, nicht der neue von Build 43
        includeAlphaTokens = (try? c.decodeIfPresent(Bool.self, forKey: .includeAlphaTokens))
            ?? AppSettings.defaultForMigration(existingInstall: true, legacy: AppSettings.legacyIncludeAlphaTokens,
                                               current: d.includeAlphaTokens)
        accentColor = (try? c.decodeIfPresent(AccentColor.self, forKey: .accentColor)) ?? d.accentColor
        darkMode = try? c.decodeIfPresent(Bool.self, forKey: .darkMode)
        showHttpLog = (try? c.decodeIfPresent(Bool.self, forKey: .showHttpLog)) ?? d.showHttpLog
        developerUnlocked = (try? c.decodeIfPresent(Bool.self, forKey: .developerUnlocked)) ?? d.developerUnlocked
        fearGreedBelow = (try? c.decodeIfPresent(Int.self, forKey: .fearGreedBelow)) ?? d.fearGreedBelow
        fearGreedAbove = (try? c.decodeIfPresent(Int.self, forKey: .fearGreedAbove)) ?? d.fearGreedAbove
        indicatorAlerts = (try? c.decodeIfPresent([String: Int].self, forKey: .indicatorAlerts)) ?? d.indicatorAlerts
        gestureHintSeen = (try? c.decodeIfPresent(Bool.self, forKey: .gestureHintSeen)) ?? d.gestureHintSeen
        firstAlarmCardDismissed = (try? c.decodeIfPresent(Bool.self, forKey: .firstAlarmCardDismissed))
            ?? d.firstAlarmCardDismissed
        usageDays = (try? c.decodeIfPresent(Int.self, forKey: .usageDays)) ?? d.usageDays
        usageLastDay = (try? c.decodeIfPresent(Int.self, forKey: .usageLastDay)) ?? nil
        aboutSeen = (try? c.decodeIfPresent(Bool.self, forKey: .aboutSeen)) ?? d.aboutSeen
        watchlistGroup = try? c.decodeIfPresent(String.self, forKey: .watchlistGroup)
        activityAlerts = (try? c.decodeIfPresent(Bool.self, forKey: .activityAlerts)) ?? d.activityAlerts
        // Fehlt (ältere Einstellungen) oder unbekannt: «Normal»
        activitySensitivity = ActivitySensitivity.from(name: try? c.decodeIfPresent(String.self, forKey: .activitySensitivity))
        macroNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .macroNotifications)) ?? d.macroNotifications
        // Fehlt (vor dem Schalter gespeichert): bisheriger Standard an, nicht der neue von Build 44
        portfolioEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioEnabled))
            ?? AppSettings.defaultForMigration(existingInstall: true, legacy: AppSettings.legacyPortfolioEnabled,
                                               current: d.portfolioEnabled)
        // Einstellungen von vor «Portfolio ab Werk an»: das mitgespeicherte frühere Standard-«aus» ist
        // keine erkennbare Wahl → einmalig «an» (siehe `portfolioDefaultV2`)
        let defaultMigrated: Bool? = try? c.decodeIfPresent(Bool.self, forKey: .portfolioDefaultV2)
        if defaultMigrated == nil { portfolioEnabled = AppSettings.legacyPortfolioEnabled }
        portfolioDefaultV2 = true
        portfolioCurrency = (try? c.decodeIfPresent(String.self, forKey: .portfolioCurrency)) ?? d.portfolioCurrency
        showConverted = (try? c.decodeIfPresent(Bool.self, forKey: .showConverted))
            ?? AppSettings.defaultForMigration(existingInstall: true, legacy: AppSettings.legacyShowConverted,
                                               current: d.showConverted)
        gasAlertEthTenths = (try? c.decodeIfPresent(Int.self, forKey: .gasAlertEthTenths)) ?? d.gasAlertEthTenths
        gasAlertBtc = (try? c.decodeIfPresent(Int.self, forKey: .gasAlertBtc)) ?? d.gasAlertBtc
        depegAlarm = (try? c.decodeIfPresent(Bool.self, forKey: .depegAlarm)) ?? d.depegAlarm
        depegThresholdTenths = DepegAlarm.threshold(
            (try? c.decodeIfPresent(Int.self, forKey: .depegThresholdTenths)) ?? d.depegThresholdTenths)
        alarmSound = try? c.decodeIfPresent(String.self, forKey: .alarmSound)
        // Fehlt oder unbekannt: «System»; Android-Werte → nächster iOS-Wert
        alarmSignal = AlarmSignal.from(name: try? c.decodeIfPresent(String.self, forKey: .alarmSignal)).ios
        // Unbekannter Wert (z. B. aus einer neueren Version) → Standard
        priceColorScheme = (try? c.decodeIfPresent(PriceColorScheme.self, forKey: .priceColorScheme)) ?? d.priceColorScheme
        let colorDefaultMigrated: Bool? = try? c.decodeIfPresent(Bool.self, forKey: .priceColorDefaultV2)
        priceColorScheme = PriceColorScheme.migrated(priceColorScheme, markerPresent: colorDefaultMigrated != nil)
        priceColorDefaultV2 = true
        watchlistSparkline = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistSparkline)) ?? d.watchlistSparkline
        watchlistNames = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistNames))
            ?? AppSettings.defaultForMigration(existingInstall: true, legacy: AppSettings.legacyWatchlistNames,
                                               current: d.watchlistNames)
        showChangePeriod = (try? c.decodeIfPresent(Bool.self, forKey: .showChangePeriod)) ?? d.showChangePeriod
        appIconBadge = (try? c.decodeIfPresent(Bool.self, forKey: .appIconBadge)) ?? d.appIconBadge
        watchlistActivityCard = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistActivityCard)) ?? d.watchlistActivityCard
        coinLogos = (try? c.decodeIfPresent(Bool.self, forKey: .coinLogos)) ?? d.coinLogos
        widgetCoinLogos = (try? c.decodeIfPresent(Bool.self, forKey: .widgetCoinLogos)) ?? d.widgetCoinLogos
        portfolioCoinLogos = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioCoinLogos)) ?? d.portfolioCoinLogos
        // Fehlt (ältere Einstellungen) oder unbekannt: «Letzte 24 Std.»
        changeBasis = ChangeBasis.from(name: try? c.decodeIfPresent(String.self, forKey: .changeBasis))
        highContrast = (try? c.decodeIfPresent(Bool.self, forKey: .highContrast)) ?? d.highContrast
        priceColorsInverted = (try? c.decodeIfPresent(Bool.self, forKey: .priceColorsInverted)) ?? d.priceColorsInverted
        // Seit dem Komfort-Paket; ungültige Zeiten → Standard
        quietHoursEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .quietHoursEnabled)) ?? d.quietHoursEnabled
        let start = (try? c.decodeIfPresent(Int.self, forKey: .quietHoursStart)) ?? nil
        quietHoursStart = start.flatMap { QuietHours.isValidMinute($0) ? $0 : nil } ?? d.quietHoursStart
        let end = (try? c.decodeIfPresent(Int.self, forKey: .quietHoursEnd)) ?? nil
        quietHoursEnd = end.flatMap { QuietHours.isValidMinute($0) ? $0 : nil } ?? d.quietHoursEnd
        appLock = (try? c.decodeIfPresent(Bool.self, forKey: .appLock)) ?? d.appLock
        hidePortfolioAmounts = (try? c.decodeIfPresent(Bool.self, forKey: .hidePortfolioAmounts)) ?? d.hidePortfolioAmounts
        portfolioSystemBackup = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioSystemBackup)) ?? d.portfolioSystemBackup
        firstAlarmShown = (try? c.decodeIfPresent(Bool.self, forKey: .firstAlarmShown)) ?? d.firstAlarmShown
        alarmTimingHintShown = (try? c.decodeIfPresent(Bool.self, forKey: .alarmTimingHintShown)) ?? d.alarmTimingHintShown
        firstPairAdded = (try? c.decodeIfPresent(Bool.self, forKey: .firstPairAdded)) ?? d.firstPairAdded
        sheetChartLine = (try? c.decodeIfPresent(Bool.self, forKey: .sheetChartLine)) ?? d.sheetChartLine
        portfolioHistoryExpanded = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioHistoryExpanded))
            ?? d.portfolioHistoryExpanded
        // Unbekannter Zeitraum (neuere Version): Standard 30 Tage
        portfolioHistoryRange = (try? c.decodeIfPresent(PortfolioHistoryRange.self, forKey: .portfolioHistoryRange))
            ?? d.portfolioHistoryRange
        // Unbekannte Darstellung (neuere Version): in der Währung
        portfolioHistoryView = (try? c.decodeIfPresent(PortfolioHistoryView.self, forKey: .portfolioHistoryView))
            ?? d.portfolioHistoryView
        portfolioHistoryCandles = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioHistoryCandles))
            ?? d.portfolioHistoryCandles
        portfolioHistoryPnl = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioHistoryPnl)) ?? d.portfolioHistoryPnl
        // Fehlt oder unbekannt (auch bestehende Installationen): FIFO
        costMethod = (try? c.decodeIfPresent(CostMethod.self, forKey: .costMethod)) ?? d.costMethod
        // Fehlt oder unbekannt: Neutral
        taxTemplate = (try? c.decodeIfPresent(TaxTemplate.self, forKey: .taxTemplate)) ?? d.taxTemplate
        marketTabSeen = (try? c.decodeIfPresent(Bool.self, forKey: .marketTabSeen)) ?? d.marketTabSeen
        taxTemplateConfirmed = (try? c.decodeIfPresent(Bool.self, forKey: .taxTemplateConfirmed)) ?? d.taxTemplateConfirmed
        marketMoreExpanded = (try? c.decodeIfPresent(Bool.self, forKey: .marketMoreExpanded)) ?? d.marketMoreExpanded
    }
}

extension AppSettings {
    /// Live-Kurse per WebSocket, solange die Merkliste offen ist (`LivePriceStream`) — bei «Live, solange
    /// offen». Hintergrund und Widgets fragen weiter per REST ab.
    var liveWebSocket: Bool { updateMode.liveWhileOpen }

    /// Werden Kurse von Paaren mit «Kurs vorlesen» angesagt? Nur mit Sprachausgabe an und «Nur Alarme»
    /// aus — wie `speaksPairPrices` (Android). Sonst schaltet «Kurs vorlesen» beides passend um.
    var speaksPairPrices: Bool { ttsEnabled && !ttsAlarmsOnly }
}

extension AppSettings {
    /// Schlüssel früherer Versionen, die nur noch gelesen werden.
    enum LegacyCodingKeys: String, CodingKey {
        /// Früherer Schalter «TradFi» → `assetScope`.
        case includeTradFiFutures
        /// Frühere Schalter der Aktualisierung → `updateMode`.
        case backgroundUpdates
        case liveService
        case liveWebSocket
    }
}

/// «Wie aktuell?» (Einstellungen › Kurs-Aktualisierung) — wie `UpdateMode.kt`: eine Wahl statt
/// der früheren drei Schalter «Im Hintergrund aktualisieren», «Häufig aktualisieren» und «Live-Kurse».
///
///  - `SPARSAM` (Standard, empfohlen, seit Build 43): nur die Hintergrund-Aktualisierung im gewählten Intervall, kein WebSocket.
///  - `LIVE_OFFEN` (für Profis): Live-Kurse per WebSocket, solange die Merkliste offen ist,
///    dazu die Hintergrund-Aktualisierung bei geschlossener App.
///  - `IMMER_KURZ`: nur Android (Vordergrunddienst); iOS kennt keinen Dauerdienst → `LIVE_OFFEN`.
///
/// Gespeichert und gesichert unter dem Namen (Schlüssel «updateMode», wie Android). Übernahme aus den
/// früheren Schaltern: «Häufig aktualisieren» an → `IMMER_KURZ` (iOS → `LIVE_OFFEN`), sonst
/// «Live-Kurse» an → `LIVE_OFFEN`, sonst `SPARSAM`.
enum UpdateMode: String, Codable, CaseIterable, Sendable {
    case SPARSAM
    case LIVE_OFFEN
    case IMMER_KURZ

    static let `default`: UpdateMode = .SPARSAM

    /// Wahlen dieser Plattform: iOS ohne «Immer kurz».
    static let iosChoices: [UpdateMode] = [.SPARSAM, .LIVE_OFFEN]

    /// Live-Kurse per WebSocket, solange die Merkliste offen ist.
    var liveWhileOpen: Bool { self != .SPARSAM }

    /// Vordergrunddienst mit kurzem Intervall — nur Android; auf iOS nie gewählt.
    var frequentService: Bool { self == .IMMER_KURZ }

    /// Frühere Schalter für Sicherungen, die ältere App-Versionen lesen.
    var legacyBackgroundUpdates: Bool { true }
    var legacyLiveService: Bool { frequentService }
    var legacyLiveWebSocket: Bool { liveWhileOpen }

    /// Auf einer Plattform ohne Dauerdienst (iOS) wird `IMMER_KURZ` zu `LIVE_OFFEN`.
    func forPlatform(serviceAvailable: Bool) -> UpdateMode {
        self == .IMMER_KURZ && !serviceAvailable ? .LIVE_OFFEN : self
    }

    /// Gespeicherter Name; unbekannt (neuere Version) oder leer → nil.
    static func from(name: String?) -> UpdateMode? {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), !n.isEmpty else { return nil }
        return UpdateMode(rawValue: n)
    }

    /// Aus den früheren Schaltern; fehlen alle drei, nil. Ein fehlender einzelner Schalter zählt als «aus».
    static func fromLegacy(liveService: Bool?, liveWebSocket: Bool?, backgroundUpdates: Bool?,
                           serviceAvailable: Bool) -> UpdateMode? {
        if liveService == nil && liveWebSocket == nil && backgroundUpdates == nil { return nil }
        if liveService == true { return UpdateMode.IMMER_KURZ.forPlatform(serviceAvailable: serviceAvailable) }
        return liveWebSocket == true ? .LIVE_OFFEN : .SPARSAM
    }

    /// Gespeicherte Einstellung: der neue Wert, sonst die früheren Schalter, sonst bei einer bestehenden
    /// Installation `SPARSAM` (früheres Ab-Werk-Verhalten), sonst der Standard. iOS übergibt
    /// `existingInstall: false` — die gespeicherten Einstellungen enthalten die Schalter immer.
    static func resolve(stored: String?, legacyLiveService: Bool?, legacyWebSocket: Bool?, legacyBackground: Bool?,
                        existingInstall: Bool, serviceAvailable: Bool) -> UpdateMode {
        if let mode = fromBackup(stored, legacyLiveService: legacyLiveService, legacyWebSocket: legacyWebSocket,
                                 legacyBackground: legacyBackground, serviceAvailable: serviceAvailable) {
            return mode
        }
        return existingInstall ? .SPARSAM : .default
    }

    /// Aus einer Sicherung: «updateMode», sonst die früheren Schalter (ältere Sicherung); fehlt alles,
    /// nil = aktuelle Einstellung stehen lassen.
    static func fromBackup(_ stored: String?, legacyLiveService: Bool?, legacyWebSocket: Bool?, legacyBackground: Bool?,
                           serviceAvailable: Bool) -> UpdateMode? {
        if let mode = from(name: stored) { return mode.forPlatform(serviceAvailable: serviceAvailable) }
        return fromLegacy(liveService: legacyLiveService, liveWebSocket: legacyWebSocket,
                          backgroundUpdates: legacyBackground, serviceAvailable: serviceAvailable)
    }
}

/// «Token & TradFi» (Einstellungen › Erweitert) — wie `AssetScope.kt`: Was die Paar-Auswahl
/// (Paar hinzufügen, Suche über alle Börsen, «Alle …-Paare») zeigt.
///
///  - `TOKENS` (Standard): nur Krypto — Spot, Perpetuals und (mit QTLY) Laufzeit-Futures.
///  - `TRADFI`: nur Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO (`MarketPair.isTradFi`).
///  - `BOTH`: alles.
///
/// Alpha-Tokens (`AssetKind.ALPHA`) gehören zu Token; der Schalter «inkl. Alpha-Tokens»
/// (`AppSettings.includeAlphaTokens`, ab Build 43 Standard aus) blendet sie in der Auswahl aus (`allows` mit
/// `hiddenAlpha`).
///
/// Gemerkte Paare bleiben immer in der Merkliste. Gespeichert und gesichert unter dem Namen
/// (Schlüssel «assetScope», wie Android); der frühere Schalter «includeTradFiFutures»: an → `BOTH`,
/// aus → `TOKENS`.
enum AssetScope: String, Codable, CaseIterable, Sendable {
    case TOKENS
    case TRADFI
    case BOTH

    static let `default`: AssetScope = .TOKENS

    /// TradFi-Kontrakte erscheinen in der Auswahl.
    var allowsTradFi: Bool { self != .TOKENS }

    /// Krypto-Paare (Token) erscheinen in der Auswahl.
    var allowsTokens: Bool { self != .TRADFI }

    /// Paar der Börse `marketKey` in der Auswahl zeigen? `hiddenAlpha`: Kürzel der Alpha-Tokens, die
    /// ausgeblendet sind («inkl. Alpha-Tokens» aus; `hiddenAlphaSymbols`), sonst leer. Alpha-Paare von
    /// Binance selbst (`BinanceAlpha`) nur mit «inkl. Alpha-Tokens». `pairNames` (Kürzel gross → Name aus
    /// der Paarliste der Börse) und `alphaNames` wie bei `AssetKind.of`: klar anderer Name → kein Alpha.
    func allows(_ pair: MarketPair, marketKey: String = "", hiddenAlpha: Set<String> = [],
                pairNames: [String: String] = [:], alphaNames: [String: String] = [:]) -> Bool {
        if pair.isTradFi { return allowsTradFi }
        guard allowsTokens else { return false }
        if BinanceAlpha.isAlphaPair(marketKey: marketKey, pairId: pair.pairId) { return hiddenAlpha.isEmpty }
        if hiddenAlpha.isEmpty { return true }
        let kind = AssetKind.of(marketKey: marketKey, base: pair.base, tradFi: false, alphaSymbols: hiddenAlpha,
                                pairName: pairNames[TokenIdentity.key(pair.base)], alphaNames: alphaNames)
        return kind != .ALPHA
    }

    /// Platzhalter, wenn «inkl. Alpha-Tokens» aus ist, aber noch keine Alpha-Liste vorliegt: hält
    /// `hiddenAlphaSymbols` nicht leer, damit die Alpha-Paare von Binance trotzdem ausgeblendet sind
    /// (kein gültiges Kürzel, trifft nie ein Paar).
    static let hideAlphaMark = "#alpha"

    /// Auszublendende Alpha-Kürzel für `allows`: «inkl. Alpha-Tokens» an → keine, aus → alle
    /// bekannten (`alphaSymbols`; ohne Liste `hideAlphaMark`).
    static func hiddenAlphaSymbols(includeAlpha: Bool, alphaSymbols: Set<String>) -> Set<String> {
        if includeAlpha { return [] }
        return alphaSymbols.isEmpty ? [hideAlphaMark] : alphaSymbols
    }

    /// Börse in der Auswahl zeigen? Bei «Nur TradFi» nur Börsen, deren Paarliste TradFi kennt
    /// (`CoinLogos.tradFiMarkets`) — die übrigen (auch DEX) hätten dort keine Paare.
    func allowsMarket(_ marketKey: String) -> Bool {
        allowsTokens || CoinLogos.tradFiMarkets.contains(marketKey)
    }

    /// Gespeicherter Name; unbekannt (neuere Version) oder leer → nil.
    static func from(name: String?) -> AssetScope? {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), !n.isEmpty else { return nil }
        return AssetScope(rawValue: n)
    }

    /// Früherer Schalter «TradFi»: an → `BOTH`, sonst `TOKENS`.
    static func fromLegacy(_ includeTradFi: Bool?) -> AssetScope { includeTradFi == true ? .BOTH : .TOKENS }

    /// Gespeicherte Einstellung: der neue Wert, sonst (Update) der frühere Schalter, sonst der Standard.
    static func resolve(stored: String?, legacyTradFi: Bool?) -> AssetScope {
        fromBackup(stored, legacyTradFi: legacyTradFi) ?? .default
    }

    /// Aus einer Sicherung: «assetScope», sonst «includeTradFiFutures» (ältere Sicherung); fehlen
    /// beide, nil = aktuelle Einstellung stehen lassen.
    static func fromBackup(_ scope: String?, legacyTradFi: Bool?) -> AssetScope? {
        if let s = from(name: scope) { return s }
        guard let legacyTradFi else { return nil }
        return fromLegacy(legacyTradFi)
    }
}

extension AppSettings {
    /// Standard-Umrechnungswährung: die Währung der Geräte-Region (Schweiz → CHF,
    /// Deutschland → EUR, USA → USD …), sofern dafür ein Devisenkurs verfügbar ist,
    /// sonst USD. Gilt nur, bis jemand selbst eine Währung wählt — wie `defaultCurrency()` in Android.
    static func defaultCurrency(_ locale: Locale = .current) -> String {
        guard let code = locale.currency?.identifier.uppercased(),
              FxRateSource.currencies.contains(code) else { return "USD" }
        return code
    }

    /// Regionen, in denen Rot «steigend» bedeutet — wie Android (`Locale.getDefault().country`).
    static let invertedPriceColorRegions: Set<String> = ["CN", "TW", "HK", "MO", "JP", "KR"]

    /// Standard für «Farben tauschen»: an in CN, TW, HK, MO, JP und KR.
    static func defaultPriceColorsInverted(_ locale: Locale = .current) -> Bool {
        guard let region = locale.region?.identifier.uppercased() else { return false }
        return invertedPriceColorRegions.contains(region)
    }
}
