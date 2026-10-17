package com.cryptochecker.app.widget

import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.domain.watch.ChangeStamp
import org.junit.Assert.assertEquals
import org.junit.Test

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
