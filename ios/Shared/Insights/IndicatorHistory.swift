import Foundation

/// Einordnung der Bitcoin-Kennzahlen in ihren eigenen Verlauf — wie `IndicatorHistoryMath` (Android, dort
/// getestet). Nur Rechnung, keine Prognose: Perzentil seit Juni 2016 (BTC-Tagesschluss von Coin Metrics über den
/// Daten-Spiegel) und der Wert in 30 Tagen bei gleichem Kurs. Nur Kennzahlen aus dem Kurs; On-Chain-Werte haben
/// in der App nur rund 14 Monate Verlauf.
enum IndicatorHistoryMath {
    /// Höchster Tagesschluss vor Juni 2016 (Nov. 2013, Coin Metrics).
    static let athBefore2016 = 1_151.0
    static let flatDays = 30
    static let minDays = 365

    static func compute(closes: [(day: LocalDay, price: Double)], current: [IndicatorId: Double?],
                        price: Double) -> IndicatorHistory? {
        let clean = closes.filter { $0.price > 0 && $0.price.isFinite }
        guard clean.count >= 400, price > 0 else { return nil }
        let values = clean.map(\.price)
        let n = values.count
        var prefix = [Double](repeating: 0, count: n + 1)
        for i in 0..<n { prefix[i + 1] = prefix[i] + values[i] }
        func sma(_ end: Int, _ len: Int) -> Double? {
            end + 1 >= len ? (prefix[end + 1] - prefix[end + 1 - len]) / Double(len) : nil
        }
        var mayer: [Double] = [], ath: [Double] = [], change: [Double] = [], pi: [Double] = []
        var runningMax = athBefore2016
        var firstDay: LocalDay? = nil
        for i in 0..<n {
            let p = values[i]
            runningMax = max(runningMax, p)
            ath.append((1 - p / runningMax) * 100)
            if let s = sma(i, 200) {
                mayer.append(p / s)
                if firstDay == nil { firstDay = clean[i].day }
            }
            if i >= 30 { change.append((p / values[i - 30] - 1) * 100) }
            if let s111 = sma(i, 111), let s350 = sma(i, 350) { pi.append((s111 / (2 * s350) - 1) * 100) }
        }
        guard let since = firstDay else { return nil }
        var stats: [IndicatorId: IndicatorStat] = [:]
        func put(_ id: IndicatorId, _ series: [Double], _ flat: Double?) {
            guard let value = current[id] ?? nil else { return }
            stats[id] = IndicatorStat(percentileBelow: percentileBelow(series, value), flat30: flat)
        }
        put(.MAYER, mayer, flatMayer(values, price: price))
        put(.ATH_DISTANCE, ath, nil)
        put(.CHANGE_30D, change, nil)
        put(.PI_CYCLE_GAP, pi, flatPiGap(values, price: price))
        return IndicatorHistory(since: since, stats: stats)
    }

    static func percentileBelow(_ series: [Double], _ value: Double) -> Double? {
        let usable = series.filter(\.isFinite)
        guard usable.count >= minDays, value.isFinite else { return nil }
        return Double(usable.filter { $0 < value }.count) * 100 / Double(usable.count)
    }

    static func flatMayer(_ values: [Double], price: Double) -> Double? {
        flatSma(values, price: price, len: 200).map { price / $0 }
    }

    static func flatPiGap(_ values: [Double], price: Double) -> Double? {
        guard let s111 = flatSma(values, price: price, len: 111), let s350 = flatSma(values, price: price, len: 350) else { return nil }
        return (s111 / (2 * s350) - 1) * 100
    }

    private static func flatSma(_ values: [Double], price: Double, len: Int) -> Double? {
        let keep = len - flatDays
        guard keep >= 0, values.count >= keep else { return nil }
        return (values.suffix(keep).reduce(0, +) + Double(flatDays) * price) / Double(len)
    }
}
