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

// MARK: Vergleich in Prozent

/// Vergleich in Prozent seit dem Ausgangspunkt — wie `HistoryCompareChart` (Android): beide Kurven
/// beginnen bei 0 % (gestrichelte Linie), die gewählte Währung kräftig in `color`, USDT dünn und
/// grau; rechts Hoch und Tief in Prozent. Kurz drücken, dann ziehen wie bei `PortfolioHistoryChart`
/// — der Tag geht an `scrub` (die Karte zeigt darunter den Währungseffekt dieses Tags), das Etikett
/// zeigt «7. Okt. 2026 · CHF +7.00% · USDT +12.10%». Prozente bleiben auch bei «Beträge verbergen».
struct PortfolioCompareChart: View {
    let compare: PortfolioCompareSeries
    let currency: String
    let color: Color
    @Binding var scrub: Int?

    /// x des Fingers beim Ziehen (nach kurzem Drücken); nil = kein Ziehen.
    @GestureState private var pressX: CGFloat? = nil

    /// Rand für die Strichbreite — gleich beim Zeichnen und beim Ziehen.
    static let inset: CGFloat = 3

    var body: some View {
        let bounds = PortfolioCompare.bounds(compare)
        HStack(spacing: 6) {
            plot(bounds)
            scale(bounds)
        }
        // Zeitachse immer von links nach rechts (auch bei Rechts-nach-links-Sprachen)
        .environment(\.layoutDirection, .leftToRight)
    }

    private func plot(_ bounds: (lo: Double, hi: Double)) -> some View {
        GeometryReader { proxy in
            let index = scrubIndex(width: proxy.size.width)
            lines(bounds)
                .overlay(alignment: .topLeading) {
                    if let index {
                        marker(index, bounds: bounds, size: proxy.size)
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
                .sensoryFeedback(.selection, trigger: index) { old, new in
                    SheetChart.isNewCandle(previous: old, current: new)
                }
                .onChange(of: index) { _, new in scrub = new }
        }
    }

    /// 0-%-Linie gestrichelt, darauf USDT dünn und grau, darüber die Währung kräftig.
    private func lines(_ bounds: (lo: Double, hi: Double)) -> some View {
        ZStack {
            CompareZeroLine(bounds: bounds)
                .stroke(AppColors.outline.opacity(0.7), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            CompareLineShape(values: compare.usdt, bounds: bounds)
                .stroke(AppColors.onSurfaceVariant, style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
            CompareLineShape(values: compare.currency, bounds: bounds)
                .stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
        }
    }

    /// Ziehen: senkrechte Markierung, Punkte auf beiden Kurven und Etikett.
    @ViewBuilder
    private func marker(_ i: Int, bounds: (lo: Double, hi: Double), size: CGSize) -> some View {
        if compare.currency.indices.contains(i), compare.usdt.indices.contains(i) {
            let rect = CGRect(origin: .zero, size: size)
            let x = compareX(i, count: compare.epochDays.count, in: rect)
            Path { path in
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: rect.maxY))
            }
            .stroke(AppColors.onSurface.opacity(0.6), lineWidth: 1)
            Circle()
                .fill(AppColors.onSurfaceVariant)
                .frame(width: 5, height: 5)
                .position(x: x, y: compareY(compare.usdt[i], bounds: bounds, in: rect))
            Circle()
                .fill(AppColors.onSurface)
                .frame(width: 7, height: 7)
                .position(x: x, y: compareY(compare.currency[i], bounds: bounds, in: rect))
            ScrubLabelLayout(centerX: x) {
                scrubLabel(i)
            }
        }
    }

    /// Skala rechts: Hoch oben, Tief unten (der Chart-Satz nennt die Werte für VoiceOver).
    private func scale(_ bounds: (lo: Double, hi: Double)) -> some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(PortfolioFormat.signedPercent(bounds.hi))
            Spacer(minLength: 4)
            Text(PortfolioFormat.signedPercent(bounds.lo))
        }
        .font(.caption2.monospacedDigit())
        .foregroundStyle(AppColors.onSurfaceVariant)
        .lineLimit(1)
        .fixedSize(horizontal: true, vertical: false)
    }

    /// Tag unter dem Finger; nil ohne Ziehen.
    private func scrubIndex(width: CGFloat) -> Int? {
        guard let x = pressX else { return nil }
        return PortfolioHistory.scrubIndex(x: Double(x), left: Double(Self.inset),
                                           width: Double(width - 2 * Self.inset), count: compare.epochDays.count)
    }

    /// «7. Okt. 2026 · CHF +7.00% · USDT +12.10%».
    private func scrubLabel(_ i: Int) -> some View {
        let date = PortfolioFormat.date(LocalDay(epochDay: compare.epochDays[i]).date.millis)
        return Text(date + " · " + Self.values(compare, currency: currency, index: i))
            .font(AppFont.amount(.caption, weight: .medium))
            .foregroundStyle(AppColors.onSurface)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(AppColors.containerHighest, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .fixedSize()
    }

    /// «CHF +7.00% · USDT +12.10%» für den Tag `index`.
    static func values(_ compare: PortfolioCompareSeries, currency: String, index: Int) -> String {
        let c = PortfolioFormat.signedPercent(compare.currency[index])
        let u = PortfolioFormat.signedPercent(compare.usdt[index])
        return "\(currency) \(c) · \(PortfolioFormat.usdt) \(u)"
    }
}

/// x des Tags `index` von `count` im Rechteck (Rand für die Strichbreite).
private func compareX(_ index: Int, count: Int, in rect: CGRect) -> CGFloat {
    let inset = PortfolioCompareChart.inset
    let w = rect.width - 2 * inset
    guard count > 1 else { return rect.midX }
    return rect.minX + inset + w * CGFloat(index) / CGFloat(count - 1)
}

/// y des Prozentwerts `value` auf der gemeinsamen Skala; flache Skala in der Mitte.
private func compareY(_ value: Double, bounds: (lo: Double, hi: Double), in rect: CGRect) -> CGFloat {
    let inset = PortfolioCompareChart.inset
    let h = rect.height - 2 * inset
    let span = bounds.hi - bounds.lo
    guard span > 0 else { return rect.minY + inset + h / 2 }
    return rect.minY + inset + h - CGFloat((value - bounds.lo) / span) * h
}

private struct CompareLineShape: Shape {
    let values: [Double]
    let bounds: (lo: Double, hi: Double)

    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard values.count >= 2, rect.width > 2 * PortfolioCompareChart.inset,
              rect.height > 2 * PortfolioCompareChart.inset else { return path }
        for (index, value) in values.enumerated() {
            let point = CGPoint(x: compareX(index, count: values.count, in: rect),
                                y: compareY(value, bounds: bounds, in: rect))
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        return path
    }
}

private struct CompareZeroLine: Shape {
    let bounds: (lo: Double, hi: Double)

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let y = compareY(0, bounds: bounds, in: rect)
        path.move(to: CGPoint(x: rect.minX, y: y))
        path.addLine(to: CGPoint(x: rect.maxX, y: y))
        return path
    }
}
