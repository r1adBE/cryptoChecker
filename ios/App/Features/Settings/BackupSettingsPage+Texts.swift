import Foundation

/// Texte der Seite «Sichern & Wiederherstellen» (Rückfrage und Ergebnis) — wie Android `overLimitsText`/`restoredText`.
extension BackupSettingsPage {
    /// «3 Paare und 12 Alarme überschreiten die Grenzen …» mit `key` vor (`backup_restore_over_limits`) bzw. nach dem
    /// Wiederherstellen (`backup_restored_over_limits`); nil, wenn nichts wegfällt — wie Android `overLimitsText`.
    static func overLimitsText(_ key: String, pairs: Int, alarms: Int) -> String? {
        guard pairs > 0 || alarms > 0 else { return nil }
        return L(key, L("backup_restored_pairs", count: pairs), L("backup_restored_alarms", count: alarms),
                 LocaleNumbers.integer(WatchlistLimits.maxPairs, grouping: true),
                 LocaleNumbers.integer(WatchlistLimits.maxAlarmsPerPair))
    }

    /// Hinweis zum eigenen Bitcoin-Server nach dem Wiederherstellen (wie Android).
    static func serverNoteText(_ note: BackupRestorePolicy.AddressServerNote) -> String {
        switch note {
        case .KEPT: return L("backup_restore_server_kept")
        case .PUBLIC: return L("backup_restore_server_public")
        case .CHANGED: return L("backup_restore_server_changed")
        }
    }

    /// «Wiederhergestellt: …» samt Hinweisen: über den Grenzen weggelassen; Sperre blieb an (Sicherung ohne Passwort
    /// wollte sie ausschalten); eigener Bitcoin-Server nicht übernommen, entfernt oder ersetzt.
    static func restoredText(_ result: BackupManager.RestoreResult) -> String {
        let restored = L("backup_restored", L("backup_restored_pairs", count: result.watches),
                         L("backup_restored_alarms", count: result.alarms))
        return [
            restored,
            overLimitsText("backup_restored_over_limits", pairs: result.droppedPairs, alarms: result.droppedAlarms),
            result.appLockKept ? L("backup_restore_app_lock_kept") : nil,
            result.addressServerNote.map { serverNoteText($0) },
        ].compactMap { $0 }.joined(separator: "\n")
    }

    /// Ergebnis mit Hinweisen oder langem Text als Dialog (`BackupResultPresentation`)?
    static func restoredAsDialog(_ result: BackupManager.RestoreResult, text: String) -> Bool {
        BackupResultPresentation.asDialog(key: "backup_restored", text: text, droppedPairs: result.droppedPairs,
                                          droppedAlarms: result.droppedAlarms, appLockKept: result.appLockKept,
                                          addressServerNote: result.addressServerNote, fontScale: fontScale())
    }

    /// Rückfrage «Ersetzen?»: Ohne Passwort keine Echtheitsprüfung — Herkunft prüfen lassen; ungültige Einträge und
    /// solche über den Grenzen nennen, bevor etwas ersetzt wird, statt sie still wegzulassen (wie Android).
    static func confirmText(_ preview: BackupManager.RestorePreview, encrypted: Bool) -> String {
        let parts: [String?] = [
            L("backup_restore_confirm"),
            encrypted ? nil : L("backup_restore_unencrypted_warning"),
            preview.invalid > 0 ? L("backup_restore_invalid", count: preview.invalid) : nil,
            overLimitsText("backup_restore_over_limits", pairs: preview.droppedPairs, alarms: preview.droppedAlarms),
        ]
        return parts.compactMap { $0 }.joined(separator: "\n\n")
    }
}
