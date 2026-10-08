package com.cryptochecker.app.data.remote

import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.BitcoinCycle
import com.cryptochecker.app.domain.market.CoinInputs
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleExtremes
import com.cryptochecker.app.domain.market.CycleExtremesResult
import com.cryptochecker.app.domain.market.CycleSeries
import com.cryptochecker.app.domain.market.DataFreshness
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GlobalMarket
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.Indicators
import com.cryptochecker.app.domain.market.Sourced
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Daten für den Markt-Tab über Bitcoin hinaus:
 *  - Kursverlauf beliebiger Coins (Ausweich-Kette Binance/Binance.US/Coinbase, sonst Bybit) für das Coin-Modell
 *  - Fear & Greed Index (alternative.me)
 *  - Bitcoin-Dominanz (CoinGecko) und Altcoin-Saison (Ausweich-Kette)
 *  - Bitcoin-Kursverlauf seit 2016 für den Zyklus-Vergleich (Coin Metrics, sonst Binance,
 *    zuletzt die Ausweich-Kette mit kürzerem Verlauf)
 * Alles frei und ohne Schlüssel.
 */
class InsightsDataSource @Inject constructor(
    private val httpClient: OkHttpClient,
    private val candleDataSource: CandleDataSource,
) {
    private class Candle(val date: LocalDate, val high: Double, val close: Double)

    // ---------------- Coin ----------------

    /** Kennzahlen eines Coins; [Sourced.provider] = Anbieter der Tageskerzen (z. B. «Binance», «Bybit»). */
    suspend fun fetchCoin(symbol: String): Sourced<CoinInputs> = coroutineScope {
        val base = symbol.uppercase()
        val dailyJob = async { klinesSourced(base, daily = true) }
        val weeklyJob = async { runCatching { klines(base, daily = false) }.getOrDefault(emptyList()) }
        val btcJob = async {
            if (base == "BTC") null
            else runCatching { klines("BTC", daily = true, limit = 120) }.getOrNull()
        }

        val dailySourced = dailyJob.await()
        val daily = dailySourced.value
        require(daily.isNotEmpty()) { "Keine Kursdaten für $base" }
        val weekly = weeklyJob.await()
        val closes = daily.map { it.close }
        val weeklyCloses = weekly.map { it.close }
        val athCandle = (weekly + daily).maxByOrNull { it.high }

        // Stärke gegenüber Bitcoin: Verhältnis heute vs. vor 90 Tagen
        val vsBtc = btcJob.await()?.let { btc ->
            val c0 = closes.getOrNull(closes.size - 91)
            val b0 = btc.map { it.close }.let { it.getOrNull(it.size - 91) }
            val b1 = btc.lastOrNull()?.close
            if (c0 != null && b0 != null && b1 != null && c0 > 0 && b0 > 0 && b1 > 0)
                ((closes.last() / c0) / (b1 / b0) - 1.0) * 100.0 else null
        }

        val historyDays = maxOf(
            daily.size,
            weekly.firstOrNull()?.let { ChronoUnit.DAYS.between(it.date, LocalDate.now(ZoneOffset.UTC)).toInt() } ?: 0
        )

        val inputs = CoinInputs(
            price = closes.last(),
            sma50d = Indicators.smaOfLast(closes, 50),
            sma200d = Indicators.smaOfLast(closes, 200),
            sma111d = Indicators.smaOfLast(closes, 111),
            sma350d = Indicators.smaOfLast(closes, 350),
            price30dAgo = closes.getOrNull(closes.size - 31),
            sma200w = Indicators.smaOfLast(weeklyCloses, 200),
            ath = athCandle?.high,
            athDate = athCandle?.date,
            rsiDaily = Indicators.rsi(closes),
            rsiWeekly = Indicators.rsi(weeklyCloses),
            vsBtc90d = vsBtc,
            historyDays = historyDays,
        )
        Sourced(inputs, dailySourced.provider)
    }

    /**
     * Erst die Ausweich-Kette (Binance, Binance.US, Coinbase — siehe [CandleDataSource]),
     * sonst Bybit (z. B. für Coins, die keine dieser Quellen führt).
     */
    private suspend fun klines(base: String, daily: Boolean, limit: Int = 1000): List<Candle> =
        klinesSourced(base, daily, limit).value

    /** Wie [klines], dazu der Anbieter (Ausweich-Kette oder [DataFreshness.BYBIT]). */
    private suspend fun klinesSourced(base: String, daily: Boolean, limit: Int = 1000): Sourced<List<Candle>> =
        runCatching { chainKlinesSourced(base, if (daily) CandleInterval.D1 else CandleInterval.W1, limit) }
            .recoverCatching { Sourced(bybitKlines("${base}USDT", if (daily) "D" else "W", limit), DataFreshness.BYBIT) }
            .getOrThrow()

    /** Kerzen über [CandleDataSource]; wirft, wenn keine Quelle liefert. */
    private suspend fun chainKlines(base: String, interval: CandleInterval, limit: Int): List<Candle> =
        chainKlinesSourced(base, interval, limit).value

    private suspend fun chainKlinesSourced(base: String, interval: CandleInterval, limit: Int): Sourced<List<Candle>> {
        val sourced = candleDataSource.candlesSourced(base, "USDT", interval, limit)
            ?: error("Keine Kerzen für $base")
        val candles = sourced.value.map {
            Candle(
                date = Instant.ofEpochMilli(it.openTime).atZone(ZoneOffset.UTC).toLocalDate(),
                high = it.high,
                close = it.close,
            )
        }
        return Sourced(candles, sourced.provider)
    }

    private suspend fun binanceKlines(symbol: String, interval: String, limit: Int, startTime: Long? = null): List<Candle> {
        val url = "https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit" +
            (startTime?.let { "&startTime=$it" } ?: "")
        val array = JSONArray(httpClient.callMarket(url, null))
        return (0 until array.length()).map { i ->
            val k = array.getJSONArray(i)
            Candle(
                date = Instant.ofEpochMilli(k.getLong(0)).atZone(ZoneOffset.UTC).toLocalDate(),
                high = k.getString(2).toDouble(),
                close = k.getString(4).toDouble(),
            )
        }
    }

    /** Bybit liefert die neueste Kerze zuerst — umdrehen. */
    private suspend fun bybitKlines(symbol: String, interval: String, limit: Int): List<Candle> {
        val url = "https://api.bybit.com/v5/market/kline?category=spot&symbol=$symbol&interval=$interval&limit=$limit"
        val list = JSONObject(httpClient.callMarket(url, null)).getJSONObject("result").getJSONArray("list")
        return (0 until list.length()).map { i ->
            val k = list.getJSONArray(i)
            Candle(
                date = Instant.ofEpochMilli(k.getString(0).toLong()).atZone(ZoneOffset.UTC).toLocalDate(),
                high = k.getString(2).toDouble(),
                close = k.getString(4).toDouble(),
            )
        }.reversed()
    }

    // ---------------- Fear & Greed ----------------

    suspend fun fearGreed(): FearGreed {
        val data = JSONObject(httpClient.callMarket("https://api.alternative.me/fng/?limit=31", null))
            .getJSONArray("data")
        fun valueAt(i: Int): Int? =
            if (i < data.length()) data.getJSONObject(i).optString("value").toIntOrNull() else null
        return FearGreed(
            value = valueAt(0) ?: error("Fear & Greed ohne Wert"),
            yesterday = valueAt(1),
            weekAgo = valueAt(7),
            monthAgo = valueAt(30),
        )
    }

    // ---------------- Dominanz & Altcoin-Saison ----------------

    /**
     * Ein Aufruf von CoinGecko `/global` für Dominanz und die Karte «Krypto-Markt»
     * (Marktkapitalisierung, Volumen, Veränderung 24 Std.). Fehlen nur die Summen,
     * bleibt die Dominanz erhalten.
     */
    suspend fun global(): GlobalMarket {
        val data = JSONObject(httpClient.callMarket("https://api.coingecko.com/api/v3/global", null))
            .getJSONObject("data")
        val pct = data.getJSONObject("market_cap_percentage")
        val dominance = Dominance(btc = pct.getDouble("btc"), eth = pct.optDouble("eth").takeUnless { it.isNaN() })
        val totals = runCatching {
            MarketTotals(
                marketCap = amounts(data.getJSONObject("total_market_cap")),
                volume = amounts(data.getJSONObject("total_volume")),
                changePercent24h = data.optDouble("market_cap_change_percentage_24h_usd").takeIf { it.isFinite() },
            ).takeIf { it.marketCap.isNotEmpty() && it.volume.isNotEmpty() }
        }.onFailure { Timber.w(it, "CoinGecko ohne Markt-Summen") }.getOrNull()
        return GlobalMarket(dominance, totals)
    }

    /** {"usd": 3.4e12, "chf": …} → Karte mit Schlüsseln in Kleinbuchstaben. */
    private fun amounts(o: JSONObject): Map<String, Double> =
        o.keys().asSequence().mapNotNull { key ->
            o.optDouble(key).takeIf { it.isFinite() }?.let { key.lowercase() to it }
        }.toMap()

    /** Altcoin-Saison; [Sourced.provider] = Anbieter des BTC-Verlaufs (Ausweich-Kette). */
    suspend fun altSeason(): Sourced<AltSeason> = coroutineScope {
        val btcSourced = chainKlinesSourced("BTC", CandleInterval.D1, 91)
        val btc = btcSourced.value.map { it.close }
        val btcChange = change90(btc) ?: error("BTC-Verlauf fehlt")
        val permits = Semaphore(5)
        val results = ALTS.map { alt ->
            async {
                permits.withPermit {
                    runCatching { change90(chainKlines(alt, CandleInterval.D1, 91).map { it.close }) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
        Sourced(AltSeason(outperformers = results.count { it > btcChange }, total = results.size), btcSourced.provider)
    }

    private fun change90(closes: List<Double>): Double? {
        if (closes.size < 91) return null
        val first = closes[closes.size - 91]
        return if (first > 0) closes.last() / first - 1.0 else null
    }

    // ---------------- Zyklus-Vergleich ----------------

    suspend fun cycleHistory(): CycleHistory {
        val prices = runCatching { coinMetricsPrices() }
            .onFailure { Timber.w(it, "Coin Metrics nicht erreichbar, nehme Binance") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { binanceBtcHistory() }
                .onFailure { Timber.w(it, "Binance-Verlauf nicht erreichbar, nehme Ausweich-Kette") }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            ?: chainBtcHistory()

        val halvings = BitcoinCycle.HALVINGS.filter { it.year >= 2016 }
        val series = halvings.mapNotNull { halving ->
            // Nur Zyklen, deren Daten am Halving-Tag (± 1 Woche) beginnen
            val first = prices.entries.firstOrNull { !it.key.isBefore(halving) } ?: return@mapNotNull null
            if (ChronoUnit.DAYS.between(halving, first.key) > 7) return@mapNotNull null
            val startPrice = first.value
            val extremes = cycleMarkers(prices, halving, startPrice)
            // Wochenpunkte, dazu die Tage aller Marken, damit die Linie genau durch sie läuft
            val days = ((0..1440 step 7) + listOfNotNull(
                extremes.top?.day, extremes.bottom?.day, extremes.secondTop?.day, extremes.secondBottom?.day
            )).toSortedSet()
            val points = days.mapNotNull { day ->
                prices[halving.plusDays(day.toLong())]?.let { day to it / startPrice }
            }
            if (points.size < 2) null
            else CycleSeries(halving, points, extremes.top, extremes.bottom, extremes.secondTop, extremes.secondBottom)
        }
        return CycleHistory(series)
    }

    /** Hoch, Tief und Doppel-Top/-Bottom aus den Tageskursen 0–1440 Tage nach dem Halving. */
    private fun cycleMarkers(
        prices: Map<LocalDate, Double>,
        halving: LocalDate,
        startPrice: Double,
    ): CycleExtremesResult {
        val daily = (0..1440).mapNotNull { day ->
            prices[halving.plusDays(day.toLong())]?.takeIf { it > 0 }?.let { day to it }
        }
        return CycleExtremes.find(daily, halving, startPrice)
    }

    /** Tagesschlusskurse BTC in USD seit Mitte 2016 (Coin Metrics, ein Aufruf). */
    private suspend fun coinMetricsPrices(): Map<LocalDate, Double> {
        val url = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics" +
            "?assets=btc&metrics=PriceUSD&frequency=1d&start_time=2016-06-01&page_size=10000"
        val rows = JSONObject(httpClient.callMarket(url, null)).getJSONArray("data")
        val map = java.util.TreeMap<LocalDate, Double>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val date = LocalDate.parse(r.getString("time").substring(0, 10))
            r.optString("PriceUSD").toDoubleOrNull()?.let { map[date] = it }
        }
        return map
    }

    /** Ersatz: Binance-Tageskerzen ab August 2017, in Blöcken zu 1000 Tagen. */
    private suspend fun binanceBtcHistory(): Map<LocalDate, Double> {
        val map = java.util.TreeMap<LocalDate, Double>()
        var start = LocalDate.of(2017, 8, 17).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        repeat(5) {
            val batch = binanceKlines("BTCUSDT", "1d", 1000, startTime = start)
            if (batch.isEmpty()) return map
            batch.forEach { map[it.date] = it.close }
            start = batch.last().date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            if (batch.size < 1000) return map
        }
        return map
    }

    /** Letzter Ersatz: so viele Tageskerzen, wie die Ausweich-Kette liefert (Coinbase: ~300 Tage). */
    private suspend fun chainBtcHistory(): Map<LocalDate, Double> {
        val map = java.util.TreeMap<LocalDate, Double>()
        chainKlines("BTC", CandleInterval.D1, 1000).forEach { map[it.date] = it.close }
        return map
    }

    private companion object {
        /** Grosse Altcoins für die Altcoin-Saison (ohne Stablecoins). */
        val ALTS = listOf(
            "ETH", "BNB", "SOL", "XRP", "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT",
            "TON", "SHIB", "LTC", "BCH", "UNI", "NEAR", "APT", "ICP", "ETC", "XLM",
        )
    }
}
