package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.data.remote.VolumeSpike
import com.cryptochecker.app.domain.alarm.AlarmCheckStatus
import com.cryptochecker.app.domain.alarm.QuietHours
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.NOW
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.above
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.below
import com.cryptochecker.app.domain.refresh.RefreshFixture.Companion.watch
import com.cryptochecker.app.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RefreshEffects.checkAlarms] selbst (nicht ein Nachbau) mit Fakes für Speicher, Mitteilungen und Sprachausgabe:
 * Kreuzen nach oben/unten meldet einmal, eine verworfene Meldung lässt den Alarm scharf, ein inzwischen bearbeiteter
 * Alarm zieht die Meldung zurück, Nachtruhe hält zurück, Alarm-Währung und Live-Kurse.
 */
class RefreshEffectsAlarmTest {

    private val minute = 60_000L

    /** Eine Prüfung wie RefreshResultProcessor: Alarme frisch aus dem Speicher, Paar 1. */
    private fun RefreshFixture.check(
        price: Double,
        previous: Double?,
        at: Long,
        settings: AppSettings = this.settings.settings,
        live: Boolean = false,
        alarms: List<AlarmEntity>? = null,
    ): Int = runBlocking {
        val enabled = alarms ?: store.getAllEnabledAlarms()
        effects.checkAlarms(store.watches.getValue(1), price, previous, enabled, settings, at, live)
    }

    /** Nachtruhe, die jetzt gerade gilt (eine Stunde vor bis eine Stunde nach der aktuellen Minute). */
    private fun quietNow(base: AppSettings): AppSettings {
        val minute = QuietHours.minuteOfDay()
        return base.copy(
            quietHoursEnabled = true,
            quietHoursStart = Math.floorMod(minute - 60, QuietHours.MINUTES_PER_DAY),
            quietHoursEnd = Math.floorMod(minute + 60, QuietHours.MINUTES_PER_DAY),
        )
    }

    @Test
    fun crossingUpFiresOnceUntilThePriceReturns() {
        val f = RefreshFixture(listOf(watch()), listOf(above(100.0, repeating = true)))

        assertEquals(1, f.check(101.0, 99.0, NOW))
        assertEquals(listOf(Triple(7L, 101.0, null as String?)), f.notifier.alarmPosts)
        val marked = f.store.alarm(7)
        assertEquals(NOW, marked.lastTriggeredAt)
        assertEquals(NOW, marked.referenceAt)
        assertTrue("wiederholender Alarm bleibt an", marked.enabled)

        // Kurs bleibt über der Marke: keine zweite Meldung, kein Wiederscharfstellen
        assertEquals(0, f.check(102.0, 101.0, NOW + 5 * minute))
        assertEquals(1, f.notifier.alarmPosts.size)
        assertTrue(f.store.rearmed.isEmpty())

        // Zurück unter die Marke (mehr als die Hysterese): wieder scharf, ohne zu melden
        assertEquals(0, f.check(95.0, 102.0, NOW + 10 * minute))
        assertEquals(listOf(7L), f.store.rearmed)
        assertEquals(0L, f.store.alarm(7).referenceAt)

        // Erneut darüber, nach der Abklingzeit (15 Min.): zweite Meldung
        assertEquals(1, f.check(101.0, 95.0, NOW + 20 * minute))
        assertEquals(2, f.notifier.alarmPosts.size)
    }

    @Test
    fun crossingDownFiresOnceAndOneShotSwitchesOff() {
        val f = RefreshFixture(listOf(watch(price = 101.0)), listOf(below(100.0)))

        assertEquals(1, f.check(99.0, 101.0, NOW))
        assertEquals(listOf(Triple(8L, 99.0, null as String?)), f.notifier.alarmPosts)
        assertFalse("einmaliger Alarm schaltet sich ab", f.store.alarm(8).enabled)

        // Danach nicht mehr unter den eingeschalteten Alarmen: keine zweite Meldung
        assertEquals(0, f.check(98.0, 99.0, NOW + minute))
        assertEquals(1, f.notifier.alarmPosts.size)
    }

    @Test
    fun priceOnTheWrongSideDoesNothing() {
        val f = RefreshFixture(listOf(watch()), listOf(above(100.0), below(90.0)))
        assertEquals(0, f.check(95.0, 99.0, NOW))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(0L, f.store.alarm(7).lastTriggeredAt)
    }

    @Test
    fun discardedNotificationKeepsAlarmArmedAndRetriesAfterCooldown() {
        val original = above(100.0)
        val f = RefreshFixture(listOf(watch()), listOf(original))
        f.notifier.shows = false

        assertEquals(0, f.check(101.0, 99.0, NOW))
        assertEquals(1, f.notifier.alarmPosts.size)
        assertEquals("nicht gespeichert: scharf und eingeschaltet", original, f.store.alarm(7))

        // Gleich danach kein neuer Versuch (Abklingzeit des unbestätigten Versuchs), später schon
        f.notifier.shows = true
        assertEquals(0, f.check(101.0, 101.0, NOW + minute))
        assertEquals(1, f.notifier.alarmPosts.size)
        assertEquals(1, f.check(101.0, 101.0, NOW + 60 * minute))
        assertEquals(2, f.notifier.alarmPosts.size)
        assertEquals(NOW + 60 * minute, f.store.alarm(7).lastTriggeredAt)
    }

    @Test
    fun undeliverableAlarmNeitherPostsNorMarks() {
        val original = above(100.0)
        val f = RefreshFixture(listOf(watch()), listOf(original))
        f.notifier.deliverable = false

        assertEquals(0, f.check(101.0, 99.0, NOW))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(original, f.store.alarm(7))
    }

