import Foundation

/// Built-in pairs of an exchange without a fetchable pair list: base → possible quote currencies.
typealias CurrencyPairsMap = [String: [String]]

/// Exchange adapter: HOW to talk to one exchange — URLs, POST bodies, parsing responses and error texts.
/// Networking, storage, retries and UI live elsewhere (`ExchangeService`).
///
/// Template methods: the entry points `read…`/`parse…Main` (Exchange+Defaults.swift, not requirements) handle the
/// shared parts (timestamps, empty pairs, broken rows) and call the hooks `fill…`/`parseBulkTickers`. An adapter
/// implements only what differs; everything else has a default in Exchange+Defaults.swift. Sections follow the flow:
/// single ticker · bulk ticker · pair list · coin names. A typical exchange with one pair-list URL and one ticker URL
/// adopts `SimpleExchange`. Android counterpart: `Exchange.kt`. Step-by-step guide: DEVELOPMENT.md («Adding an
/// exchange»); register new adapters in `ExchangesConfig.all`.
///
/// Instances are shared by concurrent requests, so the protocol requires `Sendable` — checked by the compiler: an
/// adapter is a `struct` with constants, or a `final class` whose changing state sits behind an
/// `OSAllocatedUnfairLock` (Gemini). `key` equals the Android key (the Kotlin class name) and is stored in backups of
/// both platforms — never rename it. Shared numbers: `ExchangeConstants`.
protocol Exchange: Sendable {
    /// Storage key, identical to Android `Exchange.key`. Never change it after release.
    var key: String { get }
    /// Display name of the exchange.
    var name: String { get }
    /// Name for speech output; defaults to `name`.
    var ttsName: String { get }
    /// Only for a few exchanges: built-in pairs for the time before the first pair-list sync. Default: nil.
    var currencyPairs: CurrencyPairsMap? { get }

    /// true: the delivered price is a mid price (from bid/ask or mark) rather than the last trade. The action sheet
    /// then labels it «mid price»; there is no exchange time, «updated» is the fetch time. Android: `priceIsMid`.
    var priceIsMid: Bool { get }

    /// true: the price is the close of the newest hourly candle (WOO X spot has no ticker). The action sheet labels it
    /// «hourly price». Android: `priceIsHourlyClose`.
    var priceIsHourlyClose: Bool { get }

    /// true: the 24 h change refers to the mark price (e.g. `markPx` vs. `prevDayPx`), not to the displayed
    /// price; no adapter sets it at the moment (Hyperliquid uses the displayed price). Row and action sheet then add «Mark» (`ChangeBasisMath.showsMark`). Android: `changeIsMark`.
    var changeIsMark: Bool { get }

    /// Abschnitt der Börsenauswahl (Spot, Futures, DEX-Pools, Forex). Standard: Spot. Android: `category`.
    var category: ExchangeCategory { get }

    /// false: Kurse dieser Quelle lösen nie Alarme aus (EZB-Referenzkurse, einmal täglich festgelegt) — die
    /// Alarm-Seite bietet kein «Hinzufügen», die Auswertung überspringt sie. Android: `allowsAlarms`.
    var allowsAlarms: Bool { get }

    /// true: der Kurs ist ein täglicher Referenzkurs (EZB), nie live. Zeile und Aktionsblatt zeigen «Referenzkurs,
    /// täglich». Android: `priceIsDailyReference`.
    var priceIsDailyReference: Bool { get }

    // MARK: Einzelkurs

    /// Number of requests per price; `call` runs from 0 to count − 1. Default: one request.
    func tickerCallCount(_ info: PairQuery) -> Int

    /// URL of single-ticker request `call` for the pair in `info` — every exchange must provide it (abstract in
    /// Kotlin; `SimpleExchange` builds it from `tickerURL`).
    func tickerAddress(call: Int, info: PairQuery) -> String

    /// Fehler statt Anfrage, wenn die Kennung nicht in die Adresse darf (DexScreener `pairId`); nil =
    /// anfragen. Android: `tickerAddress` wirft.
    func tickerQueryError(call: Int, info: PairQuery) -> Error?

    /// POST body for request `call`; nil means a plain GET request.
    func tickerPostBody(call: Int, info: PairQuery) -> PostRequest?

