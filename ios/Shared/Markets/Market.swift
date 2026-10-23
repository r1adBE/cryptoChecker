import Foundation

/// Built-in pairs of an exchange without a fetchable pair list: base → possible quote currencies.
typealias CurrencyPairsMap = [String: [String]]

/// Base class of an exchange adapter: HOW to talk to one exchange — URLs, POST bodies, parsing responses and
/// error texts. Networking, storage, retries and UI live elsewhere (`MarketService`).
///
/// Template methods: the final entry points `read…`/`parse…Main` handle the shared parts (timestamps, empty
/// pairs, broken rows) and call the overridable hooks `fill…`/`parseBulkTickers`. A subclass overrides only what
/// differs. Sections follow the flow: single ticker · bulk ticker · pair list · coin names. A typical exchange
/// with one pair-list URL and one ticker URL subclasses `SimpleMarket`. Android counterpart: `Market.kt`.
/// Step-by-step guide: DEVELOPMENT.md («Adding an exchange»); register new adapters in `MarketsConfig.all`.
///
/// Instances are stateless and shared by concurrent requests. `key` equals the Android key (the Kotlin class
/// name) and is stored in backups of both platforms — never rename it.
class Market: @unchecked Sendable { // @unchecked: nur let-Konstanten, Unterklassen ohne veränderlichen Zustand (geteilte Instanzen)
    /// Storage key, identical to Android `Market.key`. Never change it after release.
    let key: String
    /// Display name of the exchange.
    let name: String
    /// Name for speech output; defaults to `name`.
    let ttsName: String
    /// Only for a few exchanges: built-in pairs for the time before the first pair-list sync.
    let currencyPairs: CurrencyPairsMap?

    init(key: String, name: String, ttsName: String? = nil, currencyPairs: CurrencyPairsMap? = nil) {
        self.key = key
        self.name = name
        self.ttsName = ttsName ?? name
        self.currencyPairs = currencyPairs
    }

    /// true: the delivered price is a mid price (from bid/ask or mark) rather than the last trade. The action sheet
    /// then labels it «mid price»; there is no exchange time, «updated» is the fetch time. Android: `priceIsMid`.
    var priceIsMid: Bool { false }

    /// true: the price is the close of the newest hourly candle (WOO X spot has no ticker). The action sheet labels it
    /// «hourly price». Android: `priceIsHourlyClose`.
    var priceIsHourlyClose: Bool { false }

    /// true: the 24 h change refers to the mark price (e.g. `markPx` vs. `prevDayPx`), not to the displayed
    /// price; no adapter sets it at the moment (Hyperliquid uses the displayed price). Row and action sheet then add «Mark» (`ChangeBasisMath.showsMark`). Android: `changeIsMark`.
    var changeIsMark: Bool { false }

    // MARK: Einzelkurs

    /// Number of requests per price; `call` runs from 0 to count − 1. Default: one request.
    func tickerCallCount(_ info: PairQuery) -> Int { 1 }

    /// URL of single-ticker request `call` for the pair in `info` — every exchange must override it (abstract in Kotlin).
    func tickerAddress(call: Int, info: PairQuery) -> String {
        fatalError("\(key): tickerAddress(call:info:) fehlt")
    }

    /// Fehler statt Anfrage, wenn die Kennung nicht in die Adresse darf (DexScreener `pairId`); nil =
    /// anfragen. Android: `tickerAddress` wirft.
    func tickerQueryError(call: Int, info: PairQuery) -> Error? { nil }

    /// POST body for request `call`; nil means a plain GET request.
    func tickerPostBody(call: Int, info: PairQuery) -> PostRequestInfo? { nil }

