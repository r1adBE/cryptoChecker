package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayCost
import com.cryptochecker.app.domain.portfolio.PortfolioDisplayFx
import com.cryptochecker.app.domain.portfolio.PortfolioPosition
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Kleine Testklassen dieses Themas in einer Datei; jede Klasse unverändert.

// ---- aus PortfolioRefreshGateTest

/** Wann die Hintergrund-Aktualisierung das Portfolio holt ([PortfolioSnapshotUpdater.shouldRefresh]). */
class PortfolioRefreshGateTest {

    @Test
    fun widgetAlwaysRefreshes() {
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = true, activeAlarms = false))
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = true, activeAlarms = null))
    }

    @Test
    fun activeAlarmWithoutWidgetRefreshes() {
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = false, activeAlarms = true))
    }

    @Test
    fun noWidgetAndNoAlarmSkips() {
        assertFalse(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = false, activeAlarms = false))
    }

    @Test
    fun unreadableAlarmsRefreshAnyway() {
        // Kurz gesperrte Datenbank ist nicht «keine Alarme»: im Zweifel aktualisieren
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = false, activeAlarms = null))
    }
}

// ---- aus PortfolioSnapshotCodecTest

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

    /** «Seit Kauf»/«7 T» in der Anzeigewährung samt Merkzettel überstehen einen Neustart; ältere Aufnahmen laden ohne. */
    @Test
    fun displayValuesRoundTripAndOldSnapshotWithoutKeys() {
        val change = PortfolioChange(-80.5, null, listOf("SOL"))
        assertEquals(change, PortfolioSnapshotCodec.readDisplayChange(PortfolioSnapshotCodec.displayChangeJson(change)))
        val plain = PortfolioChange(95.0, 2.1)
        assertEquals(plain, PortfolioSnapshotCodec.readDisplayChange(PortfolioSnapshotCodec.displayChangeJson(plain)))
        assertNull(PortfolioSnapshotCodec.displayChangeJson(null))
        assertNull(PortfolioSnapshotCodec.displayChangeJson(PortfolioChange(Double.NaN, 1.0)))
        assertNull(PortfolioSnapshotCodec.readDisplayChange(null))
        assertNull(PortfolioSnapshotCodec.readDisplayChange("kaputt"))
        val fx = PortfolioDisplayFx("CHF", mapOf("BTC" to PortfolioDisplayCost(20_000.0, 18_000.0)), mapOf(20_193L to 0.8, 20_199L to 0.81), 20_200L)
        assertEquals(fx, PortfolioSnapshotCodec.readDisplayFx(PortfolioSnapshotCodec.displayFxJson(fx)))
        assertNull(PortfolioSnapshotCodec.readDisplayFx(null))
        assertNull(PortfolioSnapshotCodec.readDisplayFx("kaputt"))
    }
}

// ---- aus PortfolioWidgetRowBasisTest

/** Zeitraum vor der Veränderung je Position in der Liste ([PortfolioWidgetViews.rowChangeBasis]). */
class PortfolioWidgetRowBasisTest {

    private fun snapshot(stamp: ChangeStamp?) = PortfolioSnapshot(
        total = 100.0,
        changeAmount = null,
        changePercent = null,
        currency = "USD",
        time = 1L,
        empty = false,
        stamp = stamp,
    )

    @Test
    fun `ohne Stempel rollende 24 Stunden`() {
        assertEquals(ChangeBasis.ROLLING_24H, PortfolioWidgetViews.rowChangeBasis(snapshot(null)))
    }

    @Test
    fun `rollend aufgenommen heisst 24h`() {
        assertEquals(ChangeBasis.ROLLING_24H, PortfolioWidgetViews.rowChangeBasis(snapshot(ChangeStamp(ChangeBasis.ROLLING_24H, 0L))))
    }

    @Test
    fun `ab Tagesbeginn aufgenommen heisst heute in derselben Zone`() {
        assertEquals(ChangeBasis.LOCAL_DAY, PortfolioWidgetViews.rowChangeBasis(snapshot(ChangeStamp(ChangeBasis.LOCAL_DAY, 1000L))))
        assertEquals(ChangeBasis.UTC_DAY, PortfolioWidgetViews.rowChangeBasis(snapshot(ChangeStamp(ChangeBasis.UTC_DAY, 1000L))))
    }
}
