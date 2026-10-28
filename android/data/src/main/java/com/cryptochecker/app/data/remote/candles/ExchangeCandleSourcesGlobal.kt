package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.marketdata.model.FuturesContractType
import org.json.JSONArray
import org.json.JSONObject

// Eigene Kerzen, zweite Runde: weitere Börsen mit öffentlichem Endpunkt ohne Schlüssel (Stand Oktober
// 2026, je aus der Dokumentation der Börse). Kennung des Paars immer wie beim Kursabruf (ohne Kennung
// keine eigenen Kerzen). Wie ExchangeCandleSourcesGlobal.swift.

/** Bitfinex: `/v2/candles/trade:{1h|1D}:{tSYMBOL}/hist?limit&sort=-1` — [MTS, OPEN, CLOSE, HIGH, LOW, VOLUME]; 4 h aus 1 h. */
object BitfinexCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("Bitfinex")
    private fun id(pair: CandlePair) = CandleTime.pairId(pair, ":")?.takeIf { it.startsWith("t") }

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && id(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = id(pair) ?: return null
        val tf = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> "1h"
            CandleInterval.D1 -> "1D"
            CandleInterval.W1 -> return null
        }
        val n = CandleTime.rawCount(interval, limit).coerceAtMost(10_000)
        val body = http.get("https://api-pub.bitfinex.com/v2/candles/trade:$tf:$id/hist?limit=$n&sort=-1") ?: return null
        val raw = runCatchingCancellable { parse(body) }.getOrNull() ?: return null
        val candles = if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw
        return CandleParse.finish(candles ?: return null, limit)
    }

    fun parse(json: String): List<HourCandle> = with(CandleParse) {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val k = a.optJSONArray(i) ?: return@mapNotNull null
            HourCandle(openTime = k.numAt(0).toLong(), open = k.numAt(1), close = k.numAt(2), high = k.numAt(3),
                low = k.numAt(4), volume = k.numAt(5))
        }
    }
}

/**
 * Bitget (Unified, `/api/v3/market/candles?category={SPOT|USDT-FUTURES}&symbol&interval={1H|4H|1D}&limit`,
 * höchstens 100 je Seite, ältere Seiten mit `endTime`) — [ts, open, high, low, close, Menge, Umsatz].
 * Spot für «Bitget», USDT-Perpetuals (auch TradFi/RWA) für «BitgetFutures».
 */
object BitgetCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("Bitget", "BitgetFutures")
    private const val PAGE = 100
    private const val MAX_PAGES = 4

    private fun category(pair: CandlePair): String? = when {
        pair.exchangeKey == "BitgetFutures" && pair.contractType == FuturesContractType.PERPETUAL -> "USDT-FUTURES"
        pair.exchangeKey == "Bitget" && pair.contractType == FuturesContractType.NONE -> "SPOT"
        else -> null
    }

    override fun supports(pair: CandlePair) = category(pair) != null && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val category = category(pair) ?: return null
        val id = CandleTime.pairId(pair) ?: return null
        val code = when (interval) {
            CandleInterval.H1 -> "1H"
            CandleInterval.H4 -> "4H"
            CandleInterval.D1 -> "1D"
            CandleInterval.W1 -> return null
        }
        val base = "https://api.bitget.com/api/v3/market/candles?category=$category&symbol=$id&interval=$code"
        var all = emptyList<HourCandle>()
        var end: Long? = null
        for (page in 0 until MAX_PAGES) {
            val want = (limit - all.size).coerceIn(1, PAGE)
            val url = "$base&limit=$want" + (end?.let { "&endTime=$it" } ?: "")
            val rows = http.get(url)?.let { runCatchingCancellable { parse(it) }.getOrNull() } ?: break
            all = rows + all
            if (rows.size < want || all.size >= limit) break
            end = rows.minOf { it.openTime } - 1
        }
        return CandleParse.finish(all, limit).takeIf { it.isNotEmpty() }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (root.optString("code") != "00000") return null
        val data = root.optJSONArray("data") ?: return null
        return with(CandleParse) {
            (0 until data.length()).mapNotNull { i ->
                val k = data.optJSONArray(i) ?: return@mapNotNull null
                HourCandle(openTime = k.numAt(0).toLong(), open = k.numAt(1), high = k.numAt(2), low = k.numAt(3),
                    close = k.numAt(4), volume = k.numAt(5), quoteVolume = quoteVolume(k.numAt(6)))
            }
        }
    }
}

