import Foundation

/// Eine Stunde (`hour` = Millisekunden seit 1970 / 1 h): Hintergrund-Läufe, Netzanfragen und empfangene Bytes.
struct UsageBucket: Equatable, Sendable {
    let hour: Int64
    let runs: Int
    let requests: Int
    let bytes: Int64
}

/// Summe über ein Fenster (24 h): Hintergrund-Läufe, Netzanfragen, empfangene Bytes.
struct UsageTotals: Equatable, Sendable {
    let runs: Int
    let requests: Int
    let bytes: Int64

    static let zero = UsageTotals(runs: 0, requests: 0, bytes: 0)
}

/// Stand der Uhr fürs iPhone: Summe der letzten 24 h zur Zeit `at`.
struct UsageReport: Equatable, Sendable {
    let at: Int64
    let totals: UsageTotals
}

/// Einstufung «gering / mittel / hoch» (`UsageLog.level`); `rawValue` steht im Testfall (gleich auf Android).
enum UsageLevel: String, CaseIterable, Sendable {
    case low, medium, high
}

/// Was die Aktivität vor allem treibt (`UsageLog.driver`); `rawValue` steht im Testfall (gleich auf Android).
enum UsageDriver: String, CaseIterable, Sendable {
    /// Keine Hintergrund-Prüfung geplant: es zählt, was die offene App und die Widgets abrufen.
    case none
    /// Hintergrund-Prüfung im gewählten Intervall.
    case interval
    /// Dazu «Pünktlich prüfen» (nur Android; iOS übergibt nie `exactActive`).
    case intervalExact = "interval_exact"
}

/// «Akku & Aktivität»: Apps können ihren eigenen Akkuverbrauch nicht auslesen — gezählt werden darum je Stunde nur
/// Hintergrund-Läufe, Netzanfragen und empfangene Bytes, auf dem Gerät, ohne Inhalte und ohne Hosts. Gezeigt wird die
/// Summe der letzten `windowHours` Stunden; behalten höchstens `keepHours`. Feste Grenzen je 24 h (gleich auf Android,
/// `Tests/Parity/usage_log.json`): mittel ab 48 Läufen, 500 Anfragen oder 10 MB, hoch ab 144, 2000 bzw. 50 MB; es zählt
/// die höchste der drei Stufen. Datei: eine Zeile je Stunde `stunde;läufe;anfragen;bytes`. Reine Logik — wie
/// `UsageLog.kt`.
enum UsageLog {
    static let hourMillis: Int64 = 3_600_000
    static let windowHours: Int64 = 24
    static let keepHours: Int64 = 168

    /// 48 Läufe = alle 30 Min.; 144 = alle 10 Min. (etwa Intervall 15 Min. und «Pünktlich prüfen» auf Android).
    static let mediumRuns = 48
    static let highRuns = 144
    static let mediumRequests = 500
    static let highRequests = 2_000
    static let mediumBytes: Int64 = 10_000_000
    static let highBytes: Int64 = 50_000_000

    /// Höchstens so viele Zeilen lesen (eine Datei wächst nie darüber, `merge` kürzt).
    private static let maxLines = 1_000
    private static let reportVersion = "1"

    /// Stunde zu `millis` (abgerundet, auch vor 1970).
    static func hourOf(_ millis: Int64) -> Int64 {
        let q = millis / hourMillis
        return (millis % hourMillis != 0 && millis < 0) ? q - 1 : q
    }

    /// `runs`, `requests` und `bytes` zur Stunde von `now` dazuzählen.
    static func add(_ buckets: [UsageBucket], now: Int64, runs: Int = 0, requests: Int = 0, bytes: Int64 = 0) -> [UsageBucket] {
        merge(buckets, [UsageBucket(hour: hourOf(now), runs: runs, requests: requests, bytes: bytes)], now: now)
    }

    /// `a` und `b` je Stunde zusammenzählen, aufsteigend; nur Stunden der letzten `keepHours` bis zur Stunde von `now`
    /// (spätere = falsch gestellte Uhr, fallen weg). Negative Werte zählen als 0, Summen höchstens `Int32.max` bzw.
    /// `Int64.max` — wie Android (`Int.MAX_VALUE`).
    static func merge(_ a: [UsageBucket], _ b: [UsageBucket], now: Int64) -> [UsageBucket] {
        let current = hourOf(now)
        var sums: [Int64: (Int64, Int64, Int64)] = [:]
        for bucket in a + b where bucket.hour > current - keepHours && bucket.hour <= current {
            let s = sums[bucket.hour] ?? (0, 0, 0)
            sums[bucket.hour] = (plus(s.0, Int64(bucket.runs)), plus(s.1, Int64(bucket.requests)), plus(s.2, bucket.bytes))
        }
        return sums.keys.sorted().map { hour in
            let s = sums[hour] ?? (0, 0, 0)
            return UsageBucket(hour: hour, runs: toInt(s.0), requests: toInt(s.1), bytes: s.2)
        }
    }

