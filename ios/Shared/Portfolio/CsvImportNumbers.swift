import Foundation

// CSV-Import (`CsvImportParser`): Zahlen mit Dezimal- und Tausendertrennzeichen der Börse lesen — wie `CsvImportNumbers.kt` (Android).

extension CsvImportParser {

    /// Ziffern nach dem ersten `sep` (wie Android `length - indexOf - 1`).
    private static func digitsAfter(_ chars: [Character], _ sep: Character) -> Int {
        guard let i = chars.firstIndex(of: sep) else { return -1 }
        return chars.count - i - 1
    }

    /// Ganzzahlteil vor dem einzigen `sep` ist «0» oder «-0» (nie eine Tausendergruppe) — wie Android `zeroWhole`.
    private static func zeroWhole(_ chars: [Character], _ sep: Character) -> Bool {
        guard let i = chars.firstIndex(of: sep) else { return false }
        var whole = chars[..<i]
        if whole.first == "-" { whole = whole.dropFirst() }
        return whole.count == 1 && whole.first == "0"
    }

    /// Erste mehrdeutige Zahl, wenn die Datei kein Dezimalzeichen verrät — wie Android `ambiguousNumber`: genau ein «.»
    /// oder «,», genau 3 Ziffern dahinter, gültig als Tausender («42,000» = 42000 oder 42?). nil = keine.
    static func ambiguousNumber(_ cells: [String]) -> String? {
        for raw in cells {
            let t = stripGrouping(raw)
            let dots = t.filter { $0 == "." }.count
            let commas = t.filter { $0 == "," }.count
            let sep: Character
            if dots == 1 && commas == 0 { sep = "." } else if commas == 1 && dots == 0 { sep = "," } else { continue }
            if grouped(t, sep) && isPlain(t.replacingOccurrences(of: String(sep), with: "")) { return trim(raw) }
        }
        return nil
    }

    /// Dezimalzeichen der Datei aus eindeutigen Zahlen (wie Android `decimalHint`; «0.125» = Punkt).
    static func decimalHint(_ cells: [String]) -> Character? {
        var dot = false
        var comma = false
        for raw in cells {
            let t = Array(stripGrouping(raw))
            let dots = t.filter { $0 == "." }.count
            let commas = t.filter { $0 == "," }.count
            if dots == 1 && commas == 0 && (digitsAfter(t, ".") != 3 || zeroWhole(t, ".")) { dot = true }
            if commas == 1 && dots == 0 && (digitsAfter(t, ",") != 3 || zeroWhole(t, ",")) { comma = true }
            if dots >= 1 && commas >= 1 {
                if (t.lastIndex(of: ".") ?? 0) > (t.lastIndex(of: ",") ?? 0) { dot = true } else { comma = true }
            }
        }
        if dot && !comma { return "." }
        if comma && !dot { return "," }
        return nil
    }

    private static func stripGrouping(_ text: String) -> String {
        var out = ""
        for c in trim(text) {
            switch c {
            case " ", "\u{00A0}", "\u{202F}", "'", "’", "ʼ", "+": continue
            case "−": out.append("-")
            default: out.append(c)
            }
        }
        return out
    }

    /// Nur «-?Ziffern(.Ziffern)?» (wie Android `PLAIN`).
    private static func isPlain(_ s: String) -> Bool {
        var chars = Array(s)
        if chars.first == "-" { chars.removeFirst() }
        let parts = chars.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 1 || parts.count == 2 else { return false }
        return parts.allSatisfy { part in !part.isEmpty && part.allSatisfy { $0.isASCII && $0.isNumber } }
    }

