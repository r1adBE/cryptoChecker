package com.cryptochecker.marketdata.model

import com.cryptochecker.marketdata.exceptions.MarketParseException
import com.cryptochecker.marketdata.util.BulkTickerParse
import com.cryptochecker.marketdata.util.BulkTickerRows
import com.cryptochecker.marketdata.util.TimeUtils
import org.json.JSONObject

/** Built-in pairs of an exchange without a fetchable pair list: base → possible quote currencies. */
typealias CurrencyPairsMap = LinkedHashMap<String, Array<String>>

/**
 * Base class of an exchange adapter: HOW to talk to one exchange — URLs, POST bodies, parsing responses and
 * error texts. Networking, storage, retries and UI live elsewhere (`MarketRemoteDataSource` in the app).
 *
 * Template methods: the final entry points `read…`/`parse…Main` handle the shared parts (timestamps, empty
 * pairs, broken rows) and call the overridable hooks `fill…`/`parseBulkTickers`. A subclass overrides only what
 * differs. Sections follow the flow: single ticker · bulk ticker · pair list · coin names. A typical exchange
 * with one pair-list URL and one ticker URL extends
 * [com.cryptochecker.marketdata.model.market.generic.SimpleMarket]. iOS counterpart: `Market.swift`.
 * Step-by-step guide: DEVELOPMENT.md («Adding an exchange»).
 *
 * Instances are shared by concurrent requests: no per-request state in fields (shared caches must be
 * thread-safe). [key] is stored in the database — never change it.
 *
 * @param name display name of the exchange.
 * @param ttsName name for speech output ([SimpleMarket][com.cryptochecker.marketdata.model.market.generic.SimpleMarket]
 *   defaults it to [name]).
 * @param currencyPairs built-in pairs for exchanges without a pair-list endpoint; null otherwise.
 */
