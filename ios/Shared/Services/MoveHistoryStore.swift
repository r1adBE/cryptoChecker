import Foundation

/// Ablage der Verläufe (`MoveHistoryCore`): Text je Schlüssel; in `write` steht nil für «entfernen».
protocol MoveHistoryStorage: AnyObject {
    func read(_ key: String) -> String?
    func write(_ changes: [String: String?])
    func keys() -> [String]
}

/// Kursverlauf je Paar für den Bewegungs-Alarm — wie `MoveHistoryStore.kt`. Nur für Paare mit so einem Alarm, im
/// gemeinsamen Speicher (App Group, auch die Hintergrund-Aktualisierung prüft Alarme). Seit Build 102 ein Schlüssel je
/// Paar («move_history_<watchId>» = `[[zeit, kurs], …]`, `MoveHistoryCore`). Fehlt der Verlauf, prüft der Alarm gegen
/// seinen gespeicherten Bezug.
enum MoveHistoryStore {
    private final class DefaultsStorage: MoveHistoryStorage {
        func read(_ key: String) -> String? { SharedStorage.defaults.string(forKey: key) }

        func write(_ changes: [String: String?]) {
            let defaults = SharedStorage.defaults
            for (key, value) in changes {
                if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
            }
        }

        func keys() -> [String] { Array(SharedStorage.defaults.dictionaryRepresentation().keys) }
    }

    private static let core = MoveHistoryCore(storage: DefaultsStorage())

    /// Gespeicherter Verlauf des Paars, älteste zuerst.
    static func history(_ watchId: Int64) -> [MoveWindow.PricePoint] { core.history(watchId) }

    /// Punkt anhängen und aufräumen (`MoveWindow.append`); schreibt nur, wenn sich etwas ändert. Verläufe anderer Paare,
    /// deren letzter Punkt älter als die Aufbewahrung ist, fallen höchstens alle 30 Minuten weg.
    static func append(_ watchId: Int64, _ point: MoveWindow.PricePoint, now: Int64) {
        core.append(watchId, point, now: now)
    }
}

/// Kern von `MoveHistoryStore` (seit Build 102) — wie `MoveHistoryCore.kt`: ein Schlüssel je Paar statt aller Verläufe
/// unter einem. Vorher las und schrieb jedes `append` den Inhalt aller Paare und dekodierte jeden fremden Verlauf —
/// O(M²) je Durchlauf. Jetzt nur der eigene Eintrag; fremde, abgelaufene Verläufe räumt höchstens alle
/// `cleanupIntervalMillis` ein Durchgang weg. Der alte Sammelschlüssel wird einmal aufgeteilt.
final class MoveHistoryCore: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    static let prefix = "move_history_"
    static let legacyKey = "move_history"
    static let cleanupIntervalMillis: Int64 = 30 * 60_000

    private let storage: MoveHistoryStorage
    private let lock = NSLock()
    private var migrated = false
    private var lastCleanupAt: Int64?
    /// Dekodierte Verläufe seit dem Start (für den Test: append bleibt O(N)); nur unter `lock`.
    private var decodeCount = 0

    init(storage: MoveHistoryStorage) { self.storage = storage }

    static func key(_ watchId: Int64) -> String { prefix + String(watchId) }

    var decodes: Int { lock.withLock { decodeCount } }

    func history(_ watchId: Int64) -> [MoveWindow.PricePoint] {
        lock.withLock {
            migrate()
            return decode(storage.read(Self.key(watchId)))
        }
    }

    func append(_ watchId: Int64, _ point: MoveWindow.PricePoint, now: Int64) {
        lock.withLock {
            migrate()
            let key = Self.key(watchId)
            let before = decode(storage.read(key))
            let updated = MoveWindow.append(before, point, now: now)
            var changes: [String: String?] = [:]
            // Live-Kurse kommen sekündlich: meist kein neuer Punkt — dann nichts schreiben
            if updated != before { changes[key] = .some(encode(updated)) }
            if cleanupDue(now) {
                lastCleanupAt = now
                for stale in staleKeys(now: now, except: key) { changes[stale] = .some(nil) }
            }
            if !changes.isEmpty { storage.write(changes) }
        }
    }

    private func cleanupDue(_ now: Int64) -> Bool {
        guard let last = lastCleanupAt else { return true }
        // Uhr zurückgestellt: gleich aufräumen statt lange warten
        return !(0..<Self.cleanupIntervalMillis).contains(now - last)
    }

    /// Verläufe anderer Paare, deren letzter Punkt älter als die Aufbewahrung ist (oder unlesbar).
    private func staleKeys(now: Int64, except: String) -> [String] {
        storage.keys().filter { $0.hasPrefix(Self.prefix) && $0 != except }.filter { other in
            guard let last = decode(storage.read(other)).map(\.time).max() else { return true }
            return now - last > MoveWindow.retentionMillis
        }
    }

    /// Bis Build 101 standen alle Verläufe unter `legacyKey` (`{"<watchId>": [[zeit, kurs], …]}`): einmal aufteilen.
    private func migrate() {
        guard !migrated else { return }
        migrated = true
        guard let legacy = storage.read(Self.legacyKey) else { return }
        var changes: [String: String?] = [Self.legacyKey: nil]
        if let data = legacy.data(using: .utf8),
           let map = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            for (id, value) in map {
                guard Int64(id) != nil, let array = value as? [Any],
                      let json = try? JSONSerialization.data(withJSONObject: array),
                      let text = String(data: json, encoding: .utf8) else { continue }
                changes[Self.prefix + id] = .some(text)
            }
        }
        storage.write(changes)
    }

    private func decode(_ text: String?) -> [MoveWindow.PricePoint] {
        guard let text else { return [] }
        decodeCount += 1
        guard let data = text.data(using: .utf8), let array = try? JSONSerialization.jsonObject(with: data) as? [Any]
        else { return [] }
        return array.compactMap { item in
            guard let pair = item as? [Any], pair.count >= 2,
                  let time = (pair[0] as? NSNumber)?.int64Value,
                  let price = (pair[1] as? NSNumber)?.doubleValue else { return nil }
            return MoveWindow.PricePoint(price: price, time: time)
        }
    }

    private func encode(_ points: [MoveWindow.PricePoint]) -> String {
        let array = points.map { [NSNumber(value: $0.time), NSNumber(value: $0.price)] }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }
}
