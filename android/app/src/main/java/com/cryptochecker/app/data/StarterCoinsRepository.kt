package com.cryptochecker.app.data

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import androidx.core.content.edit
import android.content.Context
import com.cryptochecker.app.data.remote.callMarket
import com.cryptochecker.app.domain.starter.MarketUniverse
import com.cryptochecker.app.domain.starter.StarterCoins
import com.cryptochecker.app.domain.starter.StarterPairs
import com.cryptochecker.app.domain.starter.StarterPrice
import com.cryptochecker.app.domain.starter.StarterPrices
import com.cryptochecker.marketdata.model.FuturesContractType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coins der Start-Merkliste: die fünf grössten nach Marktkapitalisierung
 * (CoinGecko), ohne Stablecoins und Doppelgänger, nur mit Paar an der
 * Start-Börse (Binance …/USDT, in den USA Binance.US …/USDT). Auswahl siehe [StarterCoins].
 *
 * Datenschutz: Von CoinGecko wird nur die öffentliche Rangliste geholt
 * (`/coins/markets`, ohne Schlüssel, ohne Angaben zur Merkliste). CoinGecko
 * steht bereits in der Datenschutzerklärung.
 *
 * Ergebnis 24 Stunden zwischengespeichert, je Start-Börse. Fehler → null, die
 * Oberfläche bleibt bei Zwischenspeicher bzw. Ausweich-Liste.
 */
