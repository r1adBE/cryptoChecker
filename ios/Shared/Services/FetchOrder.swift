import Foundation

/// Reihenfolge der Einzelabfragen einer Börse: Paare mit aktivem Alarm zuerst. Im Hintergrund bleibt nur ein kurzes
/// Zeitfenster; was danach noch aussteht, fällt diesmal weg — dann lieber Kurse ohne Alarm. Sonst bleibt die
/// Reihenfolge, wie sie ist (stabil). Wie `FetchOrder.kt`.
enum FetchOrder {
    /// `rotation`: mit Zeitbudget je Lauf ein anderer Beginn (beide Teile rotiert) — sonst fielen jedes Mal dieselben
    /// Paare am Ende aus dem Budget; 0 = stabile Reihenfolge. Wie Android.
    static func alarmsFirst<T>(_ items: [T], alarmed: Set<Int64>, rotation: Int = 0, id: (T) -> Int64) -> [T] {
        guard items.count >= 2 else { return items }
        let first = items.filter { alarmed.contains(id($0)) }
        let rest = items.filter { !alarmed.contains(id($0)) }
        return rotated(first, by: rotation) + rotated(rest, by: rotation)
    }

    private static func rotated<T>(_ list: [T], by: Int) -> [T] {
        guard list.count >= 2 else { return list }
        let k = ((by % list.count) + list.count) % list.count
        return k == 0 ? list : Array(list[k...] + list[..<k])
    }
}
