package com.cryptochecker.app.ui.features.settings

import androidx.annotation.StringRes
import com.cryptochecker.app.R
import com.cryptochecker.app.data.BackupRestorePolicy

/**
 * Ergebnis von Sichern/Wiederherstellen als Dialog statt als Toast? Ein Text-Toast zeigt ab Android 12 höchstens
 * zwei Zeilen — Hinweise (Grenzen, Sperre bleibt an, eigener Bitcoin-Server) und lange Fehlermeldungen fielen weg.
 * Kurze Meldungen bleiben Toasts. Gleiche Regel wie iOS `BackupResultPresentation.swift`
 * (gemeinsame Fälle: testdata/parity/backup_result_dialog.json).
 */
object BackupResultPresentation {

    /** Höchstens so viele Zeichen (Unicode-Codepunkte) passen sicher in einen Toast. */
    const val TOAST_MAX_CHARS = 80

    /** Fehlermeldungen, die in jeder Sprache zu lang für einen Toast sind (Namen der Texte). */
    val LONG_KEYS: Set<String> = setOf(
        "backup_not_enough_memory",
        "backup_storage_kept",
        "backup_restore_storage_in_memory",
        "backup_restore_journal_unavailable",
    )

    /**
     * [key] = Name des Haupttexts (z. B. «backup_restored»), [text] = angezeigter Gesamttext; die übrigen Werte
     * stammen aus dem Ergebnis des Wiederherstellens (sonst 0/false/null).
     */
    fun asDialog(
        key: String,
        text: String,
        droppedPairs: Int = 0,
        droppedAlarms: Int = 0,
        appLockKept: Boolean = false,
        addressServerNote: BackupRestorePolicy.AddressServerNote? = null,
    ): Boolean =
        droppedPairs > 0 || droppedAlarms > 0 || appLockKept || addressServerNote != null ||
            key in LONG_KEYS || text.codePointCount(0, text.length) > TOAST_MAX_CHARS

    /** Haupttext einer Meldung (bei [BackupMessage.Restored] die Vorlage «Wiederhergestellt: …»). */
    @StringRes
    fun messageRes(message: BackupMessage): Int = when (message) {
        BackupMessage.Exported -> R.string.backup_exported
        BackupMessage.Failed -> R.string.backup_failed
        BackupMessage.TooLarge -> R.string.backup_too_large
        BackupMessage.NewerVersion -> R.string.backup_restore_newer_version
        BackupMessage.NotVerified -> R.string.backup_not_verified
        BackupMessage.NotEnoughMemory -> R.string.backup_not_enough_memory
        BackupMessage.StorageKept -> R.string.backup_storage_kept
        BackupMessage.StorageInMemory -> R.string.backup_restore_storage_in_memory
        BackupMessage.JournalUnavailable -> R.string.backup_restore_journal_unavailable
        BackupMessage.ExportTooLarge -> R.string.backup_export_too_large
        is BackupMessage.Restored -> R.string.backup_restored
    }
}
