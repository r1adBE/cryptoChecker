package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.CandleSeries
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.market.BinanceAlpha
import org.json.JSONArray
import org.json.JSONObject

// Eigene Kerzen je Börse: öffentliche Endpunkte ohne Schlüssel (Stand Oktober 2026).
// Kennung des Paars wie beim Kursabruf ([CandlePair.pairId]); fehlt sie, gebildet wie der Adapter.
// Wie ExchangeCandleSourcesMajor.swift.

/** Binance Spot: `/api/v3/klines` — zuerst der öffentliche Spiegel, dann api.binance.com. */
object BinanceCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Binance")
    private val endpoints = listOf("https://data-api.binance.vision/api/v3/klines", "https://api.binance.com/api/v3/klines")

    fun symbol(pair: CandlePair): String? = CandleParse.id(pair.pairId) {
        val b = CandleParse.asset(pair.base)
        val q = CandleParse.asset(pair.quote)
        if (b == null || q == null) null else b + q
    }

    // Alpha-Paare («ALPHA_175USDT») haben eigene Kerzen ([BinanceAlphaCandles])
    override fun supports(pair: CandlePair) =
        pair.contractType == FuturesContractType.NONE && !BinanceAlpha.isPairId(pair.pairId) && symbol(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val symbol = symbol(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        for (endpoint in endpoints) {
            val body = http.get("$endpoint?symbol=$symbol&interval=$code&limit=${limit.coerceAtMost(1000)}") ?: continue
            val candles = runCatchingCancellable { CandleParse.binance(body, coinM = false) }.getOrNull()?.let { CandleParse.finish(it, limit) }
            if (candles != null && candles.size >= 2) return candles
        }
        return null
    }
}

/**
 * Binance Alpha ([BinanceAlpha]): `www.binance.com/bapi/defi/v1/public/alpha-trade/klines` für Paare mit
 * Kennung «ALPHA_<id>USDT» (unter der Börse Binance). Antwort `{"code":"000000","data":[[…Binance-Kline…]]}`.
 */
object BinanceAlphaCandles : ExchangeCandleSource {
    override val marketKeys = setOf(BinanceAlpha.MARKET_KEY)

    override fun supports(pair: CandlePair) =
        pair.contractType == FuturesContractType.NONE && BinanceAlpha.isPairId(pair.pairId)

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = pair.pairId?.takeIf { BinanceAlpha.isPairId(it) } ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val body = http.get(BinanceAlpha.klinesUrl(id, code, limit)) ?: return null
        return runCatchingCancellable { CandleParse.binance(BinanceAlpha.klines(body), coinM = false) }.getOrNull()
            ?.let { CandleParse.finish(it, limit) }
    }
}

