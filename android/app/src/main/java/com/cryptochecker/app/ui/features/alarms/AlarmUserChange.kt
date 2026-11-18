package com.cryptochecker.app.ui.features.alarms

import com.cryptochecker.app.domain.local.DatabaseNames
import com.cryptochecker.app.domain.local.StorageMemory
import com.cryptochecker.app.domain.remote.util.runCatchingCancellable
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
     * eines gespeicherten Alarms sein — Mitteilung und Protokoll bleiben.
     */
    fun afterDelete(storedKept: Boolean): Cleanup =
        if (storedKept) {
            Cleanup(cancelNotification = false, removeLog = false)
        } else {
            Cleanup(cancelNotification = true, removeLog = true)
        }

    /** Ein- oder ausgeschaltet bzw. bearbeitet ([enabled] null): Mitteilung weg, ausser beim Einschalten. */
    fun cancelsNotification(enabled: Boolean?, storedKept: Boolean): Boolean = !storedKept && enabled != true

    /** Für die ViewModels: [cancelsNotification] mit dem Zustand der Merkliste. */
    fun cancelsNotification(enabled: Boolean?): Boolean =
        cancelsNotification(enabled, StorageMemory.isStoredKept(DatabaseNames.WATCHLIST))

    /** Für die ViewModels: [afterDelete] mit dem Zustand der Merkliste. */
    fun afterDelete(): Cleanup = afterDelete(storedKept = StorageMemory.isStoredKept(DatabaseNames.WATCHLIST))
}

/** Mitteilung des Alarms [alarmId] entfernen; scheitert das, bleibt die Änderung am Alarm trotzdem gespeichert. */
internal suspend fun AppNotifier.retractAlarm(alarmId: Long) {
    runCatchingCancellable { cancelAlarms(listOf(alarmId)) }
        .onFailure { Timber.w(it, "Alarm-Mitteilung %d nicht entfernt", alarmId) }
}
