package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.data.local.AppDatabase
import com.cryptochecker.app.data.local.DatabaseEncryptionNotice
import com.cryptochecker.app.data.remote.util.runCatchingCancellable
import com.cryptochecker.app.notification.AppNotifier
import timber.log.Timber

/**
 * Was eine Änderung des Nutzers an einem Paar-Alarm mit dessen gemeldeter Mitteilung und seinem Protokoll macht (reine Regeln,
 * Unit-Test `AlarmUserChangeTest`). Ausschalten, Bearbeiten und Löschen nehmen die Mitteilung weg: Ihr Knopf
 * «1 h später» stellt eine Kursmarke wieder scharf und schaltet sie dabei ein (AlarmActionReceiver) — auch einen Alarm, den
 * der Nutzer inzwischen ausgeschaltet hat. Schaltet die Prüfung einen einmaligen Alarm nach dem Melden aus, bleibt die
 * Mitteilung samt Knopf (das läuft nicht hier durch).
 */
internal object AlarmUserChange {

    /** Aufräumen nach dem Löschen. */
    data class Cleanup(val cancelNotification: Boolean, val removeLog: Boolean)

    /**
     * Nach dem Löschen: [storedKept] = Merkliste nur leer im Arbeitsspeicher, die Id gilt nur für diesen Lauf und kann die
     * eines gespeicherten Alarms sein — Mitteilung und Protokoll bleiben. [volatileCopy] = Kopie einer alten
     * unverschlüsselten Datei: derselbe Alarm wie in der Datei, er kommt nach dem Neustart zurück — Mitteilung weg, sein
     * Protokoll bleibt.
     */
    fun afterDelete(storedKept: Boolean, volatileCopy: Boolean): Cleanup = when {
        storedKept -> Cleanup(cancelNotification = false, removeLog = false)
        volatileCopy -> Cleanup(cancelNotification = true, removeLog = false)
        else -> Cleanup(cancelNotification = true, removeLog = true)
    }

    /** Ein- oder ausgeschaltet bzw. bearbeitet ([enabled] null): Mitteilung weg, ausser beim Einschalten. */
    fun cancelsNotification(enabled: Boolean?, storedKept: Boolean): Boolean = !storedKept && enabled != true

    /** Für die ViewModels: [cancelsNotification] mit dem Zustand der Merkliste. */
    fun cancelsNotification(enabled: Boolean?): Boolean =
        cancelsNotification(enabled, DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME))

    /** Für die ViewModels: [afterDelete] mit dem Zustand der Merkliste. */
    fun afterDelete(): Cleanup = afterDelete(
        storedKept = DatabaseEncryptionNotice.isStoredKept(AppDatabase.DB_NAME),
        volatileCopy = DatabaseEncryptionNotice.isVolatileCopy(AppDatabase.DB_NAME),
    )
}

/** Mitteilung des Alarms [alarmId] entfernen; scheitert das, bleibt die Änderung am Alarm trotzdem gespeichert. */
internal suspend fun AppNotifier.retractAlarm(alarmId: Long) {
    runCatchingCancellable { cancelAlarms(listOf(alarmId)) }
        .onFailure { Timber.w(it, "Alarm-Mitteilung %d nicht entfernt", alarmId) }
}
