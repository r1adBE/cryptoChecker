package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.domain.alarm.AlarmCardStatus.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Statuszeile der Alarm-Karte: Reihenfolge der Gründe, Grenzen, ausgeschaltete Alarme. Wie iOS. */
class AlarmCardStatusTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val stale = 45 * minute

    private fun status(
        enabled: Boolean = true,
        allowed: Boolean = true,
        dex: Boolean = false,
        exchangeTime: Long? = null,
        lastUpdate: Long = now - minute,
        unchecked: Boolean = false,
    ) = AlarmCardStatus.of(enabled, allowed, dex, exchangeTime, lastUpdate, now, stale, unchecked)

    @Test
    fun healthyAlarmHasNoLine() {
        assertNull(status())
        assertNull(status(exchangeTime = now - hour))
    }

    @Test
    fun disabledAlarmNeverHasALine() {
        assertNull(status(enabled = false, allowed = false, dex = true, lastUpdate = now - 10 * hour, unchecked = true))
    }

    @Test
    fun notificationsOffComesFirst() {
        assertEquals(Kind.NOTIFICATIONS_OFF, status(allowed = false, dex = true, lastUpdate = now - 10 * hour, unchecked = true))
    }

    @Test
    fun lowLiquidityBeforeStaleAndUnchecked() {
        assertEquals(Kind.LOW_LIQUIDITY, status(dex = true, exchangeTime = now - 3 * hour, unchecked = true))
    }

    @Test
    fun exchangeTimeOlderThanTwoHoursWaits() {
        assertEquals(Kind.WAITING_FOR_PRICE, status(exchangeTime = now - 2 * hour - 1, unchecked = true))
        assertNull(status(exchangeTime = now - 2 * hour))
    }

    @Test
    fun fetchOlderThanStaleThresholdWaits() {
        assertEquals(Kind.WAITING_FOR_PRICE, status(lastUpdate = now - stale - 1))
        assertNull(status(lastUpdate = now - stale))
        // Noch nie abgerufen: kein «Wartet» (dort steht ohnehin «—»)
        assertNull(status(lastUpdate = 0L))
    }

    @Test
    fun uncheckedOnlyWhenNothingElseApplies() {
        assertEquals(Kind.UNCHECKED, status(unchecked = true))
    }
}
