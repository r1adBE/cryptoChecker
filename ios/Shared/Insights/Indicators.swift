import Foundation

/// Kleine Kennzahlen-Helfer auf Schlusskursen (älteste zuerst) — wie `Indicators.kt`.
enum Indicators {

    /// Tagesschluss am Tag `day` (Tage seit 1970-01-01, UTC).
    struct DayClose: Equatable, Sendable {
        let day: Int
        let close: Double
    }

    /// Grösste Lücke, die ein Schluss «am oder vor» einem Tag überbrücken darf (Wochenende, fehlende Kerzen einer Quelle).
    static let maxGapDays = 3

    /// Schluss am Tag `day` oder am letzten Tag davor, höchstens `maxGapDays` früher; sonst (oder nicht positiv) nil.
    /// `series` älteste zuerst. Kennzahlen «vor n Tagen» zählen so Tage, nicht Kerzen — wie Android.
    static func closeOnOrBefore(_ series: [DayClose], day: Int) -> Double? {
        guard let hit = series.last(where: { $0.day <= day }), day - hit.day <= maxGapDays,
              hit.close.isFinite, hit.close > 0 else { return nil }
        return hit.close
    }

    /// Veränderung (Faktor − 1) vom Schluss `days` Tage vor dem letzten Tag der Reihe bis zu ihm, nach Datum; sonst nil.
    static func changeOverDays(_ series: [DayClose], days: Int) -> Double? {
        guard let last = series.last, last.close.isFinite, last.close > 0,
              let first = closeOnOrBefore(series, day: last.day - days) else { return nil }
        return last.close / first - 1.0
    }

    /// Stärke gegen eine Vergleichsreihe in % über `days` Tage, beide am SELBEN Datum — «heute» = letzter Tag des Coins
    /// (BTC an diesem Tag), «damals» = `days` Tage davor; fehlt einer der vier Schlüsse, nil — wie Android.
    static func relativeChangePercent(coin: [DayClose], btc: [DayClose], days: Int) -> Double? {
        guard let end = coin.last, end.close.isFinite, end.close > 0,
              let c0 = closeOnOrBefore(coin, day: end.day - days),
              let b1 = closeOnOrBefore(btc, day: end.day),
              let b0 = closeOnOrBefore(btc, day: end.day - days) else { return nil }
        return ((end.close / c0) / (b1 / b0) - 1.0) * 100.0
    }

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
