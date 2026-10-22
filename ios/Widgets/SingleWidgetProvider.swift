import AppIntents
import SwiftUI
import WidgetKit

// Daten des Einzel-Widgets: Eintrag der Zeitleiste (`SingleEntry`) und der Anbieter (`SingleProvider`), der
// veraltete Kurse auffrischt, die Kerzen holt und Galerie-Beispiele liefert.
// Eigene Datei, weil das Laden der Daten von den Ansichten getrennt lesbar sein soll.

// MARK: Daten

struct SingleEntry: TimelineEntry {
    var date: Date
    /// nil = noch kein Paar gewählt (oder gelöscht).
    let watch: Watch?
    /// Kerzen (OHLC) im gewählten Zeitraum; nil = nicht verfügbar (Chart ausgeblendet).
    let candles: [MarketCandle]?
    /// Zeitraum des Charts (Kurzform neben der Uhrzeit).
    let range: WidgetChartRangeOption
    /// Kerzen oder Linie.
    let chartType: WidgetChartTypeOption
    let theme: WidgetThemeOption
    /// Werte aus den App-Einstellungen: beim Erstellen des Eintrags EINMAL dekodiert (seit Build 103; vorher je Feld
    /// und bei jedem Zeichnen erneut).
    let accent: AccentColor
    /// Kursfarben aus der App-Einstellung.
    var priceColors: PriceColorScheme
    /// Einstellung «Hoher Kontrast».
    var highContrast: Bool
    /// Einstellung «Farben tauschen».
    var priceColorsInverted: Bool
    /// Ab diesem Alter «veraltet» (`WidgetOutdated`), gemessen an `date`.
    var outdatedAfter: Int64 = WidgetOutdated.afterMillis
    /// «Basis der %-Änderung» und Stempel der gespeicherten Werte (beim Erstellen gelesen).
    var changeBasis: ChangeBasis
    var changeStamp: ChangeStamp? = SharedStorage.changeStamp
    /// Einstellung «Zeitraum zur Änderung zeigen».
    var showPeriod: Bool
    /// Coin-Logo des Paares (`WidgetLogos`); leer = Initialen.
    var logos: [String: UIImage] = [:]
    /// Schalter «Coin-Logos in Widgets»; aus = keine Plakette.
    var showLogos: Bool
    /// Kerzen einer anderen Börse (Ausweich-Kette): deren Name für «Kraken · Chart: Binance»; nil = eigene Börse.
    var foreignChart: String? = nil

    init(date: Date, watch: Watch?, candles: [MarketCandle]?, range: WidgetChartRangeOption,
         chartType: WidgetChartTypeOption, theme: WidgetThemeOption,
         settings: AppSettings = SharedStorage.loadSettings()) {
        self.date = date
        self.watch = watch
        self.candles = candles
        self.range = range
        self.chartType = chartType
        self.theme = theme
        accent = settings.accentColor
        priceColors = settings.priceColorScheme
        highContrast = settings.highContrast
        priceColorsInverted = settings.priceColorsInverted
        changeBasis = settings.changeBasis
        showPeriod = settings.showChangePeriod
        showLogos = settings.widgetCoinLogos
    }

    /// %-Basis zum Zeitpunkt des Eintrags: nach Mitternacht bzw. mit anderer Basis «—».
    var changeView: ChangeView {
        ChangeView.of(stamp: changeStamp, basis: changeBasis, now: Int64(date.timeIntervalSince1970 * 1000),
                      showPeriod: showPeriod)
    }

    /// Das Paar, wie es gezeigt wird: Veränderung nur, wenn sie zur %-Basis passt.
    var shownWatch: Watch? {
        guard var w = watch else { return nil }
        w.change24h = changeView.shown(w)
        return w
    }

    /// Kurs des Paares am Datum des Eintrags veraltet?
    var outdated: Bool {
        guard let watch else { return false }
        return WidgetOutdated.isOutdated(watch, at: date, afterMillis: outdatedAfter)
    }

    static func sample(theme: WidgetThemeOption = .system, range: WidgetChartRangeOption = .day,
                       chartType: WidgetChartTypeOption = .candles) -> SingleEntry {
        SingleEntry(date: Date(), watch: WidgetData.sampleWatches.first,
                    candles: WidgetData.sampleCandles(range: range), range: range, chartType: chartType,
                    theme: theme)
    }
}

struct SingleProvider: AppIntentTimelineProvider {
    typealias Entry = SingleEntry
    typealias Intent = SingleWidgetIntent

    func placeholder(in context: Context) -> SingleEntry {
        .sample()
    }

