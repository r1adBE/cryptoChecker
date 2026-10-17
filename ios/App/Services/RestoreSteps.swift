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
}
