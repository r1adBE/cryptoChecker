import Foundation

/// Liest einen getippten oder eingefügten Schwellwert — robust gegen Tausendertrennung und
/// Dezimalzeichen der Region. Spiegel von `ThresholdParser.kt` (gleiche Regeln, gleiche Tests).
///
/// - Tausendertrenner ’ ' ‘ ` Leerzeichen, geschütztes und schmales Leerzeichen werden ignoriert.
/// - Kommen Punkt und Komma vor, ist das letzte der beiden das Dezimalzeichen: «1.234,56», «1,234.56».
/// - Mehrfach dasselbe Zeichen = Tausendertrennung in Dreiergruppen: «1.234.567», «1,234,567».
///   Seit Build 103 mit `indianGrouping` (Region mit Zweier-Nebengruppen wie hi-IN) auch indisch: «1,00,000».
/// - Einmal ein Zeichen mit genau drei Ziffern danach («60,000», «60.000») ist mehrdeutig:
///   Mit `priceHint` (aktueller Kurs in der Währung des Schwellwerts) gewinnt die Lesart, die
///   näher am Kurs liegt (Verhältnis am nächsten bei 1); ohne Kurs entscheidet das
///   Dezimalzeichen der Region (`decimalSeparator`).
/// - Arabisch-indische und persische Ziffern sowie «٫»/«٬» gelten wie 0–9, «.» und «’» (`latinDigits`).
/// - Alles andere (Buchstaben wie «60k», Vorzeichen, Exponent) ist ungültig → nil.
///
/// Ergebnis immer > 0 und endlich, sonst nil.
enum ThresholdParser {

    /// Zeichen, die nur der Tausendertrennung dienen.
    private static let grouping: Set<Character> = ["\u{2019}", "'", "\u{2018}", "`", " ", "\u{00A0}", "\u{202F}", "\u{2009}"]

    /// Dezimalzeichen der Region, auf Punkt oder Komma abgebildet — dieselbe Locale wie die Anzeige
    /// (`LocaleNumbers.appLocale`).
    static var localeDecimalSeparator: Character { decimalSeparator(LocaleNumbers.appLocale) }

    /// Dezimalzeichen einer Locale, auf Punkt oder Komma abgebildet (wie Android `inputDecimalSeparator`).
    static func decimalSeparator(_ locale: Locale) -> Character {
        normalized(locale.decimalSeparator?.first ?? ".")
    }

    /// Dezimalzeichen auf Punkt oder Komma abbilden (anderes → Punkt).
    static func normalized(_ separator: Character) -> Character { separator == "," ? "," : "." }

    /// Seit Build 103: Gruppiert die Zahlen-Locale (`LocaleNumbers.appLocale`) indisch? Standard für `indianGrouping`;
    /// wie Android `ThresholdParser.regionIndianGrouping`.
    static var regionIndianGrouping: Bool { LocaleNumbers.usesIndianGrouping(LocaleNumbers.appLocale) }

    static func parse(_ text: String, decimalSeparator: Character, priceHint: Double? = nil,
                      indianGrouping: Bool = ThresholdParser.regionIndianGrouping) -> Double? {
        guard let text = canonical(text, decimalSeparator: decimalSeparator, priceHint: priceHint,
                                   indianGrouping: indianGrouping),
              let value = Double(text), value.isFinite, value > 0 else { return nil }
        return value
    }

    /// Wie `parse`, aber exakt als `Decimal` (z. B. 0.00000001 BTC = 1 Satoshi ohne Gleitkomma-Rundung)
    /// — für Mengen, Kurse und Gebühren im Portfolio. Wie `ThresholdParser.parseDecimal` (Android).
    static func parseDecimal(_ text: String, decimalSeparator: Character, priceHint: Double? = nil,
                             indianGrouping: Bool = ThresholdParser.regionIndianGrouping) -> Decimal? {
        guard let text = canonical(text, decimalSeparator: decimalSeparator, priceHint: priceHint,
                                   indianGrouping: indianGrouping),
              let value = PortfolioDecimal.parse(text), value > 0 else { return nil }
        return value
    }

