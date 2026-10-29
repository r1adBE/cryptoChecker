package com.cryptochecker.app.work

import com.cryptochecker.app.domain.macro.MacroCalendar
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import java.time.Instant
import java.time.ZoneId

/**
 * Entscheide der Hintergrund-Arbeit ohne Android (JVM-testbar): [ExactCheckScheduler] (stellen,
 * absagen, stehen lassen), [ExactCheckReceiver] (nach dem Auslösen), [BootReceiver] (welche
 * Broadcasts neu planen) und [PriceUpdateWorker] (Ergebnis an WorkManager). Nur Android; iOS plant
 * über `RefreshSchedule` (BackgroundRefresh.swift) und kennt keinen Wecker.
 */
object BackgroundWorkPolicy {

    // ── ExactCheckScheduler ─────────────────────────────────────────────────

    /** Was [ExactCheckScheduler.sync] mit dem Wecker tut. */
    sealed interface ExactAction {
        /** Aus, keine Berechtigung oder keine Paare: absagen — WorkManager allein. */
        data object Cancel : ExactAction

        /** Gestellter Wecker passt: nichts tun (sonst schöbe jede Einstellung den Takt hinaus). */
        data object Keep : ExactAction

        /** Wecker auf [at] stellen. */
        data class Arm(val at: Long) : ExactAction
    }

    /**
     * Abgleich des Weckers. [armedFor]: gestellter Zeitpunkt (≤ 0 = keiner), [force]: Android hat ihn
     * gelöscht (Neustart, Update, neue Berechtigung).
     */
    fun exactAction(
        enabled: Boolean,
        permitted: Boolean,
        hasWork: Boolean,
        armedFor: Long,
        now: Long,
        force: Boolean = false,
    ): ExactAction = when {
        !BackgroundSchedule.shouldArmExact(enabled, permitted, hasWork) -> ExactAction.Cancel
        BackgroundSchedule.needsArm(armedFor, now, force) -> ExactAction.Arm(BackgroundSchedule.nextExactAt(now))
        else -> ExactAction.Keep
    }

    // ── ExactCheckReceiver ──────────────────────────────────────────────────

    /** Was nach dem Auslösen des Weckers geschieht. */
    enum class FiredAction {
        /** Nicht mehr nötig/erlaubt: absagen (über [exactAction]), nicht neu stellen, nicht prüfen. */
        CANCEL,

        /** Nächsten Wecker stellen, aber offline nicht prüfen (als übersprungen vermerken). */
        REARM_SKIP_OFFLINE,

        /** Nächsten Wecker stellen und prüfen. */
        REARM_AND_CHECK,
    }

    fun firedAction(enabled: Boolean, permitted: Boolean, hasWork: Boolean, online: Boolean): FiredAction = when {
        !BackgroundSchedule.shouldArmExact(enabled, permitted, hasWork) -> FiredAction.CANCEL
        !online -> FiredAction.REARM_SKIP_OFFLINE
        else -> FiredAction.REARM_AND_CHECK
    }

    /**
     * Ruhemodus für das Zeitbudget des Weckers (CheckBudget.exactTimeoutMillis): tiefer Doze [deepIdle] oder leichter
     * [lightIdle] (PowerManager.isDeviceLightIdleMode, ab Android 13 lesbar). Auch im leichten Ruhemodus darf die App
     * nach dem Wecker nur kurz ins Netz; mit dem vollen Budget liefen die Abfragen über das Fenster hinaus.
     */
    fun deviceIdle(deepIdle: Boolean, lightIdle: Boolean): Boolean = deepIdle || lightIdle

    // ── BootReceiver ────────────────────────────────────────────────────────

    /** = Intent.ACTION_BOOT_COMPLETED */
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    /** = Intent.ACTION_MY_PACKAGE_REPLACED */
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    /** = AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31; als Text, minSdk 26). */
    const val ACTION_EXACT_ALARM_PERMISSION_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

    /**
     * Nur geschützte System-Broadcasts: Andere Apps können sie nicht senden, auch nicht gezielt an
     * den (exportierten) Empfänger. QUICKBOOT_POWERON (Hersteller, ungeschützt) bewusst nicht —
     * die Aufträge von WorkManager überstehen den Neustart ohnehin.
     */
    val RESCHEDULE_ACTIONS: Set<String> = setOf(
        ACTION_BOOT_COMPLETED,
        ACTION_MY_PACKAGE_REPLACED,
        // Android 12+: «Wecker und Erinnerungen» erlaubt (geschützt, nur vom System)
        ACTION_EXACT_ALARM_PERMISSION_CHANGED,
    )

