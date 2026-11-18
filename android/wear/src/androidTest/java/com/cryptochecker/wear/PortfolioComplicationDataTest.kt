package com.cryptochecker.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import com.cryptochecker.marketdata.watch.WatchInterval
import com.cryptochecker.marketdata.watch.WatchPortfolio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Portfolio-Komplikation auf der Uhr: aus, mit Wert, Beträge verborgen und veraltet. */
@RunWith(AndroidJUnit4::class)
class PortfolioComplicationDataTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val interval = WatchInterval.DEFAULT_MINUTES

    @Test
    fun offSaysHowToTurnItOn() {
        val short = PortfolioComplicationData.off(context, ComplicationType.SHORT_TEXT, tap = null) as ShortTextComplicationData
        assertEquals(context.getString(R.string.watch_portfolio_off), short.text.shown(context))
        assertEquals(context.getString(R.string.watch_portfolio), short.title?.shown(context))
        val long = PortfolioComplicationData.off(context, ComplicationType.LONG_TEXT, tap = null) as LongTextComplicationData
        val expected = WatchPortfolioText.offLine(
            context.getString(R.string.watch_portfolio),
            context.getString(R.string.watch_portfolio_turn_on),
        )
        assertEquals(expected, long.text.shown(context))
    }

    @Test
    fun valueShowsTotalAndChange() {
        val now = System.currentTimeMillis()
        val p = WatchPortfolio(12_345.67, 1.2, "USD", now, hidden = false)
        val long = PortfolioComplicationData.data(context, ComplicationType.LONG_TEXT, p, interval, null, now)
            as LongTextComplicationData
        assertEquals(WatchPortfolioText.total(p) + "  " + WatchFormat.change(1.2), long.text.shown(context))
        val short = PortfolioComplicationData.data(context, ComplicationType.SHORT_TEXT, p, interval, null, now)
            as ShortTextComplicationData
        assertEquals(WatchPortfolioText.compact(p), short.text.shown(context))
        assertEquals("USD", short.title?.shown(context))
    }

    @Test
    fun hiddenAmountsShowNoChange() {
        val now = System.currentTimeMillis()
        val p = WatchPortfolio(12_345.67, 1.2, "USD", now, hidden = true)
        val long = PortfolioComplicationData.data(context, ComplicationType.LONG_TEXT, p, interval, null, now)
            as LongTextComplicationData
        val text = long.text.shown(context)
        assertEquals(WatchPortfolioText.total(p), text)
        assertFalse(text, text.contains("12"))
    }

    @Test
    fun outdatedValueShowsAge() {
        val time = System.currentTimeMillis() - 6 * 60 * 60_000L
        val now = System.currentTimeMillis()
        val p = WatchPortfolio(500.0, null, "CHF", time, hidden = false)
        val long = PortfolioComplicationData.data(context, ComplicationType.LONG_TEXT, p, interval, null, now)
            as LongTextComplicationData
        assertTrue(long.text.shown(context).endsWith("  ·  " + WatchAge.short(time, now)))
        val short = PortfolioComplicationData.data(context, ComplicationType.SHORT_TEXT, p, interval, null, now)
            as ShortTextComplicationData
        val title = short.title!!.shown(context)
        assertTrue(title, title.startsWith("CHF ") && title != "CHF ")
    }
}
