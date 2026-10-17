package com.cryptochecker.app.domain.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * «Warum?»-Auswertung: relative Schwellen, zweite Markt-Referenz, Stablecoins, Lage (30 Tage /
 * 3 Tage), Volumen-Qualität, Open-Interest-Verlauf und Funding-Verlauf — Randfälle.
 */
class WhyAnalysisTest {

    private val h = ActivityAnalyzer.HOUR_MILLIS
    private val day = ActivityAnalyzer.DAY_MILLIS

    /** Laufende Stunde beginnt bei [runOpen]; «jetzt» ist 30 Min. später. */
    private val runOpen = 1_700_000_000_000L - 1_700_000_000_000L % day + 12 * h
    private val now = runOpen + 30 * 60_000L

    /**
     * [count] Stundenkerzen, die letzte = laufende Stunde. Renditen in %: [tail] am Ende, davor
     * [pattern] im Wechsel. Volumen [volume], die letzten Werte aus [volumeTail]; mit [quote]
     * zusätzlich Quote-Umsatz = Menge × Schluss.
     */
    private fun series(
        count: Int = 100,
        start: Double = 100.0,
        pattern: List<Double> = listOf(0.1, -0.1),
        tail: List<Double> = emptyList(),
        volume: Double = 100.0,
        volumeTail: List<Double> = emptyList(),
        quote: Boolean = false,
    ): List<HourCandle> {
        val returns = count - 1
        val r = (0 until returns).map { i ->
            val fromEnd = returns - i
            if (fromEnd <= tail.size) tail[tail.size - fromEnd] else pattern[i % pattern.size]
        }
        val closes = ArrayList<Double>(count)
        closes += start
        r.forEach { closes += closes.last() * (1 + it / 100.0) }
        return closes.indices.map { i ->
            val open = if (i == 0) closes[0] else closes[i - 1]
            val fromEnd = count - i
            val v = if (fromEnd <= volumeTail.size) volumeTail[volumeTail.size - fromEnd] else volume
            HourCandle(
                openTime = runOpen - (count - 1 - i) * h,
                open = open,
                high = maxOf(open, closes[i]),
                low = minOf(open, closes[i]),
                close = closes[i],
                volume = v,
                quoteVolume = if (quote) v * closes[i] else null,
            )
        }
    }

    /** 24-h-Bewegung von [total] % gleichmässig über die letzten 24 Stunden (plus laufende ohne Bewegung). */
    private fun move24(total: Double): List<Double> {
        val step = (Math.pow(1 + total / 100.0, 1.0 / 24) - 1) * 100.0
        return List(24) { step } + 0.0
    }

    private fun daily(closes: List<Double>, lows: List<Double>? = null, highs: List<Double>? = null): List<HourCandle> {
        val lastOpen = runOpen - runOpen % day
        return closes.indices.map { i ->
            HourCandle(
                openTime = lastOpen - (closes.size - 1 - i) * day,
                open = closes[i],
                high = highs?.get(i) ?: closes[i],
                low = lows?.get(i) ?: closes[i],
                close = closes[i],
                volume = 1.0,
            )
        }
    }

    private fun input(
        base: String = "SOL",
        quote: String = "USDT",
        candles: List<HourCandle>? = series(),
        reference: List<HourCandle>? = series(),
        eth: List<HourCandle>? = null,
        broad: BroadMarketSnapshot? = null,
        funding: Double? = null,
        oi: Double? = null,
        oiConsistent: Boolean = true,
        daily: List<HourCandle>? = null,
        history: FundingHistory? = null,
        fundingSource: String? = null,
    ) = WhyInput(
        baseAsset = base, candles = candles, referenceCandles = reference, fundingPercent = funding,
        openInterestChangePercent = oi, fearGreed = null, fearGreedYesterday = null, now = now,
        dailyCandles = daily, quoteAsset = quote, ethCandles = eth, broadMarket = broad,
        openInterestMinutes = oi?.let { 90 }, openInterestConsistent = oiConsistent,
        fundingHistory = history, fundingSource = fundingSource,
    )

    private fun market(report: WhyReport): Reason =
        report.reasons.first {
            it.kind in setOf(
                ReasonKind.MARKET_WIDE, ReasonKind.COIN_ONLY, ReasonKind.AGAINST_MARKET,
                ReasonKind.MARKET_CALM, ReasonKind.MARKET_LEADER,
            )
        }

    // ---------------- W1 relative Schwellen ----------------

    @Test
    fun relative_hourlySigma_usesOnlyHoursBeforeWindow() {
        // ±0,1 % davor, im Fenster grosse Bewegung: σ bleibt die von vorher
        val c = series(pattern = listOf(0.1, -0.1), tail = move24(20.0))
        val sigma = RelativeMove.sigmaBefore24h(c, now)!!
        assertEquals(0.1, sigma, 0.005)
    }

