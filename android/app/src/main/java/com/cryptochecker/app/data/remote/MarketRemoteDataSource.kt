package com.cryptochecker.app.data.remote

import com.cryptochecker.marketdata.model.*
import com.cryptochecker.marketdata.model.market.DexPool
import com.cryptochecker.marketdata.model.market.DexScreener
import com.cryptochecker.app.data.TickerImpl
import com.cryptochecker.app.data.remote.util.InFlightRequests
import com.cryptochecker.app.data.remote.util.await
import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.exceptions.*
import com.cryptochecker.app.domain.model.BulkTickers
import com.cryptochecker.app.domain.model.MarketPairsInfo
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.InvalidObjectException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MarketRemoteDataSource @Inject constructor(
    private val httpClient: OkHttpClient
) {
    /**
     * Für Massenabfragen etwas mehr Luft als für Einzelkurse, aber nicht mehr
     * die früheren 60 s: Eine hängende Verbindung blockierte sonst den ganzen
     * Durchlauf bis zu zwei Minuten (siehe [callMarket]).
     */
    private val bulkHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(BULK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(BULK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchMarketCurrencyPairsInfo(market: Market): MarketPairsInfo {
        val pairs: MutableList<CurrencyPairInfo> = ArrayList()
        val numOfRequests = market.currencyPairsNumOfRequests

        if (market.currencyPairsCombined) {
            val client = httpClient.newBuilder()
                .callTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val responses = (0 until numOfRequests).map { requestId ->
                val url = market.getCurrencyPairsUrl(requestId)
                if (url.isNullOrEmpty()) "" else client.callMarket(url, market.getCurrencyPairsPostRequestInfo(requestId))
            }
            try {
                market.parseCurrencyPairsCombinedMain(responses, pairs)
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                throw ParseError(ex)
            }
            pairs.sort()
            return MarketPairsInfo(lastSyncDate = System.currentTimeMillis(), pairs = pairs)
        }

        val nextPairs: MutableList<CurrencyPairInfo> = ArrayList()
        for (requestId in 0 until numOfRequests) {
            try {
                val nextUrl = market.getCurrencyPairsUrl(requestId)
                val nextPostRequestBody = market.getCurrencyPairsPostRequestInfo(requestId)

                if (!nextUrl.isNullOrEmpty()) {
/*
                    val responseString = MarketHttp.httpClient.callMarket(nextUrl, nextPostRequestBody) {
                        timeout {
                            requestTimeoutMillis = 120_000
                            socketTimeoutMillis = 120_000
                        }
                    }
*/
                    val responseString = httpClient
                        .newBuilder()
                        .callTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build()
                        .callMarket(nextUrl, nextPostRequestBody)

                    nextPairs.clear()
                    try {
                        market.parseCurrencyPairsMain(requestId, responseString, nextPairs)
                    } catch (ex: Exception) {
                        ex.rethrowIfCritical()
                        if (requestId == 0) {
                            throw ParseError(ex)
                        }
                    }
                    pairs.addAll(nextPairs)
                }
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                if (requestId == 0) {
                    throw ex
                }
            }
        }

        pairs.sort()

        return MarketPairsInfo(
            lastSyncDate = System.currentTimeMillis(),
            pairs = pairs
        )
    }

    /**
     * Holt die Ticker einer Börse in möglichst wenigen Anfragen.
     * Schlüssel ist die Paar-Kennung der Börse.
     *
     * @param pairIds die beobachteten Paare. Unterstützt die Börse eine Auswahl,
     *   wird nur nach diesen gefragt; scheitert das (z. B. weil ein Paar nicht
     *   mehr gehandelt wird und die Börse die ganze Anfrage ablehnt), folgt
     *   einmal die ungefilterte Abfrage.
     */
    suspend fun fetchBulkTickers(market: Market, pairIds: Collection<String>): BulkTickers {
        if (market.bulkTickersNumOfRequests <= 0) return BulkTickers()
        // Lange Paarlisten verteilt die Börse auf mehrere gefilterte Anfragen.
        val numOfRequests = market.bulkTickersRequestCount(pairIds)

        val client = bulkHttpClient
        val result = LinkedHashMap<String, Ticker>()
        // Vollständig nur, wenn jede Teilabfrage geklappt hat.
        var allRequestsOk = true
        // Schon geladene ungefilterte Abfragen: decken alle Teilanfragen mit derselben URL ab.
        val loadedFullUrls = HashSet<String>()

        for (requestId in 0 until numOfRequests) {
            val filteredUrl = market.getBulkTickersUrl(requestId, pairIds)
            val fullUrl = market.getBulkTickersUrl(requestId)
            if (filteredUrl.isNullOrEmpty() || filteredUrl in loadedFullUrls) continue

            try {
                val postInfo = market.getBulkTickersPostRequestInfo(requestId)
                val tickers = try {
                    market.parseBulkTickersMain(requestId, client.callMarket(filteredUrl, postInfo))
                } catch (ex: Exception) {
                    // Abgelehnt oder unlesbar (z. B. ein Paar wird nicht mehr gehandelt
                    // und die Börse verwirft die ganze Liste): einmal ungefiltert.
                    ex.rethrowIfCritical()
                    if (fullUrl.isNullOrEmpty() || fullUrl == filteredUrl) throw ex
                    if (fullUrl in loadedFullUrls) continue
                    Timber.w(ex, "Gefilterte Massenabfrage gescheitert, hole alle Paare: %s", market.key)
                    market.parseBulkTickersMain(requestId, client.callMarket(fullUrl, postInfo))
                        .also { loadedFullUrls.add(fullUrl) }
                }

                result.putAll(tickers)
            } catch (ex: Exception) {
                ex.rethrowIfCritical()
                Timber.w(ex, "Massenabfrage fehlgeschlagen (market=%s, request=%d)", market.key, requestId)
                allRequestsOk = false

                // Die erste Anfrage trägt die Hauptlast; ohne sie lohnt der Rest nicht.
                if (requestId == 0) throw ex
            }
        }

        return BulkTickers(
            tickers = result,
            complete = market.bulkTickersComplete && allRequestsOk && result.isNotEmpty()
        )
    }

    /** Sucht DEX-Pools über DexScreener. */
    suspend fun searchDexPools(query: String): List<DexPool> =
        DexScreener.parseSearch(httpClient.callMarket(DexScreener.searchUrl(query), null))

    suspend fun fetchMarketTicker(market: Market, checkerInfo: CheckerInfo): Ticker {

        val ticker = TickerImpl()

        updateMarketTicker(
            ticker,
            httpClient,
            market,
            0,
            checkerInfo
        )

        val numOfRequests = market.getNumOfRequests(checkerInfo)
        if (numOfRequests > 1) {
            // Executing additional requests
            for (requestId in 1 until numOfRequests) {
                try {
                    updateMarketTicker(
                        ticker,
                        httpClient,
                        market,
                        requestId,
                        checkerInfo
                    )

                } catch (ex: Exception) {
                    ex.rethrowIfCritical()
                    // e.printStackTrace()
                    Timber.e(ex, "Failed to execute additional request #$requestId")
                }
            }
        }

        return ticker // Success
    }

    private companion object {
        const val BULK_TIMEOUT_SECONDS = 20L
    }
}

suspend fun OkHttpClient.callMarket(url: String, postRequestInfo: PostRequestInfo?): String {
    // Schutz gegen eine hängende OkHttp-Anfrage. Knapp über dem callTimeout,
    // damit ein Ausfall nicht doppelt so lange blockiert wie nötig.
    val guardMillis = if (callTimeoutMillis > 0) callTimeoutMillis + 5_000L else 35_000L
    return withTimeout(guardMillis) {
        callMarketInternal(url, postRequestInfo)
    }
}

private suspend fun OkHttpClient.callMarketInternal(url: String, postRequestInfo: PostRequestInfo?): String {
    fun getResponseString(response: Response): String {
        val responseString = response.body.string()

        if(!response.isSuccessful)
            throw HttpMarketError(
                response.code,
                responseString,
                // Bei «zu vielen Anfragen» sagt die Börse oft, wie lange sie Ruhe will
                ExchangeBackoff.parseRetryAfterSeconds(response.header("Retry-After"), System.currentTimeMillis()),
            )

        return responseString
    }

    try {
        val requestBuilder = Request.Builder().url(url)

        if (postRequestInfo == null) {
            // logger.debug { "Market GET request: $url" }
            // HTTP GET — gleiche gleichzeitige Anfragen nur einmal (InFlightRequests)
            val request = requestBuilder.build()
            return InFlightRequests.shared("$callTimeoutMillis|$url") {
                getResponseString(this.newCall(request).await())
            }
        }

        // HTTP POST
        //logger.debug { "Market POST request: $url" }

        postRequestInfo.headers?.let {
            it.forEach { (name, value) ->
                requestBuilder.addHeader(name, value)
            }
        }

        val request = requestBuilder
            .post(postRequestInfo.body.toRequestBody())
            .build()
        return  getResponseString(this.newCall(request).await())

    } catch (ex: Exception) {
        ex.rethrowIfCritical()
        throw parseMarketError(ex)
    }
}

private suspend fun updateMarketTicker(ticker: Ticker, httpClient: OkHttpClient, market: Market, requestId: Int, checkerInfo: CheckerInfo) {
    val url = market.getUrl(requestId, checkerInfo)

    if(url.isEmpty()) {
        if(requestId > 0)
            return

        throw IllegalArgumentException("Url is empty (market=${market.key})")
    }

    val postRequestInfo = market.getPostRequestInfo(requestId, checkerInfo)
    val responseString = httpClient.callMarket(url, postRequestInfo)

    if (responseString.isEmpty())
        // Übersetzt wird in der Oberfläche (friendlyError → market_data_empty_error)
        throw UserFriendlyMarketError(UserFriendlyMarketError.EMPTY_RESPONSE)

    try {
        market.parseTickerMain(requestId, responseString, ticker, checkerInfo)

        if(ticker.last <= Ticker.NO_DATA)
            throw InvalidObjectException(UserFriendlyMarketError.NO_TICKER_DATA)
    } catch (ex: MarketError){
        throw ex
    } catch (ex: Exception){
        ex.rethrowIfCritical()

        val errorMessage: String?

        // Try to parse error message from response
        try {
            errorMessage = market.parseErrorMain(
                    requestId,
                    responseString,
                    checkerInfo
                )
        } catch (ex2: Exception) {
            ex2.rethrowIfCritical()

            // Failed to parse, re-throw original exception
            throw ex
        }

        throw UserFriendlyMarketError(errorMessage ?: UserFriendlyMarketError.UNKNOWN_EMPTY)
    }
}
