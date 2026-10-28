package com.cryptochecker.app.testing

import okhttp3.HttpUrl
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ein Coin der Bildschirmfoto-Tour: Kürzel, Name, Kurs in USD(T), Marktkapitalisierung, 24-h-Veränderung in % und
 * [beta] = wie stark sein Verlauf dem von BTC folgt (0 = Stablecoin, fester Kurs).
 */
internal data class TourCoin(
    val symbol: String,
    val name: String,
    val price: Double,
    val marketCap: Double,
    val change24h: Double,
    val beta: Double,
)

/**
 * Feste, in sich stimmige Marktdaten der Bildschirmfoto-Tour ([FakeExchange.marketData]) — gleiche Werte wie
 * `UITestExchange+Series.swift` (iOS): rund 34 grosse Coins (BTC 95’000, ETH 3’500, SOL 200 wie die Merkliste,
 * Gesamtmarkt rund 3,2 Billionen USD) und ein künstlicher, aber plausibler BTC-Verlauf seit Juni 2016.
 *
 * Der Verlauf läuft geometrisch durch Eckpunkte (Halvings, Hochs und Tiefs der Zyklen) bis zum heutigen Kurs, mit
 * kleinen festen Schwingungen (Sinus) — so ergeben Durchschnitte, RSI, Zyklus-Vergleich und Altcoin-Saison echte
 * Werte. Andere Coins folgen BTC mit ihrem [TourCoin.beta]. Alles wird je Abfrage zur aktuellen Uhrzeit erzeugt:
 * feste JSON-Dateien veralteten (die App verwirft Kerzen- und Marktdaten, die nicht bis jetzt reichen).
 */
internal object FakeMarketSeries {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    /** Binance-Wochen beginnen Montag 00:00 UTC; der 1.1.1970 war ein Donnerstag. */
    private const val MONDAY_OFFSET = 4 * DAY

    /** Erste Woche von BTCUSDT bei Binance (Montag 14.8.2017): davor keine Kerzen, wie bei der echten Börse. */
    private val LISTING = dayStart(2017, 8, 14)

    /** Die Coins nach Marktkapitalisierung; die fünf Start-Coins mit den Kursen aus [FakeExchange]. */
    val coins: List<TourCoin> = listOf(
        TourCoin("BTC", "Bitcoin", FakeExchange.START_PRICE, 1.895e12, 1.25, 1.0),
        TourCoin("ETH", "Ethereum", 3_500.0, 4.22e11, 2.1, 1.15),
        TourCoin("USDT", "Tether", 1.0002, 1.85e11, 0.01, 0.0),
        TourCoin("XRP", "XRP", 2.5, 1.49e11, -0.8, 0.9),
        TourCoin("BNB", "BNB", 900.0, 1.26e11, 0.6, 0.7),
        TourCoin("SOL", "Solana", 200.0, 1.09e11, 3.4, 1.3),
        TourCoin("USDC", "USDC", 0.9998, 7.8e10, -0.01, 0.0),
        TourCoin("DOGE", "Dogecoin", 0.38, 5.7e10, 6.8, 1.4),
        TourCoin("ADA", "Cardano", 0.95, 3.42e10, 3.4, 1.2),
        TourCoin("TRX", "TRON", 0.29, 2.75e10, -0.6, 0.5),
        TourCoin("LINK", "Chainlink", 24.5, 1.66e10, 4.9, 1.1),
        TourCoin("AVAX", "Avalanche", 38.0, 1.6e10, -2.7, 1.35),
        TourCoin("SUI", "Sui", 4.1, 1.44e10, 8.9, 1.5),
        TourCoin("SHIB", "Shiba Inu", 0.000024, 1.41e10, 5.5, 1.3),
        TourCoin("TON", "Toncoin", 5.6, 1.4e10, -3.8, 0.75),
        TourCoin("XLM", "Stellar", 0.42, 1.3e10, 0.8, 0.85),
        TourCoin("DOT", "Polkadot", 7.8, 1.25e10, 1.9, 1.25),
        TourCoin("HBAR", "Hedera", 0.27, 1.13e10, 2.2, 1.05),
        TourCoin("BCH", "Bitcoin Cash", 520.0, 1.03e10, -1.4, 0.95),
        TourCoin("LTC", "Litecoin", 115.0, 8.7e9, -0.9, 0.8),
        TourCoin("UNI", "Uniswap", 13.5, 8.1e9, 7.2, 1.1),
        TourCoin("PEPE", "Pepe", 0.000019, 8.0e9, 11.2, 1.6),
        TourCoin("NEAR", "NEAR Protocol", 6.2, 7.8e9, -4.6, 1.2),
        TourCoin("APT", "Aptos", 11.8, 7.1e9, 3.1, 1.3),
        TourCoin("ICP", "Internet Computer", 12.4, 6.6e9, -5.9, 1.15),
        TourCoin("POL", "POL (ex-MATIC)", 0.55, 5.8e9, -1.1, 1.2),
        TourCoin("AAVE", "Aave", 330.0, 5.0e9, 4.4, 1.0),
        TourCoin("ETC", "Ethereum Classic", 31.0, 4.7e9, 0.4, 0.9),
        TourCoin("FIL", "Filecoin", 6.4, 4.5e9, -3.3, 1.1),
        TourCoin("RENDER", "Render", 8.2, 4.3e9, -7.4, 1.45),
        TourCoin("ARB", "Arbitrum", 0.85, 3.8e9, 2.6, 1.3),
        TourCoin("ATOM", "Cosmos Hub", 8.9, 3.6e9, -2.2, 1.05),
        TourCoin("OP", "Optimism", 2.1, 3.6e9, -6.7, 1.35),
        TourCoin("INJ", "Injective", 28.0, 2.7e9, 9.6, 1.4),
    )

