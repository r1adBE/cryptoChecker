package com.cryptochecker.app.data.remote

import com.cryptochecker.app.data.CycleCacheStore
import com.cryptochecker.app.data.MarketExtraCodecs
import com.cryptochecker.app.data.StarterCoinsRepository
import com.cryptochecker.app.domain.exceptions.HttpMarketError
import com.cryptochecker.app.domain.market.MarketUnusual
import com.cryptochecker.app.domain.market.UnusualCoin
import com.cryptochecker.app.domain.market.UnusualInput
import com.cryptochecker.app.domain.starter.MarketUniverse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import timber.log.Timber
import java.net.URLEncoder
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daten für «Heute auffällig» — nur Quellen, die die App schon nutzt, je ein Abruf:
 *  - Coins: rund 30 grösste ohne Stablecoins mit Paar …USDT auf Binance
 *    ([StarterCoinsRepository.universe], CoinGecko-Rangliste, 24 h zwischengespeichert)
 *  - 24-h-Veränderung und Umsatz: Binance-Spiegel (data-api.binance.vision, 24-h-Ticker, alle Coins in einem Abruf)
 *  - Funding: Binance USDⓈ-M (fapi.binance.com, premiumIndex ohne Symbol = alle Perpetuals in einem Abruf);
 *    fehlt diese Quelle (gesperrt, Fehler), bleibt Funding einfach weg
 *
 * «Üblicher» Umsatz ohne weitere Abfragen: je Coin ein Tageswert des Umsatz-Anteils auf dem
 * Gerät ([MarketUnusual.updateHistory]), sonst der Median aller Coins.
 * Auswertung mit [MarketUnusual.evaluate]; Zwischenspeicher (10 Min.) siehe InfoViewModel.
 */
@Singleton
class UnusualDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val starterCoins: StarterCoinsRepository,
    private val cacheStore: CycleCacheStore,
) {
    private val mutex = Mutex()

    /** Frische Eingaben; höchstens ein Abruf gleichzeitig. Wirft ohne Coins oder Ticker. */
    suspend fun fetchInput(): UnusualInput = mutex.withLock {
        withContext(Dispatchers.IO) { load() }
    }

    private suspend fun load(): UnusualInput = coroutineScope {
        val fundingJob = async { funding() }
        var universe = starterCoins.universe() ?: error("Keine Coins für «Heute auffällig»")
        val tickers = try {
            tickers(universe)
        } catch (e: HttpMarketError) {
            // Binance kennt ein Symbol nicht mehr (400): Liste neu ermitteln, einmal wiederholen
            if (e.httpCode != 400) throw e
            universe = starterCoins.universe(force = true) ?: throw e
            tickers(universe)
        }
        val funding = fundingJob.await()
        val now = System.currentTimeMillis()

        val coins = universe.mapNotNull { coin ->
            val t = tickers[coin.symbol] ?: return@mapNotNull null
            UnusualCoin(
                symbol = coin.symbol,
                name = coin.name,
                change24h = t.first,
                quoteVolume = t.second,
                marketCap = coin.marketCap,
                fundingPercent = funding[coin.symbol],
            )
        }
        require(coins.isNotEmpty()) { "Keine Ticker für «Heute auffällig»" }

        // Tageswerte des Umsatz-Anteils fortschreiben (Ortsdatum), daraus «üblich» je Coin
        val today = LocalDate.now().toEpochDay()
        val history = cacheStore.read(HISTORY_NAME, MarketExtraCodecs.turnoverHistory)?.value.orEmpty()
        val updated = MarketUnusual.updateHistory(history, coins, today)
        cacheStore.write(HISTORY_NAME, updated, now, MarketExtraCodecs.turnoverHistory)
        val own = updated.mapNotNull { (symbol, samples) ->
            MarketUnusual.ownTypical(samples, today)?.let { symbol to it }
        }.toMap()

        UnusualInput(coins = coins, ownTypical = own, time = now)
    }

    /** Coin → (24-h-Veränderung in %, 24-h-Umsatz in USDT). */
    private suspend fun tickers(universe: List<MarketUniverse.Coin>): Map<String, Pair<Double, Double?>> {
        val url = BINANCE_TICKER_URL + URLEncoder.encode(MarketUniverse.binanceSymbols(universe), "UTF-8")
        val array = JSONArray(httpClient.callMarket(url, null))
        val out = HashMap<String, Pair<Double, Double?>>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val symbol = o.optString("symbol").uppercase()
            if (!symbol.endsWith("USDT")) continue
            val change = o.optString("priceChangePercent").toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            val volume = o.optString("quoteVolume").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            out[symbol.removeSuffix("USDT")] = change to volume
        }
        return out
    }

    /** Coin → letzte Funding Rate in % (USDT-Perpetuals); leer bei Fehler oder gesperrter Quelle. */
    private suspend fun funding(): Map<String, Double> {
        if (BlockedSources.isBlocked(FAPI_HOST)) return emptyMap()
        return try {
            withTimeoutOrNull(FUNDING_TIMEOUT_MILLIS) {
                val array = JSONArray(httpClient.callMarket(PREMIUM_INDEX_URL, null))
                val out = HashMap<String, Double>()
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val symbol = o.optString("symbol").uppercase()
                    if (!symbol.endsWith("USDT")) continue
                    val rate = o.optString("lastFundingRate").toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                    out[symbol.removeSuffix("USDT")] = rate * 100.0
                }
                out
            }.orEmpty()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Innere Zeitgrenze (callMarket-Wächter): wie ein Fehler der Quelle
            Timber.d(e, "Funding für «Heute auffällig»: Zeitüberschreitung")
            emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!BlockedSources.noteFailure(FAPI_HOST, e)) Timber.d(e, "Funding für «Heute auffällig» nicht verfügbar")
            emptyMap()
        }
    }

    private companion object {
        const val BINANCE_TICKER_URL = "https://data-api.binance.vision/api/v3/ticker/24hr?symbols="
        const val PREMIUM_INDEX_URL = "https://fapi.binance.com/fapi/v1/premiumIndex"
        const val FAPI_HOST = "fapi.binance.com"
        const val FUNDING_TIMEOUT_MILLIS = 8_000L

        /** Eintrag im Zwischenspeicher des Markt-Tabs mit den Tageswerten je Coin. */
        const val HISTORY_NAME = "unusual_turnover"
    }
}
