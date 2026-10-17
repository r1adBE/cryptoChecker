package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kette Kurs → Alarm → Meldung mit Fakes: Kursquelle liefert einen Kurs über der Marke, echte
 * Auswertung ([AlarmEvaluator]) und echter Melde-Ablauf ([AlarmDelivery]), Fake-Notifier hält die
 * Meldung fest, Fake-Speicher wendet [AlarmEvaluator.triggered] an (wie WatchRepository.markAlarmTriggered).
 * Spiegel: AlarmChainIntegrationTests.swift.
 */
class AlarmChainIntegrationTest {

    private val now = 1_700_000_000_000L

    /** Kursquelle (statt Börse). */
    private class FakeMarket(var price: Double) {
        suspend fun last(): Double = price
    }

    /** Hält Meldungen fest; [shown] = false wie «von Android verworfen». */
    private class FakeNotifier(var shown: Boolean = true, var deliverable: Boolean = true) {
        val posts = ArrayList<Pair<Long, Double>>()
        fun canShowAlarm(): Boolean = deliverable
        fun showAlarm(alarm: AlarmEntity, price: Double): Boolean {
            posts += alarm.id to price
            return shown
        }
    }

    /** Alarm-Speicher im Speicher. */
    private class FakeStore(alarm: AlarmEntity) {
        var alarm: AlarmEntity = alarm
        fun markTriggered(price: Double, time: Long): Boolean {
            alarm = AlarmEvaluator.triggered(alarm, price, time)
            return true
        }
    }

    /** Ein Durchlauf wie RefreshEffects.checkAlarms für eine Kursmarke. @return ausgelöste Alarme */
    private suspend fun refresh(
        market: FakeMarket,
        previous: Double,
        store: FakeStore,
        notifier: FakeNotifier,
        delivery: AlarmDelivery,
        at: Long,
    ): Int {
        val evaluator = AlarmEvaluator()
        val price = market.last()
        val alarm = store.alarm
        if (evaluator.shouldRearmLevel(alarm, price)) return 0
        if (!evaluator.shouldTrigger(alarm, price, previous, at, cooldownMinutes = 15)) return 0
        val saved = delivery.deliver(
            alarmId = alarm.id,
            now = at,
            cooldownMinutes = 15,
            holdAlarms = false,
            canShow = { notifier.canShowAlarm() },
            show = { notifier.showAlarm(alarm, price) },
            mark = { store.markTriggered(price, at) },
        )
        return if (saved) 1 else 0
    }

    private fun alarmAbove(level: Double) =
        AlarmEntity(id = 7, watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = level)

    @Test
    fun crossingPostsOnceAndMarksAlarm() = runBlocking {
        val store = FakeStore(alarmAbove(100.0))
        val notifier = FakeNotifier()
        val delivery = AlarmDelivery()
        val market = FakeMarket(101.0)

        assertEquals(1, refresh(market, 99.0, store, notifier, delivery, now))
        assertEquals(listOf(7L to 101.0), notifier.posts)
        assertEquals(now, store.alarm.lastTriggeredAt)
        assertTrue(store.alarm.referenceAt > 0)
        // Einmaliger Alarm: danach aus
        assertFalse(store.alarm.enabled)

        // Nächster Durchlauf, Kurs bleibt über der Marke: keine zweite Meldung
        assertEquals(0, refresh(market, 101.0, store, notifier, delivery, now + 60_000))
        assertEquals(1, notifier.posts.size)
    }

    @Test
    fun notShownLeavesAlarmArmed() = runBlocking {
        val original = alarmAbove(100.0)
        val store = FakeStore(original)
        val notifier = FakeNotifier(shown = false)
        val delivery = AlarmDelivery()
        val market = FakeMarket(101.0)

        assertEquals(0, refresh(market, 99.0, store, notifier, delivery, now))
        assertEquals(1, notifier.posts.size)
        // Nicht gespeichert: bleibt scharf und eingeschaltet
        assertEquals(original, store.alarm)

        // Gleich danach: kein erneuter Versuch (Abklingzeit), später schon — dann gespeichert
        notifier.shown = true
        assertEquals(0, refresh(market, 99.0, store, notifier, delivery, now + 60_000))
        assertEquals(1, notifier.posts.size)
        val later = now + 60 * 60_000L
        assertEquals(1, refresh(market, 99.0, store, notifier, delivery, later))
        assertEquals(2, notifier.posts.size)
        assertEquals(later, store.alarm.lastTriggeredAt)
    }

    @Test
    fun undeliverableNeitherPostsNorMarks() = runBlocking {
        val original = alarmAbove(100.0)
        val store = FakeStore(original)
        val notifier = FakeNotifier(deliverable = false)

        assertEquals(0, refresh(FakeMarket(101.0), 99.0, store, notifier, AlarmDelivery(), now))
        assertTrue(notifier.posts.isEmpty())
        assertEquals(original, store.alarm)
    }

    @Test
    fun priceBelowLevelDoesNothing() = runBlocking {
        val store = FakeStore(alarmAbove(100.0))
        val notifier = FakeNotifier()
        assertEquals(0, refresh(FakeMarket(99.5), 99.0, store, notifier, AlarmDelivery(), now))
        assertTrue(notifier.posts.isEmpty())
    }
}
