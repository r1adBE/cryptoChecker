import Foundation

// Kerzen und Achsen des Wertverlaufs — wie `PortfolioChart.kt` (Android).

/// Wie viele Tage eine Portfolio-Kerze umfasst — wie `PortfolioCandleBucket` (Android).
enum PortfolioCandleBucket: Sendable {
    case day, week, month
}

/// Eine Kerze des Portfolio-Werts über die Tage `firstDay`...`lastDay` (Tage seit 1970-01-01, lokal).
/// Werte in der Einheit des Verlaufs (Währung oder USDT) — wie `PortfolioCandle` (Android).
struct PortfolioCandle: Equatable, Sendable {
    let firstDay: Int
    let lastDay: Int
    let open: Double
    let high: Double
    let low: Double
    let close: Double

    /// Steigend (Schluss ≥ Eröffnung) = Kursfarbe «steigend».
    var up: Bool { close >= open }
}

/// Kerzen aus dem täglichen Wertverlauf — Spiegel von `PortfolioCandles` (Android):
///  - Tageskerze: Eröffnung = Wert am Ende des Vortags (erster Tag: seine eigene Eröffnung aus den
///    Coin-Kerzen, sonst sein Schluss), Schluss = Wert am Tagesende — dieselbe Reihe wie die Linie
///    (samt Umrechnung mit dem Tageskurs und Stablecoin-Regel).
///  - Hoch/Tief: aus `PortfolioHistoryPoint.high`/`low` (Σ Bestand × Hoch bzw. Tief der Coins — eine
///    Schranke, siehe `PortfolioHistory.build`); immer so begrenzt, dass Tief ≤ min(Eröffnung, Schluss)
///    und Hoch ≥ max(Eröffnung, Schluss). Ohne diese Werte Hoch/Tief = max/min(Eröffnung, Schluss).
///  - Lange Zeiträume werden zusammengefasst (höchstens rund 90 Kerzen): bis `maxDailyDays` Tage
///    Tageskerzen, bis `maxWeeklyDays` Tage Wochenkerzen (ab Montag), darüber Monatskerzen. Die Linie
///    behält ihre Tagespunkte.
enum PortfolioCandles {
    /// Bis zu so vielen Tagen eine Kerze je Tag (30 T, 7 T).
    static let maxDailyDays = 92
    /// Bis zu so vielen Tagen Wochenkerzen (1 J = 53, höchstens 90); darüber Monatskerzen (5 J = 61).
    static let maxWeeklyDays = 630

    /// Zusammenfassung für einen Verlauf über `dayCount` Tage.
    static func bucket(dayCount: Int) -> PortfolioCandleBucket {
        if dayCount <= maxDailyDays { return .day }
        if dayCount <= maxWeeklyDays { return .week }
        return .month
    }

    /// Erster Tag der Gruppe von `day`: der Tag selbst, sein Montag oder der Monatserste.
    static func bucketStart(_ day: Int, bucket: PortfolioCandleBucket) -> Int {
        switch bucket {
        case .day:
            return day
        case .week:
            // 1970-01-01 war ein Donnerstag: Montag = Tag − (Tag + 3) mod 7
            let offset = ((day + 3) % 7 + 7) % 7
            return day - offset
        case .month:
            let d = LocalDay(epochDay: day)
            return LocalDay(d.year, d.month, 1).epochDay
        }
    }

    /// Eine Kerze je Punkt (aufsteigend nach Tag); leer ohne Punkte.
    static func daily(_ points: [PortfolioHistoryPoint]) -> [PortfolioCandle] {
        var out: [PortfolioCandle] = []
        out.reserveCapacity(points.count)
        for (i, p) in points.enumerated() {
            let close = p.value
            let open: Double
            if i == 0 {
                open = p.open.flatMap { $0.isFinite ? $0 : nil } ?? close
            } else {
                open = points[i - 1].value
            }
            let top = Swift.max(open, close)
            let bottom = Swift.min(open, close)
            let high = p.high.flatMap { $0.isFinite ? Swift.max($0, top) : nil } ?? top
            let low = p.low.flatMap { $0.isFinite ? Swift.min($0, bottom) : nil } ?? bottom
            out.append(PortfolioCandle(firstDay: p.epochDay, lastDay: p.epochDay, open: open, high: high,
                                       low: low, close: close))
        }
        return out
    }

    /// Fasst aufeinanderfolgende Kerzen derselben Gruppe zusammen: Eröffnung der ersten, Schluss der
    /// letzten, höchstes Hoch, tiefstes Tief.
    static func aggregate(_ candles: [PortfolioCandle], bucket: PortfolioCandleBucket) -> [PortfolioCandle] {
        guard bucket != .day else { return candles }
        var out: [PortfolioCandle] = []
        var current: PortfolioCandle?
        var currentKey = Int.min
        for c in candles {
            let key = bucketStart(c.firstDay, bucket: bucket)
            if let open = current, key == currentKey {
                current = PortfolioCandle(firstDay: open.firstDay, lastDay: c.lastDay, open: open.open,
                                          high: Swift.max(open.high, c.high), low: Swift.min(open.low, c.low),
                                          close: c.close)
            } else {
                if let open = current { out.append(open) }
                currentKey = key
                current = c
            }
        }
        if let current { out.append(current) }
        return out
    }

