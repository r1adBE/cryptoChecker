import os
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

    /// Grundfarbe (Seed), aus der das Schema abgeleitet ist. Ein Orange: #ED835E ist
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
        // #92CFCA wie Android zur Laufzeit
        case .MARRS_GREEN: 0x92CFCA
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
    /// Trennlinien: hell #ADA9A0 ≈ 2.2:1 auf #FBF9F6 (vorher #C9C6BF 1.6:1) — wie Android.
    static let outlineVariant = Pair(light: 0xADA9A0, dark: 0x474747)
    /// Fehler in Hell und Dunkel warmes Orange-Rot statt Material-Rot/-Rosa — wie Android `ErrorColors.kt`.
    /// Hell #B13419: 5.92:1 auf #FBF9F6, 4.81:1 auf #E5E2DB, Weiss darauf 6.22:1, auf der eigenen Fehlerfläche
    /// (`error` · 0.12 über #F1EEE9) 4.5:1; dunkel #FF5833: 5.9:1 auf #131313.
    /// Hoher Kontrast lässt sie unverändert (wie Android). Fehlerflächen: `error` mit geringer Deckkraft (≤ 0.12).
    static let error = Pair(light: 0xB13419, dark: 0xFF5833)
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
    /// Löschen und Zurücksetzen (Wisch-Aktionen, «App zurücksetzen»): der Fehlerton statt Systemrot, wie Android.
    static let destructive = error
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

/// Kleiner Wert hinter einer Sperre (`OSAllocatedUnfairLock`, thread-sicher) für globale Einstellungen, die aus
/// mehreren Threads gelesen werden. Vom Compiler geprüft `Sendable`: der Wert ist `Sendable`, die Zugriffe sind
/// `@Sendable` und fassen nur Sendable-Werte an; nichts wartet unter der Sperre (kein `await`).
final class LockedBox<Value: Sendable>: Sendable {
    private let state: OSAllocatedUnfairLock<Value>

    init(_ value: Value) {
        state = OSAllocatedUnfairLock(initialState: value)
    }

    func read<R: Sendable>(_ body: @Sendable (Value) -> R) -> R {
        state.withLock { body($0) }
    }

    func write(_ body: @Sendable (inout Value) -> Void) {
        state.withLock { body(&$0) }
    }

    /// Lesen und Ändern in einem Schritt unter der Sperre, mit Ergebnis (etwa «abholen und zurücksetzen»).
    func modify<R: Sendable>(_ body: @Sendable (inout Value) -> R) -> R {
        state.withLock { body(&$0) }
    }

    /// Ganzer Wert: Lesen und Ersetzen je unter der Sperre (für Lesen-und-Ändern `write`/`modify` nehmen).
    var current: Value {
        get { read { $0 } }
        set { write { $0 = newValue } }
    }
}
