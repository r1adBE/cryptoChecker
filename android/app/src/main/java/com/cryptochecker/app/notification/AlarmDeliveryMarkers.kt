package com.cryptochecker.app.notification

import android.annotation.SuppressLint
import android.content.Context
import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.portfolio.PortfolioDatabase
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarker
import com.cryptochecker.app.domain.alarm.AlarmDeliveryMarkerStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seit Build 103: Vermerk «Alarm wird gemeldet» dauerhaft (eigene SharedPreferences), damit ein Prozess-Ende zwischen
 * Melden und Speichern keinen zweiten Alarmton auslöst ([com.cryptochecker.app.domain.alarm.AlarmDelivery]). Wie iOS
 * `AlarmDelivery` im App-Group-Speicher. Synchron geschrieben (`commit`): der Vermerk muss vor der Mitteilung stehen;
 * die Prüfung läuft nie auf dem Haupt-Thread. Seit Build 104 je Alarmart eine eigene Datei ([prefsName]): Paar- und
 * Portfolio-Alarme haben eigene Ids (wie iOS `AlarmDelivery.pairs` / `.portfolio`).
 */
abstract class PrefsAlarmDeliveryMarkers(
    private val context: Context,
    private val prefsName: String,
) : AlarmDeliveryMarkerStore {

    private val prefs get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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

    /** Seit Build 104: in einem Schreibvorgang. */
    @SuppressLint("ApplySharedPref")
    override fun remove(alarmIds: Collection<Long>) {
        runCatching {
            val keys = alarmIds.map { it.toString() }.filter { prefs.contains(it) }
            if (keys.isEmpty()) return
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            edit.commit()
        }
    }

    /** Seit Build 104: Vermerke gelöschter Alarme entfernen. */
    override fun retain(alarmIds: Set<Long>) {
        val stale = runCatching { prefs.all.keys.mapNotNull { it.toLongOrNull() }.filter { it !in alarmIds } }
            .getOrDefault(emptyList())
        if (stale.isNotEmpty()) remove(stale)
    }

    companion object {
        /** Seit Build 104: alle Vermerke einer Datei löschen (Datenbank neu angelegt — Ids beginnen von vorn). */
        @SuppressLint("ApplySharedPref")
        fun clearAll(context: Context, prefsName: String) {
            runCatching { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
        }

        /**
         * Seit Build 105: Vermerke der Alarme in [databaseName] löschen bzw. kürzen? Nicht, solange der nächste Prozess den
         * Stand der Datei zurückbringt (gesperrt behalten oder Kopie im Speicher, [AlarmDeliveryMarker.shouldClear]).
         */
        fun prunable(databaseName: String): Boolean = AlarmDeliveryMarker.shouldClear(
            DatabaseEncryptionNotice.isStoredKept(databaseName),
            DatabaseEncryptionNotice.isVolatileCopy(databaseName),
        )
    }
}

/** Paar-Alarme ([com.cryptochecker.app.domain.refresh.RefreshEffects]). */
@Singleton
class AlarmDeliveryMarkers @Inject constructor(
    @ApplicationContext context: Context,
) : PrefsAlarmDeliveryMarkers(context, PREFS) {
    companion object {
        const val PREFS = "alarm_delivery_markers"

        /** Seit Build 105: Vermerke der Paar-Alarme kürzen ([PrefsAlarmDeliveryMarkers.prunable])? */
        fun prunable(): Boolean = PrefsAlarmDeliveryMarkers.prunable(AppDatabase.DB_NAME)
    }
}

/** Seit Build 104: Portfolio-Alarme ([com.cryptochecker.app.data.portfolio.PortfolioAlarmChecker]), wie iOS `.portfolio`. */
@Singleton
class PortfolioAlarmDeliveryMarkers @Inject constructor(
    @ApplicationContext context: Context,
) : PrefsAlarmDeliveryMarkers(context, PREFS) {
    companion object {
        const val PREFS = "portfolio_alarm_delivery_markers"

        /** Seit Build 105: Vermerke der Portfolio-Alarme kürzen ([PrefsAlarmDeliveryMarkers.prunable])? */
        fun prunable(): Boolean = PrefsAlarmDeliveryMarkers.prunable(PortfolioDatabase.DB_NAME)
    }
}
