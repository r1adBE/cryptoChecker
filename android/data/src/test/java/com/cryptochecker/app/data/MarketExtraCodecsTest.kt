package com.cryptochecker.app.data

import com.cryptochecker.app.domain.macro.MacroEvent
import com.cryptochecker.app.domain.macro.MacroEventType
import com.cryptochecker.app.domain.market.TurnoverSample
import com.cryptochecker.app.domain.market.UnusualCoin
import com.cryptochecker.app.domain.market.UnusualInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Speicherformate für «Heute auffällig», Umsatz-Anteile und den Kalender der Wirtschaftsdaten ([MarketExtraCodecs]):
 * Hin und zurück über den Text der Datei, nicht endliche Zahlen fallen weg, unlesbare Einträge werden übersprungen.
 */
class MarketExtraCodecsTest {

    private fun <T> roundTrip(codec: CacheCodec<T>, value: T): T = codec.decode(JSONObject(codec.encode(value).toString()))

    @Test
    fun unusualInputRoundTrip() {
        val input = UnusualInput(
            coins = listOf(
                UnusualCoin("BTC", "Bitcoin", 2.5, quoteVolume = 1.2e9, marketCap = 1.3e12, fundingPercent = 0.01),
                UnusualCoin("PEPE", "Pepe", -12.75, quoteVolume = null, marketCap = null, fundingPercent = null),
            ),
            ownTypical = mapOf("BTC" to 0.021, "PEPE" to 0.004),
            time = 1_792_800_000_000L,
        )
        assertEquals(input, roundTrip(MarketExtraCodecs.unusualInput, input))
    }

    @Test
    fun unusualInputDropsNonFiniteValues() {
        val input = UnusualInput(
            coins = listOf(UnusualCoin("ETH", "Ethereum", 1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NaN)),
            ownTypical = mapOf("ETH" to Double.NaN, "SOL" to 0.5),
            time = 5L,
        )
        val json = MarketExtraCodecs.unusualInput.encode(input)
        val coin = json.getJSONArray("coins").getJSONObject(0)
        assertFalse(coin.has("v"))
        assertFalse(coin.has("m"))
        assertFalse(coin.has("f"))
        val back = roundTrip(MarketExtraCodecs.unusualInput, input)
        assertEquals(UnusualCoin("ETH", "Ethereum", 1.0, null, null, null), back.coins.single())
        assertEquals(mapOf("SOL" to 0.5), back.ownTypical)
    }

    @Test
    fun unusualInputWithoutTimeCoinsOwnOrNameIsBroken() {
        assertThrows(Exception::class.java) {
            MarketExtraCodecs.unusualInput.decode(JSONObject().put("coins", JSONArray()).put("own", JSONObject()))
        }
        assertThrows(Exception::class.java) {
            MarketExtraCodecs.unusualInput.decode(JSONObject().put("time", 1L).put("own", JSONObject()))
        }
        assertThrows(Exception::class.java) {
            MarketExtraCodecs.unusualInput.decode(JSONObject().put("coins", JSONArray()).put("time", 1L))
        }
        val nameless = JSONArray().put(JSONObject().put("s", "ARB").put("c", -3.0))
        assertThrows(Exception::class.java) {
            MarketExtraCodecs.unusualInput.decode(JSONObject().put("coins", nameless).put("own", JSONObject()).put("time", 1L))
        }
    }

    @Test
    fun turnoverHistoryRoundTrip() {
        val history = mapOf(
            "BTC" to listOf(TurnoverSample(20367L, 0.021), TurnoverSample(20368L, 0.019)),
            "ETH" to emptyList(),
        )
        assertEquals(history, roundTrip(MarketExtraCodecs.turnoverHistory, history))
    }

    @Test
    fun turnoverHistorySkipsBrokenSamples() {
        val history = mapOf("SOL" to listOf(TurnoverSample(1L, Double.NaN), TurnoverSample(2L, 0.5)))
        assertEquals(1, MarketExtraCodecs.turnoverHistory.encode(history).getJSONArray("SOL").length())
        val root = JSONObject()
            .put(
                "BTC",
                JSONArray()
                    .put(JSONArray().put(10L))
                    .put("kein Paar")
                    .put(JSONArray().put(11L).put("x"))
                    .put(JSONArray().put(12L).put(0.3)),
            )
            .put("ETH", "keine Liste")
        val decoded = MarketExtraCodecs.turnoverHistory.decode(root)
        assertEquals(mapOf("BTC" to listOf(TurnoverSample(12L, 0.3))), decoded)
    }

    @Test
    fun macroEventsRoundTrip() {
        val events = listOf(MacroEvent(MacroEventType.CPI, 1_760_445_000_000L), MacroEvent(MacroEventType.NFP, 1_761_000_000_000L))
        val json = MarketExtraCodecs.macroEvents.encode(events)
        assertEquals("CPI", json.getJSONArray("events").getJSONObject(0).getString("type"))
        assertEquals(events, roundTrip(MarketExtraCodecs.macroEvents, events))
        assertEquals(emptyList<MacroEvent>(), roundTrip(MarketExtraCodecs.macroEvents, emptyList()))
    }

    @Test
    fun macroEventsSkipUnknownTypesAndMissingTimes() {
        val array = JSONArray()
            .put(JSONObject().put("type", "UNBEKANNT").put("time", 5L))
            .put(JSONObject().put("type", "PPI"))
            .put(JSONObject().put("type", "PPI").put("time", 0L))
            .put("kein Objekt")
            .put(JSONObject().put("type", "PPI").put("time", 42L))
        val decoded = MarketExtraCodecs.macroEvents.decode(JSONObject().put("events", array))
        assertEquals(listOf(MacroEvent(MacroEventType.PPI, 42L)), decoded)
        assertThrows(Exception::class.java) { MarketExtraCodecs.macroEvents.decode(JSONObject()) }
    }

    @Test
    fun storedNameAndMarketCapAreRead() {
        val coins = JSONArray().put(JSONObject().put("s", "OP").put("n", "Optimism").put("c", 0.0).put("m", 5.0e9))
        val decoded = MarketExtraCodecs.unusualInput.decode(JSONObject().put("coins", coins).put("own", JSONObject()).put("time", 1L))
        val coin = decoded.coins.single()
        assertEquals("Optimism", coin.name)
        assertEquals(5.0e9, coin.marketCap!!, 0.0)
        assertNull(coin.fundingPercent)
    }
}
