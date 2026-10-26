import Foundation

/// Kursdaten eines beliebigen Coins (in USDT), aufbereitet für die Kennzahlen.
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

/// Kennzahlen eines Coins in fester Reihenfolge (Werte nil = zu wenig Kursverlauf).
struct CoinReport: Equatable, Sendable, Codable {
    let symbol: String
    let price: Double
    let indicators: [Indicator]
    let historyDays: Int
}

/// Kennzahlen für beliebige Coins — nur aus dem Kursverlauf, weil On-Chain-Daten frei nur
/// für Bitcoin verfügbar sind. Reine Messwerte ohne Zonen, Scores oder Deutung, wie
/// `BitcoinIndicatorModel`. Wie `CoinIndicatorModel` in `CoinCycleModel.kt`.
enum CoinIndicatorModel {

    static func evaluate(symbol: String, input: CoinInputs) -> CoinReport {
        let price = input.price
        let indicators = [
            Indicator(id: .MAYER, value: IndicatorMath.ratio(price, input.sma200d)),
            Indicator(id: .MA200W, value: input.sma200w.flatMap { $0 > 0 ? $0 : nil }),
            Indicator(id: .ATH_DISTANCE, value: IndicatorMath.athDistance(price, input.ath)),
            Indicator(id: .CHANGE_30D, value: IndicatorMath.changePercent(price, input.price30dAgo)),
            Indicator(id: .SMA50_VS_200, value: IndicatorMath.changePercent(input.sma50d, input.sma200d)),
            Indicator(id: .RSI_WEEKLY, value: input.rsiWeekly.flatMap { $0.isFinite ? $0 : nil }),
            Indicator(id: .RSI_DAILY, value: input.rsiDaily.flatMap { $0.isFinite ? $0 : nil }),
            Indicator(id: .VS_BTC, value: input.vsBtc90d.flatMap { $0.isFinite ? $0 : nil }),
        ]
        return CoinReport(symbol: symbol, price: price, indicators: indicators, historyDays: input.historyDays)
    }
}
