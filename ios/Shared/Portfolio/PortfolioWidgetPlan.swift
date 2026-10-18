import Foundation

// Anzeige, Zeitraum und Daten des Portfolio-Widgets — Spiegel der Modelle aus `PortfolioWidgetChart.kt` (Android).
// Regeln des Charts in PortfolioWidgetChartRules.swift, Geometrie in PortfolioWidgetChartGeometry.swift, Ansichten
// «Übersicht»/«Liste» in PortfolioWidgetViews.swift, Währung hinter Kurs und Betrag in WidgetQuoteText.swift.
// Ohne Widget-Typen, damit App, Widget und Tests sie teilen.

/// Anzeige je Widget — wie `PortfolioDisplay` (Android): nur USDT, nur Umrechnung, Umrechnung + «≈ … USDT».
enum PortfolioWidgetDisplay: String, Sendable, CaseIterable {
    case usdt, conversion, conversionUsdt

    /// Ohne Wahl: «Umrechnung» — auch für USD (USDT zum Marktkurs USDT/USD); ist die Umrechnungswährung ein
    /// USD-Stablecoin, «Nur USDT». Wie Android `PortfolioDisplay.defaultFor`.
    static func defaultFor(currency: String) -> PortfolioWidgetDisplay {
        PortfolioWidgetChart.collapsesToUsdt(currency) ? .usdt : .conversion
    }
}

/// Zeitraum für Veränderung und Chart — wie `PortfolioChartRange` (Android).
enum PortfolioWidgetRange: String, Sendable, CaseIterable {
    case day, week, month, year

    var days: Int {
        switch self {
        case .day: 1
        case .week: 7
        case .month: 30
        case .year: 365
        }
    }

    /// Aus dem Tagesverlauf (statt aus den Stundenwerten)?
    var daily: Bool { self != .day }

    /// Zeiträume mit Tagesverlauf (Tage): 7, 30, 365.
    static var periodDays: [Int] { allCases.filter(\.daily).map(\.days) }

    /// Kurzform «24h» / «7T» / «30T» / «1J».
    var shortLabelKey: String {
        switch self {
        case .day: "widget_range_short_24h"
        case .week: "widget_range_short_7d"
        case .month: "widget_range_short_30d"
        case .year: "widget_range_short_1y"
        }
    }

    /// Langform «24h» / «7 Tage» / «30 Tage» / «1 Jahr» (VoiceOver).
    var labelKey: String {
        switch self {
        case .day: "widget_range_24h"
        case .week: "widget_range_7d"
        case .month: "widget_range_30d"
        case .year: "widget_range_1y"
        }
    }
}

/// Ein Tag des Widget-Tagesverlaufs (USDT) — speicherbare Form von `PortfolioHistoryPoint`.
struct PortfolioWidgetDay: Codable, Equatable, Sendable {
    var day: Int
    var value: Double
    var open: Double?
    var high: Double?
    var low: Double?

    init(_ p: PortfolioHistoryPoint) {
        day = p.epochDay
        value = p.value
        open = p.open
        high = p.high
        low = p.low
    }

    var point: PortfolioHistoryPoint {
        PortfolioHistoryPoint(epochDay: day, value: value, open: open, high: high, low: low)
    }
}

/// Veränderung eines Zeitraums in USDT (speicherbar); `percent` nil ohne Ausgangswert.
struct PortfolioWidgetChange: Codable, Equatable, Sendable {
    var amount: Double
    var percent: Double?
    /// Coins, die in der Zahl fehlen (kein Marktkurs) — «7 T ohne …» wie die App; nil = keiner (älterer Stand).
    var skipped: [String]? = nil
}

/// Ein Balken des Portfolio-Charts über `start`...`end` (ms) — wie `ChartBar` (Android). Ein Punkt
/// der Linie hat `start` = `end` und vier gleiche Werte.
struct PortfolioChartBar: Equatable, Sendable {
    let start: Int64
    let end: Int64
    let open: Double
    let high: Double
    let low: Double
    let close: Double

