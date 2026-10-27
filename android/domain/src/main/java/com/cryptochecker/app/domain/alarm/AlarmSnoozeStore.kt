package com.cryptochecker.app.domain.alarm

import com.cryptochecker.app.util.KeyValueStores

/**
 * Laufende «1 h später»-Pausen je Paar-Alarm ([AlarmSnooze]): Alarm-Id → Ende der Pause. Eigene kleine Datei
 * statt einer Spalte in der Alarm-Tabelle — die Pause dauert höchstens eine Stunde, gehört nicht in Sicherungen
 * und braucht so keine Datenbank-Migration. Gelesen vom Melde-Schritt (RefreshEffects), geschrieben vom Knopf
 * der Mitteilung ([com.cryptochecker.app.notification.AlarmActionReceiver]). Wie der App-Group-Eintrag
 * «alarm_snooze_until» auf iOS (`AlarmSnooze.swift`). Gelöschte Alarme fallen spätestens nach einer Stunde weg.
 */
class AlarmSnoozeStore(stores: KeyValueStores) {
    private val prefs = stores.open(PREFS)

    /** Ende der Pause von [alarmId]; null = keine. Abgelaufene Pausen zählt [AlarmSnooze.isSnoozed] nicht. */
    fun snoozeUntil(alarmId: Long): Long? =
        runCatching { prefs.getLong(key(alarmId), 0L) }.getOrDefault(0L).takeIf { it > 0 }

    /** «1 h später» für [alarmId] ab [now]; räumt abgelaufene Pausen mit weg. */
    @Synchronized
    fun snooze(alarmId: Long, now: Long) {
        val expired = prefs.all().keys.filter { name ->
            name.startsWith(PREFIX) && !AlarmSnooze.isSnoozed(runCatching { prefs.getLong(name, 0L) }.getOrNull(), now)
        }
        prefs.edit {
            expired.forEach { remove(it) }
            putLong(key(alarmId), AlarmSnooze.snoozeUntil(now))
        }
    }

    private fun key(alarmId: Long) = "$PREFIX$alarmId"

    private companion object {
        const val PREFS = "alarm_snooze"
        const val PREFIX = "until_"
    }
}
