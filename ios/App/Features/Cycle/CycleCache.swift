import Foundation

/// Zeitgrenzen (TTL) und Entscheidungen des Zyklus-Zwischenspeichers — wie
/// `CycleCachePolicy` in Android. Rein, ohne Platte und Netz.
enum CycleCachePolicy {
    static let minute: Int64 = 60_000
    static let hour: Int64 = 60 * minute

    /// Zyklus-Vergleich (Halving-Kurven) 12 h; On-Chain-Werte siehe `CycleDataSource`.
    static let history: Int64 = 12 * hour
    /// Marktphase (Scores) 1 h.
    static let market: Int64 = hour
    static let fearGreed: Int64 = 30 * minute
    /// CoinGecko `/global` (Marktkapitalisierung, Volumen, Dominanz) 15 Min.
    static let global: Int64 = 15 * minute
    static let altSeason: Int64 = hour
    static let pulse: Int64 = 5 * minute
    static let gas: Int64 = minute
    /// Coin-Analyse je Coin 15 Min.
    static let coin: Int64 = 15 * minute

    /// Frisch: gespeichert vor weniger als `ttl`. Ein Zeitstempel in der Zukunft
    /// (Uhr verstellt) gilt als alt, damit nichts ewig hängen bleibt.
    static func isFresh(savedAt: Int64, now: Int64, ttl: Int64) -> Bool {
        let age = now - savedAt
        return age >= 0 && age < ttl
    }

    /// Neu laden, wenn erzwungen, nichts bekannt ist oder der Stand älter als `ttl` ist.
    static func needsRefresh(savedAt: Int64?, now: Int64, ttl: Int64, force: Bool) -> Bool {
        guard !force, let savedAt else { return true }
        return !isFresh(savedAt: savedAt, now: now, ttl: ttl)
    }

    /// Ältester gezeigter Stand für «Stand … · wird aktualisiert …» — nur solange
    /// mindestens ein Bereich mit schon gezeigten Daten neu lädt; sonst nil.
    static func asOf(stamps: [String: Int64], shown: [String], refreshing: Set<String>) -> Int64? {
        let visible = shown.compactMap { key in stamps[key].map { (key, $0) } }
        guard visible.contains(where: { refreshing.contains($0.0) }) else { return nil }
        return visible.map { $0.1 }.min()
    }
}

/// Je Bereich eine kleine JSON-Datei im Caches-Ordner (`cycle_cache/<name>.v1.json`)
/// mit Zeitstempel. Kaputte oder alte Formate werden still übergangen und gelöscht.
/// Die Widgets brauchen die Daten nicht — darum kein App-Group-Ordner.
enum CycleCache {
    /// Formatversion; bei einer Änderung der gespeicherten Typen erhöhen.
    static let version = 1

    struct Entry<Value: Codable>: Codable {
        let version: Int
        let savedAt: Int64
        let value: Value
    }

    private static let directory: URL? = {
        guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let dir = base.appendingPathComponent("cycle_cache", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static func url(_ key: String) -> URL? {
        // Nur einfache Zeichen im Dateinamen (Coin-Symbole kommen von aussen)
        let safe = String(key.map { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") ? $0 : "_" })
        return directory?.appendingPathComponent("\(safe).v\(version).json")
    }

    /// Nicht-endliche Zahlen (NaN, ∞) als Text, damit ein einzelner Wert nicht das Speichern verhindert.
    private static func encoder() -> JSONEncoder {
        let e = JSONEncoder()
        e.nonConformingFloatEncodingStrategy = .convertToString(positiveInfinity: "inf", negativeInfinity: "-inf", nan: "nan")
        return e
    }

    private static func decoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.nonConformingFloatDecodingStrategy = .convertFromString(positiveInfinity: "inf", negativeInfinity: "-inf", nan: "nan")
        return d
    }

    /// Gespeicherter Stand; nil, wenn keiner da ist oder er nicht (mehr) lesbar ist.
    static func read<Value: Codable>(_ key: String, as type: Value.Type = Value.self) -> Entry<Value>? {
        guard let url = url(key), let data = try? Data(contentsOf: url) else { return nil }
        guard let entry = try? decoder().decode(Entry<Value>.self, from: data), entry.version == version else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return entry
    }

    /// Schreibt atomar (nie eine halbe Datei); Fehler werden ignoriert.
    static func write<Value: Codable>(_ key: String, _ value: Value, savedAt: Int64) {
        guard let url = url(key),
              let data = try? encoder().encode(Entry(version: version, savedAt: savedAt, value: value)) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

/// Harte Zeitgrenze für eine ganze Abfrage (alle Teilanfragen zusammen): Danach
/// wird sie abgebrochen und gilt als gescheitert.
enum AsyncTimeout {
    struct Expired: Error {}

    static func run<T: Sendable>(seconds: Double, _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw Expired()
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw Expired() }
            return first
        }
    }
}