/** Binance.US: `api.binance.us/api/v3/klines`. */
object BinanceUsCandles : ExchangeCandleSource {
    override val marketKeys = setOf("BinanceUs")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && BinanceCandles.symbol(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val symbol = BinanceCandles.symbol(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val body = http.get("https://api.binance.us/api/v3/klines?symbol=$symbol&interval=$code&limit=${limit.coerceAtMost(1000)}") ?: return null
        return runCatchingCancellable { CandleParse.binance(body, coinM = false) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }
}

/**
 * Binance Futures: USDⓈ-M `fapi.binance.com/fapi/v1/klines`, COIN-M (Kennung «2:BTCUSD_PERP»)
 * `dapi.binance.com/dapi/v1/klines`. Perpetuals (auch TradFi, gleiche Endpunkte) und Laufzeit-Kontrakte
 * (Quartal): deren Symbol aus der Paarliste wie die Kursabfrage (`BTCUSDT_251226`, COIN-M `BTCUSD_251226`)
 * — nie die Kerzen des Perpetuals.
 */
object BinanceFuturesCandles : ExchangeCandleSource {
    override val marketKeys = setOf("BinanceFutures")
    private const val COIN_M_PREFIX = "2:"
    private val DELIVERY_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyMMdd", java.util.Locale.ROOT)

    /** Verfallstag (UTC, «yyMMdd») des Laufzeit-Kontrakts wie die Kursabfrage; null = Perpetual/Spot. */
    fun deliveryCode(type: FuturesContractType): String? =
        FuturesContractType.getDeliveryDate(type)?.format(DELIVERY_FORMAT)

    fun id(pair: CandlePair): Pair<String, Boolean>? {
        val raw = pair.pairId?.trim()
        val coinM = raw != null && raw.startsWith(COIN_M_PREFIX)
        if (pair.isDated) {
            // Symbol der Börse aus der Paarliste wie die Kursabfrage («BTCUSDT_251226»), nie aus dem
            // Kalender (nach der Abrechnung Freitag 08:00 UTC träfe der den abgelaufenen Kontrakt)
            val stripped = raw?.removePrefix(COIN_M_PREFIX)
            if (FuturesContractType.deliveryCodeOf(stripped) != null) {
                return CandleParse.safeId(stripped)?.let { it to coinM }
            }
            // Ältere Kennung ohne Verfallstag: Kalender als Notbehelf
            val code = deliveryCode(pair.contractType) ?: return null
            val base = CandleParse.asset(pair.base) ?: return null
            val quote = CandleParse.asset(pair.quote) ?: return null
            return "$base${quote}_$code" to coinM
        }
        if (coinM) {
            return CandleParse.safeId(raw.removePrefix(COIN_M_PREFIX))?.let { it to true }
        }
        return BinanceCandles.symbol(pair)?.let { it to false }
    }

    override fun supports(pair: CandlePair) =
        (pair.contractType.isPerpetual || pair.isDated) && id(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val (symbol, coinM) = id(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val endpoint = if (coinM) "https://dapi.binance.com/dapi/v1/klines" else "https://fapi.binance.com/fapi/v1/klines"
        val body = http.get("$endpoint?symbol=$symbol&interval=$code&limit=${limit.coerceAtMost(1500)}") ?: return null
        return runCatchingCancellable { CandleParse.binance(body, coinM) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }
}

/**
 * Coinbase Exchange: `/products/{id}/candles` (höchstens 300 je Anfrage, NEUESTE zuerst; nur 1 h und
 * 1 Tag — 4 h aus Stundenkerzen zusammengesetzt; für 1 Jahr eine ältere Seite dazu).
 */
object CoinbaseCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Coinbase")
    private const val HOUR_MILLIS = 60 * 60_000L

    fun product(pair: CandlePair): String? = CandleParse.id(pair.pairId) {
        val b = CandleParse.asset(pair.base)
        val q = CandleParse.asset(pair.quote)
        if (b == null || q == null) null else "$b-$q"
    }

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && product(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val product = product(pair) ?: return null
        val granularity = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> 3600
            CandleInterval.D1 -> 86400
            CandleInterval.W1 -> return null
        }
        val url = "https://api.exchange.coinbase.com/products/$product/candles?granularity=$granularity"
        val body = http.get(url) ?: return null
        var raw = runCatchingCancellable { CandleDataSource.parseCoinbase(body) }.getOrNull() ?: return null
        val rawNeeded = limit * if (interval == CandleInterval.H4) 4 else 1
        val older = raw.firstOrNull()?.let {
            CandleSeries.coinbaseOlderWindow(it.openTime, raw.size, rawNeeded, granularity * 1000L)
        }
        if (older != null) {
            val olderBody = http.get("$url&start=${CandleParse.iso(older.first)}&end=${CandleParse.iso(older.last)}")
            val olderRaw = olderBody?.let { runCatchingCancellable { CandleDataSource.parseCoinbase(it) }.getOrNull() }
            if (olderRaw != null) raw = CandleSeries.mergeAscending(olderRaw, raw)
        }
        val candles = if (interval == CandleInterval.H4) CandleDataSource.aggregate(raw, 4 * HOUR_MILLIS, 0L, 4) else raw
        return CandleParse.finish(candles, limit)
    }
}

/**
 * Kraken: `/0/public/OHLC?pair={id}&interval={Minuten}` — höchstens die jüngsten 720 Kerzen.
 * Je Kerze [Zeit (s), open, high, low, close, vwap, Menge, Anzahl]; Quote-Umsatz = vwap × Menge.
 * Kennung = Schlüssel der Paarliste (z. B. «XXBTZUSD»); ohne Kennung keine eigenen Kerzen.
 */
object KrakenCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Kraken")

    override fun supports(pair: CandlePair) =
        pair.contractType == FuturesContractType.NONE && CandleParse.safeId(pair.pairId) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleParse.safeId(pair.pairId) ?: return null
        val minutes = when (interval) {
            CandleInterval.H1 -> 60
            CandleInterval.H4 -> 240
            CandleInterval.D1 -> 1440
            CandleInterval.W1 -> return null
        }
        val body = http.get("https://api.kraken.com/0/public/OHLC?pair=$id&interval=$minutes") ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if ((root.optJSONArray("error")?.length() ?: 0) > 0) return null
        val result = root.optJSONObject("result") ?: return null
        // Erster Schlüssel ausser «last» (Name je nach Paar, z. B. «XXBTZUSD»)
        val key = result.keys().asSequence().firstOrNull { it != "last" && result.optJSONArray(it) != null } ?: return null
        val array = result.getJSONArray(key)
        return with(CandleParse) {
            (0 until array.length()).mapNotNull { i ->
                val k = array.optJSONArray(i) ?: return@mapNotNull null
                val vwap = k.numAt(5)
                val volume = k.numAt(6)
                HourCandle(
                    openTime = k.numAt(0).toLong() * 1000L,
                    open = k.numAt(1), high = k.numAt(2), low = k.numAt(3), close = k.numAt(4),
                    volume = volume,
                    quoteVolume = quoteVolume(vwap * volume),
                )
            }
        }
    }

    override fun isRateLimited(body: String): Boolean = RateLimitSignal.inOkBody(body)
}

