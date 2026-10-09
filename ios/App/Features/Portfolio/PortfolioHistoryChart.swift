import SwiftUI

/// Linie mit sanfter Fläche (wie das Mini-Chart der Merkliste); hoher Kontrast: Fläche kräftiger.
/// Kurz drücken, dann ziehen (wie das Chart im Aktionsblatt) zeigt Markierung, Punkt und
/// «7. Okt. 2026 · 12’345.67 CHF» des nächsten Tages; Loslassen blendet beides aus. Schnelles
/// Wischen bleibt dem Bildschirm (Scrollen). VoiceOver: Chart-Satz am Aufrufer.
struct PortfolioHistoryChart: View {
    let points: [PortfolioHistoryPoint]
    let unit: String
    let color: Color
    let highContrast: Bool
    /// «Beträge verbergen»: Etikett beim Ziehen ohne Betrag.
    var hidden = false

    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState private var pressX: CGFloat? = nil

    /// Rand für die Strichbreite — gleich wie `historyPoints`.
    private static let inset: CGFloat = 2

    var body: some View {
        let values = points.map(\.value)
        GeometryReader { proxy in
            let rect = CGRect(origin: .zero, size: proxy.size)
            let scrub = scrubIndex(width: proxy.size.width)
            ZStack(alignment: .bottom) {
                HistoryAreaShape(values: values)
                    .fill(LinearGradient(colors: [color.opacity(highContrast ? 0.20 : 0.12), color.opacity(0)],
                                         startPoint: .top, endPoint: .bottom))
                HistoryLineShape(values: values)
                    .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                Rectangle()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 0.5)
            }
            .overlay(alignment: .topLeading) {
                // Ziehen: senkrechte Markierung und Punkt auf dem Wert
                if let i = scrub {
                    let all = historyPoints(values, in: rect)
                    if all.indices.contains(i) {
                        let p = all[i]
                        Path { path in
                            path.move(to: CGPoint(x: p.x, y: 0))
                            path.addLine(to: CGPoint(x: p.x, y: rect.maxY))
                        }
                        .stroke(AppColors.onSurface.opacity(0.6), lineWidth: 1)
                        Circle()
                            .fill(AppColors.onSurface)
                            .frame(width: 7, height: 7)
                            .position(p)
                        ScrubLabelLayout(centerX: p.x) {
                            scrubLabel(points[i])
                        }
                    }
                }
            }
            .contentShape(Rectangle())
            // Kurz drücken, dann ziehen; schnelles Wischen bleibt dem Bildschirm (Scrollen)
            .gesture(
                LongPressGesture(minimumDuration: 0.2)
                    .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .local))
                    .updating($pressX) { value, state, _ in
                        if case .second(true, let drag?) = value { state = drag.location.x }
                    }
            )
            // Leichtes Ticken je neuem Tag (folgt den System-Einstellungen)
            .sensoryFeedback(.selection, trigger: scrub) { old, new in
                SheetChart.isNewCandle(previous: old, current: new)
            }
        }
        // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
        .environment(\.layoutDirection, .leftToRight)
    }

    /// Tag unter dem Finger; nil ohne Ziehen.
    private func scrubIndex(width: CGFloat) -> Int? {
        guard let x = pressX else { return nil }
        return PortfolioHistory.scrubIndex(x: Double(x), left: Double(Self.inset),
                                           width: Double(width - 2 * Self.inset), count: points.count)
    }

    /// «7. Okt. 2026 · 12’345.67 CHF».
    private func scrubLabel(_ point: PortfolioHistoryPoint) -> some View {
        // Datum wie an der Achse (Format der Sprache)
        let date = PortfolioFormat.date(LocalDay(epochDay: point.epochDay).date.millis)
        return Text(date + " · " + PortfolioInsights.mask(PriceFormat.valueWithCurrency(point.value, unit), hidden: hidden))
            .font(AppFont.amount(.caption, weight: .medium))
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .fixedSize()
    }
}

/// Punkte der Linie im Rechteck (2 pt Rand für die Strichbreite); flache Reihe in der Mitte.
private func historyPoints(_ values: [Double], in rect: CGRect) -> [CGPoint] {
    guard values.count >= 2, let lo = values.min(), let hi = values.max() else { return [] }
    let inset: CGFloat = 2
    let w = rect.width - 2 * inset
    let h = rect.height - 2 * inset
    guard w > 0, h > 0 else { return [] }
    let span = hi - lo
    let last = CGFloat(values.count - 1)
    return values.enumerated().map { index, value in
        let x = rect.minX + inset + w * CGFloat(index) / last
        let y: CGFloat = span > 0
            ? rect.minY + inset + h - CGFloat((value - lo) / span) * h
            : rect.minY + inset + h / 2
        return CGPoint(x: x, y: y)
    }
}

private struct HistoryLineShape: Shape {
    let values: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let points = historyPoints(values, in: rect)
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        return path
    }
}

private struct HistoryAreaShape: Shape {
    let values: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let points = historyPoints(values, in: rect)
        guard let first = points.first, let last = points.last else { return path }
        path.move(to: first)
        for point in points.dropFirst() { path.addLine(to: point) }
        path.addLine(to: CGPoint(x: last.x, y: rect.maxY))
        path.addLine(to: CGPoint(x: first.x, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}
