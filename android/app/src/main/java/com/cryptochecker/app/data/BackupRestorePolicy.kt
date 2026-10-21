package com.cryptochecker.app.data

/**
 * Regeln beim Wiederherstellen (reines Kotlin, testbar; gespiegelt in `BackupRestorePolicy.swift`).
 *
 * Eine Sicherung ohne Passwort hat keine Echtheitsprüfung: Eine bearbeitete Datei im gleichen Format
 * wird angenommen. Darum schaltet sie die Portfolio-Sperre nie aus, wenn sie gerade an ist — und seit Build 99
 * auch sonst nichts in Richtung «weniger Schutz»: «Portfolio in Systemsicherung» nie ein ([systemBackup]),
 * «Beträge verbergen» nie aus ([hideAmounts]).
 */
object BackupRestorePolicy {

    /**
     * Was mit «appLock» aus der Sicherung geschieht: [apply] = neuer Wert (null = unverändert lassen);
     * [keptOn] = die Sicherung wollte die Sperre ausschalten, sie bleibt aber an (Hinweis in der Meldung).
     */
    data class AppLockDecision(val apply: Boolean?, val keptOn: Boolean)

    /**
     * [wanted] = Wert in der Sicherung (null = fehlt), [current] = Sperre jetzt, [encrypted] = Sicherung
     * mit Passwort, [canAuthenticate] = Gerät kann entsperren (sonst sperrte «an» das Portfolio aus).
     */
    fun appLock(wanted: Boolean?, current: Boolean, encrypted: Boolean, canAuthenticate: Boolean): AppLockDecision =
        when (wanted) {
            null -> AppLockDecision(apply = null, keptOn = false)
            true -> AppLockDecision(apply = if (canAuthenticate) true else null, keptOn = false)
            false ->
                if (current && !encrypted) AppLockDecision(apply = null, keptOn = true)
                else AppLockDecision(apply = false, keptOn = false)
        }

    /**
     * «Portfolio in Systemsicherung» aus der Sicherung: neuer Wert oder null (unverändert). Ohne Echtheitsprüfung
     * ([trusted] false) nur ausschalten — einschalten gäbe die Portfolio-Daten in die Systemsicherung.
     */
    fun systemBackup(wanted: Boolean?, current: Boolean, trusted: Boolean): Boolean? =
        if (wanted == true && !current && !trusted) null else wanted

    /**
     * «Beträge verbergen» aus der Sicherung: neuer Wert oder null (unverändert). Ohne Echtheitsprüfung
     * ([trusted] false) nur einschalten — ausschalten zeigte die Beträge offen.
     */
    fun hideAmounts(wanted: Boolean?, current: Boolean, trusted: Boolean): Boolean? =
        if (wanted == false && current && !trusted) null else wanted

    /**
     * Nur Android: Gemerkten Stand (Journal) in eine Datenbank zurückschreiben? Nicht, solange sie nur leer im
     * Arbeitsspeicher liegt ([storedKept]: Schlüssel der vorhandenen Datei gerade nicht lesbar; [inMemory]: überhaupt nur
     * im Arbeitsspeicher) — das Geschriebene ginge mit dem Lauf verloren, das Journal muss bleiben.
     */
    fun mayRollBackInto(storedKept: Boolean, inMemory: Boolean): Boolean = !storedKept && !inMemory

    /** Warum Sichern bzw. Einspielen gerade abgelehnt wird ([storageRefusal]). */
    enum class StorageRefusal {
        /** Gespeicherter Stand gerade nicht lesbar: Sichern ergäbe eine leere Datei, Einspielen ginge verloren. */
        KEPT,

        /** Nur im Arbeitsspeicher (kein Schlüssel): Einspielen meldete Erfolg, nach dem Neustart wäre alles weg. */
        IN_MEMORY,
    }

    /**
     * Nur Android: Darf mit diesem Zustand einer Datenbank gesichert ([restoring] false) bzw. eingespielt werden? null = ja.
     * Sichern bleibt im Arbeitsspeicher erlaubt (es nimmt, was gerade zu sehen ist, auch die Kopie einer alten
     * unverschlüsselten Datei). Einspielen nicht: Die Einstellungen wären schon ersetzt, Merkliste bzw. Portfolio nach dem
     * Neustart aber wieder die alten oder leer — gleiche Regel wie fürs Zurückrollen ([mayRollBackInto]).
     */
    fun storageRefusal(storedKept: Boolean, inMemory: Boolean, restoring: Boolean): StorageRefusal? = when {
        storedKept -> StorageRefusal.KEPT
        restoring && !mayRollBackInto(storedKept, inMemory) -> StorageRefusal.IN_MEMORY
        else -> null
    }

    /** Ungültige Einträge aus der Systemsicherung, die noch zu melden sind (aufsummiert, ohne Überlauf). */
    fun noticeTotal(pending: Int, added: Int): Int =
        (pending.coerceAtLeast(0).toLong() + added.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
