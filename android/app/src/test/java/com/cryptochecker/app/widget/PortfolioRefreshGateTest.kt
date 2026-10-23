package com.cryptochecker.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wann die Hintergrund-Aktualisierung das Portfolio holt ([PortfolioSnapshotUpdater.shouldRefresh]). */
class PortfolioRefreshGateTest {

    @Test
    fun widgetAlwaysRefreshes() {
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = true, activeAlarms = false))
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = true, activeAlarms = null))
    }

    @Test
    fun activeAlarmWithoutWidgetRefreshes() {
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = false, activeAlarms = true))
    }

    @Test
    fun noWidgetAndNoAlarmSkips() {
        assertFalse(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = false, activeAlarms = false))
    }

    @Test
    fun unreadableAlarmsRefreshAnyway() {
        // Kurz gesperrte Datenbank ist nicht «keine Alarme»: im Zweifel aktualisieren
        assertTrue(PortfolioSnapshotUpdater.shouldRefresh(hasPortfolioWidget = false, activeAlarms = null))
    }
}
