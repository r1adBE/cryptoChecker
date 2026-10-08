import SwiftUI

/// Wertverlauf (24 h): Linie 1.75 pt in der Kursfarbe, Fläche als Verlauf von 25 % an der Linie
/// bis 0 unten, gestrichelte schwache Linie beim Ausgangswert, Punkt am letzten Wert; keine Achsen —
/// wie `WidgetChartRenderer.drawPortfolioArea` (Android).
struct PortfolioValueChart: View {
    let points: [PortfolioWidgetPoint]
    let color: Color
    let baseline: Color
    /// Feste Zeitachse (Tages-Basis); nil = erster bis letzter Punkt.
    var axis: (from: Int64, to: Int64)? = nil
    var lineWidth: CGFloat = 1.75
    var dot: CGFloat = 2.5

    var body: some View {
        GeometryReader { geo in
            let inset = dot + lineWidth / 2
            let xy = Self.positions(points, size: geo.size, inset: inset, axis: axis)
            if xy.count >= 2, let first = xy.first, let last = xy.last {
                let top = xy.map(\.y).min() ?? 0
                let baseY = Self.valueY(points, value: points[0].value, height: geo.size.height, inset: inset)
                ZStack {
                    Path { path in
                        path.move(to: CGPoint(x: first.x, y: geo.size.height))
                        for p in xy { path.addLine(to: p) }
                        path.addLine(to: CGPoint(x: last.x, y: geo.size.height))
                        path.closeSubpath()
                    }
                    .fill(LinearGradient(colors: [color.opacity(0.25), color.opacity(0)],
                                         startPoint: UnitPoint(x: 0.5, y: geo.size.height > 0 ? top / geo.size.height : 0),
                                         endPoint: .bottom))
                    Path { path in
                        path.move(to: CGPoint(x: first.x, y: baseY))
                        path.addLine(to: CGPoint(x: last.x, y: baseY))
                    }
                    .stroke(baseline.opacity(0.45), style: StrokeStyle(lineWidth: 0.75, dash: [3, 3]))
                    Path { path in
                        path.move(to: first)
                        for p in xy.dropFirst() { path.addLine(to: p) }
                    }
                    .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                    Circle()
                        .fill(color)
                        .frame(width: dot * 2, height: dot * 2)
                        .position(last)
                }
            }
        }
        .accessibilityHidden(true)
    }

    /// x nach der Zeit über die Breite (Rand `inset` links und rechts), y zwischen `inset` und
    /// Höhe − `inset` (höchster Wert oben); ohne Spanne mittig — wie `PortfolioWidgetMath.linePoints`.
    /// `axis`: feste Zeitachse (Tages-Basis) — die Linie füllt nur den bisherigen Teil des Tages.
    static func positions(_ points: [PortfolioWidgetPoint], size: CGSize, inset: CGFloat,
                          axis: (from: Int64, to: Int64)? = nil) -> [CGPoint] {
        guard points.count >= 2, let first = points.first, let last = points.last else { return [] }
        let values = points.map(\.value)
        guard let low = values.min(), let high = values.max() else { return [] }
        let fixed = axis.flatMap { $0.to > $0.from ? $0 : nil }
        let t0 = fixed?.from ?? first.at
        let span = Double((fixed?.to ?? last.at) - t0)
        let width = Swift.max(0, size.width - 2 * inset)
        let top = inset
        let bottom = Swift.max(inset, size.height - inset)
        var out: [CGPoint] = []
        for (i, p) in points.enumerated() {
            let fx = Swift.min(1, Swift.max(0, span > 0 ? Double(p.at - t0) / span : Double(i) / Double(points.count - 1)))
            let x = inset + CGFloat(fx) * width
            let y: CGFloat
            if high > low {
                y = bottom - CGFloat((p.value - low) / (high - low)) * (bottom - top)
            } else {
                y = (top + bottom) / 2
            }
            out.append(CGPoint(x: x, y: y))
        }
        return out
    }

    /// y-Lage eines Werts in derselben Skala, auf die Fläche geklemmt — wie `PortfolioWidgetMath.valueY`.
    static func valueY(_ points: [PortfolioWidgetPoint], value: Double, height: CGFloat, inset: CGFloat) -> CGFloat {
        let values = points.map(\.value)
        let top = inset
        let bottom = Swift.max(inset, height - inset)
        guard let low = values.min(), let high = values.max(), high > low else { return (top + bottom) / 2 }
        let y = bottom - CGFloat((value - low) / (high - low)) * (bottom - top)
        return Swift.min(bottom, Swift.max(top, y))
    }
}
