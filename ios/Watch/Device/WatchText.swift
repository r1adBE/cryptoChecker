import Foundation

/// Texte der Uhr aus `Localizable.xcstrings` (Android-Schlüssel, 75 Sprachen) und Zahlen kurz formatiert — wie
/// Android `WatchFormat`; Alter veralteter Kurse wie Android `WatchAge`.
enum WatchText {
    static func t(_ key: String, _ args: CVarArg...) -> String {
        let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
        return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
    }

    static func price(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale.current
        switch value {
        case 1000...: f.minimumFractionDigits = 2; f.maximumFractionDigits = 2
        case 1...: f.minimumFractionDigits = 2; f.maximumFractionDigits = 4
        case 0.01...: f.minimumFractionDigits = 4; f.maximumFractionDigits = 6
        default: f.minimumFractionDigits = 8; f.maximumFractionDigits = 10
        }
        return f.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Änderung in der Schreibweise der iPhone-App (`WatchPercent`, wie Android `WatchFormat.change`).
    static func change(_ value: Double?) -> String { WatchPercent.change(value) }

    /// «vor 47 Min.» — kurz und klein, steht hinter «+1.23%  ·» (wie Android `WatchAge.short`).
    static func ago(_ time: Int64, now: Int64) -> String {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale.current
        f.unitsStyle = .abbreviated
        f.formattingContext = .middleOfSentence
        return f.localizedString(for: date(time), relativeTo: date(now))
    }

    /// «Kurs veraltet, zuletzt aktualisiert vor 47 Minuten» für VoiceOver (wie Android `WatchAge.spoken`).
    static func spokenAge(_ time: Int64, now: Int64) -> String {
        let age = WatchFreshness.age(time, now: now)
        let updated: String? = switch age.unit {
        case .justNow: nil
        case .minutes: t("a11y_watch_updated_minutes", age.count)
        case .hours: t("a11y_watch_updated_hours", age.count)
        case .days: t("a11y_watch_updated_days", age.count)
        }
        return [t("a11y_watch_outdated"), updated].compactMap { $0 }.joined(separator: ", ")
    }

    /// Epoch-ms → Datum.
    static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(millis) / 1000) }
}
