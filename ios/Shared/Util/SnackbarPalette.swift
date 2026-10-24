import SwiftUI
import UIKit

/// Art einer kurzen Meldung unten (Banner, Toast, Snackbar): bestimmt Rahmen und Symbol, nie den Text. `info` ist der
/// Standard für alles, was weder Erfolg noch Fehler meldet. Wie Android `SnackbarKind`.
enum SnackbarKind: Sendable {
    case success, error, info

    /// Nach «Mehrere Paare»: etwas angelegt = Erfolg, nichts angelegt weil die Merkliste voll ist = Fehler, sonst Info
    /// (alle schon da). Wie Android `bulkAddedKind`.
    static func bulkAdded(added: Int, refused: Int) -> SnackbarKind {
        if added > 0 { return .success }
        return refused > 0 ? .error : .info
    }
}

/// Farbe von Rahmen (1.5 pt) und Symbol einer Meldung je `SnackbarKind` — Werte geteilt mit Android
/// (`snackbar_colors.json`, Android `SnackbarPalette.kt`).
///
/// - `success`: Steigend-Farbe des Kursfarben-Schemas, aber nie getauscht (`PriceColors.ok`) — Erfolg ist keine
///   Kursrichtung, bei «Farben tauschen» (Rot = steigend) bliebe er sonst rot.
/// - `error`: Fehlerfarbe der App (`ColorTokens.error`); bei einem Schema für Farbsehschwäche (`colorVision`) dessen
///   Fallend-Farbe (Orange statt Rot, ebenfalls nie getauscht).
/// - `info`: nil — kein farbiger Rahmen, kein Symbol.
///
/// Alle Töne erreichen als Grafik ≥ 3:1 auf `AppColors.containerHighest` (`SnackbarColorsTests`). Die Bedeutung trägt
/// zusätzlich das Symbol (Häkchen bzw. Ausrufezeichen) und immer der Meldungstext.
enum SnackbarPalette {
    /// Schemata für Farbsehschwäche: Erfolg/Fehler nehmen deren Blau/Orange-Töne.
    static func colorVision(_ scheme: PriceColorScheme) -> Bool {
        scheme == .BLUE_ORANGE || scheme == .CALM_COLOR_VISION
    }

    /// Ton (RGB) für `kind`; nil bei `info`.
    static func toneHex(_ kind: SnackbarKind, scheme: PriceColorScheme, dark: Bool, highContrast: Bool,
                        accent: AccentColor?) -> UInt32? {
        switch kind {
        case .success:
            return scheme.upHex(dark: dark, highContrast: highContrast, inverted: false, accent: accent)
        case .error:
            if colorVision(scheme) {
                return scheme.downHex(dark: dark, highContrast: highContrast, inverted: false, accent: accent)
            }
            return dark ? ColorTokens.error.dark : ColorTokens.error.light
        case .info:
            return nil
        }
    }

    /// Ton für `kind` in der Ansicht (Hell/Dunkel nach der Ansicht, hoher Kontrast auch über `HighContrast.isOn`,
    /// Akzentfarbe aus `PriceColors.accent`); nil bei `info`.
    static func tone(_ kind: SnackbarKind, scheme: PriceColorScheme, highContrast: Bool) -> Color? {
        guard kind != .info else { return nil }
        let accent: AccentColor = PriceColors.accent
        return Color(uiColor: UIColor { t in
            UIColor(hex: toneHex(kind, scheme: scheme, dark: t.userInterfaceStyle == .dark,
                                 highContrast: highContrast || HighContrast.isOn(t), accent: accent) ?? 0)
        })
    }
}
