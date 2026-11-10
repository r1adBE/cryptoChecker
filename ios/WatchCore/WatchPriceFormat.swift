import Foundation

/// Kurse auf der Uhr (Uhr-App und Komplikation): Kurs mit festen Nachkommastellen, knapp für kleine Komplikationen
/// (`compactPrice`) und die Zeile der rechteckigen Komplikation (`line`). Wie Android `WatchFormat` (:wear), geprüft mit
/// denselben Testfällen (`Parity/watch_price.json`).
enum WatchPriceFormat {

    /// Kurs kurz: ab 1000 zwei Nachkommastellen, ab 1 zwei bis vier, ab 0.01 vier bis sechs, darunter acht bis zehn.
    static func price(_ value: Double, locale: Locale = .current) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = locale
        switch value {
        case 1000...: f.minimumFractionDigits = 2; f.maximumFractionDigits = 2
        case 1...: f.minimumFractionDigits = 2; f.maximumFractionDigits = 4
        case 0.01...: f.minimumFractionDigits = 4; f.maximumFractionDigits = 6
        default: f.minimumFractionDigits = 8; f.maximumFractionDigits = 10
        }
        return f.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// Kurs knapp für kleine Komplikationen (etwa 7 Zeichen), Ziffern, Dezimalzeichen und Tausendertrennung der Sprache:
    /// ab 1 Mio. drei Stellen mit M/B/T («1.23M»), ab 10’000 ganz («84’450»), ab 1000 eine Nachkommastelle
    /// («2’234.6»), ab 10 zwei («123.46»), darunter vier signifikante Stellen wie die iPhone-App («1.234», «0.02468»),
    /// unter 0.01 drei («0.00988»), unter 0.001 mit Exponent («1.23e-5»). Kaufmännisch über die kürzeste
    /// Dezimaldarstellung gerundet; rundet ein Wert in die nächste Stufe, gilt deren Regel (9’999.96 → «10’000»,
    /// 999’999.6 → «1M»). Nicht positiv oder nicht endlich «—». Wie Android `WatchFormat.compactPrice`.
    static func compactPrice(_ value: Double, locale: Locale = .current) -> String {
        guard value.isFinite, value > 0 else { return "—" }
        let exact = Decimal(string: "\(value)", locale: Locale(identifier: "en_US_POSIX")) ?? Decimal(value)
        var current = step(exact)
        var rounded = snap(exact, current)
        // Höchstens bis zur nächsten Stufe: 9.99996 → 10.000 (zwei Nachkommastellen) → 10.00
        for _ in 0..<stepRounds {
            let next = step(rounded)
            if next == current { break }
            current = next
            rounded = snap(rounded, current)
        }
        return render(rounded, current, locale: locale)
    }

    /// Text der rechteckigen Komplikation: «BTC • 84’450.00 USDT  +1.20%», dahinter `detail` («  ·  Binance» bzw. das
    /// Alter); ohne Kurs «BTC • —». `quote` = Gegenwährung des Paars (USDT, USD, EUR …). Wie Android `WatchFormat.priceLine`.
    static func line(coin: String, price: Double?, quote: String, change: Double?, detail: String?,
                     locale: Locale = .current, language: String = WatchPercent.appLanguage) -> String {
        guard let price, price.isFinite else { return "\(coin) • —" }
        let text = "\(coin) • \(Self.price(price, locale: locale)) \(quote)  "
            + WatchPercent.change(change, locale: locale, language: language)
        guard let detail, !detail.trimmingCharacters(in: .whitespaces).isEmpty else { return text }
        return text + "  ·  " + detail
    }

    /// Stufen von `compactPrice`: Einheit (Zehnerexponent) bzw. Nachkommastellen oder signifikante Stellen.
    private enum Step { case trillion, billion, million, whole, oneDecimal, twoDecimals, fourDigits, threeDigits, exponent }

    private static let stepRounds = 4

    private static func step(_ x: Decimal) -> Step {
        if x >= power(12) { return .trillion }
        if x >= power(9) { return .billion }
        if x >= power(6) { return .million }
        if x >= 10_000 { return .whole }
        if x >= 1000 { return .oneDecimal }
        if x >= 10 { return .twoDecimals }
        if x >= power(-2) { return .fourDigits }
        if x >= power(-3) { return .threeDigits }
        return .exponent
    }

