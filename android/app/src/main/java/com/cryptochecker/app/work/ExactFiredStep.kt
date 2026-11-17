package com.cryptochecker.app.work

import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.domain.work.BackgroundWorkPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was der abgelaufene Wecker «Pünktlich prüfen» vor der Prüfung tut ([BackgroundWorkPolicy.firedAction]), aus
 * [ExactCheckReceiver] gelöst, damit es auf dem Gerät prüfbar ist (`ExactFiredStepTest`). Ergebnis: true = jetzt prüfen.
 */
@Singleton
class ExactFiredStep @Inject constructor(
    private val scheduler: ExactCheckScheduler,
    private val backgroundCheck: BackgroundCheck,
) {
    fun apply(
        action: BackgroundWorkPolicy.FiredAction,
        enabled: Boolean,
        hasWork: Boolean,
        intervalMinutes: Int,
        startedAt: Long
    ): Boolean =
        when (action) {
            // Aus, keine Berechtigung mehr oder nichts zu prüfen (ExactCheckDemand): absagen, nicht neu stellen
            BackgroundWorkPolicy.FiredAction.CANCEL -> {
                scheduler.sync(enabled, hasWork, intervalMinutes = intervalMinutes)
                false
            }
            // Offline: kein Versuch — sonst stünden alle Paare auf Fehler. WorkManager holt
            // nach, sobald Netz da ist. Eintrag «Übersprungen (offline)»: zählt nicht als Prüfung (die
            // Zuverlässigkeitszeile wirkt nicht frisch), nennt aber das Netz als Grund statt «Akku»
            BackgroundWorkPolicy.FiredAction.REARM_SKIP_OFFLINE -> {
                backgroundCheck.log(CheckTrigger.EXACT, CheckResult.OFFLINE, startedAt)
                scheduler.rearm(intervalMinutes)
                false
            }
            BackgroundWorkPolicy.FiredAction.REARM_AND_CHECK -> {
                scheduler.rearm(intervalMinutes)
                true
            }
        }
}
