import Foundation

/// Prozentangaben überall in derselben Schreibweise der App-Sprache (Muster nach CLDR): «2.4%», Deutsch «2,4 %»
/// (Schweiz und Liechtenstein «2.4%»), Französisch «2,4 %», Türkisch «%2,4», Arabisch «٢٫٤٪», Persisch «۲٫۴٪» —
/// Spiegel von `PercentText.kt` (gleiche Tabelle, gleiche Testfälle `Parity/percent_text.json`). Eine eigene kleine
/// Tabelle statt `NumberFormatter.percent`, damit beide Systeme Zeichen für Zeichen dasselbe liefern.
///
/// Für den Abstand zählt die ganze Locale (Sprache + Region) wie CLDR, nicht nur die Sprache:
/// Spanisch (Mexiko) «2.4%», Englisch in Deutschland, Österreich, Schweden, Finnland, Dänemark «2,4 %».
///
/// Die eine Stelle für Kursänderungen, Aktivität («+2.9 %» vorher), Dominanz und das Pulse-Widget.
enum PercentText {

    /// Abstand vor «%» (geschützt, bricht nicht um) wie CLDR.
    private static let nbsp = "\u{00A0}"

    /// Sprachen mit Abstand vor «%» («2,4 %»).
    private static let spaced: Set<String> = ["de", "fr", "cs", "da", "es", "fi", "nb", "no", "ro", "ru", "sv"]

    /// Ausnahmen nach Region zu `spaced`: diese Sprach-Regionen ohne Abstand (CLDR de_CH, de_LI, fr_CH: «2.4%»,
    /// es_MX: «2.4%»; die übrigen spanischen Regionen inkl. es_US und es_419 behalten den Abstand).
    private static let unspacedRegions: [String: Set<String>] = [
        "de": ["CH", "LI"],
        "fr": ["CH", "LI"],
        "es": ["MX"],
    ]

    /// Umgekehrte Ausnahmen: Sprachen ohne Abstand, in diesen Regionen mit (CLDR en_AT, en_DE, en_DK, en_FI, en_SE).
    private static let spacedRegions: [String: Set<String>] = [
        "en": ["AT", "DE", "DK", "FI", "SE"],
    ]

    /// Abstand vor «%» für `language` in `region` nach CLDR (ohne Türkisch und arabische Ziffern).
    static func isSpaced(language: String, region: String) -> Bool {
        let r = region.uppercased()
        if spaced.contains(language) {
            return !(unspacedRegions[language]?.contains(r) ?? false)
        }
        return spacedRegions[language]?.contains(r) ?? false
    }

    /// Arabisches Prozentzeichen «٪» (U+066A) zu arabischen/persischen Ziffern.
    private static let arabicPercent = "\u{066A}"

    /// Nur das Prozentzeichen der Sprache von `locale` als Einheit hinter einem Eingabefeld: «٪» zu arabischen bzw.
    /// persischen Ziffern, sonst «%». Im Feld steht es immer hinten wie eine Währung (auch Türkisch, wo die fertige
    /// Zahl «%5» schreibt): die Eingabe beginnt so immer mit der Zahl. Wie `PercentText.symbol` (Android).
    static func symbol(locale: Locale = LocaleNumbers.appLocale) -> String {
        let language = locale.language.languageCode?.identifier ?? ""
        if language == "ar" || language == "fa", LocaleNumbers.integer(0, locale: locale) != "0" { return arabicPercent }
        return "%"
    }

    /// Fertige Zahl `number` (ohne Vorzeichen) mit dem Prozentzeichen der Sprache von `locale`.
    static func wrap(_ number: String, locale: Locale = LocaleNumbers.appLocale) -> String {
        let language = locale.language.languageCode?.identifier ?? ""
        let region = locale.region?.identifier.uppercased() ?? ""
        if language == "tr" { return "%" + number }
        if language == "ar" || language == "fa", LocaleNumbers.integer(0, locale: locale) != "0" {
            return number + arabicPercent
        }
        if isSpaced(language: language, region: region) {
            return number + nbsp + "%"
        }
        return number + "%"
    }

    /// Betrag von `value` mit genau `decimals` Nachkommastellen (kaufmännisch gerundet, Ziffern und Dezimalzeichen der
    /// Sprache, ohne Tausendertrennung) und Prozentzeichen — ohne Vorzeichen. Mit `minDecimals` unter `decimals` fallen
    /// Nullen am Ende weg («5%», «2.5%», «0.0125%»; für Schwellen und Alarm-Sätze statt
    /// `NumberFormatter.percent`). Mit `grouping` mit Tausendertrennung («1,000%», Deutsch «1.000 %») — für
    /// Alarm-Sätze, deren Schwellen bis 1000 % gehen (Open Interest); sonst ohne.
    static func unsigned(_ value: Double, decimals: Int, minDecimals: Int? = nil,
                         locale: Locale = LocaleNumbers.appLocale, grouping: Bool = false) -> String {
        wrap(LocaleNumbers.decimal(DecimalText.rounded(abs(value), decimals), maxDecimals: decimals,
                                   minDecimals: minDecimals ?? decimals, locale: locale, grouping: grouping),
             locale: locale)
    }

    /// Mit Vorzeichen vor dem Ganzen («+2.40%», «−1.4 %», türkisch «+%2,4»), echtes Minus U+2212; gerundet 0 ohne
    /// Vorzeichen. Ohne Richtungs-Insel — die setzt der Aufrufer (`BidiText.ltr`).
    static func signed(_ value: Double, decimals: Int, minDecimals: Int? = nil,
                       locale: Locale = LocaleNumbers.appLocale, grouping: Bool = false) -> String {
        let rounded = DecimalText.rounded(abs(value), decimals)
        let sign = rounded == 0 ? "" : (value > 0 ? "+" : "−")
        return sign + unsigned(value, decimals: decimals, minDecimals: minDecimals, locale: locale, grouping: grouping)
    }
}
