import Foundation

/// Ein Teil der «Aufteilung»: Coin (`coin` nil = «Andere», alle übrigen zusammen), Wert in USDT
/// und Anteil am Gesamtwert (0–100) — wie `AllocationSlice` (Android).
struct AllocationSlice: Equatable, Sendable {
    let coin: String?
    let valueUsd: Double
    let sharePercent: Double

    var isOther: Bool { coin == nil }
}

/// Eine der «Grössten Bewegungen»: Wertänderung der Position in USDT über die gewählte %-Basis
/// (Bestand × Kursänderung) und die Kursänderung in Prozent — wie `PortfolioMover` (Android).
struct PortfolioMover: Equatable, Sendable, Identifiable {
    let coin: String
    let changeUsd: Double
    let changePercent: Double

    var id: String { coin }
}

/// Reine Regeln für «Aufteilung», «Grösste Bewegungen» und «Beträge verbergen» — Spiegel von
/// `PortfolioInsights.kt` (getestet in PortfolioInsightsTests).
enum PortfolioInsights {
    /// So viele Coins zeigt die Aufteilung einzeln, der Rest wird zu «Andere».
    static let allocationTop = 4
    /// So viele Positionen zeigt «Grösste Bewegungen».
    static let moversTop = 3
    /// Platzhalter für verborgene Beträge.
    static let hidden = "•••"

    /// `text` oder `hidden`, wenn Beträge verborgen sind.
    static func mask(_ text: String, hidden isHidden: Bool) -> String { isHidden ? hidden : text }

    /// Aufteilung nach Wert: die `top` wertvollsten Coins (absteigend, gleicher Wert nach Kürzel),
    /// alle übrigen zusammen als «Andere» (nur, wenn es mehr als `top` gibt und sie etwas wert sind).
    /// Coins ohne Kurs oder Wert zählen nicht mit; leer, wenn nichts einen Wert hat.
    static func allocation(_ open: [CoinPosition], top: Int = allocationTop) -> [AllocationSlice] {
        var priced: [(coin: String, value: Double)] = []
        for p in open {
            if let v = p.value, v.isFinite, v > 0 { priced.append((p.coin, v)) }
        }
        priced.sort { $0.value != $1.value ? $0.value > $1.value : $0.coin < $1.coin }
        let total = priced.reduce(0) { $0 + $1.value }
        guard !priced.isEmpty, total > 0 else { return [] }
        let count = max(1, top)
        var slices = priced.prefix(count).map { AllocationSlice(coin: $0.coin, valueUsd: $0.value, sharePercent: share($0.value, total)) }
        let rest = priced.dropFirst(count).reduce(0) { $0 + $1.value }
        if priced.count > count && rest > 0 {
            slices.append(AllocationSlice(coin: nil, valueUsd: rest, sharePercent: share(rest, total)))
        }
        return slices
    }

    private static func share(_ value: Double, _ total: Double) -> Double {
        min(100, max(0, value / total * 100))
    }

    /// Wertänderung bei heutigem Wert `valueNow` und Kursänderung `percent` %: damals = Wert / (1 + p/100);
    /// nil bei ungültigen Werten (oder −100 % und weniger).
    static func valueChange(_ valueNow: Double, percent: Double) -> Double? {
        guard valueNow.isFinite, percent.isFinite, percent > -100 else { return nil }
        let then = valueNow / (1 + percent / 100)
        let change = valueNow - then
        return change.isFinite ? change : nil
    }

    /// Die `top` Positionen mit der grössten Wertänderung (Betrag, gleich gross nach Kürzel) aus der
    /// Kursänderung je Coin `changes`. Ohne Kurs, ohne Veränderung oder ohne Bewegung (unter einem
    /// halben Cent) erscheint ein Coin nicht.
    static func movers(_ open: [CoinPosition], changes: [String: Double], top: Int = moversTop) -> [PortfolioMover] {
        var out: [PortfolioMover] = []
        for p in open {
            guard let value = p.value, value.isFinite, value > 0,
                  let percent = changes[p.coin] ?? changes[p.coin.uppercased()],
                  let change = valueChange(value, percent: percent), abs(change) >= 0.005 else { continue }
            out.append(PortfolioMover(coin: p.coin, changeUsd: change, changePercent: percent))
        }
        out.sort { abs($0.changeUsd) != abs($1.changeUsd) ? abs($0.changeUsd) > abs($1.changeUsd) : $0.coin < $1.coin }
        return Array(out.prefix(max(0, top)))
    }
}
