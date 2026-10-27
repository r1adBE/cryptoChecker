// OKX-Kerzen (Teil der eigenen Kerzen je Börse, `ExchangeCandleSourcesMajor.kt`; in iOS in `ExchangeCandleSourcesMajor.swift`).
package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.remote.CandleInterval
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.HourCandle
import com.cryptochecker.app.domain.refresh.RateLimitSignal
import com.cryptochecker.marketdata.model.FuturesContractType
import org.json.JSONObject

/**
 * OKX: `/api/v5/market/candles?instId={id}&bar={1H|4H|1Dutc}&limit={n}` (höchstens 300 je Seite,
 * ältere Seite mit `after`), NEUESTE zuerst; je Kerze [ts, o, h, l, c, vol, volCcy, volCcyQuote,
 * confirm]. Menge: Spot `vol`, Swap `volCcy` (vol zählt dort Kontrakte); Umsatz `volCcyQuote`.
 */
object OkxCandles : ExchangeCandleSource {
    override val marketKeys = setOf("Okex", "OkexFutures")
    private const val PAGE = 300

    private fun instId(pair: CandlePair): String? {
        val swap = pair.marketKey == "OkexFutures"
        if (swap && !pair.contractType.isPerpetual) return null
        if (!swap && pair.contractType != FuturesContractType.NONE) return null
        val id = CandleParse.id(pair.pairId) {
            val b = CandleParse.asset(pair.base)
            val q = CandleParse.asset(pair.quote)
            if (b == null || q == null) null else if (swap) "$b-$q-SWAP" else "$b-$q"
        }
        return id?.takeIf { it.endsWith("-SWAP") == swap }
    }

    override fun supports(pair: CandlePair) = instId(pair) != null

    override suspend fun fetch(pair: CandlePair, interval: CandleInterval, limit: Int, http: CandleHttp): List<HourCandle>? {
        val id = instId(pair) ?: return null
        val swap = pair.marketKey == "OkexFutures"
        val bar = when (interval) {
            CandleInterval.H1 -> "1H"
            CandleInterval.H4 -> "4H"
            CandleInterval.D1 -> "1Dutc"
            CandleInterval.W1 -> return null
        }
        val url = "https://www.okx.com/api/v5/market/candles?instId=$id&bar=$bar"
        val first = http.get("$url&limit=${limit.coerceAtMost(PAGE)}")?.let { runCatchingCancellable { parse(it, swap) }.getOrNull() } ?: return null
        var all = first
        // Mehr als eine Seite (1 Jahr Tageskerzen): ältere Kerzen vor der ältesten bisherigen
        if (limit > PAGE && first.size >= PAGE) {
            val oldest = first.minOf { it.openTime }
            val older = http.get("$url&after=$oldest&limit=${(limit - first.size).coerceIn(1, PAGE)}")
                ?.let { runCatchingCancellable { parse(it, swap) }.getOrNull() }
            if (older != null) all = older + first
        }
        return CandleParse.finish(all, limit)
    }

    fun parse(json: String, swap: Boolean): List<HourCandle>? {
        val root = JSONObject(json)
        if (root.optString("code") != "0") return null
        val data = root.optJSONArray("data") ?: return null
        return with(CandleParse) {
            (0 until data.length()).mapNotNull { i ->
                val k = data.optJSONArray(i) ?: return@mapNotNull null
                HourCandle(
                    openTime = k.numAt(0).toLong(),
                    open = k.numAt(1), high = k.numAt(2), low = k.numAt(3), close = k.numAt(4),
                    volume = if (swap) k.numAt(6) else k.numAt(5),
                    quoteVolume = quoteVolume(k.numAt(7)),
                )
            }
        }
    }

    /** code 50011 = zu viele Anfragen. */
    override fun isRateLimited(body: String): Boolean = RateLimitSignal.inOkBody(body)
}
