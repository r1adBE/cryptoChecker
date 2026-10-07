package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.marketdata.model.FuturesContractType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

/** Funding Rate und Open Interest eines Perpetual-Kontrakts. */
data class FuturesInfo(
    /** Aktuelle Funding Rate in Prozent (z. B. 0.01 = 0,01 %). */
    val fundingRatePercent: Double?,
    /** Zeitpunkt der nächsten Funding-Zahlung (Epoch-ms). */
    val nextFundingTime: Long?,
    /** Offene Positionen in USD. */
    val openInterestUsd: Double?,
    /** Woher die Daten stammen («Binance Futures», «Bybit» oder «OKX»; bei anderen Börsen als Richtwert). */
    val source: String,
)

/**
 * Futures-Kennzahlen. Bybit-Futures zuerst von Bybit, alles andere zuerst von
 * Binance USDⓈ-M — bei anderen Börsen als Richtwert für denselben Coin.
 * Ist die erste Quelle gesperrt (Binance in den USA: 451/403) oder liefert
 * nichts, folgen Bybit bzw. Binance und zuletzt OKX. Gesperrte Hosts werden
 * wie bei den Kerzen 6 h übersprungen (siehe [BlockedSources]).
 */
class FuturesDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun fetch(watch: WatchEntity): FuturesInfo? {
        if (watch.contractType != FuturesContractType.PERPETUAL) return null
        val base = watch.baseAsset.trim().uppercase()
        return when (watch.marketKey) {
            "BybitFutures" -> firstOf(
                source(BYBIT) { bybit(watch.pairId ?: "${base}USDT") },
                source(BINANCE) { binance("${base}USDT") },
                source(OKX) { okx(base) },
            )
            "BinanceFutures" -> firstOf(
                source(BINANCE) {
                    binance(watch.pairId?.takeIf { it.endsWith("USDT") || it.endsWith("USDC") } ?: "${base}USDT")
                },
                source(BYBIT) { bybit("${base}USDT") },
                source(OKX) { okx(base) },
            )
            else -> fetchForBase(base)
        }
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
    private suspend fun firstOf(vararg sources: Pair<String, suspend () -> FuturesInfo>): FuturesInfo {
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

    private fun source(host: String, block: suspend () -> FuturesInfo): Pair<String, suspend () -> FuturesInfo> =
        host to block

    private suspend fun binance(symbol: String): FuturesInfo = coroutineScope {
        val premium = async {
            JSONObject(httpClient.callMarket("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol", null))
        }
        val oi = async {
            runCatching {
                JSONObject(httpClient.callMarket("https://fapi.binance.com/fapi/v1/openInterest?symbol=$symbol", null))
            }.getOrNull()
        }
        val p = premium.await()
        val mark = p.optString("markPrice").toDoubleOrNull()
        val contracts = oi.await()?.optString("openInterest")?.toDoubleOrNull()
        FuturesInfo(
            fundingRatePercent = p.optString("lastFundingRate").toDoubleOrNull()?.times(100.0),
            nextFundingTime = p.optLong("nextFundingTime").takeIf { it > 0 },
            openInterestUsd = if (contracts != null && mark != null) contracts * mark else null,
            source = "Binance Futures",
        )
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
        )
    }

    /**
     * OKX-Perpetual BASE-USDT-SWAP. Funding: «fundingTime» ist die nächste Abrechnung.
     * Open Interest in USD aus «oiUsd», sonst Coins × Mark-Preis.
     */
    private suspend fun okx(base: String): FuturesInfo = coroutineScope {
        val instId = "$base-USDT-SWAP"
        val fundingJob = async {
            okxFirst(httpClient.callMarket("https://www.okx.com/api/v5/public/funding-rate?instId=$instId", null))
        }
        val oiJob = async {
            runCatching {
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
                runCatching {
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
        const val BYBIT = "api.bybit.com"
        const val OKX = "www.okx.com"
        const val SOURCE_TIMEOUT_MILLIS = 8_000L
    }
}
