import Foundation

// Abfragen ohne eigenen Zustand (Teil von `PortfolioPriceSource`): Kurse aus Merklisten-Paaren anderer Spot-Börsen,
// Binance-Sammelabfrage, Kerzenkurs und GET mit Sperre der Quelle — wie `PortfolioPriceSource.kt` und
// `PortfolioPriceSourceExchanges.kt` (Android). Zwischenspeicher und `lock` bleiben in `PortfolioPriceSource.swift`.

extension PortfolioPriceSource {
    /// Kurse aus Merklisten-Paaren für Coins, die Binance-Spot nicht führt (`PortfolioWatchPair`): aktives
    /// Spot-Paar desselben Coins an einer anderen Börse, Kurs über deren Adapter, in USDT umgerechnet.
    /// Abfragen gehen nur an Börsen, deren Paar der Nutzer selbst in der Merkliste hat — wie Android.
    static func watchPairPrices(_ coins: [String]) async -> [String: Double] {
        let watches = SharedStorage.loadSnapshot().watches
        guard !watches.isEmpty else { return [:] }
        let names = TokenIdentityStore.shared
        let candidates = watches.map { w in
            PortfolioWatchPair.Candidate(marketKey: w.marketKey, base: w.baseAsset, quote: w.quoteAsset, pairId: w.pairId,
                                         spot: w.contractType == .none, disabled: w.disabled, lastUpdate: w.lastUpdate,
                                         // Voller Name aus der Paarliste der Börse (gleicher Token, K-8)
                                         name: names.pairName(marketKey: w.marketKey, base: w.baseAsset))
        }
        var picks: [(String, PortfolioWatchPair.Candidate)] = []
        for coin in coins {
            if let pick = PortfolioWatchPair.pick(coin, watches: candidates) { picks.append((coin, pick)) }
        }
        guard !picks.isEmpty else { return [:] }
        return await withTaskGroup(of: (String, Double?).self, returning: [String: Double].self) { group in
            for (coin, pick) in picks {
                group.addTask { (coin, await Self.watchPairPrice(pick)) }
            }
            var out: [String: Double] = [:]
            for await (coin, price) in group { if let price { out[coin] = price } }
            return out
        }
    }

    /// Letzter Kurs des Merklisten-Paars `pick` in USDT; nil = Börse unbekannt, keine Antwort oder kein Devisenkurs.
    private static func watchPairPrice(_ pick: PortfolioWatchPair.Candidate) async -> Double? {
        guard let market = MarketsConfig.market(pick.marketKey) else { return nil }
        let info = MarketPair(pick.base, pick.quote, pick.pairId)
        guard let ticker = try? await MarketService.fetchTicker(market: market, info: info) else { return nil }
        let quote = pick.quote.uppercased()
        let rate: Double? = (quote == "USDT" || quote == "USD") ? nil : await FxRateSource.usdTo(quote)
        return PortfolioExchangeRoute.toUsdt(ticker.last, quote: quote,
                                             usdtUsd: DepegAlarmCheck.liveUsdtUsd() ?? 1) { _ in rate }
    }

    /// Sammelabfrage; Binance lehnt die ganze Anfrage ab, wenn ein Symbol unbekannt ist. `failed`: eine Abfrage scheiterte
    /// am Netz bzw. die Quelle ist gesperrt (für `PortfolioPriceIssue`).
    static func binanceBatch(_ coins: [String]) async -> (prices: [String: Double], failed: Bool) {
        if BlockedSources.isBlocked(binanceHost) { return ([:], true) }
        var failed = false
        // Bekannte Coins zuerst — unbekannte gehen direkt über die Kerzen-Kette.
        let known = knownCoins().map { Set($0) }
        let candidates: [String]
        if let known, !known.isEmpty {
            candidates = coins.filter { known.contains($0) }
        } else {
            candidates = coins
        }
        var result: [String: Double] = [:]
        var start = 0
        while start < candidates.count {
            let chunk = Array(candidates[start..<min(start + maxSymbols, candidates.count)])
            start += maxSymbols
            var bySymbol: [String: String] = [:]
            for coin in chunk { bySymbol[coin + stable] = coin }
            let symbols = "[" + chunk.map { "\"\($0)\(stable)\"" }.joined(separator: ",") + "]"
            guard let encoded = symbols.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { continue }
            let url = "https://\(binanceHost)/api/v3/ticker/price?symbols=\(encoded)"
            guard let body = await get(url, host: binanceHost, session: MarketHTTP.session) else {
                failed = true
                continue
            }
            guard let data = body.data(using: .utf8),
                  let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
            else { continue }
            for item in array {
                guard let o = item as? [String: Any],
                      let symbol = o["symbol"] as? String,
                      let coin = bySymbol[symbol]
                else { continue }
                let price: Double?
                if let s = o["price"] as? String { price = Double(s) }
                else if let n = o["price"] as? NSNumber { price = n.doubleValue }
                else { price = nil }
                if let price, price > 0, price.isFinite { result[coin] = price }
            }
        }
        return (result, failed)
    }

    static func candlePrice(_ coin: String, identityName: String?) async -> Double? {
        // Binance nur ohne klaren Namens-Widerspruch zu den Paaren der Merkliste (gleicher Token, K-8)
        guard let close = await CandleDataSource.candles(base: coin, quote: stable, interval: .h1, limit: 2,
                                                         identityName: identityName)?.last?.close,
              close > 0, close.isFinite
        else { return nil }
        return close
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    static func get(_ url: String, host: String, session: URLSession) async -> String? {
        do {
            return try await MarketHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }
}
