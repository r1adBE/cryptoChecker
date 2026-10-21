package com.cryptochecker.app.domain.alarm

import com.cryptochecker.marketdata.model.FuturesContractType
import com.cryptochecker.marketdata.model.PairQuery
import com.cryptochecker.marketdata.model.SimpleTicker
import com.cryptochecker.marketdata.model.market.Lighter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alarme nicht auf einem laut Börse über 2 Stunden alten Kurs; ohne Börsenzeit wie bisher. Wie iOS. */
class AlarmFreshnessTest {

    private val now = 1_700_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun withoutExchangeTimeNothingChanges() {
        assertFalse(AlarmFreshness.exchangeTimeTooOld(null, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(0L, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(-5L, now))
    }

    @Test
    fun oldExchangeTimeHoldsAlarms() {
        assertTrue(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour - 1, now))
        assertTrue(AlarmFreshness.exchangeTimeTooOld(now - 26 * hour, now))
    }

    @Test
    fun recentOrFutureExchangeTimeFires() {
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now - 2 * hour, now))
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now - 5 * 60_000L, now))
        // Uhr der Börse etwas voraus: kein Grund zu schweigen
        assertFalse(AlarmFreshness.exchangeTimeTooOld(now + 60_000L, now))
    }

    /** Lighter ohne Handel in 24 h: der stehengebliebene Kurs bleibt sichtbar, löst aber keinen Alarm aus. */
    @Test
    fun lighterWithoutTradesIn24hHoldsAlarms() {
        val info = PairQuery("ADI", "USDC", "ADI", FuturesContractType.PERPETUAL)
        fun read(trades: Int) = Lighter().readTicker(
            0,
            """{"code":200,"order_book_stats":[{"symbol":"ADI","last_trade_price":8.8236,"daily_trades_count":$trades,""" +
                """"daily_base_token_volume":0,"daily_quote_token_volume":0,"daily_price_change":0}]}""",
            SimpleTicker(),
            info,
        )
        val quiet = read(0)
        assertTrue(quiet.last == 8.8236)
        assertTrue(AlarmFreshness.blocksAlarms(quiet.exchangeTime, markFallback = false, now = quiet.timestamp))
        val traded = read(5)
        assertFalse(AlarmFreshness.blocksAlarms(traded.exchangeTime, markFallback = false, now = traded.timestamp))
    }
}
