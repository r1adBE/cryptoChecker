import Foundation

/// Gespeicherte Merkliste da, aber gerade nicht lesbar (Schlüssel im Schlüsselbund bzw. Datenschutz gerade nicht
/// verfügbar, `SharedStorage.SnapshotRead.inaccessible`): Die App zeigt eine leere Merkliste im Speicher, und nichts
/// davon wird geschrieben (`saveSnapshotInBackground` lässt die Datei unberührt) — was jetzt angelegt wird, ist weg,
/// sobald die Datei wieder gelesen wird. Alarme werden darum gar nicht erst angelegt (Hinweis statt stillem Verlust), und
/// die Merkliste zeigt einen Dauerhinweis. Paare hinzufügen bleibt erlaubt. Wie Android `WatchlistStorageLock`.
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

    /// Alarm speichern bzw. Schnell-Alarm anlegen sperren?
    static func blocksAlarmSave(locked: Bool) -> Bool { locked }
}
