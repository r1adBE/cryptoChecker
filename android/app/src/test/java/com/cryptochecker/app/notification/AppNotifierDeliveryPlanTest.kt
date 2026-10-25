package com.cryptochecker.app.notification

import com.cryptochecker.app.notification.AppNotifierDeliveryPlan.AlarmRoomInput
import com.cryptochecker.app.notification.AppNotifierDeliveryPlan.Channel
import com.cryptochecker.app.notification.AppNotifierDeliveryPlan.GroupCount
import com.cryptochecker.app.notification.AppNotifierDeliveryPlan.GroupEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entscheidungen der Zustellung ohne Systemdienst: Platz für einen Alarm, Zusammenfassung der Gruppe,
 * Sammelmeldung und Kanalwahl ([AppNotifierDeliveryPlan]).
 */
class AppNotifierDeliveryPlanTest {

    private val alarmId = NotificationIds.id(NotificationIds.Kind.ALARM, 99)

    private fun prices(n: Int): List<Pair<Long, String>> = (1L..n).map { it to "P$it" }

    private fun input(
        total: Int,
        prices: List<Pair<Long, String>> = emptyList(),
        overflow: Map<Long, String> = emptyMap(),
        overflowShown: Boolean = false,
        activity: List<Int> = emptyList(),
        alarms: List<Int> = emptyList(),
        alarmShown: Boolean = false,
        summaryShown: Boolean = true,
    ) = AlarmRoomInput(total, alarmShown, summaryShown, prices, overflow, overflowShown, activity, alarms, alarmId)

    @Test
    fun `enough room means nothing happens`() {
        assertNull(AppNotifierDeliveryPlan.alarmRoom(input(total = 30, prices = prices(20))))
        // Gleicher Alarm steht schon da (wird ersetzt), Zusammenfassung auch: braucht keinen Platz
        assertNull(AppNotifierDeliveryPlan.alarmRoom(input(total = 48, alarmShown = true)))
    }

    @Test
    fun `oldest price notifications move into the existing overflow`() {
        val plan = AppNotifierDeliveryPlan.alarmRoom(
            input(total = 49, prices = prices(20), overflow = linkedMapOf(30L to "X"), overflowShown = true)
        )!!
        // 49 + Alarm − 48 = 2 Plätze: die zwei ältesten (am Ende der Liste) wandern
        assertEquals(listOf(19L, 20L), plan.demote)
        assertEquals(listOf(19L, 20L, 30L), plan.overflowLines!!.keys.toList())
        assertTrue(plan.cancelActivity.isEmpty())
        assertFalse(plan.removeOverflow)
        assertTrue(plan.evictAlarms.isEmpty())
    }

    @Test
    fun `a new overflow costs one extra slot`() {
        val plan = AppNotifierDeliveryPlan.alarmRoom(input(total = 49, prices = prices(20)))!!
        assertEquals(listOf(18L, 19L, 20L), plan.demote)
        assertEquals(3, plan.overflowLines!!.size)
    }

    @Test
    fun `too few prices are removed without overflow, then activity, then oldest alarms`() {
        val activity = listOf(NotificationIds.id(NotificationIds.Kind.ACTIVITY, 1))
        val alarms = listOf(1L, 2L, 3L).map { NotificationIds.id(NotificationIds.Kind.ALARM, it) }
        // 51 + Alarm − 48 = 4 Plätze: 1 Kurs (ohne Sammelmeldung), 1 Aktivität, 2 älteste Alarme
        val plan = AppNotifierDeliveryPlan.alarmRoom(
            input(total = 51, prices = prices(1), activity = activity, alarms = alarms)
        )!!
        assertEquals(listOf(1L), plan.demote)
        assertNull(plan.overflowLines)
        assertEquals(activity, plan.cancelActivity)
        assertFalse(plan.removeOverflow)
        assertEquals(alarms.take(2), plan.evictAlarms)
    }

    @Test
    fun `overflow itself goes before alarms`() {
        val alarms = listOf(NotificationIds.id(NotificationIds.Kind.ALARM, 1))
        val plan = AppNotifierDeliveryPlan.alarmRoom(
            input(total = 49, overflow = linkedMapOf(5L to "E"), overflowShown = true, alarms = alarms)
        )!!
        assertTrue(plan.demote.isEmpty())
        assertNull(plan.overflowLines)
        assertTrue(plan.removeOverflow)
        assertEquals(alarms, plan.evictAlarms)
    }

