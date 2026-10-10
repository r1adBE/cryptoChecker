import Foundation

/// Datei aus den Geräte-Backups (iCloud-Backup, Finder/iTunes, Übertragung aufs neue iPhone)
/// ausnehmen oder wieder aufnehmen — über `URLResourceValues.isExcludedFromBackup`.
///
/// Wichtig: Das Merkmal hängt an der Datei. Ein atomares Schreiben (`Data.write(…, .atomic)`)
/// ersetzt die Datei durch eine neue ohne Merkmal — deshalb nach jedem Schreiben erneut setzen
/// (siehe `PortfolioStore.save`).
enum BackupExclusion {
    /// Setzt das Merkmal; fehlt die Datei, passiert nichts.
    /// - Returns: true, wenn die Datei danach den gewünschten Zustand hat.
    @discardableResult
    static func set(excluded: Bool, for url: URL) -> Bool {
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        var target = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = excluded
        do {
            try target.setResourceValues(values)
        } catch {
            return false
        }
        return isExcluded(url) == excluded
    }

    /// Aktueller Zustand (frisch gelesen, ohne zwischengespeicherte Werte der URL).
    static func isExcluded(_ url: URL) -> Bool? {
        var fresh = URL(fileURLWithPath: url.path)
        fresh.removeAllCachedResourceValues()
        return (try? fresh.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup
    }
}
