import Foundation

/// Zahlen- und Zeitformate — gleich wie `PriceFormat.kt` / `FormatUtilsBase.kt`.
enum PriceFormat {

    private static func formatter(_ configure: (NumberFormatter) -> Void) -> NumberFormatter {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale.current
        f.usesGroupingSeparator = true
        configure(f)
        return f
    }

    /// "@###": höchstens vier signifikante Stellen.
    private static let fourSignificant = formatter {
        $0.usesSignificantDigits = true
        $0.minimumSignificantDigits = 1
        $0.maximumSignificantDigits = 4
    }

    /// "#,###.00"
    private static let twoDecimals = formatter {
        $0.minimumFractionDigits = 2
        $0.maximumFractionDigits = 2
    }

    /// "#,###"
    private static let noDecimals = formatter {
        $0.maximumFractionDigits = 0
    }

    /// "@#######": höchstens acht signifikante Stellen.
    private static let eightSignificant = formatter {
        $0.usesSignificantDigits = true
        $0.minimumSignificantDigits = 1
        $0.maximumSignificantDigits = 8
    }

    static func formatDouble(_ value: Double) -> String {
        let f: NumberFormatter = value < 10 ? fourSignificant : (value < 10_000 ? twoDecimals : noDecimals)
        return f.string(from: NSNumber(value: value)) ?? String(value)
    }

    static func formatEightMax(_ value: Double) -> String {
        eightSignificant.string(from: NSNumber(value: value)) ?? String(value)
    }

    static func price(_ value: Double?) -> String {
        guard let value, value > 0 else { return "—" }
        return formatDouble(value)
    }

    static func priceWithCurrency(_ value: Double?, _ quote: String) -> String {
        guard let value, value > 0 else { return "—" }
        return "\(price(value)) \(quote)"
    }

    // MARK: Bestand

    /// "#,##0.########"
    private static let amountFormatter = formatter {
        $0.minimumFractionDigits = 0
        $0.maximumFractionDigits = 8
    }

