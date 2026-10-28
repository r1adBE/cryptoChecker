package com.cryptochecker.app.data

import com.cryptochecker.app.widget.PortfolioSnapshotSeal
import java.io.File
import java.io.IOException

/**
 * Journal des Wiederherstellens ([BackupManager.restore]): der Stand VOR dem ersten Schreiben (Merkliste, Alarme, Portfolio,
 * Favoriten, Einstellungen), damit ein abgebrochener Lauf (Prozess beendet, Akku leer) beim nächsten Start zurückgerollt
 * werden kann — Merkliste und Portfolio liegen in getrennten Datenbanken, eine gemeinsame Transaktion gibt es nicht.
 * Gespiegelt in `RestoreJournal.swift`.
 *
 * Abfolge: [write] vor dem ersten Schreiben → Wiederherstellen → [delete] (auch nach einem Zurückrollen im selben Lauf).
 * Liegt beim Start noch ein Journal da, ist ein Lauf abgebrochen: zurückrollen und den Nutzer informieren.
 *
 * Inhalt mit Portfolio-Daten: versiegelt (AES-GCM, Schlüssel im Keystore unter eigenem Alias [KEY_ALIAS], wie die Aufnahme
 * des Portfolio-Widgets [PortfolioSnapshotSeal]) in `noBackupFilesDir` — nie in einer Sicherung, nie im Klartext auf dem
 * Speicher. Ohne Schlüssel kein Journal — dann lehnt das Wiederherstellen ab, bevor etwas ersetzt wird
 * ([BackupJournalUnavailableException]), statt ohne Journal weiterzulaufen (Zurückrollen nur im selben Lauf).
 */
class RestoreJournal(
    private val file: File,
    private val keys: PortfolioSnapshotSeal.Keys,
) {
    /** Was beim Lesen gefunden wurde. */
    sealed interface Read {
        /** Kein Journal: kein Lauf abgebrochen. */
        data object Absent : Read

        /** Keystore gerade nicht erreichbar: nichts tun, der nächste Start versucht es erneut. */
        data object Unavailable : Read

        /** Journal da, aber nicht mehr zu öffnen (Schlüssel verloren, Datei beschädigt): nicht zurückrollbar. */
        data object Unreadable : Read

        class Found(val text: String) : Read
    }

    fun exists(): Boolean = file.exists()

    /** Versiegelt und atomar schreiben (erst Nachbardatei, dann umbenennen); false = kein Schlüssel, kein Journal. */
    fun write(text: String): Boolean {
        val key = (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key ?: return false
        val sealed = try {
            PortfolioSnapshotSeal.seal(key, text)
        } catch (e: Exception) {
            keys.discardIfBroken(e)
            return false
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(sealed.toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Journal des Wiederherstellens nicht gespeichert")
        }
        return true
    }

    fun read(): Read {
        if (!file.exists()) return Read.Absent
        val stored = try {
            file.readText(Charsets.US_ASCII)
        } catch (e: IOException) {
            return Read.Unreadable
        }
        val key = (keys.lookup() as? PortfolioSnapshotSeal.KeyLookup.Found)?.key ?: return Read.Unavailable
        return try {
            PortfolioSnapshotSeal.open(key, stored)?.let { Read.Found(it) } ?: Read.Unreadable
        } catch (e: Exception) {
            Read.Unavailable
        }
    }

    /** Journal (und eine halbe Nachbardatei) löschen. */
    fun delete() {
        File(file.path + ".tmp").delete()
        if (file.exists() && !file.delete()) throw IOException("Journal des Wiederherstellens nicht gelöscht")
    }

    companion object {
        const val FILE_NAME = "restore_journal.sealed"

        /** Eigener Keystore-Alias: geht der Schlüssel der Widget-Aufnahme verloren, bleibt das Journal lesbar. */
        const val KEY_ALIAS = "cryptochecker_restore_journal"

        const val FORMAT = "cryptochecker-restore-journal"
        const val VERSION = 1
    }
}
