import Foundation

/// Nachtruhe — wie `QuietHours.kt`: Alarme kommen in dieser Zeit lautlos
/// (kein Ton, keine Ansage, `interruptionLevel = .passive`), werden aber wie
/// gewohnt ausgewertet, gemerkt und gemeldet.
///
/// Zeiten als Minuten seit Mitternacht (0…1439). Der Bereich darf über
/// Mitternacht gehen (23:00–07:00) oder nicht (13:00–14:00); Beginn == Ende
/// heisst «nie».
enum QuietHours {
    static let defaultStart = 23 * 60
    static let defaultEnd = 7 * 60
    static let minutesPerDay = 24 * 60

    /// Liegt `minuteOfDay` in der Nachtruhe? Beginn einschliesslich, Ende ausschliesslich.
    static func isQuiet(enabled: Bool, start: Int, end: Int, minuteOfDay: Int) -> Bool {
        guard enabled, isValidMinute(start), isValidMinute(end), start != end else { return false }
        let now = ((minuteOfDay % minutesPerDay) + minutesPerDay) % minutesPerDay
        if start < end {
            return now >= start && now < end
        }
        // Über Mitternacht, z. B. 23:00–07:00
        return now >= start || now < end
    }

    /// Nachtruhe jetzt (Ortszeit des Geräts) nach den Einstellungen.
    static func isQuietNow(_ settings: AppSettings, date: Date = Date()) -> Bool {
        isQuiet(enabled: settings.quietHoursEnabled, start: settings.quietHoursStart,
                end: settings.quietHoursEnd, minuteOfDay: minuteOfDay(date))
    }

    /// Minuten seit Mitternacht in der Ortszeit des Geräts.
    static func minuteOfDay(_ date: Date = Date(), calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return (c.hour ?? 0) * 60 + (c.minute ?? 0)
    }

    static func isValidMinute(_ minute: Int) -> Bool {
        (0..<minutesPerDay).contains(minute)
    }

    /// Uhrzeit im kurzen Format der Region, z. B. «23:00» oder «11:00 PM».
    static func format(_ minute: Int, locale: Locale = .current) -> String {
        let m = isValidMinute(minute) ? minute : 0
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date(for: m))
    }

    /// Heutiges Datum zur Minute `minute` (für Zeitwähler).
    static func date(for minute: Int, calendar: Calendar = .current) -> Date {
        let start = calendar.startOfDay(for: Date())
        let m = isValidMinute(minute) ? minute : 0
        return calendar.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: start) ?? start
    }
}
