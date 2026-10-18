import Foundation

/// Ziel «Anpassen» aus einer Alarm-Mitteilung: «alarm/<Paar>/<Alarm>» — öffnet die Alarme des Paars mit dem Editor
/// dieses Alarms. Reine Logik — wie Android `AlarmEditTarget`.
enum AlarmEditTarget {
    private static let prefix = "alarm/"

    static func of(watchId: Int64, alarmId: Int64) -> String { "\(prefix)\(watchId)/\(alarmId)" }

    /// Paar und Alarm aus `target`; nil bei anderen oder kaputten Zielen.
    static func parse(_ target: String?) -> (watchId: Int64, alarmId: Int64)? {
        guard let target, target.hasPrefix(prefix) else { return nil }
        let parts = target.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let watchId = Int64(parts[0]), watchId > 0,
              let alarmId = Int64(parts[1]), alarmId > 0 else { return nil }
        return (watchId, alarmId)
    }
}