    /**
     * Neu planen (WorkManager und Wecker, Wecker erzwungen)? Android löscht exakte Wecker bei
     * Neustart und Update; nach neuer Berechtigung neu stellen.
     */
    fun reschedulesOn(action: String?): Boolean = action != null && action in RESCHEDULE_ACTIONS

    /** = Intent.ACTION_TIMEZONE_CHANGED (geschützt, nur vom System) */
    const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"

    /** = Intent.ACTION_TIME_CHANGED (Uhr von Hand gestellt; geschützt, nur vom System) */
    const val ACTION_TIME_CHANGED = "android.intent.action.TIME_SET"

    /**
     * Zeitzone oder Uhr geändert: Die Morgen-Meldung «Wirtschaftstermine» (MacroNotifyWorker) ist mit einer festen
     * Verzögerung bis 08:00 der alten Ortszeit geplant und muss neu gerechnet werden — sonst käme sie nach 12:00
     * (entfällt) oder vor 08:00 (plant nur den nächsten Tag). Der WorkManager-Takt hängt nicht an der Ortszeit.
     */
    fun replansMacroOn(action: String?): Boolean = action == ACTION_TIMEZONE_CHANGED || action == ACTION_TIME_CHANGED

    /**
     * Wecker «Pünktlich prüfen» nach geänderter Uhr abgleichen (ExactCheckScheduler.sync, nicht erzwungen)? Er steht
     * auf einer Uhrzeit (RTC): Zurückgestellt läge er weit in der Zukunft und setzte bis dahin aus; [BackgroundSchedule.needsArm]
     * stellt einen unplausibel fernen bzw. abgelaufenen Wecker neu, ein passender bleibt stehen.
     */
    fun resyncsExactOn(action: String?): Boolean = action == ACTION_TIME_CHANGED || action == ACTION_TIMEZONE_CHANGED