    /// Eingabefeld: ohne Tausendertrennung, Punkt als Dezimalzeichen.
    private static let inputFormatter: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = Locale(identifier: "en_US_POSIX")
        f.usesGroupingSeparator = false
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = 12
        return f
    }()

    /// Gehaltene Menge: Tausendertrennung, bis acht Nachkommastellen, ohne Nullen am Ende.
    static func amount(_ value: Double) -> String {
        amountFormatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Wert eines Bestands mit Währung, immer zwei Nachkommastellen.
    static func valueWithCurrency(_ value: Double, _ quote: String) -> String {
        "\(twoDecimals.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value)) \(quote)"
    }

    /// Menge für ein Eingabefeld; leer ohne Bestand.
    static func amountForInput(_ value: Double?) -> String {
        guard let value, value.isFinite, value > 0 else { return "" }
        return inputFormatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Freie Eingabe einer Menge: Komma oder Punkt, Leerzeichen und Tausenderstriche
    /// werden ignoriert, arabische/persische Ziffern gelten (`ThresholdParser.latinDigits`).
    /// Leer = 0 (kein Bestand), ungültig oder negativ = nil.
    static func parseAmount(_ text: String) -> Double? {
        var cleaned = ThresholdParser.latinDigits(text).trimmingCharacters(in: .whitespacesAndNewlines)
        for junk in [" ", "\u{00A0}", "\u{202F}", "'", "’"] {
            cleaned = cleaned.replacingOccurrences(of: junk, with: "")
        }
        cleaned = cleaned.replacingOccurrences(of: ",", with: ".")
        if cleaned.isEmpty { return 0 }
        guard let value = Double(cleaned), value.isFinite, value >= 0 else { return nil }
        return value
    }

    static func changePercent(_ value: Double?) -> String? {
        guard let value, abs(value) >= 0.005 else { return nil }
        let sign = value > 0 ? "+" : "−"
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl («1.20%+»)
        return BidiText.ltr(sign + String(format: "%.2f%%", locale: Locale.current, abs(value)))
    }

    /// Pfeil zur Änderung: «▲» steigend, «▼» fallend, leer bei praktisch 0 — folgt immer dem
    /// Vorzeichen, nie dem Farbtausch (wie `PriceFormat.changeArrow` in Android).
    static func changeArrow(_ value: Double?) -> String {
        guard let value, abs(value) >= 0.005 else { return "" }
        return value > 0 ? "▲" : "▼"
    }

    /// Praktisch keine Änderung: «0.00%» grau, ohne Pfeil — in der Schreibweise der App-Sprache
    /// («0,00%», arabisch «٠٫٠٠%») wie `changePercent`.
    static func zeroPercent() -> String {
        BidiText.ltr(String(format: "%.2f%%", locale: Locale.current, 0.0))
    }

    /// Mit Vorzeichen und drei Nachkommastellen (Mitteilungen).
    static func changePercentDetailed(_ value: Double) -> String {
        let sign = value >= 0 ? "+" : "-"
        return BidiText.ltr(sign + String(format: "%.3f%%", locale: Locale.current, abs(value)))
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .medium
        return f
    }()

    /// Uhrzeit mit Sekunden.
    static func time(_ millis: Int64) -> String {
        guard millis > 0 else { return "—" }
        return timeFormatter.string(from: Date(millis: millis))
    }

    /// Uhrzeit ohne Sekunden, z. B. «19:41» («Offline · Stand 19:41», «pausiert bis 19:45»).
    static func shortTime(_ millis: Int64) -> String {
        guard millis > 0 else { return "—" }
        return Date(millis: millis).formatted(date: .omitted, time: .shortened)
    }

    /// Kompakter Abstand: 45s, 12m, 3h, 7d.
    static func age(since millis: Int64, now: Int64 = TimeUtils.nowMillis) -> String? {
        guard millis > 0 else { return nil }
        let elapsed = now - millis
        guard elapsed >= 0 else { return nil }
        let s = elapsed / 1000, m = s / 60, h = m / 60, d = h / 24
        if d > 0 { return LocaleNumbers.integer(d) + "d" }
        if h > 0 { return LocaleNumbers.integer(h) + "h" }
        if m > 0 { return LocaleNumbers.integer(m) + "m" }
        return LocaleNumbers.integer(s) + "s"
    }

    static func duration(_ millis: Int64) -> String {
        if millis <= 0 { return "" }
        if millis < 1000 { return LocaleNumbers.integer(millis) + " ms" }
        return String(format: "%.1f s", locale: Locale.current, Double(millis) / 1000)
    }

    /// Kurs so, dass die Sprachausgabe ihn sinnvoll vorliest.
    static func spokenPrice(_ value: Double) -> String {
        let posix = Locale(identifier: "en_US_POSIX")
        var s: String
        if value >= 1000 {
            s = String(format: "%.0f", locale: posix, value)
        } else if value >= 1 {
            s = String(format: "%.2f", locale: posix, value)
        } else {
            s = String(format: "%.6f", locale: posix, value)
            while s.hasSuffix("0") { s.removeLast() }
            if s.hasSuffix(".") { s.removeLast() }
        }
        return s
    }

    /// Grosse Zahlen kurz: 1.2K, 3.4M, 5.6B, 7.8T.
    static func compact(_ value: Double) -> String {
        let a = abs(value)
        let (div, suffix): (Double, String) =
            a >= 1e12 ? (1e12, "T") : a >= 1e9 ? (1e9, "B") : a >= 1e6 ? (1e6, "M") : a >= 1e3 ? (1e3, "K") : (1, "")
        let v = value / div
        let digits = suffix.isEmpty ? 0 : (abs(v) >= 100 ? 0 : 1)
        return String(format: "%.\(digits)f", locale: Locale.current, v) + suffix
    }
}

extension Date {
    init(millis: Int64) { self.init(timeIntervalSince1970: Double(millis) / 1000) }
    var millis: Int64 { Int64(timeIntervalSince1970 * 1000) }
}
