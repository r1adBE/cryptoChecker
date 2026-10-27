import Foundation

/// Ein Punkt einer Vergleichslinie: Zeit (Epoch-ms) und Änderung in % seit Beginn des Zeitraums.
struct ComparePoint: Equatable, Sendable {
    let time: Int64
    let percent: Double
}

/// Kennzahlen einer Zeile der Vergleichstabelle in %; nil = nicht berechenbar (zu wenig Kerzen, kein Kurs).
struct CompareFigures: Equatable, Sendable {
    let change7d: Double?
    let change30d: Double?
    let fromHigh30d: Double?
}

/// Achsen des Vergleichs-Charts: Zeitspanne und %-Spanne (enthält immer 0).
struct CompareBounds: Equatable, Sendable {
    let minTime: Int64
    let maxTime: Int64
    let minPercent: Double
    let maxPercent: Double
}

/// Vergleichsansicht (Merkliste › markieren › «Vergleichen», Wunsch Inhaber 27.10.2026): 2 bis 4 Paare, eine Linie
/// je Paar in % ab Beginn des Zeitraums, darunter Kurs, 24 h, 7 T, 30 T und Abstand zum 30-Tage-Hoch. Nur Kerzen, die
/// die App fürs Aktionsblatt ohnehin lädt. Wie Android `CompareMath` (Paritäts-Fixture `compare.json`).
enum CompareMath {
    static let minPairs = 2
    static let maxPairs = 4
    private static let dayMillis: Int64 = 86_400_000

    /// Lässt sich mit `count` markierten Paaren vergleichen?
    static func canCompare(_ count: Int) -> Bool { (minPairs...maxPairs).contains(count) }

    /// Linie in % ab dem ersten gültigen Schlusskurs (endlich, > 0); ungültige Schlüsse fallen weg. Weniger als
    /// zwei Punkte → leer (keine Linie).
    static func normalized(_ candles: [MarketCandle]) -> [ComparePoint] {
        let valid = candles.filter { $0.close.isFinite && $0.close > 0 }.sorted { $0.openTime < $1.openTime }
        guard valid.count >= 2, let base = valid.first?.close else { return [] }
        return valid.map { ComparePoint(time: $0.openTime, percent: ($0.close / base - 1) * 100) }
    }

    /// Kennzahlen aus Tageskerzen und dem aktuellen Kurs zur Zeit `now`: 7 T bzw. 30 T = Kurs gegen den Schluss der
    /// letzten Tageskerze, die spätestens vor 7 bzw. 30 Tagen begann; Abstand zum Hoch = Kurs gegen das höchste Hoch
    /// der Kerzen der letzten 30 Tage (samt Kurs selbst, also ≤ 0).
    static func figures(_ daily: [MarketCandle], price: Double?, now: Int64) -> CompareFigures {
        guard let p = price, p.isFinite, p > 0 else { return CompareFigures(change7d: nil, change30d: nil, fromHigh30d: nil) }
        let sorted = daily.filter { $0.close.isFinite && $0.close > 0 }.sorted { $0.openTime < $1.openTime }
        func changeSince(_ days: Int64) -> Double? {
            let cutoff = now - days * dayMillis
            guard let ref = sorted.last(where: { $0.openTime <= cutoff })?.close else { return nil }
            return (p / ref - 1) * 100
        }
        let windowStart = now - 30 * dayMillis
        let highs = sorted.filter { $0.openTime >= windowStart }.map(\.high).filter { $0.isFinite && $0 > 0 }
        let high = highs.max().map { max($0, p) }
        return CompareFigures(change7d: changeSince(7), change30d: changeSince(30), fromHigh30d: high.map { (p / $0 - 1) * 100 })
    }

    /// Achsen über alle Linien; 0 % immer sichtbar. nil ohne Punkte.
    static func bounds(_ series: [[ComparePoint]]) -> CompareBounds? {
        let all = series.flatMap { $0 }
        guard let minT = all.map(\.time).min(), let maxT = all.map(\.time).max(),
              let lo = all.map(\.percent).min(), let hi = all.map(\.percent).max() else { return nil }
        let minP = min(0, lo)
        let maxP = max(0, hi)
        return CompareBounds(minTime: minT, maxTime: maxT, minPercent: minP, maxPercent: maxP > minP ? maxP : minP + 1)
    }

    /// Letzter Wert einer Linie (Änderung über den ganzen Zeitraum); nil = keine Linie.
    static func last(_ series: [ComparePoint]) -> Double? { series.last?.percent }
}