    @Test
    fun relative_tooLittleHistory_fallsBackToFixedThresholds() {
        val c = series(count = 40, tail = move24(3.5))
        assertNull(RelativeMove.sigmaBefore24h(c, now))
        val r = market(ActivityAnalyzer.explain(input(candles = c, reference = series(count = 40))))
        assertEquals(ReasonKind.COIN_ONLY, r.kind)
        assertEquals(ActivityAnalyzer.COIN_MOVE_PERCENT, r.threshold!!, 1e-12)
    }

    @Test
    fun relative_calmCoin_floorMakesSmallMoveNotable() {
        // σ ≈ 0,1 % → k·σ·√24 ≈ 0,98 % < Untergrenze 2 % → Schwelle 2 %; +2,5 % zählt (fest wären es 3 %)
        val r = market(ActivityAnalyzer.explain(input(candles = series(tail = move24(2.5)))))
        assertEquals(ReasonKind.COIN_ONLY, r.kind)
        assertEquals(RelativeMove.COIN_FLOOR_PERCENT, r.threshold!!, 1e-12)
        assertEquals(2.5, r.value, 0.1)
    }

    @Test
    fun relative_volatileCoin_largeMoveIsNoise() {
        // σ ≈ 2 % → Schwelle ≈ 2 · 2 · √24 ≈ 19,6 %; +10 % ist für diesen Coin üblich
        val r = market(ActivityAnalyzer.explain(input(candles = series(pattern = listOf(2.0, -2.0), tail = move24(10.0)))))
        assertEquals(ReasonKind.MARKET_CALM, r.kind)
        val sigma = RelativeMove.sigmaBefore24h(series(pattern = listOf(2.0, -2.0), tail = move24(10.0)), now)!!
        assertEquals(2.0 * sigma * sqrt(24.0), RelativeMove.coinThreshold(sigma), 1e-9)
    }

    @Test
    fun relative_flatSeries_isCalmWithoutPositionOrVolatility() {
        val flat = series(pattern = listOf(0.0))
        val report = ActivityAnalyzer.explain(input(candles = flat, reference = flat))
        assertTrue(report.hasMarketData)
        assertEquals(ReasonKind.MARKET_CALM, market(report).kind)
        assertEquals(ReasonKind.VOLATILITY_NORMAL, report.reasons.first { it.kind.name.startsWith("VOLATILITY") }.kind)
        assertNull(report.position3d)
        // Völlig flach: σ an der Untergrenze, Schwelle = Untergrenze
        assertEquals(RelativeMove.COIN_FLOOR_PERCENT, RelativeMove.coinThreshold(RelativeMove.sigmaBefore24h(flat, now)), 1e-12)
    }

    @Test
    fun volatility_needsMinimumHourlyMove() {
        // Sehr ruhig (±0,01 %), letzte Stunde 0,3 %: z riesig, aber unter 0,5 % → üblich
        val c = series(pattern = listOf(0.01, -0.01), tail = listOf(0.3, 0.0))
        val report = ActivityAnalyzer.explain(input(candles = c))
        val v = report.reasons.first { it.kind.name.startsWith("VOLATILITY") }
        assertEquals(ReasonKind.VOLATILITY_NORMAL, v.kind)
        // 0,6 % reicht
        val c2 = series(pattern = listOf(0.01, -0.01), tail = listOf(0.6, 0.0))
        assertTrue(ActivityAnalyzer.explain(input(candles = c2)).reasons.any { it.kind == ReasonKind.VOLATILITY_HIGH })
    }

    // ---------------- W2 zweite Markt-Referenz ----------------

    @Test
    fun market_missingReference_noMarketReason() {
        val report = ActivityAnalyzer.explain(input(candles = series(tail = move24(5.0)), reference = null))
        assertTrue(report.reasons.none { it.kind == ReasonKind.COIN_ONLY || it.kind == ReasonKind.MARKET_CALM })
    }

    @Test
    fun market_onlyBroadMarket_isEnough() {
        val report = ActivityAnalyzer.explain(
            input(candles = series(tail = move24(5.0)), reference = null, broad = BroadMarketSnapshot(4.0, 25, now))
        )
        val r = market(report)
        assertEquals(ReasonKind.MARKET_WIDE, r.kind)
        assertEquals(MarketRef.BROAD, r.ref)
    }

    @Test
    fun market_broadMarketMovesWhileBtcFlat_isNotCoinOnly() {
        val report = ActivityAnalyzer.explain(
            input(
                candles = series(tail = move24(5.0)),
                reference = series(tail = move24(0.2)),
                eth = series(tail = move24(0.3)),
                broad = BroadMarketSnapshot(4.0, 25, now),
            )
        )
        val r = market(report)
        assertEquals(ReasonKind.MARKET_WIDE, r.kind)
        assertEquals(MarketRef.BROAD, r.ref)
        assertEquals(4.0, r.value, 1e-9)
        assertTrue(r.strong)
        assertFalse(WhySummary.isSimilarOnly(r))
        val f = WhyFactors.of(report).first { it.kind == WhyFactorKind.MARKET }
        assertEquals(WhyFactorNote.MARKET_MOVES, f.note)
        assertEquals(MarketRef.BROAD, f.ref)
        // Ohne breiten Markt wäre es «nur dieser Coin»
        val without = ActivityAnalyzer.explain(
            input(candles = series(tail = move24(5.0)), reference = series(tail = move24(0.2)), eth = series(tail = move24(0.3)))
        )
        assertEquals(ReasonKind.COIN_ONLY, market(without).kind)
        assertEquals(MarketRef.BTC, market(without).ref)
    }

