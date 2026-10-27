import Foundation

/// Texte der Uhr aus `Localizable.xcstrings` (Android-Schlüssel, 31 Sprachen) und Zahlen kurz formatiert — wie
/// Android `WatchFormat`.
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

    static func change(_ value: Double?) -> String {
        guard let value, value.isFinite else { return "—" }
        let sign = abs(value) < 0.005 ? "" : (value > 0 ? "+" : "−")
        let f = NumberFormatter()
        f.locale = Locale.current
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        f.minimumIntegerDigits = 1
        return sign + (f.string(from: NSNumber(value: abs(value))) ?? "") + "%"
    }
}
