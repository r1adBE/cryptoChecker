package com.cryptochecker.app.domain.market

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schlagzeile des Pulses und gemeinsame Skala: Fälle `market_move_scale.json` (iOS: MarketMoveScaleTests). */
class MarketMoveScaleTest {

    private val fixture = ParityJson.load("market_move_scale.json")

    private fun changes(up: Int, down: Int, total: Int): List<Double> =
        List(up) { 1.0 } + List(down) { -1.0 } + List(total - up - down) { 0.0 }

    private fun makeReport(
        btc: Double, eth: Double, sol: Double, market: Double?, top: List<Double>, ratio: Double?,
    ) =
        CryptoPulse.evaluate(
            PulseInput(
                btc24h = btc, eth24h = eth, sol24h = sol, btcVolumeRatio = ratio, fearGreed = null,
                fundingPercent = null, ethGasGwei = null, time = 1L, topChanges = top,
                marketCapUsd = market?.let { 3.0e12 }, marketCap24h = market,
            )
        )!!

    @Test
    fun sharedCases() {
        val cases = fixture["cases"].list()
        assertTrue(cases.size >= 20)
        cases.forEach { raw ->
            val c = raw.obj()
            val name = c["name"] as String
            val input = c["input"].obj()
            val expected = c["expected"].obj()
            val btc = input["btc24h"].num()!!
            val eth = input["eth24h"].num()!!
            val sol = input["sol24h"].num()!!
            val market = input["marketChange24h"].num()
            val breadthSpec = input["breadth"]?.obj()
            val breadth = breadthSpec?.let {
                PulseBreadth(it["up"].num()!!.toInt(), it["down"].num()!!.toInt(), it["total"].num()!!.toInt())
            }

            val move = MarketMoveScale.decide(btc, eth, sol, market, breadth)
            assertEquals("direction: $name", expected["direction"], move.direction.name)
            assertEquals("level: $name", expected["level"], move.level?.name)

            val top = breadth?.let { changes(it.up, it.down, it.total) }.orEmpty()
            val report = makeReport(btc, eth, sol, market, top, input["btcVolumeRatio"].num())
            assertEquals("breadth: $name", breadth, report.breadth)
            assertEquals("summary: $name", expected["summary"], report.summary.name)
            assertEquals("lead: $name", expected["lead"], CryptoPulse.leadSentence(report).kind.name)
            val plain = market?.let { MarketPlainSummary.directionKind(it).name }
            assertEquals("plain: $name", expected["plainDirection"], plain)
        }
    }

    /**
     * Raster: gleichgerichtete Grosswerte → deren Richtung, ausser Gesamtmarkt (ab 1 %) oder Breite
     * stehen dagegen, dann «gemischt» (ruhige Fälle ausgenommen); nie «leicht» neben «deutlich».
     */
    @Test
    fun headlineNeverContradictsTheNumbers() {
        val steps = listOf(-4.0, -3.0, -2.0, -1.2, -0.5, 0.0, 0.5, 1.2, 2.0, 3.0, 4.0)
        val markets = listOf(null, -3.5, -3.0, -1.5, -1.0, -0.5, 0.5, 1.0, 1.5, 3.0, 3.5)
        // Breite: 26 von 30 fallend (−1) bzw. steigend (+1)
        val breadths = listOf(-1 to changes(4, 26, 30), 1 to changes(26, 4, 30))
        for (btc in steps) for (eth in steps) for (sol in steps) for (market in markets) for ((lean, top) in breadths) {
            val big = listOf(btc, eth, sol)
            val r = makeReport(btc, eth, sol, market, top, null)
            val lead = CryptoPulse.leadSentence(r).kind
            val label = "btc=$btc eth=$eth sol=$sol market=$market lean=$lean"
            val bigSign = when {
                big.all { it > MarketMoveScale.NOISE_PERCENT } -> 1
                big.all { it < -MarketMoveScale.NOISE_PERCENT } -> -1
                else -> 0
            }
            if (bigSign != 0 && r.summary != PulseSummary.CALM) {
                val marketAgainst = market != null && market * bigSign <= -MarketMoveScale.FLAT_BELOW_PERCENT
                val expected = when {
                    marketAgainst || lean == -bigSign -> PulseSummary.MIXED
                    bigSign > 0 -> PulseSummary.BROAD_UP
                    else -> PulseSummary.BROAD_DOWN
                }
                assertEquals(label, expected, r.summary)
            }
            val plain = market?.let { MarketPlainSummary.directionKind(it) }
            if (plain == PlainDirectionKind.DOWN_CLEAR) assertNotEquals(label, PulseLeadKind.DRIFT_DOWN, lead)
            if (plain == PlainDirectionKind.UP_CLEAR) assertNotEquals(label, PulseLeadKind.DRIFT_UP, lead)
            // Gesamtwert zeigt in eine Richtung (ab 1 %) → Schlagzeile nie in die Gegenrichtung
            if (market != null && market >= MarketMoveScale.FLAT_BELOW_PERCENT) {
                assertNotEquals(label, PulseSummary.BROAD_DOWN, r.summary)
            }
            if (market != null && market <= -MarketMoveScale.FLAT_BELOW_PERCENT) {
                assertNotEquals(label, PulseSummary.BROAD_UP, r.summary)
            }
        }
    }

    @Test
    fun levelThresholds() {
        assertEquals(MarketMoveLevel.FLAT, MarketMoveScale.level(0.99))
        assertEquals(MarketMoveLevel.FLAT, MarketMoveScale.level(-0.99))
        assertEquals(MarketMoveLevel.SLIGHT, MarketMoveScale.level(1.0))
        assertEquals(MarketMoveLevel.SLIGHT, MarketMoveScale.level(-2.99))
        assertEquals(MarketMoveLevel.CLEAR, MarketMoveScale.level(3.0))
        assertEquals(MarketMoveLevel.CLEAR, MarketMoveScale.level(-3.0))
    }
}
