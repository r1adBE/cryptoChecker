import SwiftUI

// Art-Symbol eines Paars (Token = Raute, TradFi = Bank, Alpha = «α») und die gezeichneten Formen dazu, die
// auch die Gruppen-Chips nutzen.
// Eigene Datei, weil Merkliste, Explorer und Einstellungen das Symbol teilen (wie
// `ui/components/AssetKindIcon.kt`).

/// Symbol der Art eines Paars (`AssetKind`) in kleinen Zeilen (vor der Glocke): Token =
/// Raute, TradFi = Bank, Alpha = «α» — dieselben wie die Chips (`GroupChipIcon.kind`), so
/// gross wie die Glocke daneben (`size`, wächst mit `.caption2`). Dekoration; VoiceOver liest die Art
/// im Zeilensatz — wie `AssetKindIcon` (Android).
struct AssetKindIconView: View {
    let kind: AssetKind
    @ScaledMetric(relativeTo: .caption2) private var size: CGFloat = 11

    /// SF Symbol für TradFi (wie `ic_bank.xml`).
    static let tradFiSymbol = "building.columns.fill"
    /// Zeichen für Alpha-Tokens (griechisches Alpha).
    static let alphaGlyph = "α"

    var body: some View {
        Group {
            switch kind {
            case .TOKEN:
                TokenIconShape()
                    .frame(width: size, height: size)
            case .TRADFI:
                Image(systemName: Self.tradFiSymbol)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size * 0.85, height: size * 0.85)
                    .frame(width: size, height: size)
            case .ALPHA:
                AlphaIconShape()
                    .frame(width: size, height: size)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Raster 24 × 24 wie die Android-Vektoren, mittig auf die Fläche skaliert.
private struct IconGrid {
    let scale: CGFloat
    let origin: CGPoint

    init(_ rect: CGRect) {
        scale = min(rect.width, rect.height) / 24
        origin = CGPoint(x: rect.midX - 12 * scale, y: rect.midY - 12 * scale)
    }

    func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
        CGPoint(x: origin.x + x * scale, y: origin.y + y * scale)
    }

    func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
        CGRect(origin: point(x, y), size: CGSize(width: w * scale, height: h * scale))
    }
}

/// «Alle»: Dreieck oben, Quadrat unten links, Kreis unten rechts — wie `ic_category.xml`.
struct CategoryIconShape: Shape {
    func path(in rect: CGRect) -> Path {
        let g = IconGrid(rect)
        var path = Path()
        path.move(to: g.point(12, 2.5))
        path.addLine(to: g.point(17.6, 11.2))
        path.addLine(to: g.point(6.4, 11.2))
        path.closeSubpath()
        path.addEllipse(in: g.rect(13.3, 13.3, 8.4, 8.4))
        let corner = CGSize(width: g.scale, height: g.scale)
        path.addRoundedRect(in: g.rect(2.8, 13.3, 8.2, 8.2), cornerSize: corner)
        return path
    }
}

/// «Token»: Raute als Umriss, Strichstärke 2,2 im 24er-Raster wie `ic_token.xml`.
struct TokenIconShape: Shape {
    func path(in rect: CGRect) -> Path {
        let g = IconGrid(rect)
        var path = Path()
        path.move(to: g.point(12, 3))
        path.addLine(to: g.point(20, 12))
        path.addLine(to: g.point(12, 21))
        path.addLine(to: g.point(4, 12))
        path.closeSubpath()
        let style = StrokeStyle(lineWidth: 2.2 * g.scale, lineCap: .round, lineJoin: .round)
        return path.strokedPath(style)
    }
}

/// Griechisches «α» als Umriss im 24er-Raster, Strichstärke 2,2 wie `TokenIconShape` — gleicher Pfad wie
/// Android `ic_alpha.xml`, damit Grösse und Höhe zu Raute und Bank passen (statt eines Schriftzeichens).
struct AlphaIconShape: Shape {
    func path(in rect: CGRect) -> Path {
        let g = IconGrid(rect)
        var path = Path()
        path.move(to: g.point(20.45, 3.88))
        path.addCurve(to: g.point(14.99, 17.52), control1: g.point(19.41, 11.03), control2: g.point(17.33, 15.32))
        path.addCurve(to: g.point(9.01, 20.12), control1: g.point(13.17, 19.21), control2: g.point(11.22, 20.12))
        path.addCurve(to: g.point(2.25, 12.32), control1: g.point(5.11, 20.12), control2: g.point(2.25, 16.74))
        path.addCurve(to: g.point(9.01, 4.52), control1: g.point(2.25, 7.9), control2: g.point(5.24, 4.52))
        path.addCurve(to: g.point(15.51, 11.28), control1: g.point(12.39, 4.52), control2: g.point(14.34, 7.38))
        path.addCurve(to: g.point(21.75, 20.12), control1: g.point(16.68, 15.32), control2: g.point(18.11, 20.12))
        let style = StrokeStyle(lineWidth: 2.2 * g.scale, lineCap: .round, lineJoin: .round)
        return path.strokedPath(style)
    }
}
