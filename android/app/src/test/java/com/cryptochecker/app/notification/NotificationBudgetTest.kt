package com.cryptochecker.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 12 (M1): höchstens 20 Kurs-Mitteilungen einzeln, Rest in der Sammelmeldung, Alarme haben Vorrang. */
class NotificationBudgetTest {

    @Test
    fun `price slots are capped at 20 and leave room for alarms`() {
        assertEquals(20, NotificationBudget.priceSlots(0))
        assertEquals(20, NotificationBudget.priceSlots(18))
        assertEquals(10, NotificationBudget.priceSlots(28))
        assertEquals(0, NotificationBudget.priceSlots(45))
        assertEquals(20, NotificationBudget.priceSlots(-3))
    }

    @Test
    fun `first run with many pairs shows 20 and puts the rest in the overflow`() {
        val posts = (1L..120L).toList()
        val plan = NotificationBudget.planPrices(emptyList(), emptyList(), posts, 20)
        assertEquals((1L..20L).toList(), plan.individual)
        assertEquals((21L..120L).toList(), plan.overflow)
        assertTrue(plan.demoted.isEmpty())
    }

    @Test
    fun `shown pairs keep their slot, new ones go to the overflow when full`() {
        val shown = (1L..20L).toList()
        val plan = NotificationBudget.planPrices(shown, listOf(30L), listOf(5L, 25L), 20)
        assertEquals(listOf(5L), plan.individual)
        // Neueste zuerst, bisherige bleiben
        assertEquals(listOf(25L, 30L), plan.overflow)
    }

    @Test
    fun `overflow pair comes back individually when a slot is free`() {
        val plan = NotificationBudget.planPrices(listOf(1L, 2L), listOf(7L, 8L), listOf(8L), 20)
        assertEquals(listOf(8L), plan.individual)
        assertEquals(listOf(7L), plan.overflow)
    }

    @Test
    fun `fewer slots demote the surplus into the overflow`() {
        val plan = NotificationBudget.planPrices(listOf(1L, 2L, 3L, 4L), emptyList(), listOf(4L), 2)
        assertEquals(listOf(3L, 4L), plan.demoted)
        assertTrue(plan.individual.isEmpty())
        assertEquals(listOf(4L, 3L), plan.overflow)
    }

    @Test
    fun `alarm needs room only near the system limit`() {
        assertEquals(0, NotificationBudget.neededForAlarm(30, alarmShown = false, summaryShown = false))
        assertEquals(0, NotificationBudget.neededForAlarm(47, alarmShown = false, summaryShown = true))
        assertEquals(1, NotificationBudget.neededForAlarm(47, alarmShown = false, summaryShown = false))
        assertEquals(0, NotificationBudget.neededForAlarm(48, alarmShown = true, summaryShown = true))
        assertEquals(3, NotificationBudget.neededForAlarm(50, alarmShown = false, summaryShown = true))
    }

    @Test
    fun `room for alarm comes from price notifications first`() {
        // Sammelmeldung offen: genau so viele verschieben
        assertEquals(NotificationBudget.AlarmRoom(2, true, 0), NotificationBudget.roomForAlarm(2, 20, overflowShown = true))
        // Neue Sammelmeldung kostet einen Platz
        assertEquals(NotificationBudget.AlarmRoom(3, true, 0), NotificationBudget.roomForAlarm(2, 20, overflowShown = false))
        // Zu wenige Kurs-Mitteilungen: ganz entfernen, Rest fehlt noch
        assertEquals(NotificationBudget.AlarmRoom(1, false, 1), NotificationBudget.roomForAlarm(2, 1, overflowShown = false))
        assertEquals(NotificationBudget.AlarmRoom(0, true, 2), NotificationBudget.roomForAlarm(2, 0, overflowShown = true))
        assertEquals(NotificationBudget.AlarmRoom(0, true, 0), NotificationBudget.roomForAlarm(0, 20, overflowShown = false))
    }

    @Test
    fun `unconfirmed alarm waits for the cooldown before retrying`() {
        val t = 1_000_000_000L
        assertFalse(NotificationBudget.retryBlocked(null, t, 15))
        assertTrue(NotificationBudget.retryBlocked(t, t + 14 * 60_000L, 15))
        assertFalse(NotificationBudget.retryBlocked(t, t + 15 * 60_000L, 15))
        // Ohne Abklingzeit mindestens 5 Minuten
        assertTrue(NotificationBudget.retryBlocked(t, t + 4 * 60_000L, 0))
        assertFalse(NotificationBudget.retryBlocked(t, t + 5 * 60_000L, 0))
        // Uhr zurückgestellt: nicht blockieren
        assertFalse(NotificationBudget.retryBlocked(t, t - 1, 15))
    }
}
