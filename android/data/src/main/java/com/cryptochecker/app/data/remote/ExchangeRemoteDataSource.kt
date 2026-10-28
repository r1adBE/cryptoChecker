package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.MutableTicker
import com.cryptochecker.app.data.remote.util.HttpBodyLimit
import com.cryptochecker.app.data.remote.util.RequestKey
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.exceptions.HttpExchangeError
import com.cryptochecker.app.domain.exceptions.ExchangeError
import com.cryptochecker.app.domain.exceptions.ParseError
import com.cryptochecker.app.domain.exceptions.UserFriendlyExchangeError
import com.cryptochecker.app.domain.exceptions.rethrowIfCritical
import com.cryptochecker.app.domain.exceptions.toExchangeFailure
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.ExchangePairsInfo
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.ExchangeFailure
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.marketdata.exceptions.ExchangeParseException
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.ExchangePair
import com.cryptochecker.marketdata.model.Exchange
import com.cryptochecker.marketdata.model.PairListPaging
import com.cryptochecker.marketdata.model.pairListPaging
import com.cryptochecker.marketdata.model.PostRequest
import com.cryptochecker.marketdata.model.Ticker
import com.cryptochecker.marketdata.model.exchange.BinanceAlpha
import com.cryptochecker.marketdata.model.exchange.DexPool
import com.cryptochecker.marketdata.model.exchange.DexScreener
import com.cryptochecker.marketdata.util.BulkCompleteness
import com.cryptochecker.marketdata.util.BulkTickerParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