    var up: Bool { close >= open }

    static func point(_ time: Int64, _ value: Double) -> PortfolioChartBar {
        PortfolioChartBar(start: time, end: time, open: value, high: value, low: value, close: value)
    }
}

/// Was das Widget zeigt — wie `PortfolioChartPlan` (Android).
struct PortfolioChartPlan: Equatable, Sendable {
    /// Wirksamer Zeitraum (bei `fallback` 24 h statt des gewählten `requested`).
    let range: PortfolioWidgetRange
    let requested: PortfolioWidgetRange
    let fallback: Bool
    let type: PriceChartType
    /// Leer = «Verlauf folgt».
    let bars: [PortfolioChartBar]
    /// Senkrechtes Gitter wie im geteilten Chart (`PriceChartRange`: Stunde, Tag, Woche, Monat).
    let grid: PriceChartRange
    /// Feste Zeitachse (nur Tages-Basis über 24 h).
    let axisFrom: Int64?
    let axisTo: Int64?
    /// Veränderung in der Anzeige-Einheit; nil = unbekannt.
    let changeAmount: Double?
    let changePercent: Double?

    var hasChart: Bool { bars.count >= 2 }
}

/// Zuletzt bekannter Devisenkurs USD → `currency` mit Abrufzeit (ms), gemerkt neben dem Widget-Stand — wie
/// `PortfolioFxMemo` (Android).
struct PortfolioFxMemo: Codable, Equatable, Sendable {
    var currency: String
    var rate: Double
    var fetchedAt: Int64
}

/// Tagesverlauf (USDT) und Veränderung je Zeitraum für den Widget-Stand.
struct PortfolioWidgetDailyData: Equatable, Sendable {
    var points: [PortfolioHistoryPoint]
    var changes: [Int: PortfolioChange]

    static let empty = PortfolioWidgetDailyData(points: [], changes: [:])
}

// MARK: Tageskurse für das Widget

/// Tageskurse eines Coins (USDT) in der App Group — Spiegel dessen, was der Wertverlauf der App
/// (`PortfolioHistorySource`) geladen hat; das Widget liest sie, ohne selbst abzufragen.
struct PortfolioWidgetDailyCoin: Codable, Equatable, Sendable {
    var closes: [Int: Double]
    var ranges: [Int: PortfolioDayRange]?
    /// Reihe von der eigenen Börse (`PortfolioExchangeRoute.historyCloses`); nil = Binance.
    var own: Bool? = nil
    /// Erster eigener Tag, davor Binance angeglichen (`PortfolioExchangeRoute.stitch`); nil = keiner.
    var binanceBefore: Int? = nil
}

/// Tageskurse für 7 T / 30 T / 1 J im Widget. Android liest dafür direkt den Zwischenspeicher des
/// Wertverlaufs (`PortfolioHistorySource.cachedDailyPrices`); iOS spiegelt ihn in eine Datei der App
/// Group (`portfolio_widget_daily.json`), weil die Widget-Erweiterung den App-Zwischenspeicher nicht
/// sieht. Nur Geräte-Daten: vom Backup ausgenommen.
enum PortfolioWidgetDailyStore {
    /// So viele Tage vor heute bleiben je Coin (1 J + Puffer für den Vortag).
    static let keepDays = 400

    static var url: URL { SharedStorage.directory.appendingPathComponent("portfolio_widget_daily.json") }

    static func load() -> [String: PortfolioWidgetDailyCoin] {
        guard let data = try? Data(contentsOf: url),
              let map = try? JSONDecoder().decode([String: PortfolioWidgetDailyCoin].self, from: data)
        else { return [:] }
        return map
    }

