import Foundation

// Standardverhalten aller Börsen (`Exchange`) und die gemeinsamen Einstiege `read…`/`parse…Main`. Die Einstiege sind
// keine Anforderungen des Protokolls: Ein Adapter ändert sie nicht, er liefert nur die Haken (`fill…`, `parse…`).

extension Exchange {
    var ttsName: String { name }
    var currencyPairs: CurrencyPairsMap? { nil }
    var priceIsMid: Bool { false }
    var priceIsHourlyClose: Bool { false }
    var changeIsMark: Bool { false }
    var category: ExchangeCategory { .spot }
    var allowsAlarms: Bool { true }
    var priceIsDailyReference: Bool { false }

    // MARK: Einzelkurs

    func tickerCallCount(_ info: PairQuery) -> Int { 1 }

    func tickerAddress(call: Int, info: PairQuery) -> String {
        fatalError("\(key): tickerAddress(call:info:) fehlt")
    }

    func tickerQueryError(call: Int, info: PairQuery) -> Error? { nil }

    func tickerPostBody(call: Int, info: PairQuery) -> PostRequest? { nil }

    /// Parses a single-ticker response into `ticker` (via `fillTicker`). Afterwards `timestamp` is the fetch time
    /// and `exchangeTime` the time reported by the exchange (or nil).
    ///
    /// Why the fetch time counts: «outdated» (OutdatedRule, lastUpdate) asks whether the exchange still lists the
    /// pair — and the answer just arrived. A quiet pair with an old last trade (Upbit `trade_timestamp`, Bithumb
    /// `trade_date`/`trade_time`) is therefore not outdated within the alarm limit; a last trade older than that limit
    /// (2 h, AlarmFreshness) marks the row «outdated – last trade …» too (PriceFreshness.staleReason). If the fetch fails, lastUpdate stays and the pair ages as
    /// usual; a delisted pair is missing from the list (NotTraded). The exchange time is only shown
    /// (PriceFreshness) so that a standing price does not look fresh. Same as `Exchange.kt`.
    func readTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTicker(call: call, body: body, ticker: &ticker, info: info)
        markFetched(&ticker)
    }

    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTicker(call: call, json: JObject(string: body), ticker: &ticker, info: info)
    }

    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {}

    func tickerFallbackAddress(call: Int, info: PairQuery) -> String? { nil }

    func tickerFallbackOnParseError(call: Int, error: Error) -> Bool { false }

    /// Wie `readTicker`, für die Antwort von `tickerFallbackAddress` (über `fillTickerFallback`).
    func readTickerFallback(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTickerFallback(call: call, body: body, ticker: &ticker, info: info)
        markFetched(&ticker)
    }

    func fillTickerFallback(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        throw JSONError(message: JSONError.noData)
    }

    /// Error text of the exchange for a ticker response that could not be parsed (via `errorMessage`). Throws if the
    /// response contains none; the caller then reports the original parse error.
    func readError(call: Int, body: String, info: PairQuery) throws -> String? {
        try errorMessage(call: call, body: body, info: info)
    }

    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        try errorMessage(call: call, json: JObject(string: body), info: info)
    }

    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try noErrorText()
    }

    /// For `errorMessage`: signals that the response contains no error text of the exchange.
    func noErrorText() throws -> Never {
        throw JSONError(message: ExchangeConstants.noErrorTextMessage)
    }

    // MARK: Sammelabfrage

    var bulkTickersNumOfRequests: Int { 0 }
    var bulkTickersComplete: Bool { false }
    var singleTickerReadsFullList: Bool { false }

    func bulkTickersURL(call: Int) -> String? { nil }

    func bulkTickersURL(call: Int, pairIds: [String]) -> String? { bulkTickersURL(call: call) }

    func bulkTickersRequestCount(pairIds: [String]) -> Int { bulkTickersNumOfRequests }

    func bulkTickersPostRequest(call: Int) -> PostRequest? { nil }

    func bulkTickersRequestGroup(call: Int) -> Int { 0 }

    func bulkTickersPairGroup(pairId: String) -> Int { 0 }

    func bulkKnownPairsURL(call: Int) -> String? { nil }

    /// All prices of a bulk response, keyed by `ExchangePair.pairId` — the same id as in the pair list, otherwise
    /// they could not be matched to a watched pair.
    func parseBulkTickersMain(call: Int, body: String) throws -> [String: Ticker] {
        try parseBulkTickersDetailed(call: call, body: body).tickers
    }

    /// Like `parseBulkTickersMain`, plus the pairs with an unreadable row (`BulkTickerParse.failed`). If unreadable
    /// rows dominate, throws `BulkFormatError` (text starts with `BulkTickerRows.formatError`).
    func parseBulkTickersDetailed(call: Int, body: String) throws -> BulkTickerParse {
        var unreadable = Set<String>()
        let good = try parseBulkTickers(call: call, body: body, failed: &unreadable)
        // Ein Paar, das doch noch gelesen wurde, gilt nicht als Fehler
        let broken = unreadable.subtracting(good.keys)

        if BulkTickerRows.isFormatFailure(parsed: good.count, failed: broken.count) {
            throw BulkFormatError(message: BulkTickerRows.formatErrorText(parsed: good.count, failed: broken.count))
        }
        let fetched = good.mapValues { row -> Ticker in
            var stamped = row
            markFetched(&stamped)
            return stamped
        }
        return BulkTickerParse(tickers: fetched, failed: broken,
                               complete: bulkResponseComplete(call: call, body: body))
    }

    func bulkResponseComplete(call: Int, body: String) -> Bool { true }

    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] { [:] }

    /// The usual bulk row: skipped silently without an id or with an empty price in `priceField` (dormant pair,
    /// `BulkTickerRows.inactivePrice`), otherwise read via `bulkRow`.
    func bulkRowIfActive(_ pairId: String, row: JObject, priceField: String, tickers: inout [String: Ticker],
                         failed: inout Set<String>, read: (inout Ticker) throws -> Void) {
        if pairId.isEmpty || BulkTickerRows.inactivePrice(row, priceField) { return }
        bulkRow(pairId, tickers: &tickers, failed: &failed, read: read)
    }

    /// Reads one bulk row with `read` into a fresh ticker. If `read` throws (field missing or renamed) or there is
    /// no usable last price (`BulkTickerRows.usable`), `pairId` goes into `failed` — the pair then shows an error
    /// instead of silently staying «unchanged». That is why every error is deliberately turned into this entry
    /// here and not rethrown.
    func bulkRow(_ pairId: String, tickers: inout [String: Ticker], failed: inout Set<String>,
                 read: (inout Ticker) throws -> Void) {
        var row = Ticker()
        let readable: Bool
        do {
            try read(&row)
            readable = BulkTickerRows.usable(row)
        } catch {
            readable = false
        }
        if readable {
            tickers[pairId] = row
        } else {
            failed.insert(pairId)
        }
    }

    // MARK: Paarliste

    var pairListCallCount: Int { 1 }
    var pairListCombined: Bool { false }
    var pairListAllCallsRequired: Bool { false }

    func pairListAddress(call: Int) -> String? { nil }

    func pairListPostBody(call: Int) -> PostRequest? { nil }

    func pairListNextPage(call: Int, body: String) throws -> String? { nil }

    /// Parses a pair-list response (via `fillPairList`) and drops pairs without base or quote.
    func readPairList(call: Int, body: String) throws -> [ExchangePair] {
        try dropIncomplete(fillPairList(call: call, body: body))
    }

    /// Like `readPairList` for exchanges with `pairListCombined`, over all collected responses.
    func readPairListCombined(bodies: [String]) throws -> [ExchangePair] {
        try dropIncomplete(fillPairListCombined(bodies: bodies))
    }

    func fillPairList(call: Int, body: String) throws -> [ExchangePair] {
        try fillPairList(call: call, json: JObject(string: body))
    }

    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair] { [] }

    func fillPairListCombined(bodies: [String]) throws -> [ExchangePair] { [] }

    // MARK: Volle Coin-Namen

    func parseBaseNames(call: Int, body: String) throws -> [String: String] { [:] }

    func parseBaseNamesCombined(bodies: [String]) throws -> [String: String] { [:] }
}

/// Die Leser schreiben eine allfällige Börsenzeit (s, ms, µs oder ns) nach `timestamp`. Hier wandert sie in ms
/// nach `exchangeTime` (≤ 0 = keine), und `timestamp` wird auf jetzt gesetzt. Eine Zeit mehr als
/// `ExchangeConstants.exchangeTimeFutureToleranceMillis` in der Zukunft ist falsch gelesen (Ortszeit als Epoche wie
/// Bithumbs KST, falsche Einheit) und fällt weg: Sie liesse einen stehenden Kurs stundenlang frisch aussehen. Wie Android.
private func markFetched(_ ticker: inout Ticker) {
    let now = EpochTime.nowMillis
    let reported = ticker.timestamp > 0 ? EpochTime.parseTimeToMillis(ticker.timestamp) : nil
    if let reported, reported <= now + ExchangeConstants.exchangeTimeFutureToleranceMillis {
        ticker.exchangeTime = reported
    } else {
        ticker.exchangeTime = nil
    }
    ticker.timestamp = now
}

private func dropIncomplete(_ pairs: [ExchangePair]) -> [ExchangePair] {
    pairs.filter { pair in !pair.base.isEmpty && !pair.quote.isEmpty }
}
