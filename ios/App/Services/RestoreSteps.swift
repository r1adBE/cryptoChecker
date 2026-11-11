import Foundation

/// Schritte des Wiederherstellens (`BackupManager.restore`) mit Rückrollen: Merkliste, Alarme,
/// Einstellungen, Portfolio und Adressen liegen in getrennten Dateien (keine gemeinsame Transaktion).
/// Scheitert ein Schritt, setzt `rollBack` die schon ausgeführten rückwärts auf den vorherigen Stand
/// zurück — wie Android `BackupManager.rollBack`.
struct RestoreSteps {
    private var undos: [() -> Void] = []

    /// Führt `step` aus; gelingt er, merkt sich `undo` für ein späteres Rückrollen.
    /// Scheitert er, wird nichts gemerkt (der Schritt hat selbst nichts geschrieben) und der Fehler weitergereicht.
    mutating func run(_ step: () throws -> Void, undo: @escaping () -> Void) rethrows {
        try step()
        undos.append(undo)
    }

    /// Alle erfolgreichen Schritte in umgekehrter Reihenfolge zurücksetzen (bestmöglich), danach leer.
    mutating func rollBack() {
        for undo in undos.reversed() { undo() }
        undos.removeAll()
    }

    /// Anzahl der Schritte, die beim Rückrollen zurückgesetzt würden.
    var completed: Int { undos.count }

    /// Die Merkliste liess sich nicht auf die Platte schreiben (`settled` false): Der Lauf gilt als gescheitert.
    struct NotSettled: Error, Equatable {}

    /// Ganzer Ablauf mit Journal (das Journal ist schon geschrieben): `body` führt die Schritte aus. Die Merkliste wird
    /// im Hintergrund geschrieben (`SharedStorage.saveSnapshotInBackground`) — erst wenn sie wirklich auf der Platte ist
    /// (`settled` true), gilt der Lauf als gelungen und das Journal darf weg (`deleteJournal`). Sonst und bei jedem
    /// gescheiterten Schritt: zurückrollen; das Journal fällt nur weg, wenn auch der zurückgeschriebene Stand auf der
    /// Platte ist — sonst bleibt es, und der nächste Start rollt zurück (wie Android `RestoreRun.execute`: Journal erst
    /// nach den Transaktionen bzw. nach vollständigem Zurückrollen löschen).
    /// - Parameter settled: wartet alle eingereihten Schreibvorgänge ab; true = der letzte hat geschrieben.
    static func execute(_ body: (inout RestoreSteps) throws -> Void, settled: () -> Bool,
                        deleteJournal: () -> Void) throws {
        var steps = RestoreSteps()
        do {
            try body(&steps)
            guard settled() else { throw NotSettled() }
        } catch {
            steps.rollBack()
            if settled() { deleteJournal() }
            throw error
        }
        deleteJournal()
    }
}