    /// Hook: fills `ticker` from a single-ticker response. Default: parses `body` as a JSON object and calls
    /// `fillTicker(call:json:…)`; implement for array or non-JSON responses. Write an exchange timestamp (s, ms, µs
    /// or ns) into `timestamp`; `readTicker` moves it to `exchangeTime`.
    func fillTicker(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws

    /// Hook: reads the fields of a JSON-object response. Without an implementation the ticker stays empty («no price»).
    func fillTicker(call: Int, json: JObject, ticker: inout Ticker, info: PairQuery) throws

    /// Ausweichadresse (GET), wenn Einzelanfrage `call` mit einem Serverfehler (HTTP 5xx) scheitert; nil = keine
    /// (Standard). Die Antwort liest `readTickerFallback`; bringt sie keinen gültigen Kurs, bleibt der Serverfehler
    /// (`SingleTickerFallback`). Android gleich.
    func tickerFallbackAddress(call: Int, info: PairQuery) -> String?

    /// true: auch dieser Lesefehler des Einzelkurses (`error`, z. B. DexScreener «Pool not found») führt zur
    /// Ausweichadresse (`SingleTickerFallback.loadAfterMissing`); bringt sie keinen Kurs, bleibt `error`.
    /// Standard: nein. Android gleich.
    func tickerFallbackOnParseError(call: Int, error: Error) -> Bool

    /// Hook zu `tickerFallbackAddress`: fehlt das Paar in der Antwort, werfen. Standard: kein Ausweg.
    func fillTickerFallback(call: Int, body: String, ticker: inout Ticker, info: PairQuery) throws

    /// Hook: extracts the exchange's error text. Default: parses `body` as a JSON object and calls `errorMessage(call:json:info:)`.
    func errorMessage(call: Int, body: String, info: PairQuery) throws -> String?

    /// Hook: error text from a JSON-object response. Default: none known (`noErrorText()`).
    func errorMessage(call: Int, json: JObject, info: PairQuery) throws -> String?

    // MARK: Sammelabfrage

    /// Number of requests of a bulk query (all prices at once); 0 = no bulk query, pairs are fetched one by one.
    var bulkTickersNumOfRequests: Int { get }

    /// true: the unfiltered bulk response lists every tradable pair. A missing pair is no longer traded; a single
    /// request would fail as well and only cost time.
    var bulkTickersComplete: Bool { get }

    /// true: the single ticker reads the same complete list as the bulk query and picks the pair from it (Lighter,
    /// Hyperliquid). Its «no entry for this pair» is then as reliable as the pair missing from the bulk list, so a
    /// single-only refresh (too few pairs for the bulk query) may confirm «no longer traded» on its own (`NotTraded`).
    var singleTickerReadsFullList: Bool { get }

    /// Unfiltered URL of bulk request `call`; nil = this request has no unfiltered form.
    func bulkTickersURL(call: Int) -> String?

    /// Filtered bulk URL for `pairIds` only — a few kilobytes instead of several megabytes where the exchange
    /// accepts a selection. Default: the unfiltered URL.
    func bulkTickersURL(call: Int, pairIds: [String]) -> String?

    /// Number of requests for the filtered query of these pairs. More than `bulkTickersNumOfRequests` when the ids
    /// are split across several URLs (`BulkPairChunks`); unfiltered, every request is then the same.
    func bulkTickersRequestCount(pairIds: [String]) -> Int

    /// POST body for bulk request `call`; nil means GET.
    func bulkTickersPostRequest(call: Int) -> PostRequest?

    /// Group of bulk request `call` (Binance: spot 0, Alpha 1). If one fails, only its group
    /// (`bulkTickersPairGroup`) loses the «no longer traded» verdict; the other pairs stay complete.
    /// Default: a single group.
    func bulkTickersRequestGroup(call: Int) -> Int

    /// Group of a pair, matching `bulkTickersRequestGroup`.
    func bulkTickersPairGroup(pairId: String) -> Int

    /// For exchanges without an unfiltered bulk query that reject a whole selection containing one unknown pair
    /// without naming it (Bithumb «Code not found»): URL of the pair list (`pairListAddress`, GET). Used to find
    /// the unknown pairs, drop them from the request and report them as not traded (NotTraded, BulkDelisted).
    /// nil = not applicable.
    func bulkKnownPairsURL(call: Int) -> String?

    /// Hook for exchanges with `bulkTickersComplete`: false when this very response cannot vouch for every tradable
    /// pair (One Trading: rows of other instrument types were dropped or a spot book is closed). A missing pair then
    /// goes to the single request instead of being reported as not traded. Default: true. Android: same name.
    func bulkResponseComplete(call: Int, body: String) -> Bool

    /// Hook: parses a bulk response — returns readable rows keyed by pair id, present but unreadable rows go into
    /// `failed`; easiest via `bulkRowIfActive` or `bulkRow`. Default: no bulk query.
    func parseBulkTickers(call: Int, body: String, failed: inout Set<String>) throws -> [String: Ticker]

    // MARK: Paarliste

    /// Number of pair-list requests; `call` runs from 0 to count − 1.
    var pairListCallCount: Int { get }

    /// true: collect all pair-list responses first, then parse them together in `fillPairListCombined` — for
    /// exchanges whose list only holds ids resolved by a second list (LATOKEN: currency id → symbol). One failed
    /// request then fails the whole sync.
    var pairListCombined: Bool { get }

    /// true: with separately parsed requests, every request must succeed (not just the first) — for exchanges whose
    /// requests each deliver their own half of the offer (Binance Futures: USDⓈ-M and COIN-M). Otherwise half a list
    /// would be saved; this way the previous list stays. Android: `pairListAllCallsRequired`.
    var pairListAllCallsRequired: Bool { get }

    /// Pair-list URL for request `call`; nil = the exchange has none (built-in pairs or search).
    func pairListAddress(call: Int) -> String?

    /// POST body for pair-list request `call`; nil means GET.
    func pairListPostBody(call: Int) -> PostRequest?

    /// For exchanges that page their pair list with a cursor (Bybit `nextPageCursor`): URL of the next page after
    /// `body`, nil on the last page. It is fetched with GET and parsed with the same `call`, at most
    /// `ExchangeConstants.maxPairListPages` pages per request. An unreadable response throws (never stop silently).
    /// Android: `pairListNextPage(call, body)`.
    func pairListNextPage(call: Int, body: String) throws -> String?

    /// Hook: returns the pairs of a pair-list response (`ExchangePair` with base, quote and the pair id used by
    /// `tickerAddress`). Default: parses `body` as a JSON object and calls `fillPairList(call:json:)`; implement for
    /// array responses.
    func fillPairList(call: Int, body: String) throws -> [ExchangePair]

    /// Hook: reads the pairs of a JSON-object response. Default: none.
    func fillPairList(call: Int, json: JObject) throws -> [ExchangePair]

    /// Hook for exchanges with `pairListCombined`: parses all collected responses at once.
    func fillPairListCombined(bodies: [String]) throws -> [ExchangePair]

    // MARK: Volle Coin-Namen (Identität «gleicher Token»)

    /// Optional: symbol → full coin name (e.g. Gate `base_name`) from the same response as the pair list, without
    /// another request. Default: no names. Android: `parseBaseNames`.
    func parseBaseNames(call: Int, body: String) throws -> [String: String]

    /// Counterpart of `parseBaseNames` for exchanges with `pairListCombined`.
    func parseBaseNamesCombined(bodies: [String]) throws -> [String: String]
}

/// Numbers shared by all adapters (a protocol cannot hold stored constants). Android: companion of `Exchange.kt`.
enum ExchangeConstants {
    /// Maximum pages per pair-list request, in case a cursor never ends.
    static let maxPairListPages = 10

    /// Message of `noErrorText()`; never reaches the UI (the parse error is shown instead).
    static let noErrorTextMessage = "No error text"

    /// Uhren gehen nie ganz gleich: so weit darf eine Börsenzeit vor der Uhr des Geräts liegen (5 min). Wie Android.
    static let exchangeTimeFutureToleranceMillis: Int64 = 5 * 60 * 1000
}

extension JSONError {
    /// Die Antwort ist gültig, enthält aber keinen Eintrag für das Paar (leere Liste). Android:
    /// `ExchangeParseException.NO_DATA`.
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
