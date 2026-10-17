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
        /// Widget (`lowMemory`): bewusst nicht abgefragt — gespeicherter Kurs bleibt, kein Fehler.
        var skipped = false
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
        /// HTTP 418 («IP gesperrt») dabei: Pause wie bei Kerzen (`ExchangeBackoff.banDefaultMillis`).
        var banned: Bool = false
    }

    /// - Parameter lowMemory: Widget-Erweiterung (~30 MB Speichergrenze): keine grossen ungefilterten
    ///   Sammellisten (`LowMemoryBulk`), sondern gefiltert, einzeln oder gar nicht (gespeicherter Kurs bleibt).
    static func fetchGroup(_ group: [Watch], includeRollingFutures: Bool,
                           lowMemory: Bool = false) async -> ([Int64: Fetched], GroupSignals?) {
        let started = TimeUtils.nowMillis
        guard let sample = group.first else { return ([:], nil) }

        var bulk = BulkTickers()
        var bulkTried = group.count >= minWatchesForBulk
        if lowMemory, bulkTried, let market = MarketsConfig.market(sample.marketKey), market.bulkTickersNumOfRequests > 0 {
            var seen: Set<String> = []
            let ids = group.compactMap(\.pairId).filter { seen.insert($0).inserted }
            switch LowMemoryBulk.plan(marketKey: market.key, pairs: group.count,
                                      unfilteredOnly: LowMemoryBulk.isUnfiltered(market, pairIds: ids)) {
            case .bulk:
                break
            case .singles:
                bulkTried = false
            case .skip:
                // Zu viele Paare für Einzelabfragen, Liste zu gross: diesmal nicht abfragen (kein Bericht, keine Pause)
                return (Dictionary(uniqueKeysWithValues: group.map { ($0.id, Fetched(skipped: true)) }), nil)
            }
        }
        if bulkTried, let market = MarketsConfig.market(sample.marketKey), market.bulkTickersNumOfRequests > 0 {
            var seen: Set<String> = []
            let ids = group.compactMap(\.pairId).filter { seen.insert($0).inserted }
            // Nicht mehr gehandelte Paare nicht mitschicken: ein delistetes Symbol verwirft bei Binance die ganze
            // gefilterte Liste → jedes Mal die ungefilterte (~2 MB). Sie fehlen dann in der vollständigen Liste.
            let notTradedIds = Set(group.filter { NotTraded.isMarker($0.lastError) }.compactMap(\.pairId))
            let plan = BulkDelistedMemory.shared.plan(sample.marketKey, pairIds: ids, notTraded: notTradedIds,
                                                      now: TimeUtils.nowMillis)
            if plan.request.isEmpty {
                // Alle beobachteten Paare nicht mehr gehandelt: keine Abfrage, die Liste gilt als vollständig ohne sie
                bulk = BulkTickers(complete: true)
            } else {
                bulk = await MarketService.fetchBulkTickers(market: market, pairIds: plan.request, lowMemory: lowMemory)
                BulkDelistedMemory.shared.learn(sample.marketKey, plan: plan, result: bulk, now: TimeUtils.nowMillis)
            }
        }
        let bulkMillis = TimeUtils.nowMillis - started

        // Sammelabfrage mit «zu vielen Anfragen» abgelehnt: keine Einzelabfragen hinterher —
        // das verschlimmerte es nur; die Börse wird pausiert (`ExchangeBackoff`).
        let bulkRateLimited = bulk.error.map { RefreshReportLogic.classify($0) == .RATE_LIMIT } ?? false
        // Antwort mehrheitlich unlesbar (Format der Börse geändert): Einzelabfragen mit demselben Leser brächten
        // nichts — alle Paare dieses Durchlaufs als Fehler, kein Nachhaken (`BulkTickerRows`) — wie Android
        let bulkFormatError = BulkTickerRows.isFormatError(bulk.error)

        var results: [Int64: Fetched] = [:]
        var singles: [Watch] = []
        // Fehlen in der vollständigen Liste: erst einzeln nachfragen (`NotTraded.askSingleWhenMissing`)
        var missing: Set<Int64> = []
        for watch in group {
            if let id = watch.pairId, let t = bulk.tickers[id] {
                results[watch.id] = Fetched(ticker: t, error: nil)
            } else if let id = watch.pairId, bulk.failed.contains(id) {
                // Zeile da, aber unlesbar: Fehler (veralteter Kurs), nicht frisch und nicht «unverändert»
                results[watch.id] = Fetched(ticker: nil, error: BulkTickerRows.rowError)
            } else if bulk.complete && !bulk.incomplete.contains(watch.pairId ?? "") && !bulkRateLimited
                        && (!includeRollingFutures || !watch.contractType.isRolling) {
                // Teilabfrage dieses Paars gescheitert (z. B. Alpha bei Binance): keine Aussage über sein Fehlen;
                // nach 429/418 keine Einzelabfragen zum Nachprüfen
                // «Nicht mehr gehandelt» erst, wenn auch die Einzelabfrage keinen Kurs bringt;
                // schon so gespeicherte Paare ohne erneute Einzelabfrage.
                if NotTraded.askSingleWhenMissing(lastError: watch.lastError) {
                    missing.insert(watch.id)
                    singles.append(watch)
                } else {
                    results[watch.id] = Fetched(ticker: nil, error: notTradedError, notTraded: true)
                }
            } else if bulkRateLimited {
                results[watch.id] = Fetched(ticker: nil, error: bulk.error, failure: .RATE_LIMIT)
            } else if bulkFormatError {
                results[watch.id] = Fetched(ticker: nil, error: bulk.error)
            } else {
                singles.append(watch)
            }
        }

        // Einzelabfragen, höchstens vier gleichzeitig je Börse — gleitend: sobald eine fertig
        // ist, startet die nächste (bisher wartete jeder Viererblock auf seine langsamste).
        // Erste Antwort mit 429/418: die übrigen nicht mehr senden, derselbe Fehler (`SingleRateGate`).
        let confirmIds = missing
        var sent = 0
        if !singles.isEmpty {
            await withTaskGroup(of: (Int64, Fetched).self) { tg in
                var gate = SingleRateGate()
                var next = 0
                var running = 0
                while true {
                    // Bis zur Grenze nachfüllen; bei gesperrter Börse die übrigen ohne Anfrage beenden
                    while running < maxParallelPerMarket && next < singles.count {
                        let w = singles[next]
                        next += 1
                        if let blocked = gate.blocked {
                            results[w.id] = Fetched(ticker: nil, error: blocked, failure: .RATE_LIMIT)
                            continue
                        }
                        let confirm = confirmIds.contains(w.id)
                        tg.addTask { (w.id, await fetchSingle(w, confirmMissing: confirm)) }
                        running += 1
                        sent += 1
                    }
                    guard let item = await tg.next() else { break }
                    running -= 1
                    results[item.0] = item.1
                    gate.note(item.1)
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
            singles: sent,
            reason: reason
        )
        let errorTexts = errors.map(\.error) + [bulk.error]
        let signals = GroupSignals(
            marketKey: sample.marketKey,
            report: entry,
            failures: failures + (bulk.error.map { [RefreshReportLogic.classify($0)] } ?? []),
            retryAfterMillis: ExchangeBackoff.retryAfterMillis(errorTexts),
            banned: ExchangeBackoff.banned(errorTexts)
        )
        return (results, signals)
    }

    /// Einzelabfrage für ein Paar, das in der vollständigen Liste fehlt (`confirmMissing`): Kurs →
    /// normal übernehmen; antwortet die Börse ohne Kurs → nicht mehr gehandelt; Netz-/Serverproblem
    /// → gewöhnlicher Fehler. Wie `PriceFetcher.confirmMissing` (Android).
    static func fetchSingle(_ watch: Watch, confirmMissing: Bool) async -> Fetched {
        let single = await fetchSingle(watch)
        // Börse fehlt in dieser Version: Text ist übersetzt, also an der Ursache erkennen
        guard confirmMissing, single.failure != .UNAVAILABLE else { return single }
        let gotPrice = single.ticker != nil && single.error == nil
        guard NotTraded.confirmedBySingle(gotPrice: gotPrice, error: single.error) else { return single }
        return Fetched(ticker: nil, error: notTradedError, fromSingle: true, millis: single.millis, notTraded: true)
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

/// Kurse in der Widget-Erweiterung (Speichergrenze ~30 MB): Ungefilterte Sammellisten mancher Börsen sind mehrere MB
/// JSON und sprengen das gelesen die Grenze. Darum dort: gefilterte Sammelabfrage ja (ohne ungefilterten Ersatz,
/// `MarketService.fetchBulkTickers`), sonst bis `maxSingles` Paare einzeln; mehr Paare einer Börse mit grosser
/// ungefilterter Liste (`largeUnfilteredKeys`) diesmal gar nicht — das Widget zeigt den gespeicherten Kurs.
/// Kleine ungefilterte Listen bleiben (sonst stünde z. B. Bitstamp mit vielen Paaren still). Die App ist unverändert.
enum LowMemoryBulk {
    enum Plan: Equatable {
        case bulk, singles, skip
    }

    /// Höchstens so viele Einzelabfragen je Börse statt der ungefilterten Liste.
    static let maxSingles = 8

    /// Börsen, deren ungefilterte Liste (oder Ersatzliste) etwa 1 MB oder mehr ist.
    static let largeUnfilteredKeys: Set<String> = [
        "GateIo", "Kucoin", "Mexc", "Htx", "Bitget", "Okex", "Poloniex", "Latoken", "Phemex", "Binance",
    ]

    /// Ist die Sammelabfrage dieser Paare ungefiltert (gleiche URL wie ohne Auswahl)?
    static func isUnfiltered(_ market: Market, pairIds: [String]) -> Bool {
        market.bulkTickersURL(call: 0, pairIds: pairIds) == market.bulkTickersURL(call: 0)
    }

    /// Teilanfrage, die das Widget auslässt: die grosse Alpha-Token-Liste von Binance (ihre Paare dann einzeln).
    static func skipsRequest(url: String) -> Bool {
        url == BinanceAlpha.tokenListURL
    }

    static func plan(marketKey: String, pairs: Int, unfilteredOnly: Bool) -> Plan {
        guard unfilteredOnly else { return .bulk }
        if pairs <= maxSingles { return .singles }
        return largeUnfilteredKeys.contains(marketKey) ? .skip : .bulk
    }
}

/// «Zu viele Anfragen» (429) oder «gesperrt» (418) bei einer Einzelabfrage: Die übrigen, noch nicht gesendeten
/// Einzelabfragen derselben Börse in diesem Durchlauf gehen nicht mehr hinaus, sondern enden mit demselben
/// Fehlertext — samt «retry-after», die Pause je Börse (`ExchangeBackoff`) wirkt wie bisher. Schon laufende
/// Abfragen laufen zu Ende. Je Börse und Durchlauf einer. Wie `SingleRateGate` in `PriceFetcher.kt`.
struct SingleRateGate: Sendable {
    /// Fehlertext, mit dem noch nicht gesendete Einzelabfragen enden; nil = senden.
    private(set) var blocked: String? = nil

    /// Ergebnis einer gesendeten Abfrage melden: das erste 429/418 sperrt den Rest.
    mutating func note(_ fetched: PriceRefresher.Fetched) {
        guard blocked == nil, fetched.ticker == nil || fetched.error != nil,
              let error = fetched.error, Self.isRateLimit(error) else { return }
        blocked = error
    }

    static func isRateLimit(_ error: String?) -> Bool {
        guard let error else { return false }
        return RefreshReportLogic.classify(error) == .RATE_LIMIT
    }
}

/// Nicht mehr gehandelte Paare aus der GEFILTERTEN Sammelabfrage heraushalten. Binance verwirft `symbols=[…]` mit
/// einem delisteten Symbol ganz (HTTP 400 «Invalid symbol») — dann lud jede Aktualisierung die ungefilterte Liste
/// (~2 MB). Welches Symbol, sagt Binance nicht: erkannt an der vollständigen Ersatzliste (angefragt, aber nicht
/// darin). Diese Paare und die schon als «nicht mehr gehandelt» gespeicherten (`NotTraded`) bleiben aus der Anfrage;
/// sie fehlen dann in der vollständigen Liste und laufen über die bestehende Logik. Alle `recheckMillis` einmal
/// alle Paare (wieder gelistet?). Wie `BulkDelisted` in `PriceFetcher.kt` (testdata/parity/bulk_delisted.json).
enum BulkDelisted {
    static let recheckMillis: Int64 = 6 * 60 * 60_000

    /// `delisted`: zuletzt in der vollständigen Liste fehlende Paare; `recheckedAt`: letzter Lauf mit allen Paaren.
    struct State: Equatable, Sendable {
        var delisted: Set<String>
        var recheckedAt: Int64
    }

    /// `all`: beobachtete Paare; `request`: davon anzufragen; `recheck`: Lauf mit allen Paaren.
    struct Plan: Equatable, Sendable {
        var all: [String]
        var request: [String]
        var recheck: Bool
    }

    static func plan(state: State?, pairIds: [String], notTraded: Set<String>, now: Int64) -> Plan {
        // Ohne Stand (z. B. nach Prozess-Start) nicht gleich alles laden: die Frist läuft ab jetzt
        var recheck = false
        if let state {
            let age = now - state.recheckedAt
            recheck = age < 0 || age >= recheckMillis
        }
        if recheck { return Plan(all: pairIds, request: pairIds, recheck: true) }
        let skip = (state?.delisted ?? []).union(notTraded)
        return Plan(all: pairIds, request: pairIds.filter { !skip.contains($0) }, recheck: false)
    }

    /// Stand nach der Abfrage; nur eine vollständige Liste (`complete`) sagt etwas über fehlende Paare — und nur über
    /// Paare, deren Teilabfrage geklappt hat (`incomplete`: z. B. Alpha gescheitert, Spot nicht).
    static func learn(state: State?, plan: Plan, tickers: Set<String>, failed: Set<String>, complete: Bool,
                      now: Int64, incomplete: Set<String> = []) -> State {
        let base = state ?? State(delisted: [], recheckedAt: now)
        guard complete else { return base }
        let all = Set(plan.all)
        let requested = Set(plan.request)
        let kept = base.delisted.filter { all.contains($0) && !tickers.contains($0) && !requested.contains($0) }
        let missing = plan.request.filter { !tickers.contains($0) && !failed.contains($0) && !incomplete.contains($0) }
        return State(delisted: kept.union(missing), recheckedAt: plan.recheck ? now : base.recheckedAt)
    }
}

/// Stand von `BulkDelisted` je Börse, prozessweit. Wie das Feld `delisted` in `PriceFetcher.kt`.
final class BulkDelistedMemory: @unchecked Sendable {
    static let shared = BulkDelistedMemory()

    private let lock = NSLock()
    private var states: [String: BulkDelisted.State] = [:]

    func plan(_ marketKey: String, pairIds: [String], notTraded: Set<String>, now: Int64) -> BulkDelisted.Plan {
        lock.lock(); defer { lock.unlock() }
        return BulkDelisted.plan(state: states[marketKey], pairIds: pairIds, notTraded: notTraded, now: now)
    }

    func learn(_ marketKey: String, plan: BulkDelisted.Plan, result: BulkTickers, now: Int64) {
        lock.lock(); defer { lock.unlock() }
        states[marketKey] = BulkDelisted.learn(state: states[marketKey], plan: plan, tickers: Set(result.tickers.keys),
                                               failed: result.failed, complete: result.complete, now: now,
                                               incomplete: result.incomplete)
    }
}
