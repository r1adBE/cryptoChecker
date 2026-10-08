import Foundation

/// Ab wann ein Kurs «veraltet» heisst — eine Regel für Merkliste und Widgets (wie
/// `OutdatedRule` in Android):
///  - Live-Modus (App vorne mit Live-Abfrage oder Live-Stream, Eingabe `live`): nach
///    `liveMillis` (2 Min.); bei einem langsamen Live-Intervall (5 Min.) erst nach zwei Intervallen.
///  - sonst (Hintergrund/REST): dreimal das eingestellte Intervall (Live: Sekunden, sonst
///    Hintergrund-Minuten), mindestens 15 Minuten.
/// Gemeinsame Testfälle: `Tests/Parity/outdated.json`.
enum OutdatedRule {
    /// Untergrenze: nie früher als nach 15 Minuten «veraltet».
    static let minMillis: Int64 = 15 * 60_000

    /// Live-Modus: nach 2 Minuten «veraltet».
    static let liveMillis: Int64 = 2 * 60_000

    /// Eingestelltes Abfrage-Intervall in Millisekunden.
    static func intervalMillis(liveService: Bool, liveIntervalSeconds: Int, backgroundIntervalMinutes: Int) -> Int64 {
        liveService ? Int64(liveIntervalSeconds) * 1000 : Int64(backgroundIntervalMinutes) * 60_000
    }

    /// Alter, ab dem ein Kurs veraltet ist. `live` = Live-Modus aktiv (App vorne mit Live-Abfrage
    /// oder Live-Stream): max(2 Min., 2 × Live-Intervall); sonst max(3 × Intervall, 15 Min.).
    static func afterMillis(liveService: Bool, liveIntervalSeconds: Int, backgroundIntervalMinutes: Int,
                            live: Bool = false) -> Int64 {
        if live {
            // Ohne Live-Abfrage (nur Stream) zählt allein die 2-Minuten-Grenze
            let twoIntervals: Int64 = liveService ? Int64(liveIntervalSeconds) * 2000 : 0
            return max(liveMillis, twoIntervals)
        }
        let interval = intervalMillis(liveService: liveService, liveIntervalSeconds: liveIntervalSeconds,
                                      backgroundIntervalMinutes: backgroundIntervalMinutes)
        return max(interval * 3, minMillis)
    }

    /// Dasselbe aus den Einstellungen.
    static func afterMillis(_ settings: AppSettings, live: Bool = false) -> Int64 {
        afterMillis(liveService: settings.liveService, liveIntervalSeconds: settings.liveIntervalSeconds,
                    backgroundIntervalMinutes: settings.backgroundIntervalMinutes, live: live)
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