    @Test
    fun market_similarButNotNotable_isWeakMarketWide() {
        // ETH schwankt stark (Schwelle ≈ 7,5 %), +3 % ist dort unauffällig — aber ähnlich wie der Coin (+4 %)
        val report = ActivityAnalyzer.explain(
            input(
                candles = series(tail = move24(4.0)),
                reference = series(tail = move24(0.1)),
                eth = series(pattern = listOf(1.0, -1.0), tail = move24(3.0)),
            )
        )
        val r = market(report)
        assertEquals(ReasonKind.MARKET_WIDE, r.kind)
        assertEquals(MarketRef.ETH, r.ref)
        assertFalse(r.strong)
        assertTrue(WhySummary.isSimilarOnly(r))
        // Halb so stark ist die Grenze: ETH +1,9 % bei Coin +4 % → nur dieser Coin
        val alone = ActivityAnalyzer.explain(
            input(
                candles = series(tail = move24(4.0)),
                reference = series(tail = move24(0.1)),
                eth = series(pattern = listOf(1.0, -1.0), tail = move24(1.9)),
            )
        )
        assertEquals(ReasonKind.COIN_ONLY, market(alone).kind)
    }

    @Test
    fun market_coinVsBtc_bothDirections() {
        val up = market(ActivityAnalyzer.explain(input(candles = series(tail = move24(5.0)), reference = series(tail = move24(-2.0)))))
        assertEquals(ReasonKind.AGAINST_MARKET, up.kind)
        assertEquals(ReasonTone.UP, up.tone)
        assertEquals(MarketRef.BTC, up.ref)
        assertEquals(-2.0, up.secondary!!, 0.1)
        val down = market(ActivityAnalyzer.explain(input(candles = series(tail = move24(-5.0)), reference = series(tail = move24(2.0)))))
        assertEquals(ReasonKind.AGAINST_MARKET, down.kind)
        assertEquals(ReasonTone.DOWN, down.tone)
        // BTC fällt, aber die Altcoins steigen wie der Coin: nicht «gegen den Markt»
        val mixed = market(
            ActivityAnalyzer.explain(
                input(
                    candles = series(tail = move24(5.0)), reference = series(tail = move24(-2.0)),
                    broad = BroadMarketSnapshot(3.0, 20, now),
                )
            )
        )
        assertEquals(ReasonKind.MARKET_WIDE, mixed.kind)
        assertEquals(MarketRef.BROAD, mixed.ref)
    }

    @Test
    fun market_btcLeader_usesOwnThreshold() {
        // Bitcoin ruhig (σ ≈ 0,1 % → Schwelle 1 %): +1,2 % ist eine deutliche Bewegung
        val report = ActivityAnalyzer.explain(input(base = "BTC", candles = series(tail = move24(1.2)), reference = series()))
        val leader = market(report)
        assertEquals(ReasonKind.MARKET_LEADER, leader.kind)
        assertEquals(RelativeMove.MARKET_FLOOR_PERCENT, leader.threshold!!, 1e-12)
        assertTrue(WhySummary.leaderMoving(leader))
        assertEquals(WhyHeadline.MARKET, WhySummary.brief(report.reasons).headline)
    }

    // ---------------- W8 Stablecoins ----------------

    @Test
    fun stable_describesPegInsteadOfMoves() {
        val c = series(start = 0.9988, pattern = listOf(0.0))
        val report = ActivityAnalyzer.explain(input(base = "USDC", quote = "USDT", candles = c, daily = daily(List(31) { 0.9988 })))
        assertTrue(report.stable)
        val peg = report.reasons.first()
        assertEquals(ReasonKind.PEG, peg.kind)
        assertEquals(-0.12, peg.value, 1e-9)
        assertFalse(peg.strong)
        assertTrue(report.reasons.none { it.kind.name.startsWith("MARKET") || it.kind.name.startsWith("VOLATILITY") })
        assertNull(report.high30d)
        assertNull(report.low30d)
        assertNull(report.position3d)
        assertNull(report.btcLink)
        val f = WhyFactors.of(report).first { it.kind == WhyFactorKind.PEG }
        assertEquals(WhyFactorNote.PEG_BELOW, f.note)
        assertTrue(f.neutral)
        assertEquals(WhyHeadline.PEG_NEAR, WhySummary.brief(report.reasons).headline)
    }