/** Netzzugriff auf die Börsen: Paarlisten, Einzelkurse, Sammelabfragen und die DEX-Suche. */
class ExchangeRemoteDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /**
     * Sammelabfragen brauchen etwas mehr Zeit als ein Einzelkurs — aber nicht die einstigen 60 s:
     * Eine hängende Verbindung hielt sonst den ganzen Durchlauf bis zu zwei Minuten auf ([callExchange]).
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
     * Lesen, Sortieren und Coin-Namen laufen auf [Dispatchers.Default]: Die Listen sind gross, und das
     * Warten auf die Antwort (`InFlightRequests`) setzt sonst im Kontext des Aufrufers fort — beim
     * Explorer der Haupt-Thread. Gibt nur das Ergebnis zurück; Zustände setzt der Aufrufer.
     */
    suspend fun fetchExchangeCurrencyPairsInfo(exchange: Exchange): ExchangePairsInfo = withContext(Dispatchers.Default) {
        if (exchange.pairListCombined) loadCombinedPairList(exchange, pairListClient)
        else loadPairListByCall(exchange, pairListClient)
    }

    /** Alle Antworten zuerst sammeln, dann gemeinsam lesen (z. B. LATOKEN: IDs → Kürzel). */
    private suspend fun loadCombinedPairList(exchange: Exchange, client: OkHttpClient): ExchangePairsInfo {
        val bodies = List(exchange.pairListCallCount) { call ->
            val address = exchange.pairListAddress(call)
            if (address.isNullOrEmpty()) ""
            else client.callExchange(address, exchange.pairListPostBody(call), HttpBodyLimit.BULK_MAX_BYTES)
        }
        val collected = ArrayList<ExchangePair>()
        try {
            exchange.readPairListCombined(bodies, collected)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            throw ParseError(ex)
        }
        collected.sort()
        // Volle Coin-Namen (K-8) aus denselben Antworten; ein Fehler dabei lässt die Liste unberührt
        val names = runCatchingCancellable { exchange.parseBaseNamesCombined(bodies) }.getOrDefault(emptyMap())
        return ExchangePairsInfo(lastSyncDate = System.currentTimeMillis(), pairs = collected, baseNames = names)
    }

    /**
     * Jede Anfrage für sich lesen (samt Folgeseiten); nur die erste muss gelingen — bei
     * [Exchange.pairListAllCallsRequired] jede (sonst würde eine halbe Liste gespeichert).
     */
    private suspend fun loadPairListByCall(exchange: Exchange, client: OkHttpClient): ExchangePairsInfo {
        val collected = ArrayList<ExchangePair>()
        val names = LinkedHashMap<String, String>()
        for (call in 0 until exchange.pairListCallCount) {
            try {
                loadPairListCall(exchange, client, call, collected, names)
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                if (call == 0 || exchange.pairListAllCallsRequired) throw ex
            }
        }
        collected.sort()
        return ExchangePairsInfo(lastSyncDate = System.currentTimeMillis(), pairs = collected, baseNames = names)
    }

    /**
     * Eine Anfrage der Paarliste samt Folgeseiten (z. B. Bybit `nextPageCursor`, Gemini-Details; höchstens
     * [Exchange.MAX_PAIR_LIST_PAGES] Seiten). Den Blätterzustand hält ein eigenes [PairListPaging] dieser
     * Anfrage, nie die geteilte Börse. Eine unlesbare Folgeseite lässt die Anfrage scheitern — sonst fehlten
     * Paare unbemerkt; eine unlesbare erste Seite nur bei der ersten Anfrage.
     */
    private suspend fun loadPairListCall(
        exchange: Exchange,
        client: OkHttpClient,
        call: Int,
        into: MutableList<ExchangePair>,
        names: MutableMap<String, String>,
    ) {
        val address = exchange.pairListAddress(call)
        val postBody = exchange.pairListPostBody(call)
        if (address.isNullOrEmpty()) return

        val firstBody = client.callExchange(address, postBody, HttpBodyLimit.BULK_MAX_BYTES)
        into += readPairPage(exchange, call, firstBody, strict = call == 0)
        runCatchingCancellable { exchange.parseBaseNames(call, firstBody) }.getOrNull()
            ?.forEach { (symbol, name) -> names.putIfAbsent(symbol, name) }

        val paging = exchange.pairListPaging()
        var nextAddress = paging.nextPage(call, firstBody)
        var pagesLoaded = 1
        while (!nextAddress.isNullOrEmpty() && pagesLoaded < Exchange.MAX_PAIR_LIST_PAGES) {
            val pageBody = client.callExchange(nextAddress, null, HttpBodyLimit.BULK_MAX_BYTES)
            into += readPairPage(exchange, call, pageBody, strict = true)
            nextAddress = paging.nextPage(call, pageBody)
            pagesLoaded++
        }
    }

    /**
     * Eine Seite lesen. [strict]: Fehler als [ParseError] weiterwerfen; sonst zählt, was bis zum
     * Fehler gelesen wurde.
     */
    private suspend fun readPairPage(exchange: Exchange, call: Int, body: String, strict: Boolean): List<ExchangePair> {
        val page = ArrayList<ExchangePair>()
        try {
            exchange.readPairList(call, body, page)
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
    suspend fun fetchBulkTickers(exchange: Exchange, pairIds: Collection<String>): BulkTickers {
        if (exchange.bulkTickersNumOfRequests <= 0) return BulkTickers()
        // Lange Listen verteilt die Börse auf mehrere gefilterte Anfragen
        val callCount = exchange.bulkTickersRequestCount(pairIds)
        val client = bulkClient

        val tickers = LinkedHashMap<String, Ticker>()
        // Paare mit unlesbarer Zeile: Fehler statt still «unverändert»
        val unreadable = LinkedHashSet<String>()
        // Gruppen gescheiterter/ausgelassener Teilabfragen (Exchange.bulkTickersRequestGroup): nur deren
        // Paare gelten als unvollständig — bei Binance bleibt Spot ohne Alpha vollständig
        val brokenGroups = HashSet<Int>()
        // Bereits geholte ungefilterte Abfragen decken jede Teilanfrage mit derselben Adresse ab (Schlüssel ohne
        // Zeitstempel, RequestKey)
        val fullDone = HashSet<String>()
        // Eine Antwort, die sich selbst als lückenhaft meldet (Exchange.bulkResponseComplete), hebt «vollständig» auf
        var responsesComplete = true
        // «Zu viele Anfragen» bei einer Folgeanfrage: Rest weglassen, Text für die Pause (ExchangeBackoff)
        var throttled: String? = null
        var throttledFailure: ExchangeFailure? = null

        for (call in 0 until callCount) {
            val filtered = exchange.getBulkTickersUrl(call, pairIds)
            val full = exchange.getBulkTickersUrl(call)
            if (filtered.isNullOrEmpty() || RequestKey.of(filtered) in fullDone) continue

            try {
                val part = loadBulkCall(client, exchange, call, pairIds, filtered, full, fullDone) ?: continue
                tickers.putAll(part.tickers)
                unreadable.addAll(part.failed)
                if (!part.complete) responsesComplete = false
                if (part.failed.isNotEmpty()) {
                    Timber.w("Sammelabfrage: %d unlesbare Zeilen (market=%s)", part.failed.size, exchange.key)
                }
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                Timber.w(ex, "Sammelabfrage gescheitert (market=%s, Teil %d)", exchange.key, call)
                brokenGroups += exchange.bulkTickersRequestGroup(call)
                // Ohne die erste (tragende) Anfrage lohnt der Rest nicht
                if (call == 0) throw ex
                if (ex.isRateLimit()) {
                    throttled = ex.message ?: ex.javaClass.simpleName
                    throttledFailure = ex.toExchangeFailure()
                    // Weggelassene Teile sagen ebenfalls nichts über ihre Paare
                    for (skipped in call + 1 until callCount) brokenGroups += exchange.bulkTickersRequestGroup(skipped)
                    break
                }
            }
        }

        val incomplete = BulkCompleteness.incomplete(pairIds, brokenGroups, exchange::bulkTickersPairGroup)
        val complete = exchange.bulkTickersComplete && responsesComplete && tickers.isNotEmpty() &&
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
        exchange: Exchange,
        call: Int,
        pairIds: Collection<String>,
        filtered: String,
        full: String?,
        fullDone: MutableSet<String>,
    ): BulkTickerParse? {
        val postBody = exchange.getBulkTickersPostRequest(call)
        return try {
            loadBulk(client, exchange, call, filtered, postBody)
        } catch (ex: Exception) {
            ex.rethrowIfCritical()
            if (ex.isRateLimit()) throw ex
            if (full.isNullOrEmpty() || RequestKey.of(full) == RequestKey.of(filtered)) {
                retryWithoutUnknown(client, exchange, call, pairIds, postBody, ex) ?: throw ex
            } else if (RequestKey.of(full) in fullDone) {
                null
            } else {
                Timber.w(ex, "Gefilterte Sammelabfrage gescheitert, lade alle Paare: %s", exchange.key)
                val body = client.callExchange(full, postBody, HttpBodyLimit.BULK_MAX_BYTES)
                exchange.parseBulkTickersDetailed(call, body).also { fullDone.add(RequestKey.of(full)) }
            }
        }
    }

    /** [withoutUnknownPairs], ein Fehler darin ergibt null (dann zählt der ursprüngliche). */
    private suspend fun retryWithoutUnknown(
        client: OkHttpClient, exchange: Exchange, call: Int, pairIds: Collection<String>,
        postBody: PostRequest?, original: Exception,
    ): BulkTickerParse? = try {
        withoutUnknownPairs(client, exchange, call, pairIds, postBody, original)
    } catch (retryEx: Exception) {
        retryEx.rethrowIfCritical()
        null
    }

    /**
     * Gefilterte Anfrage ohne ungefilterten Ersatz abgelehnt ([Exchange.getBulkKnownPairsUrl], Bithumb):
     * Paarliste holen, angefragte Paare, die dort fehlen, weglassen und ohne sie nochmals fragen. Die
     * weggelassenen fehlen dann in der (vollständigen) Antwort → die Einzelabfrage bestätigt «nicht mehr
     * gehandelt», BulkDelisted lässt sie künftig weg. null = geht nicht oder kein Paar unbekannt.
     */
    private suspend fun withoutUnknownPairs(
        client: OkHttpClient, exchange: Exchange, call: Int, pairIds: Collection<String>,
        postBody: PostRequest?, original: Exception,
    ): BulkTickerParse? {
        val listAddress = exchange.getBulkKnownPairsUrl(call)
        if (listAddress.isNullOrEmpty()) return null

        val listed = ArrayList<ExchangePair>()
        exchange.readPairList(0, client.callExchange(listAddress, null, HttpBodyLimit.BULK_MAX_BYTES), listed)
        val knownIds = listed.mapNotNullTo(HashSet()) { it.pairId }
        val unknown = BulkCompleteness.unknownPairs(pairIds, knownIds) ?: return null

        Timber.w(original, "Sammelabfrage ohne %d unbekannte Paare erneut: %s", unknown.size, exchange.key)
        val remaining = pairIds.filterNot { it in unknown }
        if (remaining.isEmpty()) return BulkTickerParse(emptyMap(), emptySet())
        val address = exchange.getBulkTickersUrl(call, remaining)
        if (address.isNullOrEmpty()) return null
        return loadBulk(client, exchange, call, address, postBody)
    }

    /**
     * Teilanfrage holen und lesen. Die (grosse, für alle gleiche) Alpha-Liste von Binance gilt
     * [BinanceAlpha.LIST_CACHE_MILLIS] lang für alle Aufrufer — eine Aktualisierung lädt sie nie zweimal.
     */
    private suspend fun loadBulk(
        client: OkHttpClient, exchange: Exchange, call: Int, address: String, postBody: PostRequest?,
    ): BulkTickerParse {
        val isAlphaList = postBody == null && address == BinanceAlpha.TOKEN_LIST_URL
        val fromCache = if (isAlphaList) BinanceAlpha.listCache.get(System.currentTimeMillis()) else null
        val body = fromCache ?: client.callExchange(address, postBody, HttpBodyLimit.BULK_MAX_BYTES)
        val parsed = exchange.parseBulkTickersDetailed(call, body)
        // Erst nach erfolgreichem Lesen merken
        if (isAlphaList && fromCache == null) BinanceAlpha.listCache.put(body, System.currentTimeMillis())
        return parsed
    }

    /** Hat die Börse gebremst (429, auch erkannt über [RateLimitSignal]) oder gesperrt (418)? */
    private fun Exception.isRateLimit(): Boolean =
        this is HttpExchangeError && ExchangeBackoff.isRateLimitCode(httpCode)

    // ---------------------------------------------------------------------------------------------
    // DEX-Suche und Einzelkurs
    // ---------------------------------------------------------------------------------------------

    /** Pools über DexScreener suchen. */
    suspend fun searchDexPools(query: String): List<DexPool> =
        DexScreener.parseSearch(httpClient.callExchange(DexScreener.searchUrl(query), null), query)

    /**
     * Kurs eines Paars. Die erste Anfrage muss gelingen; weitere (z. B. Volumen aus einer zweiten
     * Quelle) ergänzen nur und dürfen scheitern.
     */
    suspend fun fetchExchangeTicker(exchange: Exchange, checkerInfo: PairQuery): Ticker {
        val ticker = MutableTicker()
        loadTickerCall(ticker, exchange, 0, checkerInfo)

        for (call in 1 until exchange.tickerCallCount(checkerInfo)) {
            try {
                loadTickerCall(ticker, exchange, call, checkerInfo)
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                Timber.e(ex, "Zusätzliche Kursanfrage #%d gescheitert", call)
            }
        }
        return ticker
    }

    /**
     * Teil [call] des Kurses holen und in [ticker] lesen. Ohne Adresse: bei Teil 0 ein Fehler, sonst
     * nichts. Unlesbar → Fehlermeldung der Börse als [UserFriendlyExchangeError], sonst der Lesefehler.
     */
    private suspend fun loadTickerCall(ticker: Ticker, exchange: Exchange, call: Int, info: PairQuery) {
        val address = exchange.tickerAddress(call, info)
        if (address.isEmpty()) {
            if (call == 0) throw IllegalArgumentException("Url is empty (market=${exchange.key})")
            return
        }

        val body = try {
            httpClient.callExchange(address, exchange.tickerPostBody(call, info))
        } catch (server: HttpExchangeError) {
            // Serverfehler: Kurs über die Ausweichadresse der Börse, sonst bleibt er ([SingleTickerFallback])
            SingleTickerFallback.load(exchange, call, info, ticker, server) { fallback ->
                bulkClient.callExchange(fallback, null, HttpBodyLimit.BULK_MAX_BYTES)
            }
            return
        }
        // Übersetzt wird erst in der Oberfläche (friendlyError → exchange_data_empty_error)
        if (body.isEmpty()) throw UserFriendlyExchangeError(UserFriendlyExchangeError.EMPTY_RESPONSE)

        try {
            exchange.readTicker(call, body, ticker, info)
            // Kein gültiger Kurs (fehlt, 0 wie bei Binance-«BREAK», NaN/∞): «ohne Kurs» statt Erfolg — sonst hielte
            // die Einzelabfrage ein ruhendes Paar für gehandelt (NotTraded.confirmedBySingle). Als Lesefehler wie iOS.
            if (!ticker.last.isFinite() || ticker.last <= 0.0) {
                throw ExchangeParseException(UserFriendlyExchangeError.NO_TICKER_DATA)
            }
        } catch (known: ExchangeError) {
            throw known
        } catch (readFailure: Exception) {
            readFailure.rethrowIfCritical()
            // Pool unbekannt (DexScreener): derselbe Pool über die Ausweichadresse (GeckoTerminal), sonst der Fehler
            if (exchange.tickerFallbackOnParseError(call, readFailure)) {
                SingleTickerFallback.loadAfterMissing(exchange, call, info, ticker, readFailure) { fallback ->
                    bulkClient.callExchange(fallback, null, HttpBodyLimit.BULK_MAX_BYTES)
                }
                return
            }
            val exchangeMessage = try {
                exchange.readError(call, body, info)
            } catch (noMessage: Exception) {
                noMessage.rethrowIfCritical()
                // Keine Fehlermeldung erkennbar: der ursprüngliche Lesefehler
                throw readFailure
            }
            throw UserFriendlyExchangeError(exchangeMessage ?: UserFriendlyExchangeError.UNKNOWN_EMPTY)
        }
    }

    private companion object {
        const val BULK_TIMEOUT_SECONDS = 20L
        const val PAIR_LIST_TIMEOUT_SECONDS = 60L
    }
}