    /// Gelesene Zahl als Dezimaltext mit Punkt ohne Tausendertrennung («1234.56»), nach den Regeln
    /// oben; nil = ungültig. Der Wert kann 0 sein (prüfen die Aufrufer).
    private static func canonical(_ text: String, decimalSeparator: Character, priceHint: Double?,
                                  indianGrouping: Bool) -> String? {
        var cleaned = ""
        for c in latinDigits(text).trimmingCharacters(in: .whitespacesAndNewlines) {
            if grouping.contains(c) { continue }
            guard isAsciiDigit(c) || c == "." || c == "," else { return nil }
            cleaned.append(c)
        }
        guard !cleaned.isEmpty, cleaned.contains(where: isAsciiDigit) else { return nil }

        let dots = cleaned.filter { $0 == "." }.count
        let commas = cleaned.filter { $0 == "," }.count

        if dots == 0 && commas == 0 {
            return cleaned
        }
        if dots > 0 && commas > 0 {
            // Das letzte Zeichen ist der Dezimaltrenner, das andere die Tausendertrennung davor
            guard let lastDot = cleaned.lastIndex(of: "."), let lastComma = cleaned.lastIndex(of: ",") else { return nil }
            let decimal: Character = lastDot > lastComma ? "." : ","
            let groupSep: Character = decimal == "." ? "," : "."
            let decimalIndex = decimal == "." ? lastDot : lastComma
            guard cleaned.filter({ $0 == decimal }).count == 1 else { return nil }
            let intPart = String(cleaned[..<decimalIndex])
            let fracPart = String(cleaned[cleaned.index(after: decimalIndex)...])
            return validGrouping(intPart, groupSep, indian: indianGrouping)
                ? number(intPart.replacingOccurrences(of: String(groupSep), with: ""), fracPart)
                : nil
        }
        let sep: Character = dots > 0 ? "." : ","
        let count = dots > 0 ? dots : commas
        if count > 1 {
            // Mehrfach: nur Tausendertrennung
            return validGrouping(cleaned, sep, indian: indianGrouping) ? cleaned.replacingOccurrences(of: String(sep), with: "") : nil
        }
        guard let index = cleaned.firstIndex(of: sep) else { return nil }
        let intPart = String(cleaned[..<index])
        let fracPart = String(cleaned[cleaned.index(after: index)...])
        let asDecimal = number(intPart, fracPart)
        let asGrouping: String? = validGrouping(cleaned, sep) ? intPart + fracPart : nil
        guard let asGrouping, let groupingValue = Double(asGrouping) else { return asDecimal }
        guard let asDecimal, let decimalValue = Double(asDecimal), decimalValue > 0 else { return asGrouping }
        // Der Kurs entscheidet nur, wenn eine Lesart höchstens Faktor 10 von ihm entfernt ist — wie Android
        if let hint = priceHint, hint.isFinite, hint > 0,
           min(distance(groupingValue, hint), distance(decimalValue, hint)) < log(hintMaxFactor) {
            return distance(groupingValue, hint) < distance(decimalValue, hint) ? asGrouping : asDecimal
        }
        return normalized(decimalSeparator) == sep ? asDecimal : asGrouping
    }

    /// Wie `parse`, aber auch 0 gilt («0», «0,00», «.0», «0’000») — für Menge und Kurs einer
    /// Transaktion (Kurs 0 = geschenkt). Leer bleibt ungültig (nil); negativ, Exponent oder
    /// Buchstaben («1.5f», «2d») ebenso. Wie `ThresholdParser.parseAllowingZero` (Android).
    static func parseAllowingZero(_ text: String, decimalSeparator: Character, priceHint: Double? = nil,
                                  indianGrouping: Bool = ThresholdParser.regionIndianGrouping) -> Double? {
        if let value = parse(text, decimalSeparator: decimalSeparator, priceHint: priceHint,
                             indianGrouping: indianGrouping) { return value }
        return isZero(text) ? 0 : nil
    }