    /** Gesamtmarkt in USD, 24-h-Umsatz und Veränderung (CoinGecko `/global`). */
    const val TOTAL_MARKET_CAP = 3.2e12
    const val TOTAL_VOLUME = 1.25e11
    const val MARKET_CAP_CHANGE_24H = 1.4

    fun coin(symbol: String): TourCoin? = coins.firstOrNull { it.symbol == symbol }

    /** Aktueller Kurs: BTC aus [FakeExchange] (der Test kann ihn setzen), sonst aus der Tabelle. */
    fun price(coin: TourCoin): Double = if (coin.symbol == "BTC") FakeExchange.btcPrice else coin.price

    // ── Verlauf ──

    /** Eckpunkte des BTC-Verlaufs (Tag, USD): Halvings 2016/2020/2024, Hochs und Tiefs; danach der heutige Kurs. */
    private val anchors: List<Pair<Long, Double>> = listOf(
        dayStart(2016, 6, 1) to 530.0, dayStart(2016, 7, 9) to 650.0, dayStart(2017, 12, 17) to 19_300.0,
        dayStart(2018, 12, 15) to 3_200.0, dayStart(2019, 6, 26) to 12_900.0, dayStart(2020, 3, 13) to 4_900.0,
        dayStart(2020, 5, 11) to 8_600.0, dayStart(2021, 4, 14) to 63_500.0, dayStart(2021, 7, 20) to 29_800.0,
        dayStart(2021, 11, 10) to 68_500.0, dayStart(2022, 11, 21) to 15_800.0, dayStart(2024, 3, 14) to 73_000.0,
        dayStart(2024, 4, 20) to 64_000.0, dayStart(2024, 8, 5) to 54_000.0, dayStart(2024, 12, 17) to 106_000.0,
        dayStart(2025, 4, 8) to 76_500.0, dayStart(2025, 10, 6) to 124_500.0,
    )

    /** BTC in USD zum Zeitpunkt [t] (ms) ohne Schwingung; ab [now] der heutige Kurs. */
    private fun btcTrend(t: Long, now: Long): Double {
        val points = anchors.filter { it.first < now - DAY } + (now to FakeExchange.btcPrice)
        if (t <= points.first().first) return points.first().second
        if (t >= now) return FakeExchange.btcPrice
        val i = points.indexOfFirst { it.first >= t }
        val (t0, p0) = points[i - 1]
        val (t1, p1) = points[i]
        return p0 * (p1 / p0).pow((t - t0).toDouble() / (t1 - t0))
    }

    /**
     * Feste Schwingung um 1, [days] Tage vor jetzt; klingt vom vierten zum letzten Tag aus und fehlt in den letzten
     * 24 Stunden (dort gilt allein die 24-h-Veränderung, siehe [priceAt]).
     */
    private fun wiggle(days: Double, phase: Double): Double {
        val taper = ((days - 1.0) / 3.0).coerceIn(0.0, 1.0)
        val wave = 0.035 * sin(2 * PI * days / 23.0 + phase) + 0.018 * sin(2 * PI * days / 6.7 + phase) +
            0.003 * sin(2 * PI * days * 24.0 / 5.3)
        return 1.0 + taper * wave
    }

    /**
     * Kurs von [coin] zum Zeitpunkt [t]; jetzt genau [price], vor 24 h um die 24-h-Veränderung tiefer (wie Ticker und
     * Rangliste; dazwischen gleichmässig). Stablecoins bleiben fest.
     */
    fun priceAt(coin: TourCoin, t: Long, now: Long): Double {
        val current = price(coin)
        if (coin.beta == 0.0) return current
        val days = (now - t).coerceAtLeast(0L).toDouble() / DAY
        val ratio = btcTrend(t, now) / FakeExchange.btcPrice
        val lastDay = 1.0 + coin.change24h / 100.0 * days.coerceAtMost(1.0)
        return current * ratio.pow(coin.beta) * wiggle(days, coins.indexOf(coin) * 0.9) / lastDay
    }

