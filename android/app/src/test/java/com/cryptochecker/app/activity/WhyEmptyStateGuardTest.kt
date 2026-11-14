package com.cryptochecker.app.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4-02: «Was bewegt sich mit?» ohne Kerzen nennt die Börse des Paars statt «Binance»; offline bzw. ohne Antwort
 * innerhalb der Zeitgrenze heisst es «nicht abrufbar» mit «Erneut versuchen», das neu fragt (refresh) statt den
 * gemerkten Leerstand zu liefern. iOS: WhyCandlesFailureTests.
 */
class WhyEmptyStateGuardTest {

    private fun source(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    @Test
    fun leerzustandMitBoerseUndErneutVersuchen() {
        val sheet = source("src/main/java/com/cryptochecker/app/ui/features/watchlist/WatchWhySheet.kt")
        assertTrue(sheet.contains("failure = r.candlesFailure,"))
        assertTrue(sheet.contains("exchange = watch.exchangeName,"))
        assertTrue(sheet.contains("onRetry = if (r.candlesFailure != null) ({ attempt += 1 }) else null,"))
        assertTrue(sheet.contains("load(watch, attempt > 0)"))
        assertFalse(sheet.contains("EmptyReasons(onRetry = null)"))
        val reasons = source("src/main/java/com/cryptochecker/app/ui/features/watchlist/WatchWhyReasons.kt")
        assertTrue(reasons.contains("stringResource(R.string.why_no_candles_hint, exchange)"))
        assertFalse(reasons.contains("R.string.why_no_data)"))
    }

    @Test
    fun monitorUnterscheidetNichtAbrufbar() {
        val monitor = source("src/main/java/com/cryptochecker/app/activity/ActivityMonitor.kt")
        assertTrue(monitor.contains("timedOutcome { volumeDataSource.pairCandles(pair, watch.lastPrice, CandleInterval.H1, limit, refresh) }"))
        assertTrue(monitor.contains("WhyCandlesFailure.of(connectivity.isOnlineNow(), candlesFailed)"))
    }
}
