package com.cryptochecker.app.work

import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.refresh.CheckBudget
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.work.BackgroundWorkPolicy.ExactAction
import com.cryptochecker.app.work.BackgroundWorkPolicy.FiredAction
import com.cryptochecker.app.work.BackgroundWorkPolicy.WorkOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BackgroundWorkPolicyTest {

    private val now = 1_700_000_000_000L
    private val fifteenMin = 15 * 60_000L

    // ── ExactCheckScheduler.sync ────────────────────────────────────────────

    @Test
    fun exactOnWithPermissionArmsInFifteenMinutes() {
        val action = BackgroundWorkPolicy.exactAction(enabled = true, permitted = true, hasWork = true, armedFor = 0, now = now)
        assertEquals(ExactAction.Arm(now + fifteenMin), action)
    }

    @Test
    fun permissionRevokedCancelsSoWorkManagerAlone() {
        // Auch ein gestellter Wecker wird abgesagt; WorkManager plant PriceUpdateScheduler unabhängig davon
        val action = BackgroundWorkPolicy.exactAction(enabled = true, permitted = false, hasWork = true, armedFor = now + 60_000, now = now)
        assertEquals(ExactAction.Cancel, action)
        assertEquals(
            ExactAction.Cancel,
            BackgroundWorkPolicy.exactAction(enabled = true, permitted = false, hasWork = true, armedFor = 0, now = now, force = true),
        )
    }

    @Test
    fun switchedOffCancels() {
        assertEquals(
            ExactAction.Cancel,
            BackgroundWorkPolicy.exactAction(enabled = false, permitted = true, hasWork = true, armedFor = now + 60_000, now = now),
        )
    }

    @Test
    fun noActivePairsDoesNotArm() {
        assertEquals(
            ExactAction.Cancel,
            BackgroundWorkPolicy.exactAction(enabled = true, permitted = true, hasWork = false, armedFor = 0, now = now),
        )
    }

    @Test
    fun armedAlarmIsKeptUnlessForcedOrStale() {
        // Einstellungen geändert: Takt nicht hinausschieben
        assertEquals(
            ExactAction.Keep,
            BackgroundWorkPolicy.exactAction(enabled = true, permitted = true, hasWork = true, armedFor = now + 60_000, now = now),
        )
        // Neustart/Update/Berechtigung: Android hat ihn gelöscht → neu ab jetzt
        assertEquals(
            ExactAction.Arm(now + fifteenMin),
            BackgroundWorkPolicy.exactAction(enabled = true, permitted = true, hasWork = true, armedFor = now + 60_000, now = now, force = true),
        )
        // Abgelaufen bzw. Uhr zurückgestellt
        assertEquals(
            ExactAction.Arm(now + fifteenMin),
            BackgroundWorkPolicy.exactAction(enabled = true, permitted = true, hasWork = true, armedFor = now - 1, now = now),
        )
        assertEquals(
            ExactAction.Arm(now + fifteenMin),
            BackgroundWorkPolicy.exactAction(enabled = true, permitted = true, hasWork = true, armedFor = now + fifteenMin + 1, now = now),
        )
    }

    // ── ExactCheckReceiver ──────────────────────────────────────────────────

    @Test
    fun firedAlarm() {
        assertEquals(FiredAction.REARM_AND_CHECK, BackgroundWorkPolicy.firedAction(enabled = true, permitted = true, hasWork = true, online = true))
        assertEquals(FiredAction.REARM_SKIP_OFFLINE, BackgroundWorkPolicy.firedAction(enabled = true, permitted = true, hasWork = true, online = false))
        assertEquals(FiredAction.CANCEL, BackgroundWorkPolicy.firedAction(enabled = true, permitted = false, hasWork = true, online = true))
        assertEquals(FiredAction.CANCEL, BackgroundWorkPolicy.firedAction(enabled = true, permitted = true, hasWork = false, online = true))
        assertEquals(FiredAction.CANCEL, BackgroundWorkPolicy.firedAction(enabled = false, permitted = true, hasWork = true, online = true))
    }

    // ── BootReceiver ────────────────────────────────────────────────────────

    @Test
    fun systemBroadcastsReschedule() {
        assertTrue(BackgroundWorkPolicy.reschedulesOn("android.intent.action.BOOT_COMPLETED"))
        assertTrue(BackgroundWorkPolicy.reschedulesOn("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(BackgroundWorkPolicy.reschedulesOn("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
    }

    @Test
    fun otherBroadcastsIgnored() {
        assertFalse(BackgroundWorkPolicy.reschedulesOn(null))
        assertFalse(BackgroundWorkPolicy.reschedulesOn(""))
        // Ungeschützt (Hersteller): bewusst nicht
        assertFalse(BackgroundWorkPolicy.reschedulesOn("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(BackgroundWorkPolicy.reschedulesOn("android.intent.action.PACKAGE_REPLACED"))
        assertEquals(3, BackgroundWorkPolicy.RESCHEDULE_ACTIONS.size)
    }

    @Test
    fun clockChangesReplanOnlyTheMacroNotice() {
        assertTrue(BackgroundWorkPolicy.replansMacroOn("android.intent.action.TIMEZONE_CHANGED"))
        assertTrue(BackgroundWorkPolicy.replansMacroOn("android.intent.action.TIME_SET"))
        // Kein volles Neuplanen (Wecker erzwingen) bei Zeitzonenwechsel
        assertFalse(BackgroundWorkPolicy.reschedulesOn("android.intent.action.TIMEZONE_CHANGED"))
        assertFalse(BackgroundWorkPolicy.replansMacroOn("android.intent.action.BOOT_COMPLETED"))
        assertFalse(BackgroundWorkPolicy.replansMacroOn(null))
    }

    @Test
    fun exactAlarmResyncsAfterClockChange() {
        assertTrue(BackgroundWorkPolicy.resyncsExactOn("android.intent.action.TIME_SET"))
        assertTrue(BackgroundWorkPolicy.resyncsExactOn("android.intent.action.TIMEZONE_CHANGED"))
        assertFalse(BackgroundWorkPolicy.resyncsExactOn("android.intent.action.BOOT_COMPLETED"))
        assertFalse(BackgroundWorkPolicy.resyncsExactOn(null))
    }

    @Test
    fun macroAfterClockChangeRunsNowInWindowIfNotYetShown() {
        val zone = ZoneId.of("America/New_York")
        // 09:30 New York (nach Flug aus Zürich, wo 08:00 noch nicht dran war)
        val at = ZonedDateTime.of(2026, 10, 19, 9, 30, 0, 0, zone)
        val millis = at.toInstant().toEpochMilli()
        val yesterday = at.toLocalDate().minusDays(1).toEpochDay()
        assertEquals(0L, BackgroundWorkPolicy.macroDelayAfterClockChange(millis, zone, yesterday))
        // Heute schon gemeldet: erst morgen 08:00
        val today = at.toLocalDate().toEpochDay()
        val tomorrow8 = ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(tomorrow8 - millis, BackgroundWorkPolicy.macroDelayAfterClockChange(millis, zone, today))
    }

    @Test
    fun macroAfterClockChangeOutsideWindowWaitsForNextMorning() {
        val zone = ZoneId.of("Asia/Tokyo")
        // 06:00 Tokio: bis 08:00 desselben Tages der neuen Zone
        val early = ZonedDateTime.of(2026, 10, 19, 6, 0, 0, 0, zone)
        val earlyMillis = early.toInstant().toEpochMilli()
        assertEquals(2 * 60 * 60_000L, BackgroundWorkPolicy.macroDelayAfterClockChange(earlyMillis, zone, Long.MIN_VALUE))
        // 13:00 Tokio: Fenster vorbei → morgen 08:00
        val late = ZonedDateTime.of(2026, 10, 19, 13, 0, 0, 0, zone)
        val lateMillis = late.toInstant().toEpochMilli()
        assertEquals(19 * 60 * 60_000L, BackgroundWorkPolicy.macroDelayAfterClockChange(lateMillis, zone, Long.MIN_VALUE))
    }

    // ── ExactCheckReceiver: Ruhemodus ───────────────────────────────────────

    @Test
    fun lightDozeCountsAsIdle() {
        assertFalse(BackgroundWorkPolicy.deviceIdle(deepIdle = false, lightIdle = false))
        assertTrue(BackgroundWorkPolicy.deviceIdle(deepIdle = true, lightIdle = false))
        // Light-Doze: kurzes Netz-Fenster wie im tiefen Ruhemodus → kleines Budget
        assertTrue(BackgroundWorkPolicy.deviceIdle(deepIdle = false, lightIdle = true))
        val idle = BackgroundWorkPolicy.deviceIdle(deepIdle = false, lightIdle = true)
        assertTrue(CheckBudget.exactTimeoutMillis(idle, 20_000L, 1_000L) < 20_000L)
    }

    // ── PriceUpdateWorker ───────────────────────────────────────────────────

    @Test
    fun skippedRunSucceeds() {
        // Dedupe: geplanter Lauf kurz nach einer Prüfung wird übersprungen (run() = null) → Erfolg, kein Retry
        assertTrue(BackgroundSchedule.shouldSkip(CheckTrigger.WORKER, now - 60_000, now))
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcome(manual = false, checked = null, failed = null))
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcome(manual = true, checked = null, failed = null))
    }

    @Test
    fun noRetryEvenWhenEverythingFailed() {
        // Börsenausfall: kein Retry (Backoff verschöbe Alarme um Stunden), nächster Lauf im Takt
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcome(manual = false, checked = 3, failed = 3))
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcome(manual = false, checked = 3, failed = 2))
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcome(manual = false, checked = 0, failed = 0))
        // Knopfdruck: sofort fertig, nie Retry
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcome(manual = true, checked = 3, failed = 3))
    }

    @Test
    fun allFailedOnlyWhenSomethingWasChecked() {
        assertTrue(BackgroundWorkPolicy.allFailed(checked = 3, failed = 3))
        assertFalse(BackgroundWorkPolicy.allFailed(checked = 3, failed = 2))
        assertFalse(BackgroundWorkPolicy.allFailed(checked = 0, failed = 0))
    }

    @Test
    fun errorMapping() {
        // Geplanter Lauf: kein Retry, wartet auf den nächsten Takt
        assertEquals(WorkOutcome.SUCCESS, BackgroundWorkPolicy.outcomeOnError(manual = false))
        assertEquals(WorkOutcome.FAILURE, BackgroundWorkPolicy.outcomeOnError(manual = true))
        // Datenbank-Schlüssel vorübergehend nicht lesbar: einziger Retry (kurzer linearer Backoff)
        assertEquals(WorkOutcome.RETRY, BackgroundWorkPolicy.outcomeOnKeyUnavailable())
    }

    @Test
    fun onlyScheduledRunsSyncExactAlarm() {
        assertTrue(BackgroundWorkPolicy.syncsExactAfterRun(manual = false))
        assertFalse(BackgroundWorkPolicy.syncsExactAfterRun(manual = true))
    }

    // ── PriceUpdateScheduler: periodische Aktualisierung nur mit Bedarf ─────

    private fun pair(disabled: Boolean = false, alarm: Boolean = false, notification: Boolean = false, tts: Boolean = false) =
        BackgroundWorkPolicy.PairWork(disabled = disabled, hasEnabledAlarm = alarm, notificationEnabled = notification, ttsEnabled = tts)

    private fun demand(
        pairs: List<BackgroundWorkPolicy.PairWork> = emptyList(),
        priceNotifications: Boolean = false,
        ttsPrices: Boolean = false,
        activityAlerts: Boolean = false,
        pairWidgets: Boolean = false,
        portfolioWork: Boolean = false,
        marketAlarms: Boolean = false,
    ) = BackgroundWorkPolicy.WorkDemand(pairs, priceNotifications, ttsPrices, activityAlerts, pairWidgets, portfolioWork, marketAlarms)

    @Test
    fun `periodic work only when something uses it`() {
        // Nichts: absagen
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand()))
        // Paare ohne Alarm, Mitteilung, Ansage, Widget: absagen
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair(notification = true, tts = true)))))
        // Alarm an einem aktiven Paar
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair(), pair(alarm = true)))))
        // Alarm nur an einem deaktivierten Paar: wird nie geprüft
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair(disabled = true, alarm = true)), pairWidgets = true)))
        // Kurs-Mitteilung: Schalter und Paar
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair(notification = true)), priceNotifications = true)))
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair()), priceNotifications = true)))
        // Kursansage: Sprachausgabe (nicht «Nur Alarme») und Paar
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair(tts = true)), ttsPrices = true)))
        // Widgets bzw. Aktivität brauchen aktive Paare
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair()), pairWidgets = true)))
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(listOf(pair()), activityAlerts = true)))
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand(pairWidgets = true, activityAlerts = true)))
        // Portfolio-Widget/-Alarm, Gas, Depeg: auch ohne Paare
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(portfolioWork = true)))
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand(marketAlarms = true)))
    }

    @Test
    fun `pulse widget needs the periodic work even without pairs`() {
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand().copy(pulseWidget = true)))
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand().copy(pulseWidget = false)))
    }

    @Test
    fun `empty in-memory watchlist keeps the periodic work`() {
        // Gespeicherter Stand nur gerade nicht lesbar: leere Liste sagt nichts, nicht absagen
        assertTrue(BackgroundWorkPolicy.periodicNeeded(demand().copy(watchlistKept = true)))
        assertFalse(BackgroundWorkPolicy.periodicNeeded(demand().copy(watchlistKept = false)))
    }

    @Test
    fun `empty in-memory portfolio counts as armed portfolio alarm`() {
        assertTrue(BackgroundWorkPolicy.portfolioWork(portfolioEnabled = true, alarmOn = false, portfolioKept = true, widget = false))
        assertTrue(BackgroundWorkPolicy.portfolioWork(portfolioEnabled = true, alarmOn = true, portfolioKept = false, widget = false))
        assertFalse(BackgroundWorkPolicy.portfolioWork(portfolioEnabled = true, alarmOn = false, portfolioKept = false, widget = false))
        // Portfolio aus: keine Alarme, nur das Widget zählt
        assertFalse(BackgroundWorkPolicy.portfolioWork(portfolioEnabled = false, alarmOn = false, portfolioKept = true, widget = false))
        assertTrue(BackgroundWorkPolicy.portfolioWork(portfolioEnabled = false, alarmOn = false, portfolioKept = false, widget = true))
    }
}