    /// Summe der letzten `windowHours` Stunden bis zur Stunde von `now`.
    static func totals(_ buckets: [UsageBucket], now: Int64) -> UsageTotals {
        let current = hourOf(now)
        var runs: Int64 = 0
        var requests: Int64 = 0
        var bytes: Int64 = 0
        for bucket in buckets where bucket.hour > current - windowHours && bucket.hour <= current {
            runs = plus(runs, Int64(bucket.runs))
            requests = plus(requests, Int64(bucket.requests))
            bytes = plus(bytes, bucket.bytes)
        }
        return UsageTotals(runs: toInt(runs), requests: toInt(requests), bytes: bytes)
    }

    /// Höchste Stufe der drei Zahlen nach den festen Grenzen.
    static func level(_ totals: UsageTotals) -> UsageLevel {
        if totals.runs >= highRuns || totals.requests >= highRequests || totals.bytes >= highBytes { return .high }
        if totals.runs >= mediumRuns || totals.requests >= mediumRequests || totals.bytes >= mediumBytes { return .medium }
        return .low
    }

    /// Treiber: ohne geplante Hintergrund-Prüfung `.none`, mit wirksamem «Pünktlich prüfen» dazu dieses.
    static func driver(backgroundPlanned: Bool, exactActive: Bool) -> UsageDriver {
        if !backgroundPlanned { return .none }
        return exactActive ? .intervalExact : .interval
    }

    static func encode(_ buckets: [UsageBucket]) -> String {
        buckets.map { "\($0.hour);\($0.runs);\($0.requests);\($0.bytes)" }.joined(separator: "\n")
    }

    /// Unlesbare Zeilen fallen weg, negative Zahlen zählen als 0; Reihenfolge der Datei (`merge` ordnet).
    static func decode(_ text: String?) -> [UsageBucket] {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        var out: [UsageBucket] = []
        for raw in text.split(separator: "\n", omittingEmptySubsequences: false).prefix(maxLines) {
            let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ";", omittingEmptySubsequences: false)
                .map(String.init)
            guard parts.count == 4, let hour = Int64(parts[0]), let runs = Int32(parts[1]),
                  let requests = Int32(parts[2]), let bytes = Int64(parts[3]) else { continue }
            out.append(UsageBucket(hour: hour, runs: max(Int(runs), 0), requests: max(Int(requests), 0),
                                   bytes: max(bytes, 0)))
        }
        return out
    }

    /// Stand der Uhr als Text fürs iPhone: `1;zeit;läufe;anfragen;bytes`.
    static func encodeReport(_ report: UsageReport) -> String {
        let t = report.totals
        return "\(reportVersion);\(report.at);\(t.runs);\(t.requests);\(t.bytes)"
    }

    /// nil = fehlt, andere Fassung oder unlesbar (dann zeigt das iPhone «Keine Uhr verbunden»).
    static func decodeReport(_ text: String?) -> UsageReport? {
        guard let parts = text?.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ";", omittingEmptySubsequences: false).map(String.init),
              parts.count == 5, parts[0] == reportVersion,
              let at = Int64(parts[1]), at > 0,
              let runs = Int32(parts[2]), runs >= 0,
              let requests = Int32(parts[3]), requests >= 0,
              let bytes = Int64(parts[4]), bytes >= 0 else { return nil }
        return UsageReport(at: at, totals: UsageTotals(runs: Int(runs), requests: Int(requests), bytes: bytes))
    }

    /// Länge von `text` in UTF-8 (Bytes einer Live-Nachricht); wie `UsageLog.utf8Length` auf Android.
    static func utf8Length(_ text: String) -> Int64 { Int64(text.utf8.count) }

    /// Summe ohne Überlauf; negative Teile zählen als 0.
    private static func plus(_ a: Int64, _ b: Int64) -> Int64 {
        let add = max(b, 0)
        return a > Int64.max - add ? Int64.max : a + add
    }

    private static func toInt(_ value: Int64) -> Int { Int(min(max(value, 0), Int64(Int32.max))) }
}
