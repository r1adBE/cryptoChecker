import SwiftUI

/// Ebene einer Karte: `.top` für Karten direkt auf dem Bildschirm (20), `.nested` für Karten
/// innerhalb einer Karte (16). Wie Android `CardLevel`.
enum CardLevel {
    case top
    case nested

    var radius: CGFloat {
        switch self {
        case .top: Radius.large
        case .nested: Radius.card
        }
    }
}

/// Innenabstand einer Karte: `.regular` rundum `Spacing.lg`; `.list` für Zeilenlisten, deren Zeilen
/// den senkrechten Abstand selbst mitbringen; `.none`, wenn der Inhalt randlos antippbar ist oder
/// eine eigene Fläche zeichnet (Verlauf) und den Abstand darum selbst setzt. Wie Android `CardPadding`.
enum CardPadding {
    case regular
    case list
    case none

    var insets: EdgeInsets {
        switch self {
        case .regular: EdgeInsets(top: Spacing.lg, leading: Spacing.lg, bottom: Spacing.lg, trailing: Spacing.lg)
        case .list: EdgeInsets(top: Spacing.sm, leading: Spacing.lg, bottom: Spacing.sm, trailing: Spacing.lg)
        case .none: EdgeInsets()
        }
    }
}

/// Die eine Karte der App: Fläche `AppColors.container`, kein Rand, Ecken nach `level`. Alle Bereiche
/// (Markt, Portfolio, Einstellungen) nehmen diese Karte, damit Radius, Fläche und Abstand nicht von
/// Bildschirm zu Bildschirm auseinanderlaufen; hervorgehoben wird nur über den Inhalt (z. B. den
/// Verlauf der Portfolio-Summe), nie über einen Rand. Wie Android `AppCard`.
struct AppCardModifier: ViewModifier {
    let level: CardLevel
    let padding: CardPadding

    func body(content: Content) -> some View {
        content
            .padding(padding.insets)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: level.radius, style: .continuous))
    }
}

extension View {
    /// Siehe `AppCardModifier`.
    func appCard(_ level: CardLevel = .top, padding: CardPadding = .regular) -> some View {
        modifier(AppCardModifier(level: level, padding: padding))
    }
}
