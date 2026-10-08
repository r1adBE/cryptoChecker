import SwiftUI

/// Eine Schriftskala für die App — wie die Stufen in `Typography.kt` (Android). Alle Stufen
/// folgen Dynamic Type (Textstile des Systems bzw. `ScaledFont`):
///
/// - Display: grosse Werte (Portfolio-Summe, Kurs im Blatt, Marktwerte) — `View.displayFont(_:design:)`
/// - Headline: Titel von Blättern und grossen Karten — `AppFont.headline`
/// - Title: Titel von Karten und Abschnitten — `AppFont.title` (`.headline` des Systems)
/// - Body: Fliesstext — `AppFont.body` (`.subheadline` als Nebenstufe)
/// - Label: Nebentexte, Metadaten, Pillen — `AppFont.label` (`.caption`/`.caption2` als Nebenstufen)
/// - Numeric: `.monospacedDigit()` bzw. `AppFont.amount(_:weight:)` (SF Rounded mit gleich breiten
///   Ziffern, wie `amountNumbers()` in Android); alle Kurse, Prozente und Portfolio-Werte tragen eines davon.
enum AppFont {
    static let headline = Font.title2.weight(.semibold)
    static let title = Font.headline
    static let body = Font.body
    static let label = Font.footnote

    /// Betrag in einer Textstufe: SF Rounded, gleich breite Ziffern.
    static func amount(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        .system(style, design: .rounded).weight(weight).monospacedDigit()
    }

    /// Display-Stufen (Grösse bei «Large», wächst mit Dynamic Type).
    enum Display {
        /// Portfolio-Summe, Kurs im Blatt, Fear & Greed, Dominanz (Android `display`, 36 sp).
        case regular
        /// Werte in Karten (Positionswert, Marktphase-Index; Android `displayCompact`, 28 sp).
        case compact

        var size: CGFloat {
            switch self {
            case .regular: 34
            case .compact: 28
            }
        }

        var relativeTo: Font.TextStyle {
            switch self {
            case .regular: .largeTitle
            case .compact: .title
            }
        }
    }
}

extension View {
    /// Display-Stufe: halbfett, gleich breite Ziffern, mit Dynamic Type (siehe `ScaledFont`).
    func displayFont(_ display: AppFont.Display = .regular, design: Font.Design = .rounded) -> some View {
        scaledFont(size: display.size, weight: .semibold, design: design, relativeTo: display.relativeTo,
                   monospacedDigit: true)
    }
}
