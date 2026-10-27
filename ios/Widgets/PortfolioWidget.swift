import AppIntents
import SwiftUI
import WidgetKit

// MARK: Daten

struct PortfolioEntry: TimelineEntry {
    var date: Date
    /// nil = noch nie berechnet (wie leer behandelt).
    let snapshot: PortfolioWidgetSnapshot?
    /// Portfolio-Sperre an: Titel, Schloss und «Gesperrt – in der App entsperren», keine Werte.
    let locked: Bool
    /// Portfolio in der App eingeschaltet? Aus: «Portfolio ist aus» und wo man es einschaltet.
    var portfolioEnabled = true
    let accent: AccentColor
    /// Ansicht je Widget: Übersicht, Chart (Standard, auch für bestehende Widgets) oder Liste.
    var view: PortfolioWidgetMode = .standard
    /// Anzeige je Widget: nur USDT, nur Umrechnung oder zusätzlich «≈ … USDT».
    var display: PortfolioDisplayOption = .conversion
    /// Zeitraum für Veränderung und Chart (24 h / 7 T / 30 T / 1 J).
    var range: PortfolioChartRangeOption = .day
    /// Chart-Art (Standard Linie).
    var chartType: WidgetChartTypeOption = .line
    var theme: WidgetThemeOption = .system
    /// Ansicht «Liste»: welche Buchungen unter den Coins (falls Platz bleibt).
    var entryFilter: PortfolioWidgetEntryFilter = .off
    var priceColors: PriceColorScheme = .default
    var highContrast = false
    var priceColorsInverted = false
    /// «Beträge verbergen» (Einstellung der App, App Group): Beträge als «•••», Prozente bleiben.
    var hideAmounts = false
    /// Ab diesem Alter «veraltet» (`WidgetOutdated`), gemessen an `date`.
    var outdatedAfter: Int64 = WidgetOutdated.afterMillis
    /// Ansicht «Liste»: Coin-Logos je Kürzel (`CoinLogoStore`, nur vom Gerät); leer = Initialen.
    var logos: [String: UIImage] = [:]
    /// Schalter «Coin-Logos in Widgets»; aus = keine Plakette.
    var showLogos = false
    /// Zone für die Tage der Balken und das Zeitgitter — dieselbe wie im Einzel-Widget (Chart-Zeitzone der %-Basis,
    /// `ChangeBasisMath.chartTimeZone`). Wie Android.
    var chartTimeZone: TimeZone = .current

    /// Beispiel der Galerie: Zeitraum wie gesetzt (24 h, ohne Tagesverlauf kein Hinweis), ohne Regel je Ansicht.
    var isSample = false

    /// Wirksame Anzeige: je Ansicht (`PortfolioWidgetOptions`: Liste ohne «+ USDT»), dann bei einem USD-Stablecoin
    /// «Nur USDT», ohne USDT-Wert nur Umrechnung.
    var effectiveDisplay: PortfolioWidgetDisplay {
        let chosen = PortfolioWidgetOptions.effectiveDisplay(view, display.display)
        guard let snapshot else { return chosen }
        return PortfolioWidgetChart.effectiveDisplay(chosen, currency: snapshot.currency,
                                                     hasUsdt: snapshot.totalUsdt != nil)
    }

    /// Wirksamer Zeitraum je Ansicht (`PortfolioWidgetOptions`: Übersicht nur 30 T / 1 J); gespeichert bleibt die Wahl.
    var effectiveRange: PortfolioWidgetRange {
        isSample ? range.range : PortfolioWidgetOptions.effectiveRange(view, range.range)
    }

    /// Für den Sperrbildschirm: ohne Regel der gewählten Ansicht (`PortfolioWidgetOptions.lockScreenView`).
    var lockScreen: PortfolioEntry {
        var copy = self
        copy.view = PortfolioWidgetOptions.lockScreenView
        return copy
    }

    /// Der Stand, wie das Widget ihn zeigt (bei «Nur USDT» in USDT).
    var shown: PortfolioWidgetSnapshot? {
        snapshot.map { PortfolioWidgetChart.displayed($0, display: effectiveDisplay) }
    }

