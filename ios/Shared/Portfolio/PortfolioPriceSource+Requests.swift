import Foundation

// Abfragen ohne eigenen Zustand (Teil von `PortfolioPriceSource`): Kurse aus Merklisten-Paaren anderer Spot-Börsen,
// Binance-Sammelabfrage, Kerzenkurs und GET mit Sperre der Quelle — wie `PortfolioPriceSource.kt` und
// `PortfolioPriceSourceExchanges.kt` (Android). Zwischenspeicher (`state`) bleiben in `PortfolioPriceSource.swift`.

extension PortfolioPriceSource {
    /// Kurse aus Merklisten-Paaren für Coins, die Binance-Spot nicht führt (`PortfolioWatchPair`): aktives
    /// Spot-Paar desselben Coins an einer anderen Börse, Kurs über deren Adapter, in USDT umgerechnet.
    /// Abfragen gehen nur an Börsen, deren Paar der Nutzer selbst in der Merkliste hat — wie Android.
    static func watchPairPrices(_ coins: [String]) async -> [String: Double] {
        let watches = SharedStorage.loadSnapshot().watches
        guard !watches.isEmpty else { return [:] }
        let names = TokenIdentityStore.shared
        let candidates = watches.map { w in
            PortfolioWatchPair.Candidate(exchangeKey: w.exchangeKey, base: w.baseAsset, quote: w.quoteAsset, pairId: w.pairId,
                                         spot: w.contractType == .none, disabled: w.disabled, lastUpdate: w.lastUpdate,
                                         // Voller Name aus der Paarliste der Börse (gleicher Token)
                                         name: names.pairName(exchangeKey: w.exchangeKey, base: w.baseAsset))
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
        guard let exchange = ExchangesConfig.exchange(pick.exchangeKey) else { return nil }
        let info = ExchangePair(pick.base, pick.quote, pick.pairId)
        guard let ticker = try? await ExchangeService.fetchTicker(exchange: exchange, info: info) else { return nil }
        let quote = pick.quote.uppercased()
        let rate: Double? = (quote == "USDT" || quote == "USD") ? nil : await FxRateSource.usdTo(quote)
        return PortfolioExchangeRoute.toUsdt(ticker.last, quote: quote,
                                             usdtUsd: DepegAlarmCheck.liveUsdtUsd() ?? 1) { _ in rate }
    }

    /// Sammelabfrage beim Binance-Spiegel (`BinanceBatchSplit`): gefiltert nur auf eine Coin-Liste von Binance
    /// (`binanceCoinList`, ohne Netz), sonst ungefiltert — unbekannte Symbole findet das Halbieren bei HTTP 400, ohne die
    /// übrigen Coins um ihren Kurs zu bringen (auch bei der ersten Aktualisierung). `failed`: eine Abfrage scheiterte
    /// am Netz bzw. die Quelle ist gesperrt (für `PortfolioPriceIssue`). Wie `PortfolioPriceSourceBatch.kt` (Android).
    static func binanceBatch(_ coins: [String]) async -> BinanceBatchSplit.Outcome {
        await binanceBatch(coins, known: binanceCoinList()) { await tickerReply($0) }
    }

    /// Wie `binanceBatch(_:)` mit Coin-Liste `known` (nil = ungefiltert) und eingesetzter Abfrage (für die Tests). Einzeln
    /// mit 400 abgelehnte Coins (`Outcome.unlisted`) fragt die Sammelabfrage `unlistedRetryMillis` lang nicht wieder an.
    static func binanceBatch(_ coins: [String], known: Set<String>?, now: Int64 = EpochTime.nowMillis,
                             request: ([String]) async -> BinanceBatchSplit.Reply) async -> BinanceBatchSplit.Outcome {
        if BlockedSources.isBlocked(binanceHost) { return BinanceBatchSplit.Outcome(failed: true) }
        let skip: Set<String> = state.withLock { s in
            Set(s.binanceUnlisted.filter { now - $0.value >= 0 && now - $0.value < unlistedRetryMillis }.keys)
        }
        let candidates = coins.filter { (known?.contains($0) ?? true) && !skip.contains($0) }
        let outcome = await BinanceBatchSplit.fetch(candidates, request: request)
        state.withLock { s in for coin in outcome.unlisted { s.binanceUnlisted[coin] = now } }
        return outcome
    }

    /// Eine Abfrage `ticker/price?symbols=[…]`: 400 = ein Symbol unbekannt (sperrt die Quelle nicht); andere Fehler =
    /// gescheitert (451/403/429 vermerkt `BlockedSources`).
    private static func tickerReply(_ part: [String]) async -> BinanceBatchSplit.Reply {
        // Während des Halbierens gesperrt oder pausiert (403/451/429): nicht weiter anfragen
        if BlockedSources.isBlocked(binanceHost) { return .failed }
        var bySymbol: [String: String] = [:]
        for coin in part { bySymbol[coin + stable] = coin }
        let symbols = "[" + part.map { "\"\($0)\(stable)\"" }.joined(separator: ",") + "]"
        guard let encoded = symbols.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return .prices([:]) }
        let url = "https://\(binanceHost)/api/v3/ticker/price?symbols=\(encoded)"
        let body: String
        do {
            body = try await ExchangeHTTP.call(url, session: ExchangeHTTP.session)
        } catch let http as HttpExchangeError where http.httpCode == 400 {
            return .rejected
        } catch {
            BlockedSources.noteFailure(binanceHost, error)
            return .failed
        }
        guard let data = body.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return .prices([:]) }
        var result: [String: Double] = [:]
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
        return .prices(result)
    }

    static func candlePrice(_ coin: String, identityName: String?) async -> Double? {
        // Binance nur ohne klaren Namens-Widerspruch zu den Paaren der Merkliste (gleicher Token)
        guard let close = await CandleDataSource.candles(base: coin, quote: stable, interval: .h1, limit: 2,
                                                         identityName: identityName)?.last?.close,
              close > 0, close.isFinite
        else { return nil }
        return close
    }

    /// GET; nil bei Fehler. 451/403 sperrt die Quelle vorübergehend (`BlockedSources`).
    static func get(_ url: String, host: String, session: URLSession) async -> String? {
        do {
            return try await ExchangeHTTP.call(url, session: session)
        } catch {
            BlockedSources.noteFailure(host, error)
            return nil
        }
    }
}
