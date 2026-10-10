import Foundation

/// Darstellung des Wertverlaufs bei einer Umrechnungswährung (Umschalter «CHF | USDT | Vergleich»):
/// `currency` = in der gewählten Währung (Standard, wie bisher), `usdt` = unumgerechnet,
/// `compare` = beide Kurven als Veränderung in Prozent — wie `PortfolioHistoryView` (Android).
/// Nur auf diesem Gerät, nicht in der Sicherung.
enum PortfolioHistoryView: String, CaseIterable, Identifiable, Codable, Sendable {
    case currency
    case usdt
    case compare

    var id: String { rawValue }
}

/// Vergleich zweier Verläufe in Prozent seit dem Ausgangspunkt (beide beginnen bei 0 %):
/// `epochDays`, `currency` und `usdt` gleich lang und aufsteigend nach Tag, mindestens zwei Punkte —
/// wie `PortfolioCompareSeries` (Android).
struct PortfolioCompareSeries: Equatable, Sendable {
    let epochDays: [Int]
    /// Veränderung in der gewählten Währung in Prozent je Tag.
    let currency: [Double]
    /// Veränderung in USDT in Prozent je Tag.
    let usdt: [Double]

    var lastIndex: Int { epochDays.count - 1 }

    /// Währungseffekt am Tag `index` (Währung minus USDT, in Prozentpunkten); nil ausserhalb.
    func effect(at index: Int) -> Double? {
        guard currency.indices.contains(index), usdt.indices.contains(index) else { return nil }
        return PortfolioCompare.effect(currencyPercent: currency[index], usdtPercent: usdt[index])
    }

    /// Währungseffekt am letzten Tag.
    var effect: Double {
        PortfolioCompare.effect(currencyPercent: currency.last ?? 0, usdtPercent: usdt.last ?? 0)
    }
}

/// Reine Rechnung zum Vergleich «in USDT +12 %, in CHF nur +7 %» — Spiegel von `PortfolioCompare.kt`:
///  - Ausgangspunkt = erster Tag, an dem BEIDE Werte grösser als 0 sind (bei «Seit 1. Kauf» kann
///    das Portfolio am Anfang leer sein); die Tage davor fallen weg.
///  - Prozent je Tag = (Wert / Ausgangswert − 1) × 100, je Reihe mit ihrem eigenen Ausgangswert.
///  - Währungseffekt = Prozent in der Währung minus Prozent in USDT.
///  - Kein Ausgangspunkt, weniger als zwei Punkte ab dort oder Reihen, die nicht Tag für Tag
///    zusammenpassen: kein Vergleich (nil) — der Umschalter blendet «Vergleich» dann aus.
enum PortfolioCompare {

    private static func positive(_ v: Double) -> Bool { v.isFinite && v > PortfolioCalculator.eps }

    /// Index des Ausgangspunkts (beide Werte > 0); nil, wenn es keinen gibt.
    static func baseIndex(currency: [Double], usdt: [Double]) -> Int? {
        let n = min(currency.count, usdt.count)
        for i in 0..<n where positive(currency[i]) && positive(usdt[i]) {
            return i
        }
        return nil
    }

    /// Veränderung in Prozent gegenüber `base`.
    static func percent(_ value: Double, base: Double) -> Double { (value / base - 1) * 100 }

    /// Währungseffekt in Prozentpunkten: Veränderung in der Währung minus Veränderung in USDT.
    static func effect(currencyPercent: Double, usdtPercent: Double) -> Double { currencyPercent - usdtPercent }

    /// Vergleich aus dem umgerechneten Verlauf `currency` und dem unumgerechneten `usdt`
    /// (gleiche Tage in gleicher Reihenfolge); nil ohne sinnvollen Vergleich.
    static func build(currency: [PortfolioHistoryPoint], usdt: [PortfolioHistoryPoint]) -> PortfolioCompareSeries? {
        guard currency.count == usdt.count, currency.count >= 2 else { return nil }
        for i in currency.indices where currency[i].epochDay != usdt[i].epochDay {
            return nil
        }
        guard let base = baseIndex(currency: currency.map(\.value), usdt: usdt.map(\.value)),
              base < currency.count - 1 else { return nil }
        let baseCurrency = currency[base].value
        let baseUsdt = usdt[base].value
        var days: [Int] = []
        var c: [Double] = []
        var u: [Double] = []
        days.reserveCapacity(currency.count - base)
        c.reserveCapacity(currency.count - base)
        u.reserveCapacity(currency.count - base)
        for i in base..<currency.count {
            let cv = percent(currency[i].value, base: baseCurrency)
            let uv = percent(usdt[i].value, base: baseUsdt)
            // Ungültige Werte (sollte nicht vorkommen) machen den Vergleich wertlos
            guard cv.isFinite, uv.isFinite else { return nil }
            days.append(currency[i].epochDay)
            c.append(cv)
            u.append(uv)
        }
        return PortfolioCompareSeries(epochDays: days, currency: c, usdt: u)
    }

    /// Gemeinsame Skala beider Kurven (Tief, Hoch) in Prozent; 0 % liegt immer darin.
    static func bounds(_ series: PortfolioCompareSeries) -> (lo: Double, hi: Double) {
        var lo = 0.0
        var hi = 0.0
        for v in series.currency + series.usdt {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        return (lo: lo, hi: hi)
    }

    /// Welche Darstellung gilt: `saved` nur, wenn sie möglich ist — USDT braucht den unumgerechneten
    /// Verlauf mit Tageskursen (`usdtAvailable`), der Vergleich zusätzlich einen Ausgangspunkt
    /// (`compareAvailable`); sonst in der Währung (wie bisher).
    static func effectiveView(_ saved: PortfolioHistoryView, usdtAvailable: Bool,
                              compareAvailable: Bool) -> PortfolioHistoryView {
        if !usdtAvailable { return .currency }
        if saved == .compare && !compareAvailable { return .currency }
        return saved
    }
}
