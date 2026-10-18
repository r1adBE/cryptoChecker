import SwiftUI

// Zeichnen des Kurs-Charts (Teil von `PriceChart.swift`): Kerzen oder Linie, Preisstufen, Etikett und Zeit-Linien
// in einen `GraphicsContext`; Geometrie in `WidgetChartGeometry`, Farben und Schriften über `PriceChartStyle`.

/// Zeichnet den Chart des Einzel-Widgets: Kerzen oder Linie, drei Preisstufen mit Beschriftung
/// rechts, Etikett mit dem aktuellen Kurs in der Akzentfarbe, feine senkrechte Zeit-Linien.
/// Hoher Kontrast: Beschriftung in voller Nebentextfarbe, Linien mit 35 % davon.
enum PriceChartRenderer {
    /// Unter dieser Höhe (pt) nur obere/untere Beschriftung und Etikett.
    static let compactHeight: CGFloat = 80
    /// Unter dieser Breite (pt) ebenso.
    static let compactWidth: CGFloat = 120

    /// Zeichnet in `ctx`; nil, wenn nichts zu zeichnen ist (weniger als zwei Kerzen, keine Fläche).
    /// `currentPrice`: aktueller Kurs für das Etikett; nil/ungültig = letzter Schluss.
    /// `levels`: eigene Preisstufen (Aktionsblatt: Mindesthöhe der Achse); nil = aus den Kerzen.
    /// `prepared`: Kerzen schon bereinigt (`WidgetChartGeometry.clean`, abseits des Main-Threads).
    @discardableResult
    static func render(_ ctx: inout GraphicsContext, size: CGSize, candles: [MarketCandle], type: PriceChartType,
                       range: PriceChartRange, style: PriceChartStyle, currentPrice: Double?,
                       px: CGFloat, timeZone: TimeZone = .current,
                       levels customLevels: WidgetChartGeometry.Levels? = nil,
                       prepared: Bool = false) -> PriceChartLayout? {
        let data = prepared ? candles : WidgetChartGeometry.clean(candles)
        guard data.count >= 2, size.width > 0, size.height > 0,
              let levels = customLevels ?? WidgetChartGeometry.levels(data, type: type),
              let first = data.first, let last = data.last else { return nil }

        // Farben: im hohen Kontrast Nebentextfarbe voll, sonst sehr zart
        let labelColor = style.secondary
        let gridColor = style.secondary.opacity(style.highContrast ? 0.35 : 0.14)
        let levelColor = style.secondary.opacity(style.highContrast ? 0.35 : 0.22)
        let hair = Swift.max(px, 0.5)
        let lineWidth = style.lineWidth

        // Texte messen
        let levelValues = [levels.low, levels.mid, levels.high]
        var labels: [GraphicsContext.ResolvedText] = []
        for value in levelValues {
            labels.append(ctx.resolve(Text(PriceFormat.price(value)).font(style.labelFont).foregroundStyle(labelColor)))
        }
        let tagPrice = currentPrice.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } ?? last.close
        let tagText = ctx.resolve(Text(PriceFormat.price(tagPrice)).font(style.tagFont).foregroundStyle(style.onAccent))
        let probe = CGSize(width: 1000, height: 1000)
        let labelSizes = labels.map { $0.measure(in: probe) }
        let tagSize = tagText.measure(in: probe)
        let tagPadX: CGFloat = 3
        let tagPadY: CGFloat = 1
        let tagW = tagSize.width + tagPadX * 2
        let tagH = tagSize.height + tagPadY * 2
        let labelH = labelSizes.map(\.height).max() ?? tagSize.height

        // Schmale Spalte rechts für Beschriftung und Etikett; zu schmal → ohne Texte
        let column = Swift.max(labelSizes.map(\.width).max() ?? 0, tagW) + 3
        let showText = size.width - column >= 40
        let plotLeft: CGFloat = 0
        let plotRight = showText ? size.width - column : size.width
        let inset = showText ? Swift.max(labelH, tagH) / 2 + 0.5 : lineWidth + 1
        let top = inset
        let bottom = size.height - inset
        guard bottom > top, plotRight > plotLeft else { return nil }
        let compact = size.height < compactHeight || size.width < compactWidth
        let slot = (plotRight - plotLeft) / CGFloat(data.count)

        func y(_ value: Double) -> CGFloat {
            WidgetChartGeometry.y(value, levels: levels, top: top, bottom: bottom)
        }
        /// Feine Linie auf Pixelmitte.
        func snap(_ v: CGFloat) -> CGFloat { (v / px).rounded(.down) * px + px / 2 }

        // 1. Senkrechte Zeit-Linien (zwischen den Kerzen bzw. an der Tages-/Wochengrenze)
        let openTimes = data.map(\.openTime)
        let interval = range.candleMillis
        var grid = Path()
        for time in WidgetChartGeometry.gridTimes(firstOpen: first.openTime, endMillis: last.openTime + interval,
                                                  range: range, timeZone: timeZone) {
            guard let gx = WidgetChartGeometry.x(time: time, openTimes: openTimes, intervalMillis: interval,
                                                 left: plotLeft, slot: slot),
                  gx > plotLeft + px, gx < plotRight - px else { continue }
            let sx = snap(gx)
            grid.move(to: CGPoint(x: sx, y: top))
            grid.addLine(to: CGPoint(x: sx, y: bottom))
        }
        ctx.stroke(grid, with: .color(gridColor), lineWidth: hair)