    @Test
    fun stable_depegIsNotable() {
        val report = ActivityAnalyzer.explain(input(base = "USDT", quote = "USD", candles = series(start = 0.97, pattern = listOf(0.0))))
        val peg = report.reasons.first { it.kind == ReasonKind.PEG }
        assertTrue(peg.strong)
        assertEquals(WhyMark.CAUTION, WhySummary.mark(peg))
        assertEquals(WhyHeadline.PEG_OFF, WhySummary.brief(report.reasons).headline)
        val f = WhyFactors.of(report).first { it.kind == WhyFactorKind.PEG }
        assertFalse(f.neutral)
        assertEquals(WhyFactorDirection.DOWN, f.direction)
        // Nahe 1:1 (≤ 0,1 %)
        val near = ActivityAnalyzer.explain(input(base = "DAI", quote = "USDC", candles = series(start = 1.0005, pattern = listOf(0.0))))
        assertEquals(WhyFactorNote.PEG_NEAR, WhyFactors.of(near).first { it.kind == WhyFactorKind.PEG }.note)
    }

    @Test
    fun stable_onlyAgainstUsd() {
        assertTrue(StablePeg.isPegged("usdc", " usdt "))
        assertTrue(StablePeg.isPegged("USDT", "USD"))
        assertFalse(StablePeg.isPegged("USDT", "TRY"))
        assertFalse(StablePeg.isPegged("USD", "USDT"))
        assertFalse(StablePeg.isPegged("BTC", "USDT"))
        assertFalse(StablePeg.isPegged("USDC", null))
    }

    // ---------------- W5 / W6 Lage ----------------

    @Test
    fun low30d_nearLowSymmetricToHigh() {
        val closes = List(31) { 120.0 - it * 0.5 } // fällt bis 105
        val c = series(start = 100.0, pattern = listOf(0.0)) // Kurs 100
        val lows = closes.mapIndexed { i, v -> if (i == 30) 95.24 else v } // 100 ist 5,0 % über 95,24
        val report = ActivityAnalyzer.explain(input(candles = c, daily = daily(closes, lows = lows)))
        assertEquals(95.24, report.low30d!!, 1e-12)
        val f = WhyFactors.of(report).first { it.kind == WhyFactorKind.NEAR_LOW }
        assertEquals(WhyFactorNote.LOW_ABOVE, f.note)
        assertEquals(5.0, f.value, 0.01)
        assertFalse(f.neutral)
        // Genau auf dem Tief
        val at = ActivityAnalyzer.explain(input(candles = c, daily = daily(closes, lows = closes.mapIndexed { i, v -> if (i == 30) 100.0 else v })))
        assertEquals(WhyFactorNote.LOW_AT, WhyFactors.of(at).first { it.kind == WhyFactorKind.NEAR_LOW }.note)
        // Weit über dem Tief: keine Zeile
        val far = ActivityAnalyzer.explain(input(candles = c, daily = daily(closes, lows = closes.map { 80.0 })))
        assertTrue(WhyFactors.of(far).none { it.kind == WhyFactorKind.NEAR_LOW })
    }

    @Test
    fun low30d_exactBoundary() {
        assertEquals(5.0, WhyFactors.distanceToLowPercent(105.0, 100.0)!!, 1e-12)
        assertNull(WhyFactors.distanceToLowPercent(null, 100.0))
        assertNull(WhyFactors.distanceToLowPercent(100.0, 0.0))
        assertEquals(0.0, WhyFactors.distanceToLowPercent(99.0, 100.0)!!, 1e-12)
        assertNull(ActivityAnalyzer.low30d(daily(List(29) { 1.0 }), now))
        assertNull(ActivityAnalyzer.low30d(daily(List(31) { 1.0 }, lows = List(31) { if (it == 3) 0.0 else 1.0 }), now))
    }

    @Test
    fun position3d_belowHighAndAboveLow() {
        // Ruhig (σ ≈ 0,1 % → Schwelle 5 %), in den letzten 25 Stunden −10 %
        val down = series(tail = move24(-10.0))
        val p = ActivityAnalyzer.position3d(down, now)!!
        assertEquals(Position3dKind.BELOW_HIGH, p.kind)
        assertEquals(-10.0, p.percent, 0.3)
        assertEquals(ActivityAnalyzer.RANGE_FLOOR_PERCENT, p.threshold, 1e-12)
        val up = ActivityAnalyzer.position3d(series(tail = move24(8.0) + List(3) { -0.6 }), now)!!
        assertEquals(Position3dKind.ABOVE_LOW, up.kind)
        assertTrue(up.percent > 5.0)
        val factor = WhyFactors.of(ActivityAnalyzer.explain(input(candles = down))).first { it.kind == WhyFactorKind.POSITION_3D }
        assertEquals(WhyFactorNote.RANGE_BELOW_HIGH, factor.note)
        assertEquals(WhyFactorDirection.DOWN, factor.direction)
    }

