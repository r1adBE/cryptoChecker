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
    /// MARRS_GREEN am Ende (wie Android): gespeicherte Namen bleiben gültig.
    case ORANGE, RED, BLUE, GREEN, MARRS_GREEN

    static let `default`: AccentColor = .ORANGE

    var id: String { rawValue }

    /// Grundfarbe (Seed), aus der das Schema abgeleitet ist.
    var seed: UInt32 {
        switch self {
        case .ORANGE: 0xDD6F48
        case .RED: 0xE8414D
        case .BLUE: 0x3B78F0
        case .GREEN: 0x22A96C
        case .MARRS_GREEN: 0x4BACA5
        }
    }

    private var primaryLight: UInt32 {
        switch self {
        // AA (≥ 4.5:1) auch auf den Karten (#EEEEEE/#E8E8E8), wie Android
        case .ORANGE: 0xB14D29
        case .RED: 0xCA2E3C
        case .BLUE: 0x2E66D6
        case .GREEN: 0x117C4D
        case .MARRS_GREEN: 0x22706B
        }
    }

    private var primaryDark: UInt32 {
        switch self {
        case .ORANGE: 0xED835E
        case .RED: 0xFF7173
        case .BLUE: 0x73A3FC
        case .GREEN: 0x4ABE83
        case .MARRS_GREEN: 0x5FB8B1
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
        case .MARRS_GREEN: 0x18514D
        }
    }

    private var primaryHighDark: UInt32 {
        switch self {
        case .ORANGE: 0xF5B7A2
        case .RED: 0xFFAFB1
        case .BLUE: 0xA8C6FD
        case .GREEN: 0x88D4AD
        case .MARRS_GREEN: 0x95D0CC
        }
    }

    private var containerLight: UInt32 {
        switch self {
        case .ORANGE: 0xFEE1D7
        case .RED: 0xFEE0DE
        case .BLUE: 0xDCE9FE
        case .GREEN: 0xD3F1DE
        case .MARRS_GREEN: 0xD7EFEC
        }
    }

    /// Dunkel neutral grau für alle Themen (wie Android `DarkSelectedContainer`): die dunklen
    /// Akzenttöne (#772D11, #870D1F …) wirkten bei Orange und Rot bräunlich/matschig.
    private var containerDark: UInt32 { 0x333333 }

    private var onContainerLight: UInt32 {
        switch self {
        case .ORANGE: 0x410D00
        case .RED: 0x480008
        case .BLUE: 0x001A57
        case .GREEN: 0x002A16
        case .MARRS_GREEN: 0x072826
        }
    }

    /// Schrift auf der grauen Auswahlfläche: die Akzentfarbe (≥ 4.5:1 auf #333333).
    private var onContainerDark: UInt32 { primaryDark }

    /// Grundfarbe als Farbe (Vorschau in der Auswahl).
    var seedColor: Color { Color(hex: seed) }

    var primary: Color { Color(uiColor: primaryUI) }
    var primaryUI: UIColor {
        .contrastDynamic(light: primaryLight, dark: primaryDark, highLight: primaryHighLight, highDark: primaryHighDark)
    }
    var container: Color { .dynamic(light: containerLight, dark: containerDark) }
    var onContainer: Color { .dynamic(light: onContainerLight, dark: onContainerDark) }

    /// Leichte Füllung für «ausgewählt» oder «hervorgehoben» (`alpha` 0.08 … 0.35): hell eine
    /// Spur der Akzentfarbe, dunkel neutral grau — wie Android `AppColors.accentTint`. Dunkel
    /// wirkt eine schwache Akzentfarbe sonst bräunlich (Orange, Rot) statt farbig.
    func tint(_ alpha: Double) -> Color {
        let accent = primaryUI
        return Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(white: 0xE2 / 255.0, alpha: alpha * 0.8)
                : accent.resolvedColor(with: traits).withAlphaComponent(alpha)
        })
    }

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
        case .MARRS_GREEN: "accent_marrs_green"
        }
    }

    /// Name des alternativen App-Icons (nil = Standard-Icon Orange). Hell/Dunkel/Getönt
    /// liefert der Asset-Katalog selbst (Darstellungsvarianten, ab iOS 18).
    var iconName: String? {
        self == .ORANGE ? nil : "AppIcon" + assetBase
    }

    /// Namensteil der Assets: Orange, Red, Blue, Green, MarrsGreen (wie `aliasBase` in Android).
    private var assetBase: String {
        switch self {
        case .ORANGE: "Orange"
        case .RED: "Red"
        case .BLUE: "Blue"
        case .GREEN: "Green"
        case .MARRS_GREEN: "MarrsGreen"
        }
    }

    /// Logo im Asset-Katalog.
    func logoName(dark: Bool) -> String {
        "Logo" + assetBase + (dark ? "Dark" : "Light")
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

/// Hex-Werte der neutralen Flächen, Texte und Linien — dieselben Werte wie die grauen Rollen in
/// `AppColorSchemes.kt` (Android). Einzige Quelle: `AppColors` (App) und `WidgetPalette`
/// (Widgets) lesen daraus, keine zweite Tabelle.
enum ColorTokens {
    /// Ein Wert für Hell und einer für Dunkel.
    struct Pair: Sendable {
        let light: UInt32
        let dark: UInt32
        func hex(dark isDark: Bool) -> UInt32 { isDark ? dark : light }
    }

    static let background = Pair(light: 0xF9F9F9, dark: 0x131313)
    static let surfaceContainerLow = Pair(light: 0xF3F3F3, dark: 0x1B1B1B)
    static let surfaceContainer = Pair(light: 0xEEEEEE, dark: 0x1F1F1F)
    static let surfaceContainerHigh = Pair(light: 0xE8E8E8, dark: 0x2A2A2A)
    static let surfaceContainerHighest = Pair(light: 0xE2E2E2, dark: 0x353535)
    static let onSurface = Pair(light: 0x1B1B1B, dark: 0xE2E2E2)
    static let onSurfaceVariant = Pair(light: 0x474747, dark: 0xC6C6C6)
    static let outline = Pair(light: 0x777777, dark: 0x919191)
    static let outlineVariant = Pair(light: 0xC6C6C6, dark: 0x474747)
    static let error = Pair(light: 0xBA1A1A, dark: 0xFFB4AB)
    /// Tiefere Fläche im Widget (unter der Grundfläche `surfaceContainer`).
    static let widgetSurfaceDeep = Pair(light: 0xE4E4E4, dark: 0x151515)

    /// Marktskala «Extrem Bear» … «Extrem Bull» — wie `MarketScaleColors` in Android.
    static let scaleExtremeBear: UInt32 = 0xB42318
    static let scaleBear: UInt32 = 0xE5484D
    static let scaleNeutral: UInt32 = 0x7A7A7A
    static let scaleBull: UInt32 = 0x2FA36B
    static let scaleExtremeBull: UInt32 = 0x0B7A45
    /// Dunkler Text auf hellen Skalenstufen (Android `OnLight`).
    static let onLightStep: UInt32 = 0x111111
}

extension Color {
    /// Hell/Dunkel aus einem Token-Paar.
    static func dynamic(_ pair: ColorTokens.Pair) -> Color { dynamic(light: pair.light, dark: pair.dark) }
}

extension UIColor {
    /// Wie `contrastDynamic(light:dark:…)`, aus Token-Paaren (Normal und hoher Kontrast).
    static func contrastDynamic(_ pair: ColorTokens.Pair, high: ColorTokens.Pair, highAlpha: CGFloat = 1) -> UIColor {
        contrastDynamic(light: pair.light, dark: pair.dark, highLight: high.light, highDark: high.dark, highAlpha: highAlpha)
    }
}

/// Neutrale Flächen — wie die grauen Flächen der Android-Farbschemata (Werte: `ColorTokens`).
/// Bei hohem Kontrast werden Nebentexte und Linien zu `onSurface` (wie Android:
/// onSurfaceVariant = outline = onSurface, outlineVariant = onSurface · 0.6).
enum AppColors {
    static let background = Color.dynamic(ColorTokens.background)
    static let surface = Color.dynamic(ColorTokens.background)
    static let containerLow = Color.dynamic(ColorTokens.surfaceContainerLow)
    static let container = Color.dynamic(ColorTokens.surfaceContainer)
    static let containerHigh = Color.dynamic(ColorTokens.surfaceContainerHigh)
    static let containerHighest = Color.dynamic(ColorTokens.surfaceContainerHighest)
    static let onSurface = Color.dynamic(ColorTokens.onSurface)
    static let onSurfaceVariant = Color(uiColor: .contrastDynamic(ColorTokens.onSurfaceVariant, high: ColorTokens.onSurface))
    static let outline = Color(uiColor: .contrastDynamic(ColorTokens.outline, high: ColorTokens.onSurface))
    static let outlineVariant = Color(uiColor: .contrastDynamic(ColorTokens.outlineVariant, high: ColorTokens.onSurface,
                                                               highAlpha: 0.6))
    static let error = Color.dynamic(ColorTokens.error)

    // Semantische Farben (wie Android `AppColors`): Akzent = `AccentColor.primary`,
    // steigend/fallend immer aus `PriceColorScheme`/`PriceColors`, «in Ordnung» = `PriceColors.ok`.

    /// Bernstein für «Achtung» (⚡, Status) — unabhängig von der Akzentfarbe.
    static let warning = Color.dynamic(light: 0xB26B00, dark: 0xFFC94D)
    /// Bernstein für Text: hell dunkler, damit es auch auf den Karten AA (≥ 4.5:1) erreicht.
    static let warningText = Color.dynamic(light: 0x8A5300, dark: 0xFFC94D)
    /// Löschen in Wisch-Aktionen (Systemrot).
    static let destructive = Color.red
    /// Hinweis-Banner und Toasts: dunkle Fläche mit heller Schrift, in Hell und Dunkel gleich.
    static let toastBackground = Color.black.opacity(0.86)
    static let onToast = Color.white
    /// Text und Symbole auf kräftigen Farbflächen (Akzent-Vorschau, Randzonen der Marktskala).
    static let onVivid = Color.white
    /// Schatten (Deckkraft setzt die Stelle).
    static let shadow = Color.black
}

/// Fünfstufige Skala von «Extrem Bear» bis «Extrem Bull» (Marktzonen, Fear & Greed) —
/// wie `MarketScaleColors` in Android. Feste Farben: Stufen, keine Kursrichtung.
enum MarketScaleColors {
    static let steps: [Color] = [
        Color(hex: ColorTokens.scaleExtremeBear),
        Color(hex: ColorTokens.scaleBear),
        Color(hex: ColorTokens.scaleNeutral),
        Color(hex: ColorTokens.scaleBull),
        Color(hex: ColorTokens.scaleExtremeBull),
    ]

    /// Text auf einer Stufe: weiss nur auf den dunklen Randstufen, sonst dunkel (Kontrast).
    static func onStep(_ index: Int) -> Color {
        index == 0 || index == steps.count - 1 ? AppColors.onVivid : Color(hex: ColorTokens.onLightStep)
    }
}

/// Feste Erkennungsfarben einzelner Coins (Anteilsbalken der Dominanz) — wie Android `AssetColors`.
enum AssetColors {
    static let bitcoin = Color(hex: 0xF7931A)
    static let ethereum = Color(hex: 0x627EEA)
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
