import Foundation

/// Momentaufnahme aller Einstellungen — wie `AppSettings.kt`.
///
/// Unterschiede zu Android (iOS erlaubt keinen Dauerdienst):
/// - `backgroundIntervalMinutes`: iOS führt die Hintergrund-Aktualisierung frühestens nach dem
///   Intervall aus, oft später.
/// - `ongoingNotifications` hat auf iOS keine Wirkung, bleibt aber für Sicherungen erhalten.
struct AppSettings: Codable, Equatable, Sendable {
    /// «Wie aktuell?»: Sparsam (Standard) oder Live, solange offen — wie Android (`UpdateMode`); die
    /// Hintergrund-Aktualisierung läuft in jeder Wahl.
    var updateMode: UpdateMode = .default
    var backgroundIntervalMinutes: Int = 15
    var priceNotifications: Bool = true
    /// Ohne Wirkung auf iOS; Standard wie Android (aus).
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
    /// der Merkliste (siehe `AssetScope`).
    var assetScope: AssetScope = .default
    /// «inkl. Alpha-Tokens» (bei «Token & TradFi»): Alpha-Tokens (`AssetKind.ALPHA`) in der
    /// Paar-Auswahl zeigen. Standard aus. Gemerkte Paare bleiben immer — wie Android `includeAlphaTokens`.
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
    /// Optionaler Bereich «Portfolio» (eigener Tab vor den Einstellungen). Ab Werk aus — wie Android.
    var portfolioEnabled: Bool = false
    /// Umrechnungswährung: Zielwährung der Umrechnungszeile im Portfolio und der
    /// umgerechneten Kurse in der Merkliste (USD = keine Umrechnung im Portfolio).
    var portfolioCurrency: String = AppSettings.defaultCurrency()
    /// Kurse in der Merkliste zusätzlich in der Umrechnungswährung zeigen («≈ 61’234 CHF»).
    /// Ab Werk an.
    var showConverted: Bool = true
    /// Kursfarben steigend/fallend: Grün/Rot oder Blau/Orange (Rot-Grün-Sehschwäche).
    var priceColorScheme: PriceColorScheme = .default
    /// Mini-Chart (24-Stunden-Verlauf) in den Zeilen der Merkliste.
    var watchlistSparkline: Bool = false
    /// Name unter dem Paar in der Merkliste («Bitcoin», «NVIDIA»). Ab Werk an.
    var watchlistNames: Bool = true
    /// «Zahl am App-Symbol»: neue Alarme und Marktmeldungen zählen am Kennzeichen, beim Öffnen
    /// der App wieder 0 (`Notifier.badge`); ab Werk an — wie `appIconBadge` (Android).
    var appIconBadge: Bool = true
    /// Karte «Hier passiert gerade etwas» über der Merkliste. Nur die Anzeige: Mitteilungen dazu
    /// stellt man unter Markt-Meldungen ein, das ⚡ an den Paaren bleibt.
    var watchlistActivityCard: Bool = true
    /// Echte Coin-Logos (alle auf einmal von CoinGecko geladen, auf dem Gerät gespeichert) in
    /// Merkliste, Aktionsblatt, Markt und «Paar hinzufügen». Aus: Initialen. Ab Werk an.
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
    /// Erstes Paar überhaupt hinzugefügt (Start-Tipp oder Seite «Paar hinzufügen») — danach
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
    /// FIFO, wie Android («costMethod»); in der App-Sicherung (fehlt der Schlüssel → FIFO).
    var costMethod: CostMethod = .defaultMethod
    /// Steuer-Vorlage der Arbeitsunterlage (Neutral | DE | AT | CH | UK | US | CA | AU | FR | JP), Standard Neutral — wie
    /// Android («tax_template»). Die Region des Geräts wird nur vorgeschlagen, nie still übernommen. In der App-Sicherung
    /// («taxTemplate»; fehlt der Schlüssel, bleibt die Einstellung stehen).
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
    /// Eigener Bitcoin-Server für Adressabfragen (`AddressServer`, normalisiert); nil = öffentliche Knoten. Gesetzt fragen
    /// Bitcoin-Adressen und xpub nur dort. In der Sicherung («addressServerBtc»), wie Android.
    var addressServerBtc: String? = nil

    init() {}

