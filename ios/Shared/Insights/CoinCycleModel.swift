import Foundation

/// Kursdaten eines beliebigen Coins (in USDT), aufbereitet für das Modell.
struct CoinInputs: Equatable, Sendable {
    var price: Double
    var sma50d: Double?
    var sma200d: Double?
    var sma111d: Double?
    var sma350d: Double?
    var price30dAgo: Double?
    var sma200w: Double?
    var ath: Double?
    var athDate: LocalDay?
    var rsiDaily: Double?
    var rsiWeekly: Double?
    /// Veränderung des Kurses gegenüber Bitcoin in 90 Tagen, in Prozent.
    var vsBtc90d: Double?
    /// Wie viele Tage Kursverlauf vorliegen.
    var historyDays: Int
}

enum CoinSignalId: String, Sendable, Codable {
    case MAYER, MA200W, DRAWDOWN, RSI_WEEKLY, RSI_DAILY, PI_CYCLE, PARABOLIC, CROSS, VS_BTC
}

/// Ein Signal; `value` nil heisst: zu wenig Daten für diesen Indikator.
struct CoinSignal: Equatable, Sendable, Codable {
    let id: CoinSignalId
    let value: String?
    var topPoints: Int = 0
    var bottomPoints: Int = 0
}

struct CoinReport: Equatable, Sendable, Codable {
    let symbol: String
    let price: Double
    let zone: MarketZone
    let index: Int
    let topScore: Int
    let bottomScore: Int
    let signals: [CoinSignal]
    let historyDays: Int
}

/// Zonenmodell für beliebige Coins — nur aus dem Kursverlauf — wie `CoinCycleModel.kt`.
///
/// Top-Score                                  Bottom-Score
///  Mayer ≥2,0 +1 · ≥2,4 +2                    Mayer ≤0,8 +1 · ≤0,6 +2
///  200W-Multiple ≥3,5 +1                      Kurs ≤ 200-Wochen-Schnitt +2
///  Wochen-RSI ≥70 +1 · ≥80 +2                 Rückgang vom Hoch ≥50 % +1 · ≥70 % +2
///  Tages-RSI ≥80 +1                           Wochen-RSI ≤40 +1 · ≤30 +2
///  Pi-Cycle (111T ≥ 2×350T) +2                Tages-RSI ≤25 +1
///  +60 % in 30 Tagen +1
/// Trend (50/200 Tage) und Stärke gegenüber BTC fliessen nur als Richtung ein.
enum CoinCycleModel {

    static func evaluate(symbol: String, input: CoinInputs) -> CoinReport {
        var signals: [CoinSignal] = []

        var mayer: Double? = nil
        if let s = input.sma200d, s > 0 { mayer = input.price / s }
        signals.append(CoinSignal(
            id: .MAYER, value: mayer.map { Indicators.fmt("%.2f", $0) },
            topPoints: mayer.map { $0 >= 2.4 ? 2 : $0 >= 2.0 ? 1 : 0 } ?? 0,
            bottomPoints: mayer.map { $0 <= 0.6 ? 2 : $0 <= 0.8 ? 1 : 0 } ?? 0
        ))

        var mult200w: Double? = nil
        if let s = input.sma200w, s > 0 { mult200w = input.price / s }
        signals.append(CoinSignal(
            id: .MA200W, value: mult200w.map { Indicators.fmt("%.2f×", $0) },
            topPoints: (mult200w ?? 0) >= 3.5 ? 1 : 0,
            bottomPoints: (mult200w.map { $0 <= 1.0 } ?? false) ? 2 : 0
        ))

        var drawdown: Double? = nil
        if let ath = input.ath, ath > 0 { drawdown = max(0.0, (1.0 - input.price / ath) * 100.0) }
        signals.append(CoinSignal(
            id: .DRAWDOWN, value: drawdown.map { Indicators.fmt("−%.0f %%", $0) },
            bottomPoints: drawdown.map { $0 >= 70 ? 2 : $0 >= 50 ? 1 : 0 } ?? 0
        ))

        let rw = input.rsiWeekly
        signals.append(CoinSignal(
            id: .RSI_WEEKLY, value: rw.map { Indicators.fmt("%.0f", $0) },
            topPoints: rw.map { $0 >= 80 ? 2 : $0 >= 70 ? 1 : 0 } ?? 0,
            bottomPoints: rw.map { $0 <= 30 ? 2 : $0 <= 40 ? 1 : 0 } ?? 0
        ))

        let rd = input.rsiDaily
        signals.append(CoinSignal(
            id: .RSI_DAILY, value: rd.map { Indicators.fmt("%.0f", $0) },
            topPoints: (rd ?? 0) >= 80 ? 1 : 0,
            bottomPoints: (rd.map { $0 <= 25 } ?? false) ? 1 : 0
        ))

        var pi: Double? = nil
        if let s111 = input.sma111d, let s350 = input.sma350d, s350 > 0 { pi = s111 / (2 * s350) }
        signals.append(CoinSignal(
            id: .PI_CYCLE, value: pi.map { Indicators.fmt("%.2f", $0) },
            topPoints: (pi ?? 0) >= 1.0 ? 2 : 0
        ))

        var gain30: Double? = nil
        if let p30 = input.price30dAgo, p30 > 0 { gain30 = (input.price / p30 - 1.0) * 100.0 }
        signals.append(CoinSignal(
            id: .PARABOLIC, value: gain30.map { Indicators.fmt("%+.0f %%", $0) },
            topPoints: (gain30 ?? 0) >= 60.0 ? 1 : 0
        ))

        var golden: Bool? = nil
        if let s50 = input.sma50d, let s200 = input.sma200d { golden = s50 >= s200 }
        signals.append(CoinSignal(id: .CROSS, value: golden.map { $0 ? "Golden Cross" : "Death Cross" }))

        signals.append(CoinSignal(id: .VS_BTC, value: input.vsBtc90d.map { Indicators.fmt("%+.0f %%", $0) }))

        let top = min(signals.reduce(0) { $0 + $1.topPoints }, 10)
        let bottom = min(signals.reduce(0) { $0 + $1.bottomPoints }, 10)
        // Trend: über dem 200-Tage-Schnitt bzw. Golden Cross
        let uptrend: Bool?
        if let m = mayer, let g = golden {
            uptrend = m >= 1.0 && g
        } else if let m = mayer {
            uptrend = m >= 1.0
        } else {
            uptrend = golden
        }

        return CoinReport(
            symbol: symbol,
            price: input.price,
            zone: ZoneRules.zone(top: top, bottom: bottom, uptrend: uptrend),
            index: ZoneRules.index(top: top, bottom: bottom, uptrend: uptrend),
            topScore: top,
            bottomScore: bottom,
            signals: signals,
            historyDays: input.historyDays
        )
    }
}
