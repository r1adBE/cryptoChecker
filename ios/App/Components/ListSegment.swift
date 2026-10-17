import SwiftUI

/// Coin-Listen als eine Einheit (Merkliste, Start-Auswahl, Heute auffällig, Portfolio): die Zeilen
/// liegen ohne Fuge aneinander und wirken wie eine einzige Karte — aussen gerundet (oben an der
/// ersten, unten an der letzten Zeile; eine einzelne Zeile ringsum), innen eckig, dazwischen eine
/// feine Trennlinie über die ganze Breite (`segmentLines`). Wie `ListSegment` (Android).
enum ListSegment {
    /// Fuge zwischen zwei Zeilen: keine, die Trennlinie zeichnet die Zeile selbst.
    static let gap: CGFloat = 0
    /// Abstand zu anderen Elementen über bzw. unter der Liste.
    static let spacing: CGFloat = 6
    /// Logo in allen Coin-Listen gleich gross (wie «+» und Lupe: 34).
    static let logo: CGFloat = 34

    private static let outer: CGFloat = 16

    /// Form der Zeile `index` von `count` Zeilen.
    static func shape(_ index: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = index <= 0 ? outer : 0
        let bottom: CGFloat = index >= count - 1 ? outer : 0
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .continuous)
    }
}

extension View {
    /// Linien einer Listenzeile mit der Form `shape` aus `ListSegment.shape`: unten eine feine
    /// Trennlinie über die ganze Breite (nicht an der letzten Zeile) und — mit `outline` — der feine
    /// Rand nur aussen (Seiten, oben an der ersten, unten an der letzten Zeile), damit die Liste wie
    /// eine Karte mit Rand aussieht und nie doppelte Linien entstehen. Gehört zur Zeile selbst,
    /// wandert also beim Wischen und Ziehen mit. Wie `Modifier.segmentLines` (Android).
    func segmentLines(_ shape: UnevenRoundedRectangle, outline: Bool = true) -> some View {
        overlay(alignment: .bottom) { SegmentLines(shape: shape, outline: outline) }
    }
}

/// Zeichnet die Linien zu `segmentLines`; Platz in der Liste aus den Ecken der Form gelesen.
private struct SegmentLines: View {
    let shape: UnevenRoundedRectangle
    let outline: Bool

    private var first: Bool { shape.cornerRadii.topLeading > 0 }
    private var last: Bool { shape.cornerRadii.bottomLeading > 0 }

    var body: some View {
        ZStack(alignment: .bottom) {
            if outline {
                // Innen liegende Kanten über die Zeile hinaus verlegen: abgeschnitten bleiben die Seiten
                shape
                    .strokeBorder(AppColors.outlineVariant, lineWidth: 1)
                    .padding(.top, first ? 0 : -2)
                    .padding(.bottom, last ? 0 : -2)
            }
            if !last {
                // Zwischen den Seitenrändern, damit sich die Linien nicht überlagern
                Rectangle()
                    // Volle Token-Farbe wie der Rand (keine halbtransparenten Linien, Build 46)
                    .fill(AppColors.outlineVariant)
                    .frame(height: 0.5)
                    .padding(.horizontal, outline ? 1 : 0)
            }
        }
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Welche kleinen Zeichen in den Zeilen gerade in der Themenfarbe stehen: Stern (★) und Blitz (⚡)
/// sind sonst weiss (Textfarbe) und werden nur farbig, solange der passende Gruppen-Chip gewählt ist
/// (★ = Favoriten, ⚡ = «Hier passiert gerade etwas»). Ebenso das Art-Symbol vor der Glocke (Token =
/// Raute, TradFi = Bank, Alpha = «α»): in der Themenfarbe nur mit gewähltem Chip «Token»
/// (`tokens`), «TradFi» (`tradFi`) bzw. «Alpha» (`alpha`). Wie `LocalRowHighlight` (Android).
struct RowHighlight: Equatable {
    var favorites = false
    var activity = false
    var tokens = false
    var tradFi = false
    var alpha = false

    /// Art-Symbol `kind` in der Themenfarbe?
    func highlights(_ kind: AssetKind) -> Bool {
        switch kind {
        case .TOKEN: return tokens
        case .TRADFI: return tradFi
        case .ALPHA: return alpha
        }
    }
}

private struct RowHighlightKey: EnvironmentKey {
    static let defaultValue = RowHighlight()
}

extension EnvironmentValues {
    var rowHighlight: RowHighlight {
        get { self[RowHighlightKey.self] }
        set { self[RowHighlightKey.self] = newValue }
    }
}

/// Hauptschalter «Kursmitteilungen»: Die Zeile zeigt die schlichte Glocke nur, wenn er und der Schalter des Paars
/// an sind, sonst die durchgestrichene. Wie Android `LocalPriceNotificationsOn`.
private struct PriceNotificationsOnKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    var priceNotificationsOn: Bool {
        get { self[PriceNotificationsOnKey.self] }
        set { self[PriceNotificationsOnKey.self] = newValue }
    }
}
