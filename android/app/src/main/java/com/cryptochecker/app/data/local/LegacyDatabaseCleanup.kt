package com.cryptochecker.app.data.local

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Überbleibsel des Vorgängerprojekts ([AppDatabase.LEGACY_DB_NAME], anderer Name, andere Pakete) einmal entfernen —
 * wird nicht übernommen. Bis Build 98 lief das bei jedem Anlegen der Datenbank im Hauptthread (auch für Widgets,
 * WorkManager und den Neustart); seit Build 99 einmal je Installation im Hintergrund, mit Merker in
 * `noBackupFilesDir`. Keine Reihenfolge zur Datenbank nötig: andere Datei, nie gelesen.
 */
object LegacyDatabaseCleanup {
    /** Merker-Datei: vorhanden = schon aufgeräumt. «App zurücksetzen» löscht sie (dann einmal erneut, harmlos). */
    const val FLAG_FILE = "legacy_database_cleaned"

    /**
     * Führt [delete] aus, solange [flag] fehlt, und setzt danach den Merker (nur ohne Fehler).
     * @return true = jetzt aufgeräumt; false = schon früher oder gescheitert (nächster Start versucht es wieder)
     */
    fun runOnce(flag: File, delete: () -> Unit): Boolean {
        if (flag.exists()) return false
        return try {
            delete()
            flag.parentFile?.mkdirs()
            flag.createNewFile()
            true
        } catch (e: Exception) {
            Timber.w(e, "Alte Datenbank nicht entfernt")
            false
        }
    }

    /** Ein-/Ausgabe: nicht im Hauptthread aufrufen. */
    fun runOnce(context: Context): Boolean =
        runOnce(File(context.noBackupFilesDir, FLAG_FILE)) { context.deleteDatabase(AppDatabase.LEGACY_DB_NAME) }
}
