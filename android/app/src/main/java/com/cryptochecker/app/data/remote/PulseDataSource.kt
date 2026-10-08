package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.activity.ActivityAnalyzer
import com.cryptochecker.app.domain.market.CryptoPulse
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.PulseInput
import com.cryptochecker.app.domain.starter.StarterCoins
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
 *  - Marktbreite: 24-h-Veränderung der grössten Coins (CoinGecko-Rangliste, ohne Stablecoins und
 *    Doppelgänger wie bei den Start-Coins); Krypto-Markt gesamt: CoinGecko `/global`
 *    (30 Min. im Speicher — bewegt sich langsam, schont das Abruflimit)
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
        val topJob = async { timed { topChanges() } }
        val globalJob = async { timed { globalTotals() } }

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
            topChanges = topJob.await().orEmpty(),
            marketCapUsd = globalJob.await()?.marketCap?.get("usd"),
            marketCap24h = globalJob.await()?.changePercent24h,
        )
    }

    /**
     * 24-h-Veränderung der grössten Coins (Rang-Reihenfolge), ohne Stablecoins und Doppelgänger,
     * höchstens [CryptoPulse.BREADTH_COINS].
     */
    private suspend fun topChanges(): List<Double> {
        val array = JSONArray(httpClient.callMarket(COINGECKO_TOP_URL, null))
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val coin = StarterCoins.MarketCoin(
                symbol = o.optString("symbol").trim().uppercase(),
                name = o.optString("name").trim(),
                rank = if (o.isNull("market_cap_rank")) null else o.optInt("market_cap_rank"),
                marketCap = null,
            )
            if (StarterCoins.isExcluded(coin) || o.isNull("price_change_percentage_24h")) return@mapNotNull null
            o.optDouble("price_change_percentage_24h").takeIf { it.isFinite() }
        }.take(CryptoPulse.BREADTH_COINS)
    }

    @Volatile private var globalCache: Pair<Long, MarketTotals>? = null

    /** Markt-Summen von CoinGecko `/global`, 30 Minuten im Speicher. */
    private suspend fun globalTotals(): MarketTotals? {
        val now = System.currentTimeMillis()
        globalCache?.let { (at, totals) -> if (now - at in 0 until GLOBAL_CACHE_MILLIS) return totals }
        return insightsDataSource.global().totals?.also { globalCache = now to it }
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
        const val COINGECKO_TOP_URL = "https://api.coingecko.com/api/v3/coins/markets" +
            "?vs_currency=usd&order=market_cap_desc&per_page=50&page=1&sparkline=false"
        const val GLOBAL_CACHE_MILLIS = 30 * 60_000L
        const val SOURCE_TIMEOUT_MILLIS = 10_000L
    }
}
