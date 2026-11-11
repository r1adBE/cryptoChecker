package com.cryptochecker.app.domain.watch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ziel der Glocke im Merklisten-Kopf — gleiche Fälle wie `AlarmBellRouteTests`. */
class AlarmBellRouteTest {

    @Test
    fun withAlarmsAlwaysOverview() {
        assertEquals(AlarmBellRoute.OVERVIEW, AlarmBellRoute.of(alarmCount = 1, watchCount = 0))
        assertEquals(AlarmBellRoute.OVERVIEW, AlarmBellRoute.of(alarmCount = 1, watchCount = 1))
        assertEquals(AlarmBellRoute.OVERVIEW, AlarmBellRoute.of(alarmCount = 7, watchCount = 12))
    }

    @Test
    fun notLoadedYetStaysOnOverview() {
        // Früher Tipp vor dem ersten Stand der Datenbank: nie ein falscher Sprung
        assertEquals(AlarmBellRoute.OVERVIEW, AlarmBellRoute.of(alarmCount = null, watchCount = 0))
        assertEquals(AlarmBellRoute.OVERVIEW, AlarmBellRoute.of(alarmCount = null, watchCount = 3))
    }

    @Test
    fun withoutAlarmsLeadsToCreatingOne() {
        assertEquals(AlarmBellRoute.EDITOR, AlarmBellRoute.of(alarmCount = 0, watchCount = 1))
        assertEquals(AlarmBellRoute.PICK_PAIR, AlarmBellRoute.of(alarmCount = 0, watchCount = 2))
        assertEquals(AlarmBellRoute.PICK_PAIR, AlarmBellRoute.of(alarmCount = 0, watchCount = 40))
        assertEquals(AlarmBellRoute.ADD_COIN, AlarmBellRoute.of(alarmCount = 0, watchCount = 0))
    }
}
