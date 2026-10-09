package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAnalyzerTest {

    private val h = ActivityAnalyzer.HOUR_MILLIS
    private val t0 = 1_700_000_000_000L - 1_700_000_000_000L % ActivityAnalyzer.HOUR_MILLIS

    /** Kerzen aus Schlusskursen; Eröffnung = voriger Schluss, letzte Kerze = laufende Stunde. */
    private fun candles(closes: List<Double>, volumes: List<Double> = closes.map { 100.0 }): List<HourCandle> =
        closes.indices.map { i ->
            val open = if (i == 0) closes[0] else closes[i - 1]
            HourCandle(
                openTime = t0 + i * h,
                open = open,
                high = maxOf(open, closes[i]),
                low = minOf(open, closes[i]),
                close = closes[i],
                volume = volumes[i],
            )
        }

    /** Schlusskurse aus Renditen in %: 24 Vergleichsstunden, letzte abgeschlossene, laufende. */
    private fun fromReturns(returns: List<Double>, start: Double = 100.0): List<Double> {
        val closes = arrayListOf(start)
        returns.forEach { closes += closes.last() * (1 + it / 100.0) }
        return closes
    }

    private fun window(step: Double) = List(24) { if (it % 2 == 0) step else -step }

    /** 27 flache Kerzen bei 100, laufende Stunde schliesst bei [last]. */
    private fun flat(last: Double, lastCompletedVolume: Double = 100.0): List<HourCandle> {
        val closes = List(26) { 100.0 } + last
        val volumes = List(27) { if (it == 25) lastCompletedVolume else 100.0 }
        return candles(closes, volumes)
    }

    /** Mitten in der laufenden (27.) Stunde. */
    private val now = t0 + 26 * ActivityAnalyzer.HOUR_MILLIS + 30 * 60_000L

    // ---------------- Kennzahlen ----------------

    @Test
    fun hourStats_needsEnoughCandles() {
        assertNull(ActivityAnalyzer.hourStats(candles(fromReturns(window(0.5) + 1.0))))
        assertNotNull(ActivityAnalyzer.hourStats(candles(fromReturns(window(0.5) + 1.0 + 0.0))))
    }

    @Test
    fun hourStats_zScoreAndVolume() {
        val closes = fromReturns(window(0.5) + 5.0 + 0.0)
        val volumes = List(closes.size) { if (it == closes.size - 2) 400.0 else 100.0 }
        val stats = ActivityAnalyzer.hourStats(candles(closes, volumes))!!
        assertEquals(5.0, stats.movePercent, 1e-9)
        // Stichproben-Standardabweichung von ±0.5: sqrt(24 * 0.25 / 23) ≈ 0.5108
        assertEquals(5.0 / 0.51075, stats.zScore, 0.01)
        assertEquals(4.0, stats.volumeRatio!!, 1e-9)
        assertEquals(t0 + 25 * h, stats.candleOpenTime)
    }

    @Test
    fun priceAt_interpolatesInsideCandle() {
        val c = candles(listOf(100.0, 110.0, 120.0))
        assertEquals(105.0, ActivityAnalyzer.priceAt(c, t0 + h + h / 2)!!, 1e-9)
        assertEquals(120.0, ActivityAnalyzer.priceAt(c, t0 + 2 * h + 10)!!, 1e-9)
        assertNull(ActivityAnalyzer.priceAt(c, t0 - 1))
    }

    // ---------------- Signale ----------------

    @Test
    fun priceMove_strongAboveFivePercent() {
        val stats = ActivityAnalyzer.hourStats(candles(fromReturns(window(0.5) + 5.0 + 0.0)))
        val signals = ActivityAnalyzer.signals(stats, null, null, null, now)
        assertEquals(SignalKind.PRICE_MOVE, signals.single().kind)
        assertEquals(SignalSeverity.STRONG, signals.single().severity)
    }

    @Test
    fun priceMove_notableAboveThreshold() {
        // σ ≈ 0.77 %, 3 % ≈ 3.9 σ: auffällig, aber nicht stark
        val stats = ActivityAnalyzer.hourStats(candles(fromReturns(window(0.75) + 3.0 + 0.0)))
        val signal = ActivityAnalyzer.signals(stats, null, null, null, now).single()
        assertEquals(SignalSeverity.NOTABLE, signal.severity)
        assertEquals(3.0, signal.value, 1e-9)
    }

    @Test
    fun priceMove_ignoresMovesBelowTwoAndAHalfPercent() {
        val stats = ActivityAnalyzer.hourStats(candles(fromReturns(window(0.3) + 2.0 + 0.0)))
        assertTrue(ActivityAnalyzer.signals(stats, null, null, null, now).isEmpty())
    }

    @Test
    fun priceMove_ignoresSmallMovesOfCalmCoins() {
        // Stablecoin: winzige Schwankung, 0.5 % sind zwar «50 σ», aber unter 2.5 %
        val stats = ActivityAnalyzer.hourStats(candles(fromReturns(window(0.01) + 0.5 + 0.0)))
        assertTrue(ActivityAnalyzer.signals(stats, null, null, null, now).isEmpty())
    }

    @Test
    fun volumeSpike_thresholds() {
        val closes = List(27) { 100.0 }
        fun statsWith(lastVolume: Double) = ActivityAnalyzer.hourStats(
            candles(closes, List(27) { if (it == 25) lastVolume else 100.0 })
        )
        assertTrue(ActivityAnalyzer.signals(statsWith(490.0), null, null, null, now).isEmpty())
        assertEquals(
            SignalSeverity.NOTABLE,
            ActivityAnalyzer.signals(statsWith(500.0), null, null, null, now).single().severity
        )
        assertEquals(
            SignalSeverity.STRONG,
            ActivityAnalyzer.signals(statsWith(1000.0), null, null, null, now).single().severity
        )
    }

    @Test
    fun funding_extremeBothSides() {
        assertTrue(ActivityAnalyzer.signals(null, 0.09, null, null, now).isEmpty())
        assertEquals(SignalKind.FUNDING_EXTREME, ActivityAnalyzer.signals(null, 0.1, null, null, now).single().kind)
        assertEquals(SignalSeverity.STRONG, ActivityAnalyzer.signals(null, -0.22, null, null, now).single().severity)
    }

    @Test
    fun openInterest_comparesOnlyAgainstSamplesThirtyMinutesToSixHoursOld() {
        assertEquals(OiUpdate(null, null, store = true), ActivityAnalyzer.oiChange(null, 1000.0, now))
        assertEquals(
            OiUpdate(null, null, store = false),
            ActivityAnalyzer.oiChange(OiSample(1000.0, now - 20 * 60_000L), 1200.0, now)
        )
        val fresh = ActivityAnalyzer.oiChange(OiSample(1000.0, now - 45 * 60_000L), 1120.0, now)
        assertEquals(12.0, fresh.changePercent!!, 1e-9)
        assertEquals(45, fresh.minutes)
        assertTrue(fresh.store)
        assertEquals(
            OiUpdate(null, null, store = true),
            ActivityAnalyzer.oiChange(OiSample(1000.0, now - 7 * h), 1200.0, now)
        )
        assertEquals(OiUpdate(null, null, store = false), ActivityAnalyzer.oiChange(null, null, now))
    }

    @Test
    fun openInterest_jumpSignal() {
        assertTrue(ActivityAnalyzer.signals(null, null, 14.0, 40, now).isEmpty())
        val signal = ActivityAnalyzer.signals(null, null, -16.0, 40, now).single()
        assertEquals(SignalKind.OPEN_INTEREST_JUMP, signal.kind)
        assertEquals(40.0, signal.factor!!, 1e-9)
    }

    @Test
    fun signals_strongFirst() {
        val stats = HourStats(movePercent = 3.0, zScore = 4.0, volumeRatio = 11.0, candleOpenTime = 0)
        val kinds = ActivityAnalyzer.signals(stats, 0.12, null, null, now).map { it.kind }
        assertEquals(listOf(SignalKind.VOLUME_SPIKE, SignalKind.PRICE_MOVE, SignalKind.FUNDING_EXTREME), kinds)
    }

    // ---------------- Merken, Ablauf, Meldung ----------------

    @Test
    fun merge_keepsSignalsForOneHourAndReportsNewKinds() {
        // Über der Schwelle (5×): alte Signale werden beim Zusammenführen nach der Empfindlichkeit neu beurteilt
        val volume = ActivitySignal(SignalKind.VOLUME_SPIKE, SignalSeverity.NOTABLE, 6.0, seenAt = now)
        val first = ActivityAnalyzer.merge(null, listOf(volume), now)
        assertEquals(setOf(SignalKind.VOLUME_SPIKE), first.newKinds)

        // 30 Min. später nicht mehr erkannt: bleibt stehen, nichts Neues
        val later = now + 30 * 60_000L
        val second = ActivityAnalyzer.merge(first.report, emptyList(), later)
        assertEquals(1, second.report.active(later).size)
        assertTrue(second.newKinds.isEmpty())

        // Gleiche Art erneut erkannt: nicht neu
        val again = ActivityAnalyzer.merge(first.report, listOf(volume), later)
        assertTrue(again.newKinds.isEmpty())

        // Nach über 1 h abgelaufen
        val expired = now + h + 60_000L
        assertTrue(second.report.active(expired).isEmpty())
        assertEquals(setOf(SignalKind.VOLUME_SPIKE), ActivityAnalyzer.merge(second.report, listOf(volume), expired).newKinds)
    }

    @Test
    fun notify_atMostHourly() {
        val kinds = setOf(SignalKind.PRICE_MOVE)
        assertFalse(ActivityAnalyzer.shouldNotify(false, kinds, 0, now))
        assertFalse(ActivityAnalyzer.shouldNotify(true, emptySet(), 0, now))
        assertTrue(ActivityAnalyzer.shouldNotify(true, kinds, 0, now))
        assertFalse(ActivityAnalyzer.shouldNotify(true, kinds, now - 59 * 60_000L, now))
        assertTrue(ActivityAnalyzer.shouldNotify(true, kinds, now - 60 * 60_000L, now))
    }

    @Test
    fun isDue_everyTenMinutes() {
        assertTrue(ActivityAnalyzer.isDue(null, now))
        assertFalse(ActivityAnalyzer.isDue(ActivityReport(emptyList(), now - 9 * 60_000L), now))
        assertTrue(ActivityAnalyzer.isDue(ActivityReport(emptyList(), now - 10 * 60_000L), now))
    }

    // ---------------- Gründe ----------------

    private fun input(
        base: String = "SOL",
        candles: List<HourCandle>? = flat(105.0),
        reference: List<HourCandle>? = flat(100.3),
        funding: Double? = null,
        oi: Double? = null,
        fng: Int? = null,
        fngYesterday: Int? = null,
    ) = WhyInput(base, candles, reference, funding, oi, fng, fngYesterday, now)

    @Test
    fun explain_onlyThisCoinMoves() {
        val report = ActivityAnalyzer.explain(input())
        assertEquals(5.0, report.change24h!!, 1e-9)
        assertEquals(5.0, report.change1h!!, 1e-9)
        val market = report.reasons.first()
        assertEquals(ReasonKind.COIN_ONLY, market.kind)
        assertEquals(ReasonTone.UP, market.tone)
        assertEquals(0.3, market.secondary!!, 1e-6)
        assertTrue(report.hasMarketData)
    }

    @Test
    fun explain_wholeMarketMoves() {
        val report = ActivityAnalyzer.explain(input(candles = flat(96.0), reference = flat(96.5)))
        val market = report.reasons.first { it.kind == ReasonKind.MARKET_WIDE }
        assertEquals(-3.5, market.value, 1e-9)
        assertEquals(ReasonTone.DOWN, market.tone)
        assertTrue(market.strong)
    }

    @Test
    fun explain_againstTheMarket() {
        val report = ActivityAnalyzer.explain(input(candles = flat(105.0), reference = flat(97.0)))
        assertEquals(ReasonKind.AGAINST_MARKET, report.reasons.first().kind)
    }

    @Test
    fun explain_bitcoinIsMarketLeader() {
        val report = ActivityAnalyzer.explain(input(base = "BTC", candles = flat(102.0), reference = flat(103.0)))
        val leader = report.reasons.first { it.kind == ReasonKind.MARKET_LEADER }
        assertEquals(2.0, leader.value, 1e-9)
        assertEquals(3.0, leader.secondary!!, 1e-9)
    }

    @Test
    fun explain_thinVolumeWhileMoving() {
        val report = ActivityAnalyzer.explain(input(candles = flat(105.0, lastCompletedVolume = 50.0)))
        assertTrue(report.reasons.any { it.kind == ReasonKind.VOLUME_LOW })
    }

    @Test
    fun explain_leverageAndOrder() {
        // Ruhiger Markt (nicht auffällig) + viele Longs (auffällig) → Hebel zuerst
        val report = ActivityAnalyzer.explain(
            input(candles = flat(100.5), reference = flat(100.2), funding = 0.08, oi = 15.0)
        )
        val first = report.reasons.first()
        assertEquals(ReasonKind.LEVERAGE_LONGS, first.kind)
        assertEquals(15.0, first.secondary!!, 1e-9)
        assertTrue(first.strong)
        assertTrue(report.reasons.any { it.kind == ReasonKind.MARKET_CALM })
        assertTrue(report.reasons.size <= ActivityAnalyzer.MAX_REASONS)
    }

    @Test
    fun explain_withoutBinanceData() {
        val report = ActivityAnalyzer.explain(input(candles = null, fng = 20, fngYesterday = 28))
        assertFalse(report.hasMarketData)
        assertNull(report.change24h)
        val sentiment = report.reasons.single()
        assertEquals(ReasonKind.SENTIMENT, sentiment.kind)
        assertEquals(-8.0, sentiment.secondary!!, 1e-9)
        assertEquals(ReasonTone.WARNING, sentiment.tone)
        assertEquals(FearGreedLevel.EXTREME_FEAR, ActivityAnalyzer.fearGreedLevel(20))
    }
}