    // ── Binance-Kerzen ──

    /**
     * `GET /api/v3/klines?symbol=…&interval=1h|4h|1d|1w&limit=…[&startTime=…]` im Format von Binance (älteste zuerst,
     * die letzte Kerze läuft noch); null = unbekanntes Symbol oder Intervall (404).
     */
    fun klines(url: HttpUrl, now: Long = System.currentTimeMillis()): String? {
        val coin = url.queryParameter("symbol")?.removeSuffix("USDT")?.let(::coin) ?: return null
        val step = when (url.queryParameter("interval")) {
            "1h" -> HOUR
            "4h" -> 4 * HOUR
            "1d" -> DAY
            "1w" -> WEEK
            else -> return null
        }
        val limit = url.queryParameter("limit")?.toIntOrNull()?.coerceIn(1, 1000) ?: 500
        val offset = if (step == WEEK) MONDAY_OFFSET else 0L
        val current = Math.floorDiv(now - offset, step) * step + offset
        val start = url.queryParameter("startTime")?.toLongOrNull()
            ?.let { Math.floorDiv(it - offset + step - 1, step) * step + offset }
        val requested = if (start != null) {
            generateSequence(start) { it + step }.takeWhile { it <= current }.take(limit).toList()
        } else {
            (limit - 1 downTo 0).map { current - it * step }
        }
        val opens = requested.filter { it >= LISTING }
        val dailyVolume = coin.marketCap * 0.03 / price(coin)
        var previous: Double? = null
        return opens.mapIndexed { i, open ->
            val close = if (open == current) price(coin) else priceAt(coin, open + step, now)
            val first = previous ?: priceAt(coin, open, now)
            previous = close
            val volume = dailyVolume * step / DAY * (1.0 + 0.3 * sin(i * 1.3))
            "[$open,\"${num(first)}\",\"${num(maxOf(first, close) * 1.012)}\",\"${num(minOf(first, close) * 0.988)}\"," +
                "\"${num(close)}\",\"${num(volume)}\",${open + step - 1},\"${num(volume * close)}\",${1_000 + i}," +
                "\"${num(volume / 2)}\",\"${num(volume * close / 2)}\",\"0\"]"
        }.joinToString(",", "[", "]")
    }

    // ── Coin Metrics ──

    /**
     * `GET /v4/timeseries/asset-metrics?assets=btc&metrics=…&start_time=…`: Tageswerte bis gestern. «PriceUSD» = BTC-
     * Schlusskurs; sonst MVRV, Ausgabe der neuen Coins in USD (450 BTC am Tag) und Hashrate (TH/s, steigend).
     */
    fun coinMetrics(url: HttpUrl, now: Long = System.currentTimeMillis()): String {
        val metrics = url.queryParameter("metrics").orEmpty()
        val start = url.queryParameter("start_time")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            ?: LocalDate.of(2016, 6, 1)
        val today = LocalDate.ofEpochDay(Math.floorDiv(now, DAY))
        val btc = coins.first()
        val rows = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.map { day ->
            val dayMillis = day.toEpochDay() * DAY
            val close = priceAt(btc, dayMillis + DAY, now)
            val time = "\"asset\":\"btc\",\"time\":\"${day}T00:00:00.000000000Z\""
            if ("PriceUSD" in metrics) {
                "{$time,\"PriceUSD\":\"${num(close)}\"}"
            } else {
                val daysAgo = (now - dayMillis).toDouble() / DAY
                val realized = 45_000.0 - 9.0 * daysAgo
                val hash = 1.05e9 * (1.0 - 0.0009 * daysAgo) * (1.0 + 0.02 * sin(daysAgo / 4.0))
                "{$time,\"CapMVRVCur\":\"${num(close / realized)}\",\"IssTotUSD\":\"${num(450.0 * close)}\"," +
                    "\"HashRate\":\"${num(hash)}\"}"
            }
        }
        return rows.joinToString(",", "{\"data\":[", "]}")
    }

    /** Coins für die 24-h-Ticker und Kurse der Fake-Börsen: alle ausser USDT (kein Paar USDTUSDT). */
    val tradable: List<String> get() = coins.map { it.symbol }.filter { it != "USDT" }

    /** Umsatz-Anteil am Tag: grössere Bewegung, mehr Umsatz (für «Heute auffällig»). */
    fun turnover(coin: TourCoin): Double = 0.03 + 0.012 * abs(coin.change24h)

    /** Zahl als Text wie bei Binance/Coin Metrics: Punkt, acht Nachkommastellen, nie Exponent. */
    fun num(value: Double): String = String.format(Locale.US, "%.8f", value)

    private fun dayStart(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Beginn des UTC-Tags [days] Tage vor [now] (für Zeitstempel in Sekunden, z. B. Fear & Greed). */
    fun dayStartSeconds(now: Long, days: Int): Long = (floor(now.toDouble() / DAY).toLong() - days) * DAY / 1000
}
