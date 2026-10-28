package com.cryptochecker.app.data

import com.cryptochecker.app.domain.market.AltSeason
import com.cryptochecker.app.domain.market.BtcFees
import com.cryptochecker.app.domain.market.CoinInputs
import com.cryptochecker.app.domain.market.CycleHistory
import com.cryptochecker.app.domain.market.CycleInputs
import com.cryptochecker.app.domain.market.CycleMarker
import com.cryptochecker.app.domain.market.CycleSeries
import com.cryptochecker.app.domain.market.Dominance
import com.cryptochecker.app.domain.market.EvmGas
import com.cryptochecker.app.domain.market.FearGreed
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.domain.market.GlobalMarket
import com.cryptochecker.app.domain.market.IndicatorHistory
import com.cryptochecker.app.domain.market.IndicatorId
import com.cryptochecker.app.domain.market.IndicatorStat
import com.cryptochecker.app.domain.market.MarketMoversResult
import com.cryptochecker.app.domain.market.MarketTotals
import com.cryptochecker.app.domain.market.MoverCoin
import com.cryptochecker.app.domain.market.OnChainValues
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Speicherformate der Markt-Bereiche ([CycleCacheCodecs], Hilfen [CycleCacheJson]): Hin und zurück über den Text der
 * Datei, fehlende oder nicht endliche Zahlen fallen weg, ältere Einträge ohne neue Felder bleiben lesbar, kaputte
 * Einträge werfen (der Zwischenspeicher gilt dann als leer).
 */
class CycleCacheCodecsTest {

    /** Wie im Zwischenspeicher: kodieren, als Text speichern, wieder lesen. */
    private fun <T> roundTrip(codec: CacheCodec<T>, value: T): T = codec.decode(JSONObject(codec.encode(value).toString()))

    private val day = LocalDate.of(2026, 10, 1)

    @Test
    fun moversDropEntriesWithoutChangeOrSymbol() {
        val movers = MarketMoversResult(
            gainers = listOf(MoverCoin("SOL", "Solana", 5, 7.1), MoverCoin("XYZ", "Ohne", null, null)),
            losers = listOf(MoverCoin("DOGE", "Dogecoin", null, -4.5), MoverCoin("NAN", "Kaputt", 9, Double.NaN)),
        )
        val json = CycleCacheCodecs.moversJson(movers)
        // Ohne Veränderung (fehlt oder NaN) gar nicht geschrieben; Rang nur, wenn bekannt
        assertEquals(1, json.getJSONArray("up").length())
        assertEquals(1, json.getJSONArray("down").length())
        assertFalse(json.getJSONArray("down").getJSONObject(0).has("r"))
        val back = CycleCacheCodecs.moversFrom(JSONObject(json.toString()))
        assertEquals(listOf(MoverCoin("SOL", "Solana", 5, 7.1)), back.gainers)
        assertEquals(listOf(MoverCoin("DOGE", "Dogecoin", null, -4.5)), back.losers)
    }

    @Test
    fun unreadableMoverEntriesAreSkipped() {
        val up = JSONArray()
            .put("kein Objekt")
            .put(JSONObject().put("s", " ").put("c", 1.0))
            .put(JSONObject().put("s", "ETH").put("n", "Ethereum"))
            .put(JSONObject().put("s", "BTC").put("n", "Bitcoin").put("r", 1).put("c", 2.5))
        val result = CycleCacheCodecs.moversFrom(JSONObject().put("up", up))
        assertEquals(listOf(MoverCoin("BTC", "Bitcoin", 1, 2.5)), result.gainers)
        // Fehlende Liste: leer statt Fehler
        assertTrue(result.losers.isEmpty())
    }

    @Test
    fun cycleInputsRoundTripWithHistory() {
        val history = IndicatorHistory(
            since = LocalDate.of(2012, 1, 1),
            stats = mapOf(IndicatorId.MAYER to IndicatorStat(42.0, 0.1), IndicatorId.MVRV to IndicatorStat(null, null)),
        )
        val input = CycleInputs(
            price = 65_000.5, sma200d = 60_000.0, sma111d = 62_000.0, sma350d = 30_000.0, price30dAgo = 58_000.0,
            sma200w = 40_000.0, ath = 73_000.0, athDate = LocalDate.of(2024, 3, 14), mvrv = 2.1, puell = 1.2,
            hash30d = 600.0, hash60d = 580.0, today = day, history = history,
        )
        val back = roundTrip(CycleCacheCodecs.cycleInputs, input)
        // «today» wird nicht gespeichert: das Modell rechnet mit dem aktuellen Datum
        assertEquals(input, back.copy(today = day))
    }

    @Test
    fun cycleInputsWithoutOptionalValues() {
        val input = CycleInputs(
            price = 100.0, sma200d = null, sma111d = Double.NaN, sma350d = null, price30dAgo = null, sma200w = null,
            ath = null, athDate = null, mvrv = null, puell = Double.POSITIVE_INFINITY, hash30d = null, hash60d = null,
            today = day,
        )
        val json = CycleCacheCodecs.cycleInputs.encode(input)
        assertFalse(json.has("sma111d"))
        assertFalse(json.has("puell"))
        assertFalse(json.has("athDate"))
        assertFalse(json.has("history"))
        val back = roundTrip(CycleCacheCodecs.cycleInputs, input).copy(today = day)
        assertEquals(input.copy(sma111d = null, puell = null), back)
    }

