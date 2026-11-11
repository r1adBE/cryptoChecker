import Foundation
import os

/// Systemprotokoll (Konsole.app / `log stream`) für Abläufe, die ohne offene App laufen und sonst nicht
/// nachvollziehbar wären: Hintergrund-Aufgabe, Widget-Neuladen, Speicher, Alarm-Lease.
///
/// Keine persönlichen Daten: keine Paare, Beträge, Adressen oder Dateipfade. Als `.public` nur Zahlen
/// (Anzahl, Dauer in ms) und feste Kennungen — alles andere bleibt in der Standardeinstellung `.private`.
enum AppLog {
    /// Gleiche Kennung in App und Widget-Erweiterung, damit ein Filter beide zeigt.
    static let subsystem = "com.cryptochecker.app"

    static let background = Logger(subsystem: subsystem, category: "background")
    static let widget = Logger(subsystem: subsystem, category: "widget")
    static let storage = Logger(subsystem: subsystem, category: "storage")
    static let lease = Logger(subsystem: subsystem, category: "lease")
}

extension AppLog {
    /// Schreib- oder Verschiebevorgang, dessen Fehlschlag den Ablauf nicht anhält (kein Cache): ausführen und einen
    /// Fehlschlag ins Protokoll schreiben statt ihn mit `try?` zu verschlucken. Im Protokoll nur die feste Kennung
    /// `what` und Fehlerdomäne/-code — nie Pfad oder Inhalt. - Returns: true = gelungen.
    @discardableResult
    static func attempt(_ what: String, _ work: () throws -> Void) -> Bool {
        do {
            try work()
            return true
        } catch {
            report(what, error)
            return false
        }
    }

    /// Datei oder Ordner löschen; fehlt das Ziel, ist nichts zu tun (kein Eintrag). Sonst wie `attempt`.
    @discardableResult
    static func remove(_ url: URL, _ what: String, fileManager: FileManager = .default) -> Bool {
        do {
            try fileManager.removeItem(at: url)
            return true
        } catch {
            if isMissingFile(error) { return true }
            report(what, error)
            return false
        }
    }

    /// «Datei nicht vorhanden» (Cocoa oder POSIX) — beim Löschen kein Fehler.
    static func isMissingFile(_ error: Error) -> Bool {
        let e = error as NSError
        if e.domain == NSCocoaErrorDomain { return e.code == NSFileNoSuchFileError || e.code == NSFileReadNoSuchFileError }
        return e.domain == NSPOSIXErrorDomain && e.code == Int(ENOENT)
    }

    private static func report(_ what: String, _ error: Error) {
        let e = error as NSError
        storage.error("\(what, privacy: .public) fehlgeschlagen (\(e.domain, privacy: .public) \(e.code, privacy: .public))")
    }
}
