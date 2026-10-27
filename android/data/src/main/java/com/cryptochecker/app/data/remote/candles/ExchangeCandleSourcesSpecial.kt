// Eigene Kerzen, dritte Runde: Upbit bis One Trading (Teil von `ExchangeCandleSourcesGlobal.kt`, gespiegelt in
// `ExchangeCandleSourcesSpecial.swift`).
package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.marketdata.model.FuturesContractType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Upbit: `/v1/candles/minutes/{60|240}` bzw. `/v1/candles/days` `?market&count` (höchstens 200, ältere Seite
 * mit `to`), NEUESTE zuerst — {candle_date_time_utc, opening_price, high_price, low_price, trade_price,
 * candle_acc_trade_volume, candle_acc_trade_price}.
 */
object UpbitCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Upbit")
    private const val PAGE = 200

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val path = when (interval) {
            CandleInterval.H1 -> "minutes/60"
            CandleInterval.H4 -> "minutes/240"
            CandleInterval.D1 -> "days"
            CandleInterval.W1 -> return null
        }
        val url = "https://api.upbit.com/v1/candles/$path?market=$id"
        val first = http.get("$url&count=${limit.coerceAtMost(PAGE)}")?.let { runCatchingCancellable { parse(it) }.getOrNull() } ?: return null
        var all = first
        if (limit > PAGE && first.size >= PAGE) {
            val oldest = first.minOf { it.openTime }
            val to = CandleTime.formatIso(oldest).substring(0, 19) + "Z"
            val older = http.get("$url&count=${(limit - first.size).coerceIn(1, PAGE)}&to=$to")?.let { runCatchingCancellable { parse(it) }.getOrNull() }
            if (older != null) all = older + first
        }
        return CandleParse.finish(all, limit)
    }

    fun parse(json: String): List<HourCandle> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val time = CandleTime.parseIso(o.optString("candle_date_time_utc")) ?: return@mapNotNull null
            HourCandle(openTime = time, open = CandleParse.num(o.opt("opening_price")), high = CandleParse.num(o.opt("high_price")),
                low = CandleParse.num(o.opt("low_price")), close = CandleParse.num(o.opt("trade_price")),
                volume = CandleParse.num(o.opt("candle_acc_trade_volume")),
                quoteVolume = CandleParse.quoteVolume(CandleParse.num(o.opt("candle_acc_trade_price"))))
        }
    }
}

/**
 * WOO X: `/v3/public/kline?symbol&type={1h|4h|1d}&limit` (höchstens 1000) — data.rows [{open, close, low,
 * high, volume (Menge), amount (Umsatz), startTimestamp}]. Spot («SPOT_…») und Perpetuals («PERP_…»).
 */
object WooCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Woo", "WooFutures")

    private fun id(pair: CandlePair): String? {
        val id = CandleTime.pairId(pair) ?: return null
        return when {
            pair.marketKey == "WooFutures" && pair.contractType == FuturesContractType.PERPETUAL && id.startsWith("PERP_") -> id
            pair.marketKey == "Woo" && pair.contractType == FuturesContractType.NONE && id.startsWith("SPOT_") -> id
            else -> null
        }
    }

    override fun supports(pair: CandlePair) = id(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = id(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val body = http.get("https://api.woox.io/v3/public/kline?symbol=$id&type=$code&limit=${limit.coerceAtMost(1000)}") ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        if (!root.optBoolean("success", false)) return null
        val rows = root.optJSONObject("data")?.optJSONArray("rows") ?: return null
        return (0 until rows.length()).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            HourCandle(openTime = CandleParse.num(o.opt("startTimestamp")).toLong(), open = CandleParse.num(o.opt("open")),
                high = CandleParse.num(o.opt("high")), low = CandleParse.num(o.opt("low")), close = CandleParse.num(o.opt("close")),
                volume = CandleParse.num(o.opt("volume")), quoteVolume = CandleParse.quoteVolume(CandleParse.num(o.opt("amount"))))
        }
    }
}

/**
 * Poloniex: `/markets/{symbol}/candles?interval={HOUR_1|HOUR_4|DAY_1}&limit` (höchstens 500) — [low, high,
 * open, close, amount (Umsatz), quantity (Menge), …, startTime (Index 12), closeTime].
 */
object PoloniexCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Poloniex")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val code = when (interval) {
            CandleInterval.H1 -> "HOUR_1"
            CandleInterval.H4 -> "HOUR_4"
            CandleInterval.D1 -> "DAY_1"
            CandleInterval.W1 -> return null
        }
        val body = http.get("https://api.poloniex.com/markets/$id/candles?interval=$code&limit=${limit.coerceAtMost(500)}") ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle> = with(CandleParse) {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val k = a.optJSONArray(i) ?: return@mapNotNull null
            HourCandle(openTime = k.numAt(12).toLong(), open = k.numAt(2), high = k.numAt(1), low = k.numAt(0),
                close = k.numAt(3), volume = k.numAt(5), quoteVolume = quoteVolume(k.numAt(4)))
        }
    }
}

