package com.cryptochecker.app.domain.local

import kotlinx.coroutines.flow.StateFlow

/**
 * Vermerke dieses Geräts, die einzelne Bausteine der Oberfläche selbst lesen (Umsetzung in :data über
 * SharedPreferences). Lesen und Schreiben nicht auf dem Hauptthread, ausser [hideSetAsideForNow].
 */
interface StoredNotices {
    /** Zähler der Vormerkungen zum Zustand der gespeicherten Daten (`StorageRecoveryNotice.changes`). */
    val recoveryChanges: StateFlow<Int>

    /** Nächster zu zeigender Hinweis zum Zustand der gespeicherten Daten; null = keiner. */
    fun pendingRecovery(): StorageRecoveryKind?

    /** Hinweis [kind] erledigt. */
    fun acknowledgeRecovery(kind: StorageRecoveryKind)

    /** «Später» bzw. weggetippt: in diesem Prozess nicht mehr zeigen. */
    fun hideSetAsideForNow()

    /** Einstellungen ersetzt: der eigene Bitcoin-Server ist unbekannt? */
    fun isAddressServerUnknown(): Boolean

    /** Ungültige Portfolio-Einträge aus der Systemsicherung lesen und löschen (0 = nichts zu melden). */
    fun takePortfolioRestoreInvalid(): Int

    /** Liegt mindestens eine Datenbank ohne Schlüssel nur im Arbeitsspeicher (Hinweis in Einstellungen › Sicherung)? */
    fun isEncryptionPending(): Boolean

    /** Gespeicherte letzte volle Prüfung der Alarme; 0 = noch keine. */
    fun lastFullCheck(): Long

    /** Gemerkte Token-Adresse des DEX-Pools [pairId]; null = unbekannt. */
    fun dexTokenAddress(pairId: String?): String?
}
