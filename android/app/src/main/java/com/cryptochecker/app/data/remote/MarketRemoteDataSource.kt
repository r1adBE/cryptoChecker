package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.TickerImpl
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.InFlightRequests
import com.cryptochecker.app.data.remote.util.await
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.exceptions.MarketError
import com.cryptochecker.app.domain.exceptions.ParseError
import com.cryptochecker.app.domain.exceptions.ResponseTooLargeError
import com.cryptochecker.app.domain.exceptions.TimeoutError
import com.cryptochecker.app.domain.exceptions.UserFriendlyMarketError
import com.cryptochecker.app.domain.exceptions.parseMarketError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.exceptions.toMarketFailure
import com.cryptochecker.app.domain.mirror.DataMirror
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketPairsInfo
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.MarketFailure
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.MarketPair
import com.cryptochecker.marketdata.model.Market
import com.cryptochecker.marketdata.model.PairListPaging
import com.cryptochecker.marketdata.model.pairListPaging
import com.cryptochecker.marketdata.model.PostRequestInfo
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import com.cryptochecker.marketdata.model.market.DexPool
import com.cryptochecker.marketdata.model.market.DexScreener
import com.cryptochecker.marketdata.util.BulkCompleteness
import com.cryptochecker.marketdata.util.BulkTickerParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.InterruptedIOException
import java.io.InvalidObjectException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Netzzugriff auf die Börsen: Paarlisten, Einzelkurse, Sammelabfragen und die DEX-Suche. */
class MarketRemoteDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /**
     * Sammelabfragen brauchen etwas mehr Zeit als ein Einzelkurs — aber nicht die einstigen 60 s:
     * Eine hängende Verbindung hielt sonst den ganzen Durchlauf bis zu zwei Minuten auf ([callMarket]).
     */
    private val bulkClient: OkHttpClient by lazy { httpClient.withTimeouts(BULK_TIMEOUT_SECONDS) }

    /** Paarlisten sind gross: eigene, längere Frist. */
    private val pairListClient: OkHttpClient by lazy { httpClient.withTimeouts(PAIR_LIST_TIMEOUT_SECONDS) }

    // ---------------------------------------------------------------------------------------------
    // Paarliste
    // ---------------------------------------------------------------------------------------------

    /**
     * Lädt die ganze Paarliste der Börse. Scheitert die erste Anfrage (oder bei gemeinsam
     * ausgewerteten Listen irgendeine), scheitert der Abgleich; spätere Anfragen dürfen fehlen.
     */
    suspend fun fetchMarketCurrencyPairsInfo(market: Market): MarketPairsInfo =
        if (market.pairListCombined) loadCombinedPairList(market, pairListClient)
        else loadPairListByCall(market, pairListClient)

    /** Alle Antworten zuerst sammeln, dann gemeinsam lesen (z. B. LATOKEN: IDs → Kürzel). */
    private suspend fun loadCombinedPairList(market: Market, client: OkHttpClient): MarketPairsInfo {
        val bodies = List(market.pairListCallCount) { call ->
            val address = market.pairListAddress(call)
            if (address.isNullOrEmpty()) ""
            else client.callMarket(address, market.pairListPostBody(call), HttpBodyLimit.BULK_MAX_BYTES)
        }
        val collected = ArrayList<MarketPair>()
        try {
            market.readPairListCombined(bodies, collected)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            throw ParseError(ex)
        }
        collected.sort()
        // Volle Coin-Namen (K-8) aus denselben Antworten; ein Fehler dabei lässt die Liste unberührt
        val names = runCatchingCancellable { market.parseBaseNamesCombined(bodies) }.getOrDefault(emptyMap())
        return MarketPairsInfo(lastSyncDate = System.currentTimeMillis(), pairs = collected, baseNames = names)
    }

    /**
     * Jede Anfrage für sich lesen (samt Folgeseiten); nur die erste muss gelingen — bei
     * [Market.pairListAllCallsRequired] jede (sonst würde eine halbe Liste gespeichert).
     */
    private suspend fun loadPairListByCall(market: Market, client: OkHttpClient): MarketPairsInfo {
        val collected = ArrayList<MarketPair>()
        val names = LinkedHashMap<String, String>()
        for (call in 0 until market.pairListCallCount) {
            try {
                loadPairListCall(market, client, call, collected, names)
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                if (call == 0 || market.pairListAllCallsRequired) throw ex
            }
        }
        collected.sort()
        return MarketPairsInfo(lastSyncDate = System.currentTimeMillis(), pairs = collected, baseNames = names)
    }

    /**
     * Eine Anfrage der Paarliste samt Folgeseiten (z. B. Bybit `nextPageCursor`, Gemini-Details; höchstens
     * [Market.MAX_PAIR_LIST_PAGES] Seiten). Den Blätterzustand hält ein eigenes [PairListPaging] dieser
     * Anfrage, nie die geteilte Börse. Eine unlesbare Folgeseite lässt die Anfrage scheitern — sonst fehlten
     * Paare unbemerkt; eine unlesbare erste Seite nur bei der ersten Anfrage.
     */
    private suspend fun loadPairListCall(
        market: Market,
        client: OkHttpClient,
        call: Int,
        into: MutableList<MarketPair>,
        names: MutableMap<String, String>,
    ) {
        val address = market.pairListAddress(call)
        val postBody = market.pairListPostBody(call)
        if (address.isNullOrEmpty()) return

        val firstBody = client.callMarket(address, postBody, HttpBodyLimit.BULK_MAX_BYTES)
        into += readPairPage(market, call, firstBody, strict = call == 0)
        runCatchingCancellable { market.parseBaseNames(call, firstBody) }.getOrNull()
            ?.forEach { (symbol, name) -> names.putIfAbsent(symbol, name) }

        val paging = market.pairListPaging()
        var nextAddress = paging.nextPage(call, firstBody)
        var pagesLoaded = 1
        while (!nextAddress.isNullOrEmpty() && pagesLoaded < Market.MAX_PAIR_LIST_PAGES) {
            val pageBody = client.callMarket(nextAddress, null, HttpBodyLimit.BULK_MAX_BYTES)
            into += readPairPage(market, call, pageBody, strict = true)
            nextAddress = paging.nextPage(call, pageBody)
            pagesLoaded++
        }
    }

    /**
     * Eine Seite lesen. [strict]: Fehler als [ParseError] weiterwerfen; sonst zählt, was bis zum
     * Fehler gelesen wurde.
     */
    private suspend fun readPairPage(market: Market, call: Int, body: String, strict: Boolean): List<MarketPair> {
        val page = ArrayList<MarketPair>()
        try {
            market.readPairList(call, body, page)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            if (strict) throw ParseError(ex)
        }
        return page
    }

    // ---------------------------------------------------------------------------------------------
    // Sammelabfrage
    // ---------------------------------------------------------------------------------------------

    /**
     * Kurse vieler Paare in möglichst wenigen Anfragen, Schlüssel = Paar-Kennung der Börse.
     *
     * @param pairIds die beobachteten Paare. Kann die Börse filtern, wird nur nach ihnen gefragt;
     *   lehnt sie das ab (etwa weil ein Paar nicht mehr gehandelt wird), folgt einmal die
     *   ungefilterte Abfrage.
     */
    suspend fun fetchBulkTickers(market: Market, pairIds: Collection<String>): BulkTickers {
        if (market.bulkTickersNumOfRequests <= 0) return BulkTickers()
        // Lange Listen verteilt die Börse auf mehrere gefilterte Anfragen
        val callCount = market.bulkTickersRequestCount(pairIds)
        val client = bulkClient

        val tickers = LinkedHashMap<String, Ticker>()
        // Paare mit unlesbarer Zeile: Fehler statt still «unverändert»
        val unreadable = LinkedHashSet<String>()
        // Gruppen gescheiterter/ausgelassener Teilabfragen (Market.bulkTickersRequestGroup): nur deren
        // Paare gelten als unvollständig — bei Binance bleibt Spot ohne Alpha vollständig
        val brokenGroups = HashSet<Int>()
        // Bereits geholte ungefilterte Abfragen decken jede Teilanfrage mit derselben Adresse ab
        val fullDone = HashSet<String>()
        // «Zu viele Anfragen» bei einer Folgeanfrage: Rest weglassen, Text für die Pause (ExchangeBackoff)
        var throttled: String? = null
        var throttledFailure: MarketFailure? = null

        for (call in 0 until callCount) {
            val filtered = market.getBulkTickersUrl(call, pairIds)
            val full = market.getBulkTickersUrl(call)
            if (filtered.isNullOrEmpty() || filtered in fullDone) continue

            try {
                val part = loadBulkCall(client, market, call, pairIds, filtered, full, fullDone) ?: continue
                tickers.putAll(part.tickers)
                unreadable.addAll(part.failed)
                if (part.failed.isNotEmpty()) {
                    Timber.w("Sammelabfrage: %d unlesbare Zeilen (market=%s)", part.failed.size, market.key)
                }
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                Timber.w(ex, "Sammelabfrage gescheitert (market=%s, Teil %d)", market.key, call)
                brokenGroups += market.bulkTickersRequestGroup(call)
                // Ohne die erste (tragende) Anfrage lohnt der Rest nicht
                if (call == 0) throw ex
                if (ex.isRateLimit()) {
                    throttled = ex.message ?: ex.javaClass.simpleName
                    throttledFailure = ex.toMarketFailure()
                    // Weggelassene Teile sagen ebenfalls nichts über ihre Paare
                    for (skipped in call + 1 until callCount) brokenGroups += market.bulkTickersRequestGroup(skipped)
                    break
                }
            }
        }

        val incomplete = BulkCompleteness.incomplete(pairIds, brokenGroups, market::bulkTickersPairGroup)
        val complete = market.bulkTickersComplete && tickers.isNotEmpty() &&
            BulkCompleteness.anyComplete(pairIds, brokenGroups, incomplete)
        return BulkTickers(
            tickers = tickers,
            complete = complete,
            error = throttled,
            failure = throttledFailure,
            failed = unreadable - tickers.keys,
            incomplete = incomplete,
        )
    }

    /**
     * Eine Teilanfrage, gefiltert. Wird sie abgelehnt oder ist sie unlesbar: einmal ungefiltert
     * ([full]) — ohne ungefilterte Adresse über die Paarliste ohne unbekannte Paare (Bithumb); gelingt
     * auch das nicht, gilt der ursprüngliche Fehler. null = schon durch eine ungefilterte Abfrage erledigt.
     */
    private suspend fun loadBulkCall(
        client: OkHttpClient,
        market: Market,
        call: Int,
        pairIds: Collection<String>,
        filtered: String,
        full: String?,
        fullDone: MutableSet<String>,
    ): BulkTickerParse? {
        val postBody = market.getBulkTickersPostRequestInfo(call)
        return try {
            loadBulk(client, market, call, filtered, postBody)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            if (ex.isRateLimit()) throw ex
            if (full.isNullOrEmpty() || full == filtered) {
                retryWithoutUnknown(client, market, call, pairIds, postBody, ex) ?: throw ex
            } else if (full in fullDone) {
                null
            } else {
                Timber.w(ex, "Gefilterte Sammelabfrage gescheitert, lade alle Paare: %s", market.key)
                val body = client.callMarket(full, postBody, HttpBodyLimit.BULK_MAX_BYTES)
                market.parseBulkTickersDetailed(call, body).also { fullDone.add(full) }
            }
        }
    }

    /** [withoutUnknownPairs], ein Fehler darin ergibt null (dann zählt der ursprüngliche). */
    private suspend fun retryWithoutUnknown(
        client: OkHttpClient, market: Market, call: Int, pairIds: Collection<String>,
        postBody: PostRequestInfo?, original: Exception,
    ): BulkTickerParse? = try {
        withoutUnknownPairs(client, market, call, pairIds, postBody, original)
    } catch (retryEx: Exception) {
        retryEx.rethrowIfCritical()
        null
    }

    /**
     * Gefilterte Anfrage ohne ungefilterten Ersatz abgelehnt ([Market.getBulkKnownPairsUrl], Bithumb):
     * Paarliste holen, angefragte Paare, die dort fehlen, weglassen und ohne sie nochmals fragen. Die
     * weggelassenen fehlen dann in der (vollständigen) Antwort → die Einzelabfrage bestätigt «nicht mehr
     * gehandelt», BulkDelisted lässt sie künftig weg. null = geht nicht oder kein Paar unbekannt.
     */
    private suspend fun withoutUnknownPairs(
        client: OkHttpClient, market: Market, call: Int, pairIds: Collection<String>,
        postBody: PostRequestInfo?, original: Exception,
    ): BulkTickerParse? {
        val listAddress = market.getBulkKnownPairsUrl(call)
        if (listAddress.isNullOrEmpty()) return null

        val listed = ArrayList<MarketPair>()
        market.readPairList(0, client.callMarket(listAddress, null, HttpBodyLimit.BULK_MAX_BYTES), listed)
        val knownIds = listed.mapNotNullTo(HashSet()) { it.pairId }
        val unknown = BulkCompleteness.unknownPairs(pairIds, knownIds) ?: return null

        Timber.w(original, "Sammelabfrage ohne %d unbekannte Paare erneut: %s", unknown.size, market.key)
        val remaining = pairIds.filterNot { it in unknown }
        if (remaining.isEmpty()) return BulkTickerParse(emptyMap(), emptySet())
        val address = market.getBulkTickersUrl(call, remaining)
        if (address.isNullOrEmpty()) return null
        return loadBulk(client, market, call, address, postBody)
    }

    /**
     * Teilanfrage holen und lesen. Die (grosse, für alle gleiche) Alpha-Liste von Binance gilt
     * [BinanceAlpha.LIST_CACHE_MILLIS] lang für alle Aufrufer — eine Aktualisierung lädt sie nie zweimal.
     */
    private suspend fun loadBulk(
        client: OkHttpClient, market: Market, call: Int, address: String, postBody: PostRequestInfo?,
    ): BulkTickerParse {
        val isAlphaList = postBody == null && address == BinanceAlpha.TOKEN_LIST_URL
        val fromCache = if (isAlphaList) BinanceAlpha.listCache.get(System.currentTimeMillis()) else null
        val body = fromCache ?: client.callMarket(address, postBody, HttpBodyLimit.BULK_MAX_BYTES)
        val parsed = market.parseBulkTickersDetailed(call, body)
        // Erst nach erfolgreichem Lesen merken
        if (isAlphaList && fromCache == null) BinanceAlpha.listCache.put(body, System.currentTimeMillis())
        return parsed
    }

    /** Hat die Börse gebremst (429, auch erkannt über [RateLimitSignal]) oder gesperrt (418)? */
    private fun Exception.isRateLimit(): Boolean =
        this is HttpMarketError && ExchangeBackoff.isRateLimitCode(httpCode)

    // ---------------------------------------------------------------------------------------------
    // DEX-Suche und Einzelkurs
    // ---------------------------------------------------------------------------------------------

    /** Pools über DexScreener suchen. */
    suspend fun searchDexPools(query: String): List<DexPool> =
        DexScreener.parseSearch(httpClient.callMarket(DexScreener.searchUrl(query), null))

    /**
     * Kurs eines Paars. Die erste Anfrage muss gelingen; weitere (z. B. Volumen aus einer zweiten
     * Quelle) ergänzen nur und dürfen scheitern.
     */
    suspend fun fetchMarketTicker(market: Market, checkerInfo: PairQuery): Ticker {
        val ticker = TickerImpl()
        loadTickerCall(ticker, market, 0, checkerInfo)

        for (call in 1 until market.tickerCallCount(checkerInfo)) {
            try {
                loadTickerCall(ticker, market, call, checkerInfo)
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                Timber.e(ex, "Zusätzliche Kursanfrage #%d gescheitert", call)
            }
        }
        return ticker
    }

    /**
     * Teil [call] des Kurses holen und in [ticker] lesen. Ohne Adresse: bei Teil 0 ein Fehler, sonst
     * nichts. Unlesbar → Fehlermeldung der Börse als [UserFriendlyMarketError], sonst der Lesefehler.
     */
    private suspend fun loadTickerCall(ticker: Ticker, market: Market, call: Int, info: PairQuery) {
        val address = market.tickerAddress(call, info)
        if (address.isEmpty()) {
            if (call == 0) throw IllegalArgumentException("Url is empty (market=${market.key})")
            return
        }

        val body = httpClient.callMarket(address, market.tickerPostBody(call, info))
        // Übersetzt wird erst in der Oberfläche (friendlyError → market_data_empty_error)
        if (body.isEmpty()) throw UserFriendlyMarketError(UserFriendlyMarketError.EMPTY_RESPONSE)

        try {
            market.readTicker(call, body, ticker, info)
            if (ticker.last <= Ticker.MISSING) throw InvalidObjectException(UserFriendlyMarketError.NO_TICKER_DATA)
        } catch (known: MarketError) {
            throw known
        } catch (readFailure: Exception) {
            readFailure.rethrowIfCritical()
            val exchangeMessage = try {
                market.readError(call, body, info)
            } catch (noMessage: Exception) {
                noMessage.rethrowIfCritical()
                // Keine Fehlermeldung erkennbar: der ursprüngliche Lesefehler
                throw readFailure
            }
            throw UserFriendlyMarketError(exchangeMessage ?: UserFriendlyMarketError.UNKNOWN_EMPTY)
        }
    }

    private companion object {
        const val BULK_TIMEOUT_SECONDS = 20L
        const val PAIR_LIST_TIMEOUT_SECONDS = 60L
    }
}