    @Test
    fun position3d_nearHigh_andSuppressedBy30dHigh() {
        val c = series(tail = move24(4.0))
        val p = ActivityAnalyzer.position3d(c, now)!!
        assertEquals(Position3dKind.NEAR_HIGH, p.kind)
        // Nach einem Einbruch: der Abstand zum Hoch, nicht «nahe am Tief»
        assertEquals(Position3dKind.BELOW_HIGH, ActivityAnalyzer.position3d(series(tail = move24(-10.0)), now)?.kind)
        assertEquals(Position3dKind.NEAR_LOW, ActivityAnalyzer.position3d(series(tail = move24(-4.0)), now)?.kind)
        // Ohne 30-Tage-Daten: 3-Tage-Zeile
        assertTrue(WhyFactors.of(ActivityAnalyzer.explain(input(candles = c))).any { it.kind == WhyFactorKind.POSITION_3D })
        // Auch nahe am 30-Tage-Hoch: nur diese Zeile
        val price = c.last().close
        val report = ActivityAnalyzer.explain(input(candles = c, daily = daily(List(31) { price * 0.9 }, highs = List(31) { price })))
        val factors = WhyFactors.of(report)
        assertTrue(factors.any { it.kind == WhyFactorKind.NEAR_HIGH && !it.neutral })
        assertTrue(factors.none { it.kind == WhyFactorKind.POSITION_3D })
    }

    @Test
    fun position3d_quietOrShort_isNull() {
        assertNull(ActivityAnalyzer.position3d(series(), now))
        assertNull(ActivityAnalyzer.position3d(series(count = 40, tail = move24(-10.0)), now))
        // Spanne unter 3 %: «nahe am Hoch» ohne Aussage
        assertNull(ActivityAnalyzer.position3d(series(tail = move24(2.0)), now))
    }

    // ---------------- W9 Volumen ----------------

    @Test
    fun volume_approximateWithoutQuoteVolume() {
        val base = series(volumeTail = listOf(300.0, 100.0))
        val stats = ActivityAnalyzer.hourStats(base)!!
        assertTrue(stats.volumeApproximate)
        assertEquals(3.0, stats.volumeRatio!!, 1e-9)
        val report = ActivityAnalyzer.explain(input(candles = base))
        val reason = report.reasons.first { it.kind == ReasonKind.VOLUME_HIGH }
        assertTrue(reason.approximate)
        assertTrue(WhyFactors.of(report).first { it.kind == WhyFactorKind.VOLUME }.approximate)
        // Mit Quote-Umsatz: genau, Verhältnis aus dem Umsatz
        val quoted = ActivityAnalyzer.hourStats(series(volumeTail = listOf(300.0, 100.0), quote = true))!!
        assertFalse(quoted.volumeApproximate)
        assertEquals(3.0, quoted.volumeRatio!!, 0.01)
    }

    @Test
    fun volume_unreliableWithManyEmptyHours() {
        // 7 von 24 Vergleichsstunden ohne Handel → kein Verhältnis
        val v = List(7) { 0.0 } + List(17) { 100.0 } + listOf(500.0, 100.0)
        val c = series(count = 26, volumeTail = v)
        assertNull(ActivityAnalyzer.hourStats(series(count = 27, volumeTail = v))?.volumeRatio)
        // 6 sind noch erlaubt
        val ok = List(6) { 0.0 } + List(18) { 100.0 } + listOf(500.0, 100.0)
        assertNotNull(ActivityAnalyzer.hourStats(series(count = 27, volumeTail = ok))?.volumeRatio)
        assertNull(ActivityAnalyzer.hourStats(c))
    }

    // ---------------- Hebel ----------------

    @Test
    fun funding_exactlyAtThreshold() {
        assertEquals(ReasonKind.LEVERAGE_LONGS, ActivityAnalyzer.explain(input(funding = 0.05)).reasons.first { it.kind.name.startsWith("LEVERAGE") }.kind)
        assertEquals(ReasonKind.LEVERAGE_BALANCED, ActivityAnalyzer.explain(input(funding = 0.0499)).reasons.first { it.kind.name.startsWith("LEVERAGE") }.kind)
        assertEquals(ReasonKind.LEVERAGE_SHORTS, ActivityAnalyzer.explain(input(funding = -0.05)).reasons.first { it.kind.name.startsWith("LEVERAGE") }.kind)
        val strong = ActivityAnalyzer.explain(input(funding = 0.1)).reasons.first { it.kind == ReasonKind.LEVERAGE_LONGS }
        assertTrue(strong.strong)
    }

    @Test
    fun openInterest_inconsistentIsNeutral() {
        val mixed = ActivityAnalyzer.explain(input(funding = 0.01, oi = 15.0, oiConsistent = false))
        val lev = mixed.reasons.first { it.kind == ReasonKind.LEVERAGE_BALANCED }
        assertFalse(lev.strong)
        val f = WhyFactors.of(mixed).first { it.kind == WhyFactorKind.OPEN_INTEREST }
        assertEquals(WhyFactorNote.OI_MIXED, f.note)
        assertTrue(f.neutral)
        assertEquals(90, mixed.oiMinutes)
        assertFalse(mixed.oiConsistent)
        val clear = ActivityAnalyzer.explain(input(funding = 0.01, oi = 15.0))
        assertTrue(clear.reasons.first { it.kind == ReasonKind.LEVERAGE_BALANCED }.strong)
        assertEquals(WhyFactorNote.OI_UP, WhyFactors.of(clear).first { it.kind == WhyFactorKind.OPEN_INTEREST }.note)
    }

