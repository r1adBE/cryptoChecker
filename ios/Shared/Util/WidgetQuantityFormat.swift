import Foundation

/// Gehaltene Menge klein vor dem Kürzel in den Portfolio-Widgets («1.3 BTC», «12.45 ETH»).
/// Spiegel von `WidgetQuantityFormat.kt` (Testfälle `widget_quantity.json`).
///
/// Regeln:
///  - höchstens 2 Nachkommastellen, Nullen am Ende weg (1.30 → «1.3», 3 → «3»),
///  - unter 0.01: 2 gültige Stellen («0.0042», «0.00051») — nie «0.00»,
///  - immer abgeschnitten (ROUND_DOWN), nie gerundet: das Widget zeigt nie mehr als gehalten,
///  - immer mit Null vor dem Komma, Tausendertrennung und Dezimalzeichen der Sprache.
///
/// Gerechnet wird auf dem Dezimaltext (aus `DecimalText.plain`), nicht auf dem Double —
/// so schneidet 1.13 nicht wegen 1.1299999… auf «1.12» ab.
enum WidgetQuantityFormat {

    /// Anzeige im Widget: nil bei verborgenen Beträgen (`hidden`) oder ohne Menge.
    static func visible(_ quantity: String?, hidden: Bool, locale: Locale = .current) -> String? {
        hidden ? nil : format(quantity, locale: locale)
    }

    /// Kürzel für VoiceOver: «1.3 BTC» mit Menge, sonst nur das Kürzel.
    static func spokenName(_ symbol: String, quantity: String?) -> String {
        guard let quantity, !quantity.isEmpty else { return symbol }
        return quantity + " " + symbol
    }

    /// Mit Dezimalzeichen und Tausendertrennung der App-Sprache.
    static func format(_ text: String?, locale: Locale = .current) -> String? {
        format(text, decimal: locale.decimalSeparator ?? ".", grouping: locale.groupingSeparator ?? ",")
    }

    /// - Parameter text: Menge als Dezimaltext («12.4567», auch «1.2e-05»); Punkt als Dezimalzeichen
    /// - Returns: nil bei leerem, ungültigem, negativem oder null-Wert (Widget zeigt dann keine Menge)
    static func format(_ text: String?, decimal: String, grouping: String) -> String? {
        guard let raw = text?.trimmingCharacters(in: .whitespaces), let parts = split(raw) else { return nil }
        let intPart = parts.int
        let fracPart = Array(parts.frac)
        if intPart == "0" && fracPart.allSatisfy({ $0 == "0" }) { return nil }
        var frac: [Character]
        if intPart != "0" || fracPart.prefix(2).contains(where: { $0 != "0" }) {
            frac = Array(fracPart.prefix(2))
        } else {
            // Unter 0.01: bis zur zweiten gültigen Stelle
            let first = fracPart.firstIndex(where: { $0 != "0" }) ?? 0
            frac = Array(fracPart.prefix(first + 2))
        }
        while frac.last == "0" { frac.removeLast() }
        let grouped = group(intPart, grouping: grouping)
        return frac.isEmpty ? grouped : grouped + decimal + String(frac)
    }

    /// Ganz- und Nachkommastellen (ohne führende Nullen, mindestens «0»); nil = ungültig/negativ.
    private static func split(_ raw: String) -> (int: String, frac: String)? {
        if raw.isEmpty || raw.hasPrefix("-") { return nil }
        var mantissa = raw.hasPrefix("+") ? String(raw.dropFirst()) : raw
        var exponent = 0
        if let e = mantissa.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            var expText = String(mantissa[mantissa.index(after: e)...])
            if expText.hasPrefix("+") { expText.removeFirst() }
            guard let value = Int(expText) else { return nil }
            exponent = value
            mantissa = String(mantissa[..<e])
        }
        var intDigits: String
        var fracDigits: String
        if let dot = mantissa.firstIndex(of: ".") {
            intDigits = String(mantissa[..<dot])
            fracDigits = String(mantissa[mantissa.index(after: dot)...])
        } else {
            intDigits = mantissa
            fracDigits = ""
        }
        if intDigits.isEmpty && fracDigits.isEmpty { return nil }
        guard (intDigits + fracDigits).allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        // Komma um den Exponenten verschieben
        if exponent > 0 {
            let padded = Array(fracDigits) + Array(repeating: Character("0"), count: Swift.max(0, exponent - fracDigits.count))
            intDigits += String(padded[0..<exponent])
            fracDigits = String(padded[exponent...])
        } else if exponent < 0 {
            let shift = -exponent
            let padded = Array(repeating: Character("0"), count: Swift.max(0, shift - intDigits.count)) + Array(intDigits)
            fracDigits = String(padded[(padded.count - shift)...]) + fracDigits
            intDigits = String(padded[..<(padded.count - shift)])
        }
        let trimmed = intDigits.drop(while: { $0 == "0" })
        return (trimmed.isEmpty ? "0" : String(trimmed), fracDigits)
    }

    /// Dreiergruppen von rechts.
    private static func group(_ digits: String, grouping: String) -> String {
        if digits.count <= 3 || grouping.isEmpty { return digits }
        var out = ""
        for (i, c) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out += grouping }
            out.append(c)
        }
        return out
    }
}
