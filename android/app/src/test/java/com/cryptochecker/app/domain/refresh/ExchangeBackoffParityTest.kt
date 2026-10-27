package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pausen nach «zu viele Anfragen» — vor allem: eine unmögliche gespeicherte Pause (weiter als 6 h in der Zukunft)
 * zählt nirgends (`exchange_backoff.json`, geteilt mit iOS). Die Fälle «merge» gibt es nur auf iOS.
 * Wie `ExchangeBackoffParityTests`.
 */
class ExchangeBackoffParityTest {

    private val data = ParityJson.load("exchange_backoff.json")
    private val now = data["now"].num()!!.toLong()

    private fun state(raw: Any?): ExchangeBackoff.State? {
        if (raw == null) return null
        val s = raw.obj()
        return ExchangeBackoff.State(
            strikes = s["strikes"].num()!!.toInt(),
            pausedUntil = s["pausedUntil"].num()!!.toLong(),
            reason = (s["reason"] as String?)?.let { RefreshFailure.valueOf(it) },
            timeoutRuns = s["timeoutRuns"].num()!!.toInt(),
        )
    }

    @Test
    fun nextNachZuVielenAnfragen() {
        data["next"].list().forEach { raw ->
            val c = raw.obj()
            val result = ExchangeBackoff.next(
                state(c["state"]), ExchangeBackoff.Outcome.RATE_LIMITED, now,
                retryAfterMillis = c["retryAfterMillis"].num()?.toLong(),
                banned = c["banned"] as Boolean, shared = c["shared"] as Boolean,
            )
            assertEquals(c["name"] as String, state(c["expected"]), result)
        }
    }

    @Test
    fun gemeinsamePause() {
        data["groupPause"].list().forEach { raw ->
            val c = raw.obj()
            val result = ExchangeBackoff.groupPause(
                state(c["state"]), now, c["httpCode"].num()!!.toInt(), c["retryAfterSeconds"].num()?.toLong(),
            )
            assertEquals(c["name"] as String, state(c["expected"]), result)
        }
    }

    @Test
    fun uebernommenePause() {
        data["adoptedPause"].list().forEach { raw ->
            val c = raw.obj()
            val result = ExchangeBackoff.adoptedPause(state(c["old"]), c["until"].num()!!.toLong(), now)
            assertEquals(c["name"] as String, state(c["expected"]), result)
        }
    }
}