/**
 * Bitstamp: `/api/v2/ohlc/{url_symbol}/?step={s}&limit={n}` (höchstens 1000), älteste zuerst;
 * je Kerze ein Objekt mit Texten (Zeit in Sekunden).
 */
object BitstampCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Bitstamp")

    private fun symbol(pair: CandlePair): String? = CandleParse.id(pair.pairId) {
        val b = CandleParse.asset(pair.base)
        val q = CandleParse.asset(pair.quote)
        if (b == null || q == null) null else b + q
    }?.lowercase()

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && symbol(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val symbol = symbol(pair) ?: return null
        val step = when (interval) {
            CandleInterval.H1 -> 3600
            CandleInterval.H4 -> 14400
            CandleInterval.D1 -> 86400
            CandleInterval.W1 -> return null
        }
        val body = http.get("https://www.bitstamp.net/api/v2/ohlc/$symbol/?step=$step&limit=${limit.coerceAtMost(1000)}") ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val list = JSONObject(json).optJSONObject("data")?.optJSONArray("ohlc") ?: return null
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            HourCandle(
                openTime = CandleParse.num(o.opt("timestamp")).toLong() * 1000L,
                open = CandleParse.num(o.opt("open")), high = CandleParse.num(o.opt("high")),
                low = CandleParse.num(o.opt("low")), close = CandleParse.num(o.opt("close")),
                volume = CandleParse.num(o.opt("volume")),
            )
        }
    }
}

/**
 * Bitvavo: `/v2/{market}/candles?interval={1h|4h|1d}&limit={n}` (höchstens 1440), NEUESTE zuerst;
 * je Kerze [Zeit (ms), open, high, low, close, Menge] (Texte).
 */
object BitvavoCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Bitvavo")

    private fun market(pair: CandlePair): String? = CoinbaseCandles.product(pair)

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && market(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val market = market(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val body = http.get("https://api.bitvavo.com/v2/$market/candles?interval=$code&limit=${limit.coerceAtMost(1440)}") ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle> {
        val array = JSONArray(json)
        return with(CandleParse) {
            (0 until array.length()).mapNotNull { i ->
                array.optJSONArray(i)?.let { k ->
                    HourCandle(
                        openTime = k.numAt(0).toLong(),
                        open = k.numAt(1), high = k.numAt(2), low = k.numAt(3), close = k.numAt(4),
                        volume = k.numAt(5),
                    )
                } ?: array.optJSONObject(i)?.let { o ->
                    // Laut Dokumentation auch als Objekt mit Namen möglich
                    HourCandle(
                        openTime = num(o.opt("timestamp")).toLong(),
                        open = num(o.opt("open")), high = num(o.opt("high")), low = num(o.opt("low")),
                        close = num(o.opt("close")), volume = num(o.opt("volume")),
                    )
                }
            }
        }
    }
}

/**
 * Bybit v5: `/v5/market/kline?category={spot|linear}&symbol={id}&interval={60|240|D}&limit={n}`
 * (höchstens 1000), NEUESTE zuerst; je Kerze [Start (ms), open, high, low, close, Menge, Umsatz].
 * Spot für «Bybit», USDT/USDC-Perpetuals (linear, auch TradFi) für «BybitFutures».
 */
object BybitCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Bybit", "BybitFutures")

    private fun category(pair: CandlePair): String? = when {
        pair.marketKey == "BybitFutures" && pair.contractType == FuturesContractType.PERPETUAL -> "linear"
        pair.marketKey == "Bybit" && pair.contractType == FuturesContractType.NONE -> "spot"
        else -> null
    }

    override fun supports(pair: CandlePair) = category(pair) != null && BinanceCandles.symbol(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val category = category(pair) ?: return null
        val symbol = BinanceCandles.symbol(pair) ?: return null
        val code = when (interval) {
            CandleInterval.H1 -> "60"
            CandleInterval.H4 -> "240"
            CandleInterval.D1 -> "D"
            CandleInterval.W1 -> return null
        }
        val body = http.get(
            "https://api.bybit.com/v5/market/kline?category=$category&symbol=$symbol&interval=$code&limit=${limit.coerceAtMost(1000)}"
        ) ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (root.optInt("retCode", -1) != 0) return null
        val list = root.optJSONObject("result")?.optJSONArray("list") ?: return null
        return with(CandleParse) {
            (0 until list.length()).mapNotNull { i ->
                val k = list.optJSONArray(i) ?: return@mapNotNull null
                HourCandle(
                    openTime = k.numAt(0).toLong(),
                    open = k.numAt(1), high = k.numAt(2), low = k.numAt(3), close = k.numAt(4),
                    volume = k.numAt(5),
                    quoteVolume = quoteVolume(k.numAt(6)),
                )
            }
        }
    }

    /** retCode 10006 = zu viele Anfragen. */
    override fun isRateLimited(body: String): Boolean = RateLimitSignal.inOkBody(body)
}
