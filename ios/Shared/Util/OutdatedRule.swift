import Foundation

/// Ab wann ein Kurs «veraltet» heisst — eine Regel für Merkliste, Portfolio und Widgets (wie
/// `OutdatedRule` in Android): dreimal das Hintergrund-Intervall, mindestens 15 Minuten.
/// Abgefragt wird nur im Hintergrund-Takt oder von Hand; einen eigenen schnelleren Abfrage-Takt
/// gibt es nicht. Paare am Live-Strom haben ihre eigene,
/// kürzere Grenze `liveMillis` (siehe `LiveStatus`).
/// Gemeinsame Testfälle: `Tests/Parity/outdated.json`.
enum OutdatedRule {
    /// Untergrenze: nie früher als nach 15 Minuten «veraltet».
    static let minMillis: Int64 = 15 * 60_000

    /// Paar am Live-Strom: nach 2 Minuten ohne neuen Kurs «veraltet».
    static let liveMillis: Int64 = 2 * 60_000

    /// Alter, ab dem ein abgefragter Kurs veraltet ist: max(3 × Hintergrund-Intervall, 15 Min.).
    static func afterMillis(backgroundIntervalMinutes: Int) -> Int64 {
        max(SafeMillis.minutes(backgroundIntervalMinutes) * 3, minMillis)
    }

    /// Veraltet? Ohne Zeitpunkt (≤ 0) nie — dann steht ohnehin «—».
    static func isOutdated(_ time: Int64, now: Int64, afterMillis: Int64) -> Bool {
        time > 0 && now - time > afterMillis
    }

    /// Nächster Zeitpunkt, an dem einer der `times` veraltet wird (eine Millisekunde nach der
    /// Grenze), oder nil, wenn keiner mehr wechselt. Für den zweiten Timeline-Eintrag der Widgets.
    static func nextChangeAt(_ times: [Int64], now: Int64, afterMillis: Int64) -> Int64? {
        times.filter { $0 > 0 && !isOutdated($0, now: now, afterMillis: afterMillis) }
            .min()
            .map { $0 + afterMillis + 1 }
    }
}
