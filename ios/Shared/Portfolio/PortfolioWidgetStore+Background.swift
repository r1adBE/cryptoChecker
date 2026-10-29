import Foundation
import os

// Portfolio-Widget-Stand ausserhalb des Hauptthreads rechnen (Teil von `PortfolioWidgetStore`): Die
// ganze Rechnung (FIFO, Verlauf, Tageskurse) lief nach jedem Kursabruf des Portfolio-Tabs synchron auf dem MainActor —
// Android rechnet sie auf `Dispatchers.IO` (`PortfolioSnapshotUpdater`). Alle Aufnahmen eines Prozesses laufen
// nacheinander (`OrderedSerialRunner`): Verlauf, Stundenkurse und Stand schreibt immer nur eine, und eine ältere
// Aufnahme überschreibt nie eine neuere.

/// Führt Arbeiten einzeln nacheinander aus, in der Reihenfolge ihrer Marken (`ticket`, beim Aufruf gezogen). Kommt eine
/// Arbeit erst an die Reihe, nachdem eine mit neuerer Marke schon lief, entfällt sie (nil) — ihre Eingaben sind älter.
/// Vom Compiler geprüft `Sendable`: die Zähler liegen hinter eigenen Sperren; `lock` (NSLock) hält die Arbeit
/// zusammen (`work` ist nicht `Sendable` und läuft darunter), `applied` wird nur unter `lock` gelesen oder geändert.
final class OrderedSerialRunner: Sendable {
    private let lock = NSLock()
    private let issued = OSAllocatedUnfairLock<Int64>(initialState: 0)
    private let applied = OSAllocatedUnfairLock<Int64>(initialState: 0)

    /// Neue Marke — beim Erfassen der Eingaben ziehen, nicht erst bei der Ausführung.
    func ticket() -> Int64 {
        issued.withLock { last in
            last += 1
            return last
        }
    }

    /// `work` unter der Sperre (keine andere Arbeit gleichzeitig); nil = eine neuere lief schon.
    func run<T>(_ ticket: Int64, _ work: () -> T) -> T? {
        lock.lock()
        defer { lock.unlock() }
        let fresh = applied.withLock { last -> Bool in
            guard ticket > last else { return false }
            last = ticket
            return true
        }
        guard fresh else { return nil }
        return work()
    }

    /// Marke jetzt ziehen, `work` auf einem Hintergrund-Thread ausführen (nie auf dem Hauptthread).
    func runDetached<T: Sendable>(priority: TaskPriority = .userInitiated,
                                  _ work: @escaping @Sendable () -> T) async -> T? {
        let ticket = ticket()
        return await Task.detached(priority: priority) { [self] in run(ticket, work) }.value
    }
}

extension PortfolioWidgetStore {
    /// Alle Aufnahmen dieses Prozesses (App: Portfolio-Tab, Hintergrund, neue Transaktion; Widget-Erweiterung).
    static let updates = OrderedSerialRunner()

    /// Berechnen und speichern (`updateNow`), nacheinander mit allen anderen Aufnahmen; bei Änderung das Widget neu
    /// zeichnen. Ohne einen einzigen bekannten Kurs bleibt der bisherige Stand stehen (dann nil); nil auch, wenn
    /// inzwischen eine neuere Aufnahme gespeichert wurde. Gibt den neuen Stand zurück (Portfolio-Tab: «Grösste
    /// Bewegungen»; App: Portfolio-Alarme).
    @discardableResult
    static func update(transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                       rate: Double?, reload: Bool = true,
                       extraHourly: [String: [PortfolioTimedPrice]] = [:],
                       displayFx: PortfolioDisplayFx? = nil) -> PortfolioWidgetSnapshot? {
        updates.run(updates.ticket()) {
            updateNow(transactions: transactions, prices: prices, currency: currency, rate: rate, reload: reload,
                      extraHourly: extraHourly, displayFx: displayFx)
        } ?? nil
    }

    /// Wie `update`, aber mit einer früher gezogenen Marke (`updates.ticket()` beim Erfassen der Eingaben auf dem
    /// MainActor) — für Aufrufer, die Kurse und Adressen erst im Hintergrund dazunehmen. Nicht auf dem Hauptthread
    /// aufrufen.
    @discardableResult
    static func update(ticket: Int64, transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                       rate: Double?) -> PortfolioWidgetSnapshot? {
        updates.run(ticket) {
            updateNow(transactions: transactions, prices: prices, currency: currency, rate: rate)
        } ?? nil
    }

    /// Wie `update`, aber im Hintergrund gerechnet — für den MainActor (Portfolio-Tab). Die Reihenfolge gilt ab dem
    /// Aufruf: Eingaben, die später erfasst wurden, gewinnen, auch wenn ihre Rechnung zuerst fertig ist.
    @discardableResult
    static func updateInBackground(transactions: [PortfolioTx], prices: PortfolioPrices, currency: String,
                                   rate: Double?, reload: Bool = true,
                                   displayFx: PortfolioDisplayFx? = nil) async -> PortfolioWidgetSnapshot? {
        await updates.runDetached {
            updateNow(transactions: transactions, prices: prices, currency: currency, rate: rate, reload: reload,
                      displayFx: displayFx)
        } ?? nil
    }
}
