import Foundation

/// Exakte Dezimalzahlen für die Buchhaltung des Portfolios (Mengen, Kaufpreise, Gebühren,
/// Einstand, realisierter Gewinn) — wie `PortfolioDecimal.kt` (Android, `BigDecimal`). Ein Bitcoin
/// hat 8 Nachkommastellen (1 Satoshi = 0.00000001 BTC), Ethereum-Tokens bis 18 (1 Wei); mit
/// `Double` summieren sich Rundungsfehler («0.1 + 0.2 = 0.30000000000000004»), mit `Decimal` nicht.
/// Exakt sind Addition, Subtraktion und Multiplikation; Teilungen (und anteilige Produkte) runden auf
/// 34 gültige Stellen (`divide`, `multiply`) — wie Android (`MathContext.DECIMAL128`, halbe auf gerade).
/// Börsenkurse kommen als `Double` an; Wert und unrealisierter Gewinn rechnen exakt mit ihrer kürzesten
/// Darstellung (`of`).
enum PortfolioDecimal {
    /// Darunter gilt eine Menge als null (kleiner als 1 Wei = 10⁻¹⁸; nur Rest aus Teilungen).
    static let dust = Decimal(string: "1E-20") ?? 0

    /// Höchstens so viele Nachkommastellen werden gespeichert (1 Wei).
    static let maxScale = 18

    private static let posix = Locale(identifier: "en_US_POSIX")

    /// Double → Decimal über die kürzeste Darstellung (0.1 → «0.1»); nil für NaN/∞.
    static func of(_ value: Double?) -> Decimal? {
        guard let value, value.isFinite else { return nil }
        // `description` ist die kürzeste Darstellung, die den Double eindeutig wiedergibt («1e-08»)
        return Decimal(string: "\(value)", locale: posix)
    }

    /// Höchstens so viele Stellen vor dem Komma (|Wert| < 10³⁰) — sonst ungültig.
    static let maxIntegerDigits = 30

    /// Höchstens so viele Nachkommastellen (ohne Nullen am Ende) beim Einlesen — sonst ungültig.
    static let maxParseScale = 40

    /// Gespeicherter Text («0.00000001», auch «1e-08») → Decimal; ungültig = nil.
    /// Absurde Grössen (z. B. «1E300000000» aus einer manipulierten Sicherung) sind ungültig: mehr als
    /// `maxIntegerDigits` Stellen vor dem Komma oder mehr als `maxParseScale` Nachkommastellen — gleich
    /// wie Android (`PortfolioDecimal.parse`).
    static func parse(_ text: String?) -> Decimal? {
        guard let t = text?.trimmingCharacters(in: .whitespaces), !t.isEmpty else { return nil }
        guard t.allSatisfy({ $0.isASCII && ($0.isNumber || "+-.eE".contains($0)) }) else { return nil }
        guard let value = Decimal(string: t, locale: posix), withinLimits(value) else { return nil }
        // Unterlauf («1E-300000000» → 0): Ziffern ungleich 0 vor dem Exponenten, aber Wert 0 = ungültig
        if value == 0, t.prefix(while: { $0 != "e" && $0 != "E" }).contains(where: { $0 >= "1" && $0 <= "9" }) {
            return nil
        }
        return value
    }

    /// Grösse im erlaubten Bereich (siehe `parse`); NaN nie, null immer.
    static func withinLimits(_ value: Decimal) -> Bool {
        if value.isNaN || !value.isFinite { return false }
        if value == 0 { return true }
        guard abs(value) < Decimal(sign: .plus, exponent: maxIntegerDigits, significand: 1) else { return false }
        // Ohne Nullen am Ende: Exponent = −Nachkommastellen
        var compact = value
        NSDecimalCompact(&compact)
        return compact.exponent >= -maxParseScale
    }

    /// Zum Speichern: ohne Exponent und Nullen am Ende, höchstens `maxScale` Stellen («0.00000001»).
    static func text(_ value: Decimal) -> String {
        var v = value
        var rounded = Decimal()
        NSDecimalRound(&rounded, &v, maxScale, .plain)
        if rounded == 0 { return "0" }
        // NSDecimalString schreibt ohne Exponent und ohne Nullen am Ende
        var r = rounded
        return NSDecimalString(&r, posix)
    }

    /// Für Bewertung und Anzeige mit Gleitkomma.
    static func double(_ value: Decimal) -> Double { NSDecimalNumber(decimal: value).doubleValue }

    /// Gültige Stellen für Teilungen — wie Android (`PortfolioDecimal.MC` = decimal128, 34 Stellen).
    static let significantDigits = 34

    /// `a / b`, gerundet auf `significantDigits` gültige Stellen (halbe auf gerade) — wie
    /// `a.divide(b, MathContext.DECIMAL128)` (Android). `Decimal` teilt selbst auf 38 Stellen; ohne
    /// das Runden wichen Einstand und realisierter Gewinn in den hinteren Stellen ab.
    static func divide(_ a: Decimal, _ b: Decimal) -> Decimal { roundSignificant(a / b) }

    /// `a × b`, gerundet auf `significantDigits` gültige Stellen — wie `a.multiply(b, MathContext.DECIMAL128)`.
    static func multiply(_ a: Decimal, _ b: Decimal) -> Decimal { roundSignificant(a * b) }

    /// Auf `digits` gültige Stellen runden (halbe auf gerade); 0, NaN und kürzere Werte unverändert.
    static func roundSignificant(_ value: Decimal, digits: Int = significantDigits) -> Decimal {
        guard value != 0, !value.isNaN, value.isFinite else { return value }
        var compact = value
        NSDecimalCompact(&compact)
        // Anzahl Ziffern der Mantisse (ohne Nullen am Ende): Mantisse mit Exponent 0 als Text
        var mantissa = compact.significand
        let count = NSDecimalString(&mantissa, posix).filter(\.isNumber).count
        let excess = count - digits
        guard excess > 0 else { return compact }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &compact, -(Int(compact.exponent) + excess), .bankers)
        return rounded
    }

    /// Anteilige Menge (Teil mit Kaufpreis bei einem Abgang) auf `maxScale` Nachkommastellen (halbe auf gerade).
    /// So bleiben beide Teile Mengen mit höchstens 18 Stellen und «Teil ohne Preis = Rest − Teil mit Preis» passt
    /// in die 38 Stellen von `Decimal` — sonst wäre der Bestand 1.000…0009 statt 1. Wie Android (`amount`).
    static func amount(_ value: Decimal) -> Decimal {
        guard !value.isNaN, value.isFinite else { return value }
        var v = value
        var rounded = Decimal()
        NSDecimalRound(&rounded, &v, maxScale, .bankers)
        return rounded
    }

    /// Betrag so klein, dass er nur Rest aus einer Teilung sein kann.
    static func isDust(_ value: Decimal) -> Bool { abs(value) <= dust }
}
