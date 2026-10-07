import SwiftUI

// Kurs-Chart des Einzel-Widgets, geteilt mit dem Aktionsblatt der App: reine Geometrie
// (`WidgetChartGeometry`) und das Zeichnen in einen `GraphicsContext` (`PriceChartRenderer`).
// Ohne App- oder Widget-Typen; Farben und Schriften kommen über `PriceChartStyle`.

/// Chart-Art — wie `WidgetChartType` (Android).
enum PriceChartType: String, Sendable, CaseIterable {
    case candles, line
}

/// Zeitraum des Charts: Kerzenintervall und Anzahl — wie `WidgetChartRange` (Android).
/// 24 h = 24 × 1 h, 7 Tage = 42 × 4 h, 30 Tage = 30 × 1 Tag.
enum PriceChartRange: String, Sendable, CaseIterable {
    case day, week, month

    var candleInterval: CandleInterval {
        switch self {
        case .day: .h1
        case .week: .h4
        case .month: .d1
        }
    }

    var limit: Int {
        switch self {
        case .day: 24
        case .week: 42
        case .month: 30
        }
    }

    /// Dauer einer Kerze in ms (für die senkrechten Zeit-Linien).
    var candleMillis: Int64 {
        switch self {
        case .day: 3_600_000
        case .week: 4 * 3_600_000
        case .month: 24 * 3_600_000
        }
    }

    /// «24h», «7 Tage», «30 Tage» (auch Screenreader).
    var labelKey: String {
        switch self {
        case .day: "widget_range_24h"
        case .week: "widget_range_7d"
        case .month: "widget_range_30d"
        }
    }

    /// Kurzform, z. B. «7T».
    var shortLabelKey: String {
        switch self {
        case .day: "widget_range_short_24h"
        case .week: "widget_range_short_7d"
        case .month: "widget_range_short_30d"
        }
    }
}

// MARK: Geometrie (ohne Zeichnen)

/// Reine Rechenregeln des Einzel-Widget-Charts — gleiche Regeln wie Android
/// (Preisstufen, Zeit-Linien, Etikett gegen Beschriftung, Kerzenfarbe).
enum WidgetChartGeometry {
    /// Untere und obere Preisstufe; die mittlere liegt dazwischen.
    struct Levels: Equatable {
        let low: Double
        let high: Double
        var mid: Double { (low + high) / 2 }
        /// Keine Spanne: alles auf einer Höhe.
        var flat: Bool { !(high > low) }
    }

    /// Nur Kerzen mit gültigen, positiven Werten.
    static func clean(_ candles: [MarketCandle]) -> [MarketCandle] {
        candles.filter { c in
            [c.open, c.high, c.low, c.close].allSatisfy { $0.isFinite && $0 > 0 }
        }
    }

    /// Steigende Kerze (Schluss ≥ Eröffnung) → Kursfarbe «steigend».
    static func isUp(_ candle: MarketCandle) -> Bool { candle.close >= candle.open }

    /// Höchster Wert der Kerze (auch bei unsauberen Daten nie unter Open/Close).
    static func top(_ c: MarketCandle) -> Double { Swift.max(c.high, c.open, c.close) }

    /// Tiefster Wert der Kerze (auch bei unsauberen Daten nie über Open/Close).
    static func bottom(_ c: MarketCandle) -> Double { Swift.min(c.low, c.open, c.close) }

    /// Kerzen: tiefstes Low / höchstes High; Linie: tiefster / höchster Schluss.
    static func levels(_ candles: [MarketCandle], type: PriceChartType) -> Levels? {
        let lows: [Double]
        let highs: [Double]
        switch type {
        case .candles:
            lows = candles.map(bottom)
            highs = candles.map(top)
        case .line:
            lows = candles.map(\.close)
            highs = lows
        }
        guard let low = lows.min(), let high = highs.max() else { return nil }
        return Levels(low: low, high: high)
    }

    /// Höhe eines Preises zwischen `top` (oben, höchster Preis) und `bottom` (unten, tiefster Preis).
    static func y(_ value: Double, levels: Levels, top: CGFloat, bottom: CGFloat) -> CGFloat {
        guard !levels.flat else { return (top + bottom) / 2 }
        return bottom - CGFloat((value - levels.low) / (levels.high - levels.low)) * (bottom - top)
    }

    /// Zeitpunkte der senkrechten Linien (lokale Zeit) strikt nach `firstOpen` und vor `endMillis`:
    /// 24 h → jede volle Stunde, 7 Tage → jede Mitternacht, 30 Tage → jeder Montag 00:00.
    static func gridTimes(firstOpen: Int64, endMillis: Int64, range: PriceChartRange,
                          timeZone: TimeZone = .current) -> [Int64] {
        guard endMillis > firstOpen else { return [] }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let startDate = Date(millis: firstOpen)
        var current: Date?
        let unit: Calendar.Component
        let step: Int
        switch range {
        case .day:
            current = calendar.dateInterval(of: .hour, for: startDate)?.start
            unit = .hour
            step = 1
        case .week:
            current = calendar.startOfDay(for: startDate)
            unit = .day
            step = 1
        case .month:
            // Erster Montag (gregorianisch: Wochentag 2) ab dem Starttag
            var day = calendar.startOfDay(for: startDate)
            for _ in 0..<7 where calendar.component(.weekday, from: day) != 2 {
                guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
                day = next
            }
            current = day
            unit = .day
            step = 7
        }
        var times: [Int64] = []
        while let date = current, date.millis < endMillis, times.count < 400 {
            if date.millis > firstOpen { times.append(date.millis) }
            guard let next = calendar.date(byAdding: unit, value: step, to: date), next > date else { break }
            current = next
        }
        return times
    }