    /**
     * Verzögerung der Morgen-Meldung nach einer Änderung von Zeitzone/Uhr. Liegt die neue Ortszeit im Meldefenster
     * (08:00–12:00) und kam heute ([lastNotifiedDay], Epochentag) noch keine Meldung: sofort, damit der Tag nicht
     * ausfällt. Sonst bis zum nächsten 08:00 der neuen Zone.
     */
    fun macroDelayAfterClockChange(now: Long, zone: ZoneId, lastNotifiedDay: Long): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val minute = local.hour * 60 + local.minute
        if (MacroCalendar.isNotifyWindow(minute) && local.toLocalDate().toEpochDay() != lastNotifiedDay) return 0L
        return (MacroCalendar.nextNotifyAt(now, zone) - now).coerceAtLeast(0L)
    }

    // ── PriceUpdateWorker ───────────────────────────────────────────────────

    /** Ergebnis an WorkManager (Result.success/retry/failure). */
    enum class WorkOutcome { SUCCESS, RETRY, FAILURE }

    /**
     * Nach einem Lauf, auch einem übersprungenen (eben erst geprüft, läuft bereits), geplant wie per Knopfdruck:
     * immer Erfolg, auch wenn alle Paare scheiterten ([allFailed]). Ein Retry des periodischen Auftrags verschöbe
     * mit dem Backoff von WorkManager (exponentiell, bis 5 h) den nächsten Lauf und damit die Alarme um Stunden
     * nach einem Börsenausfall. Der nächste Lauf kommt im normalen Takt (bzw. über den Wecker «Pünktlich prüfen»);
     * der Fehlschlag steht in «Letzte Prüfungen» und im Log. Ein Knopfdruck soll sofort fertig sein, sonst dreht
     * die Anzeige weiter.
     */
    val outcomeAfterRun: WorkOutcome = WorkOutcome.SUCCESS

    /** Alle geprüften Paare gescheitert (nur fürs Log, ändert das Ergebnis nicht). */
    fun allFailed(checked: Int, failed: Int): Boolean = checked > 0 && failed == checked

    /**
     * Ausnahme im Lauf: Knopfdruck scheitert sofort, geplanter Lauf wartet auf den nächsten Takt
     * (kein Retry — gleicher Grund wie bei [outcomeAfterRun]; eine bleibende Ausnahme liefe sonst in
     * immer neue Versuche).
     */
    fun outcomeOnError(manual: Boolean): WorkOutcome = if (manual) WorkOutcome.FAILURE else WorkOutcome.SUCCESS

    /**
     * Datenbank-Schlüssel gerade nicht lesbar (Keystore, vorübergehend): erneut versuchen, geplant wie
     * angestossen. Beide Aufträge setzen dafür einen kurzen linearen Backoff (PriceUpdateScheduler, 30 s).
     */
    fun outcomeOnKeyUnavailable(): WorkOutcome = WorkOutcome.RETRY

    /**
     * Linearer Backoff (Sekunden) für [outcomeOnKeyUnavailable], gesetzt von PriceUpdateScheduler (WorkManager-Minimum
     * sind 10 s); der n-te neue Versuch wartet n × so lange
     * ([com.cryptochecker.app.domain.refresh.RetryWait.linearRetryAt]).
     */
    const val RETRY_BACKOFF_SECONDS = 30L

    /** Wecker «Pünktlich prüfen» nach dem Lauf abgleichen? Nur geplante Läufe (Netz darunter). */
    fun syncsExactAfterRun(manual: Boolean): Boolean = !manual

    // ── PriceUpdateScheduler ────────────────────────────────────────────────

    /** Was ein Paar im Hintergrund braucht. */
    data class PairWork(
        val disabled: Boolean,
        val hasEnabledAlarm: Boolean,
        val notificationEnabled: Boolean,
        val ttsEnabled: Boolean,
    )

    /** Eingaben für [periodicNeeded]. */
    data class WorkDemand(
        val pairs: List<PairWork>,
        /** Schalter «Kurs-Mitteilungen». */
        val priceNotifications: Boolean,
        /** Sprachausgabe an und nicht «Nur Alarme» (Kursansagen je Paar). */
        val ttsPrices: Boolean,
        /** Meldung «Ungewöhnliche Aktivität». */
        val activityAlerts: Boolean,
        /** Listen- oder Einzel-Widget auf dem Startbildschirm. */
        val pairWidgets: Boolean,
        /** Portfolio-Widget oder scharfer Portfolio-Alarm (wie PortfolioSnapshotUpdater.refreshIfWidgets). */
        val portfolioWork: Boolean,
        /** Gas- oder USDT-Depeg-Alarm (prüft der Lauf auch ohne Paare). */
        val marketAlarms: Boolean,
        /**
         * Watchlist-Datenbank nur leer im Arbeitsspeicher, die Datei mit dem gespeicherten Stand bleibt
         * (DatabaseEncryptionNotice.isStoredKept): Die leere Liste sagt nichts über den Bedarf, darum geplant lassen.
         */
        val watchlistKept: Boolean = false,
        /** Pulse-Widget auf dem Startbildschirm: zeichnet mit jedem Lauf neu (Marktdaten, auch ohne Paare). */
        val pulseWidget: Boolean = false,
    )

    /**
     * Portfolio-Teil von [WorkDemand.portfolioWork]. [portfolioKept]: Portfolio-Datenbank nur leer im Arbeitsspeicher
     * (gespeicherter Stand bleibt) — wie ein scharfer Alarm behandeln, damit die leere Kopie nichts absagt.
     */
    fun portfolioWork(portfolioEnabled: Boolean, alarmOn: Boolean, portfolioKept: Boolean, widget: Boolean): Boolean =
        widget || (portfolioEnabled && (alarmOn || portfolioKept))

    /**
     * Braucht es die periodische Hintergrund-Aktualisierung? Nur wenn sie etwas bewirkt: Alarme,
     * Kurs-Mitteilungen bzw. -Ansagen eines aktiven Paars, Aktivitäts-Meldung, Widgets mit Paaren,
     * Portfolio-Widget/-Alarm, Pulse-Widget, Gas/Depeg. Sonst wird sie abgesagt (kein Akku für nichts).
     */
    fun periodicNeeded(d: WorkDemand): Boolean {
        if (d.watchlistKept) return true
        if (d.portfolioWork || d.marketAlarms || d.pulseWidget) return true
        val active = d.pairs.filterNot { it.disabled }
        if (active.isEmpty()) return false
        if (d.pairWidgets || d.activityAlerts) return true
        return active.any {
            it.hasEnabledAlarm || (d.priceNotifications && it.notificationEnabled) || (d.ttsPrices && it.ttsEnabled)
        }
    }

    /** Höchste Pause, bevor das Nachführen des Bedarfs (PriceUpdateScheduler.followWork) neu beginnt. */
    const val FOLLOW_RETRY_MAX_MILLIS = 60_000L

    /**
     * Pause vor dem [attempt]-ten Neubeginn (0 = erster) nach einem Lesefehler: 1, 2, 4 … höchstens
     * [FOLLOW_RETRY_MAX_MILLIS]. Vorher beendete ein einziger Fehler das Nachführen bis zum Prozessende.
     */
    fun followRetryDelayMillis(attempt: Long): Long =
        (1_000L shl attempt.coerceIn(0, 6).toInt()).coerceAtMost(FOLLOW_RETRY_MAX_MILLIS)
}
