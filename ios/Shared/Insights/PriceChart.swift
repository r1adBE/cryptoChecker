import SwiftUI

// Kurs-Chart des Einzel-Widgets, geteilt mit dem Aktionsblatt der App: reine Geometrie
// (`WidgetChartGeometry`) und das Zeichnen in einen `GraphicsContext` (`PriceChartRenderer`).
// Ohne App- oder Widget-Typen; Farben und Schriften kommen über `PriceChartStyle`.

/// Chart-Art — wie `WidgetChartType` (Android).
enum PriceChartType: String, Sendable, CaseIterable {
    case candles, line
}

/// Zeitraum des Charts: Kerzenintervall und Anzahl — wie `WidgetChartRange` (Android).
/// 24 h = 24 × 1 h, 7 Tage = 42 × 4 h, 30 Tage = 30 × 1 Tag; 1 Jahr = 365 × 1 Tag nur im
/// Aktionsblatt (`WidgetChartRangeOption` kennt es nicht).
enum PriceChartRange: String, Sendable, CaseIterable {
    case day, week, month, year

    var candleInterval: CandleInterval {
        switch self {
        case .day: .h1
        case .week: .h4
        case .month, .year: .d1
        }
    }

    var limit: Int {
        switch self {
        case .day: 24
        case .week: 42
        case .month: 30
        case .year: 365
        }
    }

    /// Dauer einer Kerze in ms (für die senkrechten Zeit-Linien).
    var candleMillis: Int64 {
        switch self {
        case .day: 3_600_000
        case .week: 4 * 3_600_000
        case .month, .year: 24 * 3_600_000
        }
    }

    /// «24h», «7 Tage», «30 Tage» (auch Screenreader).
    var labelKey: String {
        switch self {
        case .day: "widget_range_24h"
        case .week: "widget_range_7d"
        case .month: "widget_range_30d"
        case .year: "widget_range_1y"
        }
    }

    /// Kurzform, z. B. «7T».
    var shortLabelKey: String {
        switch self {
        case .day: "widget_range_short_24h"
        case .week: "widget_range_short_7d"
        case .month: "widget_range_short_30d"
        case .year: "widget_range_short_1y"
        }
    }
}

// MARK: Geometrie (ohne Zeichnen)

/// Reine Rechenregeln des Einzel-Widget-Charts — gleiche Regeln wie Android
/// (Preisstufen, Zeit-Linien, Etikett gegen Beschriftung, Kerzenfarbe).
enum WidgetChartGeometry {
    /// Untere und obere Preisstufe; die mittlere liegt dazwischen.
    struct Levels: Equatable {
        let low: Double
        let high: Double
        var mid: Double { (low + high) / 2 }
        /// Keine Spanne: alles auf einer Höhe.
        var flat: Bool { !(high > low) }
    }

    /// Nur Kerzen mit gültigen, positiven Werten.
    static func clean(_ candles: [MarketCandle]) -> [MarketCandle] {
        candles.filter { c in
            [c.open, c.high, c.low, c.close].allSatisfy { $0.isFinite && $0 > 0 }
        }
    }

    /// Kerzen des Einzel-Widgets nur bei gleichem Token: Die Reihe kommt über die Ausweich-Kette
    /// (Binance, Binance.US, Coinbase), oft von einer anderen Börse als das Paar. Gleiche Prüfung wie
    /// Aktionsblatt und Mini-Chart (`SheetChart.accept`, Reihe des Paars ohne Umrechnung): letzter
    /// Schluss höchstens `DayChange.maxPriceGap` vom Kurs `price` des Paars. Ohne Kurs nicht prüfbar
    /// → nil (kein Chart, wie `SheetChart.sparklineFor`). Gleich wie Android.
    static func pairCandles(_ candles: [MarketCandle]?, price: Double?) -> [MarketCandle]? {
        guard let p = price, p.isFinite, p > 0 else { return nil }
        let cleaned = clean(candles ?? [])
        guard cleaned.count >= 2, let last = cleaned.last?.close else { return nil }
        return abs(p / last - 1) > DayChange.maxPriceGap ? nil : cleaned
    }

    /// Steigende Kerze (Schluss ≥ Eröffnung) → Kursfarbe «steigend».
    static func isUp(_ candle: MarketCandle) -> Bool { candle.close >= candle.open }

    /// Höchster Wert der Kerze (auch bei unsauberen Daten nie unter Open/Close).
    static func top(_ c: MarketCandle) -> Double { Swift.max(c.high, c.open, c.close) }

    /// Tiefster Wert der Kerze (auch bei unsauberen Daten nie über Open/Close).
    static func bottom(_ c: MarketCandle) -> Double { Swift.min(c.low, c.open, c.close) }

