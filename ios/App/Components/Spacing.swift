import CoreGraphics

/// Abstände im 4-pt-Raster (wie `Spacing` in Android): Innenabstände, Lücken zwischen
/// Elementen und Ränder nehmen eine dieser Stufen. Kleinere Feinabstimmungen (1–3 pt)
/// und Masse von Charts und Widgets bleiben eigene Werte.
enum Spacing {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
}
