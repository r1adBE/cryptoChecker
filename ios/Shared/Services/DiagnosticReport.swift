import Foundation

/// Einstellungen, die für Alarme zählen — bewusst eigene Felder statt einer freien Liste: kein Platz für Beträge.
/// Wie `DiagnosticSettings` (Android).
struct DiagnosticSettings: Sendable {
    var updateMode: String
    var backgroundIntervalMinutes: Int
    /// «Pünktlich prüfen»; nil = gibt es auf dieser Plattform nicht (iOS).
    var exactChecks: Bool?
    var priceNotifications: Bool
    var alarmSignal: String
    var alarmCooldownMinutes: Int
    var quietHoursEnabled: Bool
    var quietHoursStart: Int
    var quietHoursEnd: Int
    var activeAlarms: Int
}

/// Alles für «Diagnose teilen». Bewusst ohne Portfolio, Beträge, Adressen und Notizen: diese Typen kommen hier gar
/// nicht vor. Vom Alarm-Protokoll gehen nur Zeit, Paar, Börse, Bedingung und Ergebnis in den Text — keine Kurse und
/// keine Schwellen. Wie `DiagnosticInput` (Android).
struct DiagnosticInput: Sendable {
    var createdAt: Int64
    /// Zeitzone des Geräts (z. B. «Europe/Zurich»); alle Zeiten im Text in dieser Zone.
    var zone: String
    var appVersion: String
    var appBuild: String
    /// z. B. «iOS 18.1».
    var system: String
    var device: String
    var locale: String
    var settings: DiagnosticSettings
    var readiness: [AlarmReadiness.Item]
    var checkRuns: [CheckRun]
    var alarmEvents: [AlarmLogEntry]
    var exchangePauses: [String: ExchangeBackoff.State]
    /// Fehlerprotokoll des Release-Builds; nil = die App führt keines.
    var errorLog: [String]? = nil
}

/// «Diagnose teilen» (Build 81): reiner Textbericht, nur auf dem Gerät erstellt und erst nach der Vorschau über das
/// Teilen-Menü weitergegeben — nichts wird automatisch gesendet. Bezeichnungen fest auf Englisch, Werte als Codes.
/// Gleiches Format wie `DiagnosticReport.kt`.
enum DiagnosticReport {
    static let fileName = "cryptochecker-diagnostics.txt"
    static let maxCheckRuns = 20
    static let redacted = "[redacted]"
    private static let none = "(none)"

    // Alles, was nach einer Adresse oder einem erweiterten Schlüssel aussieht, fliegt raus — wie Android
    private static let addressPatterns: [NSRegularExpression] = [
        #"0x[0-9a-fA-F]{16,}"#,
        #"\b(?:bc1|tb1|ltc1|bcrt1)[0-9a-zA-Z]{11,}\b"#,
        #"\b[xyzuvtXYZUVT]pub[1-9A-HJ-NP-Za-km-z]{20,}\b"#,
        #"\b[1-9A-HJ-NP-Za-km-z]{25,}\b"#,
    ].compactMap { try? NSRegularExpression(pattern: $0) }

    /// Adressähnliche Zeichenfolgen durch `redacted` ersetzen.
    static func redact(_ text: String) -> String {
        addressPatterns.reduce(text) { acc, regex in
            regex.stringByReplacingMatches(in: acc, range: NSRange(acc.startIndex..., in: acc),
                                           withTemplate: NSRegularExpression.escapedTemplate(for: redacted))
        }
    }

    static func build(_ input: DiagnosticInput) -> String {
        let zone = TimeZone(identifier: input.zone) ?? TimeZone(identifier: "UTC")!
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        func at(_ millis: Int64) -> String {
            formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
        }
        let s = input.settings
        var lines: [String] = []
        lines.append("Crypto Checker diagnostics")
        lines.append("Created: \(at(input.createdAt)) (\(zone.identifier))")
        lines.append("App: \(clean(input.appVersion)) (\(clean(input.appBuild)))")
        lines.append("System: \(clean(input.system))")
        lines.append("Device: \(clean(input.device))")
        lines.append("Language: \(clean(input.locale))")
        lines.append("Contains no portfolio data, amounts, addresses or notes. Nothing is sent automatically.")

        lines.append("")
        lines.append("== Settings ==")
        lines.append("Update mode: \(clean(s.updateMode))")
        lines.append("Background interval: \(s.backgroundIntervalMinutes) min")
        if let exact = s.exactChecks { lines.append("Exact checks: \(onOff(exact))") }
        lines.append("Price notifications: \(onOff(s.priceNotifications))")
        lines.append("Alarm signal: \(clean(s.alarmSignal))")
        lines.append("Alarm cooldown: \(s.alarmCooldownMinutes) min")
        lines.append("Quiet hours: " + (s.quietHoursEnabled ? "on \(clock(s.quietHoursStart))-\(clock(s.quietHoursEnd))" : "off"))
        lines.append("Active alarms: \(s.activeAlarms)")

        lines.append("")
        lines.append("== Alarm delivery ==")
        if input.readiness.isEmpty { lines.append(none) }
        for item in input.readiness {
            lines.append("\(item.row.rawValue): \(item.state.rawValue) (\(item.detail.rawValue))")
        }

        lines.append("")
        lines.append("== Last checks (newest first) ==")
        let runs = input.checkRuns.prefix(maxCheckRuns)
        if runs.isEmpty { lines.append(none) }
        for run in runs {
            let seconds = String(format: "%.1f s", Double(max(run.durationMillis, 0)) / 1000)
            lines.append([at(run.at), run.trigger.rawValue, run.result.rawValue, seconds].joined(separator: " | "))
        }

        lines.append("")
        lines.append("== Alarm log (newest first) ==")
        if input.alarmEvents.isEmpty { lines.append(none) }
        for e in input.alarmEvents {
            let period = e.until > e.at ? "\(at(e.at)) - \(at(e.until))" : at(e.at)
            lines.append([period, clean(e.pair), clean(e.exchange), e.condition.rawValue, e.outcome.rawValue]
                .joined(separator: " | "))
        }

        lines.append("")
        lines.append("== Exchange pauses ==")
        if input.exchangePauses.isEmpty { lines.append(none) }
        for key in input.exchangePauses.keys.sorted() {
            guard let state = input.exchangePauses[key] else { continue }
            let paused = ExchangeBackoff.isPaused(state, now: input.createdAt)
                ? "paused until \(at(state.pausedUntil))" : "not paused"
            lines.append([clean(key), paused, state.reason?.rawValue ?? "-", "strikes \(state.strikes)"]
                .joined(separator: " | "))
        }

        lines.append("")
        lines.append("== Error log ==")
        if let errors = input.errorLog {
            if errors.isEmpty { lines.append(none) }
            lines.append(contentsOf: errors.map(clean))
        } else {
            lines.append("none - the app keeps no error log")
        }
        return lines.joined(separator: "\n") + "\n"
    }

    /// Eine Zeile, ohne Steuerzeichen und ohne Adressen.
    private static func clean(_ text: String) -> String {
        let flat = text.components(separatedBy: CharacterSet(charactersIn: "\r\n\t"))
            .filter { !$0.isEmpty }.joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
        let result = redact(flat)
        return result.isEmpty ? "-" : result
    }

    private static func onOff(_ on: Bool) -> String { on ? "on" : "off" }

    private static func clock(_ minute: Int) -> String {
        let m = ((minute % 1440) + 1440) % 1440
        return String(format: "%02d:%02d", m / 60, m % 60)
    }
}
