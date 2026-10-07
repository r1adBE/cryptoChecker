import SwiftUI
import UIKit

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: alpha)
    }

    /// Farbe, die je nach Hell/Dunkel wechselt.
    static func dynamic(light: UInt32, dark: UInt32) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) }
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(uiColor: UIColor(hex: hex, alpha: opacity))
    }

    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: .dynamic(light: light, dark: dark))
    }
}

/// Akzentfarbe der App — wie `AccentColor.kt`. Die Namen sind die Kotlin-Enum-Namen
/// (Sicherungen bleiben zwischen Android und iOS austauschbar).
enum AccentColor: String, CaseIterable, Codable, Identifiable, Sendable {
    case ORANGE, RED, BLUE, GREEN

    static let `default`: AccentColor = .ORANGE

    var id: String { rawValue }

    /// Grundfarbe (Seed), aus der das Schema abgeleitet ist.
    var seed: UInt32 {
        switch self {
        case .ORANGE: 0xDD6F48
        case .RED: 0xE8414D
        case .BLUE: 0x3B78F0
        case .GREEN: 0x22A96C
        }
    }

    private var primaryLight: UInt32 {
        switch self {
        // AA (≥ 4.5:1) auch auf den Karten (#EEEEEE/#E8E8E8), wie Android
        case .ORANGE: 0xB14D29
        case .RED: 0xCA2E3C
        case .BLUE: 0x2E66D6
        case .GREEN: 0x117C4D
        }
    }

    private var primaryDark: UInt32 {
        switch self {
        case .ORANGE: 0xED835E
        case .RED: 0xFF7173
        case .BLUE: 0x73A3FC
        case .GREEN: 0x4ABE83
        }
    }

    /// Hoher Kontrast: Akzent so weit abgedunkelt bzw. aufgehellt, dass er auf der
    /// dunkelsten Kartenfläche (#E2E2E2 / #353535) mindestens 7:1 erreicht
    /// (gleiche Werte wie Android `withContrast`).
    private var primaryHighLight: UInt32 {
        switch self {
        case .ORANGE: 0x76331B
        case .RED: 0x871E28
        case .BLUE: 0x1F448F
        case .GREEN: 0x0B5233
        }
    }

    private var primaryHighDark: UInt32 {
        switch self {
        case .ORANGE: 0xF5B7A2
        case .RED: 0xFFAFB1
        case .BLUE: 0xA8C6FD
        case .GREEN: 0x88D4AD
        }
    }

    private var containerLight: UInt32 {
        switch self {
        case .ORANGE: 0xFEE1D7
        case .RED: 0xFEE0DE
        case .BLUE: 0xDCE9FE
        case .GREEN: 0xD3F1DE
        }
    }

    private var containerDark: UInt32 {
        switch self {
        case .ORANGE: 0x772D11
        case .RED: 0x870D1F
        case .BLUE: 0x184194
        case .GREEN: 0x035633
        }
    }

    private var onContainerLight: UInt32 {
        switch self {
        case .ORANGE: 0x410D00
        case .RED: 0x480008
        case .BLUE: 0x001A57
        case .GREEN: 0x002A16
        }
    }

    private var onContainerDark: UInt32 { containerLight }

    var primary: Color { Color(uiColor: primaryUI) }
    var primaryUI: UIColor {
        .contrastDynamic(light: primaryLight, dark: primaryDark, highLight: primaryHighLight, highDark: primaryHighDark)
    }
    var container: Color { .dynamic(light: containerLight, dark: containerDark) }
    var onContainer: Color { .dynamic(light: onContainerLight, dark: onContainerDark) }

    /// Text auf `primary` (Knöpfe).
    var onPrimary: Color { .dynamic(light: 0xFFFFFF, dark: onContainerLight) }

    func primary(dark: Bool) -> Color { Color(hex: dark ? primaryDark : primaryLight) }

    /// Fester Hell/Dunkel-Akzent mit Hoch-Kontrast-Variante (Widgets, z. B. Trennlinien der Liste).
    func primary(dark: Bool, highContrast: Bool) -> Color {
        guard highContrast else { return primary(dark: dark) }
        return Color(hex: dark ? primaryHighDark : primaryHighLight)
    }

    /// Text auf `primary(dark:)` mit festem Hell/Dunkel (Widgets) — wie `onPrimary`.
    func onPrimary(dark: Bool) -> Color { Color(hex: dark ? onContainerLight : 0xFFFFFF) }

    var labelKey: String {
        switch self {
        case .ORANGE: "accent_orange"
        case .RED: "accent_red"
        case .BLUE: "accent_blue"
        case .GREEN: "accent_green"
        }
    }

    /// Name des alternativen App-Icons (nil = Standard-Icon Orange). Hell/Dunkel/Getönt
    /// liefert der Asset-Katalog selbst (Darstellungsvarianten, ab iOS 18).
    var iconName: String? {
        self == .ORANGE ? nil : "AppIcon" + rawValue.capitalized
    }