    private static func unitExponent(_ step: Step) -> Int? {
        switch step {
        case .trillion: 12
        case .billion: 9
        case .million: 6
        default: nil
        }
    }

    private static func unitSuffix(_ step: Step) -> String {
        switch step {
        case .trillion: "T"
        case .billion: "B"
        default: "M"
        }
    }

    /// 10^`exponent`, exakt.
    private static func power(_ exponent: Int) -> Decimal { Decimal(sign: .plus, exponent: exponent, significand: 1) }

    /// `x` · 10^`exponent`, exakt.
    private static func shifted(_ x: Decimal, _ exponent: Int) -> Decimal {
        Decimal(sign: .plus, exponent: exponent, significand: x)
    }

    /// Zehnerexponent der ersten Ziffer (84’450 → 4, 0.02468 → −2); `x` > 0.
    private static func magnitude(_ x: Decimal) -> Int {
        var e = 0
        if x >= 1 {
            while x >= power(e + 1) { e += 1 }
        } else {
            while x < power(e) { e -= 1 }
        }
        return e
    }

    /// Nachkommastellen für `digits` signifikante Stellen; bei Einheiten nie weniger als 0 (1’235T).
    private static func significantScale(_ x: Decimal, _ digits: Int, clamp: Bool = false) -> Int {
        let scale = digits - 1 - magnitude(x)
        return clamp ? max(0, scale) : scale
    }

    /// Kaufmännisch (bei der Hälfte weg von 0) auf `scale` Nachkommastellen, wie `RoundingMode.HALF_UP`.
    private static func rounded(_ x: Decimal, scale: Int) -> Decimal {
        var input = x
        var result = Decimal()
        NSDecimalRound(&result, &input, scale, .plain)
        return result
    }

    private static func snap(_ x: Decimal, _ step: Step) -> Decimal {
        if let e = unitExponent(step) {
            let mantissa = shifted(x, -e)
            return shifted(rounded(mantissa, scale: significantScale(mantissa, 3, clamp: true)), e)
        }
        let scale: Int
        switch step {
        case .whole: scale = 0
        case .oneDecimal: scale = 1
        case .twoDecimals: scale = 2
        case .fourDigits: scale = significantScale(x, 4)
        default: scale = significantScale(x, 3)
        }
        return rounded(x, scale: scale)
    }

    private static func render(_ r: Decimal, _ step: Step, locale: Locale) -> String {
        if let e = unitExponent(step) {
            let mantissa = shifted(r, -e)
            return number(mantissa, min: 0, max: significantScale(mantissa, 3, clamp: true), locale: locale) + unitSuffix(step)
        }
        switch step {
        case .whole: return number(r, min: 0, max: 0, locale: locale)
        case .oneDecimal: return number(r, min: 1, max: 1, locale: locale)
        case .twoDecimals: return number(r, min: 2, max: 2, locale: locale)
        case .fourDigits: return number(r, min: 0, max: significantScale(r, 4), locale: locale)
        case .threeDigits: return number(r, min: 0, max: significantScale(r, 3), locale: locale)
        default:
            let e = magnitude(r)
            return number(shifted(r, -e), min: 0, max: 2, locale: locale) + "e" + String(e)
        }
    }

    /// Zahl in der Schreibweise der Sprache (mit Tausendertrennung), `min`…`max` Nachkommastellen, ohne Nullen am Ende.
    private static func number(_ x: Decimal, min: Int, max: Int, locale: Locale) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = locale
        f.roundingMode = .halfUp
        f.usesGroupingSeparator = true
        f.minimumFractionDigits = min
        f.maximumFractionDigits = max
        return f.string(from: NSDecimalNumber(decimal: x)) ?? "\(x)"
    }
}

/// Portfolio-Komplikation ohne Wert (Schalter «Portfolio-Wert auf der Uhr» aus oder noch nie ein Wert): sagt es, statt
/// leer zu bleiben. Wie Android `WatchPortfolioText.offLine` / `offSpoken`.
enum WatchPortfolioOff {
    /// «Portfolio · am Handy einschalten».
    static func line(title: String, hint: String) -> String { "\(title) · \(hint)" }

    /// Für VoiceOver: «Portfolio, am Handy einschalten».
    static func spoken(title: String, hint: String) -> String { "\(title), \(hint)" }
}
