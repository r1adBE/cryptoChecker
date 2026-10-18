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

    /// Grundfarbe (Seed), aus der das Schema abgeleitet ist. Ein Orange (Build 46): #ED835E ist
    /// Markenfarbe, App-Icon und Akzent dunkel; #A64827 (hell) ist die dunklere Stufe davon (AA).
    var seed: UInt32 {
        switch self {
        case .ORANGE: 0xED835E
        case .RED: 0xE8414D
        case .BLUE: 0x3B78F0
        case .GREEN: 0x22A96C
        case .MARRS_GREEN: 0x4BACA5
        }
    }

    private var primaryLight: UInt32 {
        switch self {
        // AA auch auf der dunkelsten Fläche #E2E2E2 (4.51–4.54:1, Weiss darauf ≥ 5.8), wie Android
        case .ORANGE: 0xA64827
        case .RED: 0xBE2B38
        case .BLUE: 0x2B5FC8
        case .GREEN: 0x107348
        case .MARRS_GREEN: 0x22706B
        }
    }

    /// Auf #353535 (hellste dunkle Fläche): Orange 4.68, Rot 4.59, Blau 4.89, Grün 5.25, Marrs 5.24.
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
    /// Werte aus Android `WidgetContrast.highContrastAccent` (ganzzahlig gemischt, 7.03–7.27:1).
    private var primaryHighLight: UInt32 {
        switch self {
        case .ORANGE: 0x78341C
        case .RED: 0x891F28
        case .BLUE: 0x1F4490
        case .GREEN: 0x0C5334
        case .MARRS_GREEN: 0x184E4B
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

    func primary(dark: Bool) -> Color { Color(hex: primaryHex(dark: dark)) }

    /// Akzent als Hex (Hell/Dunkel, optional hoher Kontrast) — für Kontrast-Tests und Widgets.
    func primaryHex(dark: Bool, highContrast: Bool = false) -> UInt32 {
        if highContrast { return dark ? primaryHighDark : primaryHighLight }
        return dark ? primaryDark : primaryLight
    }

    /// Text auf dem Akzent als Hex (wie `onPrimary(dark:)`).
    func onPrimaryHex(dark: Bool) -> UInt32 { dark ? onContainerLight : 0xFFFFFF }

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
    /// Gelesen in den Farb-Closures von UIKit (jeder Thread, auch Widgets), geschrieben von AppData:
    /// darum hinter einer Sperre statt `nonisolated(unsafe)`.
    private static let state = LockedBox(false)
    static var setting: Bool {
        get { state.current }
        set { state.current = newValue }
    }

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

    static let background = Pair(light: 0xFBF9F6, dark: 0x131313)
    static let surfaceContainerLow = Pair(light: 0xF6F3EE, dark: 0x1B1B1B)
    static let surfaceContainer = Pair(light: 0xF1EEE9, dark: 0x1F1F1F)
    static let surfaceContainerHigh = Pair(light: 0xEBE8E2, dark: 0x2A2A2A)
    static let surfaceContainerHighest = Pair(light: 0xE5E2DB, dark: 0x353535)
    static let onSurface = Pair(light: 0x1B1B1B, dark: 0xE2E2E2)
    static let onSurfaceVariant = Pair(light: 0x474747, dark: 0xC6C6C6)
    static let outline = Pair(light: 0x777777, dark: 0x919191)
    /// Trennlinien: hell #ADA9A0 ≈ 2.2:1 auf #FBF9F6 (Build 58, vorher #C9C6BF 1.6:1) — wie Android.
    static let outlineVariant = Pair(light: 0xADA9A0, dark: 0x474747)
    static let error = Pair(light: 0xBA1A1A, dark: 0xFFB4AB)
    /// Tiefere Fläche im Widget (unter der Grundfläche `surfaceContainer`).
    static let widgetSurfaceDeep = Pair(light: 0xE7E4DD, dark: 0x151515)
    /// «Unverändert» im Widget (`WidgetPalette.neutral`): hell #5F5F5F statt `outline` #777777
    /// (3.87:1 auf surfaceContainer, unter AA) — wie Android `WidgetColors.neutralColor`.
    static let widgetNeutral = Pair(light: 0x5F5F5F, dark: 0x919191)

    /// Bitcoin-Orange: hell dunkler (#F7931A hielt auf heller Fläche nur ≈ 2.2:1), ≥ 3:1 als Grafik bis
    /// #E2E2E2/#E5E2DB; dunkel das Original (≥ 5:1 bis #353535). Wie Android `AssetPalette`.
    static let bitcoin = Pair(light: 0xB86200, dark: 0xF7931A)
    /// Ethereum-Blau: hell dunkler (#627EEA hielt auf #E5E2DB nur ≈ 2.85:1), ≥ 3:1 als Grafik;
    /// dunkel das Original (≥ 3.3:1 bis #353535). Wie Android `AssetPalette.ethereum`.
    static let ethereum = Pair(light: 0x4C64D0, dark: 0x627EEA)
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

/// Fünfstufige Fear-&-Greed-Skala in den aktiven Kursfarben (Werte: `MarketScalePalette.steps`) — Schema inkl.
/// Farbsehschwäche, hoher Kontrast, Tausch und Ersatzfassungen neben der Akzentfarbe, also dieselben Farben wie
/// die Kurspfeile. Nur für Grafik (Stufenpunkt, je ≥ 3:1), nie für Schrift. Wie `MarketScaleColors` in Android.
enum MarketScaleColors {
    /// Farbe der Stufe `index` (0…4); Hell/Dunkel nach der Ansicht, hoher Kontrast auch über `HighContrast.isOn`,
    /// Akzentfarbe aus `PriceColors.accent` — wie `PriceColorScheme.up(highContrast:inverted:)`.
    static func step(_ index: Int, scheme: PriceColorScheme, highContrast: Bool, inverted: Bool) -> Color {
        let accent: AccentColor = PriceColors.accent
        return Color(uiColor: UIColor { t in
            UIColor(hex: MarketScalePalette.steps(scheme, dark: t.userInterfaceStyle == .dark,
                                                  highContrast: highContrast || HighContrast.isOn(t),
                                                  inverted: inverted, accent: accent)[index])
        })
    }
}

/// Feste Erkennungsfarben einzelner Coins (Anteilsbalken der Dominanz) — wie Android `AssetColors`.
enum AssetColors {
    static let bitcoin = Color.dynamic(ColorTokens.bitcoin)
    static let ethereum = Color.dynamic(ColorTokens.ethereum)
}

/// Kursfarben steigend/fallend — wie `PriceColorScheme.kt`. Blau/Orange bleibt bei
/// jeder Farbsehschwäche (Rot-Grün und Blau-Gelb) gut unterscheidbar; die Richtung
/// steht zusätzlich immer als + / − (und Pfeil) daneben. Die Namen sind die
/// Kotlin-Enum-Namen (Sicherungen bleiben zwischen Android und iOS austauschbar).
///
/// Normale Werte erreichen WCAG AA (≥ 4.5:1), die Werte für hohen Kontrast AAA (≥ 7:1)
/// — auf allen hellen Flächen #FFFFFF … #E2E2E2 bzw. dunklen #0E0E0E … #353535 (Änderungen als
/// Schrift ohne Pille). Die Börsenfarben liegen je knapp über 4.5:1 bzw. 7:1 (so nah am Börsenton,
/// wie der Kontrast erlaubt).
///
/// Feste Börsenfarben («Frisch», «Traditionell», «Farbsehschwäche», Entscheid 18. Oktober 2026:
/// «Farben wie bei allen Börsen, die Akzentfarbe soll nicht irritieren»), geprüft mit
/// `android/tools/check_price_colors.py` und `SettingsLogicTests`: je Farbe der Börsenton so nah wie
/// möglich (CIEDE2000; «Frisch» = Binance #0ECB81 / #F6465D, «Traditionell» = TradingView #089981 /
/// #F23645, «Farbsehschwäche» = Blau/Orange #1565C0 / #E65100 hell, #3D9BFF / #F7931A dunkel),
/// hart nur der Kontrast (≥ 4.5:1, hoher Kontrast ≥ 7:1); «Frisch» und «Traditionell» je Farbe
/// ΔE2000 ≥ 8; nur «Farbsehschwäche» ΔE2000 ≥ 35 unter Protanopie, Deuteranopie, Tritanopie (Machado
/// 2009) und Leuchtdichte ≥ 1.4:1. Kein Abstand zur Akzentfarbe. Wie Android.
///
/// `inverted` («Farben tauschen»): steigend in der Fall-Farbe und umgekehrt (Rot =
/// steigend wie in China, Japan, Korea, Taiwan). Nur die Farben tauschen — Vorzeichen,
/// Pfeile und VoiceOver-Wörter bleiben.
///
/// Akzentfarbe: Liegt eine Kursfarbe neben `AccentColor.primaryHex` (hell und dunkel) unter
/// ΔE2000 `minAccentDeltaE`, nimmt das Schema die nächste Fassung seiner Liste (nur «Ruhig» und
/// «Ruhig Farbsehschwäche», z. B. Karmin statt Rot bei orangem Akzent); hoher Kontrast nimmt dieselbe Fassung (`ColorDifference`).
/// Eine Fassung kann eine tiefere eigene Schwelle haben (`PriceTone.minAccentLight/Dark`).
enum PriceColorScheme: String, CaseIterable, Codable, Identifiable, Sendable {
    /// Reihenfolge = Reihenfolge der Karten in «Kursfarben» (Ruhig, Frisch, Traditionell,
    /// Farbsehschwäche, Ruhig Farbsehschwäche); gespeichert wird der Name, nie die Position. Wie Android `PriceColorScheme`.
    case CALM, GREEN_RED, TRADITIONAL, BLUE_ORANGE, CALM_COLOR_VISION

    /// Standard seit Build 46: «Ruhig» (gedämpftes Grün/Rot); «Frisch» (Binance-Grün/-Rot) bleibt wählbar.
    static let `default`: PriceColorScheme = .CALM

    /// Gespeicherte Einstellungen ohne Merker `priceColorDefaultV2`: «Frisch» war der mitgespeicherte
    /// frühere Standard (iOS speichert alle Einstellungen als Ganzes) → «Ruhig»; andere Wahl bleibt.
    static func migrated(_ stored: PriceColorScheme, markerPresent: Bool) -> PriceColorScheme {
        !markerPresent && stored == .GREEN_RED ? .default : stored
    }

    var id: String { rawValue }

    /// Mindestabstand (ΔE2000) jeder Kursfarbe zur Akzentfarbe, hell und dunkel je für sich.
    static let minAccentDeltaE: Double = 24

    /// Steigend: erste Fassung = Standard. Nur «Ruhig» und «Ruhig Farbsehschwäche» haben Ersatz für Akzente, die ihr zu ähnlich sind;
    /// die übrigen Schemata bleiben feste Börsenfarben (Entscheid 16. Oktober 2026).
    private var rises: [PriceTone] {
        switch self {
        case .CALM: [.calmGreen, .calmBlue]
        // Binance-Grün #0ECB81 (dunkel genau, sonst gleicher Farbton mit nötigem Kontrast)
        case .GREEN_RED: [PriceTone(0x127248, 0x0ECB81, 0x025430, 0x30DE92)]
        // TradingView-Grün #089981
        case .TRADITIONAL: [PriceTone(0x0A7262, 0x3EAE97, 0x005246, 0x5AD8BE)]
        // Blau nahe #1565C0 / #3D9BFF (Entscheid 18. Oktober 2026)
        case .BLUE_ORANGE: [PriceTone(0x064E93, 0x4F9FFD, 0x00335F, 0xCFEAFF)]
        // Ersatz auf der Blau-Gelb-Achse: Petrol bzw. Blau-Violett statt Blau
        case .CALM_COLOR_VISION: [PriceTone(0x1460AB, 0x64B5F6, 0x093D83, 0xA6D4FF), .petrol, .vividBlue]
        }
    }

    /// Fallend, ebenso (nur die beiden «Ruhig»-Schemata mit Ersatz).
    private var falls: [PriceTone] {
        switch self {
        case .CALM: [.calmRed, .calmCrimson, .calmPlum]
        // Binance-Rot #F6465D
        case .GREEN_RED: [PriceTone(0xC3213A, 0xFC6F7D, 0x911127, 0xFFAEB7)]
        // TradingView-Rot #F23645; dunkel röter (≈ 6°), damit ΔE ≥ 8 zu «Frisch»
        case .TRADITIONAL: [PriceTone(0xCC0416, 0xFE7062, 0x960208, 0xFEB2A4)]
        // Orange nahe #E65100 / #F7931A
        case .BLUE_ORANGE: [PriceTone(0xB73900, 0xFFA73E, 0x842A00, 0xFFB46A)]
        case .CALM_COLOR_VISION: [PriceTone(0x9F4300, 0xFFA040, 0x6C2E00, 0xFFC685), .mustard]
        }
    }

    /// Kleinerer Abstand (hell, dunkel) von `tone` zur Akzentfarbe in normalem Kontrast.
    static func accentDistance(_ tone: PriceTone, _ accent: AccentColor) -> Double {
        min(ColorDifference.deltaE2000(tone.light, accent.primaryHex(dark: false)),
            ColorDifference.deltaE2000(tone.dark, accent.primaryHex(dark: true)))
    }

    /// Hält `tone` neben `accent` seine Schwelle ein (hell und dunkel je für sich)?
    private static func fitsBeside(_ tone: PriceTone, _ accent: AccentColor) -> Bool {
        ColorDifference.deltaE2000(tone.light, accent.primaryHex(dark: false)) >= tone.minAccentLight
            && ColorDifference.deltaE2000(tone.dark, accent.primaryHex(dark: true)) >= tone.minAccentDark
    }

    /// Erste Fassung, die ihre Schwelle einhält; sonst die mit dem grössten Abstand.
    private static func pick(_ tones: [PriceTone], _ accent: AccentColor) -> PriceTone {
        if let tone = tones.first(where: { fitsBeside($0, accent) }) { return tone }
        return tones.max { accentDistance($0, accent) < accentDistance($1, accent) } ?? tones[0]
    }

    /// Gewählte Fassungen je Schema und Akzent, einmal gerechnet.
    private static let choices: [PriceColorScheme: [AccentColor: (rise: PriceTone, fall: PriceTone)]] = {
        var out: [PriceColorScheme: [AccentColor: (rise: PriceTone, fall: PriceTone)]] = [:]
        for scheme in PriceColorScheme.allCases {
            var row: [AccentColor: (rise: PriceTone, fall: PriceTone)] = [:]
            for accent in AccentColor.allCases {
                row[accent] = (PriceColorScheme.pick(scheme.rises, accent), PriceColorScheme.pick(scheme.falls, accent))
            }
            out[scheme] = row
        }
        return out
    }()

    private func rise(_ accent: AccentColor?) -> PriceTone {
        guard let accent, let choice = Self.choices[self]?[accent] else { return rises[0] }
        return choice.rise
    }

    private func fall(_ accent: AccentColor?) -> PriceTone {
        guard let accent, let choice = Self.choices[self]?[accent] else { return falls[0] }
        return choice.fall
    }

    /// Mindestabstand (ΔE2000) zur Akzentfarbe, den die gewählte Fassung einhalten muss: meist
    /// `minAccentDeltaE`, tiefer nur für Fassungen mit eigener Schwelle (Karmin neben Orange).
    func accentFloor(up: Bool, dark: Bool, accent: AccentColor) -> Double {
        let tone = up ? rise(accent) : fall(accent)
        return dark ? tone.minAccentDark : tone.minAccentLight
    }

    /// Weicht neben `accent` mindestens eine Kursfarbe vom Standard des Schemas ab?
    func clashes(with accent: AccentColor?) -> Bool {
        rise(accent) != rises[0] || fall(accent) != falls[0]
    }

    /// Farbe für «steigend» (bei `inverted` die Fall-Farbe). `accent`: ist eine Kursfarbe ihr zu
    /// ähnlich (ΔE2000 < `minAccentDeltaE`), gilt bei «Ruhig» und «Ruhig Farbsehschwäche» die Ersatzfassung — wie Android.
    func upHex(dark: Bool, highContrast: Bool = false, inverted: Bool = false, accent: AccentColor? = nil) -> UInt32 {
        (inverted ? fall(accent) : rise(accent)).hex(dark: dark, highContrast: highContrast)
    }

    /// Farbe für «fallend» (bei `inverted` die Steig-Farbe, mit `accent` wie bei `upHex`).
    func downHex(dark: Bool, highContrast: Bool = false, inverted: Bool = false, accent: AccentColor? = nil) -> UInt32 {
        (inverted ? rise(accent) : fall(accent)).hex(dark: dark, highContrast: highContrast)
    }

    /// Hell/Dunkel nach der Ansicht; hoher Kontrast, wenn `highContrast` gesetzt ist
    /// oder `HighContrast.isOn` (Einstellung bzw. «Kontrast erhöhen» des Systems).
    /// Akzentfarbe aus `PriceColors.accent` (setzt AppData), wie Android `LocalAccentColor`.
    func up(highContrast: Bool, inverted: Bool = false) -> Color {
        let accent: AccentColor = PriceColors.accent
        return Color(uiColor: UIColor { t in
            UIColor(hex: upHex(dark: t.userInterfaceStyle == .dark,
                               highContrast: highContrast || HighContrast.isOn(t), inverted: inverted, accent: accent))
        })
    }

    func down(highContrast: Bool, inverted: Bool = false) -> Color {
        let accent: AccentColor = PriceColors.accent
        return Color(uiColor: UIColor { t in
            UIColor(hex: downHex(dark: t.userInterfaceStyle == .dark,
                                 highContrast: highContrast || HighContrast.isOn(t), inverted: inverted, accent: accent))
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

/// Eine Kursfarbe in vier Fassungen (RGB): hell, dunkel, je normal und hoher Kontrast — wie
/// Android `PriceTone`. Alle AA (≥ 4.5:1) bzw. AAA (≥ 7:1) auf allen Flächen; die Ersatzfassungen
/// der «Ruhig»-Schemata ΔE2000 ≥ 24 zu den Akzenten, für die sie einspringen (Ausnahme: eigene
/// Schwelle `minAccentLight` / `minAccentDark`, z. B. Karmin neben Orange).
struct PriceTone: Equatable, Sendable {
    let light: UInt32
    let dark: UInt32
    let lightHc: UInt32
    let darkHc: UInt32
    /// Nötiger Abstand (ΔE2000) zur Akzentfarbe, hell bzw. dunkel.
    let minAccentLight: Double
    let minAccentDark: Double

    init(_ light: UInt32, _ dark: UInt32, _ lightHc: UInt32, _ darkHc: UInt32,
         minAccentLight: Double = PriceColorScheme.minAccentDeltaE,
         minAccentDark: Double = PriceColorScheme.minAccentDeltaE) {
        self.light = light
        self.dark = dark
        self.lightHc = lightHc
        self.darkHc = darkHc
        self.minAccentLight = minAccentLight
        self.minAccentDark = minAccentDark
    }

    func hex(dark: Bool, highContrast: Bool) -> UInt32 {
        highContrast ? (dark ? darkHc : lightHc) : (dark ? self.dark : light)
    }

    /// «Ruhig» steigend: knappste #396E54 4.59 auf #E2E2E2, #61AE87 4.62 auf #353535.
    static let calmGreen = PriceTone(0x396E54, 0x61AE87, 0x294F3C, 0xA1CFB8)
    /// «Ruhig» fallend: hell dunkler, dunkel heller als steigend (Leuchtdichte 1.78:1 / 1.505:1,
    /// Deuteranopie simuliert 1.54 / 1.66). Dunkel Lachs-Rot #FFB097 (HSL 14°, C* 35.6; bis 18. Oktober
    /// 2026 das rosigere #FFAFA8; ΔE ≥ 39 zu Blau, Grün, Marrs Green).
    static let calmRed = PriceTone(0x771D23, 0xFFB097, 0x5A0813, 0xFFD1CF)
    /// «Ruhig» fallend neben Orange (Standard-Akzent): Karmin, liest sich als Rot. Ein Rot neben
    /// Orange erreicht ΔE 24 nicht, daher eigene Schwelle 19 hell / 20 dunkel: ΔE 19.3 / 20.3 zu
    /// #A64827 / #ED835E; dunkel #FFAFBB (HSL 351°, C* 31.4), so rot wie erlaubt — wie Android `CALM_CRIMSON`.
    static let calmCrimson = PriceTone(0x77212F, 0xFFAFBB, 0x51000D, 0xFFE3E7,
                                       minAccentLight: 19, minAccentDark: 20)
    /// «Ruhig» fallend neben Rot: Pflaume (röter geht dunkel nur fast weiss, C* ≤ 17).
    static let calmPlum = PriceTone(0x672B61, 0xE7B4E0, 0x4A1446, 0xFFD6F9)
    /// «Ruhig» steigend neben Grün/Marrs Green: gedämpftes Blau.
    static let calmBlue = PriceTone(0x506299, 0x8D9CD3, 0x364673, 0xB9C3F2)
    /// «Ruhig Farbsehschwäche» steigend neben Grün/Marrs Green: Blau mit leichtem Violett.
    static let vividBlue = PriceTone(0x2462BE, 0x819BEA, 0x03448D, 0xB4C3FE)
    /// «Ruhig Farbsehschwäche»: Petrol statt Blau bei blauer Akzentfarbe (knappster Kontrast #0D6F73 4.58 auf #E5E2DB).
    static let petrol = PriceTone(0x0D6F73, 0x26C6CC, 0x095053, 0x80E6EA)
    /// «Ruhig Farbsehschwäche» fallend neben Orange/Rot: Senfgelb (Blau-Gelb-Achse).
    static let mustard = PriceTone(0x786200, 0xC9A718, 0x564500, 0xECC959)
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
    /// Gelesen aus jedem Thread (Widgets, Hintergrund-Aufgabe, Ansichten), geschrieben von AppData:
    /// darum hinter einer Sperre statt `nonisolated(unsafe)` (gleichzeitiges Lesen und Schreiben wäre ein
    /// Datenwettlauf).
    private static let state = LockedBox(State())

    private struct State {
        var scheme: PriceColorScheme = .default
        var inverted = false
        var accent: AccentColor = .default
    }

    static var scheme: PriceColorScheme {
        get { state.read { $0.scheme } }
        set { state.write { $0.scheme = newValue } }
    }
    /// «Farben tauschen» (setzt AppData).
    static var inverted: Bool {
        get { state.read { $0.inverted } }
        set { state.write { $0.inverted = newValue } }
    }
    /// Akzentfarbe der App (setzt AppData): Kursfarben, die ihr zu ähnlich sind, nehmen ihre Ersatzfassung.
    static var accent: AccentColor {
        get { state.read { $0.accent } }
        set { state.write { $0.accent = newValue } }
    }

    /// Richtung steigend/fallend (mit Tausch); hoher Kontrast über `HighContrast`.
    static var up: Color { scheme.up(highContrast: false, inverted: inverted) }
    static var down: Color { scheme.down(highContrast: false, inverted: inverted) }

    /// «Alles in Ordnung» (z. B. aktuelle Kurse, schon in der Merkliste) — keine
    /// Kursrichtung, deshalb nie getauscht.
    static var ok: Color { scheme.up }

    static func forChange(_ change: Double?) -> Color { scheme.forChange(change, inverted: inverted) }
}

/// Kleiner Wert hinter einer `NSLock` (thread-sicher) für globale Einstellungen, die aus mehreren Threads
/// gelesen werden.
final class LockedBox<Value>: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func read<R>(_ body: (Value) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(value)
    }

    func write(_ body: (inout Value) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        body(&value)
    }

    /// Ganzer Wert: Lesen und Ersetzen je unter der Sperre (für Lesen-und-Ändern `write` nehmen).
    var current: Value {
        get { read { $0 } }
        set { write { $0 = newValue } }
    }
}
