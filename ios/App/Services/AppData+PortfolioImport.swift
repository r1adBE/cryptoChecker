import Foundation

// CSV-Import in AppData: importierte Transaktionen abseits des Haupt-Threads zusammenstellen (mit Fortschritt und
// abbrechbar) und dann in EINEM Schreibvorgang speichern. Eigene Datei, weil `AppData+Portfolio.swift` an der
// Längengrenze ist (wie `PortfolioRepository.insertImported` auf Android).

/// Ergebnis von `AppData.insertImportedPortfolioTxs(_:batch:progress:)`.
enum PortfolioImportWrite: Equatable {
    /// Geschrieben: Anzahl angelegter Transaktionen (0 = alle schon da, nichts zu schreiben).
    case written(Int)
    /// Nicht geschrieben (gesperrte bzw. nicht lesbare Datei): der Import meldet den Fehler statt «0 importiert».
    case failed
    /// «Abbrechen» vor dem Schreiben: nichts gespeichert.
    case cancelled
}

extension AppData {
    /// Importierte Transaktionen anlegen (gleiche Bereinigung wie von Hand); Zeilen, deren Herkunftsschlüssel
    /// (`importKey` oder `linkedImportKey`) schon da ist, werden übersprungen — wie `PortfolioRepository.insertImported`.
    /// `batch` = Kennung des Importlaufs (`PortfolioImportBatch.newId`) für alle angelegten Transaktionen (nil = keine).
    /// Zusammengestellt wird losgelöst abseits des Haupt-Threads (`withImported`, Fortschritt gedrosselt an `progress`),
    /// geschrieben danach auf dem MainActor in einem Schreibvorgang. Abgebrochen (Aufgabe abgebrochen) bis dahin:
    /// `.cancelled`, nichts geschrieben — es gibt keinen Teil-Import.
    func insertImportedPortfolioTxs(_ list: [PortfolioTx], batch: String?,
                                    progress: @escaping @Sendable (CsvImportProgress) -> Void) async -> PortfolioImportWrite {
        let base = currentPortfolioFile()
        let built = await CsvImportBackground.run { () -> (file: PortfolioFile, count: Int)? in
            var throttle = CsvProgressThrottle(step: .SAVE, total: list.count, report: progress)
            throttle.start()
            return try? AppData.withImported(list, batch: batch, in: base, cancelled: { Task.isCancelled }) { done in
                throttle.row(done)
            }
        }
        // Erst hier (MainActor) wird geschrieben: ein «Abbrechen» davor verwirft den zusammengestellten Stand
        guard !Task.isCancelled, var next = built else { return .cancelled }
        // Inzwischen anders gespeichert (selten): auf dem aktuellen Stand noch einmal zusammenstellen
        let current = currentPortfolioFile()
        if current.nextId != base.nextId || current.transactions != base.transactions {
            guard let again = try? AppData.withImported(list, batch: batch, in: current, cancelled: { false },
                                                        onRow: { _ in }) else { return .cancelled }
            next = again
        }
        guard next.count > 0 else { return .written(0) }
        return setPortfolioFile(next.file) ? .written(next.count) : .failed
    }

    /// `base` samt den bereinigten importierten Zeilen aus `list` (neue Ids wie `PortfolioStore.insert` mit Id 0, ohne
    /// für jede Zeile die ganze Liste zu durchsuchen); Zeilen mit schon vorhandenem Herkunftsschlüssel übersprungen.
    /// Rein, für den Hintergrund. Wirft `CsvImportCancelled`, wenn `cancelled` vor einer Zeile wahr ist (`base` bleibt).
    nonisolated static func withImported(_ list: [PortfolioTx], batch: String?, in base: PortfolioFile,
                                         cancelled: () -> Bool,
                                         onRow: (Int) -> Void) throws -> (file: PortfolioFile, count: Int) {
        var file = base
        var known = importKeys(file.transactions)
        var maxId = file.transactions.map(\.id).max() ?? 0
        let count = try CsvImportInsert.run(list, known: &known, keys: { [$0.importKey, $0.linkedImportKey].compactMap { $0 } },
                                            cancelled: cancelled, onRow: onRow) { tx in
            var item = tx
            item.id = 0
            if let batch { item.importBatch = batch }
            guard var clean = cleanedPortfolioTx(item) else { return false }
            // Wie `PortfolioStore.insert` mit Id 0: nächste freie Id (grösser als alle vorhandenen), angehängt
            clean.id = max(file.nextId, maxId + 1)
            file.transactions.append(clean)
            file.nextId = max(file.nextId, clean.id + 1, maxId + 1)
            maxId = clean.id
            return true
        }
        return (file: file, count: count)
    }
}