    /// Mindesthöhe der Achse bei Stablecoins: ±0.5 % um die Mitte (1 % der Mitte), wie im Aktionsblatt.
    static let stableMinSpan = 0.01
    /// Mindesthöhe sonst: ±0.1 % — nur für extrem ruhige Reihen (Rauschen statt Absturz).
    static let minSpan = 0.002

    /// Preisstufen der Widget-Charts mit Mindesthöhe wie im Aktionsblatt: `levels`, aber mindestens
    /// `stableMinSpan` (Stablecoin) bzw. `minSpan` der Mitte hoch, um die Mitte gelegt — ein ruhiges
    /// Paar sieht so nicht wie ein Absturz aus. Wie `WidgetChartGeometry.axisLevels` (Android).
    static func axisLevels(_ candles: [MarketCandle], type: PriceChartType, stable: Bool = false) -> Levels? {
        guard let raw = Self.levels(candles, type: type) else { return nil }
        return widen(raw, minFraction: stable ? stableMinSpan : minSpan)
    }

    /// Spanne von `levels` auf mindestens `minFraction` × Mitte aufziehen (mittig); gleiche Regel wie
    /// `SheetChart.widen`.
    static func widen(_ levels: Levels, minFraction: Double) -> Levels {
        let mid = levels.mid
        guard mid.isFinite, mid > 0 else { return levels }
        let span = mid * minFraction
        if levels.high - levels.low >= span { return levels }
        return Levels(low: mid - span / 2, high: mid + span / 2)
    }

    /// Kerzen: tiefstes Low / höchstes High; Linie: tiefster / höchster Schluss.
    static func levels(_ candles: [MarketCandle], type: PriceChartType) -> Levels? {
        let lows: [Double]
        let highs: [Double]
        switch type {
        case .candles:
            lows = candles.map(bottom)
            highs = candles.map(top)
        case .line:
            lows = candles.map(\.close)
            highs = lows
        }
        guard let low = lows.min(), let high = highs.max() else { return nil }
        return Levels(low: low, high: high)
    }

    /// Höhe eines Preises zwischen `top` (oben, höchster Preis) und `bottom` (unten, tiefster Preis).
    static func y(_ value: Double, levels: Levels, top: CGFloat, bottom: CGFloat) -> CGFloat {
        guard !levels.flat else { return (top + bottom) / 2 }
        return bottom - CGFloat((value - levels.low) / (levels.high - levels.low)) * (bottom - top)
    }

    /// Zeitpunkte der senkrechten Linien (lokale Zeit) strikt nach `firstOpen` und vor `endMillis`:
    /// 24 h → jede volle Stunde, 7 Tage → jede Mitternacht, 30 Tage → jeder Montag 00:00,
    /// 1 Jahr → jeder Monatserste 00:00.
    static func gridTimes(firstOpen: Int64, endMillis: Int64, range: PriceChartRange,
                          timeZone: TimeZone = .current) -> [Int64] {
        guard endMillis > firstOpen else { return [] }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let startDate = Date(millis: firstOpen)
        var current: Date?
        let unit: Calendar.Component
        let step: Int
        switch range {
        case .day:
            current = calendar.dateInterval(of: .hour, for: startDate)?.start
            unit = .hour
            step = 1
        case .week:
            current = calendar.startOfDay(for: startDate)
            unit = .day
            step = 1
        case .month:
            // Erster Montag (gregorianisch: Wochentag 2) ab dem Starttag
            var day = calendar.startOfDay(for: startDate)
            for _ in 0..<7 where calendar.component(.weekday, from: day) != 2 {
                guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
                day = next
            }
            current = day
            unit = .day
            step = 7
        case .year:
            let comps = calendar.dateComponents([.year, .month], from: startDate)
            current = calendar.date(from: comps)
            unit = .month
            step = 1
        }
        var times: [Int64] = []
        while let date = current, date.millis < endMillis, times.count < 400 {
            if date.millis > firstOpen { times.append(date.millis) }
            guard let next = calendar.date(byAdding: unit, value: step, to: date), next > date else { break }
            current = next
        }
        return times
    }

    /// Waagrechte Lage eines Zeitpunkts: Kerze `i` belegt [left + i·slot, left + (i+1)·slot]
    /// für [open_i, open_i + Intervall). nil, wenn keine Kerze den Zeitpunkt enthält (Lücke).
    static func x(time: Int64, openTimes: [Int64], intervalMillis: Int64, left: CGFloat, slot: CGFloat) -> CGFloat? {
        guard intervalMillis > 0 else { return nil }
        for (i, open) in openTimes.enumerated() where time >= open && time < open + intervalMillis {
            let fraction = Double(time - open) / Double(intervalMillis)
            return left + (CGFloat(i) + CGFloat(fraction)) * slot
        }
        return nil
    }