    @Test
    fun brokenHistoryIsDroppedButInputsStay() {
        val json = JSONObject().put("price", 1.0).put("history", JSONObject().put("since", "kein Datum"))
        assertNull(CycleCacheCodecs.cycleInputs.decode(json).history)
        // Unbekannte Kennzahl (neuere Version) und kaputter Eintrag fallen weg
        val stats = JSONObject().put("UNBEKANNT", JSONObject().put("p", 1.0)).put("MAYER", "kaputt")
            .put("MA200W", JSONObject().put("p", 12.5))
        val withStats = JSONObject().put("price", 1.0)
            .put("history", JSONObject().put("since", "2015-06-01").put("stats", stats))
        val history = CycleCacheCodecs.cycleInputs.decode(withStats).history
        assertEquals(LocalDate.of(2015, 6, 1), history!!.since)
        assertEquals(mapOf(IndicatorId.MA200W to IndicatorStat(12.5, null)), history.stats)
    }

    @Test
    fun cycleInputsNeedAFinitePrice() {
        assertThrows(Exception::class.java) { CycleCacheCodecs.cycleInputs.decode(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) {
            CycleCacheCodecs.cycleInputs.decode(JSONObject().put("price", "NaN"))
        }
    }

    @Test
    fun onChainRoundTripAndEmpty() {
        val values = OnChainValues(mvrv = 2.0, puell = null, hash30d = 610.5, hash60d = 600.0)
        assertEquals(values, roundTrip(CycleCacheCodecs.onChain, values))
        val empty = OnChainValues(null, null, null, null)
        assertEquals(0, CycleCacheCodecs.onChain.encode(empty).length())
        assertEquals(empty, roundTrip(CycleCacheCodecs.onChain, empty))
    }

    @Test
    fun fearGreedRoundTripWithoutTime() {
        val value = FearGreed(value = 23, yesterday = 30, weekAgo = null, monthAgo = 70)
        assertEquals(value, roundTrip(CycleCacheCodecs.fearGreed, value))
        // Zeitpunkt wird nicht gespeichert
        assertNull(roundTrip(CycleCacheCodecs.fearGreed, value.copy(time = 5L)).time)
        assertThrows(Exception::class.java) { CycleCacheCodecs.fearGreed.decode(JSONObject().put("yesterday", 1)) }
    }

    @Test
    fun globalRoundTripDropsNonFiniteTotals() {
        val totals = MarketTotals(
            marketCap = mapOf("usd" to 2.4e12, "chf" to 2.1e12, "xxx" to Double.NaN),
            volume = mapOf("usd" to 9.0e10),
            changePercent24h = -1.5,
        )
        val value = GlobalMarket(Dominance(btc = 57.3, eth = 12.1), totals)
        val back = roundTrip(CycleCacheCodecs.global, value)
        assertEquals(Dominance(57.3, 12.1), back.dominance)
        assertEquals(mapOf("usd" to 2.4e12, "chf" to 2.1e12), back.totals!!.marketCap)
        assertEquals(mapOf("usd" to 9.0e10), back.totals!!.volume)
        assertEquals(-1.5, back.totals!!.changePercent24h!!, 0.0)
    }

    @Test
    fun globalWithoutUsableTotals() {
        val noTotals = GlobalMarket(Dominance(btc = 50.0, eth = null), null)
        assertEquals(noTotals, roundTrip(CycleCacheCodecs.global, noTotals))
        // Leere Werte: Summen gelten als fehlend
        val emptyTotals = GlobalMarket(Dominance(50.0, null), MarketTotals(emptyMap(), mapOf("usd" to 1.0), null))
        assertNull(roundTrip(CycleCacheCodecs.global, emptyTotals).totals)
    }

    @Test
    fun altSeasonRoundTripAndPlausibility() {
        val value = AltSeason(outperformers = 30, total = 50)
        assertEquals(value, roundTrip(CycleCacheCodecs.altSeason, value))
        assertEquals(AltSeason(0, 0), roundTrip(CycleCacheCodecs.altSeason, AltSeason(0, 0)))
        assertThrows(IllegalArgumentException::class.java) {
            CycleCacheCodecs.altSeason.decode(JSONObject().put("outperformers", 51).put("total", 50))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CycleCacheCodecs.altSeason.decode(JSONObject().put("outperformers", -1).put("total", 50))
        }
    }

    @Test
    fun historyRoundTripWithMarkers() {
        val top = CycleMarker(day = 520, date = LocalDate.of(2021, 11, 10), priceUsd = 69_000.0, multiple = 7.9, change = 690.0)
        val bottom = CycleMarker(day = 890, date = LocalDate.of(2022, 11, 21), priceUsd = 15_500.0, multiple = 1.8, change = -77.5)
        val series = CycleSeries(
            halving = LocalDate.of(2020, 5, 11),
            points = listOf(0 to 1.0, 100 to 1.5, 200 to 0.75),
            top = top,
            bottom = bottom,
        )
        val history = CycleHistory(listOf(series, CycleSeries(LocalDate.of(2024, 4, 20), emptyList())))
        assertEquals(history, roundTrip(CycleCacheCodecs.history, history))
    }

    @Test
    fun historySkipsNonFinitePoints() {
        val series = CycleSeries(LocalDate.of(2016, 7, 9), listOf(0 to 1.0, 1 to Double.NaN, 2 to 2.0))
        val back = roundTrip(CycleCacheCodecs.history, CycleHistory(listOf(series)))
        assertEquals(listOf(0 to 1.0, 2 to 2.0), back.series.single().points)
        assertNull(back.series.single().secondTop)
        assertNull(back.series.single().secondBottom)
    }

    @Test
    fun coinInputsRoundTrip() {
        val value = CoinInputs(
            price = 150.25, sma50d = 140.0, sma200d = 120.0, sma111d = null, sma350d = 90.0, price30dAgo = 130.0,
            sma200w = null, ath = 260.0, athDate = LocalDate.of(2021, 11, 6), rsiDaily = 55.5, rsiWeekly = 61.0,
            vsBtc90d = -3.25, historyDays = 1500,
        )
        assertEquals(value, roundTrip(CycleCacheCodecs.coinInputs, value))
        val minimal = CoinInputs(1.0, null, null, null, null, null, null, null, null, null, null, null, historyDays = 0)
        assertEquals(minimal, roundTrip(CycleCacheCodecs.coinInputs, minimal))
    }

    @Test
    fun gasRoundTripWithEvmAndBtc() {
        val report = GasReport(
            evm = listOf(
                EvmGas(GasNetwork.ETHEREUM, slowGwei = 0.8, normalGwei = 1.2, fastGwei = 2.5, transferUsd = 0.12),
                EvmGas(GasNetwork.BASE, slowGwei = 0.01, normalGwei = 0.02, fastGwei = 0.05, transferUsd = null),
            ),
            btc = BtcFees(fast = 12.0, normal = 8.0, slow = 3.0, transferUsd = 1.75),
            time = 1_792_800_000_000L,
        )
        assertEquals(report, roundTrip(CycleCacheCodecs.gas, report))
        val btcOnly = GasReport(emptyList(), BtcFees(5.0, 4.0, 1.0, null), 7L)
        assertEquals(btcOnly, roundTrip(CycleCacheCodecs.gas, btcOnly))
    }

    @Test
    fun gasSkipsUnknownNetworksAndNeedsValues() {
        val evm = JSONArray()
            .put(JSONObject().put("network", "MOON").put("slow", 1.0).put("normal", 1.0).put("fast", 1.0))
            .put(JSONObject().put("network", "POLYGON").put("slow", 30.0).put("normal", 35.0).put("fast", 50.0))
        val report = CycleCacheCodecs.gas.decode(JSONObject().put("evm", evm).put("time", 1L))
        assertEquals(listOf(EvmGas(GasNetwork.POLYGON, 30.0, 35.0, 50.0, null)), report.evm)
        assertNull(report.btc)
        // Nur unbekannte Netze und kein Bitcoin: unbrauchbar
        val onlyUnknown = JSONArray().put(JSONObject().put("network", "MOON").put("slow", 1.0).put("normal", 1.0).put("fast", 1.0))
        assertThrows(IllegalArgumentException::class.java) {
            CycleCacheCodecs.gas.decode(JSONObject().put("evm", onlyUnknown).put("time", 1L))
        }
    }

    @Test
    fun jsonHelpersSkipMissingAndNonFiniteValues() {
        with(CycleCacheJson) {
            val o = JSONObject()
                .putNumber("a", 1.5)
                .putNumber("nan", Double.NaN)
                .putNumber("none", null as Double?)
                .putNumber("i", 3)
                .putNumber("noInt", null as Int?)
                .putDate("d", LocalDate.of(2026, 1, 2))
                .putDate("noDate", null)
                .putMarker("noMarker", null)
                .put("null", JSONObject.NULL)
            assertEquals(setOf("a", "i", "d", "null"), o.keys().asSequence().toSet())
            assertEquals(1.5, o.doubleOrNull("a")!!, 0.0)
            assertNull(o.doubleOrNull("null"))
            assertNull(o.doubleOrNull("fehlt"))
            assertEquals(3, o.intOrNull("i"))
            assertNull(o.intOrNull("null"))
            assertEquals(LocalDate.of(2026, 1, 2), o.dateOrNull("d"))
            assertNull(o.dateOrNull("null"))
            assertNull(o.markerOrNull("fehlt"))
            val map = mapOf("x" to 1.0, "y" to Double.NEGATIVE_INFINITY).toJson()
            assertEquals(mapOf("x" to 1.0), map.toDoubleMap())
        }
    }
}