/** Gate.io Spot: `/api/v4/spot/candlesticks?currency_pair&interval={1h|4h|1d}&limit` — [t (s), Umsatz, close, high, low, open, Menge]. */
object GateIoCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("GateIo")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val body = http.get("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=$id&interval=$code&limit=${limit.coerceAtMost(1000)}")
            ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle> = with(CandleParse) {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val k = a.optJSONArray(i) ?: return@mapNotNull null
            HourCandle(openTime = k.numAt(0).toLong() * 1000L, open = k.numAt(5), high = k.numAt(3), low = k.numAt(4),
                close = k.numAt(2), volume = k.numAt(6), quoteVolume = quoteVolume(k.numAt(1)))
        }
    }
}

/**
 * KuCoin Spot: `/api/v1/market/candles?type={1hour|4hour|1day}&symbol&startAt&endAt` (Sekunden, höchstens
 * 1500), NEUESTE zuerst — [time (s), open, close, high, low, Menge, Umsatz].
 */
object KucoinCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("Kucoin")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val type = when (interval) {
            CandleInterval.H1 -> "1hour"
            CandleInterval.H4 -> "4hour"
            CandleInterval.D1 -> "1day"
            CandleInterval.W1 -> return null
        }
        val now = http.now
        val start = CandleTime.windowStart(now, interval.millis, limit) / 1000L
        val body = http.get("https://api.kucoin.com/api/v1/market/candles?type=$type&symbol=$id&startAt=$start&endAt=${now / 1000L}")
            ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (root.optString("code") != "200000") return null
        val data = root.optJSONArray("data") ?: return null
        return with(CandleParse) {
            (0 until data.length()).mapNotNull { i ->
                val k = data.optJSONArray(i) ?: return@mapNotNull null
                HourCandle(openTime = k.numAt(0).toLong() * 1000L, open = k.numAt(1), close = k.numAt(2), high = k.numAt(3),
                    low = k.numAt(4), volume = k.numAt(5), quoteVolume = quoteVolume(k.numAt(6)))
            }
        }
    }
}

/** MEXC Spot: `/api/v3/klines?symbol&interval={60m|4h|1d}&limit` (höchstens 500) — Binance-Aufbau. */
object MexcCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("Mexc")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val code = when (interval) {
            CandleInterval.H1 -> "60m"
            CandleInterval.H4 -> "4h"
            CandleInterval.D1 -> "1d"
            CandleInterval.W1 -> return null
        }
        val body = http.get("https://api.mexc.com/api/v3/klines?symbol=$id&interval=$code&limit=${limit.coerceAtMost(500)}") ?: return null
        return runCatchingCancellable { CandleParse.binance(body, coinM = false) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }
}

/**
 * MEXC Futures: `contract.mexc.com/api/v1/contract/kline/{symbol}?interval={Min60|Hour4|Day1}&start&end`
 * (Sekunden) — Felder als parallele Listen time/open/close/high/low/vol/amount; `vol` zählt Kontrakte,
 * `amount` ist der Umsatz (Menge daraus genähert). Auch TradFi-Kontrakte.
 */
object MexcFuturesCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("MexcFutures")

    override fun supports(pair: CandlePair) = pair.contractType.isPerpetual && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val code = when (interval) {
            CandleInterval.H1 -> "Min60"
            CandleInterval.H4 -> "Hour4"
            CandleInterval.D1 -> "Day1"
            CandleInterval.W1 -> return null
        }
        val now = http.now
        val start = CandleTime.windowStart(now, interval.millis, limit) / 1000L
        val body = http.get("https://contract.mexc.com/api/v1/contract/kline/$id?interval=$code&start=$start&end=${now / 1000L}")
            ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) return null
        val d = root.optJSONObject("data") ?: return null
        val time = d.optJSONArray("time") ?: return null
        fun col(name: String, i: Int): Double = d.optJSONArray(name)?.let { if (i < it.length()) CandleParse.num(it.opt(i)) else Double.NaN } ?: Double.NaN
        return (0 until time.length()).map { i ->
            val close = col("close", i)
            val amount = col("amount", i)
            HourCandle(openTime = CandleParse.num(time.opt(i)).toLong() * 1000L, open = col("open", i), high = col("high", i),
                low = col("low", i), close = close, volume = CandleTime.baseFromQuote(amount, close),
                quoteVolume = CandleParse.quoteVolume(amount))
        }
    }
}

