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
    /// Sprache der App (Sprache pro App bzw. des Geräts).
    static var appLocale: Locale { Locale.current }

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
}
