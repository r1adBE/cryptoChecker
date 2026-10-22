import Foundation

/// Zahlen für Text, den Menschen lesen, in den Ziffern der App-Sprache: Arabisch «٧٢»,
/// Persisch «۷۲», sonst «72» — überall gleich (Werte, Zähler, Gebühren, Achsen). Wie
/// `LocaleNumbers.kt`. `"\(n)"`, `String(n)` und `String(format:)` ohne bzw. mit
/// `en_US_POSIX` schreiben dagegen immer lateinische Ziffern.
///
/// Maschinenformate (Sicherung/Export als JSON, CSV-Export, URLs, API-Parameter,
/// Cache-Schlüssel, Logs, vorbefüllte Eingabefelder) bleiben bei `en_US_POSIX`. Eingaben
/// nehmen beide Schreibweisen an (`ThresholdParser.latinDigits`).
enum LocaleNumbers {
    /// Zahlen-Locale für Anzeige und Eingabe: Sprache der App (Sprache pro App bzw. des Geräts)
    /// mit der Region des Geräts — `Locale.current` verbindet beides schon (wie `formatLocale`,
    /// Android `LocaleNumbers.appLocale`): Deutsch auf einem Schweizer Gerät = de_CH, «90’000.00».
    static var appLocale: Locale { Locale.current }

    /// Seit Build 103: Ergebnis von `usesIndianGrouping` je Locale-Kennung (Formatierer sind teuer).
    private static let indianGroupingLock = NSLock()
    // nonisolated(unsafe): jeder Zugriff nur unter `indianGroupingLock` (NSLock)
    nonisolated(unsafe) private static var indianGroupingCache: [String: Bool] = [:]

    /// Seit Build 103: Gruppiert `locale` Zahlen indisch, mit Zweier-Nebengruppen («1,00,000», z. B. hi-IN, en-IN)?
    /// Aus den CLDR-Daten des Formatierers. Wie Android `LocaleNumbers.usesIndianGrouping`.
    static func usesIndianGrouping(_ locale: Locale) -> Bool {
        indianGroupingLock.lock()
        defer { indianGroupingLock.unlock() }
        if let cached = indianGroupingCache[locale.identifier] { return cached }
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        let indian = formatter.secondaryGroupingSize == 2
        indianGroupingCache[locale.identifier] = indian
        return indian
    }

    /// Sprache + Region wie Android `LocaleNumbers.formatLocale`: ungültige oder leere Region →
    /// nur die Sprache; passt die Region nicht zur Sprache (de + US), nimmt der Formatierer die
    /// Sprach-Daten.
    static func formatLocale(language: String, region: String?) -> Locale {
        let r = (region ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        let valid = (r.count == 2 && r.allSatisfy { $0.isASCII && $0.isLetter })
            || (r.count == 3 && r.allSatisfy { $0.isASCII && $0.isNumber })
        let base = Locale(identifier: language)
        guard valid, base.region?.identifier.uppercased() != r else { return base }
        let lang = language.replacingOccurrences(of: "-", with: "_").split(separator: "_").first.map(String.init) ?? language
        return Locale(identifier: lang + "_" + r)
    }

    /// Ganze Zahl; ohne Tausendertrennung, ausser `grouping` (Werte wie «72», Jahre wie «2024»).
    static func integer(_ value: Int, locale: Locale = appLocale, grouping: Bool = false) -> String {
        integer(Int64(value), locale: locale, grouping: grouping)
    }

    static func integer(_ value: Int64, locale: Locale = appLocale, grouping: Bool = false) -> String {
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.usesGroupingSeparator = grouping
        f.maximumFractionDigits = 0
        return f.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Kommazahl mit `minDecimals`…`maxDecimals` Nachkommastellen, kaufmännisch gerundet wie
    /// `"%.nf"` (0.25 → «0.3» bei einer Stelle); ohne Tausendertrennung, ausser `grouping`.
    static func decimal(
        _ value: Double,
        maxDecimals: Int,
        minDecimals: Int? = nil,
        locale: Locale = appLocale,
        grouping: Bool = false
    ) -> String {
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.roundingMode = .halfUp
        f.usesGroupingSeparator = grouping
        f.minimumFractionDigits = minDecimals ?? maxDecimals
        f.maximumFractionDigits = maxDecimals
        return f.string(from: NSNumber(value: value)) ?? String(format: "%.\(maxDecimals)f", value)
    }

    /// Lateinische Ziffern 0–9 eines fertigen Textes durch die der Sprache ersetzen — nur für
    /// Ziffern ohne Dezimal- oder Tausendertrennzeichen (Zähler in festen Vorlagen).
    static func digits(_ text: String, locale: Locale = appLocale) -> String {
        let zero = integer(0, locale: locale)
        guard let zeroScalar = zero.unicodeScalars.first(where: { CharacterSet.decimalDigits.contains($0) }),
              zeroScalar != "0" else { return text }
        var out = String.UnicodeScalarView()
        for scalar in text.unicodeScalars {
            if scalar.value >= 0x30, scalar.value <= 0x39,
               let mapped = Unicode.Scalar(zeroScalar.value + (scalar.value - 0x30)) {
                out.append(mapped)
            } else {
                out.append(scalar)
            }
        }
        return String(out)
    }

    /// Grosse Zahl kurz in der Sprache der App, höchstens drei signifikante Stellen: «1,2 Mio.», "1.2M",
    /// «1,2 млн» — wie Android `CompactAmount.number` (ICU CompactDecimalFormat, SHORT).
    static func compact(_ value: Double, locale: Locale = appLocale) -> String {
        guard value.isFinite else { return "—" }
        return value.formatted(.number.notation(.compactName).precision(.significantDigits(1...3)).locale(locale))
    }

    /// Dauer in Sekunden mit einer Nachkommastelle und der Einheit der Sprache («0,5 s», «0.5秒») —
    /// wie Android `seconds` im Bericht «Ablauf» (ICU MeasureFormat, SHORT).
    static func seconds(_ millis: Int64, locale: Locale = appLocale) -> String {
        Measurement(value: Double(millis) / 1000, unit: UnitDuration.seconds)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                    numberFormatStyle: .number.precision(.fractionLength(1)))
                .locale(locale))
    }
}
