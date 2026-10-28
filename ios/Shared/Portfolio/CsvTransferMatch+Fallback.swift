import Foundation

// CSV-Import: Ersatzzeilen für Verknüpfungen mit gespeicherten Seiten (Teil von `CsvTransferMatch`) — wie Android
// `CsvTransferFallback.kt`.

extension CsvTransferMatch {
    /// Zeilen zum Speichern: `plan` samt den neuen Zeilen, die gegen eine gespeicherte Seite aufgehen
    /// (`CsvStoredTransfer.absorbed`), in der Reihenfolge der Vorschau `txs`. Gespeichert wird so eine Zeile nur, wenn ihre
    /// Verknüpfung beim Speichern nicht mehr geht (`fallbacks`: gespeicherte Seite inzwischen gelöscht oder schon
    /// verknüpft) — sie darf nie verloren gehen. Eine Auszahlung darunter ohne Wahl bleibt beim Speichern eine Auszahlung
    /// (vorsichtig, wie der Import ohne Wahl). Wie Android.
    static func withFallbacks(_ txs: [CsvImportTx], plan: CsvTransferPlan) -> [CsvImportTx] {
        let absorbed = plan.storedUpdates.compactMap(\.absorbed)
        guard !absorbed.isEmpty else { return plan.transactions }
        var order: [String: Int] = [:]
        for (i, t) in txs.enumerated() { order[t.importKey] = i }
        // Stabil sortiert (wie Kotlin `sortedBy`): bei gleicher Stelle bleibt die Reihenfolge
        return (plan.transactions + absorbed).enumerated()
            .sorted { (order[$0.element.importKey] ?? Int.max, $0.offset) < (order[$1.element.importKey] ?? Int.max, $1.offset) }
            .map(\.element)
    }

    /// Herkunftsschlüssel der Ersatzzeilen (`withFallbacks`) → Id der gespeicherten Seite, in der sie aufgehen.
    static func fallbacks(_ plan: CsvTransferPlan) -> [String: Int64] {
        var out: [String: Int64] = [:]
        for u in plan.storedUpdates { if let a = u.absorbed { out[a.importKey] = u.id } }
        return out
    }
}
