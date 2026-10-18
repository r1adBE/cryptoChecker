package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.refresh.RefreshFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kette Kurs → Alarm → Meldung über den Produktionscode: Die Kursquelle liefert einen Kurs über der Marke,
 * geprüft und gemeldet wird von [com.cryptochecker.app.domain.refresh.RefreshEffects.checkAlarms] selbst (echte
 * Auswertung [AlarmEvaluator], echter Melde-Ablauf [AlarmDelivery]); nur Speicher und Notifier sind Fakes
 * (RefreshFakes.kt — der Speicher wendet [AlarmEvaluator.triggered] an wie WatchRepository.markAlarmTriggered).
 * Spiegel: AlarmChainIntegrationTests.swift.
 */
class AlarmChainIntegrationTest {

    private val now = 1_700_000_000_000L

    /** Kursquelle (statt Börse). */
    private class FakeMarket(var price: Double) {
        suspend fun last(): Double = price
    }

    /** Ein Durchlauf für eine Kursmarke mit Abklingzeit 15 Min. @return ausgelöste Alarme */
    private fun RefreshFixture.refresh(market: FakeMarket, previous: Double, at: Long): Int = runBlocking {
        val price = market.last()
        effects.checkAlarms(store.watches.getValue(1), price, previous, store.getAllEnabledAlarms(), settings.settings, at)
    }

    private fun alarmAbove(level: Double) =
        AlarmEntity(id = 7, watchId = 1, condition = AlarmCondition.PRICE_ABOVE, threshold = level)

    private fun fixture(alarm: AlarmEntity) = RefreshFixture(listOf(RefreshFixture.watch(price = 99.0)), listOf(alarm))

    @Test
    fun crossingPostsOnceAndMarksAlarm() {
        val f = fixture(alarmAbove(100.0))
        val market = FakeMarket(101.0)

        assertEquals(1, f.refresh(market, 99.0, now))
        assertEquals(listOf(7L to 101.0), f.notifier.alarmPosts.map { it.first to it.second })
        assertEquals(now, f.store.alarm(7).lastTriggeredAt)
        assertTrue(f.store.alarm(7).referenceAt > 0)
        // Einmaliger Alarm: danach aus
        assertFalse(f.store.alarm(7).enabled)

        // Nächster Durchlauf, Kurs bleibt über der Marke: keine zweite Meldung
        assertEquals(0, f.refresh(market, 101.0, now + 60_000))
        assertEquals(1, f.notifier.alarmPosts.size)
    }

    @Test
    fun notShownLeavesAlarmArmed() {
        val original = alarmAbove(100.0)
        val f = fixture(original)
        f.notifier.shows = false
        val market = FakeMarket(101.0)

        assertEquals(0, f.refresh(market, 99.0, now))
        assertEquals(1, f.notifier.alarmPosts.size)
        // Nicht gespeichert: bleibt scharf und eingeschaltet
        assertEquals(original, f.store.alarm(7))

        // Gleich danach: kein erneuter Versuch (Abklingzeit), später schon — dann gespeichert
        f.notifier.shows = true
        assertEquals(0, f.refresh(market, 99.0, now + 60_000))
        assertEquals(1, f.notifier.alarmPosts.size)
        val later = now + 60 * 60_000L
        assertEquals(1, f.refresh(market, 99.0, later))
        assertEquals(2, f.notifier.alarmPosts.size)
        assertEquals(later, f.store.alarm(7).lastTriggeredAt)
    }

    @Test
    fun undeliverableNeitherPostsNorMarks() {
        val original = alarmAbove(100.0)
        val f = fixture(original)
        f.notifier.deliverable = false

        assertEquals(0, f.refresh(FakeMarket(101.0), 99.0, now))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(original, f.store.alarm(7))
    }

    @Test
    fun priceBelowLevelDoesNothing() {
        val f = fixture(alarmAbove(100.0))
        assertEquals(0, f.refresh(FakeMarket(99.5), 99.0, now))
        assertTrue(f.notifier.alarmPosts.isEmpty())
    }
}