    /// Was das Widget zeigt (aus, gesperrt, leer oder Werte) — `PortfolioOff.widgetState`.
    var state: PortfolioOff.WidgetState {
        PortfolioOff.widgetState(portfolioEnabled: portfolioEnabled, locked: locked, empty: snapshot?.empty ?? true)
    }

    /// «≈ … USDT» unter dem Gesamtwert?
    var wantsUsdt: Bool { effectiveDisplay == .conversionUsdt }

    /// Zeitraum, Chart und Veränderung für den gezeigten Stand (`PortfolioWidgetChart.plan`).
    func plan(for s: PortfolioWidgetSnapshot) -> PortfolioChartPlan {
        PortfolioWidgetChart.plan(s, requested: effectiveRange, type: chartType.chartType,
                                  // UTC-Tag wie der Tagesverlauf der Aufnahme (`PortfolioWidgetChart.dailyData`)
                                  todayEpochDay: PortfolioHistory.epochDay(utcMillis: Int64(date.timeIntervalSince1970 * 1000)),
                                  timeZone: chartTimeZone,
                                  dayAxis: PortfolioWidgetText.dayAxis(s))
    }

    /// Momentaufnahme am Datum des Eintrags veraltet? (Aus, gesperrt oder leer: nie.)
    var outdated: Bool {
        guard state == .values, let snapshot else { return false }
        return WidgetOutdated.isOutdated(snapshot.updatedAt, at: date, afterMillis: outdatedAfter)
    }

    static func current(_ config: PortfolioWidgetIntent? = nil) -> PortfolioEntry {
        // Einstellungen nicht zugänglich (vor dem ersten Entsperren): Portfolio-Sperre gilt als an
        let loaded = SharedStorage.loadSettingsChecked()
        let settings = loaded.settings
        // %-Basis: Stand mit anderer Basis oder von einem früheren Tag → Veränderung «—»
        // In der eingestellten Umrechnungswährung (ohne Kurs in USD aufgenommen oder Währung inzwischen gewechselt):
        // mit dem gemerkten Kurs umrechnen, damit «Umrechnung» sofort greift (wie Android)
        let now = TimeUtils.nowMillis
        let rate = PortfolioWidgetChart.fxRate(for: settings.portfolioCurrency, fresh: nil,
                                               memo: PortfolioWidgetStore.loadFxMemo(), now: now)
            // USDT zum Marktkurs: × USDT/USD (wie Android `PortfolioWidgetChart.usdtFactor`)
            .map { PortfolioWidgetChart.usdtFactor(currency: settings.portfolioCurrency, fx: $0,
                                                   usdtLive: DepegAlarmCheck.liveUsdtUsd(now: now)) }
        let snapshot = PortfolioWidgetStore.load().map {
            PortfolioWidgetChart.rebased($0, currency: settings.portfolioCurrency, rate: rate)
                .shown(basis: settings.changeBasis.storage, now: now)
        }
        var entry = PortfolioEntry(date: Date(), snapshot: snapshot,
                                   locked: PortfolioLockPolicy.widgetLocked(lockSetting: loaded.appLockAssumed),
                                   portfolioEnabled: settings.portfolioEnabled,
                                   accent: settings.accentColor,
                                   priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                                   priceColorsInverted: settings.priceColorsInverted,
                                   hideAmounts: settings.hidePortfolioAmounts)
        entry.chartTimeZone = ChangeBasisMath.chartTimeZone(settings.changeBasis.storage)
        entry.apply(config)
        return entry
    }

    /// Einstellungen des Widgets übernehmen (ohne: Standardwerte).
    mutating func apply(_ config: PortfolioWidgetIntent?) {
        guard let config else { return }
        view = config.view.view
        display = config.display
        range = config.range
        chartType = config.chartType
        entryFilter = config.entries.filter
        theme = config.theme
    }

