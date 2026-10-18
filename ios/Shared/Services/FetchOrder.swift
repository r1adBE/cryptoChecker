import Foundation

/// Reihenfolge der Einzelabfragen einer Börse: Paare mit aktivem Alarm zuerst. Im Hintergrund bleibt nur ein kurzes
/// Zeitfenster; was danach noch aussteht, fällt diesmal weg — dann lieber Kurse ohne Alarm. Sonst bleibt die
/// Reihenfolge, wie sie ist (stabil). Wie `FetchOrder.kt`.
enum FetchOrder {
    static func alarmsFirst<T>(_ items: [T], alarmed: Set<Int64>, id: (T) -> Int64) -> [T] {
        guard !alarmed.isEmpty, items.count >= 2 else { return items }
        let first = items.filter { alarmed.contains(id($0)) }
        guard !first.isEmpty, first.count < items.count else { return items }
        return first + items.filter { !alarmed.contains(id($0)) }
    }
}
