package com.cryptochecker.app.data.portfolio

import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.data.MarketRepository
import com.cryptochecker.app.data.remote.BlockedSources
import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.model.MarketInfo
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioStables
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Aktuelle USDT-Kurse je Coin und der Zeitpunkt der ältesten verwendeten Abfrage. */
data class PortfolioPrices(
    val prices: Map<String, Double> = emptyMap(),
    /** 0 = noch keine Kurse. */
    val updatedAt: Long = 0,
)

/**
 * Kurse und Coin-Liste für das Portfolio, immer in USDT.
 *
 * Kurse: Sammelabfrage beim Binance-Spiegel (`ticker/price?symbols=[…]`), für
 * fehlende Coins einzeln der letzte Schlusskurs der 1-h-Kerzen aus der
 * [CandleDataSource]-Kette (Binance → Binance.US → Coinbase). USDT = 1; andere Stablecoins
 * (USDC, DAI …) werden wie jeder Coin geholt — fehlt ihr Kurs, setzt der Rechner 1
 * ([PortfolioStables]). Zwischenspeicher 60 s.
 *
 * Coin-Liste für die Suche: Binance-Spot-Paare gegen USDT (exchangeInfo), sonst
 * der Paar-Zwischenspeicher der Börse «Binance», sonst Coinbase-USD-Produkte.
 * Zwischenspeicher 1 Tag (auch über einen Neustart).
 */
