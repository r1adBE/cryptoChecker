import Foundation

// Sichern und Wiederherstellen bei nicht lesbarem Stand (Teil von `BackupManager`, R4-02): «gerade gesperrt» (Merkliste
// gesperrt, kein Schlüssel, Datenschutz vor dem ersten Entsperren) heisst abwarten — weder sichern (die Datei enthielte
// den Stand nicht und sähe gültig aus) noch einspielen (das Journal hielte einen leeren Stand, die Daten kämen nach dem
// Entsperren zurück). «Beschädigt» (lesbar, aber kaputt) hilft kein Neustart: Sichern lehnt mit eigenem Text ab,
// Wiederherstellen ist der Rettungsweg — die beschädigte Datei kommt unverändert daneben (Bytes exakt), ein Zurückrollen
// legt sie zurück. Wie Android: dort lehnt `ensureStoredReadable` nur ab, solange der Schlüssel fehlt (`storedKept`), eine
// beschädigte Datenbank liegt schon beim Öffnen beiseite.

extension BackupManager {
    /// Zustand des gespeicherten Stands für Sichern und Wiederherstellen.
    enum StorageAccess: Equatable {
        case readable
        /// Gerade nicht zugänglich: später erneut (beide abgelehnt, `backup_storage_kept`).
        case locked
        /// Beschädigt: Sichern abgelehnt (`ios_backup_storage_damaged`), Wiederherstellen ersetzt ihn.
        case damaged
    }

    /// Ein Teil (Portfolio, Adressen, Portfolio-Alarme).
    enum PartState: Equatable {
        case readable, locked, damaged
    }

    /// Rein (testbar): gesperrte Merkliste oder ein gesperrter Teil → `locked` (geht vor); sonst ein beschädigter Teil →
    /// `damaged`.
    static func storageAccess(watchlistLocked: Bool, parts: [PartState]) -> StorageAccess {
        if watchlistLocked || parts.contains(.locked) { return .locked }
        return parts.contains(.damaged) ? .damaged : .readable
    }

    /// Zustand je Teil: nicht (ganz) gelesen (`unreadable`) → beschädigt oder gesperrt (`ProtectedFile.unreadableIsDamaged`).
    static func partState(unreadable: Bool, file: URL) -> PartState {
        guard unreadable else { return .readable }
        return ProtectedFile.unreadableIsDamaged(file) ? .damaged : .locked
    }

    /// `portfolio.json`, `watched_addresses.json` bzw. `portfolio_alarms.json` da, aber nicht lesbar? Einzelne
    /// übersprungene Einträge (neuere Version) zählen nicht: Die Datei ist gelesen, ihre Kopie liegt daneben.
    @MainActor
    static func storageAccess(_ data: AppData) -> StorageAccess {
        let alarmsUnreadable = data.snapshot.portfolioAlarms == nil
            && FileManager.default.fileExists(atPath: SharedStorage.portfolioAlarmsURL.path)
        let parts = [
            partState(unreadable: !data.portfolioIntact && data.portfolioFile.dropped == 0, file: PortfolioStore.url),
            partState(unreadable: !data.watchedAddressIntact && data.watchedAddresses.isEmpty, file: WatchedAddressStore.url),
            partState(unreadable: alarmsUnreadable, file: SharedStorage.portfolioAlarmsURL),
        ]
        return storageAccess(watchlistLocked: data.watchlistLocked, parts: parts)
    }

    /// Sichern abgelehnt? Schlüssel des Texts, sonst nil: gesperrt → «später erneut», beschädigt → «Sicherung einspielen».
    static func backupRefusalKey(_ access: StorageAccess) -> String? {
        switch access {
        case .readable: return nil
        case .locked: return "backup_storage_kept"
        case .damaged: return "ios_backup_storage_damaged"
        }
    }

    /// Wiederherstellen abgelehnt? Nur solange gesperrt; ein beschädigter Stand wird ersetzt.
    static func restoreRefusalKey(_ access: StorageAccess) -> String? {
        access == .locked ? "backup_storage_kept" : nil
    }
}