    /// Neue Kurse übernehmen (je Coin ersetzt), andere Coins behalten; Tage älter als `keepDays` fallen weg.
    static func merge(_ stored: [String: PortfolioWidgetDailyCoin], closes: [String: [Int: Double]],
                      ranges: [String: [Int: PortfolioDayRange]], todayUtcDay: Int,
                      own: Set<String> = [], binanceBefore: [String: Int] = [:]) -> [String: PortfolioWidgetDailyCoin] {
        let from = todayUtcDay - keepDays
        var out = stored
        for (coin, byDay) in closes where !byDay.isEmpty {
            let kept = byDay.filter { $0.key >= from && $0.value > 0 && $0.value.isFinite }
            let keptRanges = ranges[coin]?.filter { $0.key >= from && $0.value.valid }
            out[coin] = PortfolioWidgetDailyCoin(closes: kept, ranges: keptRanges?.isEmpty == false ? keptRanges : nil,
                                                 own: own.contains(coin) ? true : nil,
                                                 binanceBefore: own.contains(coin) ? binanceBefore[coin] : nil)
        }
        // Coins ohne Kurs im ganzen Fenster nicht mitschleppen
        return out.filter { _, coin in coin.closes.keys.contains { $0 >= from } }
    }

    /// Vom Wertverlauf der App nach jedem Laden aufgerufen; Fehler bleiben still.
    static func save(closes: [String: [Int: Double]], ranges: [String: [Int: PortfolioDayRange]],
                     own: Set<String> = [], binanceBefore: [String: Int] = [:],
                     now: Int64 = TimeUtils.nowMillis) {
        guard !closes.isEmpty else { return }
        let merged = merge(load(), closes: closes, ranges: ranges,
                           todayUtcDay: PortfolioHistory.epochDay(utcMillis: now),
                           own: own, binanceBefore: binanceBefore)
        guard let data = try? JSONEncoder().encode(merged) else { return }
        do {
            try data.write(to: url, options: [.atomic])
            BackupExclusion.set(excluded: true, for: url)
        } catch {
            return
        }
    }

    /// Schlüsse und Hoch/Tief je Coin für `dailyData`.
    static func prices(_ map: [String: PortfolioWidgetDailyCoin]) -> (closes: [String: [Int: Double]],
                                                                    ranges: [String: [Int: PortfolioDayRange]]) {
        var closes: [String: [Int: Double]] = [:]
        var ranges: [String: [Int: PortfolioDayRange]] = [:]
        for (coin, entry) in map {
            closes[coin] = entry.closes
            if let r = entry.ranges, !r.isEmpty { ranges[coin] = r }
        }
        return (closes, ranges)
    }

    /// Coins mit Reihe der eigenen Börse (für `PortfolioExchangeRoute.historyCloses`).
    static func own(_ map: [String: PortfolioWidgetDailyCoin]) -> Set<String> {
        Set(map.filter { $0.value.own == true }.keys)
    }
}

// MARK: Ansicht je Widget

/// Ansicht je Widget — wie `PortfolioWidgetView` (Android; hier anders benannt, weil `PortfolioWidgetView` die SwiftUI-Ansicht des Widgets ist): Übersicht (wie die Gesamtkarte im
/// Portfolio-Tab), Chart (bisher die einzige Ansicht, deshalb Standard) oder Liste der Positionen.
enum PortfolioWidgetMode: String, Sendable, CaseIterable {
    case overview, chart, list

    /// Neue und bestehende Widgets ohne Wahl: Chart wie bisher.
    static let standard: PortfolioWidgetMode = .chart

    /// Gespeicherter Name → Ansicht; unbekannt oder fehlend = `standard`.
    static func from(_ name: String?) -> PortfolioWidgetMode {
        name.flatMap { PortfolioWidgetMode(rawValue: $0) } ?? standard
    }

    /// Chart-Art (Kerzen / Linie) gilt nur für den Chart.
    var usesChartType: Bool { self == .chart }

    /// Zeitraum (24 h / 7 T / 30 T / 1 J) gilt nicht für die Liste (Einrichtung blendet ihn aus, die Wahl bleibt gespeichert).
    var usesRange: Bool { self != .list }
}
