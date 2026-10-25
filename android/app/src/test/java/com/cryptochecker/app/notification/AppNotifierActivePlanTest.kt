package com.cryptochecker.app.notification

import com.cryptochecker.app.notification.AppNotifierDeliveryPlan.ActiveEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aufteilen der offenen Mitteilungen für das Mitteilungs-Budget ([AppNotifierDeliveryPlan.splitActive] u. a.). */
class AppNotifierActivePlanTest {

    private val summaryId = 9
    private val overflowId = 11

    private fun price(watchId: Long?, postTime: Long, title: String = "P$watchId") =
        ActiveEntry(NotificationIds.id(NotificationIds.Kind.PRICE, watchId ?: 77), postTime, true, watchId, title)

    @Test
    fun `split sorts prices newest first and counts the rest as other`() {
        val activityOld = ActiveEntry(NotificationIds.id(NotificationIds.Kind.ACTIVITY, 1), 5, false)
        val activityNew = ActiveEntry(NotificationIds.id(NotificationIds.Kind.ACTIVITY, 2), 50, false)
        val alarm = ActiveEntry(NotificationIds.id(NotificationIds.Kind.ALARM, 3), 40, false)
        val portfolio = ActiveEntry(NotificationIds.id(NotificationIds.Kind.PORTFOLIO_ALARM, 4), 30, false)
        val fng = ActiveEntry(4, 1, false) // feste Nummer (Fear & Greed)
        val split = AppNotifierDeliveryPlan.splitActive(
            listOf(
                price(1, 10), price(2, 20), price(null, 15),
                ActiveEntry(summaryId, 99, false), ActiveEntry(overflowId, 3, false),
                activityNew, alarm, activityOld, portfolio, fng,
            ),
            summaryId, overflowId,
        )
        assertEquals(listOf(2L, 1L), split.listed.keys.toList())
        assertEquals("P1", split.listed[1L])
        assertTrue(split.overflowListed)
        // Kurs ohne erkennbares Paar, 2 Aktivität, 2 Alarme, Fear & Greed — Zusammenfassung und Sammelmeldung nicht
        assertEquals(6, split.other)
        assertEquals(listOf(activityOld.id, activityNew.id), split.activity)
        assertEquals(listOf(portfolio.id, alarm.id), split.alarms)
    }

    @Test
    fun `split without overflow`() {
        val split = AppNotifierDeliveryPlan.splitActive(listOf(price(1, 1)), summaryId, overflowId)
        assertFalse(split.overflowListed)
        assertEquals(0, split.other)
    }

    @Test
    fun `just posted prices come first, newest first, without duplicates`() {
        val prices = AppNotifierDeliveryPlan.orderPrices(
            recent = mapOf(5L to (100L to "neu 5"), 6L to (200L to "neu 6"), 1L to (300L to "neu 1")),
            listed = linkedMapOf(1L to "B1", 2L to "B2"),
        )
        assertEquals(listOf(6L, 5L, 1L, 2L), prices.keys.toList())
        // Geführte Mitteilung hat den Titel des Systemdienstes
        assertEquals("B1", prices[1L])
    }

    @Test
    fun `overflow counts as shown while listed or just posted`() {
        assertTrue(AppNotifierDeliveryPlan.overflowShown(true, false, now = 0, postedAt = 0, recentMillis = 5_000))
        assertTrue(AppNotifierDeliveryPlan.overflowShown(false, true, now = 4_999, postedAt = 0, recentMillis = 5_000))
        assertFalse(AppNotifierDeliveryPlan.overflowShown(false, true, now = 5_000, postedAt = 0, recentMillis = 5_000))
        assertFalse(AppNotifierDeliveryPlan.overflowShown(false, false, now = 1, postedAt = 0, recentMillis = 5_000))
        // Uhr zurückgestellt: nicht mehr «eben gemeldet»
        assertFalse(AppNotifierDeliveryPlan.overflowShown(false, true, now = -1, postedAt = 0, recentMillis = 5_000))
    }
}
