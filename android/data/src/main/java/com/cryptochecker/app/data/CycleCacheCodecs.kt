// JSON-Formate der zwischengespeicherten Markt-Bereiche (Teil von `CycleCacheStore.kt`); Hilfen in `CycleCacheJson.kt`.
package com.cryptochecker.app.data

import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.BtcFees
import com.cryptochecker.app.domain.market.CoinInputs
import com.cryptochecker.app.domain.market.CycleCachePolicy
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleInputs
import com.cryptochecker.app.domain.market.IndicatorHistory
import com.cryptochecker.app.domain.market.IndicatorId
import com.cryptochecker.app.domain.market.IndicatorStat
import com.cryptochecker.app.domain.market.CycleSeries
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.EvmGas
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.domain.market.GlobalMarket
import com.cryptochecker.app.domain.market.MarketMoversResult
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.market.OnChainValues
import com.cryptochecker.app.domain.market.PulseInput
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import com.cryptochecker.app.data.CycleCacheJson.dateOrNull
import com.cryptochecker.app.data.CycleCacheJson.doubleOrNull
import com.cryptochecker.app.data.CycleCacheJson.intOrNull
import com.cryptochecker.app.data.CycleCacheJson.markerOrNull
import com.cryptochecker.app.data.CycleCacheJson.putDate
import com.cryptochecker.app.data.CycleCacheJson.putMarker
import com.cryptochecker.app.data.CycleCacheJson.putNumber
import com.cryptochecker.app.data.CycleCacheJson.requireDouble
import com.cryptochecker.app.data.CycleCacheJson.toDoubleMap
import com.cryptochecker.app.data.CycleCacheJson.toJson

/** JSON-Formate der zwischengespeicherten Bereiche (Version: [CycleCachePolicy.FORMAT_VERSION]). */
object CycleCacheCodecs {

