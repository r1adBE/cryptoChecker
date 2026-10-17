package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gespeicherter Gewinn/Verlust je Position und je Coin übersteht einen Neustart; ältere Aufnahmen laden ohne. */
class PortfolioSnapshotCodecTest {

    @Test
    fun positionsRoundTripWithPnl() {
        val positions = listOf(
            PortfolioPosition("BTC", 83_385.24, 88.9, 1.5, "1.3", pnlAmount = 1_250.0, pnlPercent = 1.52),
            PortfolioPosition("ETH", 10_000.0, 11.1, -0.5, "3", pnlAmount = null, pnlPercent = null),
        )
        val back = PortfolioSnapshotCodec.readPositions(PortfolioSnapshotCodec.positionsJson(positions).toString())
        assertEquals(positions, back)
    }

    @Test
    fun oldPositionsWithoutPnlStillLoad() {
        val old = """[{"s":"BTC","v":900.0,"p":100.0,"c":1.0,"q":"0.01"}]"""
        val p = PortfolioSnapshotCodec.readPositions(old).single()
        assertEquals("BTC", p.symbol)
        assertEquals(1.0, p.change24hPercent!!, 1e-9)
        assertNull(p.pnlAmount)
        assertNull(p.pnlPercent)
        assertNull(PortfolioWidgetViews.rowPnl(p))
    }

    @Test
    fun coinPnlRoundTripAndOldSnapshotWithoutKey() {
        val coinPnl = mapOf("BTC" to PortfolioChange(13.0, 6.28), "SOL" to PortfolioChange(10.0, null))
        assertEquals(coinPnl, PortfolioSnapshotCodec.readCoinPnl(PortfolioSnapshotCodec.coinPnlJson(coinPnl)))
        // Ältere Aufnahme (Schlüssel fehlt) oder unlesbar: leer
        assertTrue(PortfolioSnapshotCodec.readCoinPnl(null).isEmpty())
        assertTrue(PortfolioSnapshotCodec.readCoinPnl("kaputt").isEmpty())
        // Nicht endliche Beträge werden nicht gespeichert
        assertTrue(PortfolioSnapshotCodec.readCoinPnl(PortfolioSnapshotCodec.coinPnlJson(mapOf("X" to PortfolioChange(Double.NaN, 1.0)))).isEmpty())
    }
}
