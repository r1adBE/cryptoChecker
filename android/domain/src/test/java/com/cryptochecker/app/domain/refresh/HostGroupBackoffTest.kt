package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.parity.ParityJson
import com.cryptochecker.app.parity.list
import com.cryptochecker.app.parity.num
import com.cryptochecker.app.parity.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spot und Futures auf demselben Host teilen die Pause nach «zu viele Anfragen» (`host_group_backoff.json`, geteilt
 * mit iOS). Wie `HostGroupBackoffTests.swift`.
 */
class HostGroupBackoffTest {

    private val data = ParityJson.load("host_group_backoff.json")

    private fun state(raw: Any?): ExchangeBackoff.State {
        val s = raw.obj()
        return ExchangeBackoff.State(
            strikes = s["strikes"].num()!!.toInt(),
            pausedUntil = s["pausedUntil"].num()!!.toLong(),
            reason = (s["reason"] as String?)?.let { RefreshFailure.valueOf(it) },
            timeoutRuns = s["timeoutRuns"].num()!!.toInt(),
        )
    }

    private fun states(raw: Any?): Map<String, ExchangeBackoff.State> = raw.obj().mapValues { state(it.value) }

    @Test
    fun hostGroupsMatchFixture() {
        assertEquals(data["hostGroups"], ExchangeBackoff.HOST_GROUPS)
    }

    @Test
    fun parityCases() {
        val now = data["now"].num()!!.toLong()
        data["cases"].list().forEach { raw ->
            val c = raw.obj()
            assertEquals(c["name"] as String, states(c["expected"]),
                ExchangeBackoff.withHostGroupPauses(states(c["states"]), now))
        }
    }

    @Test
    fun rateLimitAtBingXAlsoPausesBingXFutures() {
        val now = 5_000_000L
        val after429 = ExchangeBackoff.next(null, ExchangeBackoff.Outcome.RATE_LIMITED, now)!!
        val stored = ExchangeBackoff.withHostGroupPauses(mapOf("BingX" to after429), now)
        // Nächster Durchlauf: beide Teile übersprungen
        assertTrue(ExchangeBackoff.isPaused(stored["BingX"], now + 1))
        assertTrue(ExchangeBackoff.isPaused(stored["BingXFutures"], now + 1))
        assertEquals(RefreshFailure.RATE_LIMIT, stored["BingXFutures"]?.reason)
        // Andere Börse unberührt
        assertFalse(ExchangeBackoff.isPaused(stored["Bybit"], now + 1))
    }
}
