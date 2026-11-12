import Foundation

/// Ziel der Mitteilung eines Portfolio-Alarms: «portfolioAlarm/<Alarm>» — öffnet «Alle Alarme» mit dem ausgelösten
/// Portfolio-Alarm hervorgehoben (`AppRouter.highlightPortfolioAlarmId`). Die Mitteilung selbst bleibt neutral (kein
/// Betrag); nur die Kennung steht im Ziel. Wie Android `PortfolioAlarmTarget`.
enum PortfolioAlarmTarget {
    private static let prefix = "portfolioAlarm/"

    static func of(_ alarmId: Int64) -> String { "\(prefix)\(alarmId)" }

    /// Alarm aus `target`; nil bei anderen oder kaputten Zielen.
    static func parse(_ target: String?) -> Int64? {
        guard let target, target.hasPrefix(prefix), let id = Int64(target.dropFirst(prefix.count)), id > 0 else { return nil }
        return id
    }
}
