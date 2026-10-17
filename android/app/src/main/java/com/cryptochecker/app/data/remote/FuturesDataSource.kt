package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.activity.FundingHistory
import com.cryptochecker.app.domain.activity.FundingPoint
import com.cryptochecker.app.domain.activity.FundingTrendRules
import com.cryptochecker.marketdata.model.PostRequestInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Funding Rate und Open Interest eines Perpetual-Kontrakts. */
data class FuturesInfo(
    /** Aktuelle Funding Rate in Prozent (z. B. 0.01 = 0,01 %). */
    val fundingRatePercent: Double?,
    /** Zeitpunkt der nächsten Funding-Zahlung (Epoch-ms). */
    val nextFundingTime: Long?,
    /** Offene Positionen in USD. */
    val openInterestUsd: Double?,
    /** Woher die Daten stammen ([FuturesSources]: «Binance Futures», «Bybit», «OKX», «WOO X Futures» …). */
    val source: String,
    /**
     * Abrechnungsintervall dieses Kontrakts in Stunden, wie die Börse es meldet (Binance 1/4/8,
     * Bybit, OKX, Bitget, MEXC); null = die Antwort nennt es nicht.
     */
    val fundingIntervalHours: Int? = null,
) {
    /**
     * Wirksames Intervall: gemeldet, sonst das feste der Quelle ([FuturesSources.fundingIntervalHours]);
     * null = unbekannt (Quelle mit Intervall je Kontrakt, das gerade nicht geladen werden konnte) — dann
     * keine Umrechnung auf 8 Std. und kein Funding-Alarm.
     */
    val intervalHours: Int? get() = fundingIntervalHours ?: FuturesSources.fundingIntervalHours(source)
}

/**
 * Futures-Kennzahlen. Perpetuals zuerst von der beobachteten Börse, wenn sie eigene Daten liefert
 * ([FuturesSources.own]: Binance, Bybit, OKX, WOO X, Bitget, MEXC, HTX, Hyperliquid, Deribit), alles
 * andere zuerst von Binance USDⓈ-M — bei anderen Börsen als Richtwert für denselben Coin
 * ([FuturesSources.note], in der Oberfläche als «Richtwert» gekennzeichnet).
 * Ist die erste Quelle gesperrt (Binance in den USA: 451/403) oder liefert
 * nichts, folgen Bybit bzw. Binance und zuletzt OKX. Gesperrte Hosts werden
 * wie bei den Kerzen 6 h übersprungen (siehe [BlockedSources]).
 */
class FuturesDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun fetch(watch: WatchEntity): FuturesInfo? {
        if (!watch.contractType.isPerpetual) return null
        val base = watch.baseAsset.trim().uppercase()
        return when (watch.marketKey) {
            "BybitFutures" -> firstOf(
                source(BYBIT) { bybit(watch.pairId ?: "${base}USDT") },
                source(BINANCE) { binance("${base}USDT") },
                source(OKX) { okx(base) },
            )
            // COIN-M (2:BTCUSD_PERP): eigene Kennzahlen von dapi; USDT-Kontrakt nur als Richtwert
            "BinanceFutures" -> if (FuturesSources.isCoinM(watch.pairId)) firstOf(
                source(BINANCE_COIN_M) { binanceCoinM(watch.pairId.orEmpty().removePrefix(FuturesSources.COIN_M_PREFIX)) },
                source(BINANCE) { binance("${base}USDT") },
                source(BYBIT) { bybit("${base}USDT") },
                source(OKX) { okx(base) },
            ) else firstOf(
                source(BINANCE) {
                    binance(watch.pairId?.takeIf { it.endsWith("USDT") || it.endsWith("USDC") } ?: "${base}USDT")
                },
                source(BYBIT) { bybit("${base}USDT") },
                source(OKX) { okx(base) },
            )
            // OKX-Perpetual: eigener Kontrakt (auch BASE-USD-SWAP), sonst Binance/Bybit als Richtwert
            "OkexFutures" -> firstOf(
                source(OKX) { okx(base, watch.pairId?.takeIf { it.endsWith("-SWAP") }) },
                source(BINANCE) { binance("${base}USDT") },
                source(BYBIT) { bybit("${base}USDT") },
            )
            else -> {
                // Weitere Börsen mit eigener Quelle: zuerst ihre eigenen Kennzahlen, sonst Richtwert der Kette
                val own = ownSource(watch, base) ?: return fetchForBase(base)
                firstOf(
                    own,
                    source(BINANCE) { binance("${base}USDT") },
                    source(BYBIT) { bybit("${base}USDT") },
                    source(OKX) { okx(base) },
                )
            }
        }
    }

    /**
     * Eigene Quelle der beobachteten Börse (Symbol wie im Adapter, sonst daraus gebildet); null =
     * Börse ohne eigene Funding-/Open-Interest-Daten (z. B. Phemex: Einheit des Open Interest nicht
     * belegt) — dann nur Richtwert. Abfragen gehen nur an die Börse, die das Paar ohnehin liefert:
     *  - WOO X: `GET https://api.woox.io/v3/public/futures?symbol=PERP_BTC_USDT`
     *  - Bitget: `GET https://api.bitget.com/api/v2/mix/market/ticker?symbol=BTCUSDT&productType=USDT-FUTURES`
     *    und `…/api/v2/mix/market/funding-time?symbol=…&productType=USDT-FUTURES`
     *  - MEXC: `GET https://contract.mexc.com/api/v1/contract/ticker?symbol=BTC_USDT`,
     *    `…/api/v1/contract/detail?symbol=…` und `…/api/v1/contract/funding_rate/{symbol}`
     *  - HTX: `GET https://api.hbdm.com/linear-swap-api/v1/swap_funding_rate?contract_code=BTC-USDT`
     *    und `…/linear-swap-api/v1/swap_open_interest?contract_code=…&business_type=swap`
     *  - Hyperliquid: `POST https://api.hyperliquid.xyz/info {"type":"metaAndAssetCtxs"}`
     *  - Deribit: `GET https://www.deribit.com/api/v2/public/ticker?instrument_name=…`
     */
    private fun ownSource(watch: WatchEntity, base: String): Pair<String, suspend () -> FuturesInfo>? {
        val quote = watch.quoteAsset.trim().uppercase()
        val id = watch.pairId
        return when (watch.marketKey) {
            "WooFutures" -> source(WOO) { woo(id ?: "PERP_${base}_$quote") }
            "BitgetFutures" -> source(BITGET) { bitget(id ?: "$base$quote") }
            "MexcFutures" -> source(MEXC) { mexc(id ?: "${base}_$quote") }
            "HtxFutures" -> source(HTX) { htx(id ?: "$base-$quote") }
            "Hyperliquid" -> source(HYPERLIQUID) { hyperliquid(id ?: watch.baseAsset.trim()) }
            "Deribit" -> source(DERIBIT) { deribit(id ?: "$base-PERPETUAL") }
            else -> null
        }
    }

    private suspend fun woo(symbol: String): FuturesInfo =
        FuturesParsers.woo(httpClient.callMarket("https://api.woox.io/v3/public/futures?symbol=$symbol", null))

    private suspend fun bitget(symbol: String): FuturesInfo = coroutineScope {
        val ticker = async {
            httpClient.callMarket("https://api.bitget.com/api/v2/mix/market/ticker?symbol=$symbol&productType=USDT-FUTURES", null)
        }
        val time = async {
            optionalCall("https://api.bitget.com/api/v2/mix/market/funding-time?symbol=$symbol&productType=USDT-FUTURES")
        }
        FuturesParsers.bitget(ticker.await(), time.await())
    }

    private suspend fun mexc(symbol: String): FuturesInfo = coroutineScope {
        val ticker = async { httpClient.callMarket("https://contract.mexc.com/api/v1/contract/ticker?symbol=$symbol", null) }
        val detail = async { optionalCall("https://contract.mexc.com/api/v1/contract/detail?symbol=$symbol") }
        val funding = async { optionalCall("https://contract.mexc.com/api/v1/contract/funding_rate/$symbol") }
        FuturesParsers.mexc(ticker.await(), detail.await(), funding.await(), symbol)
    }

    private suspend fun htx(code: String): FuturesInfo = coroutineScope {
        val funding = async { httpClient.callMarket("https://api.hbdm.com/linear-swap-api/v1/swap_funding_rate?contract_code=$code", null) }
        val oi = async {
            optionalCall("https://api.hbdm.com/linear-swap-api/v1/swap_open_interest?contract_code=$code&business_type=swap")
        }
        FuturesParsers.htx(funding.await(), oi.await())
    }

    private suspend fun hyperliquid(coin: String): FuturesInfo = FuturesParsers.hyperliquid(
        httpClient.callMarket(
            "https://api.hyperliquid.xyz/info",
            PostRequestInfo(body = """{"type":"metaAndAssetCtxs"}""", headers = mapOf("Content-Type" to "application/json")),
        ),
        coin,
    )

    private suspend fun deribit(instrument: String): FuturesInfo =
        FuturesParsers.deribit(httpClient.callMarket("https://www.deribit.com/api/v2/public/ticker?instrument_name=$instrument", null))

    /** Zusatzabfrage, deren Ausfall nur ein Feld kostet; echter Abbruch wird weitergereicht. */
    private suspend fun optionalCall(url: String): String? = try {
        httpClient.callMarket(url, null)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }

    /**
     * USDT-Perpetual für einen beliebigen Coin, z. B. für ein Spot-Paar im
     * «Warum»-Blatt: Binance, sonst Bybit, sonst OKX. Wirft, wenn keine Quelle
     * einen solchen Kontrakt führt.
     */
    suspend fun fetchForBase(baseAsset: String): FuturesInfo {
        val base = baseAsset.trim().uppercase()
        return firstOf(
            source(BINANCE) { binance("${base}USDT") },
            source(BYBIT) { bybit("${base}USDT") },
            source(OKX) { okx(base) },
        )
    }

    /**
     * Fragt die Quellen der Reihe nach (gesperrte Hosts übersprungen); die erste
     * Antwort gewinnt. Wirft den letzten Fehler, wenn alle scheitern.
     */
    private suspend fun <T : Any> firstOf(vararg sources: Pair<String, suspend () -> T>): T {
        var lastError: Throwable? = null
        for ((host, block) in sources) {
            if (BlockedSources.isBlocked(host)) continue
            try {
                // Eigene Zeitgrenze je Quelle, damit eine hängende Börse die nächste nicht verhindert
                withTimeoutOrNull(SOURCE_TIMEOUT_MILLIS) { block() }?.let { return it }
                Timber.d("Futures-Quelle antwortet nicht rechtzeitig: %s", host)
                lastError = java.util.concurrent.TimeoutException(host)
            } catch (e: Exception) {
                // Echten Abbruch des Aufrufers nicht verschlucken
                currentCoroutineContext().ensureActive()
                if (!BlockedSources.noteFailure(host, e)) Timber.d(e, "Futures-Daten nicht verfügbar: %s", host)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Alle Futures-Quellen gesperrt")
    }

    private fun <T : Any> source(host: String, block: suspend () -> T): Pair<String, suspend () -> T> =
        host to block

    // ---------------- Funding-Verlauf («Warum?») ----------------

    /**
     * Die letzten [FundingTrendRules.LIMIT] abgerechneten Funding Rates — gleiche Ausweich-Kette
     * und Symbole wie [fetch] (Perpetual) bzw. [fetchForBase] (sonst), gesperrte Hosts übersprungen.
     * Einmal beim Öffnen von «Warum?»; Ergebnisse ~10 Min. im Speicher. null = keine Quelle.
     *  - Binance: `GET https://fapi.binance.com/fapi/v1/fundingRate?symbol=…&limit=4`
     *  - Bybit: `GET https://api.bybit.com/v5/market/funding/history?category=linear&symbol=…&limit=4`
     *  - OKX: `GET https://www.okx.com/api/v5/public/funding-rate-history?instId=…&limit=4`
     */
    suspend fun fundingHistory(watch: WatchEntity): FundingHistory? {
        val base = watch.baseAsset.trim().uppercase()
        val perpetual = watch.contractType.isPerpetual
        val key = if (perpetual) "${watch.marketKey}|${watch.pairId}|$base" else "SPOT|$base"
        val now = System.currentTimeMillis()
        historyCache[key]?.let { (time, value) -> if (now - time in 0 until HISTORY_CACHE_MILLIS) return value }
        val usdt = "${base}USDT"
        val chain: Array<Pair<String, suspend () -> FundingHistory>> = when (if (perpetual) watch.marketKey else null) {
            "BybitFutures" -> arrayOf(
                source(BYBIT) { bybitHistory(watch.pairId ?: usdt) },
                source(BINANCE) { binanceHistory(usdt) },
                source(OKX) { okxHistory("$base-USDT-SWAP") },
            )
            "BinanceFutures" -> if (FuturesSources.isCoinM(watch.pairId)) arrayOf(
                source(BINANCE_COIN_M) { binanceCoinMHistory(watch.pairId.orEmpty().removePrefix(FuturesSources.COIN_M_PREFIX)) },
                source(BINANCE) { binanceHistory(usdt) },
                source(BYBIT) { bybitHistory(usdt) },
                source(OKX) { okxHistory("$base-USDT-SWAP") },
            ) else arrayOf(
                source(BINANCE) {
                    binanceHistory(watch.pairId?.takeIf { it.endsWith("USDT") || it.endsWith("USDC") } ?: usdt)
                },
                source(BYBIT) { bybitHistory(usdt) },
                source(OKX) { okxHistory("$base-USDT-SWAP") },
            )
            "OkexFutures" -> arrayOf(
                source(OKX) { okxHistory(watch.pairId?.takeIf { it.endsWith("-SWAP") } ?: "$base-USDT-SWAP") },
                source(BINANCE) { binanceHistory(usdt) },
                source(BYBIT) { bybitHistory(usdt) },
            )
            else -> arrayOf(
                source(BINANCE) { binanceHistory(usdt) },
                source(BYBIT) { bybitHistory(usdt) },
                source(OKX) { okxHistory("$base-USDT-SWAP") },
            )
        }
        val result = try {
            firstOf(*chain)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.d(e, "Funding-Verlauf nicht verfügbar: %s", base)
            // Fehler nicht merken: beim nächsten Öffnen neu versuchen
            return null
        }
        historyCache[key] = now to result
        return result
    }

    private suspend fun binanceHistory(symbol: String): FundingHistory = history(
        FundingTrendRules.parseBinance(
            httpClient.callMarket("https://fapi.binance.com/fapi/v1/fundingRate?symbol=$symbol&limit=${FundingTrendRules.LIMIT}", null)
        ),
        "Binance Futures",
    )

    private suspend fun binanceCoinMHistory(symbol: String): FundingHistory = history(
        FundingTrendRules.parseBinance(
            httpClient.callMarket("https://dapi.binance.com/dapi/v1/fundingRate?symbol=$symbol&limit=${FundingTrendRules.LIMIT}", null)
        ),
        FuturesSources.BINANCE_COIN_M,
    )

    /**
     * Binance COIN-M: `GET https://dapi.binance.com/dapi/v1/premiumIndex?symbol=BTCUSD_PERP` und
     * `…/dapi/v1/openInterest?symbol=…` — der Kontrakt, den der Nutzer beobachtet ([FuturesParsers.binanceCoinM]).
     */
    private suspend fun binanceCoinM(symbol: String): FuturesInfo = coroutineScope {
        val premium = async { httpClient.callMarket("https://dapi.binance.com/dapi/v1/premiumIndex?symbol=$symbol", null) }
        val oi = async { optionalCall("https://dapi.binance.com/dapi/v1/openInterest?symbol=$symbol") }
        val sizes = async { binanceCoinMSizes() }
        FuturesParsers.binanceCoinM(premium.await(), oi.await(), symbol, sizes.await())
    }

    /**
     * Binance COIN-M `GET /dapi/v1/exchangeInfo`: Kontraktwert je Symbol ([FuturesParsers.binanceCoinMContractSizes]),
     * wie die Intervall-Liste 6 Std. im Speicher und bei Fehlschlag bis [INTERVAL_STALE_MILLIS] weiter;
     * sonst null (dann BTC 100 / sonst 10 USD).
     */
    private suspend fun binanceCoinMSizes(): Map<String, Double>? {
        val now = System.currentTimeMillis()
        val cached = binanceCoinMSizeCache
        cached?.let { (time, map) -> if (now - time in 0 until INTERVAL_CACHE_MILLIS) return map }
        val map = optionalCall("https://dapi.binance.com/dapi/v1/exchangeInfo")
            ?.let { runCatching { FuturesParsers.binanceCoinMContractSizes(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return cached?.takeIf { now - it.first in 0 until INTERVAL_STALE_MILLIS }?.second
        binanceCoinMSizeCache = now to map
        return map
    }

    private suspend fun bybitHistory(symbol: String): FundingHistory = history(
        FundingTrendRules.parseBybit(
            httpClient.callMarket(
                "https://api.bybit.com/v5/market/funding/history?category=linear&symbol=$symbol&limit=${FundingTrendRules.LIMIT}",
                null,
            )
        ),
        "Bybit",
    )

    private suspend fun okxHistory(instId: String): FundingHistory = history(
        FundingTrendRules.parseOkx(
            httpClient.callMarket(
                "https://www.okx.com/api/v5/public/funding-rate-history?instId=$instId&limit=${FundingTrendRules.LIMIT}",
                null,
            )
        ),
        "OKX",
    )

    /** Leere Antwort zählt als Fehler, damit die Kette mit der nächsten Quelle weitermacht. */
    private fun history(points: List<FundingPoint>, source: String): FundingHistory {
        require(points.isNotEmpty()) { "Kein Funding-Verlauf von $source" }
        return FundingHistory(points, source)
    }

    private suspend fun binance(symbol: String): FuturesInfo = coroutineScope {
        val premium = async {
            JSONObject(httpClient.callMarket("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol", null))
        }
        val oi = async {
            runCatchingCancellable {
                JSONObject(httpClient.callMarket("https://fapi.binance.com/fapi/v1/openInterest?symbol=$symbol", null))
            }.getOrNull()
        }
        val intervals = async { binanceIntervals() }
        val p = premium.await()
        val mark = p.optString("markPrice").toDoubleOrNull()
        val contracts = oi.await()?.optString("openInterest")?.toDoubleOrNull()
        FuturesInfo(
            fundingRatePercent = p.optString("lastFundingRate").toDoubleOrNull()?.times(100.0),
            nextFundingTime = p.optLong("nextFundingTime").takeIf { it > 0 },
            openInterestUsd = if (contracts != null && mark != null) contracts * mark else null,
            source = "Binance Futures",
            // Nicht gelistet = Standard 8 Std.; Liste nicht ladbar = unbekannt (keine Umrechnung, kein Alarm)
            fundingIntervalHours = intervals.await()?.let { FuturesParsers.binanceIntervalHours(it, symbol) },
        )
    }

    /**
     * Binance `GET /fapi/v1/fundingInfo`: alle Kontrakte mit abweichendem Abrechnungsintervall (1 oder
     * 4 Std.) in einer Antwort, 6 Std. im Speicher. Scheitert das Neuladen, gilt die letzte Liste bis
     * [INTERVAL_STALE_MILLIS] weiter (Intervalle ändern sich selten); sonst null = unbekannt.
     */
    private suspend fun binanceIntervals(): Map<String, Int>? {
        val now = System.currentTimeMillis()
        val cached = binanceIntervalCache
        cached?.let { (time, map) -> if (now - time in 0 until INTERVAL_CACHE_MILLIS) return map }
        val map = optionalCall("https://fapi.binance.com/fapi/v1/fundingInfo")
            ?.let { runCatching { FuturesParsers.binanceFundingInfo(it) }.getOrNull() }
            ?: return cached?.takeIf { now - it.first in 0 until INTERVAL_STALE_MILLIS }?.second
        binanceIntervalCache = now to map
        return map
    }

    private suspend fun bybit(symbol: String): FuturesInfo {
        val url = "https://api.bybit.com/v5/market/tickers?category=linear&symbol=$symbol"
        val t = JSONObject(httpClient.callMarket(url, null))
            .getJSONObject("result").getJSONArray("list").getJSONObject(0)
        val funding = t.optString("fundingRate").toDoubleOrNull()?.times(100.0)
        val oiUsd = t.optString("openInterestValue").toDoubleOrNull()
        require(funding != null || oiUsd != null) { "Bybit ohne Futures-Daten für $symbol" }
        return FuturesInfo(
            fundingRatePercent = funding,
            nextFundingTime = t.optString("nextFundingTime").toLongOrNull()?.takeIf { it > 0 },
            openInterestUsd = oiUsd,
            source = "Bybit",
            fundingIntervalHours = FuturesParsers.hours(t.optString("fundingIntervalHour").toDoubleOrNull()),
        )
    }

    /**
     * OKX-Perpetual [instId] (Standard BASE-USDT-SWAP). Funding: «fundingTime» ist die nächste
     * Abrechnung. Open Interest in USD aus «oiUsd», sonst Coins × Mark-Preis.
     */
    private suspend fun okx(base: String, instIdOrNull: String? = null): FuturesInfo = coroutineScope {
        val instId = instIdOrNull ?: "$base-USDT-SWAP"
        val fundingJob = async {
            okxFirst(httpClient.callMarket("https://www.okx.com/api/v5/public/funding-rate?instId=$instId", null))
        }
        val oiJob = async {
            runCatchingCancellable {
                okxFirst(
                    httpClient.callMarket(
                        "https://www.okx.com/api/v5/public/open-interest?instType=SWAP&instId=$instId", null
                    )
                )
            }.getOrNull()
        }
        val f = fundingJob.await()
        val oi = oiJob.await()
        val oiUsd = oi?.optString("oiUsd")?.toDoubleOrNull()
            ?: oi?.optString("oiCcy")?.toDoubleOrNull()?.let { coins ->
                runCatchingCancellable {
                    okxFirst(
                        httpClient.callMarket(
                            "https://www.okx.com/api/v5/public/mark-price?instType=SWAP&instId=$instId", null
                        )
                    ).optString("markPx").toDoubleOrNull()
                }.getOrNull()?.let { coins * it }
            }
        FuturesInfo(
            fundingRatePercent = f.optString("fundingRate").toDoubleOrNull()?.times(100.0),
            nextFundingTime = (f.optString("fundingTime").toLongOrNull()
                ?: f.optString("nextFundingTime").toLongOrNull())?.takeIf { it > 0 },
            openInterestUsd = oiUsd,
            source = "OKX",
            // «fundingTime» = nächste, «nextFundingTime» = übernächste Abrechnung: der Abstand ist das Intervall
            fundingIntervalHours = FuturesParsers.okxIntervalHours(
                f.optString("fundingTime").toLongOrNull(), f.optString("nextFundingTime").toLongOrNull()
            ),
        )
    }

    /** OKX-Antwort {"code":"0","data":[{…}]}: erstes Datenobjekt, sonst Fehler. */
    private fun okxFirst(json: String): JSONObject {
        val root = JSONObject(json)
        require(root.optString("code") == "0") { "OKX-Fehler: ${root.optString("msg")}" }
        return root.getJSONArray("data").getJSONObject(0)
    }

    private companion object {
        /** Hostnamen = Schlüssel in [BlockedSources]. */
        const val BINANCE = "fapi.binance.com"
        const val BINANCE_COIN_M = "dapi.binance.com"
        const val BYBIT = "api.bybit.com"
        const val OKX = "www.okx.com"
        const val WOO = "api.woox.io"
        const val BITGET = "api.bitget.com"
        const val MEXC = "contract.mexc.com"
        const val HTX = "api.hbdm.com"
        const val HYPERLIQUID = "api.hyperliquid.xyz"
        const val DERIBIT = "www.deribit.com"
        const val SOURCE_TIMEOUT_MILLIS = 8_000L
        const val HISTORY_CACHE_MILLIS = 10 * 60_000L
        const val INTERVAL_CACHE_MILLIS = 6 * 60 * 60_000L
        const val INTERVAL_STALE_MILLIS = 7 * 24 * 60 * 60_000L

        /** Abrechnungsintervalle von Binance (Symbol → Stunden), prozessweit. */
        @Volatile var binanceIntervalCache: Pair<Long, Map<String, Int>>? = null

        /** Kontraktwerte von Binance COIN-M (Symbol → USD je Kontrakt), prozessweit. */
        @Volatile var binanceCoinMSizeCache: Pair<Long, Map<String, Double>>? = null

        /** Funding-Verlauf je Paar, prozessweit (die Klasse ist kein Singleton). */
        val historyCache = ConcurrentHashMap<String, Pair<Long, FundingHistory>>()
    }
}
