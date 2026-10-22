package com.cryptochecker.app.notification

import android.annotation.SuppressLint
import android.content.Context
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarkerStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seit Build 103: Vermerk «Alarm wird gemeldet» dauerhaft (eigene SharedPreferences), damit ein Prozess-Ende zwischen
 * Melden und Speichern keinen zweiten Alarmton auslöst ([com.cryptochecker.app.domain.alarm.AlarmDelivery]). Wie iOS
 * `AlarmDelivery` im App-Group-Speicher. Synchron geschrieben (`commit`): der Vermerk muss vor der Mitteilung stehen;
 * die Prüfung läuft nie auf dem Haupt-Thread.
 */
@Singleton
class AlarmDeliveryMarkers @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmDeliveryMarkerStore {

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(alarmId: Long): Long? =
        runCatching { prefs.getLong(alarmId.toString(), 0L) }.getOrNull()?.takeIf { it > 0L }

    // commit statt apply: der Vermerk muss auf der Platte stehen, bevor die Mitteilung klingelt
    @SuppressLint("ApplySharedPref")
    override fun set(alarmId: Long, at: Long?) {
        runCatching {
            val key = alarmId.toString()
            if (at == null && !prefs.contains(key)) return
            val edit = prefs.edit()
            if (at == null) edit.remove(key) else edit.putLong(key, at)
            edit.commit()
        }
    }

    private companion object {
        const val PREFS = "alarm_delivery_markers"
    }
}
