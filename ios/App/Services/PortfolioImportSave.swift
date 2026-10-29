import Foundation
import os

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
/// Bewusst so: Schreibt gerade der Import einer grossen Datei im Hintergrund, warten synchrone Schreibwege auf dem
/// MainActor kurz an der Sperre (Kodieren und Schreiben der Datei), statt neben ihm zu schreiben.
///
/// Zwei Sperren, damit die Klasse vom Compiler geprüft `Sendable` ist: `lock` (NSLock) hält die Reihenfolge — das
/// Schreiben selbst (`body`, nicht `Sendable`) läuft darunter; die Nummer liegt in `generation` und wird nur unter
/// `lock` gelesen oder geändert (immer zuerst `lock`, dann `generation`).
final class PortfolioWriteOrder: Sendable {
    private let lock = NSLock()
    private let generation = OSAllocatedUnfairLock(initialState: 0)

    /// Nummer des letzten Schreibens.
    var current: Int { lock.withLock { generation.withLock { $0 } } }

    /// Schreiben ohne Bedingung (synchrone Schreibwege auf dem MainActor).
    func write<T>(_ body: () -> T) -> T {
        lock.withLock {
            generation.withLock { $0 += 1 }
            return body()
        }
    }

    /// Schreiben nur, wenn seit `expected` niemand geschrieben hat; nil = überholt (nichts geschrieben). Dazu die
    /// Nummer dieses Schreibens.
    func write<T>(ifUnchangedSince expected: Int, _ body: () -> T) -> (result: T, generation: Int)? {
        lock.withLock {
            let next: Int? = generation.withLock { number in
                guard number == expected else { return nil }
                number += 1
                return number
            }
            guard let next else { return nil }
            return (body(), next)
        }
    }
}

/// Reihenfolge der Schreibwege im Hintergrund (Ändern, Löschen, Zurücklegen, Leeren: `AppData.editPortfolio`; Schreiben
/// des Imports): wer es hält, liest den Stand, schreibt und übernimmt ihn, bevor der nächste dran ist — kein Schreibweg
/// baut auf einem Stand, dessen Schreiben noch läuft. Wartende der Reihe nach (FIFO); nicht wiedereintrittsfähig.
@MainActor
final class PortfolioEditGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Wartet, bis das Gate an der Reihe frei ist, und belegt es; danach `release()`.
    func acquire() async {
        guard busy else {
            busy = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    /// Gibt frei bzw. direkt an den nächsten Wartenden weiter.
    func release() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
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

    /// Zusammenstellen samt Verknüpfungen (`links`, `fallbacks`: `AppData.withImported`; mit Fortschritt, abbrechbar)
    /// und in einem Schreibvorgang speichern. Abgebrochen vor dem Schreiben: `.cancelled`, nichts geschrieben. Hat ein
    /// anderes Schreiben den Stand inzwischen geändert, wird auf dem neuen Stand noch einmal zusammengestellt — ebenfalls
    /// im Hintergrund, die Verknüpfungen neu geprüft.
    func run(_ list: [PortfolioTx], batch: String?, links: [CsvStoredTransfer] = [], fallbacks: [String: Int64] = [:],
             progress: @escaping @Sendable (CsvImportProgress) -> Void) async -> PortfolioImportWrite {
        var builtOn = current().file
        guard var built = await Self.build(list, batch: batch, links: links, fallbacks: fallbacks, on: builtOn,
                                           progress: progress) else {
            return .cancelled
        }
        for _ in 0..<Self.maxRounds {
            let now = current()
            if now.file.nextId != builtOn.nextId || now.file.transactions != builtOn.transactions {
                // Inzwischen anders gespeichert (selten): auf dem aktuellen Stand noch einmal, danach erneut prüfen
                guard let again = await Self.build(list, batch: batch, links: links, fallbacks: fallbacks, on: now.file,
                                                   progress: nil) else {
                    return .cancelled
                }
                built = again
                builtOn = now.file
                continue
            }
            // Erst das Schreiben zählt: ein «Abbrechen» davor verwirft den zusammengestellten Stand
            guard !Task.isCancelled else { return .cancelled }
            guard built.count > 0 || built.linked > 0 else { return .written(0) }
            switch await write(built.file, now.generation) {
            case .saved: return .written(built.count, linked: built.linked)
            case .failed: return .failed
            case .superseded: continue
            }
        }
        return .failed
    }

    /// `base` samt den importierten Zeilen und Verknüpfungen, losgelöst zusammengestellt (`AppData.withImported`);
    /// nil = abgebrochen.
    nonisolated static func build(_ list: [PortfolioTx], batch: String?, links: [CsvStoredTransfer] = [],
                                  fallbacks: [String: Int64] = [:], on base: PortfolioFile,
                                  progress: (@Sendable (CsvImportProgress) -> Void)?) async
        -> (file: PortfolioFile, count: Int, linked: Int)? {
        await CsvImportBackground.run { () -> (file: PortfolioFile, count: Int, linked: Int)? in
            var throttle = progress.map { CsvProgressThrottle(step: .SAVE, total: list.count, report: $0) }
            throttle?.start()
            return try? AppData.withImported(list, batch: batch, in: base, links: links, fallbacks: fallbacks,
                                             cancelled: { Task.isCancelled }) { done in
                throttle?.row(done)
            }
        }
    }
}