    @Test
    fun fundingTrend_attachedToFactor() {
        val history = FundingHistory(
            listOf(FundingPoint(now - 24 * h, 0.01), FundingPoint(now - 16 * h, 0.015), FundingPoint(now - 8 * h, 0.02)),
            "Bybit",
        )
        val report = ActivityAnalyzer.explain(input(funding = 0.03, history = history, fundingSource = "Bybit"))
        assertEquals(FundingTrend(FundingTrendDirection.RISING, 24), report.fundingTrend)
        assertEquals(report.fundingTrend, WhyFactors.of(report).first { it.kind == WhyFactorKind.FUNDING }.trend)
        // Ohne Funding kein Verlauf
        assertNull(ActivityAnalyzer.explain(input(history = history)).fundingTrend)
    }

    // ---------------- Funding-Verlauf ----------------

    private fun fh(source: String = "Binance Futures", vararg points: Pair<Int, Double>) =
        FundingHistory(points.map { (hoursAgo, rate) -> FundingPoint(now - hoursAgo * h, rate) }, source)

    @Test
    fun fundingTrend_directions() {
        val rising = fh("Bybit", 16 to 0.01, 8 to 0.012, 0 to 0.02)
        assertEquals(FundingTrendDirection.RISING, FundingTrendRules.trend(rising, null, null, now)?.direction)
        val falling = fh("Bybit", 24 to 0.03, 16 to 0.02, 8 to 0.015)
        assertEquals(FundingTrend(FundingTrendDirection.FALLING, 24), FundingTrendRules.trend(falling, 0.01, "Bybit", now))
        val stable = fh("Bybit", 24 to 0.01, 16 to 0.012, 8 to 0.0105)
        assertEquals(FundingTrendDirection.STABLE, FundingTrendRules.trend(stable, 0.011, "Bybit", now)?.direction)
        // Hin und her: kein Verlauf
        val mixed = fh("Bybit", 24 to 0.01, 16 to 0.03, 8 to 0.005)
        assertNull(FundingTrendRules.trend(mixed, 0.02, "Bybit", now))
    }

    @Test
    fun fundingTrend_hourlyRatesComparedPerEightHours() {
        // Stündliche Sätze (Hyperliquid) zählen achtfach: +0,001 %/Std. = +0,008 % je 8 Std. → steigend
        val hourly = fh("Hyperliquid", 3 to 0.0100, 2 to 0.0105, 1 to 0.0110)
        assertEquals(FundingTrendDirection.RISING, FundingTrendRules.trend(hourly, null, null, now)?.direction)
        // Dieselben Sätze einer 8-Stunden-Börse bleiben stabil
        val eight = fh("Bybit", 3 to 0.0100, 2 to 0.0105, 1 to 0.0110)
        assertEquals(FundingTrendDirection.STABLE, FundingTrendRules.trend(eight, null, null, now)?.direction)
    }

    @Test
    fun fundingTrend_thresholdAndSources() {
        // Genau 0,005 Prozentpunkte (aus Strings × 100) zählen als Veränderung
        val points = FundingTrendRules.parseBinance(
            """[{"fundingTime":${now - 16 * h},"fundingRate":"0.00010000"},""" +
                """{"fundingTime":${now - 8 * h},"fundingRate":"0.00012000"},""" +
                """{"fundingTime":${now - 1 * h},"fundingRate":"0.00015000"}]""",
        )
        assertEquals(3, points.size)
        val trend = FundingTrendRules.trend(FundingHistory(points, "Binance Futures"), null, null, now)
        assertEquals(FundingTrend(FundingTrendDirection.RISING, 15), trend)
        // Aktueller Satz einer anderen Börse zählt nicht → zu wenige Punkte
        val two = fh("Bybit", 16 to 0.01, 8 to 0.02)
        assertNull(FundingTrendRules.trend(two, 0.03, "OKX", now))
        assertNotNull(FundingTrendRules.trend(two, 0.03, "Bybit", now))
        // Zu kurze Spanne (< 2 Std.)
        val short = FundingHistory(listOf(FundingPoint(now - 60 * 60_000L, 0.01), FundingPoint(now - 30 * 60_000L, 0.02)), "OKX")
        assertNull(FundingTrendRules.trend(short, 0.03, "OKX", now))
        // Älter als 25 Std. zählt nicht
        assertNull(FundingTrendRules.trend(fh("OKX", 40 to 0.01, 32 to 0.02, 30 to 0.03), null, null, now))
        assertNull(FundingTrendRules.trend(null, 0.01, "OKX", now))
    }