    /// Überlappen sich zwei senkrecht zentrierte Kästen (mit Abstand `gap`)?
    static func overlaps(_ a: CGFloat, _ heightA: CGFloat, _ b: CGFloat, _ heightB: CGFloat, gap: CGFloat = 1) -> Bool {
        abs(a - b) < (heightA + heightB) / 2 + gap
    }

    /// Sichtbarkeit der drei Beschriftungen (Index 0 = unten, 1 = Mitte, 2 = oben), `centers` in
    /// gleicher Reihenfolge. Klein (`compact`) nur oben und unten; ohne Spanne nur die Mitte.
    /// Das Kurs-Etikett gewinnt: Eine Beschriftung, die es überdecken würde, entfällt.
    /// Beschriftungen überlappen sich nie (zuerst fällt die Mitte, dann unten).
    static func visibleLabels(centers: [CGFloat], labelHeight: CGFloat, tagCenter: CGFloat?, tagHeight: CGFloat,
                              compact: Bool, flat: Bool) -> [Bool] {
        guard centers.count == 3 else { return [false, false, false] }
        var visible = flat ? [false, true, false] : [true, !compact, true]
        if let tagCenter {
            for i in 0..<3 where visible[i] && overlaps(centers[i], labelHeight, tagCenter, tagHeight) {
                visible[i] = false
            }
        }
        if visible[1] {
            for j in [0, 2] where visible[j] && overlaps(centers[1], labelHeight, centers[j], labelHeight) {
                visible[1] = false
            }
        }
        if visible[0], visible[2], overlaps(centers[0], labelHeight, centers[2], labelHeight) {
            visible[0] = false
        }
        return visible
    }

    /// Kennzahlen des Screenreader-Satzes: Kerzen = erstes Open, letztes Close, höchstes High,
    /// tiefstes Low; Linie = Schlusskurse. nil bei weniger als zwei Kerzen.
    static func summary(_ candles: [MarketCandle], type: PriceChartType)
        -> (start: Double, end: Double, high: Double, low: Double)? {
        let data = clean(candles)
        guard data.count >= 2, let first = data.first, let last = data.last,
              let levels = Self.levels(data, type: type) else { return nil }
        switch type {
        case .candles: return (first.open, last.close, levels.high, levels.low)
        case .line: return (first.close, last.close, levels.high, levels.low)
        }
    }

    /// VoiceOver-Satz zum Chart: Zeitraum, Start, Ende, Veränderung, Hoch, Tief (in der Quote).
    /// `period`: Zeitraum im Satz; nil = der des Bereichs (z. B. bei «Heute» der %-Basis anders).
    static func accessibility(_ candles: [MarketCandle], type: PriceChartType, range: PriceChartRange,
                              quote: String, period: String? = nil) -> String {
        let format: (Double) -> String = { PriceFormat.priceWithCurrency($0, quote) }
        guard let s = Self.summary(candles, type: type) else { return L("a11y_chart_empty") }
        return A11y.chart(period: period ?? L(range.labelKey), first: s.start, last: s.end, high: s.high, low: s.low,
                          format: format)
    }
}

// MARK: Zeichnen

/// Farben und Schriften des Charts (Widget: `WidgetPalette`, App: Kursfarben und Akzent).
struct PriceChartStyle {
    let up: Color
    let down: Color
    let accent: Color
    /// Text auf der Akzentfarbe (Kurs-Etikett).
    let onAccent: Color
    /// Beschriftung; Linien in abgeschwächter Fassung davon.
    let secondary: Color
    /// Hoher Kontrast: Linien 35 %, Akzentlinie voll.
    let highContrast: Bool
    var labelFont: Font = .system(size: 8.5, weight: .medium).monospacedDigit()
    var tagFont: Font = .system(size: 8.5, weight: .semibold).monospacedDigit()
    /// Strichstärke der Linie (nur Linien-Chart).
    var lineWidth: CGFloat = 1.6
}

/// Lage der Zeichenfläche nach dem Zeichnen — für Markierung und Ziehen im Aktionsblatt.
struct PriceChartLayout: Equatable {
    let plotLeft: CGFloat
    let plotRight: CGFloat
    let top: CGFloat
    let bottom: CGFloat
    let count: Int
    let levels: WidgetChartGeometry.Levels

    var slot: CGFloat { count > 0 ? (plotRight - plotLeft) / CGFloat(count) : 0 }

    /// Mitte der Kerze `index`.
    func x(_ index: Int) -> CGFloat { plotLeft + (CGFloat(index) + 0.5) * slot }

    func y(_ value: Double) -> CGFloat { WidgetChartGeometry.y(value, levels: levels, top: top, bottom: bottom) }
}
