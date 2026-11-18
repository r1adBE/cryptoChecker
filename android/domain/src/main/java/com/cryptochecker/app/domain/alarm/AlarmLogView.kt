package com.cryptochecker.app.domain.alarm

import kotlinx.coroutines.flow.StateFlow

/** Alarm-Protokoll für die Oberfläche: lesen, Einträge gelöschter Alarme entfernen, leeren (Umsetzung: `AlarmLogStore`). */
interface AlarmLogView {
    /** Stand für die Anzeige; erst nach [load] gefüllt. */
    val state: StateFlow<AlarmLogState>

    /** Datei lesen (abseits des Hauptfadens). */
    suspend fun load(): Boolean

    fun removeAlarm(alarmId: Long)

    fun retainAlarms(alarmIds: Set<Long>)

    fun clearEvents()
}
