import Foundation

/// Ergebnisse der Auswertung «Ungewöhnliche Aktivität» je Paar (Watch-Id) im
/// gemeinsamen Speicher, damit die Liste nach einem Neustart sofort weiss, wo
/// etwas los ist. Dazu die letzte Open-Interest-Messung und die Zeit der letzten
/// Meldung je Paar — wie `ActivityRepository.kt`.
///
/// Bewusst UserDefaults (App Group) statt der Merkliste-Datei: kleine, kurzlebige
/// Daten ohne Migration. Format wie in Android:
/// `{"<watchId>": {"t": computedAt, "s": [{"k","sv","v","f","a"}]}}`.
enum ActivityRepository {
    private static let keyReports = "activity_reports"
    private static let keyOi = "activity_oi"
    private static let keyNotified = "activity_notified"

    private static let lock = NSLock()

    // MARK: Berichte

    /// Alle gespeicherten Berichte; abgelaufene Signale filtert `ActivityReport.active`.
    static func reports() -> [Int64: ActivityReport] {
        lock.lock(); defer { lock.unlock() }
        return decode(readMap(keyReports))
    }

    /// Mehrere Berichte auf einmal übernehmen und einmal speichern.
    static func putAll(_ updates: [Int64: ActivityReport]) {
        guard !updates.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        var map = decode(readMap(keyReports))
        for (id, report) in updates { map[id] = report }
        writeMap(keyReports, encode(map))
    }

    /// Daten gelöschter Paare wegräumen.
    static func retain(_ watchIds: Set<Int64>) {
        lock.lock(); defer { lock.unlock() }
        for key in [keyReports, keyOi, keyNotified] {
            let map = readMap(key)
            let kept = map.filter { entry in
                guard let id = Int64(entry.key) else { return false }
                return watchIds.contains(id)
            }
            if kept.count != map.count { writeMap(key, kept) }
        }
    }

    // MARK: Open Interest

    static func oiSample(_ watchId: Int64) -> OiSample? {
        lock.lock(); defer { lock.unlock() }
        guard let o = readMap(keyOi)[String(watchId)] as? [String: Any] else { return nil }
        return OiSample(units: number(o["u"]) ?? 0, time: int64(o["t"]) ?? 0)
    }

    static func setOiSample(_ watchId: Int64, _ sample: OiSample) {
        lock.lock(); defer { lock.unlock() }
        var map = readMap(keyOi)
        map[String(watchId)] = ["u": sample.units.isFinite ? sample.units : 0.0, "t": sample.time] as [String: Any]
        writeMap(keyOi, map)
    }

    // MARK: Meldungen

    static func lastNotifiedAt(_ watchId: Int64) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return int64(readMap(keyNotified)[String(watchId)]) ?? 0
    }

    static func setNotifiedAt(_ watchId: Int64, _ time: Int64) {
        lock.lock(); defer { lock.unlock() }
        var map = readMap(keyNotified)
        map[String(watchId)] = time
        writeMap(keyNotified, map)
    }

    // MARK: Speichern (nur unter `lock` aufrufen)

    private static func readMap(_ key: String) -> [String: Any] {
        guard let text = SharedStorage.defaults.string(forKey: key), let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return object
    }

    private static func writeMap(_ key: String, _ map: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(map),
              let data = try? JSONSerialization.data(withJSONObject: map),
              let text = String(data: data, encoding: .utf8) else { return }
        SharedStorage.defaults.set(text, forKey: key)
    }

    private static func encode(_ map: [Int64: ActivityReport]) -> [String: Any] {
        var root: [String: Any] = [:]
        for (id, report) in map {
            let signals: [[String: Any]] = report.signals.map { s in
                [
                    "k": s.kind.rawValue,
                    "sv": s.severity.rawValue,
                    "v": s.value.isFinite ? s.value : 0.0,
                    "f": s.factor.flatMap { $0.isFinite ? $0 : nil }.map { $0 as Any } ?? NSNull(),
                    "a": s.seenAt,
                ] as [String: Any]
            }
            root[String(id)] = ["t": report.computedAt, "s": signals] as [String: Any]
        }
        return root
    }

    /// Unlesbare Einträge werden übersprungen statt alles zu verwerfen.
    private static func decode(_ root: [String: Any]) -> [Int64: ActivityReport] {
        var out: [Int64: ActivityReport] = [:]
        for (key, value) in root {
            guard let id = Int64(key), let o = value as? [String: Any] else { continue }
            let array = o["s"] as? [[String: Any]] ?? []
            let signals: [ActivitySignal] = array.compactMap { s in
                guard let kind = (s["k"] as? String).flatMap(ActivitySignalKind.init(rawValue:)) else { return nil }
                return ActivitySignal(
                    kind: kind,
                    severity: (s["sv"] as? String).flatMap(ActivitySignalSeverity.init(rawValue:)) ?? .NOTABLE,
                    value: number(s["v"]) ?? 0,
                    factor: number(s["f"]).flatMap { $0.isNaN ? nil : $0 },
                    seenAt: int64(s["a"]) ?? 0
                )
            }
            out[id] = ActivityReport(signals: signals, computedAt: int64(o["t"]) ?? 0)
        }
        return out
    }

    private static func number(_ value: Any?) -> Double? {
        (value as? NSNumber)?.doubleValue
    }

    private static func int64(_ value: Any?) -> Int64? {
        (value as? NSNumber)?.int64Value
    }
}