/** Kopie des Clients mit gleicher Gesamt- und Lesefrist. */
private fun OkHttpClient.withTimeouts(seconds: Long): OkHttpClient =
    newBuilder()
        .callTimeout(seconds, TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS)
        .build()

/**
 * Text einer Börsenanfrage (GET ohne, POST mit [postRequestInfo]). Fehler kommen als [MarketError].
 *
 * @param maxBytes Höchstgrösse der Antwort ([HttpBodyLimit]); Paar- und Sammellisten nehmen
 *   [HttpBodyLimit.BULK_MAX_BYTES].
 */
suspend fun OkHttpClient.callMarket(
    url: String,
    postRequestInfo: PostRequestInfo?,
    maxBytes: Long = HttpBodyLimit.DEFAULT_MAX_BYTES,
): String {
    // Wache gegen eine hängende OkHttp-Anfrage, knapp über dem callTimeout
    val guardMillis = if (callTimeoutMillis > 0) callTimeoutMillis + GUARD_EXTRA_MILLIS else GUARD_DEFAULT_MILLIS
    // Für alle gleiche Daten zuerst aus dem Spiegel auf GitHub (DataMirror), sonst beim Anbieter
    if (postRequestInfo == null) {
        val entry = DataMirror.entryFor(url)
        if (entry != null) fromMirror(entry, guardMillis, maxBytes)?.let { return it }
    }
    // withTimeoutOrNull statt withTimeout: Die Wache endet als gewöhnliche Zeitüberschreitung der Börse,
    // nie als TimeoutCancellationException — sonst sähe sie aus wie ein Abbruch des ganzen Durchlaufs und
    // die Teilanfragen, die scheitern dürfen (Folgeanfragen, Zusatzkurse), rissen alles mit.
    return withTimeoutOrNull(guardMillis) { request(url, postRequestInfo, maxBytes) }
        ?: throw TimeoutError(InterruptedIOException("timeout"))
}

