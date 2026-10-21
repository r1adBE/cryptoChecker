import Foundation

/// Satz «Portfolio-Alarme pausiert: …» der Alarm-Übersicht (`PortfolioAlarmPause`) — wie Android `PortfolioPauseText`.
/// Höchstens `maxCoins` Coin- bzw. Adressnamen, dann «…»; `hideCoins` (Portfolio gesperrt oder «Beträge verbergen») ohne
/// Namen, die Zusammensetzung des Bestands bleibt verborgen.
enum PortfolioPauseText {
    static let maxCoins = 3

    static func text(_ pause: PortfolioAlarmPause, hideCoins: Bool) -> String {
        switch pause {
        case .addressesPending:
            return L("portfolio_alarms_paused_addresses")
        case .noPrice(let list):
            guard let names = coins(list, hideCoins: hideCoins) else { return L("portfolio_alarms_paused_price_generic") }
            return L("portfolio_alarms_paused_price", names)
        // Adressnamen wie Coins: verborgen ohne Namen
        case .tokensStale(let list):
            guard let names = coins(list, hideCoins: hideCoins) else { return L("portfolio_alarms_paused_tokens_generic") }
            return L("portfolio_alarms_paused_tokens", names)
        case .addressesFailing(let list):
            guard let names = coins(list, hideCoins: hideCoins) else { return L("portfolio_alarms_address_failing_generic") }
            return L("portfolio_alarms_address_failing", names)
        }
    }

    /// «BTC, ETH, SOL …»; leer oder verborgen = nil.
    static func coins(_ coins: [String], hideCoins: Bool) -> String? {
        guard !hideCoins, !coins.isEmpty else { return nil }
        let shown = coins.prefix(maxCoins).joined(separator: ", ")
        return coins.count > maxCoins ? shown + " …" : shown
    }
}
