import Foundation

/// Darstellung «Nach Uhrzeit»: dunkel ab `AppSettings.themeDarkFrom` bis `themeLightFrom`, sonst hell — für App und
/// Widgets (Widget-Wahl «System» folgt der App). Zeiten in Minuten seit Mitternacht (0..1439), Ortszeit des Geräts; der
/// Zeitraum darf über Mitternacht gehen (Vorgabe 23:00–07:00). Beginn gehört dazu, Ende nicht; gleiche Zeiten = nie
/// dunkel (wie `QuietHours.isQuiet`). Wie Android `ThemeSchedule`, gemeinsame Testfälle `theme_schedule.json`.
enum ThemeSchedule {
    static let defaultDarkFrom = 23 * 60
    static let defaultLightFrom = 7 * 60

    static func isDarkAt(darkFrom: Int, lightFrom: Int, minuteOfDay: Int) -> Bool {
        QuietHours.isQuiet(enabled: true, start: darkFrom, end: lightFrom, minuteOfDay: minuteOfDay)
    }

    /// Hell/Dunkel, das die App erzwingt: nil = wie das System. «Nach Uhrzeit» (`scheduled`) geht vor `darkMode`.
    static func forcedDark(darkMode: Bool?, scheduled: Bool, darkFrom: Int, lightFrom: Int, minuteOfDay: Int) -> Bool? {
        scheduled ? isDarkAt(darkFrom: darkFrom, lightFrom: lightFrom, minuteOfDay: minuteOfDay) : darkMode
    }

    /// Wie oben mit den Einstellungen zum Zeitpunkt `date`.
    static func forcedDark(_ settings: AppSettings, at date: Date = Date(), calendar: Calendar = .current) -> Bool? {
        forcedDark(darkMode: settings.darkMode, scheduled: settings.themeScheduled, darkFrom: settings.themeDarkFrom,
                   lightFrom: settings.themeLightFrom,
                   minuteOfDay: QuietHours.minuteOfDay(date, calendar: calendar))
    }

    /// Wirksam dunkel: erzwungen oder sonst wie das System (`systemDark`).
    static func isDark(_ settings: AppSettings, systemDark: Bool, at date: Date = Date()) -> Bool {
        forcedDark(settings, at: date) ?? systemDark
    }

    /// Nächster Wechsel nach `now`; nil ohne «Nach Uhrzeit», mit ungültigen oder gleichen Zeiten.
    static func nextSwitch(_ settings: AppSettings, after now: Date, calendar: Calendar = .current) -> Date? {
        guard settings.themeScheduled else { return nil }
        return nextSwitch(darkFrom: settings.themeDarkFrom, lightFrom: settings.themeLightFrom, after: now,
                          calendar: calendar)
    }

    /// Die nähere der beiden Zeiten als Ortszeit strikt nach `now`. Fällt eine Zeit in die übersprungene Stunde der
    /// Sommerzeit, gilt die nächste Uhrzeit danach (der Wechsel kommt dann früher, nie später).
    static func nextSwitch(darkFrom: Int, lightFrom: Int, after now: Date, calendar: Calendar = .current) -> Date? {
        guard QuietHours.isValidMinute(darkFrom), QuietHours.isValidMinute(lightFrom), darkFrom != lightFrom else {
            return nil
        }
        let times = [darkFrom, lightFrom].compactMap { next($0, after: now, calendar: calendar) }
        return times.min()
    }

    private static func next(_ minute: Int, after now: Date, calendar: Calendar) -> Date? {
        let today = calendar.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: now,
                                  matchingPolicy: .nextTime)
        if let today, today > now { return today }
        guard let tomorrow = calendar.date(byAdding: .day, value: 1, to: now) else { return nil }
        return calendar.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: tomorrow,
                             matchingPolicy: .nextTime)
    }
}
