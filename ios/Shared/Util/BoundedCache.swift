import Foundation

/// Zwischenspeicher mit höchstens `maxEntries` Einträgen: der am längsten nicht benutzte fällt heraus (LRU) — wie
/// `BoundedCache.kt` (Android). Seit Build 99 statt eines unbegrenzten Wörterbuchs: abgelaufene Einträge wurden nie
/// entfernt, jedes je geprüfte Paar blieb bis zum Prozessende im Speicher. Nicht selbst threadsicher: Aufrufer
/// schützen ihn (z. B. mit einem `NSLock`).
struct BoundedCache<Key: Hashable, Value> {
    /// Übliche Grenze für Zwischenspeicher je Paar bzw. Coin (wie `MEMORY_CACHE_ENTRIES`, Android).
    static var defaultMaxEntries: Int { 512 }

    let maxEntries: Int
    private var entries: [Key: (value: Value, used: UInt64)] = [:]
    private var tick: UInt64 = 0

    init(maxEntries: Int = BoundedCache.defaultMaxEntries) {
        self.maxEntries = max(1, maxEntries)
    }

    var count: Int { entries.count }

    /// Wert zu `key`; zählt als Benutzung (rückt nach hinten in der Verdrängungsreihenfolge).
    mutating func get(_ key: Key) -> Value? {
        guard let entry = entries[key] else { return nil }
        tick &+= 1
        entries[key] = (entry.value, tick)
        return entry.value
    }

    /// Setzt `value`; über der Grenze fällt der am längsten nicht benutzte Eintrag heraus.
    mutating func set(_ key: Key, _ value: Value) {
        tick &+= 1
        entries[key] = (value, tick)
        if entries.count > maxEntries, let oldest = entries.min(by: { $0.value.used < $1.value.used })?.key {
            entries.removeValue(forKey: oldest)
        }
    }

    @discardableResult
    mutating func removeValue(forKey key: Key) -> Value? { entries.removeValue(forKey: key)?.value }

    mutating func removeAll() { entries.removeAll() }

    /// Alle Einträge entfernen, deren Schlüssel `predicate` erfüllt (z. B. alle eines Paars) — wie `removeKeysIf` (Android).
    mutating func removeKeys(where predicate: (Key) -> Bool) {
        for key in entries.keys where predicate(key) { entries.removeValue(forKey: key) }
    }
}
