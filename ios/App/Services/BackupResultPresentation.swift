import Foundation

/// Ergebnis von Sichern/Wiederherstellen als Dialog statt als Toast? Hinweise (Grenzen, Sperre bleibt an, eigener
/// Bitcoin-Server) und lange Fehlermeldungen gehen in einem kurzen Toast unter. Kurze Meldungen bleiben Toasts.
/// Gleiche Regel wie Android `BackupResultPresentation.kt` (gemeinsame Fälle: Parity/backup_result_dialog.json).
enum BackupResultPresentation {
    /// Höchstens diese Anzeigebreite (`displayWidth`) passt bei normaler Schriftgrösse sicher in einen Toast.
    static let toastMaxChars = 80

    /// Fehlermeldungen, die in jeder Sprache zu lang für einen Toast sind (Schlüssel der Texte).
    static let longKeys: Set<String> = [
        "backup_not_enough_memory",
        "backup_storage_kept",
        "backup_restore_storage_in_memory",
        "backup_restore_journal_unavailable",
        "backup_not_verified",
        "backup_restore_newer_version",
    ]

    /// Codepunkte, die doppelt so breit sind (East Asian Wide/Fullwidth, vereinfacht; je einschliesslich).
    static let wideRanges: [ClosedRange<UInt32>] = [
        0x1100...0x115F, 0x2E80...0xA4CF, 0xAC00...0xD7A3, 0xF900...0xFAFF,
        0xFE30...0xFE4F, 0xFF00...0xFF60, 0xFFE0...0xFFE6, 0x20000...0x3FFFD,
    ]

    /// Anzeigebreite: je Codepunkt 1, breite Zeichen (`wideRanges`) 2.
    static func displayWidth(_ text: String) -> Int {
        text.unicodeScalars.reduce(0) { width, scalar in
            width + (wideRanges.contains { $0.contains(scalar.value) } ? 2 : 1)
        }
    }

    /// Grenze für einen Toast bei Schriftgrösse `fontScale` (grössere Schrift → weniger Platz; kleinere hebt nicht an).
    static func toastLimit(fontScale: Double) -> Double {
        Double(toastMaxChars) / max(1, fontScale)
    }

    /// `key` = Schlüssel des Haupttexts (z. B. «backup_restored»), `text` = angezeigter Gesamttext; die übrigen
    /// Werte stammen aus dem Ergebnis des Wiederherstellens (sonst 0/false/nil); `fontScale` = Schriftgrösse des Systems.
    static func asDialog(
        key: String,
        text: String,
        droppedPairs: Int = 0,
        droppedAlarms: Int = 0,
        appLockKept: Bool = false,
        addressServerNote: BackupRestorePolicy.AddressServerNote? = nil,
        fontScale: Double = 1
    ) -> Bool {
        droppedPairs > 0 || droppedAlarms > 0 || appLockKept || addressServerNote != nil
            || longKeys.contains(key) || Double(displayWidth(text)) > toastLimit(fontScale: fontScale)
    }

    /// Ergebnis-Dialog erst zeigen, wenn nichts anderes offen ist (Rückfrage, Blatt, Dateiauswahl, voriges Ergebnis)
    /// — sonst erscheint er nicht. Nur iOS: Android zeigt den Dialog sofort.
    static func canPresent(confirmOpen: Bool, sheetOpen: Bool, pickerOpen: Bool, resultOpen: Bool) -> Bool {
        !confirmOpen && !sheetOpen && !pickerOpen && !resultOpen
    }
}