    val pulseInput = CacheCodec<PulseInput>(
        encode = { p ->
            JSONObject()
                .putNumber("btc24h", p.btc24h)
                .putNumber("eth24h", p.eth24h)
                .putNumber("sol24h", p.sol24h)
                .putNumber("volumeRatio", p.btcVolumeRatio)
                .putNumber("fearGreed", p.fearGreed)
                .putNumber("funding", p.fundingPercent)
                .putNumber("gas", p.ethGasGwei)
                .put("time", p.time)
                .put("top", JSONArray(p.topChanges.filter { it.isFinite() }))
                .putNumber("marketCap", p.marketCapUsd)
                .putNumber("marketCap24h", p.marketCap24h)
                .apply { p.movers?.let { put("movers", moversJson(it)) } }
        },
        decode = { o ->
            PulseInput(
                btc24h = o.doubleOrNull("btc24h"),
                eth24h = o.doubleOrNull("eth24h"),
                sol24h = o.doubleOrNull("sol24h"),
                btcVolumeRatio = o.doubleOrNull("volumeRatio"),
                fearGreed = o.intOrNull("fearGreed"),
                fundingPercent = o.doubleOrNull("funding"),
                ethGasGwei = o.doubleOrNull("gas"),
                time = o.getLong("time"),
                // Einträge ohne diese Felder: Zeilen entfallen bis zum nächsten Abruf
                topChanges = o.optJSONArray("top")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optDouble(i).takeIf { it.isFinite() } }
                }.orEmpty(),
                marketCapUsd = o.doubleOrNull("marketCap"),
                marketCap24h = o.doubleOrNull("marketCap24h"),
                // Einträge ohne «movers»: Karte entfällt bis zum nächsten Abruf
                movers = o.optJSONObject("movers")?.let { moversFrom(it) },
            )
        },
    )

    /** `{"up":[{"s":"SOL","n":"Solana","r":5,"c":7.1}],"down":[…]}` (Rang nur, wenn bekannt). */
    fun moversJson(m: MarketMoversResult): JSONObject {
        fun list(coins: List<MoverCoin>) = JSONArray().apply {
            coins.forEach { c ->
                val change = c.change24h?.takeIf { it.isFinite() } ?: return@forEach
                put(JSONObject().put("s", c.symbol).put("n", c.name).putNumber("r", c.rank).put("c", change))
            }
        }
        return JSONObject().put("up", list(m.gainers)).put("down", list(m.losers))
    }

    /** Lesen; unlesbare Einträge fallen weg. */
    fun moversFrom(o: JSONObject): MarketMoversResult {
        fun list(key: String): List<MoverCoin> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                val symbol = e.optString("s").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val change = e.doubleOrNull("c") ?: return@mapNotNull null
                MoverCoin(symbol, e.optString("n"), e.intOrNull("r"), change)
            }
        }
        return MarketMoversResult(list("up"), list("down"))
    }

    /** Ohne «today»: das Modell rechnet nach dem Laden mit dem aktuellen Datum. */
    val cycleInputs = CacheCodec<CycleInputs>(
        encode = { c ->
            JSONObject()
                .put("price", c.price)
                .putNumber("sma200d", c.sma200d)
                .putNumber("sma111d", c.sma111d)
                .putNumber("sma350d", c.sma350d)
                .putNumber("price30dAgo", c.price30dAgo)
                .putNumber("sma200w", c.sma200w)
                .putNumber("ath", c.ath)
                .putDate("athDate", c.athDate)
                .putNumber("mvrv", c.mvrv)
                .putNumber("puell", c.puell)
                .putNumber("hash30d", c.hash30d)
                .putNumber("hash60d", c.hash60d)
                .apply { c.history?.let { put("history", encodeHistory(it)) } }
        },
        decode = { o ->
            CycleInputs(
                price = o.requireDouble("price"),
                sma200d = o.doubleOrNull("sma200d"),
                sma111d = o.doubleOrNull("sma111d"),
                sma350d = o.doubleOrNull("sma350d"),
                price30dAgo = o.doubleOrNull("price30dAgo"),
                sma200w = o.doubleOrNull("sma200w"),
                ath = o.doubleOrNull("ath"),
                athDate = o.dateOrNull("athDate"),
                mvrv = o.doubleOrNull("mvrv"),
                puell = o.doubleOrNull("puell"),
                hash30d = o.doubleOrNull("hash30d"),
                hash60d = o.doubleOrNull("hash60d"),
                // Einträge ohne Verlauf: ohne Einordnung bis zum nächsten Abruf
                history = o.optJSONObject("history")?.let { decodeHistory(it) },
            )
        },
    )

    private fun encodeHistory(h: IndicatorHistory): JSONObject {
        val stats = JSONObject()
        h.stats.forEach { (id, stat) ->
            stats.put(id.name, JSONObject().putNumber("p", stat.percentileBelow).putNumber("f", stat.flat30))
        }
        return JSONObject().put("since", h.since.toString()).put("stats", stats)
    }

    private fun decodeHistory(o: JSONObject): IndicatorHistory? {
        val since = runCatching { java.time.LocalDate.parse(o.getString("since")) }.getOrNull() ?: return null
        val stats = HashMap<IndicatorId, IndicatorStat>()
        val json = o.optJSONObject("stats") ?: JSONObject()
        json.keys().forEach { key ->
            val id = IndicatorId.entries.firstOrNull { it.name == key } ?: return@forEach
            val s = json.optJSONObject(key) ?: return@forEach
            stats[id] = IndicatorStat(s.doubleOrNull("p"), s.doubleOrNull("f"))
        }
        return IndicatorHistory(since, stats)
    }

    val onChain = CacheCodec<OnChainValues>(
        encode = { v ->
            JSONObject()
                .putNumber("mvrv", v.mvrv)
                .putNumber("puell", v.puell)
                .putNumber("hash30d", v.hash30d)
                .putNumber("hash60d", v.hash60d)
        },
        decode = { o ->
            OnChainValues(
                mvrv = o.doubleOrNull("mvrv"),
                puell = o.doubleOrNull("puell"),
                hash30d = o.doubleOrNull("hash30d"),
                hash60d = o.doubleOrNull("hash60d"),
            )
        },
    )

    val fearGreed = CacheCodec<FearGreed>(
        encode = { f ->
            JSONObject()
                .put("value", f.value)
                .putNumber("yesterday", f.yesterday)
                .putNumber("weekAgo", f.weekAgo)
                .putNumber("monthAgo", f.monthAgo)
        },
        decode = { o ->
            FearGreed(
                value = o.getInt("value"),
                yesterday = o.intOrNull("yesterday"),
                weekAgo = o.intOrNull("weekAgo"),
                monthAgo = o.intOrNull("monthAgo"),
            )
        },
    )

    val global = CacheCodec<GlobalMarket>(
        encode = { g ->
            JSONObject()
                .put("btc", g.dominance.btc)
                .putNumber("eth", g.dominance.eth)
                .apply {
                    g.totals?.let { t ->
                        put(
                            "totals",
                            JSONObject()
                                .put("marketCap", t.marketCap.toJson())
                                .put("volume", t.volume.toJson())
                                .putNumber("change24h", t.changePercent24h)
                        )
                    }
                }
        },
        decode = { o ->
            GlobalMarket(
                dominance = Dominance(btc = o.requireDouble("btc"), eth = o.doubleOrNull("eth")),
                totals = o.optJSONObject("totals")?.let { t ->
                    MarketTotals(
                        marketCap = t.getJSONObject("marketCap").toDoubleMap(),
                        volume = t.getJSONObject("volume").toDoubleMap(),
                        changePercent24h = t.doubleOrNull("change24h"),
                    ).takeIf { it.marketCap.isNotEmpty() && it.volume.isNotEmpty() }
                },
            )
        },
    )

    val altSeason = CacheCodec<AltSeason>(
        encode = { a -> JSONObject().put("outperformers", a.outperformers).put("total", a.total) },
        decode = { o ->
            AltSeason(outperformers = o.getInt("outperformers"), total = o.getInt("total")).also {
                require(it.total >= 0 && it.outperformers in 0..it.total)
            }
        },
    )

    val history = CacheCodec<CycleHistory>(
        encode = { h ->
            val series = JSONArray()
            h.series.forEach { s ->
                val points = JSONArray()
                s.points.forEach { (day, value) -> if (value.isFinite()) points.put(JSONArray().put(day).put(value)) }
                series.put(
                    JSONObject()
                        .put("halving", s.halving.toString())
                        .put("points", points)
                        .putMarker("top", s.top)
                        .putMarker("bottom", s.bottom)
                        .putMarker("secondTop", s.secondTop)
                        .putMarker("secondBottom", s.secondBottom)
                )
            }
            JSONObject().put("series", series)
        },
        decode = { o ->
            val array = o.getJSONArray("series")
            CycleHistory(
                (0 until array.length()).map { i ->
                    val s = array.getJSONObject(i)
                    val points = s.getJSONArray("points")
                    CycleSeries(
                        halving = LocalDate.parse(s.getString("halving")),
                        points = (0 until points.length()).map { p ->
                            val pair = points.getJSONArray(p)
                            pair.getInt(0) to pair.getDouble(1)
                        },
                        top = s.markerOrNull("top"),
                        bottom = s.markerOrNull("bottom"),
                        secondTop = s.markerOrNull("secondTop"),
                        secondBottom = s.markerOrNull("secondBottom"),
                    )
                }
            )
        },
    )

    val coinInputs = CacheCodec<CoinInputs>(
        encode = { c ->
            JSONObject()
                .put("price", c.price)
                .putNumber("sma50d", c.sma50d)
                .putNumber("sma200d", c.sma200d)
                .putNumber("sma111d", c.sma111d)
                .putNumber("sma350d", c.sma350d)
                .putNumber("price30dAgo", c.price30dAgo)
                .putNumber("sma200w", c.sma200w)
                .putNumber("ath", c.ath)
                .putDate("athDate", c.athDate)
                .putNumber("rsiDaily", c.rsiDaily)
                .putNumber("rsiWeekly", c.rsiWeekly)
                .putNumber("vsBtc90d", c.vsBtc90d)
                .put("historyDays", c.historyDays)
        },
        decode = { o ->
            CoinInputs(
                price = o.requireDouble("price"),
                sma50d = o.doubleOrNull("sma50d"),
                sma200d = o.doubleOrNull("sma200d"),
                sma111d = o.doubleOrNull("sma111d"),
                sma350d = o.doubleOrNull("sma350d"),
                price30dAgo = o.doubleOrNull("price30dAgo"),
                sma200w = o.doubleOrNull("sma200w"),
                ath = o.doubleOrNull("ath"),
                athDate = o.dateOrNull("athDate"),
                rsiDaily = o.doubleOrNull("rsiDaily"),
                rsiWeekly = o.doubleOrNull("rsiWeekly"),
                vsBtc90d = o.doubleOrNull("vsBtc90d"),
                historyDays = o.getInt("historyDays"),
            )
        },
    )

    val gas = CacheCodec<GasReport>(
        encode = { g ->
            val evm = JSONArray()
            g.evm.forEach { e ->
                evm.put(
                    JSONObject()
                        .put("network", e.network.name)
                        .put("slow", e.slowGwei)
                        .put("normal", e.normalGwei)
                        .put("fast", e.fastGwei)
                        .putNumber("usd", e.transferUsd)
                )
            }
            JSONObject()
                .put("evm", evm)
                .put("time", g.time)
                .apply {
                    g.btc?.let { b ->
                        put(
                            "btc",
                            JSONObject()
                                .put("fast", b.fast)
                                .put("normal", b.normal)
                                .put("slow", b.slow)
                                .putNumber("usd", b.transferUsd)
                        )
                    }
                }
        },
        decode = { o ->
            val evm = o.getJSONArray("evm")
            val report = GasReport(
                evm = (0 until evm.length()).mapNotNull { i ->
                    val e = evm.getJSONObject(i)
                    // Unbekanntes Netz: auslassen
                    val network = GasNetwork.entries.firstOrNull { it.name == e.optString("network") }
                        ?: return@mapNotNull null
                    EvmGas(
                        network = network,
                        slowGwei = e.requireDouble("slow"),
                        normalGwei = e.requireDouble("normal"),
                        fastGwei = e.requireDouble("fast"),
                        transferUsd = e.doubleOrNull("usd"),
                    )
                },
                btc = o.optJSONObject("btc")?.let { b ->
                    BtcFees(
                        fast = b.requireDouble("fast"),
                        normal = b.requireDouble("normal"),
                        slow = b.requireDouble("slow"),
                        transferUsd = b.doubleOrNull("usd"),
                    )
                },
                time = o.getLong("time"),
            )
            require(report.evm.isNotEmpty() || report.btc != null) { "Gas ohne Werte" }
            report
        },
    )
}
