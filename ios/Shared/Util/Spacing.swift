import CoreGraphics

/// Abstände im 4-pt-Raster (wie `Spacing` in Android): Innenabstände, Lücken zwischen
/// Elementen und Ränder nehmen eine dieser Stufen. Kleinere Feinabstimmungen (1–3 pt)
/// und Masse von Charts und Widgets bleiben eigene Werte. In Shared/, damit auch die
/// Widget-Erweiterung (Live-Aktivität) dieselbe Skala nutzt.
enum Spacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}

/// Eckenradien — eine Formstufe wie Android `appShapes` (Shape.kt): Felder und Menüs 12, Karten auf dem
/// Bildschirm 20 (`large`, `CardLevel.top`), Karten und Zeilen in einer Karte bzw. Liste 16 (`card`,
/// `CardLevel.nested`), Blätter 28; dazu nur für Chips, Tooltips und Chart-Marken die kleine Stufe 8.
/// Ausnahmen: Farbmuster 16 × 16 (4) und in Canvas gezeichnete Kurs-Etiketten (3) — Glyph-Grösse.
enum Radius {
    static let chip: CGFloat = 8
    static let field: CGFloat = 12
    static let card: CGFloat = 16
    static let large: CGFloat = 20
    static let sheet: CGFloat = 28
}
