package com.cryptochecker.app.data

import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.macro.MacroEventType
import com.cryptochecker.app.domain.market.TurnoverSample
import com.cryptochecker.app.domain.market.UnusualCoin
import com.cryptochecker.app.domain.market.UnusualInput
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-Formate für «Heute auffällig» und den Kalender der Wirtschaftsdaten im
 * Zwischenspeicher des Markt-Tabs ([CycleCacheStore]). Eigene Datei, damit die
 * Formate der übrigen Bereiche ([CycleCacheCodecs]) unberührt bleiben.
 */
object MarketExtraCodecs {

    val unusualInput = CacheCodec<UnusualInput>(
        encode = { input ->
            val coins = JSONArray()
            input.coins.forEach { c ->
                coins.put(
                    JSONObject()
                        .put("s", c.symbol)
                        .put("n", c.name)
                        .put("c", c.change24h)
                        .putFinite("v", c.quoteVolume)
                        .putFinite("m", c.marketCap)
                        .putFinite("f", c.fundingPercent)
                )
            }
            val own = JSONObject()
            input.ownTypical.forEach { (symbol, value) -> if (value.isFinite()) own.put(symbol, value) }
            JSONObject().put("coins", coins).put("own", own).put("time", input.time)
        },
        decode = { o ->
            val array = o.getJSONArray("coins")
            val coins = (0 until array.length()).map { i ->
                val c = array.getJSONObject(i)
                UnusualCoin(
                    symbol = c.getString("s"),
                    name = c.optString("n", c.getString("s")),
                    change24h = c.getDouble("c"),
                    quoteVolume = c.finiteOrNull("v"),
                    marketCap = c.finiteOrNull("m"),
                    fundingPercent = c.finiteOrNull("f"),
                )
            }
            val ownJson = o.optJSONObject("own")
            val own = HashMap<String, Double>()
            ownJson?.keys()?.forEach { key -> ownJson.finiteOrNull(key)?.let { own[key] = it } }
            UnusualInput(coins = coins, ownTypical = own, time = o.getLong("time"))
        },
    )

    /** Tageswerte des Umsatz-Anteils je Coin: {"BTC":[[20367,0.021],…],…}. */
    val turnoverHistory = CacheCodec<Map<String, List<TurnoverSample>>>(
        encode = { history ->
            val root = JSONObject()
            history.forEach { (symbol, samples) ->
                val array = JSONArray()
                samples.filter { it.value.isFinite() }.forEach { array.put(JSONArray().put(it.day).put(it.value)) }
                root.put(symbol, array)
            }
            root
        },
        decode = { root ->
            val out = HashMap<String, List<TurnoverSample>>()
            root.keys().forEach { symbol ->
                val array = root.optJSONArray(symbol) ?: return@forEach
                out[symbol] = (0 until array.length()).mapNotNull { i ->
                    val pair = array.optJSONArray(i) ?: return@mapNotNull null
                    val value = pair.optDouble(1)
                    if (pair.length() < 2 || !value.isFinite()) null else TurnoverSample(pair.optLong(0), value)
                }
            }
            out
        },
    )

    /** Termine der Wirtschaftsdaten: {"events":[{"type":"CPI","time":1760445000000},…]}. */
    val macroEvents = CacheCodec<List<MacroEvent>>(
        encode = { events ->
            val array = JSONArray()
            events.forEach { array.put(JSONObject().put("type", it.type.name).put("time", it.time)) }
            JSONObject().put("events", array)
        },
        decode = { root ->
            val array = root.getJSONArray("events")
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val type = MacroEventType.entries.firstOrNull { it.name == o.optString("type") } ?: return@mapNotNull null
                val time = o.optLong("time", 0L).takeIf { it > 0L } ?: return@mapNotNull null
                MacroEvent(type, time)
            }
        },
    )

    private fun JSONObject.putFinite(key: String, value: Double?): JSONObject =
        if (value != null && value.isFinite()) put(key, value) else this

    private fun JSONObject.finiteOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
}
