import Foundation

/// Momentaufnahme aller Einstellungen — wie `AppSettings.kt`.
///
/// Unterschiede zu Android (iOS erlaubt keinen Dauerdienst):
/// - `backgroundUpdates`/`backgroundIntervalMinutes`: iOS führt die Hintergrund-
///   Aktualisierung frühestens nach dem Intervall aus, oft später.
/// - `liveService`/`liveIntervalSeconds`: «Live» aktualisiert, solange die App offen ist.
/// - `ongoingNotifications` hat auf iOS keine Wirkung, bleibt aber für Sicherungen erhalten.
struct AppSettings: Codable, Equatable, Sendable {
    var backgroundUpdates: Bool = true
    var backgroundIntervalMinutes: Int = 15
    var liveService: Bool = false
    var liveIntervalSeconds: Int = 60
    /// Live-Kurse per WebSocket, solange die Merkliste offen ist (`LivePriceStream`);
    /// Hintergrund und Widgets fragen weiter per REST ab.
    var liveWebSocket: Bool = false
    var priceNotifications: Bool = true
    var ongoingNotifications: Bool = true
    /// Mindestveränderung in Prozent für eine Kurs-Mitteilung (0 = jede Aktualisierung).
    var notificationChangePercent: Double = 5.0
    var ttsEnabled: Bool = false
    var ttsAlarmsOnly: Bool = true
    /// Sprechgeschwindigkeit (0.5 – 2.0), 1 = normal.
    var ttsSpeechRate: Double = 1.0
    /// Ruhezeit, bevor derselbe Alarm erneut auslöst (Minuten).
    var alarmCooldownMinutes: Int = 0
    var includeRollingFutures: Bool = false
    /// Futures auf Aktien, Rohstoffe, Devisen und Pre-IPO (Binance «TradFi-Perpetuals») in der
    /// Auswahl zeigen. Aus (Standard): nur Krypto; gemerkte Paare bleiben in der Merkliste.
    var includeTradFiFutures: Bool = false
    var accentColor: AccentColor = .default
    /// nil = wie das System.
    var darkMode: Bool? = nil
    var showHttpLog: Bool = false
    var developerUnlocked: Bool = false
    var zoneAlerts: Bool = true
    /// Fear & Greed: melden unter/über diesem Wert (0 = aus).
    var fearGreedBelow: Int = 0
    var fearGreedAbove: Int = 0
    /// Gas-Alarm Ethereum in Zehntel-gwei (0 = aus) — wie `gasAlertEthTenths`.
    var gasAlertEthTenths: Int = 0
    /// Gas-Alarm Bitcoin in sat/vB (0 = aus).
    var gasAlertBtc: Int = 0
    /// Alarmton: Name eines mitgelieferten Tons (`AlarmSounds`), nil = Standardton.
    var alarmSound: String? = nil
    /// «Alarm-Signal» (Schlüssel wie Android). iOS: nur `SYSTEM` oder `SILENT`, siehe `AlarmSignal`.
    var alarmSignal: AlarmSignal = .default
    var gestureHintSeen: Bool = false
    var aboutSeen: Bool = false
    /// Gewählte Gruppe der Merkliste; nil = «Alle».
    var watchlistGroup: String? = nil
    /// Ungewöhnliche Aktivität (Bewegung, Volumen, Futures) als Mitteilung melden.
    var activityAlerts: Bool = false
    /// Empfindlichkeit von «Ungewöhnliche Aktivität» (Karte und Mitteilungen); Standard = bisherige Schwellen.
    var activitySensitivity: ActivitySensitivity = .NORMAL
    /// Morgen-Mitteilung (08:00) an Tagen mit wichtigen US-Wirtschaftsdaten.
    var macroNotifications: Bool = false
    /// Optionaler Bereich «Portfolio» (eigener Tab vor den Optionen).
    var portfolioEnabled: Bool = false
    /// Umrechnungswährung: Zielwährung der Umrechnungszeile im Portfolio und der
    /// umgerechneten Kurse in der Merkliste (USD = keine Umrechnung im Portfolio).
    var portfolioCurrency: String = AppSettings.defaultCurrency()
    /// Kurse in der Merkliste zusätzlich in der Umrechnungswährung zeigen («≈ 61’234 CHF»).
    var showConverted: Bool = false
    /// Kursfarben steigend/fallend: Grün/Rot oder Blau/Orange (Rot-Grün-Sehschwäche).
    var priceColorScheme: PriceColorScheme = .default
    /// Mini-Chart (24-Stunden-Verlauf) in den Zeilen der Merkliste.
    var watchlistSparkline: Bool = false
    /// Name unter dem Paar in der Merkliste («Bitcoin», «NVIDIA»); ab Werk aus (Zeilen werden höher).
    var watchlistNames: Bool = false
    /// Zeitraum der %-Änderung («24h», «heute», «letzter Stand») klein neben Pille, Puls,
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
    /// Ortszeit — für Pille, Puls, Aktionsblatt, Widgets und Live Activity; Alarme unabhängig davon.
    var changeBasis: ChangeBasis = .default
    /// Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte (zusätzlich zu
    /// «Kontrast erhöhen» des Systems).
    var highContrast: Bool = false
    /// «Farben tauschen»: Rot = steigend, Grün = fallend (bzw. Orange/Blau). Ohne
    /// gespeicherten Wert gilt der Standard der Geräte-Region.
    var priceColorsInverted: Bool = AppSettings.defaultPriceColorsInverted()
    /// Nachtruhe: Alarme in dieser Zeit lautlos (siehe `QuietHours`). Zeiten in
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
    /// Der Markt-Tab wurde schon einmal gesehen (mindestens 3 s sichtbar): «Einordnung» und
    /// «Daten» beginnen danach zugeklappt. Gleicher Schlüssel wie Android; nicht in der Sicherung.
    var marketTabSeen: Bool = false

