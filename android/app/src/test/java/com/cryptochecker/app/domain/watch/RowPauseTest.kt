package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.refresh.ExchangeBackoff
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.parity.ParityJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** Börsen-Pause in der Zeile: gemeinsame Fälle `row_pause.json` (iOS: `RowPauseTests.swift`). */
class RowPauseTest {

    private val data = ParityJson.load("row_pause.json")

    private fun long(value: Any?): Long? = (value as Double?)?.toLong()

    @Test
    fun shownUntilParity() {
        val now = long(data["now"])!!
        @Suppress("UNCHECKED_CAST")
        val cases = data["shownUntil"] as List<Map<String, Any?>>
        for (c in cases) {
            val pausedUntil = long(c["pausedInMillis"])?.let { now + it }
            val expected = long(c["expected"])?.let { now + it }
            assertEquals(c["name"] as String, expected, RowPause.shownUntil(c["disabled"] as Boolean, pausedUntil, now))
        }
    }

    @Test
    fun pausesParity() {
        @Suppress("UNCHECKED_CAST")
        val cases = data["pauses"] as List<Map<String, Any?>>
        for (c in cases) {
            @Suppress("UNCHECKED_CAST")
            val states = (c["states"] as List<Map<String, Any?>>).associate { s ->
                s["key"] as String to ExchangeBackoff.State(
                    strikes = long(s["strikes"])!!.toInt(),
                    pausedUntil = long(s["pausedUntil"])!!,
                    reason = (s["reason"] as String?)?.let(RefreshFailure::valueOf),
                    timeoutRuns = long(s["timeoutRuns"])!!.toInt(),
                )
            }
            @Suppress("UNCHECKED_CAST")
            val expected = (c["expected"] as Map<String, Any?>).mapValues { long(it.value)!! }
            assertEquals(c["name"] as String, expected, RowPause.pauses(states))
        }
    }

    @Test
    fun pauseFromBackoffIsShownUntilItEnds() {
        // Neue Pause nach «zu viele Anfragen»: die Zeile zeigt ihr Ende, danach wieder die normale Zeitzeile
        val now = 1_700_000_000_000L
        val state = ExchangeBackoff.next(null, ExchangeBackoff.Outcome.RATE_LIMITED, now)!!
        val until = RowPause.pauses(mapOf("Binance" to state))["Binance"]
        assertEquals(now + ExchangeBackoff.BASE_MILLIS, RowPause.shownUntil(false, until, now))
        assertEquals(null, RowPause.shownUntil(false, until, now + ExchangeBackoff.BASE_MILLIS))
    }
}
