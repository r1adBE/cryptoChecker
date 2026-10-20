import SwiftUI

// Kleine Teile des Charts im Aktionsblatt (`WatchSheetChartView`): Kerzen-Symbol, Zeile unter dem Chart,
// Kopfzeile mit der Änderung und die Masse der Beschriftungen.

/// Zwei Kerzen mit Docht (24er-Raster wie Material «candlestick_chart»), in der Vordergrundfarbe.
/// Auch im Schalter Kerzen | Linie des Wertverlaufs im Portfolio.
struct CandlesIcon: View {
    var body: some View {
        Canvas { context, size in
            let k = min(size.width, size.height) / 24
            func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> Path {
                Path(CGRect(x: x * k, y: y * k, width: w * k, height: h * k))
            }
            var p = Path()
            p.addPath(r(7, 4, 2, 16))   // Docht links
            p.addPath(r(5, 6, 6, 12))   // Körper links
            p.addPath(r(15, 4, 2, 16))  // Docht rechts
            p.addPath(r(13, 8, 6, 7))   // Körper rechts
            context.fill(p, with: .foreground)
        }
        .accessibilityHidden(true)
    }
}

/// Kleine graue Zeile unter dem Chart (wie Android `SheetChartCaption`): Börse der Kerzen (fremde Börse als
/// «Chart: Binance», `markets` = Schlüssel und Name der Börse des Paars), bei der
/// Ausweich-Reihe die Umrechnung («BTC/USDT, umgerechnet mit Tageskursen» bzw. «… (aktueller Kurs)»)
/// und der Stand der letzten Kerze («Stand 14:00»), getrennt mit « · ».
struct SheetChartCaption: View {
    let info: SheetChartInfo
    let candles: [MarketCandle]
    let range: PriceChartRange
    let timeZone: TimeZone
    let markets: [String]
    let quote: String

    /// Eine Reihe mit heutigem Kurs umgerechnet: eigene, deutliche Zeile darunter statt «(aktueller Kurs)» oben.
    private var todayRate: (source: String, target: String)? {
        SheetChart.todayRateCaption(info.fx, sourcePair: info.sourcePair, quote: quote)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: text)
                .font(.caption2)
            if let rate = todayRate {
                Text(verbatim: L("sheet_chart_usdt_today_rate", BidiText.isolate(rate.source), BidiText.isolate(rate.target)))
                    .font(.caption)
            }
        }
        .foregroundStyle(AppColors.onSurfaceVariant)
        .padding(.top, -4)
    }

    private var text: String {
        var parts: [String] = []
        // Kerzen einer anderen Börse (Ausweich-Kette): als fremd kennzeichnen, «Chart: Binance» statt nur «Binance»
        if let foreign = DayChange.foreignCandleSource(markets: markets, provider: info.provider) {
            parts.append(L("watch_row_source_chart", BidiText.isolate(foreign)))
        } else if let provider = info.provider {
            parts.append(provider)
        }
        let pair = BidiText.isolate(info.sourcePair ?? "")
        switch info.fx {
        case .daily: parts.append(L("sheet_chart_converted_daily", pair))
        case .current:
            if todayRate == nil { parts.append(L("sheet_chart_converted_current", pair)) }
        case .none: break
        }
        // Aus der USDT-Reihe umgerechnet: USDT = 1 USD angenommen (Vermerk wie im Portfolio)
        if SheetChart.usdtParityNote(info.fx, sourcePair: info.sourcePair) { parts.append(L("portfolio_usdt_assumed")) }
        if let last = candles.last {
            let stamp = SheetChart.stampMillis(lastOpenTime: last.openTime, intervalMillis: range.candleMillis,
                                               fetchedAt: info.fetchedAt)
            let template = SheetChart.stampTemplate(stamp: stamp, now: TimeUtils.nowMillis, timeZone: timeZone)
            let f = DateFormatter()
            f.locale = Locale.current
            f.timeZone = timeZone
            f.setLocalizedDateFormatFromTemplate(template)
            parts.append(L("pulse_updated", BidiText.isolate(f.string(from: Date(millis: stamp)))))
        }
        return parts.joined(separator: " · ")
    }
}

/// Kopfzeile über dem Chart: Text vor und nach `mark` grau, dazwischen die Änderung.
struct SheetChangeHeaderLine: View {
    let sentence: String
    let mark: String
    let change: Double?

    var body: some View {
        let parts = sentence.components(separatedBy: mark)
        let before = parts.first?.trimmingCharacters(in: .whitespaces) ?? ""
        let after = parts.count > 1 ? parts[1].trimmingCharacters(in: .whitespaces) : ""
        HStack(spacing: Spacing.xs) {
            if !before.isEmpty { label(before) }
            ChangePill(change: change, size: .medium, dashWhenMissing: true)
            if !after.isEmpty { label(after) }
        }
        .lineLimit(1)
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(AppColors.onSurfaceVariant)
    }
}

/// Beschriftungen in Charts (Aktionsblatt, Wertverlauf): 10 pt, mit der Textgrösse bis 1.5× (Build 46,
/// vorher 1.3×). Die Preisspalte misst ihre Breite und die Ränder die Höhe — nichts wird abgeschnitten.
/// Wie Android `LABEL_SP` / `MAX_FONT_SCALE`.
enum ChartLabelMetrics {
    static let base: CGFloat = 10
    static let maxScale: CGFloat = 1.5
    static let maxSize: CGFloat = base * maxScale
}
