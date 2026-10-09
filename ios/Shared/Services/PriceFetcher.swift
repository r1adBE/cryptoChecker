import Foundation

/// Kurse holen: je Börse erst gesammelt, was fehlt einzeln (höchstens vier gleichzeitig);
/// dazu die Bericht-Einträge. Wie `PriceFetcher.kt`; Teil von `PriceRefresher`.
extension PriceRefresher {
    /// Unter so wenigen Paaren spart die Sammelabfrage nichts.
    static let minWatchesForBulk = 3
    /// Gleichzeitige Einzelabfragen je Börse.
    static let maxParallelPerMarket = 4

    struct Fetched: Sendable {
        var ticker: Ticker?
        var error: String?
        var fromSingle = false
        var millis: Int64 = 0
        var notTraded = false
        /// Ursache für den Bericht (nur bei Fehlern).
        var failure: RefreshFailure? = nil
    }

    static let notTradedError = NotTraded.marker

    // MARK: Netz

    /// Bericht-Einträge der übersprungenen (pausierten) Börsen.
    static func pausedMarkets(_ paused: [Watch], backoff: [String: ExchangeBackoff.State]) -> [MarketRefresh] {
        Dictionary(grouping: paused, by: \.marketKey).compactMap { key, list in
            guard let first = list.first else { return nil }
            return MarketRefresh(name: first.marketName, millis: 0, pairs: list.count, updated: 0,
                                 pausedUntil: backoff[key]?.pausedUntil, pauseReason: backoff[key]?.reason)
        }
    }

    /// Bericht einer Börse plus was die Pause je Börse (`ExchangeBackoff`) braucht.
    struct GroupSignals: Sendable {
        var marketKey: String
        var report: MarketRefresh
        /// Ursachen aller Fehler (auch einer gescheiterten Sammelabfrage).
        var failures: [RefreshFailure]
        var retryAfterMillis: Int64?
    }

    static func fetchGroup(_ group: [Watch], includeRollingFutures: Bool) async -> ([Int64: Fetched], GroupSignals?) {
        let started = TimeUtils.nowMillis
        guard let sample = group.first else { return ([:], nil) }

        var bulk = BulkTickers()
        let bulkTried = group.count >= minWatchesForBulk
        if bulkTried, let market = MarketsConfig.market(sample.marketKey), market.bulkTickersNumOfRequests > 0 {
            let ids = Array(Set(group.compactMap(\.pairId)))
            bulk = await MarketService.fetchBulkTickers(market: market, pairIds: ids)
        }
        let bulkMillis = TimeUtils.nowMillis - started

        // Sammelabfrage mit «zu vielen Anfragen» abgelehnt: keine Einzelabfragen hinterher —
        // das verschlimmerte es nur; die Börse wird pausiert (`ExchangeBackoff`).
        let bulkRateLimited = bulk.error.map { RefreshReportLogic.classify($0) == .RATE_LIMIT } ?? false

        var results: [Int64: Fetched] = [:]
        var singles: [Watch] = []
        for watch in group {
            if let id = watch.pairId, let t = bulk.tickers[id] {
                results[watch.id] = Fetched(ticker: t, error: nil)
            } else if bulk.complete && (!includeRollingFutures || !watch.contractType.isRolling) {
                results[watch.id] = Fetched(ticker: nil, error: notTradedError, notTraded: true)
            } else if bulkRateLimited {
                results[watch.id] = Fetched(ticker: nil, error: bulk.error, failure: .RATE_LIMIT)
            } else {
                singles.append(watch)
            }
        }

        // Einzelabfragen, höchstens vier gleichzeitig je Börse — gleitend: sobald eine fertig
        // ist, startet die nächste (bisher wartete jeder Viererblock auf seine langsamste).
        if !singles.isEmpty {
            await withTaskGroup(of: (Int64, Fetched).self) { tg in
                var next = 0
                while next < min(maxParallelPerMarket, singles.count) {
                    let w = singles[next]
                    tg.addTask { (w.id, await fetchSingle(w)) }
                    next += 1
                }
                while let item = await tg.next() {
                    results[item.0] = item.1
                    if next < singles.count {
                        let w = singles[next]
                        tg.addTask { (w.id, await fetchSingle(w)) }
                        next += 1
                    }
                }
            }
        }

        let notTraded = results.values.filter(\.notTraded).count
        let errors = results.values.filter { ($0.ticker == nil || $0.error != nil) && !$0.notTraded }
        // Ursache: die beim Abfragen erkannte (z. B. Zeitüberschreitung), sonst aus dem Fehlertext
        let failures = errors.map { $0.failure ?? RefreshReportLogic.classify($0.error) }
        let reason = RefreshReportLogic.mostFrequent(failures)
        let entry = MarketRefresh(
            name: sample.marketName,
            millis: TimeUtils.nowMillis - started,
            pairs: group.count,
            updated: group.count - notTraded - errors.count,
            notTraded: notTraded,
            failed: errors.count,
            bulkTried: bulkTried,
            bulkMillis: bulkMillis,
            bulkPrices: bulk.tickers.count,
            singles: singles.count,
            reason: reason
        )
        let errorTexts = errors.map(\.error) + [bulk.error]
        let signals = GroupSignals(
            marketKey: sample.marketKey,
            report: entry,
            failures: failures + (bulk.error.map { [RefreshReportLogic.classify($0)] } ?? []),
            retryAfterMillis: ExchangeBackoff.retryAfterMillis(errorTexts)
        )
        return (results, signals)
    }

    static func fetchSingle(_ watch: Watch) async -> Fetched {
        let started = TimeUtils.nowMillis
        guard let market = MarketsConfig.market(watch.marketKey) else {
            return Fetched(ticker: nil, error: L("market_unavailable_error"), fromSingle: true, failure: .UNAVAILABLE)
        }
        do {
            let t = try await MarketService.fetchTicker(market: market, info: watch.pairInfo)
            return Fetched(ticker: t, error: nil, fromSingle: true, millis: TimeUtils.nowMillis - started)
        } catch {
            // Zeitüberschreitung eigens erkennen: `describe` macht daraus «kein Netz»
            let timedOut = (error as? URLError)?.code == .timedOut
            let described = ConnectionErrors.describe(error)
            return Fetched(ticker: nil, error: described, fromSingle: true, millis: TimeUtils.nowMillis - started,
                           failure: timedOut ? .TIMEOUT : RefreshReportLogic.classify(described))
        }
    }
}