abstract class Market(
    val name: String,
    val ttsName: String,
    val currencyPairs: CurrencyPairsMap? = null,
) {

    /**
     * Storage key of the exchange in the database, settings, backups and widgets. Set it explicitly to a fixed
     * string (by convention the class name, identical to the iOS `key`); it used to come from
     * `javaClass.simpleName`, which tied saved data to class names and R8. Never change it after release —
     * `MarketKeyTest` guards the list.
     */
    abstract val key: String

    /**
     * true: the delivered price is a mid price (from bid/ask or mark) rather than the last trade. The action sheet
     * then labels it «mid price»; there is no exchange time, «updated» is the fetch time. iOS: `priceIsMid`.
     */
    open val priceIsMid: Boolean get() = false

    /**
     * true: the price is the close of the newest hourly candle (WOO X spot has no ticker). The action sheet labels it
     * «hourly price». iOS: `priceIsHourlyClose`.
     */
    open val priceIsHourlyClose: Boolean get() = false

    /**
     * true: the 24 h change refers to the mark price (e.g. `markPx` vs. `prevDayPx`), not to the displayed
     * price; no adapter sets it at the moment (Hyperliquid uses the displayed price). Row and action sheet then add «Mark» (`ChangeBasisMath.showsMark`). iOS: `changeIsMark`.
     */
    open val changeIsMark: Boolean get() = false

    /** Abschnitt in der Börsenauswahl ([MarketCategory]); Standard Spot. iOS: `category`. */
    open val category: MarketCategory get() = MarketCategory.SPOT

    /** false: keine Alarme (EZB-Referenzkurse: kein «Hinzufügen», nie ausgewertet). iOS: `allowsAlarms`. */
    open val allowsAlarms: Boolean get() = true

    /** true: der Kurs ist ein täglicher Referenzkurs (EZB), kein Handel — Zeile und Aktionsblatt sagen es. iOS gleich. */
    open val priceIsDailyReference: Boolean get() = false

    // ── Einzelkurs ───────────────────────────────────────────────────────────────

    /** Number of requests per price; `call` runs from 0 to count − 1. Default: one request. */
    open fun tickerCallCount(info: PairQuery?): Int = 1

    /** URL of single-ticker request [call] for the pair in [info] (usually built from [PairQuery.pairId]). */
    abstract fun tickerAddress(call: Int, info: PairQuery): String

    /** POST body for request [call]; null means a plain GET request. */
    open fun tickerPostBody(call: Int, info: PairQuery): PostRequestInfo? = null

    /**
     * Parses a single-ticker response into [ticker] (via [fillTicker]) and returns it. Afterwards
     * [Ticker.timestamp] is the fetch time and [Ticker.exchangeTime] the time reported by the exchange (or null).
     *
     * Why the fetch time counts: «outdated» (OutdatedRule, lastUpdate) asks whether the exchange still lists the
     * pair — and the answer just arrived. A quiet pair with an old last trade (Upbit `trade_timestamp`, Bithumb
     * `trade_date`/`trade_time`) is therefore not outdated within the alarm limit; a last trade older than that limit
     * (2 h, AlarmFreshness) marks the row «outdated – last trade …» too (PriceFreshness.staleReason). If the fetch fails, lastUpdate stays and the pair ages as
     * usual; a delisted pair is missing from the list (NotTraded). The exchange time is only shown
     * (PriceFreshness) so that a standing price does not look fresh. Same as `Market.swift`.
     */
    @Throws(Exception::class)
    fun readTicker(call: Int, body: String, ticker: Ticker, info: PairQuery): Ticker {
        fillTicker(call, body, ticker, info)
        markFetched(ticker)
        return ticker
    }

    /**
     * Hook: fills [ticker] from a single-ticker response. Default: parses [body] as a JSON object and calls
     * [fillTickerJson]; override for array or non-JSON responses. Write an exchange timestamp (s, ms, µs or ns)
     * into [Ticker.timestamp]; [readTicker] moves it to [Ticker.exchangeTime].
     */
    protected open fun fillTicker(call: Int, body: String, ticker: Ticker, info: PairQuery) {
        fillTickerJson(call, JSONObject(body), ticker, info)
    }

    /** Hook: reads the fields of a JSON-object response. Without an override the ticker stays empty («no price»). */
    protected open fun fillTickerJson(call: Int, json: JSONObject, ticker: Ticker, info: PairQuery) = Unit

    /**
     * Ausweichadresse (GET), wenn Einzelanfrage [call] mit einem Serverfehler (HTTP 5xx) scheitert; null = keine
     * (Standard). Die Antwort liest [readTickerFallback]; bringt sie keinen gültigen Kurs, bleibt der Serverfehler
     * (App: `SingleTickerFallback`). iOS gleich.
     */
    open fun tickerFallbackAddress(call: Int, info: PairQuery): String? = null

    /** true: nach diesem Lesefehler [tickerFallbackAddress] versuchen (DexScreener «Pool not found» → GeckoTerminal). */
    open fun tickerFallbackOnParseError(call: Int, error: Exception): Boolean = false

    /** Wie [readTicker], für die Antwort von [tickerFallbackAddress] (über [fillTickerFallback]). */
    @Throws(Exception::class)
    fun readTickerFallback(call: Int, body: String, ticker: Ticker, info: PairQuery): Ticker {
        fillTickerFallback(call, body, ticker, info)
        markFetched(ticker)
        return ticker
    }

    /** Hook zu [tickerFallbackAddress]: fehlt das Paar in der Antwort, werfen. Standard: kein Ausweg. */
    protected open fun fillTickerFallback(call: Int, body: String, ticker: Ticker, info: PairQuery): Unit =
        throw MarketParseException(MarketParseException.NO_DATA)

    /**
     * Error text of the exchange for a ticker response that could not be parsed (via [errorMessage]). Throws if the
     * response contains none; the caller then reports the original parse error.
     */
    @Throws(Exception::class)
    fun readError(call: Int, body: String, info: PairQuery): String? = errorMessage(call, body, info)

    /** Hook: extracts the exchange's error text. Default: parses [body] as a JSON object and calls [errorMessageJson]. */
    protected open fun errorMessage(call: Int, body: String, info: PairQuery): String? =
        errorMessageJson(call, JSONObject(body), info)

    /** Hook: error text from a JSON-object response. Default: none known ([noErrorText]). */
    protected open fun errorMessageJson(call: Int, json: JSONObject, info: PairQuery): String? = noErrorText()

    /** For [errorMessage]/[errorMessageJson]: signals that the response contains no error text of the exchange. */
    protected fun noErrorText(): Nothing = throw MarketParseException(NO_ERROR_TEXT)

    /**
     * Die Leser schreiben eine allfällige Börsenzeit (s, ms, µs oder ns) in [Ticker.timestamp]. Hier wird sie in
     * ms nach [Ticker.exchangeTime] verschoben (≤ 0 = keine) und [Ticker.timestamp] auf jetzt gesetzt. Eine Zeit
     * mehr als [EXCHANGE_TIME_FUTURE_TOLERANCE_MS] in der Zukunft ist falsch gelesen (Ortszeit als Epoche wie
     * Bithumbs KST, falsche Einheit) und fällt weg: Sie liesse einen stehenden Kurs stundenlang frisch aussehen.
     */
    private fun markFetched(ticker: Ticker) {
        val now = System.currentTimeMillis()
        val reported = ticker.timestamp.takeIf { it > 0 }?.let(TimeUtils::parseTimeToMillis)
        ticker.exchangeTime = reported?.takeIf { it <= now + EXCHANGE_TIME_FUTURE_TOLERANCE_MS }
        ticker.timestamp = now
    }

    // ── Sammelabfrage aller Kurse (freiwillig) ───────────────────────────────────

    /** Number of requests of a bulk query (all prices at once); 0 = no bulk query, pairs are fetched one by one. */
    open val bulkTickersNumOfRequests: Int get() = 0

    /**
     * true: the unfiltered bulk response lists every tradable pair. A missing pair is no longer traded; a single
     * request would fail as well and only cost time.
     */
    open val bulkTickersComplete: Boolean get() = false

    /**
     * true: the single ticker reads the same complete list as the bulk query and picks the pair from it (Lighter,
     * Hyperliquid). Its «no entry for this pair» is then as reliable as the pair missing from the bulk list, so a
     * single-only refresh (too few pairs for the bulk query) may confirm «no longer traded» on its own (NotTraded).
     */
    open val singleTickerReadsFullList: Boolean get() = false

    /** Unfiltered URL of bulk request [call]; null = this request has no unfiltered form. */
    open fun getBulkTickersUrl(call: Int): String? = null

    /**
     * Filtered bulk URL for [pairIds] only — a few kilobytes instead of several megabytes where the exchange
     * accepts a selection. Default: the unfiltered URL.
     */
    open fun getBulkTickersUrl(call: Int, pairIds: Collection<String>): String? = getBulkTickersUrl(call)

    /**
     * Number of requests for the filtered query of these pairs. More than [bulkTickersNumOfRequests] when the ids
     * are split across several URLs ([com.cryptochecker.marketdata.util.BulkPairChunks]); unfiltered, every
     * request is then the same.
     */
    open fun bulkTickersRequestCount(pairIds: Collection<String>): Int = bulkTickersNumOfRequests

    /** POST body for bulk request [call]; null means GET. */
    open fun getBulkTickersPostRequestInfo(call: Int): PostRequestInfo? = null

    /**
     * Group of bulk request [call] (Binance: spot 0, Alpha 1). If one fails, only its group
     * ([bulkTickersPairGroup]) loses the «no longer traded» verdict; the other pairs stay complete.
     * Default: a single group.
     */
    open fun bulkTickersRequestGroup(call: Int): Int = 0

    /** Group of a pair, matching [bulkTickersRequestGroup]. */
    open fun bulkTickersPairGroup(pairId: String): Int = 0

    /**
     * For exchanges without an unfiltered bulk query that reject a whole selection containing one unknown pair
     * without naming it (Bithumb «Code not found»): URL of the pair list ([pairListAddress], GET). Used to find
     * the unknown pairs, drop them from the request and report them as not traded (NotTraded, BulkDelisted).
     * null = not applicable.
     */
    open fun getBulkKnownPairsUrl(call: Int): String? = null

    /**
     * All prices of a bulk response, keyed by [MarketPair.pairId] — the same id as in the pair list, otherwise
     * they could not be matched to a watched pair.
     */
    @Throws(Exception::class)
    fun parseBulkTickersMain(call: Int, body: String): Map<String, Ticker> =
        parseBulkTickersDetailed(call, body).tickers

    /**
     * Like [parseBulkTickersMain], plus the pairs with an unreadable row ([BulkTickerParse.failed]). If unreadable
     * rows dominate, throws [MarketParseException] with [BulkTickerRows.FORMAT_ERROR].
     */
    @Throws(Exception::class)
    fun parseBulkTickersDetailed(call: Int, body: String): BulkTickerParse {
        val good = LinkedHashMap<String, Ticker>()
        val broken = LinkedHashSet<String>()
        parseBulkTickers(call, body, good, broken)
        // Ein Paar, das doch noch gelesen wurde, gilt nicht als Fehler
        broken -= good.keys

        if (BulkTickerRows.isFormatFailure(good.size, broken.size)) {
            throw MarketParseException(BulkTickerRows.formatErrorText(good.size, broken.size))
        }
        good.values.forEach(::markFetched)
        return BulkTickerParse(good, broken, bulkResponseComplete(call, body))
    }

    /**
     * Hook for exchanges with [bulkTickersComplete]: false when this very response cannot vouch for every tradable
     * pair (One Trading: rows of other instrument types were dropped or a spot book is closed). A missing pair then
     * goes to the single request instead of being reported as not traded. Default: true. iOS: same name.
     */
    protected open fun bulkResponseComplete(call: Int, body: String): Boolean = true

    /**
     * Hook: parses a bulk response — readable rows into [tickers] (keyed by pair id), present but unreadable rows
     * into [failed]; easiest via [bulkRowIfActive] or [bulkRow]. Default: no bulk query.
     */
    protected open fun parseBulkTickers(
        call: Int,
        body: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
    ) = Unit

    /**
     * The usual bulk row: skipped silently without an id or with an empty price in [priceField] (dormant pair,
     * [BulkTickerRows.inactivePrice]), otherwise read via [bulkRow].
     */
    protected fun bulkRowIfActive(
        pairId: String,
        row: JSONObject,
        priceField: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
        read: (Ticker) -> Unit,
    ) {
        if (pairId.isEmpty() || BulkTickerRows.inactivePrice(row, priceField)) return
        bulkRow(pairId, tickers, failed, read)
    }

    /**
     * Reads one bulk row with [read] into a fresh ticker. If [read] throws (field missing or renamed) or there is
     * no usable last price ([BulkTickerRows.usable]), [pairId] goes into [failed] — the pair then shows an error
     * instead of silently staying «unchanged». That is why every exception is deliberately turned into this
     * entry here and not rethrown.
     */
    protected fun bulkRow(
        pairId: String,
        tickers: MutableMap<String, Ticker>,
        failed: MutableSet<String>,
        read: (Ticker) -> Unit,
    ) {
        val row = SimpleTicker()
        val readable = try {
            read(row)
            BulkTickerRows.usable(row)
        } catch (_: Exception) {
            false
        }
        if (readable) {
            tickers[pairId] = row
        } else {
            failed.add(pairId)
        }
    }

    // ── Paarliste ────────────────────────────────────────────────────────────────

    /** Number of pair-list requests; `call` runs from 0 to count − 1. */
    open val pairListCallCount: Int get() = 1

    /**
     * true: collect all pair-list responses first, then parse them together in [fillPairListCombined] — for
     * exchanges whose list only holds ids resolved by a second list (LATOKEN: currency id → symbol). One failed
     * request then fails the whole sync.
     */
    open val pairListCombined: Boolean get() = false

    /**
     * true: with separately parsed requests, every request must succeed (not just the first) — for exchanges whose
     * requests each deliver their own half of the offer (Binance Futures: USDⓈ-M and COIN-M). Otherwise half a list
     * would be saved and the other half would vanish until the next sync; this way the previous list stays.
     * iOS: `pairListAllCallsRequired`.
     */
    open val pairListAllCallsRequired: Boolean get() = false

    /** Pair-list URL for request [call]; null = the exchange has none (built-in pairs or search). */
    open fun pairListAddress(call: Int): String? = null

    /** POST body for pair-list request [call]; null means GET. */
    open fun pairListPostBody(call: Int): PostRequestInfo? = null

    /**
     * For exchanges that page their pair list with a cursor (Bybit `nextPageCursor`): URL of the next page after
     * [body], null on the last page. It is fetched with GET and parsed with the same [call], at most
     * [MAX_PAIR_LIST_PAGES] pages per request. An unreadable response throws (never stop silently).
     * iOS: `pairListNextPage(call:body:)`.
     */
    open fun pairListNextPage(call: Int, body: String): String? = null

    /** Parses a pair-list response into [out] (via [fillPairList]) and drops pairs without base or quote. */
    @Throws(Exception::class)
    fun readPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        fillPairList(call, body, out)
        dropIncomplete(out)
    }

    /** Like [readPairList] for exchanges with [pairListCombined], over all collected responses. */
    @Throws(Exception::class)
    fun readPairListCombined(bodies: List<String>, out: MutableList<MarketPair>) {
        fillPairListCombined(bodies, out)
        dropIncomplete(out)
    }

    /**
     * Hook: adds the pairs of a pair-list response to [out] ([MarketPair] with base, quote and the pair id used by
     * [tickerAddress]). Default: parses [body] as a JSON object and calls [fillPairListJson]; override for array
     * responses.
     */
    protected open fun fillPairList(call: Int, body: String, out: MutableList<MarketPair>) {
        fillPairListJson(call, JSONObject(body), out)
    }

    /** Hook: reads the pairs of a JSON-object response. Default: none. */
    protected open fun fillPairListJson(call: Int, json: JSONObject, out: MutableList<MarketPair>) = Unit

    /** Hook for exchanges with [pairListCombined]: parses all collected responses at once. */
    protected open fun fillPairListCombined(bodies: List<String>, out: MutableList<MarketPair>) = Unit

    // Reines Kotlin (kein TextUtils), damit die Vertragstests der Paarlisten auf der JVM laufen
    private fun dropIncomplete(out: MutableList<MarketPair>) {
        out.removeAll { pair -> pair.base.isEmpty() || pair.quote.isEmpty() }
    }

    // ── Volle Coin-Namen (Identität «gleicher Token», K-8) ───────────────────────

    /**
     * Optional: symbol → full coin name (e.g. Gate `base_name`) from the same response as the pair list, without
     * another request. Default: no names. iOS: `parseBaseNames`.
     */
    @Throws(Exception::class)
    open fun parseBaseNames(call: Int, body: String): Map<String, String> = emptyMap()

    /** Counterpart of [parseBaseNames] for exchanges with [pairListCombined]. */
    @Throws(Exception::class)
    open fun parseBaseNamesCombined(bodies: List<String>): Map<String, String> = emptyMap()

    companion object {
        /** Maximum pages per pair-list request, in case a cursor never ends. */
        const val MAX_PAIR_LIST_PAGES = 10

        /** Message of [noErrorText]; never reaches the UI (the parse error is shown instead). */
        const val NO_ERROR_TEXT = "No error text"

        /** Uhren gehen nie ganz gleich: so weit darf eine Börsenzeit vor der Uhr des Geräts liegen (5 min). iOS gleich. */
        const val EXCHANGE_TIME_FUTURE_TOLERANCE_MS = 5L * 60 * 1000
    }
}
