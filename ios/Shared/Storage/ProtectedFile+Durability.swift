import Foundation

// Wie fest ein versiegeltes Schreiben auf dem Speicher liegen muss (Teil von `ProtectedFile`).
// Der Live-Takt speichert die Merkliste alle 10 s: dort reicht eine Schreib-Barriere statt des vollen Leerens.

extension ProtectedFile {
    /// Art des Schreibens in die Nachbardatei vor dem `rename`.
    enum Durability: Sendable, Equatable {
        /// Struktur, Alarme, Portfolio, Einstellungen …: F_FULLFSYNC (Laufwerks-Cache geleert) und Nachlesen der
        /// Nachbardatei vor dem Umbenennen.
        case full
        /// Reine Kurs-Speicherung (Live-Takt): F_BARRIERFSYNC — die Reihenfolge Inhalt vor Umbenennen
        /// bleibt gesichert, nur das teure Leeren des Laufwerks-Caches und das Nachlesen entfallen. Geht nach einem
        /// Stromausfall der letzte Kurs verloren, holt ihn die nächste Abfrage nach.
        case barrier

        /// Befehl für `fcntl`.
        var syncCommand: Int32 { self == .full ? F_FULLFSYNC : F_BARRIERFSYNC }
        /// Nachbardatei vor dem Umbenennen lesen, öffnen und vergleichen?
        var verifiesReadBack: Bool { self == .full }
    }

    /// Schreibt `data` in die neue Nachbardatei und erzwingt je nach `durability` (F_FULLFSYNC bzw. F_BARRIERFSYNC),
    /// dass die Bytes vor dem `rename` auf dem Speicher liegen: Sonst kann nach einem Stromausfall oder Absturz des
    /// Geräts schon der neue Name auf eine leere oder halbe Datei zeigen. Kennt das Dateisystem den Befehl nicht,
    /// wenigstens fsync.
    static func writeDurably(_ data: Data, to temp: URL, durability: Durability = .full) throws {
        guard FileManager.default.createFile(atPath: temp.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: temp.path])
        }
        let handle = try FileHandle(forWritingTo: temp)
        do {
            try handle.write(contentsOf: data)
            if fcntl(handle.fileDescriptor, durability.syncCommand) == -1, fsync(handle.fileDescriptor) == -1 {
                throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: temp.path])
            }
        } catch {
            try? handle.close()
            throw error
        }
        try handle.close()
    }
}