    @Test
    fun `the new alarm itself is never evicted`() {
        val full = input(total = 50, alarms = listOf(alarmId), summaryShown = false)
        val plan = AppNotifierDeliveryPlan.alarmRoom(full)!!
        assertTrue(plan.evictAlarms.isEmpty())
    }

    @Test
    fun `merge keeps moved titles first, skips empty ones and keeps previous lines`() {
        val lines = AppNotifierDeliveryPlan.mergeOverflow(
            listOf(1L to "A", 2L to "", 3L to "C"),
            linkedMapOf(3L to "C alt", 4L to "D"),
        )
        assertEquals(listOf(1L, 3L, 4L), lines.keys.toList())
        assertEquals("C alt", lines[3L])
    }

    @Test
    fun `overflow shows five lines and an ellipsis`() {
        assertEquals(listOf("a", "b"), AppNotifierDeliveryPlan.overflowInboxLines(listOf("a", "b")))
        assertEquals(
            listOf("1", "2", "3", "4", "5", "…"),
            AppNotifierDeliveryPlan.overflowInboxLines(listOf("1", "2", "3", "4", "5", "6", "7")),
        )
        assertEquals(5, AppNotifierDeliveryPlan.overflowInboxLines((1..5).map { "$it" }).size)
    }

    @Test
    fun `overflow lines prefer fresh prices, then previous lines, and drop cancelled or empty ones`() {
        val lines = AppNotifierDeliveryPlan.overflowLines(
            overflow = listOf(1L, 2L, 3L, 4L, 5L),
            cancelled = setOf(2L),
            previousOverflow = mapOf(3L to "alt 3", 1L to "alt 1"),
            previousPrices = mapOf(4L to "einzeln 4"),
        ) { id -> if (id == 1L) "frisch 1" else null }
        assertEquals(linkedMapOf(1L to "frisch 1", 3L to "alt 3", 4L to "einzeln 4"), lines)
    }

    @Test
    fun `group count skips summary, other groups and excluded ids`() {
        val key = "g"
        val entries = listOf(
            GroupEntry(10, key, summary = true, ongoing = true),
            GroupEntry(1, key, summary = false, ongoing = false),
            GroupEntry(2, key, summary = false, ongoing = false),
            GroupEntry(3, "other", summary = false, ongoing = false),
            GroupEntry(4, null, summary = false, ongoing = false),
        )
        assertEquals(GroupCount(1, true), AppNotifierDeliveryPlan.groupCount(entries, key, 10, exclude = setOf(2)))
        assertEquals(GroupCount(0, null), AppNotifierDeliveryPlan.groupCount(entries.drop(1), key, 10, setOf(1, 2)))
    }

    @Test
    fun `summary is posted from the second notification and only when missing or ongoing changes`() {
        // Erste Mitteilung der Gruppe: keine Zusammenfassung
        assertFalse(AppNotifierDeliveryPlan.repostSummary(GroupCount(0, null), ongoing = false))
        assertTrue(AppNotifierDeliveryPlan.repostSummary(GroupCount(1, null), ongoing = false))
        assertFalse(AppNotifierDeliveryPlan.repostSummary(GroupCount(3, false), ongoing = false))
        assertTrue(AppNotifierDeliveryPlan.repostSummary(GroupCount(3, false), ongoing = true))
    }

    @Test
    fun `summary is removed only when the group is known to be empty`() {
        assertTrue(AppNotifierDeliveryPlan.cancelSummary(0))
        assertFalse(AppNotifierDeliveryPlan.cancelSummary(1))
        assertFalse(AppNotifierDeliveryPlan.cancelSummary(null))
    }

    @Test
    fun `quiet hours pick the silent channel, otherwise alarm signal or market`() {
        assertEquals(Channel.QUIET_ALARM, AppNotifierDeliveryPlan.alarmChannel(quiet = true))
        assertEquals(Channel.ALARM_SIGNAL, AppNotifierDeliveryPlan.alarmChannel(quiet = false))
        assertEquals(Channel.QUIET_ALARM, AppNotifierDeliveryPlan.marketChannel(quiet = true))
        assertEquals(Channel.MARKET, AppNotifierDeliveryPlan.marketChannel(quiet = false))
    }

    @Test
    fun `channel delivers unless muted`() {
        val none = 0
        assertTrue(AppNotifierDeliveryPlan.channelDelivers(null, none))
        assertTrue(AppNotifierDeliveryPlan.channelDelivers(4, none))
        assertFalse(AppNotifierDeliveryPlan.channelDelivers(none, none))
    }
}
