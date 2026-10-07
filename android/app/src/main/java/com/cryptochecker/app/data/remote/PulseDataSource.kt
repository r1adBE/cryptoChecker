package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.PulseInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daten für die Karte «Crypto Pulse» — nur Quellen, die die App schon nutzt:
 *  - 24-h-Veränderung BTC/ETH/SOL: Binance-Spiegel (data-api.binance.vision, 24-h-Ticker),
 *    sonst je Coin aus Stundenkerzen der Ausweich-Kette (Binance … Coinbase, siehe [CandleDataSource])
 *  - BTC-Volumen-Verhältnis: wie «Warum bewegt sich das?» ([ActivityAnalyzer.hourStats])
 *  - Fear & Greed (alternative.me), BTC-Funding ([FuturesDataSource]), Ethereum-Gas ([GasDataSource])
 * Liefert die Eingaben ([PulseInput]); Auswertung mit [CryptoPulse.evaluate].
 * Zwischenspeicher (5 Min., auch auf dem Gerät) siehe Markt-Tab (InfoViewModel).
 */
@Singleton
class PulseDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val volumeDataSource: VolumeDataSource,
    private val futuresDataSource: FuturesDataSource,
    private val insightsDataSource: InsightsDataSource,
    private val gasDataSource: GasDataSource,
) {
    private val mutex = Mutex()

    /** Frische Eingaben; höchstens ein Abruf gleichzeitig. Fehlende Quellen bleiben null. */
    suspend fun fetchInput(): PulseInput = mutex.withLock {
        withContext(Dispatchers.IO) { load() }
    }

    private suspend fun load(): PulseInput = coroutineScope {
        val tickerJob = async { timed { tickers24h() } }
        val btcCandlesJob = async { timed { volumeDataSource.hourlyCandles("BTC", "USDT") } }
        val fearGreedJob = async { timed { insightsDataSource.fearGreed() } }
        val fundingJob = async { timed { futuresDataSource.fetchForBase("BTC").fundingRatePercent } }
        val gasJob = async {
            timed { gasDataSource.fetch().evm.firstOrNull { it.network == GasNetwork.ETHEREUM }?.normalGwei }
        }

        val now = System.currentTimeMillis()
        val tickers = tickerJob.await().orEmpty()
        val btcCandles = btcCandlesJob.await()

        // Fehlt ein Ticker: 24 h aus den Stundenkerzen (gleiche Methode wie im «Warum»-Blatt)
        suspend fun change(base: String): Double? = tickers[base]
            ?: (if (base == "BTC") btcCandles else timed { volumeDataSource.hourlyCandles(base, "USDT") })
                ?.let { ActivityAnalyzer.changeOver(it, 24, now) }

        val btc = change("BTC")
        val eth = change("ETH")
        val sol = change("SOL")

        PulseInput(
            btc24h = btc,
            eth24h = eth,
            sol24h = sol,
            btcVolumeRatio = btcCandles?.let { ActivityAnalyzer.hourStats(it)?.volumeRatio },
            fearGreed = fearGreedJob.await()?.value,
            fundingPercent = fundingJob.await(),
            ethGasGwei = gasJob.await(),
            time = now,
        )
    }

    /** 24-h-Veränderung in % je Coin (BTC, ETH, SOL) vom Binance-Spiegel; leer bei Fehler. */
    private suspend fun tickers24h(): Map<String, Double> {
        val symbols = COINS.joinToString(",", "[", "]") { "\"${it}USDT\"" }
        val url = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols=" + URLEncoder.encode(symbols, "UTF-8")
        val array = JSONArray(httpClient.callMarket(url, null))
        val out = HashMap<String, Double>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val coin = o.optString("symbol").removeSuffix("USDT")
            val change = o.optString("priceChangePercent").toDoubleOrNull() ?: continue
            if (coin in COINS && change.isFinite()) out[coin] = change
        }
        return out
    }

    private suspend fun <T> timed(block: suspend () -> T?): T? {
        val result = try {
            withTimeoutOrNull(SOURCE_TIMEOUT_MILLIS) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Innere Zeitgrenze (z. B. callMarket-Wächter): wie ein Fehler der Quelle behandeln
            Timber.d(e, "Pulse-Quelle: Zeitüberschreitung")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Pulse-Quelle nicht verfügbar")
            null
        }
        // Zeitüberschreitungen einzelner Abfragen schlucken, echten Abbruch nicht
        currentCoroutineContext().ensureActive()
        return result
    }

    private companion object {
        val COINS = listOf("BTC", "ETH", "SOL")
        const val SOURCE_TIMEOUT_MILLIS = 10_000L
    }
}
