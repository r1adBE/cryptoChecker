package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleDataSource
import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.marketdata.model.FuturesContractType
import org.json.JSONArray
import org.json.JSONObject

// Eigene Kerzen, zweite Runde: weitere Börsen mit öffentlichem Endpunkt ohne Schlüssel (Stand Oktober
// 2026, je aus der Dokumentation der Börse). Kennung des Paars immer wie beim Kursabruf (ohne Kennung
// keine eigenen Kerzen). Wie ExchangeCandleSourcesMore.swift.

/** Zeit- und Kennungs-Helfer der zweiten Runde. */
internal object CandleTime {
    const val HOUR = 3_600_000L

    /** Beginn des laufenden Intervalls (UTC-Raster). */
    fun aligned(now: Long, step: Long): Long = Math.floorDiv(now, step) * step

    /** Beginn eines Fensters, das [count] Kerzen bis zur laufenden umfasst. */
    fun windowStart(now: Long, step: Long, count: Int): Long = aligned(now, step) - (count - 1).coerceAtLeast(0) * step

    /** Kennung des Paars mit zusätzlich erlaubten Zeichen [extra] (z. B. «:» bei Bitfinex); sonst null. */
    fun pairId(pair: CandlePair, extra: String = ""): String? {
        val t = pair.pairId?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() && it.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '_' || c in extra } }
    }

    /** Tage seit 1970 für ein Datum (proleptisch gregorianisch, ohne Zeitzone). */
    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** «2025-10-14T00:00:00», «…T13:30:00.000Z» (UTC) → Epoch-ms; null, wenn nicht lesbar. */
    fun parseIso(s: String?): Long? {
        val t = s?.trim()?.removeSuffix("Z") ?: return null
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3})\d*)?$""").matchEntire(t) ?: return null
        val g = m.groupValues
        val days = daysFromCivil(g[1].toLong(), g[2].toInt(), g[3].toInt())
        val ms = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toLong() ?: 0L
        return ((days * 24 + g[4].toLong()) * 60 + g[5].toLong()) * 60_000L + g[6].toLong() * 1000L + ms
    }

    /** Epoch-ms → «2025-10-14T00:00:00.000Z» (UTC). */
    fun formatIso(millis: Long): String {
        val days = Math.floorDiv(millis, 86_400_000L)
        val rest = millis - days * 86_400_000L
        // Datum aus Tagen (Umkehrung von daysFromCivil)
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        val h = rest / 3_600_000L
        val min = rest / 60_000L % 60
        val sec = rest / 1000L % 60
        val ms = rest % 1000L
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(java.util.Locale.ROOT, y, m, d, h, min, sec, ms)
    }

    /** 4 h aus Stundenkerzen (UTC-Raster); null bleibt null. */
    fun fourHours(hourly: List<HourCandle>?): List<HourCandle>? =
        hourly?.let { CandleDataSource.aggregate(it.sortedBy { c -> c.openTime }, 4 * HOUR, 0L, 4) }

    /** Rohkerzen für [limit] Kerzen (4 h aus Stundenkerzen: viermal so viele und ein Block Reserve). */
    fun rawCount(interval: CandleInterval, limit: Int): Int = if (interval == CandleInterval.H4) limit * 4 + 4 else limit

    /** Menge in der Basis aus dem Quote-Umsatz (nur genähert) — wenn die Quelle nur Kontrakte zählt. */
    fun baseFromQuote(quote: Double, close: Double): Double =
        if (quote.isFinite() && quote >= 0.0 && close.isFinite() && close > 0.0) quote / close else Double.NaN
}

/** Bitfinex: `/v2/candles/trade:{1h|1D}:{tSYMBOL}/hist?limit&sort=-1` — [MTS, OPEN, CLOSE, HIGH, LOW, VOLUME]; 4 h aus 1 h. */
object BitfinexCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Bitfinex")
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
        val raw = runCatching { parse(body) }.getOrNull() ?: return null
        val candles = if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw
        return CandleParse.finish(candles ?: return null, limit)
    }

    internal fun parse(json: String): List<HourCandle> = with(CandleParse) {
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
    override val marketKeys = setOf("Bitget", "BitgetFutures")
    private const val PAGE = 100
    private const val MAX_PAGES = 4

    private fun category(pair: CandlePair): String? = when {
        pair.marketKey == "BitgetFutures" && pair.contractType == FuturesContractType.PERPETUAL -> "USDT-FUTURES"
        pair.marketKey == "Bitget" && pair.contractType == FuturesContractType.NONE -> "SPOT"
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
            val rows = http.get(url)?.let { runCatching { parse(it) }.getOrNull() } ?: break
            all = rows + all
            if (rows.size < want || all.size >= limit) break
            end = rows.minOf { it.openTime } - 1
        }
        return CandleParse.finish(all, limit).takeIf { it.isNotEmpty() }
    }

    internal fun parse(json: String): List<HourCandle>? {
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
    override val marketKeys = setOf("GateIo")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair) ?: return null
        val code = CandleParse.binanceInterval(interval) ?: return null
        val body = http.get("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=$id&interval=$code&limit=${limit.coerceAtMost(1000)}")
            ?: return null
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle> = with(CandleParse) {
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
    override val marketKeys = setOf("Kucoin")

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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle>? {
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
    override val marketKeys = setOf("Mexc")

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
        return runCatching { CandleParse.binance(body, coinM = false) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }
}

/**
 * MEXC Futures: `contract.mexc.com/api/v1/contract/kline/{symbol}?interval={Min60|Hour4|Day1}&start&end`
 * (Sekunden) — Felder als parallele Listen time/open/close/high/low/vol/amount; `vol` zählt Kontrakte,
 * `amount` ist der Umsatz (Menge daraus genähert). Auch TradFi-Kontrakte.
 */
object MexcFuturesCandles : ExchangeCandleSource {
    override val marketKeys = setOf("MexcFutures")

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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle>? {
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
    override val marketKeys = setOf("Htx")

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
        val raw = runCatching { parse(body) }.getOrNull() ?: return null
        val candles = (if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw) ?: return null
        return CandleParse.finish(candles, limit)
    }

    internal fun parse(json: String): List<HourCandle>? {
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
    override val marketKeys = setOf("Gemini")

    override fun supports(pair: CandlePair) = pair.contractType == FuturesContractType.NONE && CandleTime.pairId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = CandleTime.pairId(pair)?.lowercase() ?: return null
        val frames = when (interval) {
            CandleInterval.H1, CandleInterval.H4 -> listOf("1hr", "1h")
            CandleInterval.D1 -> listOf("1day", "1d")
            CandleInterval.W1 -> return null
        }
        for (frame in frames) {
            val raw = http.get("https://api.gemini.com/v2/candles/$id/$frame")?.let { runCatching { parse(it) }.getOrNull() } ?: continue
            val candles = (if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw) ?: continue
            return CandleParse.finish(candles, limit)
        }
        return null
    }

    internal fun parse(json: String): List<HourCandle> = with(CandleParse) {
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
    override val marketKeys = setOf("CryptoCom")

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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle>? {
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
        val first = http.get("$url&count=${limit.coerceAtMost(PAGE)}")?.let { runCatching { parse(it) }.getOrNull() } ?: return null
        var all = first
        if (limit > PAGE && first.size >= PAGE) {
            val oldest = first.minOf { it.openTime }
            val to = CandleTime.formatIso(oldest).substring(0, 19) + "Z"
            val older = http.get("$url&count=${(limit - first.size).coerceIn(1, PAGE)}&to=$to")?.let { runCatching { parse(it) }.getOrNull() }
            if (older != null) all = older + first
        }
        return CandleParse.finish(all, limit)
    }

    internal fun parse(json: String): List<HourCandle> {
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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle>? {
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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle> = with(CandleParse) {
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
        val raw = runCatching { parse(body) }.getOrNull() ?: return null
        val candles = (if (interval == CandleInterval.H4) CandleTime.fourHours(raw) else raw) ?: return null
        return CandleParse.finish(candles, limit)
    }

    internal fun parse(json: String): List<HourCandle>? {
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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle> {
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
        return runCatching { parse(body) }.getOrNull()?.let { CandleParse.finish(it, limit) }
    }

    internal fun parse(json: String): List<HourCandle>? {
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