@Singleton
class PortfolioPriceSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val candleDataSource: CandleDataSource,
    private val marketRepository: MarketRepository,
) {
    /** Coin → (Kurs, Abfragezeit). */
    private val cache = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val priceMutex = Mutex()
    private val coinMutex = Mutex()

    @Volatile
    private var coinList: List<String>? = null

    /** Grosse Antworten (exchangeInfo) brauchen etwas mehr Zeit. */
    private val bulkClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Kurse aus dem Zwischenspeicher, ohne Netz (für den ersten Aufbau). */
    fun cached(coins: Collection<String>): PortfolioPrices = collect(normalized(coins))

    /**
     * Kurse für [coins]; Einträge jünger als 60 s werden nicht neu geholt
     * (ausser mit [force]). Schlägt eine Abfrage fehl, bleibt der letzte
     * bekannte Kurs stehen.
     */
    suspend fun prices(coins: Collection<String>, force: Boolean = false): PortfolioPrices =
        withContext(Dispatchers.IO) {
            val wanted = normalized(coins)
            priceMutex.withLock {
                val now = System.currentTimeMillis()
                val toFetch = wanted.filter { coin ->
                    force || cache[coin]?.let { now - it.second in 0 until PRICE_TTL_MILLIS } != true
                }
                if (toFetch.isNotEmpty()) {
                    val fetched = binanceBatch(toFetch).toMutableMap()
                    val missing = toFetch.filter { it !in fetched }
                    if (missing.isNotEmpty()) {
                        coroutineScope {
                            missing.map { coin -> async { coin to candlePrice(coin) } }.awaitAll()
                        }.forEach { (coin, price) -> if (price != null) fetched[coin] = price }
                    }
                    val time = System.currentTimeMillis()
                    fetched.forEach { (coin, price) -> cache[coin] = price to time }
                }
            }
            collect(wanted)
        }

    /** Ein einzelner Kurs, z. B. zum Vorbelegen im Erfassen-Blatt. */
    suspend fun price(coin: String): Double? {
        val symbol = PortfolioCalculator.normalizeCoin(coin)
        if (!PortfolioStables.needsQuote(symbol)) return 1.0
        // Stablecoin ohne Marktkurs: 1 (gleiche Regel wie Kopf, Verlauf und Widget)
        return PortfolioStables.price(symbol, prices(listOf(symbol)).prices[symbol])
    }

    private fun normalized(coins: Collection<String>): List<String> =
        coins.map { PortfolioCalculator.normalizeCoin(it) }
            .filter { isSymbol(it) && it != STABLE }
            .distinct()

    private fun collect(coins: List<String>): PortfolioPrices {
        val hits = coins.mapNotNull { coin -> cache[coin]?.let { coin to it } }
        return PortfolioPrices(
            prices = hits.associate { (coin, entry) -> coin to entry.first } + (STABLE to 1.0),
            updatedAt = hits.minOfOrNull { it.second.second } ?: 0L,
        )
    }

    /** Sammelabfrage; Binance lehnt die ganze Anfrage ab, wenn ein Symbol unbekannt ist. */
    private suspend fun binanceBatch(coins: List<String>): Map<String, Double> {
        if (BlockedSources.isBlocked(BINANCE_HOST)) return emptyMap()
        // Bekannte Coins zuerst — unbekannte gehen direkt über die Kerzen-Kette.
        val known = coinList?.toHashSet()
        val candidates = if (known.isNullOrEmpty()) coins else coins.filter { it in known }
        val result = HashMap<String, Double>()
        for (chunk in candidates.chunked(MAX_SYMBOLS)) {
            val bySymbol = chunk.associateBy { it + STABLE }
            val symbols = bySymbol.keys.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "https://$BINANCE_HOST/api/v3/ticker/price?symbols=" + URLEncoder.encode(symbols, "UTF-8")
            val body = get(url, BINANCE_HOST) ?: continue
            runCatching {
                val array = JSONArray(body)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val coin = bySymbol[o.optString("symbol")] ?: continue
                    val price = o.optString("price").toDoubleOrNull()
                    if (price != null && price > 0.0) result[coin] = price
                }
            }.onFailure { Timber.d(it, "Portfolio: Kurse nicht lesbar") }
        }
        return result
    }

    private suspend fun candlePrice(coin: String): Double? = try {
        candleDataSource.candles(coin, STABLE, CandleInterval.H1, 2)?.lastOrNull()?.close?.takeIf { it > 0.0 }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ---------------- Coin-Liste ----------------

    /** Alle wählbaren Coins (alphabetisch); leer, wenn keine Quelle antwortet. */
    suspend fun coins(): List<String> = withContext(Dispatchers.IO) {
        coinMutex.withLock {
            coinList?.let { list ->
                if (System.currentTimeMillis() - prefs.getLong(KEY_COINS_AT, 0L) in 0 until COINS_TTL_MILLIS) {
                    return@withLock list
                }
            }
            val stored = prefs.getString(KEY_COINS, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            val storedAt = prefs.getLong(KEY_COINS_AT, 0L)
            if (stored.isNotEmpty() && System.currentTimeMillis() - storedAt in 0 until COINS_TTL_MILLIS) {
                coinList = stored
                return@withLock stored
            }

            val fresh = binanceCoins() ?: pairCacheCoins() ?: coinbaseCoins()
            if (!fresh.isNullOrEmpty()) {
                val list = (fresh + STABLE).distinct().sorted()
                prefs.edit {
                    putString(KEY_COINS, list.joinToString(","))
                    putLong(KEY_COINS_AT, System.currentTimeMillis())
                }
                coinList = list
                list
            } else {
                // Lieber eine ältere Liste als gar keine
                stored.also { if (it.isNotEmpty()) coinList = it }
            }
        }
    }

    private suspend fun binanceCoins(): List<String>? {
        val url = "https://$BINANCE_HOST/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false"
        val body = get(url, BINANCE_HOST, bulkClient) ?: return null
        return runCatching {
            val symbols = JSONObject(body).getJSONArray("symbols")
            (0 until symbols.length()).mapNotNull { i ->
                val s = symbols.getJSONObject(i)
                s.optString("baseAsset").takeIf {
                    s.optString("quoteAsset") == STABLE && s.optString("status") == "TRADING"
                }
            }.map { it.uppercase() }.filter { isSymbol(it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Paarliste der Börse «Binance» aus der App (lokal gespeichert oder mitgeliefert). */
    private suspend fun pairCacheCoins(): List<String>? = runCatching {
        marketRepository.getMarketCurrencyPairsInfo(MarketInfo(key = "Binance", name = "Binance"))
            .pairs
            .filter { it.currencyCounter == STABLE && it.contractType == FuturesContractType.NONE }
            .map { it.currencyBase.uppercase() }
            .filter { isSymbol(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private suspend fun coinbaseCoins(): List<String>? {
        val body = get("https://api.exchange.coinbase.com/products", COINBASE_HOST, bulkClient) ?: return null
        return runCatching {
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val p = array.getJSONObject(i)
                p.optString("base_currency").takeIf {
                    p.optString("quote_currency") == "USD" && !p.optBoolean("trading_disabled", false)
                }
            }.map { it.uppercase() }.filter { isSymbol(it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** GET mit eigener Zeitgrenze; null bei Fehler. 451/403 sperrt die Quelle vorübergehend. */
    private suspend fun get(url: String, host: String, client: OkHttpClient = httpClient): String? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { client.callMarket(url, null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!BlockedSources.noteFailure(host, e)) Timber.d(e, "Portfolio: Abfrage fehlgeschlagen: %s", url)
        null
    }

    private companion object {
        const val STABLE = "USDT"
        const val BINANCE_HOST = "data-api.binance.vision"
        const val COINBASE_HOST = "api.exchange.coinbase.com"
        const val MAX_SYMBOLS = 100
        const val PRICE_TTL_MILLIS = 60_000L
        const val COINS_TTL_MILLIS = 24 * 60 * 60_000L
        const val REQUEST_TIMEOUT_MILLIS = 25_000L
        const val PREFS = "portfolio"
        const val KEY_COINS = "coin_list"
        const val KEY_COINS_AT = "coin_list_at"

        fun isSymbol(s: String) = s.length in 1..15 && s.all { it.isLetterOrDigit() }
    }
}