    static let minBackgroundIntervalMinutes = 15
    static let minLiveIntervalSeconds = 15
    static let backgroundIntervalChoices = [15, 30, 60]
    static let liveIntervalChoices = [15, 30, 60, 300]
    static let alarmCooldownChoices = [0, 5, 15, 60]
    static let fearGreedBelowChoices = [0, 10, 20, 25]
    static let fearGreedAboveChoices = [0, 75, 80, 90]
    /// Gas-Alarm Ethereum in Zehntel-gwei: aus, 0.5, 1, 2, 5.
    static let gasEthChoices = [0, 5, 10, 20, 50]
    /// Gas-Alarm Bitcoin in sat/vB.
    static let gasBtcChoices = [0, 1, 2, 5, 10]
    static let notificationChangeChoices: [Double] = [0, 3, 5, 7]

    init() {}

    // Fehlende Felder (ältere Versionen) bekommen den Standardwert.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        // Alle Felder tolerant (`try?`): Ein unbekannter Wert (z. B. eine Akzentfarbe aus einer
        // neueren Version nach einem Downgrade) setzt nur dieses Feld zurück, nicht alle Einstellungen.
        backgroundUpdates = (try? c.decodeIfPresent(Bool.self, forKey: .backgroundUpdates)) ?? d.backgroundUpdates
        backgroundIntervalMinutes = (try? c.decodeIfPresent(Int.self, forKey: .backgroundIntervalMinutes)) ?? d.backgroundIntervalMinutes
        liveService = (try? c.decodeIfPresent(Bool.self, forKey: .liveService)) ?? d.liveService
        liveIntervalSeconds = (try? c.decodeIfPresent(Int.self, forKey: .liveIntervalSeconds)) ?? d.liveIntervalSeconds
        liveWebSocket = (try? c.decodeIfPresent(Bool.self, forKey: .liveWebSocket)) ?? d.liveWebSocket
        priceNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .priceNotifications)) ?? d.priceNotifications
        ongoingNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .ongoingNotifications)) ?? d.ongoingNotifications
        notificationChangePercent = (try? c.decodeIfPresent(Double.self, forKey: .notificationChangePercent)) ?? d.notificationChangePercent
        ttsEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .ttsEnabled)) ?? d.ttsEnabled
        ttsAlarmsOnly = (try? c.decodeIfPresent(Bool.self, forKey: .ttsAlarmsOnly)) ?? d.ttsAlarmsOnly
        ttsSpeechRate = (try? c.decodeIfPresent(Double.self, forKey: .ttsSpeechRate)) ?? d.ttsSpeechRate
        alarmCooldownMinutes = (try? c.decodeIfPresent(Int.self, forKey: .alarmCooldownMinutes)) ?? d.alarmCooldownMinutes
        includeRollingFutures = (try? c.decodeIfPresent(Bool.self, forKey: .includeRollingFutures)) ?? d.includeRollingFutures
        includeTradFiFutures = (try? c.decodeIfPresent(Bool.self, forKey: .includeTradFiFutures)) ?? d.includeTradFiFutures
        accentColor = (try? c.decodeIfPresent(AccentColor.self, forKey: .accentColor)) ?? d.accentColor
        darkMode = try? c.decodeIfPresent(Bool.self, forKey: .darkMode)
        showHttpLog = (try? c.decodeIfPresent(Bool.self, forKey: .showHttpLog)) ?? d.showHttpLog
        developerUnlocked = (try? c.decodeIfPresent(Bool.self, forKey: .developerUnlocked)) ?? d.developerUnlocked
        zoneAlerts = (try? c.decodeIfPresent(Bool.self, forKey: .zoneAlerts)) ?? d.zoneAlerts
        fearGreedBelow = (try? c.decodeIfPresent(Int.self, forKey: .fearGreedBelow)) ?? d.fearGreedBelow
        fearGreedAbove = (try? c.decodeIfPresent(Int.self, forKey: .fearGreedAbove)) ?? d.fearGreedAbove
        gestureHintSeen = (try? c.decodeIfPresent(Bool.self, forKey: .gestureHintSeen)) ?? d.gestureHintSeen
        aboutSeen = (try? c.decodeIfPresent(Bool.self, forKey: .aboutSeen)) ?? d.aboutSeen
        watchlistGroup = try? c.decodeIfPresent(String.self, forKey: .watchlistGroup)
        activityAlerts = (try? c.decodeIfPresent(Bool.self, forKey: .activityAlerts)) ?? d.activityAlerts
        // Fehlt (ältere Einstellungen) oder unbekannt: «Normal»
        activitySensitivity = ActivitySensitivity.from(name: try? c.decodeIfPresent(String.self, forKey: .activitySensitivity))
        macroNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .macroNotifications)) ?? d.macroNotifications
        portfolioEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioEnabled)) ?? d.portfolioEnabled
        portfolioCurrency = (try? c.decodeIfPresent(String.self, forKey: .portfolioCurrency)) ?? d.portfolioCurrency
        showConverted = (try? c.decodeIfPresent(Bool.self, forKey: .showConverted)) ?? d.showConverted
        gasAlertEthTenths = (try? c.decodeIfPresent(Int.self, forKey: .gasAlertEthTenths)) ?? d.gasAlertEthTenths
        gasAlertBtc = (try? c.decodeIfPresent(Int.self, forKey: .gasAlertBtc)) ?? d.gasAlertBtc
        alarmSound = try? c.decodeIfPresent(String.self, forKey: .alarmSound)
        // Fehlt oder unbekannt: «System»; Android-Werte → nächster iOS-Wert
        alarmSignal = AlarmSignal.from(name: try? c.decodeIfPresent(String.self, forKey: .alarmSignal)).ios
        // Unbekannter Wert (z. B. aus einer neueren Version) → Standard
        priceColorScheme = (try? c.decodeIfPresent(PriceColorScheme.self, forKey: .priceColorScheme)) ?? d.priceColorScheme
        watchlistSparkline = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistSparkline)) ?? d.watchlistSparkline
        watchlistNames = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistNames)) ?? d.watchlistNames
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
        marketTabSeen = (try? c.decodeIfPresent(Bool.self, forKey: .marketTabSeen)) ?? d.marketTabSeen
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
