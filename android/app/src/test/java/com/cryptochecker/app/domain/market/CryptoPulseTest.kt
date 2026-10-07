package com.cryptochecker.app.domain.market

import com.cryptochecker.app.domain.activity.WhyMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoPulseTest {

    private fun input(
        btc: Double? = 0.5,
        eth: Double? = 0.5,
        sol: Double? = 0.5,
        ratio: Double? = 1.0,
        fng: Int? = 50,
        funding: Double? = 0.01,
        gas: Double? = 5.0,
    ) = PulseInput(btc, eth, sol, ratio, fng, funding, gas, time = 1_000L)

    @Test
    fun missingMarketDataIsUnavailable() {
        assertNull(CryptoPulse.evaluate(input(btc = null)))
        assertNull(CryptoPulse.evaluate(input(eth = null)))
        assertNull(CryptoPulse.evaluate(input(sol = Double.NaN)))
        assertNotNull(CryptoPulse.evaluate(input()))
    }

    @Test
    fun altsLine() {
        // avg(ETH, SOL) − BTC
        assertEquals(PulseAlts.STRONGER, CryptoPulse.alts(1.0, 2.5, 2.5))
        assertEquals(PulseAlts.STRONGER, CryptoPulse.alts(0.0, 1.0, 2.0)) // genau +1.5 pp
        assertEquals(PulseAlts.WEAKER, CryptoPulse.alts(2.0, 0.5, 0.5)) // genau −1.5 pp
        assertEquals(PulseAlts.EVEN, CryptoPulse.alts(1.0, 2.0, 2.4))
        assertEquals(PulseAlts.EVEN, CryptoPulse.alts(-1.0, -2.0, -2.4))
    }

    @Test
    fun volumeLevels() {
        assertEquals(PulseVolume.HIGH, CryptoPulse.volumeLevel(1.5))
        assertEquals(PulseVolume.NORMAL, CryptoPulse.volumeLevel(1.49))
        assertEquals(PulseVolume.NORMAL, CryptoPulse.volumeLevel(0.61))
        assertEquals(PulseVolume.LOW, CryptoPulse.volumeLevel(0.6))
    }

    @Test
    fun fundingLevels() {
        assertEquals(PulseFunding.HIGH, CryptoPulse.fundingLevel(0.03))
        assertEquals(PulseFunding.HIGH, CryptoPulse.fundingLevel(0.1))
        assertEquals(PulseFunding.SLIGHT, CryptoPulse.fundingLevel(0.01))
        assertEquals(PulseFunding.SLIGHT, CryptoPulse.fundingLevel(0.0299))
        assertEquals(PulseFunding.NEUTRAL, CryptoPulse.fundingLevel(0.0099))
        assertEquals(PulseFunding.NEUTRAL, CryptoPulse.fundingLevel(-0.0049))
        assertEquals(PulseFunding.NEGATIVE, CryptoPulse.fundingLevel(-0.005))
        assertEquals(PulseFunding.NEGATIVE, CryptoPulse.fundingLevel(-0.05))
    }

    @Test
    fun gasLevels() {
        assertEquals(PulseGas.LOW, CryptoPulse.gasLevel(1.99))
        assertEquals(PulseGas.NORMAL, CryptoPulse.gasLevel(2.0))
        assertEquals(PulseGas.NORMAL, CryptoPulse.gasLevel(10.0))
        assertEquals(PulseGas.HIGH, CryptoPulse.gasLevel(10.01))
    }

    @Test
    fun summaryRules() {
        assertEquals(PulseSummary.BROAD_UP, CryptoPulse.summary(1.5, 2.0, 3.0, 1.0))
        assertEquals(PulseSummary.BROAD_UP_VOLUME, CryptoPulse.summary(1.5, 2.0, 3.0, 1.5))
        assertEquals(PulseSummary.BROAD_DOWN, CryptoPulse.summary(-1.5, -2.0, -3.0, null))
        assertEquals(PulseSummary.BROAD_DOWN_VOLUME, CryptoPulse.summary(-1.5, -2.0, -3.0, 2.0))
        assertEquals(PulseSummary.CALM, CryptoPulse.summary(0.9, -0.9, 0.0, 3.0))
        assertEquals(PulseSummary.MIXED, CryptoPulse.summary(1.0, 0.0, 0.0, 1.0)) // |1.0| ist nicht < 1
        assertEquals(PulseSummary.MIXED, CryptoPulse.summary(2.0, 2.0, 1.4, 2.0))
        assertEquals(PulseSummary.MIXED, CryptoPulse.summary(3.0, -3.0, 3.0, 1.0))
    }

    @Test
    fun extrasInOrder() {
        assertEquals(listOf(PulseExtra.GREED, PulseExtra.LEVERAGE), CryptoPulse.extras(70, 0.03))
        assertEquals(listOf(PulseExtra.FEAR), CryptoPulse.extras(30, 0.029))
        assertTrue(CryptoPulse.extras(50, -0.1).isEmpty())
        assertTrue(CryptoPulse.extras(null, null).isEmpty())
    }

    @Test
    fun optionalSectionsAreOmitted() {
        val report = CryptoPulse.evaluate(input(ratio = null, fng = null, funding = null, gas = null))!!
        assertNull(report.volume)
        assertNull(report.fearGreed)
        assertNull(report.funding)
        assertNull(report.gas)
        assertEquals(PulseSummary.CALM, report.summary)
        assertTrue(report.extras.isEmpty())
    }

    @Test
    fun fullReport() {
        val report = CryptoPulse.evaluate(
            input(btc = 3.2, eth = 5.0, sol = 6.0, ratio = 1.8, fng = 75, funding = 0.04, gas = 12.0)
        )!!
        assertEquals(PulseAlts.STRONGER, report.alts)
        assertEquals(PulseVolume.HIGH, report.volume)
        assertEquals(PulseFunding.HIGH, report.funding)
        assertEquals(PulseGas.HIGH, report.gas)
        assertEquals(PulseSummary.BROAD_UP_VOLUME, report.summary)
        assertEquals(listOf(PulseExtra.GREED, PulseExtra.LEVERAGE), report.extras)
        assertEquals(1_000L, report.time)
    }

    private fun lead(
        btc: Double, eth: Double, sol: Double,
        ratio: Double? = null, funding: Double? = null,
    ): PulseLead = CryptoPulse.leadSentence(
        CryptoPulse.evaluate(input(btc, eth, sol, ratio = ratio, funding = funding))!!
    )

    @Test
    fun leadKinds() {
        assertEquals(PulseLeadKind.CALM, lead(0.5, -0.4, 0.9).kind)
        assertEquals(PulseLeadKind.BROAD_DOWN, lead(-2.0, -4.0, -5.0).kind) // auch wenn Alts schwächer
        assertEquals(PulseLeadKind.BTC_LEADS, lead(3.0, 2.9, 2.0).kind)
        assertEquals(PulseLeadKind.BTC_LEADS, lead(3.0, 3.0, 3.0).kind) // Gleichstand zählt für BTC
        assertEquals(PulseLeadKind.ALTS_STRONGER, lead(3.2, 5.0, 6.0).kind) // breit, Alts klar stärker
        assertEquals(PulseLeadKind.BROAD_UP, lead(2.0, 2.5, 2.4).kind) // breit, gleichauf, BTC nicht vorne
        assertEquals(PulseLeadKind.ALTS_STRONGER, lead(-1.0, 2.0, 1.0).kind) // gemischt, Alts stärker
        assertEquals(PulseLeadKind.BTC_STRONGER, lead(3.0, 0.1, 0.2).kind)
        assertEquals(PulseLeadKind.DRIFT_UP, lead(1.2, 0.3, 0.8).kind)
        assertEquals(PulseLeadKind.DRIFT_UP, lead(1.2, 0.0, 1.0).kind)
        assertEquals(PulseLeadKind.DRIFT_DOWN, lead(-1.2, -0.4, -1.4).kind)
        assertEquals(PulseLeadKind.MIXED, lead(1.2, -0.4, 0.5).kind)
    }

    @Test
    fun leadVolumeAndFunding() {
        // Nichts bekannt → nur ein Satz
        assertNull(lead(2.0, 2.0, 2.0).detail)
        // 1.34 → 34 % über normal; Funding 0.01 (leicht positiv) gilt als neutral
        val above = lead(2.0, 2.0, 2.0, ratio = 1.34, funding = 0.01)
        assertEquals(PulseDetail.VOL_ABOVE_FUND_NEUTRAL, above.detail)
        assertEquals(34, above.volumePercent)
        // 0.62 → 38 % unter normal, Funding hoch
        val below = lead(2.0, 2.0, 2.0, ratio = 0.62, funding = 0.05)
        assertEquals(PulseDetail.VOL_BELOW_FUND_HIGH, below.detail)
        assertEquals(38, below.volumePercent)
        // ±5 % = normal, ohne Zahl
        val normal = lead(2.0, 2.0, 2.0, ratio = 1.04, funding = -0.02)
        assertEquals(PulseDetail.VOL_NORMAL_FUND_NEGATIVE, normal.detail)
        assertEquals(0, normal.volumePercent)
        assertEquals(PulseDetail.VOL_ABOVE, lead(2.0, 2.0, 2.0, ratio = 1.05).detail)
        assertEquals(PulseDetail.VOL_BELOW, lead(2.0, 2.0, 2.0, ratio = 0.95).detail)
        assertEquals(PulseDetail.VOL_NORMAL, lead(2.0, 2.0, 2.0, ratio = 0.96).detail)
        // Nur Funding
        assertEquals(PulseDetail.FUND_HIGH, lead(2.0, 2.0, 2.0, funding = 0.03).detail)
        assertEquals(PulseDetail.FUND_NEUTRAL, lead(2.0, 2.0, 2.0, funding = 0.0).detail)
        assertEquals(PulseDetail.FUND_NEGATIVE, lead(2.0, 2.0, 2.0, funding = -0.005).detail)
        // Grosses Verhältnis
        assertEquals(320, lead(2.0, 2.0, 2.0, ratio = 4.2).volumePercent)
    }

    @Test
    fun factorChecklist() {
        // Volumen, Fear & Greed und Gas haben eigene Karten → nur Funding bleibt
        val full = CryptoPulse.factors(
            CryptoPulse.evaluate(input(btc = 3.0, eth = 3.0, sol = 3.0, ratio = 1.8, fng = 80, funding = 0.01, gas = 3.0))!!
        )
        assertEquals(listOf(PulseFactor(PulseFactorKind.FUNDING, WhyMark.NEUTRAL)), full)
        // Funding hoch = Vorsicht (auch bei ruhigem Markt)
        val calm = CryptoPulse.factors(
            CryptoPulse.evaluate(input(ratio = 2.0, fng = 72, funding = 0.04, gas = null))!!
        )
        assertEquals(listOf(PulseFactor(PulseFactorKind.FUNDING, WhyMark.CAUTION)), calm)
        // Funding negativ = Vorsicht
        val negative = CryptoPulse.factors(CryptoPulse.evaluate(input(funding = -0.02))!!)
        assertEquals(listOf(PulseFactor(PulseFactorKind.FUNDING, WhyMark.CAUTION)), negative)
        // Ohne Funding bleibt nichts → Abschnitt entfällt
        val none = CryptoPulse.factors(
            CryptoPulse.evaluate(input(btc = 2.0, eth = 2.0, sol = 2.0, ratio = 0.5, fng = 20, funding = null, gas = 50.0))!!
        )
        assertTrue(none.isEmpty())
    }

    @Test
    fun cacheFreshness() {
        assertTrue(CryptoPulse.isFresh(0L, CryptoPulse.CACHE_MILLIS - 1))
        assertTrue(!CryptoPulse.isFresh(0L, CryptoPulse.CACHE_MILLIS))
        assertTrue(!CryptoPulse.isFresh(10L, 0L))
    }
}
