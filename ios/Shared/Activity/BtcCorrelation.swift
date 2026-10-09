import Foundation

/// Wie eng ein Coin gerade mit Bitcoin läuft — ein optionaler Satz im «Warum?»-Blatt.
enum BtcLink: String, Sendable {
    /// «SOL läuft derzeit eng mit Bitcoin.»
    case tight
    /// «SOL bewegt sich derzeit unabhängig von Bitcoin.»
    case independent
}

/// Korrelation der stündlichen Log-Renditen eines Paars mit Bitcoin (Pearson) über die letzten
/// 24–72 Stunden — aus den Kerzen, die «Warum?» ohnehin lädt (Paar und BTCUSDT als Markt-
/// Vergleich), also ohne zusätzliche Abfrage. Nur abgeschlossene Stunden, nur Stunden, die in
/// beiden Reihen vorkommen, nur Renditen über genau eine Stunde. Wie `BtcCorrelation.kt`.
enum BtcCorrelation {
    /// Mindestens so viele gemeinsame Stunden-Renditen, sonst kein Satz.
    static let minPoints = 24
    /// Höchstens so viele (die jüngsten).
    static let maxPoints = 72
    /// Ab hier «läuft eng mit Bitcoin».
    static let tight = 0.8
    /// Bis hier «bewegt sich unabhängig von Bitcoin».
    static let independent = 0.3

    private static let hourMillis: Int64 = 3_600_000

    /// Pearson-Korrelation der Stunden-Log-Renditen; nil für Bitcoin selbst, ohne eine der Reihen
    /// oder mit weniger als `minPoints` gemeinsamen Renditen bzw. ohne Schwankung. Die letzte
    /// Kerze jeder Reihe gilt als laufende Stunde und bleibt weg.
    static func correlation(base: String, candles: [MarketCandle]?, btc: [MarketCandle]?) -> Double? {
        if base.trimmingCharacters(in: .whitespaces).uppercased() == "BTC" { return nil }
        guard let candles, let btc else { return nil }
        let coin = returns(candles)
        let ref = returns(btc)
        let times = Array(coin.keys.filter { ref[$0] != nil }.sorted().suffix(maxPoints))
        guard times.count >= minPoints else { return nil }
        return pearson(times.compactMap { coin[$0] }, times.compactMap { ref[$0] })
    }

    /// Einordnung: ≥ `tight` eng, ≤ `independent` unabhängig, dazwischen (oder nil) kein Satz.
    static func link(_ correlation: Double?) -> BtcLink? {
        guard let correlation, correlation.isFinite else { return nil }
        if correlation >= tight { return .tight }
        if correlation <= independent { return .independent }
        return nil
    }

    static func link(base: String, candles: [MarketCandle]?, btc: [MarketCandle]?) -> BtcLink? {
        link(correlation(base: base, candles: candles, btc: btc))
    }

    /// Startzeit der Stunde → ln(Schluss / Schluss der Stunde davor); ohne die laufende Kerze.
    private static func returns(_ candles: [MarketCandle]) -> [Int64: Double] {
        let closed = Array(candles.dropLast())
        var out: [Int64: Double] = [:]
        guard closed.count >= 2 else { return out }
        for i in 1..<closed.count {
            let previous = closed[i - 1]
            let current = closed[i]
            guard current.openTime - previous.openTime == hourMillis,
                  previous.close > 0, current.close > 0 else { continue }
            let r = log(current.close / previous.close)
            if r.isFinite { out[current.openTime] = r }
        }
        return out
    }

    /// Pearson; nil bei weniger als zwei Werten oder ohne Streuung in einer der Reihen.
    static func pearson(_ xs: [Double], _ ys: [Double]) -> Double? {
        let n = min(xs.count, ys.count)
        guard n >= 2 else { return nil }
        let mx = xs.prefix(n).reduce(0, +) / Double(n)
        let my = ys.prefix(n).reduce(0, +) / Double(n)
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for i in 0..<n {
            let dx = xs[i] - mx
            let dy = ys[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        guard sxx > 0, syy > 0 else { return nil }
        let r = sxy / (sxx * syy).squareRoot()
        guard r.isFinite else { return nil }
        return min(1, max(-1, r))
    }
}