    // Fehlende Felder bekommen den Standardwert.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        // Alle Felder tolerant (`try?`): Ein unbekannter Wert setzt nur dieses Feld zurück, nicht alle Einstellungen.
        updateMode = UpdateMode.from(name: (try? c.decodeIfPresent(String.self, forKey: .updateMode)) ?? nil) ?? d.updateMode
        // Grenzen wie bei der Sicherung
        backgroundIntervalMinutes = Self.clampBackgroundInterval(
            (try? c.decodeIfPresent(Int.self, forKey: .backgroundIntervalMinutes)) ?? d.backgroundIntervalMinutes)
        priceNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .priceNotifications)) ?? d.priceNotifications
        ongoingNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .ongoingNotifications)) ?? d.ongoingNotifications
        notificationChangePercent = (try? c.decodeIfPresent(Double.self, forKey: .notificationChangePercent)) ?? d.notificationChangePercent
        ttsEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .ttsEnabled)) ?? d.ttsEnabled
        ttsAlarmsOnly = (try? c.decodeIfPresent(Bool.self, forKey: .ttsAlarmsOnly)) ?? d.ttsAlarmsOnly
        ttsSpeechRate = (try? c.decodeIfPresent(Double.self, forKey: .ttsSpeechRate)) ?? d.ttsSpeechRate
        alarmCooldownMinutes = Self.clampAlarmCooldown(
            (try? c.decodeIfPresent(Int.self, forKey: .alarmCooldownMinutes)) ?? d.alarmCooldownMinutes)
        includeRollingFutures = (try? c.decodeIfPresent(Bool.self, forKey: .includeRollingFutures)) ?? d.includeRollingFutures
        assetScope = AssetScope.from(name: (try? c.decodeIfPresent(String.self, forKey: .assetScope)) ?? nil) ?? d.assetScope
        includeAlphaTokens = (try? c.decodeIfPresent(Bool.self, forKey: .includeAlphaTokens)) ?? d.includeAlphaTokens
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
        // Fehlt oder unbekannt: «Normal»
        activitySensitivity = ActivitySensitivity.from(name: try? c.decodeIfPresent(String.self, forKey: .activitySensitivity))
        macroNotifications = (try? c.decodeIfPresent(Bool.self, forKey: .macroNotifications)) ?? d.macroNotifications
        portfolioEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioEnabled)) ?? d.portfolioEnabled
        portfolioCurrency = (try? c.decodeIfPresent(String.self, forKey: .portfolioCurrency)) ?? d.portfolioCurrency
        showConverted = (try? c.decodeIfPresent(Bool.self, forKey: .showConverted)) ?? d.showConverted
        gasAlertEthTenths = (try? c.decodeIfPresent(Int.self, forKey: .gasAlertEthTenths)) ?? d.gasAlertEthTenths
        gasAlertBtc = (try? c.decodeIfPresent(Int.self, forKey: .gasAlertBtc)) ?? d.gasAlertBtc
        depegAlarm = (try? c.decodeIfPresent(Bool.self, forKey: .depegAlarm)) ?? d.depegAlarm
        depegThresholdTenths = DepegAlarm.threshold(
            (try? c.decodeIfPresent(Int.self, forKey: .depegThresholdTenths)) ?? d.depegThresholdTenths)
        alarmSound = try? c.decodeIfPresent(String.self, forKey: .alarmSound)
        // Fehlt oder unbekannt: «System»; Android-Werte → nächster iOS-Wert
        alarmSignal = AlarmSignal.from(name: try? c.decodeIfPresent(String.self, forKey: .alarmSignal)).ios
        // Unbekannter Wert → Standard
        priceColorScheme = (try? c.decodeIfPresent(PriceColorScheme.self, forKey: .priceColorScheme)) ?? d.priceColorScheme
        watchlistSparkline = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistSparkline)) ?? d.watchlistSparkline
        watchlistNames = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistNames)) ?? d.watchlistNames
        appIconBadge = (try? c.decodeIfPresent(Bool.self, forKey: .appIconBadge)) ?? d.appIconBadge
        watchlistActivityCard = (try? c.decodeIfPresent(Bool.self, forKey: .watchlistActivityCard)) ?? d.watchlistActivityCard
        coinLogos = (try? c.decodeIfPresent(Bool.self, forKey: .coinLogos)) ?? d.coinLogos
        widgetCoinLogos = (try? c.decodeIfPresent(Bool.self, forKey: .widgetCoinLogos)) ?? d.widgetCoinLogos
        portfolioCoinLogos = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioCoinLogos)) ?? d.portfolioCoinLogos
        // Fehlt oder unbekannt: «Letzte 24 Std.»
        changeBasis = ChangeBasis.from(name: try? c.decodeIfPresent(String.self, forKey: .changeBasis))
        highContrast = (try? c.decodeIfPresent(Bool.self, forKey: .highContrast)) ?? d.highContrast
        priceColorsInverted = (try? c.decodeIfPresent(Bool.self, forKey: .priceColorsInverted)) ?? d.priceColorsInverted
        // Ungültige Zeiten → Standard
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
        // Unbekannter Zeitraum: Standard 30 Tage
        portfolioHistoryRange = (try? c.decodeIfPresent(PortfolioHistoryRange.self, forKey: .portfolioHistoryRange))
            ?? d.portfolioHistoryRange
        // Unbekannte Darstellung: in der Währung
        portfolioHistoryView = (try? c.decodeIfPresent(PortfolioHistoryView.self, forKey: .portfolioHistoryView))
            ?? d.portfolioHistoryView
        portfolioHistoryCandles = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioHistoryCandles))
            ?? d.portfolioHistoryCandles
        portfolioHistoryPnl = (try? c.decodeIfPresent(Bool.self, forKey: .portfolioHistoryPnl)) ?? d.portfolioHistoryPnl
        // Fehlt oder unbekannt: FIFO
        costMethod = (try? c.decodeIfPresent(CostMethod.self, forKey: .costMethod)) ?? d.costMethod
        // Fehlt oder unbekannt: Neutral
        taxTemplate = (try? c.decodeIfPresent(TaxTemplate.self, forKey: .taxTemplate)) ?? d.taxTemplate
        marketTabSeen = (try? c.decodeIfPresent(Bool.self, forKey: .marketTabSeen)) ?? d.marketTabSeen
        taxTemplateConfirmed = (try? c.decodeIfPresent(Bool.self, forKey: .taxTemplateConfirmed)) ?? d.taxTemplateConfirmed
        marketMoreExpanded = (try? c.decodeIfPresent(Bool.self, forKey: .marketMoreExpanded)) ?? d.marketMoreExpanded
        // Beim Lesen erneut geprüft; ungültig gilt als nicht gesetzt (wie Android `AddressServerStore`)
        addressServerBtc = ((try? c.decodeIfPresent(String.self, forKey: .addressServerBtc)) ?? nil).flatMap { AddressServer.check($0).url }
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
