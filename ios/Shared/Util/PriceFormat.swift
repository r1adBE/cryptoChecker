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

    /// Gehaltene Menge: Tausendertrennung, bis acht Nachkommastellen, ohne Nullen am Ende.
    static func amount(_ value: Double) -> String {
        amountFormatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Wert eines Bestands mit Währung, immer zwei Nachkommastellen.
    static func valueWithCurrency(_ value: Double, _ quote: String) -> String {
        "\(twoDecimals.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value)) \(quote)"
    }

    /// Menge oder Kurs für ein Eingabefeld: ohne Exponent und Tausendertrennung, mit dem
    /// Dezimalzeichen der Region (liest `parseAmount` so eindeutig zurück). 0 → «0», nur
    /// nil (oder nicht endlich) → leer. Wie `PriceFormat.amountForInput` (Android).
    static func amountForInput(_ value: Double?, decimalSeparator: Character = ".") -> String {
        guard let value else { return "" }
        return DecimalText.plain(value)
            .replacingOccurrences(of: ".", with: String(ThresholdParser.normalized(decimalSeparator)))
    }

    /// Freie Eingabe einer Menge oder eines Kurses — nach den Regeln von `ThresholdParser`:
    /// Tausendertrennung (auch geschützte/schmale Leerzeichen), Dezimalzeichen der Region,
    /// arabische/persische Ziffern; mehrdeutig («60.000») entscheidet `priceHint` (aktueller
    /// Kurs), ohne Kurs das Dezimalzeichen. Leer = 0 (kein Bestand), 0 gilt;
    /// ungültig, negativ, mit Exponent oder Buchstaben («1.5f») = nil.
    static func parseAmount(_ text: String, decimalSeparator: Character, priceHint: Double? = nil) -> Double? {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return 0 }
        return ThresholdParser.parseAllowingZero(text, decimalSeparator: decimalSeparator, priceHint: priceHint)
    }

    static func changePercent(_ value: Double?) -> String? {
        guard let value, abs(value) >= 0.005 else { return nil }
        let sign = value > 0 ? "+" : "−"
        // Vorher kaufmännisch runden (`DecimalText`): 1.005 → «1.01» wie in Android
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl («1.20%+»)
        return BidiText.ltr(sign + String(format: "%.2f%%", locale: Locale.current, DecimalText.rounded(abs(value), 2)))
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
        return BidiText.ltr(sign + String(format: "%.3f%%", locale: Locale.current, DecimalText.rounded(abs(value), 3)))
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

/// Zahlen als Dezimaltext ohne Exponent und Tausendertrennung, mit Punkt — für Export,
/// vorbefüllte Eingabefelder und Rundung vor einem `"%.nf"`. Spiegel von `DecimalText.kt`.
///
/// Gerechnet wird mit der kürzesten Dezimaldarstellung des Double (`"\(value)"`, wie
/// `BigDecimal.valueOf` in Android), nicht mit dem exakten Binärwert: 1.005 ist «1.005» und
/// rundet kaufmännisch (bei der Hälfte weg von 0, `HALF_UP`) auf «1.01» — auf beiden
/// Plattformen gleich (`String(format: "%.2f")` ergäbe «1.00»).
///
/// Nicht endliche Werte (NaN, ±∞) ergeben einen leeren Text.
enum DecimalText {

    /// Ziffern ohne führende und abschliessende Nullen: Wert = 0.d₁d₂d₃… × 10^point.
    private struct Digits {
        var negative: Bool
        var digits: [Int]
        var point: Int
        var isZero: Bool { digits.isEmpty }
    }

    private static func digits(_ value: Double) -> Digits? {
        guard value.isFinite else { return nil }
        var text = "\(value)"  // kürzeste Darstellung: «60000.0», «1.2345e-10», «1e+16»
        let negative = text.hasPrefix("-")
        if negative { text.removeFirst() }
        var exponent = 0
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            exponent = Int(text[text.index(after: e)...]) ?? 0
            text = String(text[..<e])
        }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        let intPart = parts.first.map { String($0) } ?? ""
        let fracPart = parts.count > 1 ? String(parts[1]) : ""
        var list = (intPart + fracPart).compactMap { $0.wholeNumberValue }
        var point = intPart.count + exponent
        while list.first == 0 { list.removeFirst(); point -= 1 }
        while list.last == 0 { list.removeLast() }
        if list.isEmpty { return Digits(negative: false, digits: [], point: 0) }
        return Digits(negative: negative, digits: list, point: point)
    }

    /// Kaufmännisch auf `scale` Nachkommastellen runden (bei der Hälfte weg von 0).
    private static func roundHalfUp(_ d: Digits, _ scale: Int) -> Digits {
        let keep = d.point + scale
        guard d.digits.count > keep else { return d }
        if keep < 0 { return Digits(negative: false, digits: [], point: 0) }
        var list = Array(d.digits.prefix(keep))
        var point = d.point
        if d.digits[keep] >= 5 {
            var i = list.count - 1
            while i >= 0 && list[i] == 9 { list[i] = 0; i -= 1 }
            if i >= 0 { list[i] += 1 } else { list.insert(1, at: 0); point += 1 }
        }
        while list.last == 0 { list.removeLast() }
        if list.isEmpty { return Digits(negative: false, digits: [], point: 0) }
        return Digits(negative: d.negative, digits: list, point: point)
    }

    /// Text mit mindestens `minDecimals` Nachkommastellen (mit Nullen aufgefüllt).
    private static func text(_ d: Digits, minDecimals: Int) -> String {
        let chars = d.digits.map { Character(String($0)) }
        var intText: String
        var fracText: String
        if d.point <= 0 {
            intText = "0"
            fracText = String(repeating: "0", count: -d.point) + String(chars)
        } else if chars.count <= d.point {
            intText = String(chars) + String(repeating: "0", count: d.point - chars.count)
            fracText = ""
        } else {
            intText = String(chars[..<d.point])
            fracText = String(chars[d.point...])
        }
        if fracText.count < minDecimals { fracText += String(repeating: "0", count: minDecimals - fracText.count) }
        if d.isZero { intText = "0" }
        let sign = d.negative && !d.isZero ? "-" : ""
        return sign + intText + (fracText.isEmpty ? "" : "." + fracText)
    }

    /// Ohne Nullen am Ende; mit `scale` vorher auf so viele Nachkommastellen gerundet. «-0» → «0».
    static func plain(_ value: Double, scale: Int? = nil) -> String {
        guard var d = digits(value) else { return "" }
        if let scale { d = roundHalfUp(d, scale) }
        return text(d, minDecimals: 0)
    }

    /// Genau `scale` Nachkommastellen, kaufmännisch gerundet; «-0.00» → «0.00».
    static func fixed(_ value: Double, scale: Int) -> String {
        guard let d = digits(value) else { return "" }
        return text(roundHalfUp(d, scale), minDecimals: scale)
    }

    /// Kaufmännisch auf `scale` Nachkommastellen gerundet (für ein folgendes `"%.nf"`).
    static func rounded(_ value: Double, _ scale: Int) -> Double {
        guard value.isFinite else { return value }
        return Double(plain(value, scale: scale)) ?? value
    }

    /// Stellen vor dem Komma (ohne führende Nullen): 123.4 → 3, 0.5 → 0, 0.000123 → −3; 0 → 0.
    static func integerDigits(_ value: Double) -> Int {
        guard let d = digits(value), !d.isZero else { return 0 }
        return d.point
    }

    /// Nachkommastellen für mindestens `minDecimals` Stellen und mindestens `significant`
    /// gültige Stellen: 123.456 → 10, 3e-11 → 20 (bei 10/10) — kleinste Kurse bleiben so lesbar.
    static func significantScale(_ value: Double, minDecimals: Int, significant: Int) -> Int {
        max(minDecimals, significant - integerDigits(value))
    }
}

extension Date {
    init(millis: Int64) { self.init(timeIntervalSince1970: Double(millis) / 1000) }
    var millis: Int64 { Int64(timeIntervalSince1970 * 1000) }
}
