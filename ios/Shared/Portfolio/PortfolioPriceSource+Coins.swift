import Foundation
import os

// Coin-Liste für die Suche (Teil von `PortfolioPriceSource`) — wie `PortfolioPriceSourceCoins.kt` (Android).

extension PortfolioPriceSource {
    /// Herkunft der gemerkten Coin-Liste: `sourceBinance` (exchangeInfo, Paarliste «Binance») oder `sourceOther`.
    static let keyCoinsSource = "portfolio_coin_list_source"
    static let sourceBinance = "binance"
    static let sourceOther = "other"
    /// Von Binance einzeln mit 400 abgelehnte Coins so lange nicht wieder in die Sammelabfrage (6 Std.).
    static let unlistedRetryMillis: Int64 = 6 * 60 * 60_000

    static func knownCoins() -> [String]? {
        state.withLock { $0.coinList }
    }

    private static func setKnownCoins(_ list: [String]) {
        state.withLock { $0.coinList = list }
    }

    /// Alle wählbaren Coins (alphabetisch); leer, wenn keine Quelle antwortet.
    /// `fallback`: Paarliste der Börse «Binance» aus der App (nil = keine).
    static func coins(fallback: (@Sendable () async -> [String]?)? = nil) async -> [String] {
        await coinGate.run { await Self.loadCoins(fallback: fallback) }
    }

    private static func loadCoins(fallback: (@Sendable () async -> [String]?)?) async -> [String] {
        let defaults = SharedStorage.defaults
        let storedAt = Int64(defaults.double(forKey: keyCoinsAt))
        let age = EpochTime.nowMillis - storedAt
        let fresh = age >= 0 && age < coinsTtlMillis
        if let list = knownCoins(), fresh { return list }

        let stored = (defaults.string(forKey: keyCoins) ?? "")
            .split(separator: ",").map(String.init).filter { !$0.isEmpty }
        if !stored.isEmpty && fresh {
            setKnownCoins(stored)
            return stored
        }

        var loaded = await binanceCoins()
        if loaded == nil, let fallback {
            let list = await fallback()
            if let list, !list.isEmpty { loaded = list }
        }
        // Herkunft für den Filter der Sammelabfrage (`binanceCoinList`): Coinbase führt z. B. kein IOTA
        let fromBinance = loaded != nil
        if loaded == nil { loaded = await coinbaseCoins() }

        if let loaded, !loaded.isEmpty {
            let list = Array(Set(loaded + [stable])).sorted()
            defaults.set(list.joined(separator: ","), forKey: keyCoins)
            defaults.set(Double(EpochTime.nowMillis), forKey: keyCoinsAt)
            defaults.set(fromBinance ? sourceBinance : sourceOther, forKey: keyCoinsSource)
            setKnownCoins(list)
            return list
        }
        // Lieber eine ältere Liste als gar keine
        if !stored.isEmpty { setKnownCoins(stored) }
        return stored
    }

    /// Coin-Liste für den Filter der Binance-Sammelabfrage, ohne Netz: nur wenn sie von Binance stammt (exchangeInfo
    /// oder Paarliste «Binance», `keyCoinsSource`) und höchstens `coinsTtlMillis` alt ist — aus dem Arbeitsspeicher, sonst
    /// aus `SharedStorage.defaults`. Sonst nil: ungefiltert (unbekannte Symbole findet das Halbieren bei HTTP 400).
    static func binanceCoinList() -> Set<String>? {
        let defaults = SharedStorage.defaults
        guard defaults.string(forKey: keyCoinsSource) == sourceBinance else { return nil }
        let age = EpochTime.nowMillis - Int64(defaults.double(forKey: keyCoinsAt))
        guard age >= 0, age < coinsTtlMillis else { return nil }
        let list = knownCoins() ?? (defaults.string(forKey: keyCoins) ?? "")
            .split(separator: ",").map(String.init).filter { !$0.isEmpty }
        return list.isEmpty ? nil : Set(list)
    }

    private static func binanceCoins() async -> [String]? {
        let url = "https://\(binanceHost)/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false"
        guard let body = await get(url, host: binanceHost, session: ExchangeHTTP.bulkSession),
              let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let symbols = root["symbols"] as? [Any]
        else { return nil }
        let list: [String] = symbols.compactMap { item in
            guard let s = item as? [String: Any],
                  s["quoteAsset"] as? String == stable,
                  s["status"] as? String == "TRADING",
                  let base = s["baseAsset"] as? String
            else { return nil }
            let symbol = base.uppercased()
            return isSymbol(symbol) ? symbol : nil
        }
        return list.isEmpty ? nil : list
    }

    private static func coinbaseCoins() async -> [String]? {
        guard let body = await get("https://\(coinbaseHost)/products", host: coinbaseHost, session: ExchangeHTTP.bulkSession),
              let data = body.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return nil }
        let list: [String] = array.compactMap { item in
            guard let p = item as? [String: Any],
                  p["quote_currency"] as? String == "USD",
                  (p["trading_disabled"] as? Bool) != true,
                  let base = p["base_currency"] as? String
            else { return nil }
            let symbol = base.uppercased()
            return isSymbol(symbol) ? symbol : nil
        }
        return list.isEmpty ? nil : list
    }
}