    @Test
    fun fundingTrend_parsers() {
        val bybit = FundingTrendRules.parseBybit(
            """{"retCode":0,"result":{"category":"linear","list":[""" +
                """{"symbol":"BTCUSDT","fundingRate":"0.0002","fundingRateTimestamp":"${now - h}"},""" +
                """{"symbol":"BTCUSDT","fundingRate":"0.0001","fundingRateTimestamp":"${now - 9 * h}"}]}}""",
        )
        assertEquals(listOf(FundingPoint(now - 9 * h, 0.01), FundingPoint(now - h, 0.02)), bybit)
        val okx = FundingTrendRules.parseOkx(
            """{"code":"0","msg":"","data":[{"instId":"BTC-USDT-SWAP","fundingRate":"0.0003","realizedRate":"0.00029","fundingTime":"${now - h}"},""" +
                """{"instId":"BTC-USDT-SWAP","fundingRate":"0.0001","realizedRate":"","fundingTime":"${now - 9 * h}"}]}""",
        )
        assertEquals(0.01, okx[0].ratePercent, 1e-12)
        assertEquals(0.029, okx[1].ratePercent, 1e-12)
    }

    // ---------------- W4 Open-Interest-Verlauf ----------------

    @Test
    fun oiSeries_ageBounds() {
        val m = 60_000L
        val exactly30 = listOf(OiSample(1000.0, now - 30 * m, "Bybit"))
        assertEquals(10.0, OiSeries.change(exactly30, 1100.0, "Bybit", now).changePercent!!, 1e-9)
        assertEquals(30, OiSeries.change(exactly30, 1100.0, "Bybit", now).minutes)
        val younger = listOf(OiSample(1000.0, now - 30 * m + 1, "Bybit"))
        assertNull(OiSeries.change(younger, 1100.0, "Bybit", now).changePercent)
        val exactly12h = listOf(OiSample(1000.0, now - 12 * h, "Bybit"))
        assertNotNull(OiSeries.change(exactly12h, 1100.0, "Bybit", now).changePercent)
        val older = listOf(OiSample(1000.0, now - 12 * h - 1, "Bybit"))
        assertNull(OiSeries.change(older, 1100.0, "Bybit", now).changePercent)
        assertTrue(OiSeries.prune(older, now).isEmpty())
    }

    @Test
    fun oiSeries_medianAndConsistency() {
        val m = 60_000L
        val series = listOf(
            OiSample(1000.0, now - 180 * m, "Bybit"),
            OiSample(1050.0, now - 120 * m, "Bybit"),
            OiSample(2000.0, now - 60 * m, "Bybit"), // Ausreisser
        )
        val r = OiSeries.change(series, 1155.0, "Bybit", now)
        // Median 1050 → +10 %; 1155 liegt unter 2000 → uneinheitlich
        assertEquals(10.0, r.changePercent!!, 1e-9)
        assertFalse(r.consistent)
        assertEquals(180, r.minutes)
        assertEquals(3, r.samples)
        val clean = OiSeries.change(series.take(2), 1155.0, "Bybit", now)
        assertTrue(clean.consistent)
        // Andere Quelle zählt nicht, alte ohne Quelle schon
        assertNull(OiSeries.change(series, 1155.0, "OKX", now).changePercent)
        val legacy = listOf(OiSample(1000.0, now - 60 * m))
        assertEquals(15.5, OiSeries.change(legacy, 1155.0, "OKX", now).changePercent!!, 1e-9)
        assertNull(OiSeries.change(series, 0.0, "Bybit", now).changePercent)
        assertNull(OiSeries.change(series, null, "Bybit", now).changePercent)
    }

    @Test
    fun oiSeries_appendSpacingMaxAndSourceSwitch() {
        val m = 60_000L
        var s = emptyList<OiSample>()
        for (i in 0 until 8) s = OiSeries.append(s, OiSample(1000.0 + i, now - (7 - i) * 30 * m, "Binance Futures"), now)
        assertEquals(OiSeries.MAX_SAMPLES, s.size)
        assertEquals(now, s.last().time)
        // Zu dicht: nicht angehängt
        val dense = OiSeries.append(s, OiSample(2000.0, now + 10 * m, "Binance Futures"), now + 10 * m)
        assertEquals(s.last(), dense.last())
        // Quellwechsel: Verlauf beginnt neu
        val switched = OiSeries.append(s, OiSample(500.0, now + 31 * m, "Bybit"), now + 31 * m)
        assertEquals(listOf(OiSample(500.0, now + 31 * m, "Bybit")), switched)
        // Übernahme der alten Einzelmessung (ohne Quelle) bleibt erhalten
        val migrated = OiSeries.append(listOf(OiSample(900.0, now - 60 * m)), OiSample(950.0, now, "Bybit"), now)
        assertEquals(2, migrated.size)
        // Ungültige Messung wird nicht angehängt
        assertEquals(1, OiSeries.append(listOf(OiSample(900.0, now - 60 * m)), OiSample(Double.NaN, now, "Bybit"), now).size)
    }

    // ---------------- Breiter Markt ----------------

