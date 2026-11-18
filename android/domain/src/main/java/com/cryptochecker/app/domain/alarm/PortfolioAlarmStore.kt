package com.cryptochecker.app.domain.alarm

import kotlinx.coroutines.flow.Flow

/** Portfolio-Alarme für die Alarm-Übersicht: beobachten, ein- und ausschalten, löschen. Umsetzung in :data. */
interface PortfolioAlarmStore {
    fun observeAll(): Flow<List<PortfolioAlarmEntity>>

    /** «fällt um ≥ 100 %» wird nie eingeschaltet. */
    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun delete(id: Long)
}