    /// Logo im Asset-Katalog.
    func logoName(dark: Bool) -> String {
        "Logo" + rawValue.capitalized + (dark ? "Dark" : "Light")
    }
}

/// Hoher Kontrast: Einstellung «Hoher Kontrast» oder das System verlangt mehr
/// Kontrast («Kontrast erhöhen» → `accessibilityContrast == .high`, in SwiftUI
/// `colorSchemeContrast == .increased`). `setting` setzt die App (AppData).
enum HighContrast {
    nonisolated(unsafe) static var setting = false

    static func isOn(_ traits: UITraitCollection) -> Bool {
        setting || traits.accessibilityContrast == .high
    }
}

extension UIColor {
    /// Wie `dynamic(light:dark:)`, bei hohem Kontrast mit eigener Fassung.
    static func contrastDynamic(light: UInt32, dark: UInt32, highLight: UInt32, highDark: UInt32,
                                highAlpha: CGFloat = 1) -> UIColor {
        UIColor { t in
            let isDark = t.userInterfaceStyle == .dark
            if HighContrast.isOn(t) { return UIColor(hex: isDark ? highDark : highLight, alpha: highAlpha) }
            return UIColor(hex: isDark ? dark : light)
        }
    }
}

/// Neutrale Flächen — wie die grauen Flächen der Android-Farbschemata.
/// Bei hohem Kontrast werden Nebentexte und Linien zu `onSurface` (wie Android:
/// onSurfaceVariant = outline = onSurface, outlineVariant = onSurface · 0.6).
enum AppColors {
    static let background = Color.dynamic(light: 0xF9F9F9, dark: 0x131313)
    static let surface = Color.dynamic(light: 0xF9F9F9, dark: 0x131313)
    static let containerLow = Color.dynamic(light: 0xF3F3F3, dark: 0x1B1B1B)
    static let container = Color.dynamic(light: 0xEEEEEE, dark: 0x1F1F1F)
    static let containerHigh = Color.dynamic(light: 0xE8E8E8, dark: 0x2A2A2A)
    static let containerHighest = Color.dynamic(light: 0xE2E2E2, dark: 0x353535)
    static let onSurface = Color.dynamic(light: 0x1B1B1B, dark: 0xE2E2E2)
    static let onSurfaceVariant = Color(uiColor: .contrastDynamic(light: 0x474747, dark: 0xC6C6C6,
                                                                 highLight: 0x1B1B1B, highDark: 0xE2E2E2))
    static let outline = Color(uiColor: .contrastDynamic(light: 0x777777, dark: 0x919191,
                                                        highLight: 0x1B1B1B, highDark: 0xE2E2E2))
    static let outlineVariant = Color(uiColor: .contrastDynamic(light: 0xC6C6C6, dark: 0x474747,
                                                               highLight: 0x1B1B1B, highDark: 0xE2E2E2, highAlpha: 0.6))
    static let error = Color.dynamic(light: 0xBA1A1A, dark: 0xFFB4AB)
}

/// Kursfarben steigend/fallend — wie `PriceColorScheme.kt`. Blau/Orange bleibt bei
/// jeder Farbsehschwäche (Rot-Grün und Blau-Gelb) gut unterscheidbar; die Richtung
/// steht zusätzlich immer als + / − (und Pfeil) daneben. Die Namen sind die
/// Kotlin-Enum-Namen (Sicherungen bleiben zwischen Android und iOS austauschbar).
///
/// Normale Werte erreichen WCAG AA (≥ 4.5:1), die Werte für hohen Kontrast AAA (≥ 7:1)
/// — auf #FFFFFF, #F9F9F9, #EEEEEE, #E4E4E4 bzw. #121212, #131313, #1F1F1F und in der
/// 14 %-getönten Pille.
///
/// `inverted` («Farben tauschen»): steigend in der Fall-Farbe und umgekehrt (Rot =
/// steigend wie in China, Japan, Korea, Taiwan). Nur die Farben tauschen — Vorzeichen,
/// Pfeile und VoiceOver-Wörter bleiben.
enum PriceColorScheme: String, CaseIterable, Codable, Identifiable, Sendable {
    case GREEN_RED, BLUE_ORANGE

    static let `default`: PriceColorScheme = .GREEN_RED

    var id: String { rawValue }

    /// Grün bzw. Blau (ohne Tausch). Im Dunkeln hellere, im Hellen dunklere Fassungen.
    private func riseHex(dark: Bool, highContrast: Bool) -> UInt32 {
        switch self {
        case .GREEN_RED: highContrast ? (dark ? 0x7CF2B8 : 0x004A27) : (dark ? 0x3DD68C : 0x0A6D3E)
        case .BLUE_ORANGE: highContrast ? (dark ? 0xA6D4FF : 0x093D83) : (dark ? 0x64B5F6 : 0x1460AB)
        }
    }