    @Test
    fun broadMarket_snapshot() {
        val coins = listOf("BTC", "ETH", "USDT", "USDC", "WBTC", "STETH", "PAXG", "SOL") .map { BroadCoin(it, 50.0, now) } +
            (1..11).map { BroadCoin("ALT$it", it.toDouble(), now - h) }
        val snap = BroadMarket.snapshot(coins, excludeBase = "sol", now = now)!!
        assertEquals(11, snap.coins)
        assertEquals(6.0, snap.change24h, 1e-12)
        assertEquals(now - h, snap.time)
        // Ohne den Coin selbst zu wenige
        assertNull(BroadMarket.snapshot(coins.take(8) + (1..9).map { BroadCoin("ALT$it", 1.0, now) }, "SOL", now))
        // Zu alt
        assertNull(BroadMarket.snapshot((1..12).map { BroadCoin("A$it", 1.0, now - 3 * h - 1) }, null, now))
        assertNotNull(BroadMarket.snapshot((1..12).map { BroadCoin("A$it", 1.0, now - 3 * h) }, null, now))
        assertEquals(2.5, BroadMarket.median(listOf(4.0, 1.0, 2.0, 3.0)), 1e-12)
    }

    @Test
    fun broadMarket_parse() {
        val coins = BroadMarket.parse(
            """[{"id":"solana","symbol":"sol","price_change_percentage_24h":-2.5,"last_updated":"2023-11-14T22:13:20.000Z"},""" +
                """{"id":"x","symbol":"x","price_change_percentage_24h":null},{"id":"y"}]""",
        )
        assertEquals(2, coins.size)
        assertEquals(BroadCoin("SOL", -2.5, 1_700_000_000_000L), coins[0])
        assertNull(coins[1].change24h)
        assertTrue(BroadMarket.parse("nicht json").isEmpty())
        assertTrue(BroadMarket.parse(null).isEmpty())
    }

    // ---------------- Herkunft (W3) ----------------

    @Test
    fun freshness_sourcesAndAges() {
        val sources = WhySources(
            candles = "Coinbase", candlesFallback = true, reference = "Binance", futures = "OKX",
            futuresNote = FuturesSourceNote.REFERENCE, fearGreedTime = now - now % day, broadTime = now - h,
        )
        val report = ActivityAnalyzer.explain(
            input(candles = series(tail = move24(5.0)), broad = BroadMarketSnapshot(1.0, 20, now - h), funding = 0.01)
                .copy(fearGreed = 40, sources = sources)
        )
        val items = WhyFreshness.sources(report)
        assertEquals(
            listOf(WhySourceRole.CANDLES, WhySourceRole.COMPARISON, WhySourceRole.FUTURES, WhySourceRole.BREADTH, WhySourceRole.FEAR_GREED),
            items.map { it.role },
        )
        assertEquals(FuturesSourceNote.FALLBACK, items[0].note)
        assertEquals(FuturesSourceNote.REFERENCE, items[2].note)
        assertEquals(report.candleTime, runOpen)
        // Ohne Futures-Grund keine Futures-Quelle, Bitcoin ohne Marktbreite
        val btc = ActivityAnalyzer.explain(input(base = "BTC").copy(sources = sources))
        assertTrue(WhyFreshness.sources(btc).none { it.role == WhySourceRole.FUTURES || it.role == WhySourceRole.BREADTH })
        // Ohne Angaben: keine Liste (dann der allgemeine Satz)
        assertTrue(WhyFreshness.sources(ActivityAnalyzer.explain(input())).isEmpty())
        // Fear & Greed: UTC-Tage
        val midnight = now - now % day
        assertEquals(0L, WhyFreshness.fearGreedDaysAgo(midnight, now))
        assertEquals(1L, WhyFreshness.fearGreedDaysAgo(midnight - day, now))
        assertEquals(1L, WhyFreshness.fearGreedDaysAgo(midnight - 1, midnight))
        // OI-Vergleich: unter 90 Min. in Minuten, sonst gerundete Stunden
        assertNull(WhyFreshness.oiAgeHours(89))
        assertEquals(2, WhyFreshness.oiAgeHours(90))
        assertEquals(2, WhyFreshness.oiAgeHours(149))
        assertEquals(3, WhyFreshness.oiAgeHours(150))
    }

    @Test
    fun factors_tinyVolatilityAndPegFirst() {
        val c = series(pattern = listOf(0.01, -0.01), tail = listOf(0.3, 0.0))
        val v = WhyFactors.of(ActivityAnalyzer.explain(input(candles = c))).first { it.kind == WhyFactorKind.VOLATILITY }
        assertEquals(WhyFactorNote.VOLATILITY_TINY, v.note)
        assertTrue(v.neutral)
        val stable = ActivityAnalyzer.explain(input(base = "USDC", candles = series(start = 0.9995, pattern = listOf(0.0))))
        assertEquals(WhyFactorKind.PEG, WhyFactors.rank(stable).first().kind)
    }
}
