package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.alarm.PortfolioAlarmDecision
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioReading
import com.cryptochecker.app.domain.portfolio.PortfolioSnapshot
import com.cryptochecker.app.notification.AppNotifier
import com.cryptochecker.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Portfolio-Alarme lesen und ändern (Portfolio-Tab, Alarm-Übersicht, Sicherung). */
@Singleton
class PortfolioAlarmRepository @Inject constructor(
    private val dao: PortfolioAlarmDao,
) {
    fun observeAll(): Flow<List<PortfolioAlarmEntity>> = dao.observeAll()

    suspend fun getAll(): List<PortfolioAlarmEntity> = dao.getAll()

    /** Neu anlegen: scharf, ohne letzte Meldung. */
    suspend fun add(kind: PortfolioAlarmKind, threshold: Double, currency: String?, repeating: Boolean): Long =
        dao.insert(
            PortfolioAlarmEntity(
                kind = kind,
                threshold = threshold,
                currency = currency?.takeIf { kind.isValue },
                repeating = repeating,
            )
        )

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** Wiederherstellen: alles ersetzen, jeweils scharf (wie Paar-Alarme aus einer Sicherung). */
    suspend fun replaceAll(alarms: List<PortfolioAlarmEntity>) {
        dao.deleteAll()
        alarms.forEach { dao.insert(it.copy(referenceAt = 0, lastTriggeredAt = 0, lastTriggeredValue = null)) }
    }
}

/**
 * Prüft die Portfolio-Alarme nach jeder neuen Momentaufnahme (PortfolioSnapshotUpdater: in der
 * App und bei jeder Hintergrund-Aktualisierung, sobald ein Portfolio-Alarm scharf ist). Nur mit
 * eingeschaltetem Portfolio. Meldet über den Kanal des «Alarm-Signals», in der Nachtruhe
 * lautlos ([AppNotifier.showPortfolioAlarm]). Regeln: [PortfolioAlarmLogic].
 */
@Singleton
class PortfolioAlarmChecker @Inject constructor(
    private val dao: PortfolioAlarmDao,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppNotifier,
) {
    private val mutex = Mutex()

    /** Braucht die Hintergrund-Aktualisierung eine Momentaufnahme für die Alarme? */
    suspend fun hasActiveAlarms(): Boolean =
        settingsRepository.current().portfolioEnabled && dao.countEnabled() > 0

    suspend fun check(snapshot: PortfolioSnapshot) = mutex.withLock {
        val settings = settingsRepository.current()
        if (!settings.portfolioEnabled) return@withLock
        val alarms = dao.getAll().filter { it.enabled }
        if (alarms.isEmpty()) return@withLock
        val reading = PortfolioReading(
            total = snapshot.total,
            currency = snapshot.currency,
            totalUsdt = snapshot.totalUsdt,
            changePercent = snapshot.changePercent,
            empty = snapshot.empty,
        )
        val now = System.currentTimeMillis()
        for (alarm in alarms) {
            // Ein scheiternder Alarm hält die übrigen nicht auf
            try {
                when (val decision = PortfolioAlarmLogic.decide(alarm.toInput(), reading, now, settings.alarmCooldownMinutes)) {
                    PortfolioAlarmDecision.None -> Unit
                    PortfolioAlarmDecision.Rearm -> dao.rearm(alarm.id)
                    is PortfolioAlarmDecision.Fire -> {
                        // Erst speichern (auch bei Abbruch), dann melden: keine doppelte Meldung,
                        // falls das Speichern scheitert
                        withContext(NonCancellable) {
                            dao.markTriggered(alarm.id, now, decision.measured, PortfolioAlarmLogic.enabledAfterFire(alarm.repeating))
                        }
                        notifier.showPortfolioAlarm(alarm, decision.measured)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Portfolio-Alarm %d nicht geprüft", alarm.id)
            }
        }
    }
}
