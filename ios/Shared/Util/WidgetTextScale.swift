import SwiftUI

/// Schriftgrössen der Widget-Texte mit Dynamic Type.
/// - Nebentexte (Uhrzeit, Börse, Fusszeilen): mindestens 12 pt (wie Android 12 sp), mit `@ScaledMetric` zu
///   `.caption2`, höchstens `maxScale` × gross, damit das Widget nicht überläuft.
/// - Haupttexte (Coin-Name, Kurs): wachsen mit `@ScaledMetric` zu `.body` bis höchstens `maxPrimaryScale` ×
///   (`WidgetPrimaryFit`); passt die gewachsene Fassung nicht, gilt die Grundgrösse.
enum WidgetTextSize {
    static let minSecondary: CGFloat = 12
    static let maxScale: CGFloat = 1.25
    /// Obergrenze der Haupttexte (etwa bei «xxxLarge» erreicht).
    static let maxPrimaryScale: CGFloat = 1.3

    /// Grösse für `base` beim Dynamic-Type-Faktor `scale`: nie unter 12 pt, nie kleiner als `base`,
    /// höchstens `maxScale` × `base`.
    static func secondary(_ base: CGFloat, scale: CGFloat = 1) -> CGFloat {
        max(base, minSecondary) * min(max(scale, 1), maxScale)
    }

    /// Faktor der Haupttexte beim Dynamic-Type-Faktor `scale`: nie kleiner als 1, höchstens `maxPrimaryScale`.
    static func primaryScale(_ scale: CGFloat) -> CGFloat {
        guard scale.isFinite else { return 1 }
        return min(max(scale, 1), maxPrimaryScale)
    }

    /// Grösse eines Haupttextes `base` beim Dynamic-Type-Faktor `scale`.
    static func primary(_ base: CGFloat, scale: CGFloat = 1) -> CGFloat {
        base * primaryScale(scale)
    }

    /// `minimumScaleFactor` eines um `primaryScale` gewachsenen Textes, der höchstens bis `minimum` × Grundgrösse
    /// schrumpfen darf: Ein gewachsener Text wird bei Platzmangel nie kleiner als derselbe Text ohne Wachstum.
    static func minimumFactor(_ minimum: CGFloat, primaryScale: CGFloat) -> CGFloat {
        guard primaryScale > 0, primaryScale.isFinite else { return minimum }
        return min(1, minimum / primaryScale)
    }
}

/// Haupttexte eines Widgets mit Dynamic Type: `content` bekommt den Faktor (1…`maxPrimaryScale`), mit dem es
/// Coin-Name und Kurs vergrössert. `ViewThatFits` nimmt die gewachsene Fassung, wenn sie in `axes` ganz Platz hat,
/// sonst die Grundgrösse (Faktor 1) — so läuft das Widget nie über.
struct WidgetPrimaryFit<Content: View>: View {
    let axes: Axis.Set
    @ViewBuilder let content: (CGFloat) -> Content
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    init(in axes: Axis.Set = .vertical, @ViewBuilder content: @escaping (CGFloat) -> Content) {
        self.axes = axes
        self.content = content
    }

    var body: some View {
        let grown = WidgetTextSize.primaryScale(scale)
        if grown > 1 {
            ViewThatFits(in: axes) {
                content(grown)
                content(1)
            }
        } else {
            content(1)
        }
    }
}
