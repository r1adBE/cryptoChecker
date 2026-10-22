import Foundation

/// Reihenfolge der fälligen Paare für «Ungewöhnliche Aktivität» (seit Build 102) — wie `ActivityDueOrder.kt`. Der
/// Durchlauf hat ein Zeitbudget; in Listenreihenfolge kämen bei grossen Merklisten immer dieselben vorderen Paare dran
/// und die hinteren nie. Darum: am längsten nicht geprüfte zuerst, nie geprüfte ganz vorn; ein Versuch ohne Ergebnis
/// zählt wie eine Prüfung, damit ein Paar ohne Kerzen nicht jeden Lauf vorn steht.
enum ActivityDueOrder {
    /// `items` nach der letzten Prüfung bzw. dem letzten Versuch aufsteigend (stabil: Gleichstand in Listenreihenfolge).
    static func order<T>(_ items: [T], id: (T) -> Int64, computedAt: (T) -> Int64?,
                         attemptedAt: [Int64: Int64]) -> [T] {
        let keyed = items.enumerated().map { entry in
            (index: entry.offset, item: entry.element,
             key: lastTouched(computedAt: computedAt(entry.element), attemptedAt: attemptedAt[id(entry.element)]))
        }
        return keyed.sorted { $0.key != $1.key ? $0.key < $1.key : $0.index < $1.index }.map(\.item)
    }

    /// Später von Bericht und Versuch; nie berührt = ganz vorn.
    static func lastTouched(computedAt: Int64?, attemptedAt: Int64?) -> Int64 {
        max(computedAt ?? Int64.min, attemptedAt ?? Int64.min)
    }
}