        // 2. Waagrechte Preisstufen (klein ohne mittlere)
        let centers = levelValues.map { y($0) }
        var levelPath = Path()
        for i in 0..<3 where !(levels.flat && i != 1) && !(compact && i == 1) {
            let ly = snap(centers[i])
            levelPath.move(to: CGPoint(x: plotLeft, y: ly))
            levelPath.addLine(to: CGPoint(x: plotRight, y: ly))
        }
        ctx.stroke(levelPath, with: .color(levelColor), lineWidth: hair)

        // 3. Kerzen oder Linie
        switch type {
        case .candles:
            let bodyW = Swift.max(px, (slot * 0.65 / px).rounded() * px)
            let wickW = Swift.max(px, Swift.min(1, bodyW * 0.3))
            for (i, candle) in data.enumerated() {
                let color = WidgetChartGeometry.isUp(candle) ? style.up : style.down
                let cx = ((plotLeft + (CGFloat(i) + 0.5) * slot) / px).rounded() * px
                var wick = Path()
                wick.move(to: CGPoint(x: cx, y: y(WidgetChartGeometry.top(candle))))
                wick.addLine(to: CGPoint(x: cx, y: y(WidgetChartGeometry.bottom(candle))))
                ctx.stroke(wick, with: .color(color), lineWidth: wickW)
                let yOpen = y(candle.open)
                let yClose = y(candle.close)
                var bodyTop = Swift.min(yOpen, yClose)
                var bodyH = abs(yOpen - yClose)
                if bodyH < px {
                    bodyTop = (yOpen + yClose) / 2 - px / 2
                    bodyH = px
                }
                ctx.fill(Path(CGRect(x: cx - bodyW / 2, y: bodyTop, width: bodyW, height: bodyH)), with: .color(color))
            }
        case .line:
            // Farbe nach Richtung über den Zeitraum
            let color = last.close >= first.close ? style.up : style.down
            var points: [CGPoint] = []
            for (i, candle) in data.enumerated() {
                points.append(CGPoint(x: plotLeft + (CGFloat(i) + 0.5) * slot, y: y(candle.close)))
            }
            if let p0 = points.first, let pn = points.last {
                var line = Path()
                line.move(to: p0)
                for p in points.dropFirst() { line.addLine(to: p) }
                var area = line
                area.addLine(to: CGPoint(x: pn.x, y: size.height))
                area.addLine(to: CGPoint(x: p0.x, y: size.height))
                area.closeSubpath()
                ctx.fill(area, with: .linearGradient(Gradient(colors: [color.opacity(0.28), color.opacity(0)]),
                                                     startPoint: CGPoint(x: 0, y: top),
                                                     endPoint: CGPoint(x: 0, y: size.height)))
                ctx.stroke(line, with: .color(color),
                           style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                let r = lineWidth * 1.3
                ctx.fill(Path(ellipseIn: CGRect(x: pn.x - r, y: pn.y - r, width: 2 * r, height: 2 * r)),
                         with: .color(color))
            }
        }

        // 4. Gestrichelte Akzent-Linie beim aktuellen Kurs; ausserhalb tief..hoch an den Rand geklemmt
        let lastY = Swift.min(Swift.max(y(tagPrice), top), bottom)
        var dashed = Path()
        dashed.move(to: CGPoint(x: plotLeft, y: lastY))
        dashed.addLine(to: CGPoint(x: plotRight, y: lastY))
        ctx.stroke(dashed, with: .color(style.accent.opacity(style.highContrast ? 1 : 0.7)),
                   style: StrokeStyle(lineWidth: Swift.max(px, 0.75), dash: [2, 2]))

        let layout = PriceChartLayout(plotLeft: plotLeft, plotRight: plotRight, top: top, bottom: bottom,
                                      count: data.count, levels: levels)
        guard showText else { return layout }

        // 5. Beschriftungen rechts (das Etikett gewinnt)
        let tagCenter = Swift.min(Swift.max(lastY, tagH / 2), size.height - tagH / 2)
        let visible = WidgetChartGeometry.visibleLabels(centers: centers, labelHeight: labelH, tagCenter: tagCenter,
                                                        tagHeight: tagH, compact: compact, flat: levels.flat)
        let textRight = size.width - tagPadX
        for i in 0..<3 where visible[i] {
            ctx.draw(labels[i], at: CGPoint(x: textRight, y: centers[i]), anchor: .trailing)
        }

        // 6. Kurs-Etikett in der Akzentfarbe
        let tagRect = CGRect(x: size.width - tagW, y: tagCenter - tagH / 2, width: tagW, height: tagH)
        ctx.fill(Path(roundedRect: tagRect, cornerRadius: 3, style: .continuous), with: .color(style.accent))
        ctx.draw(tagText, at: CGPoint(x: tagRect.midX, y: tagRect.midY), anchor: .center)
        return layout
    }
}
