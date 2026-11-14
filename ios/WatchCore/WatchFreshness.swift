import Foundation

/// Alter der Kurse auf der Uhr (Uhr-App und Komplikation): «veraltet» nach derselben Regel wie Merkliste und Widgets
/// (`OutdatedRule`: dreimal das Intervall, mindestens 15 Min.), mit dem Intervall des iPhones (`WatchSettings.interval`,
/// begrenzt wie `WatchInterval.clamp`) — also nach 45 Min. bei 15, 90 Min. bei 30, 3 Std. bei 60. Gezählt je Coin ab
/// dem Stand seines Abrufs (letzter Abruf, der diesen Coin lieferte), nicht ab dem letzten Handel: die 24-h-Ticker der Börsen geben dafür keine verlässliche Zeit. Alter für VoiceOver in
/// Minuten, Stunden, Tagen wie `A11ySpeech.age` (Android). Wie Android `WatchFreshness` (`Parity/watch_quotes.json`,
/// Abgleich mit `OutdatedRule` in `WatchQuotesParityTests`).
enum WatchFreshness {
    /// Takt der Komplikation: Zeitleiste alle 15 Min. (Android: `UPDATE_PERIOD_SECONDS` = 900).
    static let updateMinutes = 15

    /// = `OutdatedRule.afterMillis(backgroundIntervalMinutes: WatchInterval.clamp(intervalMinutes))` = 3 × Intervall
    /// (das begrenzte Intervall ist mindestens 15 Min., die Untergrenze von `OutdatedRule` greift also nie).
    static func outdatedAfterMillis(_ intervalMinutes: Int) -> Int64 {
        3 * Int64(WatchInterval.clamp(intervalMinutes)) * 60_000
    }

    /// Veraltet beim Intervall `intervalMinutes` (`outdatedAfterMillis`)? Nie geladen (≤ 0) nie — dann steht ohnehin
    /// «—». Wie `OutdatedRule.isOutdated` (strikt älter).
    static func isOutdated(_ time: Int64, now: Int64, intervalMinutes: Int) -> Bool {
        time > 0 && now - time > outdatedAfterMillis(intervalMinutes)
    }

    /// Einheit des Alters; Rohwerte wie im Fixture.
    enum AgeUnit: String, Sendable { case justNow, minutes, hours, days }

    /// Alter in einer Einheit (`count` ganze Einheiten; bei `.justNow` 0).
    struct Age: Equatable, Sendable {
        let unit: AgeUnit
        let count: Int
    }

    /// Alter von `time` zu `now`: unter 1 Min. (auch Zeit in der Zukunft) «gerade eben», dann Minuten, Stunden, Tage.
    static func age(_ time: Int64, now: Int64) -> Age {
        let minute: Int64 = 60_000, hour = 60 * minute, day = 24 * hour
        let diff = now - time
        if diff < minute { return Age(unit: .justNow, count: 0) }
        if diff < hour { return Age(unit: .minutes, count: Int(diff / minute)) }
        if diff < day { return Age(unit: .hours, count: Int(diff / hour)) }
        return Age(unit: .days, count: Int(min(diff / day, Int64(Int.max))))
    }
    /// Alter knapp in einer Einheit für kleine Komplikationen («47m», «3h», «2d»; Deutsch «47 Min.»), vom System
    /// übersetzt; nil unter einer Minute. Wie Android `TimeDifferenceStyle.SHORT_SINGLE_UNIT`.
    static func shortAge(_ time: Int64, now: Int64, locale: Locale = .current) -> String? {
        let a = age(time, now: now)
        let unit: NSCalendar.Unit, seconds: Double
        switch a.unit {
        case .justNow: return nil
        case .minutes: unit = .minute; seconds = Double(a.count) * 60
        case .hours: unit = .hour; seconds = Double(a.count) * 3_600
        case .days: unit = .day; seconds = Double(a.count) * 86_400
        }
        let f = DateComponentsFormatter()
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        f.calendar = calendar
        f.unitsStyle = .abbreviated
        f.allowedUnits = [unit]
        f.maximumUnitCount = 1
        return f.string(from: seconds)
    }

    /// Titel einer kleinen Komplikation (rund, Ecke): veraltet mit dem Alter «BTC 47m», sonst nur der Titel — wie
    /// Android `shortTitle`/`aged` (Kurztitel «BTC ^1»).
    static func shortTitle(_ title: String, time: Int64, now: Int64, intervalMinutes: Int,
                           locale: Locale = .current) -> String {
        guard isOutdated(time, now: now, intervalMinutes: intervalMinutes), let age = shortAge(time, now: now, locale: locale) else { return title }
        return title + " " + age
    }

    /// Zeile (inline): veraltet «BTC 84’450 · 47m», sonst unverändert.
    static func inlineLine(_ text: String, time: Int64, now: Int64, intervalMinutes: Int,
                           locale: Locale = .current) -> String {
        guard isOutdated(time, now: now, intervalMinutes: intervalMinutes), let age = shortAge(time, now: now, locale: locale) else { return text }
        return text + " · " + age
    }
}