@Singleton
class StarterCoinsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val marketRepository: MarketRepository,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    /** Binance sperrt den Standort (zuletzt festgestellt, [probeBinance]); ohne Prüfung: nein. */
    val binanceBlocked: Boolean get() = prefs.getBoolean(KEY_BINANCE_BLOCKED, false)

    /** Region für den Startschritt: Geräte-Region, bei Standort-Sperre «US» ([StarterPairs.effectiveRegion]). */
    fun starterRegion(deviceRegion: String?): String? = StarterPairs.effectiveRegion(deviceRegion, binanceBlocked)

    /**
     * Sperrt Binance den Standort (HTTP 451/403)? Sonst zeigte der Startschritt fünf Fehlerzeilen. Gesperrt →
     * Startpaare auf Binance.US ([starterRegion]); gemerkt bis zur nächsten Prüfung. Nur der Startschritt.
     * @return true = Ergebnis anders als vorher
     */
    suspend fun probeBinance(deviceRegion: String?): Boolean {
        if (!StarterPairs.shouldProbe(deviceRegion)) return false
        val before = binanceBlocked
        val code: Int? = withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(PROBE_TIMEOUT_MILLIS) {
                    httpClient.callMarket(StarterPairs.BINANCE_PROBE_URL, null)
                    200
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.cryptochecker.app.domain.exceptions.HttpMarketError) {
                e.httpCode
            } catch (e: Exception) {
                null
            }
        }
        val after = StarterPairs.blockedAfterProbe(before, code)
        prefs.edit { putBoolean(KEY_BINANCE_BLOCKED, after) }
        return after != before
    }

    /** Sofort, ohne Netz: Zwischenspeicher (auch abgelaufen) oder Ausweich-Liste. */
    fun cached(regionCountry: String?): List<StarterPairs.Coin> =
        runCatching { StarterCoins.decode(prefs.getString(coinsKey(regionCountry), null)) }.getOrNull()
            ?: StarterCoins.fallback(regionCountry)

    /**
     * Frische Liste: aus dem Zwischenspeicher, wenn jünger als 24 Stunden,
     * sonst neu ermittelt. null bei jedem Fehler.
     */
    suspend fun fresh(regionCountry: String?): List<StarterPairs.Coin>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (StarterCoins.isFresh(prefs.getLong(timeKey(regionCountry), 0L), now)) {
                StarterCoins.decode(prefs.getString(coinsKey(regionCountry), null))?.let { return@withContext it }
            }
            try {
                val ranked = topByMarketCap()
                val marketKey = StarterPairs.marketKey(regionCountry)
                val available = availableBases(marketKey, StarterPairs.START_QUOTE)
                StarterCoins.pick(ranked, available)?.also { coins ->
                    prefs.edit {
                        putString(coinsKey(regionCountry), StarterCoins.encode(coins))
                        putLong(timeKey(regionCountry), now)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Start-Coins nicht verfügbar")
                null
            }
        }
    }

    private val universeMutex = Mutex()

    /**
     * Coins für «Heute auffällig» ([MarketUniverse]): rund 30 grösste nach
     * Marktkapitalisierung mit Spot-Paar …USDT auf Binance (unabhängig von der Region).
     * 24 Stunden zwischengespeichert (ein CoinGecko-Abruf am Tag); [force] = neu ermitteln
     * (z. B. wenn Binance ein Symbol nicht mehr kennt). Bei Fehlern der letzte Stand
     * (auch abgelaufen), sonst null.
     */
    suspend fun universe(force: Boolean = false): List<MarketUniverse.Coin>? = universeMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = runCatching { MarketUniverse.decode(prefs.getString(KEY_UNIVERSE, null)) }.getOrNull()
            if (!force && cached != null && MarketUniverse.isFresh(prefs.getLong(KEY_UNIVERSE_TIME, 0L), now)) {
                return@withContext cached
            }
            try {
                val ranked = topByMarketCap(COINGECKO_UNIVERSE_URL)
                val available = availableBases(StarterPairs.BINANCE_KEY, "USDT")
                MarketUniverse.pick(ranked, available)?.also { coins ->
                    prefs.edit {
                        putString(KEY_UNIVERSE, MarketUniverse.encode(coins))
                        putLong(KEY_UNIVERSE_TIME, now)
                    }
                } ?: cached
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Coins für «Heute auffällig» nicht verfügbar")
                cached
            }
        }
    }

    /** Zuletzt geholte Kurse (Schlüssel siehe [StarterPrices.cacheKey]) mit Zeitpunkt. */
    @Volatile
    private var priceCache: Triple<String, Long, Map<String, StarterPrice>>? = null
    private val priceMutex = Mutex()

    /**
     * Aktueller Kurs und 24-Stunden-Veränderung der Start-Coins in der Start-Quote:
     * Binance bzw. in den USA Binance.US (…/USDT), je ein Aufruf für alle. 60 Sekunden im Speicher. Fehlende Coins fehlen in der Karte; leer bei Fehler.
     */
    suspend fun prices(symbols: List<String>, regionCountry: String?): Map<String, StarterPrice> = priceMutex.withLock {
        val bases = symbols.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (bases.isEmpty()) return@withLock emptyMap()
        val marketKey = StarterPairs.marketKey(regionCountry)
        val key = StarterPrices.cacheKey(marketKey, bases)
        val now = System.currentTimeMillis()
        priceCache?.let { (cachedKey, time, prices) ->
            if (cachedKey == key && StarterPrices.isFresh(time, now)) return@withLock prices
        }
        val prices = withContext(Dispatchers.IO) {
            try {
                binancePrices(bases, StarterPairs.START_QUOTE, us = marketKey == StarterPairs.BINANCE_US_KEY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Kurse der Start-Coins nicht verfügbar")
                emptyMap()
            }
        }
        if (prices.isNotEmpty()) priceCache = Triple(key, System.currentTimeMillis(), prices)
        prices
    }

    /** Binance-Spiegel bzw. Binance.US ([us]): 24-Stunden-Ticker aller Coins in einem Aufruf. */
    private suspend fun binancePrices(bases: List<String>, quote: String, us: Boolean): Map<String, StarterPrice> {
        val prefix = if (us) BINANCE_US_TICKER_24H_URL else BINANCE_TICKER_24H_URL
        val url = prefix + URLEncoder.encode(StarterPrices.binanceSymbols(bases, quote), "UTF-8")
        val array = JSONArray(httpClient.callMarket(url, null))
        val suffix = quote.uppercase()
        val out = HashMap<String, StarterPrice>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val base = o.optString("symbol").uppercase().removeSuffix(suffix)
            val price = StarterPrices.validPrice(o.optString("lastPrice").toDoubleOrNull()) ?: continue
            val change = o.optString("priceChangePercent").toDoubleOrNull()?.takeIf { it.isFinite() }
            if (base in bases) out[base] = StarterPrice(price, change)
        }
        return out
    }

    /** Öffentliche Rangliste (Symbol, Name, Rang, Marktkapitalisierung). */
    private suspend fun topByMarketCap(url: String = COINGECKO_MARKETS_URL): List<StarterCoins.MarketCoin> {
        val array = JSONArray(httpClient.callMarket(url, null))
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val symbol = o.optString("symbol").trim()
            if (symbol.isEmpty()) return@mapNotNull null
            StarterCoins.MarketCoin(
                symbol = symbol.uppercase(),
                name = o.optString("name").trim(),
                rank = if (o.isNull("market_cap_rank")) null else o.optInt("market_cap_rank", Int.MAX_VALUE),
                marketCap = if (o.isNull("market_cap")) null else o.optDouble("market_cap").takeIf { it.isFinite() },
            )
        }
    }

    /**
     * Basis-Symbole mit Spot-Paar gegen [quote] an der Börse [marketKey]: zuerst
     * die für den Hinzufügen-Tab gespeicherte Paarliste, sonst eine öffentliche
     * Abfrage, zuletzt die mitgelieferte Liste der Börse.
     */
    private suspend fun availableBases(marketKey: String, quote: String): Set<String> {
        val market = marketRepository.getMarketList().firstOrNull { it.key == marketKey }
        val info = market?.let { runCatchingCancellable { marketRepository.getMarketCurrencyPairsInfo(it) }.getOrNull() }
        fun bases() = info?.pairs.orEmpty()
            .filter { it.contractType == FuturesContractType.NONE && it.quote.equals(quote, ignoreCase = true) }
            .map { it.base.uppercase() }
            .toSet()
        if (info != null && info.lastSyncDate > 0) bases().takeIf { it.isNotEmpty() }?.let { return it }

        val looked = try {
            binanceBases(quote, us = marketKey == StarterPairs.BINANCE_US_KEY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Paarliste für Start-Coins nicht verfügbar")
            emptySet()
        }
        return looked.ifEmpty { bases() }.ifEmpty { error("Keine Paarliste für $marketKey") }
    }

    /** Binance Spot bzw. Binance.US ([us]): alle Kurse in einem Aufruf, daraus die Basen mit …/[quote]. */
    private suspend fun binanceBases(quote: String, us: Boolean = false): Set<String> {
        val array = JSONArray(httpClient.callMarket(if (us) BINANCE_US_PRICES_URL else BINANCE_PRICES_URL, null))
        val suffix = quote.uppercase()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("symbol")?.uppercase()
                ?.takeIf { it.endsWith(suffix) && it.length > suffix.length }
                ?.removeSuffix(suffix)
        }.toSet()
    }

    private fun suffix(regionCountry: String?) = StarterPairs.marketKey(regionCountry)

    private fun coinsKey(regionCountry: String?) = "coins_" + suffix(regionCountry)
    private fun timeKey(regionCountry: String?) = "time_" + suffix(regionCountry)

    private companion object {
        const val PREFS = "starter_coins"
        const val KEY_BINANCE_BLOCKED = "binance_geo_blocked"
        const val PROBE_TIMEOUT_MILLIS = 4_000L
        const val COINGECKO_MARKETS_URL = "https://api.coingecko.com/api/v3/coins/markets" +
            "?vs_currency=usd&order=market_cap_desc&per_page=30&page=1"

        /** Etwas mehr als 30, damit nach Stablecoins und Doppelgängern rund 30 bleiben. */
        const val COINGECKO_UNIVERSE_URL = "https://api.coingecko.com/api/v3/coins/markets" +
            "?vs_currency=usd&order=market_cap_desc&per_page=40&page=1"
        const val KEY_UNIVERSE = "universe_binance"
        const val KEY_UNIVERSE_TIME = "universe_time_binance"
        const val BINANCE_PRICES_URL = "https://data-api.binance.vision/api/v3/ticker/price"
        const val BINANCE_TICKER_24H_URL = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols="
        const val BINANCE_US_PRICES_URL = "https://api.binance.us/api/v3/ticker/price"
        const val BINANCE_US_TICKER_24H_URL = "https://api.binance.us/api/v3/ticker/24hr?symbols="
    }
}