    /// Ansicht «Liste»: Coin-Logos der Positionen aus dem gemeinsamen Ordner (nie aus dem Netz;
    /// Schalter «Coin-Logos in Widgets») — wie `WidgetLogos` für die Merkliste.
    mutating func loadLogos() {
        showLogos = SharedStorage.loadSettings().widgetCoinLogos
        guard showLogos, view == .list, state == .values, let positions = snapshot?.topPositions, !positions.isEmpty else { return }
        logos = CoinLogoStore.storedLogos(positions.map { CoinLogos.logoKey($0.symbol, tradFi: false) })
    }

    static func sample(_ config: PortfolioWidgetIntent? = nil) -> PortfolioEntry {
        let settings = SharedStorage.loadSettings()
        let now = TimeUtils.nowMillis
        let hour: Int64 = 3_600_000
        // 24 Stundenwerte und der aktuelle (wie der Stundenverlauf)
        let values: [Double] = [12_163, 12_180, 12_150, 12_120, 12_135, 12_170, 12_166, 12_205, 12_230, 12_211,
                                12_240, 12_275, 12_260, 12_290, 12_315, 12_296, 12_324, 12_352, 12_333, 12_366,
                                12_395, 12_377, 12_410, 12_434, 12_345.67]
        var history: [PortfolioWidgetPoint] = []
        for (i, value) in values.enumerated() {
            history.append(PortfolioWidgetPoint(at: now - Int64(values.count - 1 - i) * hour, value: value))
        }
        let positions = [
            // Gewinn/Verlust je Position: Summe = «Heute» (182.40)
            PortfolioWidgetPosition(symbol: "BTC", value: 7_420.10, sharePercent: 60.1, change24hPercent: 1.8, quantity: "0.12",
                                    pnlAmount: 131.2, pnlPercent: 1.8),
            PortfolioWidgetPosition(symbol: "ETH", value: 2_910.55, sharePercent: 23.6, change24hPercent: -0.9, quantity: "1.3",
                                    pnlAmount: -26.43, pnlPercent: -0.9),
            PortfolioWidgetPosition(symbol: "SOL", value: 1_215.02, sharePercent: 9.8, change24hPercent: 3.2, quantity: "9.5",
                                    pnlAmount: 77.63, pnlPercent: 6.8),
            PortfolioWidgetPosition(symbol: "ADA", value: 800.00, sharePercent: 6.5, change24hPercent: 0, quantity: "1520",
                                    pnlAmount: 0, pnlPercent: 0),
        ]
        // Beispiel für die Übersicht: 7 T und ± seit Kauf in USDT, dazu in der Anzeigewährung (wie ein Stand mit dem
        // Merkzettel der App)
        var snapshot = PortfolioWidgetSnapshot(total: 12_345.67, changeAmount: 182.4, changePercent: 1.5,
                                               currency: settings.portfolioCurrency,
                                               updatedAt: now, empty: false, totalUsdt: 13_890.12,
                                               positions: positions, otherPositions: 0, history: history,
                                               periodChanges: ["7": PortfolioWidgetChange(amount: 412.3, percent: 3.1)],
                                               unrealized: PortfolioWidgetChange(amount: 2_140.5, percent: 18.2))
        snapshot.weekDisplay = PortfolioWidgetChange(amount: 366.4, percent: 3.1)
        snapshot.unrealizedDisplay = PortfolioWidgetChange(amount: 1_902.3, percent: 18.2)
        var entry = PortfolioEntry(date: Date(), snapshot: snapshot, locked: false, accent: settings.accentColor,
                                   priceColors: settings.priceColorScheme, highContrast: settings.highContrast,
                                   priceColorsInverted: settings.priceColorsInverted)
        entry.apply(config)
        // Beispiel ohne Tagesverlauf: in der Galerie immer 24 h (kein Hinweis «7T: Portfolio öffnen»)
        entry.range = .day
        entry.isSample = true
        return entry
    }
}

struct PortfolioProvider: AppIntentTimelineProvider {
    typealias Entry = PortfolioEntry
    typealias Intent = PortfolioWidgetIntent

    func placeholder(in context: Context) -> PortfolioEntry {
        .sample()
    }

