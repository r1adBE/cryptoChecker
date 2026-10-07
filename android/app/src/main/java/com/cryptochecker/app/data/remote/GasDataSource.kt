package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.market.BtcFees
import com.cryptochecker.app.domain.market.EvmGas
import com.cryptochecker.app.domain.market.GasFees
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.marketdata.model.PostRequestInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Netzwerkgebühren aller unterstützten Netze. Ergebnis wird eine Minute
 * zwischengespeichert, damit Markt-Tab und Gas-Alarm nicht doppelt fragen.
 */
@Singleton
class GasDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val mutex = Mutex()
    private var cached: GasReport? = null

    suspend fun fetch(maxAgeMillis: Long = CACHE_MILLIS): GasReport = mutex.withLock {
        cached?.takeIf { System.currentTimeMillis() - it.time < maxAgeMillis }?.let { return it }
        val report = load()
        if (report.evm.isEmpty() && report.btc == null) throw IllegalStateException("Keine Gebührendaten")
        cached = report
        report
    }

    private suspend fun load(): GasReport = coroutineScope {
        val pricesJob = async { runCatching { prices() }.getOrElse { if (it is CancellationException) throw it; emptyMap() } }
        val evmJobs = GasNetwork.entries.map { network ->
            async { runCatching { network to evmGas(network) }.getOrElse { if (it is CancellationException) throw it; null } }
        }
        val btcJob = async { runCatching { mempool() }.getOrElse { if (it is CancellationException) throw it; null } }

        val prices = pricesJob.await()
        val evm = evmJobs.awaitAll().filterNotNull().map { (network, gwei) ->
            EvmGas(network, gwei.first, gwei.second, gwei.third, GasFees.evmTransferUsd(gwei.second, prices[network.coin]))
        }
        val btc = btcJob.await()?.let { (fast, normal, slow) ->
            BtcFees(fast, normal, slow, GasFees.btcTransferUsd(normal, prices["BTC"]))
        }
        GasReport(evm, btc, System.currentTimeMillis())
    }

    /** (langsam, normal, schnell) in gwei; versucht die Knoten der Reihe nach. */
    private suspend fun evmGas(network: GasNetwork): Triple<Double, Double, Double> {
        var lastError: Throwable? = null
        for (rpc in network.rpcs) {
            try {
                return try {
                    GasFees.parseFeeHistory(httpClient.callMarket(rpc, jsonPost(GasFees.feeHistoryRequest())))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ohne eth_feeHistory: einfacher Gaspreis für alle drei Stufen
                    val price = GasFees.parseGasPrice(httpClient.callMarket(rpc, jsonPost(GasFees.gasPriceRequest())))
                    Triple(price, price, price)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Timber.w(e, "Gas %s über %s nicht verfügbar", network.title, rpc)
            }
        }
        throw lastError ?: IllegalStateException("Kein Knoten")
    }

    private suspend fun mempool(): Triple<Double, Double, Double> =
        GasFees.parseMempool(httpClient.callMarket("https://mempool.space/api/v1/fees/recommended", null))

    private suspend fun prices(): Map<String, Double> {
        val coins = (GasNetwork.entries.map { it.coin } + "BTC").distinct()
        return try {
            val symbols = coins.joinToString(",", "[", "]") { "\"${it}USDT\"" }
            val url = "https://data-api.binance.vision/api/v3/ticker/price?symbols=" + URLEncoder.encode(symbols, "UTF-8")
            GasFees.parseBinancePrices(httpClient.callMarket(url, null)).takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Keine Preise")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GasFees.parseCoinbaseRates(httpClient.callMarket("https://api.coinbase.com/v2/exchange-rates?currency=USD", null), coins)
        }
    }

    private fun jsonPost(body: String) = PostRequestInfo(body, mapOf("Content-Type" to "application/json"))

    private companion object {
        const val CACHE_MILLIS = 60_000L
    }
}