    /// «1.234E-05» → 0.00001234 (das «+» eines Exponenten entfernt schon `stripGrouping`): Mantisse mit höchstens einem
    /// Dezimalzeichen («.» oder «,», nie Tausender, höchstens 60 Zeichen), «E»/«e», Exponent mit 1–3 Ziffern. Der Wert
    /// muss unter 10³⁰ liegen (`PortfolioDecimal.maxIntegerDigits`), sonst ungültig. Die Nachkommastellen prüft wie bei
    /// einer gewöhnlichen Zahl erst das Speichern (gerundet, `CsvImportMapping.unsavableReason`): «1.234E-38» als Preis
    /// gilt wie «0.00…01234». Höchstens 128 Nachkommastellen (kleinster Exponent von `Decimal`). Wie Android `scientific`.
    private static func scientific(_ t: String) -> Decimal? {
        let parts = t.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "e" || $0 == "E" }).map(String.init)
        guard parts.count == 2 else { return nil }
        let mantissa = parts[0].replacingOccurrences(of: ",", with: ".")
        var digits = Substring(parts[1])
        let negative = digits.hasPrefix("-")
        if negative { digits = digits.dropFirst() }
        guard isPlain(mantissa), mantissa.count <= 60, (1...3).contains(digits.count),
              digits.allSatisfy({ $0.isASCII && $0.isNumber }), let e = Int(digits),
              var m = Decimal(string: mantissa, locale: posix) else { return nil }
        if m == 0 { return 0 }
        NSDecimalCompact(&m)
        let shift = negative ? -e : e
        // Ausserhalb des Bereichs von `Decimal` (Exponent −128…127) liegt jeder Wert weit ausserhalb der Grenzen
        guard (-128...127).contains(Int(m.exponent) + shift) else { return nil }
        let value = Decimal(sign: m < 0 ? .minus : .plus, exponent: shift, significand: abs(m))
        return abs(value) < Decimal(sign: .plus, exponent: PortfolioDecimal.maxIntegerDigits, significand: 1) ? value : nil
    }

    /// `sep` als Tausender gültig: erste Gruppe 1–3 Ziffern ohne führende 0 (eine einzelne «0» nie), alle weiteren genau
    /// 3 Ziffern, mindestens eine Trennung — «0.001» bleibt 0.001 auch mit Dezimalkomma. Wie Android `grouped`.
    private static func grouped(_ text: String, _ sep: Character) -> Bool {
        let body: Substring = text.hasPrefix("-") ? text.dropFirst() : Substring(text)
        let groups = body.split(separator: sep, omittingEmptySubsequences: false)
        guard groups.count >= 2, let first = groups.first, (1...3).contains(first.count), first.first != "0",
              first.allSatisfy({ $0.isASCII && $0.isNumber }) else { return false }
        return groups.dropFirst().allSatisfy { g in g.count == 3 && g.allSatisfy { $0.isASCII && $0.isNumber } }
    }

    /// Zahl aus der Datei → exakte Dezimalzahl; ungültig = nil (Regeln wie Android `parseNumber`), auch in
    /// wissenschaftlicher Schreibweise, wie Excel kleine Beträge speichert (`scientific`): «1.234E-05», «1,234E-05».
    static func parseNumber(_ text: String, hint: Character? = nil) -> Decimal? {
        let t = stripGrouping(text)
        if t.isEmpty { return nil }
        if t.contains(where: { $0 == "e" || $0 == "E" }) { return scientific(t) }
        let chars = Array(t)
        let dots = chars.filter { $0 == "." }.count
        let commas = chars.filter { $0 == "," }.count
        let plain: String
        if dots > 0 && commas > 0 {
            let dotLast = (chars.lastIndex(of: ".") ?? 0) > (chars.lastIndex(of: ",") ?? 0)
            let cut: Int = (dotLast ? chars.lastIndex(of: ".") : chars.lastIndex(of: ",")) ?? 0
            let whole = String(chars[0..<cut])
            let sep: Character = dotLast ? "," : "."
            guard grouped(whole, sep) else { return nil }
            plain = whole.replacingOccurrences(of: String(sep), with: "") + "." + String(chars[(cut + 1)...])
        } else if commas > 1 {
            guard grouped(t, ",") else { return nil }
            plain = t.replacingOccurrences(of: ",", with: "")
        } else if dots > 1 {
            guard grouped(t, ".") else { return nil }
            plain = t.replacingOccurrences(of: ".", with: "")
        } else if commas == 1 {
            plain = (hint == "." && grouped(t, ","))
                ? t.replacingOccurrences(of: ",", with: "")
                : t.replacingOccurrences(of: ",", with: ".")
        } else if dots == 1 {
            plain = (hint == "," && grouped(t, ".")) ? t.replacingOccurrences(of: ".", with: "") : t
        } else {
            plain = t
        }
        guard isPlain(plain), plain.count <= 60 else { return nil }
        return Decimal(string: plain, locale: posix)
    }
}