    /// Rot bzw. Orange (ohne Tausch).
    private func fallHex(dark: Bool, highContrast: Bool) -> UInt32 {
        switch self {
        case .GREEN_RED: highContrast ? (dark ? 0xFFB0B0 : 0x800B0B) : (dark ? 0xFF6B6B : 0xB22727)
        case .BLUE_ORANGE: highContrast ? (dark ? 0xFFC685 : 0x6C2E00) : (dark ? 0xFFA040 : 0x9F4300)
        }
    }

    /// Farbe für «steigend» (bei `inverted` die Fall-Farbe).
    func upHex(dark: Bool, highContrast: Bool = false, inverted: Bool = false) -> UInt32 {
        inverted ? fallHex(dark: dark, highContrast: highContrast) : riseHex(dark: dark, highContrast: highContrast)
    }

    /// Farbe für «fallend» (bei `inverted` die Steig-Farbe).
    func downHex(dark: Bool, highContrast: Bool = false, inverted: Bool = false) -> UInt32 {
        inverted ? riseHex(dark: dark, highContrast: highContrast) : fallHex(dark: dark, highContrast: highContrast)
    }

    /// Hell/Dunkel nach der Ansicht; hoher Kontrast, wenn `highContrast` gesetzt ist
    /// oder `HighContrast.isOn` (Einstellung bzw. «Kontrast erhöhen» des Systems).
    func up(highContrast: Bool, inverted: Bool = false) -> Color {
        Color(uiColor: UIColor { t in
            UIColor(hex: upHex(dark: t.userInterfaceStyle == .dark,
                               highContrast: highContrast || HighContrast.isOn(t), inverted: inverted))
        })
    }

    func down(highContrast: Bool, inverted: Bool = false) -> Color {
        Color(uiColor: UIColor { t in
            UIColor(hex: downHex(dark: t.userInterfaceStyle == .dark,
                                 highContrast: highContrast || HighContrast.isOn(t), inverted: inverted))
        })
    }

    /// Ohne Tausch (z. B. Vorschau-Punkte der Auswahl brauchen den Tausch explizit).
    var up: Color { up(highContrast: false) }
    var down: Color { down(highContrast: false) }

    func forChange(_ change: Double?, highContrast: Bool = false, inverted: Bool = false) -> Color {
        (change ?? 0) >= 0 ? up(highContrast: highContrast, inverted: inverted)
            : down(highContrast: highContrast, inverted: inverted)
    }

    var labelKey: String {
        switch self {
        case .GREEN_RED: "price_colors_green_red"
        case .BLUE_ORANGE: "price_colors_blue_orange"
        }
    }
}

private struct PriceColorSchemeKey: EnvironmentKey {
    /// Ohne gesetzten Wert (z. B. in einem Blatt) gilt die aktuelle App-Einstellung.
    static var defaultValue: PriceColorScheme { PriceColors.scheme }
}

private struct PriceHighContrastKey: EnvironmentKey {
    static var defaultValue: Bool { HighContrast.setting }
}

private struct PriceColorsInvertedKey: EnvironmentKey {
    static var defaultValue: Bool { PriceColors.inverted }
}

extension EnvironmentValues {
    /// Hoher Kontrast wirksam (Einstellung oder System) — setzt die Wurzel der App.
    var priceHighContrast: Bool {
        get { self[PriceHighContrastKey.self] }
        set { self[PriceHighContrastKey.self] = newValue }
    }

    /// «Farben tauschen (steigend ↔ fallend)» — setzt die Wurzel der App.
    var priceColorsInverted: Bool {
        get { self[PriceColorsInvertedKey.self] }
        set { self[PriceColorsInvertedKey.self] = newValue }
    }

    /// Kursfarben der App (Einstellung «Kursfarben»). Ansichten, die Kursfarben
    /// zeigen, lesen sie hier — so zeichnen sie sich beim Umstellen sofort neu.
    var priceColorScheme: PriceColorScheme {
        get { self[PriceColorSchemeKey.self] }
        set { self[PriceColorSchemeKey.self] = newValue }
    }
}

/// Gewinn/Verlust in festen Farben, unabhängig von der Akzentfarbe — Grün/Rot
/// oder Blau/Orange je nach Einstellung. `scheme` setzt die App beim Laden und
/// Ändern der Einstellungen (AppData); Ansichten nehmen besser
/// `@Environment(\.priceColorScheme)`, damit sie beim Umstellen neu zeichnen.
enum PriceColors {
    nonisolated(unsafe) static var scheme: PriceColorScheme = .default
    /// «Farben tauschen» (setzt AppData).
    nonisolated(unsafe) static var inverted = false

    /// Richtung steigend/fallend (mit Tausch); hoher Kontrast über `HighContrast`.
    static var up: Color { scheme.up(highContrast: false, inverted: inverted) }
    static var down: Color { scheme.down(highContrast: false, inverted: inverted) }

    /// «Alles in Ordnung» (z. B. aktuelle Kurse, schon in der Merkliste) — keine
    /// Kursrichtung, deshalb nie getauscht.
    static var ok: Color { scheme.up }

    static func forChange(_ change: Double?) -> Color { scheme.forChange(change, inverted: inverted) }
}