private const val GUARD_EXTRA_MILLIS = 5_000L
private const val GUARD_DEFAULT_MILLIS = 35_000L

/** Text aus dem Spiegel, wenn erreichbar und frisch genug; sonst null (dann direkt beim Anbieter). */
private suspend fun OkHttpClient.fromMirror(entry: DataMirror.Entry, guardMillis: Long, maxBytes: Long): String? =
    try {
        withTimeoutOrNull(guardMillis) { request(entry.url, null, maxBytes) }
            ?.let { DataMirror.unwrap(it, System.currentTimeMillis(), entry.maxAgeMillis) }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (ex: Exception) {
        Timber.d("Spiegel %s nicht erreichbar: %s", entry.file, ex.message)
        null
    }

/** Eine Anfrage ohne Wache; gleiche gleichzeitige GETs laufen nur einmal ([InFlightRequests]). */
private suspend fun OkHttpClient.request(url: String, postRequestInfo: PostRequestInfo?, maxBytes: Long): String =
    try {
        val builder = Request.Builder().url(url)
        if (postRequestInfo == null) {
            val get = builder.build()
            InFlightRequests.shared("$callTimeoutMillis|$maxBytes|$url") {
                newCall(get).await().bodyText(maxBytes)
            }
        } else {
            postRequestInfo.headers?.forEach { (name, value) -> builder.addHeader(name, value) }
            val post = builder.post(postRequestInfo.body.toRequestBody()).build()
            newCall(post).await().bodyText(maxBytes)
        }
    } catch (ex: Exception) {
        ex.rethrowIfCritical()
        throw parseMarketError(ex)
    }

/**
 * Body als Text (höchstens [maxBytes], sonst [ResponseTooLargeError] statt OutOfMemory), danach
 * geschlossen. Fehlercode oder erkannte Bremse → [HttpMarketError].
 */
private fun Response.bodyText(maxBytes: Long): String {
    val text = body.use { content ->
        HttpBodyLimit.read(content.byteStream(), content.contentLength(), maxBytes)?.let(HttpBodyLimit::decode)
    } ?: throw ResponseTooLargeError()
    // «Zu viele Anfragen» ohne 429/418 (Kraken 200, Bybit 403/10006, OKX 50011) als 429 melden
    val throttleCode = RateLimitSignal.httpCode(code, text)
    if (isSuccessful && throttleCode == null) return text
    throw HttpMarketError(
        throttleCode ?: code,
        text,
        // Bei Bremsung nennt die Börse oft die gewünschte Pause
        ExchangeBackoff.parseRetryAfterSeconds(header("Retry-After"), System.currentTimeMillis()),
    )
}
