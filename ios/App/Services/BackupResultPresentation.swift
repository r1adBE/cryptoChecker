import Foundation

/// Ergebnis von Sichern/Wiederherstellen als Dialog statt als Toast? Hinweise (Grenzen, Sperre bleibt an, eigener
/// Bitcoin-Server) und lange Fehlermeldungen gehen in einem kurzen Toast unter. Kurze Meldungen bleiben Toasts.
/// Gleiche Regel wie Android `BackupResultPresentation.kt` (gemeinsame Fälle: Parity/backup_result_dialog.json).
enum BackupResultPresentation {
    /// Höchstens so viele Zeichen (Unicode-Codepunkte) passen sicher in einen Toast.
    static let toastMaxChars = 80

    /// Fehlermeldungen, die in jeder Sprache zu lang für einen Toast sind (Schlüssel der Texte).
    static let longKeys: Set<String> = [
        "backup_not_enough_memory",
        "backup_storage_kept",
        "backup_restore_storage_in_memory",
        "backup_restore_journal_unavailable",
    ]

    /// `key` = Schlüssel des Haupttexts (z. B. «backup_restored»), `text` = angezeigter Gesamttext; die übrigen
    /// Werte stammen aus dem Ergebnis des Wiederherstellens (sonst 0/false/nil).
    static func asDialog(
        key: String,
        text: String,
        droppedPairs: Int = 0,
        droppedAlarms: Int = 0,
        appLockKept: Bool = false,
        addressServerNote: BackupRestorePolicy.AddressServerNote? = nil
    ) -> Bool {
        droppedPairs > 0 || droppedAlarms > 0 || appLockKept || addressServerNote != nil
            || longKeys.contains(key) || text.unicodeScalars.count > toastMaxChars
    }
}
