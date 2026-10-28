import CryptoKit
import Foundation

/// Journal des Wiederherstellens (`BackupManager.restore`) — wie `RestoreJournal.kt`: der Stand VOR dem ersten Schreiben
/// (im Format der Sicherung), damit ein abgebrochener Lauf (App beendet, Akku leer) beim nächsten Start zurückgerollt
/// werden kann — Merkliste/Einstellungen, Portfolio und Adressen liegen in getrennten Dateien.
///
/// Abfolge: `write` vor dem ersten Schreiben → Wiederherstellen → `delete` (auch nach einem Rückrollen im selben Lauf).
/// Liegt beim Start noch ein Journal da, rollt `BackupManager.recoverInterruptedRestore` zurück und nennt das.
///
/// Mit Portfolio-Daten: versiegelt mit dem Schlüssel der verschlüsselten Dateien (`ProtectedContainer`, `StorageKey`),
/// im Container der App (nicht in der App Group, die Widgets brauchen es nicht) und nie im Geräte-Backup. Ohne Schlüssel
/// kein Journal — lehnt das Wiederherstellen dann ab (`BackupManager.Failure.journalUnavailable`).
enum RestoreJournal {
    enum Read {
        /// Kein Journal: kein Lauf abgebrochen.
        case absent
        /// Schlüsselbund gerade nicht lesbar: nichts tun, der nächste Start versucht es erneut.
        case unavailable
        /// Journal da, aber nicht mehr zu öffnen: nicht zurückrollbar.
        case unreadable
        case found(Data)
    }

    static var url: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("restore_journal.sealed")
    }

    /// Versiegelt und atomar schreiben; false = kein Schlüssel oder Schreiben gescheitert (dann ohne Journal).
    @discardableResult
    static func write(_ data: Data, keys: StorageKey = .shared, to target: URL = url) -> Bool {
        // Schlüssel anlegen, falls noch keiner da ist (frische Installation: Sicherung einspielen vor dem ersten Speichern).
        // Nur mit `lookup()` würde genau dieser häufigste Fall abgelehnt (`Failure.journalUnavailable`)
        guard let key = journalKey(keys, create: true) else { return false }
        do {
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try ProtectedFile.writeSealed(data, to: target, key: key)
            BackupExclusion.set(excluded: true, for: target)
            return true
        } catch {
            return false
        }
    }

    static func read(keys: StorageKey = .shared, from source: URL = url) -> Read {
        guard FileManager.default.fileExists(atPath: source.path) else { return .absent }
        guard let raw = try? Data(contentsOf: source) else { return .unreadable }
        guard let key = journalKey(keys, create: false) else { return .unavailable }
        guard let plain = try? ProtectedContainer.open(raw, key: key) else { return .unreadable }
        return .found(plain)
    }

    /// Schlüssel des Journals: der Speicherschlüssel (`create`: anlegen, falls keiner da ist). Nur im UI-Test (Debug-Build
    /// mit `-uiTesting`): ein Schlüssel nur im Arbeitsspeicher — der unsignierte Simulator-Build hat keinen Zugriff auf die
    /// Schlüsselbund-Gruppe der App Group, und ohne Journal lehnt das Wiederherstellen ab.
    private static func journalKey(_ keys: StorageKey, create: Bool) -> SymmetricKey? {
        #if DEBUG
        if UITesting.isActive && keys === StorageKey.shared { return UITesting.journalKey }
        #endif
        if create { return keys.createIfAbsent() }
        if case .key(let key) = keys.lookup() { return key }
        return nil
    }


    static func delete(at target: URL = url) {
        try? FileManager.default.removeItem(at: target)
    }
}
