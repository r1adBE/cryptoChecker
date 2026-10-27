package com.cryptochecker.app.domain.refresh

import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.domain.activity.ActivityAnalysisGate
import com.cryptochecker.app.domain.activity.ActivityMonitor
import com.cryptochecker.app.domain.alarm.DepegAlarmChecker
import com.cryptochecker.app.domain.gas.GasAlertChecker
import com.cryptochecker.app.settings.SettingsRepository
import com.cryptochecker.app.util.AppVisibility
import com.cryptochecker.app.util.appScope
import com.cryptochecker.app.widget.PortfolioSnapshotUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prüfungen nach einer vollen Aktualisierung, ausserhalb der Refresh-Sperre: ungewöhnliche Aktivität, Gas-Alarm,
 * Depeg-Alarm und die Momentaufnahme fürs Portfolio-Widget. Im Vordergrund losgelöst ([launchDetached]), im
 * WorkManager-Job abgewartet ([awaitAll]). Teil von [PriceRefresher].
 */
@Singleton
class RefreshSideChecks @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val activityMonitor: ActivityMonitor,
    private val gasAlertChecker: GasAlertChecker,
    /** USDT/USD messen und Depeg-Alarm prüfen (mit Alarm oder Portfolio, höchstens alle 5 Minuten). */
    private val depegAlarmChecker: DepegAlarmChecker,
    private val portfolioSnapshotUpdater: PortfolioSnapshotUpdater,
) {
    /** Läuft unabhängig vom Aufrufer weiter (App-weit, ein Singleton). */
    private val scope = appScope(Dispatchers.IO, "RefreshSideChecks")

    // Ungewöhnliche Aktivität: erst nach gespeicherten Kursen und gezeichneten
    // Widgets, ausserhalb der Sperre — bremst den nächsten Durchlauf nicht,
    // Fehler bleiben still. Prüft jedes Paar höchstens alle 10 Minuten.
    // Nur mit Abnehmer: Meldung eingeschaltet oder App sichtbar (Karte, ⚡, «Warum»).
    private suspend fun activityCheck() {
        val alerts = runCatchingCancellable { settingsRepository.current().activityAlerts }.getOrDefault(false)
        if (ActivityAnalysisGate.shouldRun(alertsEnabled = alerts, appVisible = AppVisibility.visible)) {
            activityMonitor.analyzeAll()
        }
    }

    /** Eine Prüfung mit Namen fürs Protokoll. */
    private class Check(val name: String, val run: suspend () -> Unit)

    /** Die Prüfungen; Portfolio-Momentaufnahme nur mit Widget (Fehler fängt sie selbst). */
    private fun checks(): List<Check> = listOf(
        Check("Aktivität") { activityCheck() },
        // Gas-Alarm (#167): höchstens alle 10 Minuten, nur wenn eingestellt
        Check("Gas-Alarm") { gasAlertChecker.checkIfDue() },
        // USDT/USD: nur mit Depeg-Alarm oder Portfolio (sonst keine Anfrage)
        Check("Depeg-Alarm") { depegAlarmChecker.checkIfDue() },
        Check("Portfolio") { portfolioSnapshotUpdater.refreshIfWidgets() },
    )

    /**
     * Eigene Hintergrund-Jobs: Die Aktualisierung (und ihr Spinner) wartet nicht auf die Auswertung, die bis zu
     * 20 s dauern kann. Fehler bleiben still.
     */
    fun launchDetached() {
        for (check in checks()) scope.launch { runCatchingCancellable { check.run() } }
    }

    /**
     * WorkManager-Job: Nach doWork() friert Android den Prozess ein — losgelöste Jobs (und ihre Meldungen) gingen
     * verloren. Darum hier abwarten, parallel und je höchstens [timeoutMillis] (auch die Momentaufnahme: sonst
     * hielte eine hängende Abfrage den Job und die Frist des Weckers auf); [extra] mit eigener Zeitgrenze.
     */
    suspend fun awaitAll(timeoutMillis: Long, extra: SideCheckExtra? = null) {
        coroutineScope {
            for (check in checks()) launch { awaitSideCheck(check.name, check.run, timeoutMillis) }
            if (extra != null) launch { awaitSideCheck(extra.name, extra.check, extra.timeoutMillis) }
        }
    }

    /**
     * Prüfung im WorkManager-Job abwarten, höchstens [timeoutMillis]; Fehler und
     * Zeitüberschreitung bleiben still, ein Abbruch des Jobs wird weitergereicht.
     */
    private suspend fun awaitSideCheck(name: String, check: suspend () -> Unit, timeoutMillis: Long) {
        try {
            if (withTimeoutOrNull(timeoutMillis) { check() } == null) {
                Timber.d("%s: Zeitgrenze im Hintergrund-Job erreicht", name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "%s fehlgeschlagen", name)
        }
    }
}