/**
 * HTX Spot: `api.huobi.pro/market/history/kline?symbol&period={60min|1day}&size` (höchstens 2000), je Kerze
 * {id (s), open, close, low, high, amount (Menge), vol (Umsatz)}; 4 h aus Stundenkerzen.
 */
object HtxCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("Htx")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair)?.lowercase() ?: return null
        val period = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> "60min"
            CandleInterval.D1 -> "1day"
            CandleInterval.W1 -> return null
        }
        val n = CandleTime.rawCount(interval, limit).coerceAtMost(2000)
        val body = http.get("https://api.huobi.pro/market/history/kline?symbol=$id&period=$period&size=$n") ?: return null
        val raw = runCatchingCancellable { parse(body) }.getOrNull() ?: return null
        val candles = (if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw) ?: return null
        return CandleParse.finish(candles, limit)
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (root.optString("status") != "ok") return null
        val data = root.optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            HourCandle(openTime = CandleParse.num(o.opt("id")).toLong() * 1000L, open = CandleParse.num(o.opt("open")),
                high = CandleParse.num(o.opt("high")), low = CandleParse.num(o.opt("low")), close = CandleParse.num(o.opt("close")),
                volume = CandleParse.num(o.opt("amount")), quoteVolume = CandleParse.quoteVolume(CandleParse.num(o.opt("vol"))))
        }
    }
}

/**
 * Gemini: `/v2/candles/{symbol}/{1hr|1day}` — [ts (ms), open, high, low, close, Menge]. Die Dokumentation
 * nennt auch «1h»/«1d»; antwortet die erste Schreibweise nicht, die zweite. 4 h aus Stundenkerzen.
 */
object GeminiCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("Gemini")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair)?.lowercase() ?: return null
        val frames = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> listOf("1hr", "1h")
            CandleInterval.D1 -> listOf("1day", "1d")
            CandleInterval.W1 -> return null
        }
        for (frame in frames) {
            val raw = http.get("https://api.gemini.com/v2/candles/$id/$frame")?.let { runCatchingCancellable { parse(it) }.getOrNull() } ?: continue
            val candles = (if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw) ?: continue
            return CandleParse.finish(candles, limit)
        }
        return null
    }

    fun parse(json: String): List<HourCandle> = with(CandleParse) {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val k = a.optJSONArray(i) ?: return@mapNotNull null
            HourCandle(openTime = k.numAt(0).toLong(), open = k.numAt(1), high = k.numAt(2), low = k.numAt(3),
                close = k.numAt(4), volume = k.numAt(5))
        }
    }
}

/**
 * Crypto.com Exchange: `/exchange/v1/public/get-candlestick?instrument_name&timeframe={1h|4h|1D}&count&start_ts&end_ts`
 * (ms) — result.data [{o, h, l, c, v, t}].
 */
object CryptoComCandles : ExchangeCandleSource {
    override val exchangeKeys = setOf("CryptoCom")

    override fun supports(pair: CandlePair) = CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val tf = when (interval) {
            CandleInterval.H1 -> "1h"
            CandleInterval.H4 -> "4h"
            CandleInterval.D1 -> "1D"
            CandleInterval.W1 -> return null
        }
        val now = http.now
        val start = CandleTime.windowStart(now, interval.millis, limit)
        val body = http.get(
            "https://api.crypto.com/exchange/v1/public/get-candlestick?instrument_name=$id&timeframe=$tf&count=$limit&start_ts=$start&end_ts=$now"
        ) ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("result")?.optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            HourCandle(openTime = CandleParse.num(o.opt("t")).toLong(), open = CandleParse.num(o.opt("o")),
                high = CandleParse.num(o.opt("h")), low = CandleParse.num(o.opt("l")), close = CandleParse.num(o.opt("c")),
                volume = CandleParse.num(o.opt("v")))
        }
    }
}