    /// Parses a single-ticker response into `ticker` (via `fillTicker`). Afterwards `timestamp` is the fetch time
    /// and `exchangeTime` the time reported by the exchange (or nil).
    ///
    /// Why the fetch time counts: «outdated» (OutdatedRule, lastUpdate) asks whether the exchange still lists the
    /// pair — and the answer just arrived. A quiet pair with an old last trade (Upbit `trade_timestamp`, Bithumb
    /// `trade_date`/`trade_time`) is therefore not outdated within the alarm limit; a last trade older than that limit
    /// (2 h, AlarmFreshness) marks the row «outdated – last trade …» too (PriceFreshness.staleReason). If the fetch fails, lastUpdate stays and the pair ages as
    /// usual; a delisted pair is missing from the list (NotTraded). The exchange time is only shown
    /// (PriceFreshness) so that a standing price does not look fresh. Same as `Market.kt`.
    final func readTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTicker(call: call, body: body, ticker: &ticker, info: info)
        Market.markFetched(&ticker)
    }

    /// Hook: fills `ticker` from a single-ticker response. Default: parses `body` as a JSON object and calls
    /// `fillTicker(call:json:…)`; override for array or non-JSON responses. Write an exchange timestamp (s, ms, µs
    /// or ns) into `timestamp`; `readTicker` moves it to `exchangeTime`.
    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws {
        try fillTicker(call: call, json: JObject(string: body), ticker: &ticker, info: info)
    }

    /// Hook: reads the fields of a JSON-object response. Without an override the ticker stays empty («no price»).
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws {}

    /// Error text of the exchange for a ticker response that could not be parsed (via `errorMessage`). Throws if the
    /// response contains none; the caller then reports the original parse error.
    final func readError(call: Int, body: String, info: PairQuery) throws -> String? {
        try errorMessage(call: call, body: body, info: info)
    }

    /// Hook: extracts the exchange's error text. Default: parses `body` as a JSON object and calls `errorMessage(call:json:info:)`.
    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String? {
        try errorMessage(call: call, json: JObject(string: body), info: info)
    }

    /// Hook: error text from a JSON-object response. Default: none known (`noErrorText()`).
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String? {
        try noErrorText()
    }

    /// For `errorMessage`: signals that the response contains no error text of the exchange.
    final func noErrorText() throws -> Never {
        throw JSONError(message: Market.noErrorTextMessage)
    }

    /// Die Leser schreiben eine allfällige Börsenzeit (s, ms, µs oder ns) nach `timestamp`. Hier wandert sie in ms
    /// nach `exchangeTime` (≤ 0 = keine), und `timestamp` wird auf jetzt gesetzt. Eine Zeit mehr als
    /// `exchangeTimeFutureToleranceMillis` in der Zukunft ist falsch gelesen (Ortszeit als Epoche wie Bithumbs KST,
    /// falsche Einheit) und fällt weg: Sie liesse einen stehenden Kurs stundenlang frisch aussehen. Wie Android.
    private static func markFetched(_ ticker: inout Ticker) {
        let now = TimeUtils.nowMillis
        let reported = ticker.timestamp > 0 ? TimeUtils.parseTimeToMillis(ticker.timestamp) : nil
        if let reported, reported <= now + exchangeTimeFutureToleranceMillis {
            ticker.exchangeTime = reported
        } else {
            ticker.exchangeTime = nil
        }
        ticker.timestamp = now
    }

    // MARK: Sammelabfrage

    /// Number of requests of a bulk query (all prices at once); 0 = no bulk query, pairs are fetched one by one.
    var bulkTickersNumOfRequests: Int { 0 }

    /// true: the unfiltered bulk response lists every tradable pair. A missing pair is no longer traded; a single
    /// request would fail as well and only cost time.
    var bulkTickersComplete: Bool { false }

    /// true: the single ticker reads the same complete list as the bulk query and picks the pair from it (Lighter,
    /// Hyperliquid). Its «no entry for this pair» is then as reliable as the pair missing from the bulk list, so a
    /// single-only refresh (too few pairs for the bulk query) may confirm «no longer traded» on its own (`NotTraded`).
    var singleTickerReadsFullList: Bool { false }

    /// Unfiltered URL of bulk request `call`; nil = this request has no unfiltered form.
    func bulkTickersURL(call: Int) -> String? { nil }

    /// Filtered bulk URL for `pairIds` only — a few kilobytes instead of several megabytes where the exchange
    /// accepts a selection. Default: the unfiltered URL.
    func bulkTickersURL(call: Int, pairIds: [String]) -> String? { bulkTickersURL(call: call) }

    /// Number of requests for the filtered query of these pairs. More than `bulkTickersNumOfRequests` when the ids
    /// are split across several URLs (`BulkPairChunks`); unfiltered, every request is then the same.
    func bulkTickersRequestCount(pairIds: [String]) -> Int { bulkTickersNumOfRequests }

    /// POST body for bulk request `call`; nil means GET.
    func bulkTickersPostRequestInfo(call: Int) -> PostRequestInfo? { nil }

    /// Group of bulk request `call` (Binance: spot 0, Alpha 1). If one fails, only its group
    /// (`bulkTickersPairGroup`) loses the «no longer traded» verdict; the other pairs stay complete.
    /// Default: a single group.
    func bulkTickersRequestGroup(call: Int) -> Int { 0 }

    /// Group of a pair, matching `bulkTickersRequestGroup`.
    func bulkTickersPairGroup(pairId: String) -> Int { 0 }

    /// For exchanges without an unfiltered bulk query that reject a whole selection containing one unknown pair
    /// without naming it (Bithumb «Code not found»): URL of the pair list (`pairListAddress`, GET). Used to find
    /// the unknown pairs, drop them from the request and report them as not traded (NotTraded, BulkDelisted).
    /// nil = not applicable.
    func bulkKnownPairsURL(call: Int) -> String? { nil }

    /// All prices of a bulk response, keyed by `MarketPair.pairId` — the same id as in the pair list, otherwise
    /// they could not be matched to a watched pair.
    final func parseBulkTickersMain(call: Int, body: String) throws -> [String: Ticker] {
        try parseBulkTickersDetailed(call: call, body: body).tickers
    }

    /// Like `parseBulkTickersMain`, plus the pairs with an unreadable row (`BulkTickerParse.failed`). If unreadable
    /// rows dominate, throws `BulkFormatError` (text starts with `BulkTickerRows.formatError`).
    final func parseBulkTickersDetailed(call: Int, body: String) throws -> BulkTickerParse {
        var unreadable = Set<String>()
        let good = try parseBulkTickers(call: call, body: body, failed: &unreadable)
        // Ein Paar, das doch noch gelesen wurde, gilt nicht als Fehler
        let broken = unreadable.subtracting(good.keys)

        if BulkTickerRows.isFormatFailure(parsed: good.count, failed: broken.count) {
            throw BulkFormatError(message: BulkTickerRows.formatErrorText(parsed: good.count, failed: broken.count))
        }
        let fetched = good.mapValues { row -> Ticker in
            var stamped = row
            Market.markFetched(&stamped)
            return stamped
        }
        return BulkTickerParse(tickers: fetched, failed: broken,
                               complete: bulkResponseComplete(call: call, body: body))
    }

    /// Hook for exchanges with `bulkTickersComplete`: false when this very response cannot vouch for every tradable
    /// pair (One Trading: rows of other instrument types were dropped or a spot book is closed). A missing pair then
    /// goes to the single request instead of being reported as not traded. Default: true. Android: same name.
    func bulkResponseComplete(call: Int, body: String) -> Bool { true }

    /// Hook: parses a bulk response — returns readable rows keyed by pair id, present but unreadable rows go into
    /// `failed`; easiest via `bulkRowIfActive` or `bulkRow`. Default: no bulk query.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker] { [:] }

    /// The usual bulk row: skipped silently without an id or with an empty price in `priceField` (dormant pair,
    /// `BulkTickerRows.inactivePrice`), otherwise read via `bulkRow`.
    final func bulkRowIfActive(_ pairId: String, row: JObject, priceField: String, tickers: inout [String: Ticker],
                               failed: inout Set<String>, read: (inout Ticker) throws -> Void) {
        if pairId.isEmpty || BulkTickerRows.inactivePrice(row, priceField) { return }
        bulkRow(pairId, tickers: &tickers, failed: &failed, read: read)
    }

    /// Reads one bulk row with `read` into a fresh ticker. If `read` throws (field missing or renamed) or there is
    /// no usable last price (`BulkTickerRows.usable`), `pairId` goes into `failed` — the pair then shows an error
    /// instead of silently staying «unchanged». That is why every error is deliberately turned into this entry
    /// here and not rethrown.
    final func bulkRow(_ pairId: String, tickers: inout [String: Ticker], failed: inout Set<String>,
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

    /// Number of pair-list requests; `call` runs from 0 to count − 1.
    var pairListCallCount: Int { 1 }

    /// true: collect all pair-list responses first, then parse them together in `fillPairListCombined` — for
    /// exchanges whose list only holds ids resolved by a second list (LATOKEN: currency id → symbol). One failed
    /// request then fails the whole sync.
    var pairListCombined: Bool { false }

    /// true: with separately parsed requests, every request must succeed (not just the first) — for exchanges whose
    /// requests each deliver their own half of the offer (Binance Futures: USDⓈ-M and COIN-M). Otherwise half a list
    /// would be saved; this way the previous list stays. Android: `pairListAllCallsRequired`.
    var pairListAllCallsRequired: Bool { false }

    /// Pair-list URL for request `call`; nil = the exchange has none (built-in pairs or search).
    func pairListAddress(call: Int) -> String? { nil }

    /// POST body for pair-list request `call`; nil means GET.
    func pairListPostBody(call: Int) -> PostRequestInfo? { nil }

    /// For exchanges that page their pair list with a cursor (Bybit `nextPageCursor`): URL of the next page after
    /// `body`, nil on the last page. It is fetched with GET and parsed with the same `call`, at most
    /// `maxPairListPages` pages per request. An unreadable response throws (never stop silently).
    /// Android: `pairListNextPage(call, body)`.
    func pairListNextPage(call: Int, body: String) throws -> String? { nil }

    /// Parses a pair-list response (via `fillPairList`) and drops pairs without base or quote.
    final func readPairList(call: Int, body: String) throws -> [MarketPair] {
        try Market.dropIncomplete(fillPairList(call: call, body: body))
    }

    /// Like `readPairList` for exchanges with `pairListCombined`, over all collected responses.
    final func readPairListCombined(bodies: [String]) throws -> [MarketPair] {
        try Market.dropIncomplete(fillPairListCombined(bodies: bodies))
    }

    /// Hook: returns the pairs of a pair-list response (`MarketPair` with base, quote and the pair id used by
    /// `tickerAddress`). Default: parses `body` as a JSON object and calls `fillPairList(call:json:)`; override for
    /// array responses.
    func fillPairList(call: Int, body: String) throws -> [MarketPair] {
        try fillPairList(call: call, json: JObject(string: body))
    }

    /// Hook: reads the pairs of a JSON-object response. Default: none.
    func fillPairList(call: Int, json: JObject) throws -> [MarketPair] { [] }

    /// Hook for exchanges with `pairListCombined`: parses all collected responses at once.
    func fillPairListCombined(bodies: [String]) throws -> [MarketPair] { [] }

    private static func dropIncomplete(_ pairs: [MarketPair]) -> [MarketPair] {
        pairs.filter { pair in !pair.base.isEmpty && !pair.quote.isEmpty }
    }

    // MARK: Volle Coin-Namen (Identität «gleicher Token», K-8)

    /// Optional: symbol → full coin name (e.g. Gate `base_name`) from the same response as the pair list, without
    /// another request. Default: no names. Android: `parseBaseNames`.
    func parseBaseNames(call: Int, body: String) throws -> [String: String] { [:] }

    /// Counterpart of `parseBaseNames` for exchanges with `pairListCombined`.
    func parseBaseNamesCombined(bodies: [String]) throws -> [String: String] { [:] }

    // MARK: Konstanten

    /// Maximum pages per pair-list request, in case a cursor never ends.
    static let maxPairListPages = 10

    /// Message of `noErrorText()`; never reaches the UI (the parse error is shown instead).
    static let noErrorTextMessage = "No error text"

    /// Uhren gehen nie ganz gleich: so weit darf eine Börsenzeit vor der Uhr des Geräts liegen (5 min). Wie Android.
    static let exchangeTimeFutureToleranceMillis: Int64 = 5 * 60 * 1000
}

extension JSONError {
    /// Die Antwort ist gültig, enthält aber keinen Eintrag für das Paar (leere Liste). Android:
    /// `MarketParseException.NO_DATA`.
    static let noData = "No data"
}

extension String {
    /// Prozent-Kodierung eines Abfragewerts, wie Javas `URLEncoder.encode(…, "UTF-8")`.
    var urlQueryEncoded: String {
        // Bewusst nur ASCII erlaubt: `CharacterSet.alphanumerics` liesse auch Umlaute oder CJK-Zeichen
        // unkodiert durch, die URLEncoder sehr wohl kodiert.
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._*")
        return addingPercentEncoding(withAllowedCharacters: unreserved) ?? self
    }
}
