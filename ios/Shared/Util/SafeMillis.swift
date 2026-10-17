import Foundation

/// Dauer in Millisekunden aus einer Einstellung (Minuten, Sekunden, Stunden) — ohne Überlauf-Absturz:
/// `Int64(x) * 60_000` bricht in Swift bei sehr grossen Werten ab (z. B. aus einer manipulierten Sicherung).
/// Ergebnis höchstens ±`limit` (rund 100 Jahre), damit auch Summen mit Zeitstempeln nicht überlaufen.
/// Android braucht das nicht: dort ist `Int` 32 Bit, `minutes * 60_000L` passt immer in `Long`.
enum SafeMillis {
    static let limit: Int64 = 100 * 365 * 86_400_000

    /// `count` × `unitMillis` (`unitMillis` > 0), begrenzt auf ±`limit`.
    static func times(_ count: Int, _ unitMillis: Int64) -> Int64 {
        let (value, overflow) = Int64(count).multipliedReportingOverflow(by: unitMillis)
        if overflow { return count < 0 ? -limit : limit }
        return min(max(value, -limit), limit)
    }

    static func seconds(_ seconds: Int) -> Int64 { times(seconds, 1_000) }
    static func minutes(_ minutes: Int) -> Int64 { times(minutes, 60_000) }
    static func hours(_ hours: Int) -> Int64 { times(hours, 3_600_000) }
}
