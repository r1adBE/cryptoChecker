import SwiftUI
import WidgetKit

// Geometrie und Zeichnen des Charts liegen in Shared/Insights/PriceChart.swift
// (`WidgetChartGeometry`, `PriceChartRenderer`) — die App nutzt sie im Aktionsblatt.

extension WidgetChartTypeOption {
    /// Gleiche Chart-Art im geteilten Chart.
    var chartType: PriceChartType {
        switch self {
        case .candles: .candles
        case .line: .line
        }
    }
}

extension WidgetChartRangeOption {
    /// Gleicher Zeitraum im geteilten Chart (Kerzenintervall, Anzahl, Beschriftung).
    var chartRange: PriceChartRange {
        switch self {
        case .day: .day
        case .week: .week
        case .month: .month
        }
    }
}

// MARK: Ansicht

/// Chart des Einzel-Widgets: Kerzen oder Linie, drei Preisstufen mit Beschriftung rechts,
/// Etikett mit dem aktuellen Kurs in der Akzentfarbe, feine senkrechte Zeit-Linien.
/// Hoher Kontrast: Beschriftung in voller Nebentextfarbe, Linien mit 35 % davon.
struct WidgetPriceChartView: View {
    let candles: [MarketCandle]
    let type: WidgetChartTypeOption
    let range: WidgetChartRangeOption
    let palette: WidgetPalette
    /// Strichstärke der Linie (nur Linien-Chart).
    var lineWidth: CGFloat = 1.6
    /// Aktueller Kurs des Paars (wie die Schlagzeile) für das Etikett; nil/ungültig = letzter Schluss.
    var currentPrice: Double? = nil
    /// VoiceOver-Satz (Zeitraum, Start, Ende, Veränderung, Hoch, Tief); nil = Zierde.
    var accessibilityText: String? = nil
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let px = 1 / Swift.max(displayScale, 1)
        let style = PriceChartStyle(up: palette.up, down: palette.down, accent: palette.accent,
                                    onAccent: palette.onAccent, secondary: palette.secondary,
                                    highContrast: palette.highContrast, lineWidth: lineWidth)
        let chartType = type.chartType
        let chartRange = range.chartRange
        Canvas { context, size in
            _ = PriceChartRenderer.render(&context, size: size, candles: candles, type: chartType, range: chartRange,
                                          style: style, currentPrice: currentPrice, px: px)
        }
        .accessibilityElement()
        .accessibilityLabel(accessibilityText ?? "")
        .accessibilityHidden(accessibilityText == nil)
    }
}