    func snapshot(for configuration: SingleWidgetIntent, in context: Context) async -> SingleEntry {
        let watch = Self.watch(for: configuration)
        // Galerie: ohne Paar, ohne Kurs oder ohne Chart (z. B. offline) das Beispiel (BTC mit Kerzen)
        // statt eines leeren Widgets; Beispielkerzen nie mit einem echten Paar mischen.
        let sample = SingleEntry.sample(theme: configuration.theme, range: configuration.range,
                                        chartType: configuration.chartType)
        if context.isPreview, watch?.lastPrice == nil {
            return sample
        }
        let real = await self.entry(for: configuration, watch: watch)
        if context.isPreview, real.candles == nil {
            return sample
        }
        return real
    }

    func timeline(for configuration: SingleWidgetIntent, in context: Context) async -> Timeline<SingleEntry> {
        let initial = Self.watch(for: configuration)
        // Kurse und Chart gleichzeitig holen (nach einem misslungenen Versuch erst nach
        // `WidgetRefresh.retryAfterMillis` wieder; die Merkliste nur nach gespeicherten Kursen neu zeichnen).
        async let refreshed: Void = WidgetRefresh.refreshIfStale(ownKind: WidgetData.singleKind,
                                                                 otherKinds: [WidgetData.watchlistKind])
        async let chart: Sourced<[MarketCandle]>? = Self.candles(for: initial, range: configuration.range)
        _ = await refreshed
        let sourced = await chart
        let candles: [MarketCandle]? = sourced?.value
        let watch = Self.watch(for: configuration)
        // Erst diese Aktualisierung kann «nicht mehr gehandelt» ergeben haben: dann kein Chart
        // Deaktiviert (auch eben erst): kein Chart
        var entry = SingleEntry(date: Date(), watch: watch,
                                candles: watch?.isNotTraded == false && watch?.disabled == false ? candles : nil,
                                range: configuration.range, chartType: configuration.chartType,
                                theme: configuration.theme)
        entry.foreignChart = Self.foreignChart(watch, sourced)
        entry.logos = await WidgetLogos.load(for: watch.map { [$0] } ?? [])
        // Zweiter Eintrag, sobald der Kurs veraltet («veraltet · 06:42»)
        var times: [Int64] = []
        // Abruf und (verschoben) letzter Handel laut Börse — Regel der App (`WidgetFreshness.outdatedTimes`)
        if let watch, !watch.disabled, !ConnectionErrors.isNotTraded(watch.lastError) {
            times = WidgetFreshness.outdatedTimes(watch, afterMillis: entry.outdatedAfter)
        }
        let entries = WidgetOutdated.entries(entry, date: entry.date, times: times, afterMillis: entry.outdatedAfter) { date in
            var later = entry
            later.date = date
            return later
        }
        // Normaler Takt; zählt im Tageszähler mit (`WidgetCadence.dailyReloadSoftCap`)
        return Timeline(entries: entries, policy: .after(WidgetData.reload(kind: WidgetData.singleKind, wantsQuickRetry: false)))
    }

    private func entry(for configuration: SingleWidgetIntent, watch: Watch?) async -> SingleEntry {
        let sourced = await Self.candles(for: watch, range: configuration.range)
        var entry = SingleEntry(date: Date(), watch: watch, candles: sourced?.value, range: configuration.range,
                                chartType: configuration.chartType,
                                theme: configuration.theme)
        entry.foreignChart = Self.foreignChart(watch, sourced)
        entry.logos = await WidgetLogos.load(for: watch.map { [$0] } ?? [])
        return entry
    }

    private static func watch(for configuration: SingleWidgetIntent) -> Watch? {
        guard let id = configuration.watch?.watchId else { return nil }
        return SharedStorage.loadSnapshot().watches.first { $0.id == id }
    }

    /// Börse der Kerzen, falls nicht die des Paars (wie Android `SingleWidgetRenderer`, `DayChange.foreignCandleSource`).
    private static func foreignChart(_ watch: Watch?, _ sourced: Sourced<[MarketCandle]>?) -> String? {
        guard let watch, let sourced else { return nil }
        return DayChange.foreignCandleSource(markets: [watch.marketKey, watch.marketName], provider: sourced.provider)
    }

    private static func candles(for watch: Watch?, range: WidgetChartRangeOption) async -> Sourced<[MarketCandle]>? {
        // Nicht mehr gehandelt bzw. deaktiviert: kein Chart (keine Abfrage, nur alte Daten)
        guard let watch, !watch.isNotTraded, !watch.disabled else { return nil }
        // Eigene Börse zuerst; nur Kerzen desselben Tokens (Kurs des Paars ±25 %), sonst kein Chart
        return await WidgetSparkline.candles(for: watch, range: range)
    }
}
