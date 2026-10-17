import Foundation

/// Nachtruhe — wie `QuietHours.kt`: Paar- und Portfolio-Alarme lösen in dieser Zeit
/// nicht aus, sondern warten bis zum Ende (`holdsAlarms`). Übrige Mitteilungen
/// (Aktivität, Gebühren, Termine) kommen lautlos (`interruptionLevel = .passive`),
/// Ansagen schweigen.
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

    /// Eine Regel für Paar- und Portfolio-Alarme (wie `QuietHours.holdsAlarms`, Android): In der
    /// Nachtruhe wird nicht ausgelöst — der Alarm bleibt scharf und unverändert (nichts
    /// gespeichert, keine Abklingzeit, einmalige bleiben eingeschaltet). Nach dem Ende wertet die
    /// nächste Prüfung ihn wieder aus; gilt die Bedingung dann noch, meldet er sich ganz normal
    /// mit Ton — eine Mitteilung je Alarm, kein Nachholen gesammelter Auslösungen.
    /// Bewusst in Kauf genommen: Was nachts eintrat und wieder verging (Marke hin und zurück
    /// gekreuzt, Volumen-Spitze einer vergangenen Stunde), meldet sich danach nicht mehr.
    /// Wieder scharf stellen und Bezugskurse setzen läuft auch nachts weiter (kein Auslösen).
    static func holdsAlarms(enabled: Bool, start: Int, end: Int, minuteOfDay: Int) -> Bool {
        isQuiet(enabled: enabled, start: start, end: end, minuteOfDay: minuteOfDay)
    }

    /// Darf ein fälliger Paar- oder Portfolio-Alarm jetzt auslösen (speichern und melden)? Wie
    /// `QuietHours.mayFire` (Android): nicht in der Nachtruhe (`holdAlarms`) und nicht ohne
    /// zustellbare Mitteilung (`deliverable`: Erlaubnis da). Gilt für einmalige UND wiederholende
    /// Alarme: Der Alarm bleibt scharf und unverändert (keine Abklingzeit, Kreuzung nicht
    /// verbraucht) und meldet sich, sobald die Mitteilung wieder ankommt.
    static func mayFire(holdAlarms: Bool, deliverable: Bool) -> Bool {
        !holdAlarms && deliverable
    }

    /// `holdsAlarms` jetzt (Ortszeit des Geräts) nach den Einstellungen.
    static func holdsAlarmsNow(_ settings: AppSettings, date: Date = Date()) -> Bool {
        holdsAlarms(enabled: settings.quietHoursEnabled, start: settings.quietHoursStart,
                    end: settings.quietHoursEnd, minuteOfDay: minuteOfDay(date))
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
