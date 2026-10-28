import SwiftUI
import UIKit

// Kursfarben (Schema, Fassung, Umgebung, aktuelle Farben) — wie `PriceColorScheme.kt` (Android).

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
/// Feste Börsenfarben («Frisch», «Traditionell», «Farbsehschwäche»:
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

    /// Standard: «Ruhig» (gedämpftes Grün/Rot); «Frisch» (Binance-Grün/-Rot) bleibt wählbar.
    static let `default`: PriceColorScheme = .CALM

    var id: String { rawValue }

    /// Mindestabstand (ΔE2000) jeder Kursfarbe zur Akzentfarbe, hell und dunkel je für sich.
    static let minAccentDeltaE: Double = 24

    /// Steigend: erste Fassung = Standard. Nur «Ruhig» und «Ruhig Farbsehschwäche» haben Ersatz für Akzente, die ihr zu ähnlich sind;
    /// die übrigen Schemata bleiben feste Börsenfarben.
    private var rises: [PriceTone] {
        switch self {
        case .CALM: [.calmGreen, .calmBlue]
        // Binance-Grün #0ECB81 (dunkel genau, sonst gleicher Farbton mit nötigem Kontrast)
        case .GREEN_RED: [PriceTone(0x127248, 0x0ECB81, 0x025430, 0x30DE92)]
        // TradingView-Grün #089981
        case .TRADITIONAL: [PriceTone(0x0A7262, 0x3EAE97, 0x005246, 0x5AD8BE)]
        // Blau nahe #1565C0 / #3D9BFF
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
