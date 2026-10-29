import Foundation

/// Kleine Kennzahlen-Helfer auf Schlusskursen (älteste zuerst) — wie `Indicators.kt`.
enum Indicators {

    static func smaOfLast(_ values: [Double], _ n: Int) -> Double? {
        guard n > 0, values.count >= n else { return nil }
        return values.suffix(n).reduce(0, +) / Double(n)
    }

    /// RSI nach Wilder über `period` Schritte; nil bei zu wenig Daten. Ohne jede Bewegung 50, nur Gewinne 100.
    static func rsi(_ closes: [Double], period: Int = 14) -> Double? {
        guard period > 0, closes.count > period else { return nil }
        var gain = 0.0
        var loss = 0.0
        for i in 1...period {
            let d = closes[i] - closes[i - 1]
            if d >= 0 { gain += d } else { loss -= d }
        }
        var avgGain = gain / Double(period)
        var avgLoss = loss / Double(period)
        if period + 1 < closes.count {
            for i in (period + 1)..<closes.count {
                let d = closes[i] - closes[i - 1]
                avgGain = (avgGain * Double(period - 1) + max(d, 0)) / Double(period)
                avgLoss = (avgLoss * Double(period - 1) + max(-d, 0)) / Double(period)
            }
        }
        // Keine Bewegung (flache Kurse): neutral 50; nur Gewinne: 100 — wie Android
        if avgLoss == 0 { return avgGain == 0 ? 50 : 100 }
        let rs = avgGain / avgLoss
        return 100 - 100 / (1 + rs)
    }
}
