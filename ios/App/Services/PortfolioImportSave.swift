import Foundation

// Speichern des CSV-Imports abseits des Haupt-Threads: Zusammenstellen, Kodieren, Verschlüsseln und Schreiben laufen
// losgelöst; der MainActor übernimmt nur den neuen Stand. Die Schreib-Reihenfolge (`PortfolioWriteOrder`) verhindert,
// dass ein älterer Stand einen neueren überschreibt.

/// Ergebnis eines Schreibens der Portfolio-Datei im Hintergrund (`AppData.setPortfolioFileInBackground`).
enum PortfolioBackgroundWrite: Equatable, Sendable {
    case saved
    /// Nicht geschrieben (gesperrte bzw. nicht lesbare Datei, Schreibfehler).
    case failed
    /// Seit dem Erfassen des Stands hat ein anderes Schreiben gewonnen: auf dem neuen Stand noch einmal
    /// zusammenstellen.
    case superseded
}

/// Reihenfolge aller Schreibvorgänge von `portfolio.json` in diesem Prozess: jedes Schreiben unter einer Sperre (nie
/// zwei gleichzeitig) mit fortlaufender Nummer. Ein Schreiben im Hintergrund nennt die Nummer, die beim Erfassen seines
/// Stands galt; hat inzwischen ein anderes geschrieben, unterbleibt es (sein Stand wäre älter).
final class PortfolioWriteOrder: @unchecked Sendable { // @unchecked: `generation` nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var generation = 0

    /// Nummer des letzten Schreibens.
    var current: Int { lock.withLock { generation } }

    /// Schreiben ohne Bedingung (synchrone Schreibwege auf dem MainActor).
    func write<T>(_ body: () -> T) -> T {
        lock.withLock {
            generation += 1
            return body()
        }
    }

    /// Schreiben nur, wenn seit `expected` niemand geschrieben hat; nil = überholt (nichts geschrieben). Dazu die
    /// Nummer dieses Schreibens.
    func write<T>(ifUnchangedSince expected: Int, _ body: () -> T) -> (result: T, generation: Int)? {
        lock.withLock {
            guard generation == expected else { return nil }
            generation += 1
            return (body(), generation)
        }
    }
}

/// Ablauf von `AppData.insertImportedPortfolioTxs` mit austauschbarem Stand und Schreiben (Tests: Fake-Speicher).
@MainActor
struct PortfolioImportSave {
    /// Stand zum Ändern samt Nummer der Schreib-Reihenfolge — beides zusammen erfasst.
    let current: @MainActor () -> (file: PortfolioFile, generation: Int)
    /// Schreibt im Hintergrund, wenn seit der Nummer niemand geschrieben hat.
    let write: @MainActor (PortfolioFile, _ generation: Int) async -> PortfolioBackgroundWrite

    /// Höchstens so viele Runden (neu zusammenstellen bzw. schreiben), wenn andere Schreibvorgänge dazwischenkommen.
    static let maxRounds = 4

    /// Zusammenstellen (mit Fortschritt, abbrechbar) und in einem Schreibvorgang speichern. Abgebrochen vor dem
    /// Schreiben: `.cancelled`, nichts geschrieben. Hat ein anderes Schreiben den Stand inzwischen geändert, wird auf
    /// dem neuen Stand noch einmal zusammengestellt — ebenfalls im Hintergrund.
    func run(_ list: [PortfolioTx], batch: String?,
             progress: @escaping @Sendable (CsvImportProgress) -> Void) async -> PortfolioImportWrite {
        var builtOn = current().file
        guard var built = await Self.build(list, batch: batch, on: builtOn, progress: progress) else {
            return .cancelled
        }
        for _ in 0..<Self.maxRounds {
            let now = current()
            if now.file.nextId != builtOn.nextId || now.file.transactions != builtOn.transactions {
                // Inzwischen anders gespeichert (selten): auf dem aktuellen Stand noch einmal, danach erneut prüfen
                guard let again = await Self.build(list, batch: batch, on: now.file, progress: nil) else {
                    return .cancelled
                }
                built = again
                builtOn = now.file
                continue
            }
            // Erst das Schreiben zählt: ein «Abbrechen» davor verwirft den zusammengestellten Stand
            guard !Task.isCancelled else { return .cancelled }
            guard built.count > 0 else { return .written(0) }
            switch await write(built.file, now.generation) {
            case .saved: return .written(built.count)
            case .failed: return .failed
            case .superseded: continue
            }
        }
        return .failed
    }

    /// `base` samt den importierten Zeilen, losgelöst zusammengestellt (`AppData.withImported`); nil = abgebrochen.
    nonisolated static func build(_ list: [PortfolioTx], batch: String?, on base: PortfolioFile,
                                  progress: (@Sendable (CsvImportProgress) -> Void)?) async
        -> (file: PortfolioFile, count: Int)? {
        await CsvImportBackground.run { () -> (file: PortfolioFile, count: Int)? in
            var throttle = progress.map { CsvProgressThrottle(step: .SAVE, total: list.count, report: $0) }
            throttle?.start()
            return try? AppData.withImported(list, batch: batch, in: base, cancelled: { Task.isCancelled }) { done in
                throttle?.row(done)
            }
        }
    }
}
