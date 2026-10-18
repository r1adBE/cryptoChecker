import Foundation

// Funding-Verlauf für «Warum?» (Teil von `FuturesDataSource`) — wie `fundingHistory` in `FuturesDataSource.kt`.

extension FuturesDataSource {
    private typealias HistoryAttempt = (host: String, run: () async throws -> FundingHistory)

    private static let historyLock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `historyLock` (NSLock)
    nonisolated(unsafe) private static var historyCache: [String: (time: Int64, value: FundingHistory)] = [:]
    private static let historyCacheMillis: Int64 = 10 * 60_000

    /// Die letzten `FundingTrendRules.limit` abgerechneten Funding Rates — gleiche Ausweich-Kette
    /// und Symbole wie `fetch` (Perpetual) bzw. `fetchForBase` (sonst), gesperrte Hosts übersprungen.
    /// Einmal beim Öffnen von «Warum?»; Ergebnisse ~10 Min. im Speicher. nil = keine Quelle (wie Android).
    ///  - Binance: `GET https://fapi.binance.com/fapi/v1/fundingRate?symbol=…&limit=4`
    ///  - Bybit: `GET https://api.bybit.com/v5/market/funding/history?category=linear&symbol=…&limit=4`
    ///  - OKX: `GET https://www.okx.com/api/v5/public/funding-rate-history?instId=…&limit=4`
    static func fundingHistory(watch: Watch) async -> FundingHistory? {
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let perpetual = watch.contractType.isPerpetual
        let key = perpetual ? "\(watch.marketKey)|\(watch.pairId ?? "")|\(base)" : "SPOT|\(base)"
        let now = TimeUtils.nowMillis
        if let hit = cachedHistory(key, now: now) { return hit }
        let usdt = "\(base)USDT"
        let swap = "\(base)-USDT-SWAP"
        let attempts: [HistoryAttempt]
        switch perpetual ? watch.marketKey : "" {
        case "BybitFutures":
            let symbol = watch.pairId ?? usdt
            attempts = [
                (host: bybitHost, run: { try await bybitHistory(symbol: symbol) }),
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        case "BinanceFutures" where FuturesSources.isCoinM(watch.pairId):
            let coinM = String((watch.pairId ?? "").dropFirst(FuturesSources.coinMPrefix.count))
            attempts = [
                (host: coinMHost, run: { try await binanceCoinMHistory(symbol: coinM) }),
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        case "BinanceFutures":
            let symbol: String
            if let pairId = watch.pairId, pairId.hasSuffix("USDT") || pairId.hasSuffix("USDC") {
                symbol = pairId
            } else {
                symbol = usdt
            }
            attempts = [
                (host: binanceHost, run: { try await binanceHistory(symbol: symbol) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        case "OkexFutures":
            let instId = watch.pairId.flatMap { $0.hasSuffix("-SWAP") ? $0 : nil } ?? swap
            attempts = [
                (host: okxHost, run: { try await okxHistory(instId: instId) }),
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
            ]
        default:
            attempts = [
                (host: binanceHost, run: { try await binanceHistory(symbol: usdt) }),
                (host: bybitHost, run: { try await bybitHistory(symbol: usdt) }),
                (host: okxHost, run: { try await okxHistory(instId: swap) }),
            ]
        }
        for attempt in attempts {
            if BlockedSources.isBlocked(attempt.host) { continue }
            do {
                let result = try await attempt.run()
                storeHistory(key, result)
                return result
            } catch {
                // Abbruch (Blatt zu): nichts merken, nicht weiter fragen
                if error is CancellationError || Task.isCancelled { return nil }
                BlockedSources.noteFailure(attempt.host, error)
            }
        }
        // Fehler nicht merken: beim nächsten Öffnen neu versuchen
        return nil
    }

    private static func binanceHistory(symbol: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://fapi.binance.com/fapi/v1/fundingRate?symbol=\(symbol)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseBinance(text), source: "Binance Futures")
    }

    private static func binanceCoinMHistory(symbol: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://dapi.binance.com/dapi/v1/fundingRate?symbol=\(symbol)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseBinance(text), source: FuturesSources.binanceCoinM)
    }

    private static func bybitHistory(symbol: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=\(symbol)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseBybit(text), source: "Bybit")
    }

    private static func okxHistory(instId: String) async throws -> FundingHistory {
        let text = try await MarketHTTP.call(
            "https://www.okx.com/api/v5/public/funding-rate-history?instId=\(instId)&limit=\(FundingTrendRules.limit)")
        return try history(FundingTrendRules.parseOkx(text), source: "OKX")
    }

    /// Leere Antwort zählt als Fehler, damit die Kette mit der nächsten Quelle weitermacht.
    private static func history(_ points: [FundingPoint], source: String) throws -> FundingHistory {
        guard !points.isEmpty else { throw JSONError(message: "Kein Funding-Verlauf von \(source)") }
        return FundingHistory(points: points, source: source)
    }

    private static func cachedHistory(_ key: String, now: Int64) -> FundingHistory? {
        historyLock.lock(); defer { historyLock.unlock() }
        guard let entry = historyCache[key] else { return nil }
        let age = now - entry.time
        return age >= 0 && age < historyCacheMillis ? entry.value : nil
    }

    private static func storeHistory(_ key: String, _ value: FundingHistory) {
        historyLock.lock(); defer { historyLock.unlock() }
        historyCache[key] = (time: TimeUtils.nowMillis, value: value)
    }
}