    /// Wie `parseAllowingZero`, aber exakt als `Decimal`.
    static func parseDecimalAllowingZero(_ text: String, decimalSeparator: Character, priceHint: Double? = nil,
                                         indianGrouping: Bool = ThresholdParser.regionIndianGrouping) -> Decimal? {
        if let value = parseDecimal(text, decimalSeparator: decimalSeparator, priceHint: priceHint,
                                    indianGrouping: indianGrouping) { return value }
        return isZero(text) ? 0 : nil
    }

    private static func isZero(_ text: String) -> Bool {
        let cleaned = latinDigits(text).trimmingCharacters(in: .whitespacesAndNewlines).filter { !grouping.contains($0) }
        return cleaned.contains("0") && cleaned.allSatisfy { $0 == "0" || $0 == "." || $0 == "," }
            && cleaned.filter { $0 == "." || $0 == "," }.count <= 1
    }

    private static func isAsciiDigit(_ c: Character) -> Bool { c >= "0" && c <= "9" }

    /// Eingabe in lateinische Ziffern: arabisch-indische (٠–٩), persische (۰–۹) und andere
    /// Unicode-Dezimalziffern → 0–9, arabisches Dezimalzeichen «٫» → «.», arabische
    /// Tausendertrennung «٬» → «’»; Richtungszeichen (z. B. LRM aus eingefügtem Text) fallen weg.
    static func latinDigits(_ text: String) -> String {
        var out = ""
        out.reserveCapacity(text.count)
        for c in text {
            if isAsciiDigit(c) {
                out.append(c)
            } else if c == "\u{066B}" {
                out.append(".")
            } else if c == "\u{066C}" {
                out.append("\u{2019}")
            } else if BidiText.marks.contains(c) {
                continue
            } else if let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1,
                      scalar.properties.numericType == .decimal,
                      let digit = scalar.properties.numericValue {
                out.append(Character(String(Int(digit))))
            } else {
                out.append(c)
            }
        }
        return out
    }

    /// «12» + «5» → «12.5»; leere Teile zählen als 0 («.5», «60.»).
    private static func number(_ intPart: String, _ fracPart: String) -> String? {
        if intPart.isEmpty && fracPart.isEmpty { return nil }
        return (intPart.isEmpty ? "0" : intPart) + "." + (fracPart.isEmpty ? "0" : fracPart)
    }

    /// Tausendergruppen: erste Gruppe 1–3 Ziffern ohne führende 0, danach je genau drei. Seit Build 103 mit
    /// `indian` zusätzlich indisch: erste Gruppe 1–2 Ziffern, dann Zweiergruppen, zuletzt genau drei («12,34,567»).
    private static func validGrouping(_ text: String, _ sep: Character, indian: Bool = false) -> Bool {
        let groups = text.split(separator: sep, omittingEmptySubsequences: false)
        guard groups.count >= 2 else { return true }
        let first = groups[0]
        if first.isEmpty || first.count > 3 || first.hasPrefix("0") { return false }
        if groups.dropFirst().allSatisfy({ $0.count == 3 }) { return true }
        return indian && first.count <= 2 && groups[groups.count - 1].count == 3
            && groups.dropFirst().dropLast().allSatisfy { $0.count == 2 }
    }

    /// Abstand zweier positiver Werte auf der log. Skala (Verhältnis zu 1).
    private static func distance(_ value: Double, _ hint: Double) -> Double { abs(log(value / hint)) }

    /// Der Kurs entscheidet nur, wenn eine Lesart höchstens so weit (Faktor) von ihm entfernt ist.
    static let hintMaxFactor = 10.0
}
