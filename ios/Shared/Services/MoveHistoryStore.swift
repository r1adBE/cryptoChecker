import Foundation
import os

/// Ablage der Verläufe (`MoveHistoryCore`): Text je Schlüssel; in `write` steht nil für «entfernen».
protocol MoveHistoryStorage: AnyObject {
    func read(_ key: String) -> String?
    func write(_ changes: [String: String?])
    func keys() -> [String]
}

/// Kursverlauf je Paar für den Bewegungs-Alarm — wie `MoveHistoryStore.kt`. Nur für Paare mit so einem Alarm, im
/// gemeinsamen Speicher (App Group, auch die Hintergrund-Aktualisierung prüft Alarme). Ein Schlüssel je
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

/// Kern von `MoveHistoryStore` — wie `MoveHistoryCore.kt`: ein Schlüssel je Paar — ein Paar liest und schreibt
/// nur den eigenen Eintrag; fremde, abgelaufene Verläufe räumt höchstens alle `cleanupIntervalMillis` ein
/// Durchgang weg. Vom Compiler geprüft `Sendable`: Ablage (nicht `Sendable`) und Zähler liegen in einer Sperre und
/// werden nur unter ihr angefasst; heraus kommen nur Werte.
final class MoveHistoryCore: Sendable {
    static let prefix = "move_history_"
    static let cleanupIntervalMillis: Int64 = 30 * 60_000

    /// Alles Veränderliche samt der Ablage, nur unter `state`.
    private struct State {
        let storage: MoveHistoryStorage
        var lastCleanupAt: Int64?
        /// Dekodierte Verläufe seit dem Start (für den Test: append bleibt O(N)).
        var decodeCount = 0
    }

    private let state: OSAllocatedUnfairLock<State>

    init(storage: MoveHistoryStorage) { state = OSAllocatedUnfairLock(uncheckedState: State(storage: storage)) }

    static func key(_ watchId: Int64) -> String { prefix + String(watchId) }

    var decodes: Int { state.withLock { $0.decodeCount } }

    func history(_ watchId: Int64) -> [MoveWindow.PricePoint] {
        state.withLock { s in Self.decode(s.storage.read(Self.key(watchId)), &s) }
    }

    func append(_ watchId: Int64, _ point: MoveWindow.PricePoint, now: Int64) {
        state.withLock { s in
            let key = Self.key(watchId)
            let before = Self.decode(s.storage.read(key), &s)
            let updated = MoveWindow.append(before, point, now: now)
            var changes: [String: String?] = [:]
            // Live-Kurse kommen sekündlich: meist kein neuer Punkt — dann nichts schreiben
            if updated != before { changes[key] = .some(Self.encode(updated)) }
            if Self.cleanupDue(now, last: s.lastCleanupAt) {
                s.lastCleanupAt = now
                for stale in Self.staleKeys(now: now, except: key, &s) { changes[stale] = .some(nil) }
            }
            if !changes.isEmpty { s.storage.write(changes) }
        }
    }

    private static func cleanupDue(_ now: Int64, last: Int64?) -> Bool {
        guard let last else { return true }
        // Uhr zurückgestellt: gleich aufräumen statt lange warten
        return !(0..<cleanupIntervalMillis).contains(now - last)
    }

    /// Verläufe anderer Paare, deren letzter Punkt älter als die Aufbewahrung ist (oder unlesbar).
    private static func staleKeys(now: Int64, except: String, _ s: inout State) -> [String] {
        let storage = s.storage
        return storage.keys().filter { $0.hasPrefix(prefix) && $0 != except }.filter { other in
            guard let last = decode(storage.read(other), &s).map(\.time).max() else { return true }
            return now - last > MoveWindow.retentionMillis
        }
    }

    private static func decode(_ text: String?, _ s: inout State) -> [MoveWindow.PricePoint] {
        guard let text else { return [] }
        s.decodeCount += 1
        guard let data = text.data(using: .utf8), let array = try? JSONSerialization.jsonObject(with: data) as? [Any]
        else { return [] }
        return array.compactMap { item in
            guard let pair = item as? [Any], pair.count >= 2,
                  let time = (pair[0] as? NSNumber)?.int64Value,
                  let price = (pair[1] as? NSNumber)?.doubleValue else { return nil }
            return MoveWindow.PricePoint(price: price, time: time)
        }
    }

    private static func encode(_ points: [MoveWindow.PricePoint]) -> String {
        let array = points.map { [NSNumber(value: $0.time), NSNumber(value: $0.price)] }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }
}