    /// Fertige Kerzen für den Verlauf `points`: Tageskerzen, je nach Länge zusammengefasst.
    static func build(_ points: [PortfolioHistoryPoint]) -> [PortfolioCandle] {
        guard let first = points.first, let last = points.last else { return [] }
        return aggregate(daily(points), bucket: bucket(dayCount: last.epochDay - first.epochDay + 1))
    }
}

/// Abstand der senkrechten Gitterlinien im Wertverlauf — wie `PortfolioGridUnit` (Android).
enum PortfolioGridUnit: Sendable {
    case day, week, month, quarter, year
}

/// Achsen und Lage im Wertverlauf-Chart — Spiegel von `PortfolioChartAxis` (Android). Wie im
/// Aktionsblatt eines Paars: drei waagrechte Stufen (Tief, Mitte, Hoch) mit Beschriftung rechts, feine
/// senkrechte Linien an Kalendergrenzen. Jeder Punkt bzw. jede Kerze belegt einen gleich breiten Slot.
enum PortfolioChartAxis {
    /// Unterteilung nach Länge: 7 T je Tag, 30 T je Woche, 1 J je Monat, länger je Quartal bzw. Jahr.
    static func gridUnit(dayCount: Int) -> PortfolioGridUnit {
        if dayCount <= 10 { return .day }
        if dayCount <= 62 { return .week }
        if dayCount <= 400 { return .month }
        if dayCount <= 1100 { return .quarter }
        return .year
    }

    /// Tage, an denen eine senkrechte Linie beginnt (Tagesbeginn, Montag, Monats-, Quartals- bzw.
    /// Jahreserster), strikt nach `firstDay` und höchstens `lastDay`.
    static func gridDays(firstDay: Int, lastDay: Int) -> [Int] {
        guard lastDay > firstDay else { return [] }
        let unit = gridUnit(dayCount: lastDay - firstDay + 1)
        switch unit {
        case .day:
            return Array((firstDay + 1)...lastDay)
        case .week:
            return Array(stride(from: PortfolioCandles.bucketStart(firstDay, bucket: .week) + 7, through: lastDay, by: 7))
        case .month, .quarter, .year:
            let step = unit == .quarter ? 3 : (unit == .year ? 12 : 1)
            let start = LocalDay(epochDay: firstDay)
            // Erster Monat der Unterteilung (Januar/April/Juli/Oktober bzw. Januar) bis zum Starttag
            var year = start.year
            var month = (start.month - 1) / step * step + 1
            var out: [Int] = []
            while true {
                let d = LocalDay(year, month, 1).epochDay
                if d > lastDay { break }
                if d > firstDay { out.append(d) }
                month += step
                while month > 12 {
                    month -= 12
                    year += 1
                }
            }
            return out
        }
    }

    /// Lage des Tagesbeginns `day` in Slots ab dem linken Rand: Slot i umfasst die Tage `spans[i]`
    /// (lückenlos aufsteigend); innerhalb eines Slots anteilig. nil, wenn kein Slot den Tag enthält.
    static func slotPosition(_ spans: [ClosedRange<Int>], day: Int) -> Double? {
        var lo = 0
        var hi = spans.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let span = spans[mid]
            if day < span.lowerBound {
                hi = mid - 1
            } else if day > span.upperBound {
                lo = mid + 1
            } else {
                let length = Double(span.upperBound - span.lowerBound + 1)
                return Double(mid) + Double(day - span.lowerBound) / length
            }
        }
        return nil
    }

    /// Index des Slots, der `day` enthält; nil ausserhalb.
    static func slotOfDay(_ spans: [ClosedRange<Int>], day: Int) -> Int? {
        slotPosition(spans, day: day).map { Int($0) }
    }

    /// Index des höchsten Werts (bei Gleichstand der erste); nil ohne gültige Werte.
    static func maxIndex(_ values: [Double]) -> Int? {
        var best: Int?
        for (i, v) in values.enumerated() where v.isFinite {
            if let b = best, v <= values[b] { continue }
            best = i
        }
        return best
    }

    /// Waagrechte Stufen (Tief, Mitte, Hoch) einer Skala von `low` bis `high`.
    static func levels(low: Double, high: Double) -> (low: Double, mid: Double, high: Double) {
        (low, (low + high) / 2, high)
    }

    /// Bereich des Gewinn/Verlust-Charts: schliesst 0 immer ein (Nulllinie sichtbar). Wie Android.
    static func pnlBounds(low: Double, high: Double) -> (low: Double, high: Double) {
        (Swift.min(low, 0), Swift.max(high, 0))
    }

    /// Skala der Kerzen: tiefstes Tief bis höchstes Hoch; nil ohne Kerzen.
    static func candleBounds(_ candles: [PortfolioCandle]) -> (low: Double, high: Double)? {
        guard let low = candles.map(\.low).min(), let high = candles.map(\.high).max() else { return nil }
        return (low, high)
    }
}
