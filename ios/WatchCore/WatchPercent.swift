import Foundation

/// Prozent auf der Uhr (Uhr-App und Komplikation) in derselben Schreibweise wie die iPhone-App (`PercentText`,
/// `BidiText` in Shared/, das die Uhr-Ziele nicht einbinden): gleiche Tabelle, geprüft mit denselben Testfällen
/// (`Parity/percent_text.json`, auch `watch_change`). Wie Android `WatchFormat` (:wear).
enum WatchPercent {

    /// Änderung «+1.23%», Deutsch «+1,23 %», Türkisch «+%1,23»; in RTL-Sprachen (`language`) als
    /// links-nach-rechts-Insel, damit das Vorzeichen vor der Zahl bleibt.
    static func change(_ value: Double?, locale: Locale = .current, language: String = appLanguage) -> String {
        guard let value, value.isFinite else { return "—" }
        let text = signed(value, decimals: 2, locale: locale)
        return rtlLanguages.contains(language) ? "\u{2066}" + text + "\u{2069}" : text
    }

    /// Mit Vorzeichen vor dem Ganzen, echtes Minus U+2212; gerundet 0 ohne Vorzeichen (wie `PercentText.signed`).
    static func signed(_ value: Double, decimals: Int, locale: Locale) -> String {
        let sign = rounded(abs(value), decimals) == 0 ? "" : (value > 0 ? "+" : "−")
        return sign + unsigned(value, decimals: decimals, locale: locale)
    }

    /// Betrag mit genau `decimals` Nachkommastellen (kaufmännisch) und Prozentzeichen der Sprache
    /// (wie `PercentText.unsigned`).
    static func unsigned(_ value: Double, decimals: Int, locale: Locale) -> String {
        let number = format(rounded(abs(value), decimals), decimals: decimals, locale: locale)
        let language = locale.language.languageCode?.identifier ?? ""
        let region = locale.region?.identifier.uppercased() ?? ""
        if language == "tr" { return "%" + number }
        if language == "ar" || language == "fa", format(0, decimals: 0, locale: locale) != "0" {
            return number + "\u{066A}"
        }
        return spaced(language, region) ? number + "\u{00A0}%" : number + "%"
    }

    /// Sprache der App-Texte (wie `BidiText.currentLanguage`): «he-IL» → «he».
    static var appLanguage: String {
        let id = Bundle.main.preferredLocalizations.first ?? Locale.current.identifier
        return String(id.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? "").lowercased()
    }

    private static let rtlLanguages: Set<String> = ["ar", "fa", "he", "iw", "ur", "ps", "yi", "ji", "ckb", "sd", "ug", "dv"]
    private static let spacedLanguages: Set<String> = ["de", "fr", "cs", "da", "es", "fi", "nb", "no", "ro", "ru", "sv"]
    private static let unspacedRegions: [String: Set<String>] = ["de": ["CH", "LI"], "fr": ["CH", "LI"], "es": ["MX"]]
    private static let spacedRegions: [String: Set<String>] = ["en": ["AT", "DE", "DK", "FI", "SE"]]

    /// Abstand vor «%» nach CLDR — dieselbe Tabelle wie `PercentText.isSpaced`.
    private static func spaced(_ language: String, _ region: String) -> Bool {
        if spacedLanguages.contains(language) { return !(unspacedRegions[language]?.contains(region) ?? false) }
        return spacedRegions[language]?.contains(region) ?? false
    }

    /// Kaufmännisch gerundet über die kürzeste Dezimaldarstellung (1.005 → 1.01), wie `DecimalText.rounded`.
    private static func rounded(_ value: Double, _ scale: Int) -> Double {
        guard var decimal = Decimal(string: "\(value)", locale: Locale(identifier: "en_US_POSIX")) else { return value }
        var result = Decimal()
        NSDecimalRound(&result, &decimal, scale, .plain)
        return NSDecimalNumber(decimal: result).doubleValue
    }

    /// Ziffern und Dezimalzeichen der Sprache, ohne Tausendertrennung.
    private static func format(_ value: Double, decimals: Int, locale: Locale) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = locale
        f.roundingMode = .halfUp
        f.usesGroupingSeparator = false
        f.minimumFractionDigits = decimals
        f.maximumFractionDigits = decimals
        return f.string(from: NSNumber(value: value)) ?? String(value)
    }
}