/**
 * Deribit: `/api/v2/public/get_tradingview_chart_data?instrument_name&start_timestamp&end_timestamp&resolution={60|1D}`
 * (ms) — result {ticks, open, high, low, close, volume (Basis), cost (Quote), status}; 4 h aus 60 Minuten.
 */
object DeribitCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Deribit")

    override fun supports(pair: CandlePair) = pair.contractType.isPerpetual && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val (resolution, step) = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> "60" to CandleTime.HOUR
            CandleInterval.D1 -> "1D" to CandleInterval.D1.millis
            CandleInterval.W1 -> return null
        }
        val now = http.now
        val start = CandleTime.windowStart(now, step, CandleTime.rawCount(interval, limit))
        val body = http.get(
            "https://www.deribit.com/api/v2/public/get_tradingview_chart_data?instrument_name=$id&start_timestamp=$start&end_timestamp=$now&resolution=$resolution"
        ) ?: return null
        val raw = runCatchingCancellable { parse(body) }.getOrNull() ?: return null
        val candles = (if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw) ?: return null
        return CandleParse.finish(candles, limit)
    }

    fun parse(json: String): List<HourCandle>? {
        val r = JSONObject(json).optJSONObject("result") ?: return null
        if (r.optString("status") != "ok") return null
        val ticks = r.optJSONArray("ticks") ?: return null
        fun col(name: String, i: Int): Double = r.optJSONArray(name)?.let { if (i < it.length()) CandleParse.num(it.opt(i)) else Double.NaN } ?: Double.NaN
        return (0 until ticks.length()).map { i ->
            HourCandle(openTime = CandleParse.num(ticks.opt(i)).toLong(), open = col("open", i), high = col("high", i),
                low = col("low", i), close = col("close", i), volume = col("volume", i),
                quoteVolume = CandleParse.quoteVolume(col("cost", i)))
        }
    }
}

/**
 * Hyperliquid: POST `/info` {"type":"candleSnapshot","req":{"coin","interval":"1h|4h|1d","startTime","endTime"}}
 * (ms, höchstens die jüngsten 5000) — [{t, T, s, i, o, c, h, l, v, n}].
 */
object HyperliquidCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Hyperliquid")
    private fun coin(pair: CandlePair) = CandleTime.pairId(pair, ":")

    override fun supports(pair: CandlePair) = pair.contractType.isPerpetual && coin(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val coin = coin(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val now = http.now
        val start = CandleTime.windowStart(now, interval.millis, limit)
        val json = """{"type":"candleSnapshot","req":{"coin":"$coin","interval":"$code","startTime":$start,"endTime":$now}}"""
        val body = http.post("https://api.hyperliquid.xyz/info", json) ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            HourCandle(openTime = CandleParse.num(o.opt("t")).toLong(), open = CandleParse.num(o.opt("o")),
                high = CandleParse.num(o.opt("h")), low = CandleParse.num(o.opt("l")), close = CandleParse.num(o.opt("c")),
                volume = CandleParse.num(o.opt("v")))
        }
    }
}

/**
 * One Trading: `/fast/v1/candlesticks/{instrument}?unit={HOURS|DAYS}&period={1|4}&from&to&countBack` (ISO 8601,
 * NEUESTE zuerst) — {open, high, low, close, volume, start_time}. `volume` ist laut Beispiel der Umsatz in der
 * Quote (Menge daraus genähert).
 */
object OneTradingCandles : ExchangeCandleSource {
    override val marketKeys = setOf("OneTrading")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val (unit, period) = when (interval) {
            CandleInterval.H1 -> "HOURS" to 1
            CandleInterval.H4 -> "HOURS" to 4
            CandleInterval.D1 -> "DAYS" to 1
            CandleInterval.W1 -> return null
        }
        val now = http.now
        val from = CandleTime.formatIso(CandleTime.windowStart(now, interval.millis, limit)).replace(":", "%3A")
        val to = CandleTime.formatIso(now).replace(":", "%3A")
        val body = http.get(
            "https://api.onetrading.com/fast/v1/candlesticks/$id?unit=$unit&period=$period&from=$from&to=$to&countBack=$limit"
        ) ?: return null
        return runCatchingCancellable { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    fun parse(json: String): List<HourCandle>? {
        val root = JSONObject(json)
        val list = root.optJSONArray("candlesticks") ?: return if (root.optString("status") == "no_data") emptyList() else null
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val time = CandleTime.parseIso(o.optString("start_time")) ?: return@mapNotNull null
            val close = CandleParse.num(o.opt("close"))
            val quote = CandleParse.num(o.opt("volume"))
            HourCandle(openTime = time, open = CandleParse.num(o.opt("open")), high = CandleParse.num(o.opt("high")),
                low = CandleParse.num(o.opt("low")), close = close, volume = CandleTime.baseFromQuote(quote, close),
                quoteVolume = CandleParse.quoteVolume(quote))
        }
    }
}