    @Test
    fun alarmEditedDuringTheRunRetractsTheNotification() {
        val f = RefreshFixture(listOf(watch()), listOf(above(100.0)))
        // Gelesen vor der Änderung des Nutzers …
        val readBefore = runBlocking { f.store.getAllEnabledAlarms() }
        // … dann während der Aktualisierung auf 150 gestellt
        f.store.alarms[7] = f.store.alarm(7).copy(threshold = 150.0)

        assertEquals(0, f.check(101.0, 99.0, NOW, alarms = readBefore))
        assertEquals("gemeldet, dann zurückgezogen", 1, f.notifier.alarmPosts.size)
        assertEquals(listOf(7L), f.notifier.cancelledAlarms)
        // Die Änderung des Nutzers bleibt, nichts als ausgelöst gespeichert
        assertEquals(150.0, f.store.alarm(7).threshold, 0.0)
        assertEquals(0L, f.store.alarm(7).lastTriggeredAt)
    }

    @Test
    fun quietHoursHoldAlarmsArmedAndSilent() {
        val original = above(100.0).copy(speak = true)
        val f = RefreshFixture(listOf(watch()), listOf(original))
        val quiet = quietNow(f.settings.settings.copy(ttsEnabled = true))

        assertEquals(0, f.check(101.0, 99.0, NOW, settings = quiet))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertTrue(f.speaker.spoken.isEmpty())
        assertEquals(original, f.store.alarm(7))

        // Nach der Nachtruhe: meldet sich normal (samt Ansage)
        assertEquals(1, f.check(101.0, 101.0, NOW + minute, settings = quiet.copy(quietHoursEnabled = false)))
        assertEquals(1, f.notifier.alarmPosts.size)
        assertEquals(listOf(true), f.speaker.spoken.map { it.second })
    }

    @Test
    fun snoozeHoldsAlarmArmedLikeQuietHours() {
        val original = above(100.0).copy(speak = true)
        val f = RefreshFixture(listOf(watch()), listOf(original))
        val settings = f.settings.settings.copy(ttsEnabled = true)
        f.notifier.snoozedUntil[7L] = com.cryptochecker.app.domain.alarm.AlarmSnooze.snoozeUntil(NOW)

        // «1 h später» läuft: nicht gemeldet, nicht angesagt, nichts gespeichert — der Alarm bleibt scharf
        assertEquals(0, f.check(101.0, 99.0, NOW + minute, settings = settings))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertTrue(f.speaker.spoken.isEmpty())
        assertEquals(original, f.store.alarm(7))

        // Pause vorbei, Bedingung noch erfüllt: meldet sich beim nächsten Durchlauf
        assertEquals(1, f.check(101.0, 101.0, NOW + 61 * minute, settings = settings))
        assertEquals(1, f.notifier.alarmPosts.size)
    }

    @Test
    fun alarmInOtherCurrencyComparesConvertedPrice() {
        // Schwelle 90 CHF, Paar in USDT, 1 USDT = 0.9 CHF: 101 USDT = 90.9 CHF
        val chf = above(90.0).copy(currency = "CHF")
        val f = RefreshFixture(listOf(watch()), listOf(chf))

        assertEquals(1, f.check(101.0, 99.0, NOW))
        val (id, price, currency) = f.notifier.alarmPosts.single()
        assertEquals(7L, id)
        assertEquals(90.9, price, 1e-9)
        assertEquals("CHF", currency)
        // Gespeichert wird der Kurs in der Quote
        assertEquals(101.0, f.store.alarm(7).lastTriggeredPrice!!, 0.0)
    }

    @Test
    fun missingRateSkipsAlarmAndMarksItUnchecked() {
        val eur = above(90.0).copy(currency = "EUR")
        val f = RefreshFixture(listOf(watch()), listOf(eur))

        assertEquals(0, f.check(101.0, 99.0, NOW))
        assertTrue(f.notifier.alarmPosts.isEmpty())
        assertEquals(AlarmCheckStatus.Reason.NO_RATE, f.checkStatus.state.value[7L]?.reason)
        assertEquals(eur, f.store.alarm(7))
    }

    @Test
    fun liveQuotesSkipVolumeAlarms() {
        val spike = AlarmEntity(id = 9, watchId = 1, condition = AlarmCondition.VOLUME_SPIKE, threshold = 2.0)
        val f = RefreshFixture(listOf(watch()), listOf(spike))
        f.volume.spike = VolumeSpike(ratio = 5.0, candleOpenTime = NOW - 60 * minute)

        assertEquals(0, f.check(101.0, 99.0, NOW, live = true))
        assertEquals("Live-Kurs fragt keine Stundenkerzen ab", 0, f.volume.calls)
        assertTrue(f.notifier.alarmPosts.isEmpty())
    }

    @Test
    fun volumeAlarmWithoutCandlesIsMarkedUnchecked() {
        val spike = AlarmEntity(id = 9, watchId = 1, condition = AlarmCondition.VOLUME_SPIKE, threshold = 2.0)
        val f = RefreshFixture(listOf(watch()), listOf(spike))

        assertEquals(0, f.check(101.0, 99.0, NOW))
        assertEquals(1, f.volume.calls)
        assertEquals(AlarmCheckStatus.Reason.NO_CANDLES, f.checkStatus.state.value[9L]?.reason)
        assertNull(f.notifier.alarmPosts.firstOrNull())
    }
}
