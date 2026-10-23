package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.notification.AlarmDeliveryMarkers
import com.cryptochecker.app.notification.PortfolioAlarmDeliveryMarkers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Review ARC2-02: Portfolio-Alarme laufen über [AlarmDelivery] mit eigenem Vermerk-Speicher (wie iOS
 * `AlarmDelivery.portfolio`). Gegenstück: `Tests/AlarmDeliveryB104Tests.swift`.
 */
class AlarmDeliveryB104Test {

    private val at = 1_700_000_000_000L

    @Test
    fun portfolioVermerkeInEigenerDatei() {
        assertNotEquals(AlarmDeliveryMarkers.PREFS, PortfolioAlarmDeliveryMarkers.PREFS)
    }

    /** Paar- und Portfolio-Alarm mit derselben Id stören sich nicht. */
    @Test
    fun portfolioVermerkGetrenntVonPaarVermerk() = runBlocking {
        val pairs = AlarmDeliveryMarkerStore.InMemory()
        val portfolio = AlarmDeliveryMarkerStore.InMemory()
        AlarmDelivery(portfolio).deliver(
            alarmId = 5, now = at, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { false }, mark = { true },
        )
        assertEquals(at, portfolio.get(5))
        assertNull(pairs.get(5))
        pairs.retain(emptySet())
        assertEquals(at, portfolio.get(5))
    }

    /** Portfolio-Alarm: schon gemeldet (Prozess-Ende), aber inzwischen geändert (markTriggered 0 Zeilen) → Mitteilung weg. */
    @Test
    fun portfolioSchonGemeldetNichtGespeichertEntfernt() = runBlocking {
        val portfolio = AlarmDeliveryMarkerStore.InMemory()
        portfolio.set(6, at)
        var retracted = 0
        val result = AlarmDelivery(portfolio).deliver(
            alarmId = 6, now = at + 30_000L, cooldownMinutes = 0, holdAlarms = false,
            canShow = { true }, show = { true }, mark = { false }, retract = { retracted++ },
            isShown = { markerAt -> AlarmDeliveryMarker.shownSinceMarker(at + 100L, markerAt) },
        )
        assertFalse(result)
        assertEquals(1, retracted)
        assertNull(portfolio.get(6))
    }
}
