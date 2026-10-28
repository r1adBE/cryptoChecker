import Foundation

/// Exakte Dezimalzahlen für die Buchhaltung des Portfolios (Mengen, Kaufpreise, Gebühren,
/// Einstand, realisierter Gewinn) — wie `PortfolioDecimal.kt` (Android, `BigDecimal`). Ein Bitcoin
/// hat 8 Nachkommastellen (1 Satoshi = 0.00000001 BTC), Ethereum-Tokens bis 18 (1 Wei); mit
/// `Double` summieren sich Rundungsfehler («0.1 + 0.2 = 0.30000000000000004»), mit `Decimal` nicht.
/// `Decimal` hält rund 38 gültige Stellen (Mantisse 128 Bit): `+`, `−` und `*` sind exakt, solange das Ergebnis darin
/// Platz hat; anders als `BigDecimal` (Android) ist die Genauigkeit nicht unbegrenzt. Teilungen und alle Produkte der
/// Geldpfade (`divide`, `multiply`, `divide(_:_:by:)`; nie ein einfaches `*`) rechnen darum exakt über die Mantissen und
/// runden einmal auf 34 gültige Stellen — wie Android (`MathContext.DECIMAL128`, halbe auf gerade).
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

    /// Zum Speichern: ohne Exponent und Nullen am Ende, höchstens `maxScale` Stellen («0.00000001»). Gerundet wie
    /// `amount` (halbe auf gerade): eine Rundungsregel für Rechnen, Prüfen vor dem Speichern und Speichern. Wie Android.
    static func text(_ value: Decimal) -> String {
        var v = value
        var rounded = Decimal()
        NSDecimalRound(&rounded, &v, maxScale, .bankers)
        if rounded == 0 { return "0" }
        // NSDecimalString schreibt ohne Exponent und ohne Nullen am Ende
        var r = rounded
        return NSDecimalString(&r, posix)
    }

    /// Für Bewertung und Anzeige mit Gleitkomma.
    static func double(_ value: Decimal) -> Double { NSDecimalNumber(decimal: value).doubleValue }

    /// Gültige Stellen für Teilungen — wie Android (`PortfolioDecimal.MC` = decimal128, 34 Stellen).
    static let significantDigits = 34

    // `divide` und `multiply`: exakt, einmal gerundet (PortfolioDecimal+Exact.swift)

    /// Auf `digits` gültige Stellen runden (halbe auf gerade); 0, NaN und kürzere Werte unverändert.
    static func roundSignificant(_ value: Decimal, digits: Int = significantDigits) -> Decimal {
        guard value != 0, !value.isNaN, value.isFinite else { return value }
        var compact = value
        NSDecimalCompact(&compact)
        // Anzahl Ziffern der Mantisse (ohne Nullen am Ende), ohne Text: Teilungen laufen je Posten und Herkunftsteil
        let excess = mantissaDigits(compact) - digits
        guard excess > 0 else { return compact }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &compact, -(Int(compact.exponent) + excess), .bankers)
        return rounded
    }

    /// 10⁰ … 10³⁸ als 128-Bit-Zahl (obere, untere 64 Bit) — alle Werte, die eine Mantisse von `Decimal` erreicht.
    private static let tenPowers: [(hi: UInt64, lo: UInt64)] = {
        var out: [(hi: UInt64, lo: UInt64)] = [(0, 1)]
        for _ in 1...38 {
            let last = out[out.count - 1]
            let low = last.lo.multipliedFullWidth(by: 10)
            out.append((last.hi &* 10 &+ low.high, low.low))
        }
        return out
    }()

    /// Anzahl Dezimalziffern der Mantisse von `value` (Betrag, Exponent unbeachtet; 0 → 0) — gleich der Länge von
    /// `NSDecimalString` der Mantisse, aber ohne Text: die Mantisse als 128-Bit-Zahl gegen die Zehnerpotenzen.
    static func mantissaDigits(_ value: Decimal) -> Int {
        let m = value._mantissa
        let length = Int(value._length)
        func word(_ i: Int, _ w: UInt16) -> UInt64 { i < length ? UInt64(w) : 0 }
        var lo: UInt64 = word(0, m.0)
        lo |= word(1, m.1) << 16
        lo |= word(2, m.2) << 32
        lo |= word(3, m.3) << 48
        var hi: UInt64 = word(4, m.4)
        hi |= word(5, m.5) << 16
        hi |= word(6, m.6) << 32
        hi |= word(7, m.7) << 48
        var count = 0
        for p in tenPowers {
            // Mantisse ≥ 10^count: eine Ziffer mehr
            guard hi > p.hi || (hi == p.hi && lo >= p.lo) else { break }
            count += 1
        }
        return count
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

    /// Preis zum Speichern (Kaufpreis, Preis einer Gebühr im dritten Coin): `significantDigits` gültige Stellen, mindestens
    /// aber `maxScale` Nachkommastellen (wie eine Menge) und höchstens `maxParseScale` (Lesegrenze von `parse`), halbe auf
    /// gerade. So bleibt der Preis winziger Coins (1.3717333…E-9 BABYDOGE) erhalten, statt auf 18 Nachkommastellen zu
    /// schrumpfen oder unter 5E-19 zu 0 (= gratis) zu werden. Werte mit höchstens 18 Nachkommastellen bleiben unverändert.
    /// Wie Android (`PortfolioDecimal.price`).
    static func price(_ value: Decimal) -> Decimal {
        guard value != 0, !value.isNaN, value.isFinite else { return value }
        var compact = value
        NSDecimalCompact(&compact)
        let scale = -Int(compact.exponent)
        // Nachkommastellen für 34 gültige Stellen: 34 − Stellen vor dem Komma (bei 0.00… negativ gezählt)
        let significant = significantDigits - (mantissaDigits(compact) - scale)
        let target = min(maxParseScale, max(maxScale, significant))
        guard scale > target else { return compact }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &compact, target, .bankers)
        return rounded
    }

    /// Preis als gespeicherter Text (`price`): ohne Exponent und Nullen am Ende. Wie Android.
    static func priceText(_ value: Decimal) -> String {
        var p = price(value)
        if p == 0 { return "0" }
        NSDecimalCompact(&p)
        return NSDecimalString(&p, posix)
    }

    /// Menge, Gebühr oder Wert bleibt nach dem Runden auf `maxScale` Nachkommastellen lesbar (`withinLimits`).
    static func storableAmount(_ value: Decimal) -> Bool { withinLimits(amount(value)) }

    /// Preis bleibt nach `price` lesbar (`withinLimits`) und ein Preis > 0 wird nicht zu 0 (unter 5E-41: das wäre ein
    /// Kauf zum Preis 0 statt eines unbekannten Preises). Wie Android.
    static func storablePrice(_ value: Decimal) -> Bool {
        let p = price(value)
        return withinLimits(p) && (value <= 0 || p > 0)
    }

    /// Betrag so klein, dass er nur Rest aus einer Teilung sein kann.
    static func isDust(_ value: Decimal) -> Bool { abs(value) <= dust }
}
