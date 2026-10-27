package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.data.BackupRestoreLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Obergrenzen der Merkliste — gleiche Fälle wie `WatchlistLimitsTests`. */
class WatchlistLimitsTest {

    @Test
    fun grenzenWieEntschieden() {
        assertEquals(2_000, WatchlistLimits.MAX_PAIRS)
        assertEquals(10, WatchlistLimits.MAX_ALARMS_PER_PAIR)
        // Eine Quelle: die Sicherung nimmt dieselben Grenzen
        assertEquals(WatchlistLimits.MAX_PAIRS, BackupRestoreLimits.MAX_PAIRS)
        assertEquals(WatchlistLimits.MAX_ALARMS_PER_PAIR, BackupRestoreLimits.MAX_ALARMS_PER_PAIR)
    }

    @Test
    fun zehnterAlarmJaElfterNein() {
        assertTrue(WatchlistLimits.canAddAlarm(0))
        assertTrue(WatchlistLimits.canAddAlarm(9))
        assertFalse(WatchlistLimits.canAddAlarm(10))
        // Alte Daten über der Grenze: weiterhin kein neuer
        assertFalse(WatchlistLimits.canAddAlarm(12))
    }

    @Test
    fun paarNurUnterDerGrenze() {
        assertTrue(WatchlistLimits.canAddPair(0))
        assertTrue(WatchlistLimits.canAddPair(1_999))
        assertFalse(WatchlistLimits.canAddPair(2_000))
        assertFalse(WatchlistLimits.canAddPair(2_500))
    }

    @Test
    fun freiePlaetzeNieNegativ() {
        assertEquals(2_000, WatchlistLimits.freePairSlots(0))
        assertEquals(1, WatchlistLimits.freePairSlots(1_999))
        assertEquals(0, WatchlistLimits.freePairSlots(2_000))
        assertEquals(0, WatchlistLimits.freePairSlots(2_100))
    }
}
