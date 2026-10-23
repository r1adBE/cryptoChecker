import Foundation

/// J-15: Farben der fünfstufigen Skala «Extreme Angst» … «Extreme Gier» (Fear & Greed), abgeleitet
/// aus den aktiven Kursfarben — so gilt auch das Schema für Farbsehschwäche, hoher Kontrast und der
/// Tausch. Angst = Fallend-Farbe, Gier = Steigend-Farbe, die mittleren Stufen je zu 45 % mit dem
/// neutralen Grau gemischt. Nur für Flächen (Skala, Markierung), nie für Schrift; jede Stufe hält
/// ≥ 3:1 auf allen Flächen bis #E2E2E2 bzw. #353535. Gilt für den Stufenpunkt der Fear-&-Greed-Karte
/// (`MarketScaleColors`) und des Puls-Widgets (Skala folgt den Kursfarben, passend zu den
/// Pfeilen; früher eine feste Skala). Wie Android `MarketScalePalette.kt`.
enum MarketScalePalette {
    /// Neutrale Mitte: hell dunkler (3.82:1 auf #E2E2E2), dunkel heller (3.65:1 auf #353535).
    static let neutralLight: UInt32 = 0x707070
    static let neutralDark: UInt32 = 0x8C8C8C

    /// Anteil Grau in den mittleren Stufen «Angst» / «Gier».
    static let mildMix = 0.45

    /// Stufen von links (Extreme Angst) nach rechts (Extreme Gier), RGB.
    /// `accent`: Ersatzfassungen der Kursfarben neben der Akzentfarbe (wie überall).
    static func steps(_ scheme: PriceColorScheme, dark: Bool, highContrast: Bool = false,
                      inverted: Bool = false, accent: AccentColor? = nil) -> [UInt32] {
        steps(up: scheme.upHex(dark: dark, highContrast: highContrast, inverted: inverted, accent: accent),
              down: scheme.downHex(dark: dark, highContrast: highContrast, inverted: inverted, accent: accent),
              dark: dark)
    }

    /// Stufen aus bereits aufgelösten Kursfarben (RGB) — für Stellen, die steigend/fallend schon haben
    /// (Widget: `WidgetPalette`), damit der Stufenpunkt genau zu den Pfeilen passt. Wie Android `steps(up, down, dark)`.
    static func steps(up: UInt32, down: UInt32, dark: Bool) -> [UInt32] {
        let neutral: UInt32 = dark ? neutralDark : neutralLight
        return [down, mix(down, neutral, mildMix), neutral, mix(up, neutral, mildMix), up]
    }

    /// Stufe 0…4 eines Fear-&-Greed-Werts (0–100).
    static func level(_ value: Int) -> Int {
        if value < 25 { return 0 }
        if value < 45 { return 1 }
        if value <= 55 { return 2 }
        if value <= 75 { return 3 }
        return 4
    }

    /// Stufenfarbe eines Werts (für Markierung/Punkt, nicht für die Zahl).
    static func color(_ value: Int, _ scheme: PriceColorScheme, dark: Bool, highContrast: Bool = false,
                      inverted: Bool = false, accent: AccentColor? = nil) -> UInt32 {
        steps(scheme, dark: dark, highContrast: highContrast, inverted: inverted, accent: accent)[level(value)]
    }

    /// Mischung im sRGB-Raum, `t` = Anteil von `b`.
    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        func channel(_ shift: UInt32) -> UInt32 {
            let ca = Double((a >> shift) & 0xFF)
            let cb = Double((b >> shift) & 0xFF)
            let v = (ca + (cb - ca) * t).rounded()
            return UInt32(min(max(v, 0), 255))
        }
        return (channel(16) << 16) | (channel(8) << 8) | channel(0)
    }
}
