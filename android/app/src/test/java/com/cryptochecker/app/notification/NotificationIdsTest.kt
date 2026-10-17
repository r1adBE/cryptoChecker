package com.cryptochecker.app.notification

import com.cryptochecker.app.notification.NotificationIds.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun smallIdsStayAsBefore() {
        assertEquals(100_001, NotificationIds.id(Kind.PRICE, 1))
        assertEquals(200_042, NotificationIds.id(Kind.ALARM, 42))
        assertEquals(399_999, NotificationIds.id(Kind.ACTIVITY, 99_999))
        assertEquals(400_000, NotificationIds.id(Kind.PORTFOLIO_ALARM, 0))
    }

    @Test
    fun largeIdsDoNotCollideWithOtherKinds() {
        // Früher: Kurs-Meldung von Paar 100 001 = Alarm 1
        assertNotEquals(NotificationIds.id(Kind.ALARM, 1), NotificationIds.id(Kind.PRICE, 100_001))
        val ids = listOf(100_000L, 100_001L, 123_456_789L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE, -1L, 7L)
        val all = Kind.entries.flatMap { kind -> ids.map { NotificationIds.id(kind, it) } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun largeIdsStayPositiveAndAboveFixedRanges() {
        for (kind in Kind.entries) {
            for (id in listOf(100_000L, Long.MAX_VALUE, Long.MIN_VALUE, -5L, 3_000_000_000L)) {
                val n = NotificationIds.id(kind, id)
                assertTrue("$kind $id → $n", n >= NotificationIds.LARGE_START)
            }
        }
    }

    @Test
    fun kindAndSmallIdAreRecoverable() {
        for (kind in Kind.entries) {
            for (id in listOf(0L, 7L, 99_999L, 100_000L, Long.MAX_VALUE, -5L)) {
                val n = NotificationIds.id(kind, id)
                assertTrue("$kind $id", NotificationIds.isKind(kind, n))
                Kind.entries.filter { it != kind }.forEach { other -> assertTrue("$other $id", !NotificationIds.isKind(other, n)) }
            }
        }
        assertEquals(42L, NotificationIds.smallIdOf(Kind.PRICE, NotificationIds.id(Kind.PRICE, 42)))
        assertEquals(null, NotificationIds.smallIdOf(Kind.PRICE, NotificationIds.id(Kind.PRICE, 100_000)))
        assertEquals(null, NotificationIds.smallIdOf(Kind.PRICE, 11))
    }
}
