// Alarm speichern (Teil von `WatchRepository`, eigene Datei): Ergebnis eindeutig — gespeichert, Paar voll
// oder abgelehnt. Nicht beides als null: «Abbrechen» in der Rückfrage meldete sonst «Alarm-Grenze erreicht».
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.data.local.model.AlarmEntity
import com.cryptochecker.app.domain.watch.WatchlistLimits

/** Ausgang von [trySaveAlarm] bzw. [trySaveAlarmMerged]. */
sealed interface AlarmSaveResult {
    /** Gespeichert, mit seiner Id. */
    data class Saved(val alarm: AlarmEntity) : AlarmSaveResult

    /** Neuer Alarm, aber das Paar hat schon [WatchlistLimits.MAX_ALARMS_PER_PAIR] Alarme: nur dann «Grenze erreicht». */
    data object Full : AlarmSaveResult

    /** Merkliste gesperrt bzw. nicht bestätigt ([userWriteRefused]): nichts gespeichert, keine Meldung «voll». */
    data object Refused : AlarmSaveResult
}

/**
 * Neuer Alarm (id 0) nur unter der Obergrenze je Paar ([WatchlistLimits.MAX_ALARMS_PER_PAIR], Zählen und Einfügen in
 * einem Vorgang). Bearbeiten (feste id) zählt nicht.
 */
suspend fun WatchRepository.trySaveAlarm(alarm: AlarmEntity): AlarmSaveResult =
    if (userWriteRefused()) AlarmSaveResult.Refused else if (alarm.id == 0L) database.withTransaction {
        if (WatchlistLimits.canAddAlarm(watchDao.getAlarms(alarm.watchId).size)) {
            AlarmSaveResult.Saved(alarm.copy(id = watchDao.insertAlarm(alarm)))
        } else {
            AlarmSaveResult.Full
        }
    } else {
        watchDao.updateAlarm(alarm)
        AlarmSaveResult.Saved(alarm)
    }

/**
 * Alarm aus dem Editor speichern: gespeicherten Stand lesen, mit [build] zusammenführen und schreiben — alles in EINER
 * Transaktion. Sonst überschrieb ein Zwischenstand der Oberfläche einen eben ausgelösten Alarm (zweite
 * Meldung). Neuer Alarm (id 0) nur unter der Obergrenze je Paar.
 */
suspend fun WatchRepository.trySaveAlarmMerged(id: Long, build: (AlarmEntity?) -> AlarmEntity): AlarmSaveResult =
    if (userWriteRefused()) AlarmSaveResult.Refused else database.withTransaction {
        val alarm = build(if (id != 0L) watchDao.getAlarm(id) else null)
        if (alarm.id != 0L) {
            watchDao.updateAlarm(alarm)
            AlarmSaveResult.Saved(alarm)
        } else if (WatchlistLimits.canAddAlarm(watchDao.getAlarms(alarm.watchId).size)) {
            AlarmSaveResult.Saved(alarm.copy(id = watchDao.insertAlarm(alarm)))
        } else {
            AlarmSaveResult.Full
        }
    }
