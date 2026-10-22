import Foundation

/// Gespeicherte Merkliste da, aber gerade nicht lesbar (Schlüssel im Schlüsselbund bzw. Datenschutz gerade nicht
/// verfügbar, `SharedStorage.SnapshotRead.inaccessible`): Die App zeigt eine leere Merkliste im Speicher, und nichts
/// davon wird geschrieben (`saveSnapshotInBackground` lässt die Datei unberührt) — was jetzt angelegt wird, ist weg,
/// sobald die Datei wieder gelesen wird. Seit Build 103 (Review F-08) ist die Merkliste darum nur lesbar wie das
/// Portfolio: Alle Schreibwege des Nutzers (Paare hinzufügen, bearbeiten, löschen, Notiz, Gruppe, Favorit, Alarme) lehnt
/// `AppData` zentral ab (`watchlistWriteRefused`) und nennt es kurz («watchlist_storage_locked», gedrosselt); die Merkliste
/// zeigt einen Dauerhinweis. Hintergrund (Kurse, Alarmzustand, Widget) läuft weiter. Wie Android `WatchlistStorageLock`.
///
/// Nicht gesperrt: noch keine Datei (`.missing`), kaputte Datei (`.unreadable`, gesichert und danach überschrieben) und
/// verzögertes Speichern (`SharedStorage.watchlistPending`): Dort ist der Stand im Speicher vollständig und massgebend und
/// geht mit dem nächsten Speichern hinaus.
enum WatchlistStorageLock {
    /// Ist die Merkliste nach diesem Lesen gesperrt?
    static func isLocked(_ read: SharedStorage.SnapshotRead) -> Bool {
        if case .inaccessible = read { return true }
        return false
    }

    /// Gesperrt nach diesem Lesen, mit Blick auf den Stand im Speicher: Wurde die Merkliste in diesem Lauf schon einmal
    /// gelesen (`readBefore`), hält der Speicher den echten Stand — dann sperrt ein vorübergehend unlesbarer Zugriff
    /// (z. B. Hintergrund bei gesperrtem Gerät) nichts.
    static func isLocked(_ read: SharedStorage.SnapshotRead, readBefore: Bool) -> Bool {
        !readBefore && isLocked(read)
    }

    /// Hält der Speicher nach diesem Lesen den echten Stand (gelesen oder noch keine Datei)?
    static func holdsStoredState(_ read: SharedStorage.SnapshotRead) -> Bool {
        switch read {
        case .ok, .missing: return true
        case .unreadable, .inaccessible: return false
        }
    }

    /// Noch keine Datei und kein Schlüssel zum Versiegeln: Was jetzt angelegt wird, kann nicht gespeichert werden
    /// (`ProtectedFile.write(requireSealed:)` schreibt nie Klartext) — wie Android ohne Schlüssel und ohne Datei.
    static func waitsForKey(_ read: SharedStorage.SnapshotRead, keyAvailable: Bool) -> Bool {
        if case .missing = read { return !keyAvailable }
        return false
    }

    /// Schreibwege des Nutzers sperren (hinzufügen, bearbeiten, löschen, Notiz, Gruppe, Favorit, Alarme)?
    static func blocksWrites(locked: Bool) -> Bool { locked }

    /// Alarm speichern bzw. Schnell-Alarm anlegen sperren?
    static func blocksAlarmSave(locked: Bool) -> Bool { locked }

    /// Dauerhinweis der Merkliste — gleich wie Android `WatchlistStorageLock.Notice`.
    enum Notice: Equatable {
        /// Gespeicherte Datei da, aber gerade nicht lesbar: leer und gesperrt («watchlist_storage_kept_in_memory»).
        case kept
        /// Kein Schlüssel zum Versiegeln: Die Merkliste wartet im Speicher, nie im Klartext geschrieben — sie wird nicht
        /// gespeichert, bis ein Schlüssel da ist («watchlist_storage_in_memory»). Nicht gesperrt.
        case notSaved
    }

    /// Welcher Hinweis gilt? `locked` = `AppData.watchlistLocked`, `savePending` = `AppData.watchlistSavePending`.
    static func notice(locked: Bool, savePending: Bool) -> Notice? {
        if locked { return .kept }
        return savePending ? .notSaved : nil
    }
}