    func snapshot(for configuration: PortfolioWidgetIntent, in context: Context) async -> PortfolioEntry {
        var entry = PortfolioEntry.current(configuration)
        let empty = entry.snapshot?.empty ?? true
        // Galerie: leer, aus oder mit Portfolio-Sperre das Beispiel (keine echten Werte, also auch gesperrt unbedenklich)
        if context.isPreview && (empty || entry.locked || !entry.portfolioEnabled) {
            return .sample(configuration)
        }
        entry.loadLogos()
        return entry
    }

    func timeline(for configuration: PortfolioWidgetIntent, in context: Context) async -> Timeline<PortfolioEntry> {
        // Alter Stand: selbst nachrechnen (Kurse 60 s zwischengespeichert), damit
        // das Widget nicht vom Öffnen der App abhängt. Gesperrt oder Portfolio aus: nichts holen.
        let loaded = SharedStorage.loadSettingsChecked()
        let settings = loaded.settings
        let snapshot = PortfolioWidgetStore.load()
        let age = TimeUtils.nowMillis - (snapshot?.updatedAt ?? 0)
        var timedOut = false
        if settings.portfolioEnabled && !PortfolioLockPolicy.widgetLocked(lockSetting: loaded.appLockAssumed)
            && age >= WidgetRefresh.staleAfterMillis {
            // Kurse (bis 20 s), zwei Devisenquellen und Kerzen nacheinander können bei langsamem Netz über 40 s
            // brauchen — dann verwirft WidgetKit die Timeline. Höchstens `refreshDeadlineMillis` warten, sonst mit
            // dem gespeicherten Stand zeichnen und bald neu versuchen (wie Merkliste und Puls).
            let finished = await RefreshDeadline.run(within: Self.refreshDeadlineMillis) { () async -> Bool in
                await PortfolioWidgetStore.refresh(reload: false)
                return true
            }
            timedOut = finished == nil
        }
        var entry = PortfolioEntry.current(configuration)
        entry.loadLogos()
        // Zweiter Eintrag, sobald die Momentaufnahme veraltet («veraltet · 06:42»)
        var times: [Int64] = []
        if entry.state == .values, let current = entry.snapshot { times = [current.updatedAt] }
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        let reload = timedOut ? WidgetData.reload(kind: PortfolioWidgetStore.kind, wantsQuickRetry: true)
                              : WidgetData.nextReload
        return Timeline(entries: entries, policy: .after(reload))
    }

    /// Zeitgrenze für das Nachrechnen beim Zeichnen (ms).
    static let refreshDeadlineMillis: Int64 = 10_000
}

// MARK: Widget

struct PortfolioWidget: Widget {
    var body: some WidgetConfiguration {
        // Gleiche `kind` wie früher (StaticConfiguration): abgelegte Widgets bleiben und
        // erhalten die Standardwerte der Einrichtung.
        AppIntentConfiguration(kind: PortfolioWidgetStore.kind,
                               intent: PortfolioWidgetIntent.self,
                               provider: PortfolioProvider()) { entry in
            PortfolioWidgetView(entry: entry)
        }
        .configurationDisplayName(L("widget_portfolio_name"))
        .description(L("widget_portfolio_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular])
        .contentMarginsDisabled()
    }
}

// MARK: Ansicht

struct PortfolioWidgetView: View {
    let entry: PortfolioEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        switch family {
        case .accessoryRectangular:
            PortfolioRectangularView(entry: entry.lockScreen)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(PortfolioWidgetText.appURL)
        default:
            WidgetThemed(theme: entry.theme, accent: entry.accent, priceColors: entry.priceColors,
                         highContrast: entry.highContrast, inverted: entry.priceColorsInverted) { palette in
                PortfolioHomeView(entry: entry, palette: palette, family: family)
                    .widgetURL(PortfolioWidgetText.appURL)
                    .environment(\.widgetLogos, entry.logos)
                    .environment(\.widgetLogosEnabled, entry.showLogos)
            }
        }
    }
}
