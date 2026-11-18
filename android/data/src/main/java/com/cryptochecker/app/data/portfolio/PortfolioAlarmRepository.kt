package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.data.notification.PortfolioAlarmDeliveryMarkers
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.domain.alarm.PortfolioAlarmEntity
import com.cryptochecker.app.domain.alarm.PortfolioAlarmKind
import com.cryptochecker.app.domain.alarm.PortfolioAlarmLogic
import com.cryptochecker.app.domain.alarm.PortfolioAlarmStore
import com.cryptochecker.app.domain.util.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Portfolio-Alarme lesen und ändern (Portfolio-Tab, Alarm-Übersicht, Sicherung). */
@Singleton
class PortfolioAlarmRepository @Inject constructor(
    private val dao: PortfolioAlarmDao,
    /** Vermerke «wird gemeldet» gelöschter bzw. ersetzter Alarme aufräumen. */
    private val deliveryMarkers: PortfolioAlarmDeliveryMarkers,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PortfolioAlarmStore {
    override fun observeAll(): Flow<List<PortfolioAlarmEntity>> = dao.observeAll()

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

    override suspend fun delete(id: Long) {
        dao.delete(id)
        // Nicht bei Kopie im Speicher (der Alarm kommt mit dem nächsten Prozess aus der Datei zurück)
        if (PortfolioAlarmDeliveryMarkers.prunable()) withContext(ioDispatcher) { deliveryMarkers.remove(listOf(id)) }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        // «fällt um ≥ 100 %» löst nie aus — nicht einschalten (erst anpassen)
        if (enabled && dao.getAll().any { it.id == id && PortfolioAlarmLogic.exceedsFall(it.kind, it.threshold) }) return
        dao.setEnabled(id, enabled)
    }

    /** Wiederherstellen: alles ersetzen, jeweils scharf (wie Paar-Alarme aus einer Sicherung). */
    suspend fun replaceAll(alarms: List<PortfolioAlarmEntity>) {
        dao.deleteAll()
        alarms.forEach { dao.insert(it.copy(referenceAt = 0, lastTriggeredAt = 0, lastTriggeredValue = null)) }
        // Alle Alarme ersetzt (Ids können sich decken) — alte Vermerke gelten nicht mehr
        withContext(ioDispatcher) { deliveryMarkers.retain(emptySet()) }
    }
}
