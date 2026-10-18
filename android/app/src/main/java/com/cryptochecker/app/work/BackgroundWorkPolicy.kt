package com.cryptochecker.app.work

import com.cryptochecker.app.domain.refresh.BackgroundSchedule

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

    // ── PriceUpdateWorker ───────────────────────────────────────────────────

    /** Ergebnis an WorkManager (Result.success/retry/failure). */
    enum class WorkOutcome { SUCCESS, RETRY, FAILURE }

    /**
     * Nach einem Lauf. [checked]/[failed] = null: übersprungen (eben erst geprüft, läuft bereits).
     * Immer Erfolg, auch wenn alle Paare scheiterten ([allFailed]): Ein Retry des periodischen
     * Auftrags verschöbe mit dem Backoff von WorkManager (exponentiell, bis 5 h) den nächsten Lauf
     * und damit die Alarme um Stunden nach einem Börsenausfall. Der nächste Lauf kommt im normalen
     * Takt (bzw. über den Wecker «Pünktlich prüfen»); der Fehlschlag steht in «Letzte Prüfungen» und
     * im Log. Knopfdruck ([manual]): soll sofort fertig sein, sonst dreht die Anzeige weiter.
     */
    @Suppress("UNUSED_PARAMETER")
    fun outcome(manual: Boolean, checked: Int?, failed: Int?): WorkOutcome = WorkOutcome.SUCCESS

    /** Alle geprüften Paare gescheitert (nur fürs Log, ändert das Ergebnis nicht). */
    fun allFailed(checked: Int, failed: Int): Boolean = checked > 0 && failed == checked

    /**
     * Ausnahme im Lauf: Knopfdruck scheitert sofort, geplanter Lauf wartet auf den nächsten Takt
     * (kein Retry — gleicher Grund wie bei [outcome]; eine bleibende Ausnahme liefe sonst in
     * immer neue Versuche).
     */
    fun outcomeOnError(manual: Boolean): WorkOutcome = if (manual) WorkOutcome.FAILURE else WorkOutcome.SUCCESS

    /**
     * Datenbank-Schlüssel gerade nicht lesbar (Keystore, vorübergehend): erneut versuchen, geplant wie
     * angestossen. Beide Aufträge setzen dafür einen kurzen linearen Backoff (PriceUpdateScheduler, 30 s).
     */
    fun outcomeOnKeyUnavailable(): WorkOutcome = WorkOutcome.RETRY

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
    )

    /**
     * Braucht es die periodische Hintergrund-Aktualisierung? Nur wenn sie etwas bewirkt: Alarme,
     * Kurs-Mitteilungen bzw. -Ansagen eines aktiven Paars, Aktivitäts-Meldung, Widgets mit Paaren,
     * Portfolio-Widget/-Alarm, Gas/Depeg. Sonst wird sie abgesagt (kein Akku für nichts).
     */
    fun periodicNeeded(d: WorkDemand): Boolean {
        if (d.portfolioWork || d.marketAlarms) return true
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
