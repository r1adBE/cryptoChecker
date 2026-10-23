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

    /** Höchstens diese Anzeigebreite ([displayWidth]) passt bei normaler Schriftgrösse sicher in einen Toast. */
    const val TOAST_MAX_CHARS = 80

    /** Fehlermeldungen, die in jeder Sprache zu lang für einen Toast sind (Namen der Texte). */
    val LONG_KEYS: Set<String> = setOf(
        "backup_not_enough_memory",
        "backup_storage_kept",
        "backup_restore_storage_in_memory",
        "backup_restore_journal_unavailable",
        "backup_not_verified",
        "backup_restore_newer_version",
    )

    /** Codepunkte, die doppelt so breit sind (East Asian Wide/Fullwidth, vereinfacht; je einschliesslich). */
    val WIDE_RANGES: List<IntRange> = listOf(
        0x1100..0x115F, 0x2E80..0xA4CF, 0xAC00..0xD7A3, 0xF900..0xFAFF,
        0xFE30..0xFE4F, 0xFF00..0xFF60, 0xFFE0..0xFFE6, 0x20000..0x3FFFD,
    )

    /** Anzeigebreite: je Codepunkt 1, breite Zeichen ([WIDE_RANGES]) 2. */
    fun displayWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            width += if (WIDE_RANGES.any { cp in it }) 2 else 1
            i += Character.charCount(cp)
        }
        return width
    }

    /** Grenze für einen Toast bei Schriftgrösse [fontScale] (grössere Schrift → weniger Platz; kleinere hebt nicht an). */
    fun toastLimit(fontScale: Float): Double = TOAST_MAX_CHARS / maxOf(1.0, fontScale.toDouble())

    /**
     * [key] = Name des Haupttexts (z. B. «backup_restored»), [text] = angezeigter Gesamttext; die übrigen Werte
     * stammen aus dem Ergebnis des Wiederherstellens (sonst 0/false/null); [fontScale] = Schriftgrösse des Systems.
     */
    fun asDialog(
        key: String,
        text: String,
        droppedPairs: Int = 0,
        droppedAlarms: Int = 0,
        appLockKept: Boolean = false,
        addressServerNote: BackupRestorePolicy.AddressServerNote? = null,
        fontScale: Float = 1f,
    ): Boolean =
        droppedPairs > 0 || droppedAlarms > 0 || appLockKept || addressServerNote != null ||
            key in LONG_KEYS || displayWidth(text) > toastLimit(fontScale)

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
