// Alarm speichern (Teil von `WatchRepository`, eigene Datei): Ergebnis eindeutig — gespeichert, Paar voll
// oder abgelehnt. Nicht beides als null: «Abbrechen» in der Rückfrage meldete sonst «Alarm-Grenze erreicht».
package com.cryptochecker.app.data

import androidx.room.withTransaction
import com.cryptochecker.app.domain.model.AlarmEntity
import com.cryptochecker.app.domain.watch.AlarmSaveResult
import com.cryptochecker.app.domain.watch.WatchlistLimits

/**
 * Neuer Alarm (id 0) nur unter der Obergrenze je Paar ([WatchlistLimits.MAX_ALARMS_PER_PAIR], Zählen und Einfügen in
 * einem Vorgang). Bearbeiten (feste id) zählt nicht.
 */
suspend fun WatchRepository.trySaveAlarm(alarm: AlarmEntity): AlarmSaveResult =
    if (userWriteRefused()) {
        AlarmSaveResult.Refused
    } else {
        guardedWrite<AlarmSaveResult>(AlarmSaveResult.Refused) {
            // Schreibfehler (Speicher voll): Hinweis statt Absturz, nichts gespeichert (`guardedWrite`)
            if (alarm.id == 0L) {
                database.withTransaction {
                    if (WatchlistLimits.canAddAlarm(watchDao.getAlarms(alarm.watchId).size)) {
                        AlarmSaveResult.Saved(alarm.copy(id = watchDao.insertAlarm(alarm)))
                    } else {
                        AlarmSaveResult.Full
                    }
                }
            } else {
                watchDao.updateAlarm(alarm)
                AlarmSaveResult.Saved(alarm)
            }
        }
    }

/**
 * Alarm aus dem Editor speichern: gespeicherten Stand lesen, mit [build] zusammenführen und schreiben — alles in EINER
 * Transaktion. Sonst überschrieb ein Zwischenstand der Oberfläche einen eben ausgelösten Alarm (zweite
 * Meldung). Neuer Alarm (id 0) nur unter der Obergrenze je Paar.
 */
suspend fun WatchRepository.trySaveAlarmMerged(id: Long, build: (AlarmEntity?) -> AlarmEntity): AlarmSaveResult =
    if (userWriteRefused()) {
        AlarmSaveResult.Refused
    } else {
        guardedWrite<AlarmSaveResult>(AlarmSaveResult.Refused) {
            database.withTransaction {
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
        }
    }