    /// Waagrechte Lage eines Zeitpunkts: Kerze `i` belegt [left + i·slot, left + (i+1)·slot]
    /// für [open_i, open_i + Intervall). nil, wenn keine Kerze den Zeitpunkt enthält (Lücke).
    static func x(time: Int64, openTimes: [Int64], intervalMillis: Int64, left: CGFloat, slot: CGFloat) -> CGFloat? {
        guard intervalMillis > 0 else { return nil }
        for (i, open) in openTimes.enumerated() where time >= open && time < open + intervalMillis {
            let fraction = Double(time - open) / Double(intervalMillis)
            return left + (CGFloat(i) + CGFloat(fraction)) * slot
        }
        return nil
    }

    /// Überlappen sich zwei senkrecht zentrierte Kästen (mit Abstand `gap`)?
    static func overlaps(_ a: CGFloat, _ heightA: CGFloat, _ b: CGFloat, _ heightB: CGFloat, gap: CGFloat = 1) -> Bool {
        abs(a - b) < (heightA + heightB) / 2 + gap
    }

    /// Sichtbarkeit der drei Beschriftungen (Index 0 = unten, 1 = Mitte, 2 = oben), `centers` in
    /// gleicher Reihenfolge. Klein (`compact`) nur oben und unten; ohne Spanne nur die Mitte.
    /// Das Kurs-Etikett gewinnt: Eine Beschriftung, die es überdecken würde, entfällt.
    /// Beschriftungen überlappen sich nie (zuerst fällt die Mitte, dann unten).
    static func visibleLabels(centers: [CGFloat], labelHeight: CGFloat, tagCenter: CGFloat?, tagHeight: CGFloat,
                              compact: Bool, flat: Bool) -> [Bool] {
        guard centers.count == 3 else { return [false, false, false] }
        var visible = flat ? [false, true, false] : [true, !compact, true]
        if let tagCenter {
            for i in 0..<3 where visible[i] && overlaps(centers[i], labelHeight, tagCenter, tagHeight) {
                visible[i] = false
            }
        }
        if visible[1] {
            for j in [0, 2] where visible[j] && overlaps(centers[1], labelHeight, centers[j], labelHeight) {
                visible[1] = false
            }
        }
        if visible[0], visible[2], overlaps(centers[0], labelHeight, centers[2], labelHeight) {
            visible[0] = false
        }
        return visible
    }

    /// Kennzahlen des Screenreader-Satzes: Kerzen = erstes Open, letztes Close, höchstes High,
    /// tiefstes Low; Linie = Schlusskurse. nil bei weniger als zwei Kerzen.
    static func summary(_ candles: [MarketCandle], type: PriceChartType)
        -> (start: Double, end: Double, high: Double, low: Double)? {
        let data = clean(candles)
        guard data.count >= 2, let first = data.first, let last = data.last,
              let levels = Self.levels(data, type: type) else { return nil }
        switch type {
        case .candles: return (first.open, last.close, levels.high, levels.low)
        case .line: return (first.close, last.close, levels.high, levels.low)
        }
    }

    /// VoiceOver-Satz zum Chart: Zeitraum, Start, Ende, Veränderung, Hoch, Tief (in der Quote).
    static func accessibility(_ candles: [MarketCandle], type: PriceChartType, range: PriceChartRange,
                              quote: String) -> String {
        let format: (Double) -> String = { PriceFormat.priceWithCurrency($0, quote) }
        guard let s = Self.summary(candles, type: type) else { return L("a11y_chart_empty") }
        return A11y.chart(period: L(range.labelKey), first: s.start, last: s.end, high: s.high, low: s.low,
                          format: format)
    }
}

// MARK: Zeichnen

/// Farben und Schriften des Charts (Widget: `WidgetPalette`, App: Kursfarben und Akzent).
struct PriceChartStyle {
    let up: Color
    let down: Color
    let accent: Color
    /// Text auf der Akzentfarbe (Kurs-Etikett).
    let onAccent: Color
    /// Beschriftung; Linien in abgeschwächter Fassung davon.
    let secondary: Color
    /// Hoher Kontrast: Linien 35 %, Akzentlinie voll.
    let highContrast: Bool
    var labelFont: Font = .system(size: 8.5, weight: .medium).monospacedDigit()
    var tagFont: Font = .system(size: 8.5, weight: .semibold).monospacedDigit()
    /// Strichstärke der Linie (nur Linien-Chart).
    var lineWidth: CGFloat = 1.6
}

/// Lage der Zeichenfläche nach dem Zeichnen — für Markierung und Ziehen im Aktionsblatt.
struct PriceChartLayout: Equatable {
    let plotLeft: CGFloat
    let plotRight: CGFloat
    let top: CGFloat
    let bottom: CGFloat
    let count: Int
    let levels: WidgetChartGeometry.Levels

    var slot: CGFloat { count > 0 ? (plotRight - plotLeft) / CGFloat(count) : 0 }

    /// Mitte der Kerze `index`.
    func x(_ index: Int) -> CGFloat { plotLeft + (CGFloat(index) + 0.5) * slot }

    func y(_ value: Double) -> CGFloat { WidgetChartGeometry.y(value, levels: levels, top: top, bottom: bottom) }
}

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
    @discardableResult
    static func render(_ ctx: inout GraphicsContext, size: CGSize, candles: [MarketCandle], type: PriceChartType,
                       range: PriceChartRange, style: PriceChartStyle, currentPrice: Double?,
                       px: CGFloat) -> PriceChartLayout? {
        let data = WidgetChartGeometry.clean(candles)
        guard data.count >= 2, size.width > 0, size.height > 0,
              let levels = WidgetChartGeometry.levels(data, type: type),
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
                                                  range: range) {
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
