import SwiftUI

// Sprechblase an einer Marke des Zyklus-Charts (`CycleHistoryChart` in MarketCycleChart.swift): Anordnung und Inhalt.

/// Sprechblase neben einer Marke, immer innerhalb des Charts: bevorzugt rechts
/// oberhalb, sonst links bzw. unterhalb.
struct CycleBubbleLayout: Layout {
    let anchor: CGPoint
    private static let gap: CGFloat = 10

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width * 0.75, height: nil))
            // Waagrecht: rechts vom Punkt, sonst links, notfalls an den Rand geschoben
            var x = anchor.x + Self.gap
            if x + size.width > bounds.width { x = anchor.x - Self.gap - size.width }
            x = min(max(x, 0), max(bounds.width - size.width, 0))
            // Senkrecht: über dem Punkt, sonst darunter
            var y = anchor.y - Self.gap - size.height
            if y < 0 { y = anchor.y + Self.gap }
            y = min(max(y, 0), max(bounds.height - size.height, 0))
            subview.place(
                at: CGPoint(x: bounds.minX + x, y: bounds.minY + y),
                anchor: .topLeading,
                proposal: ProposedViewSize(size)
            )
        }
    }
}

/// Inhalt der Sprechblase: „Top 2021 · 69’000 USD“ / „+1’130 % seit Halving · 10.11.21“.
struct CycleMarkerBubble: View {
    let marker: CycleChartMarker

    private var label: String {
        let year = LocaleNumbers.integer(marker.marker.date.year)
        if marker.isSecondary {
            return L(marker.isTop ? "cycle_marker_double_top" : "cycle_marker_double_bottom", year)
        }
        if marker.isTop {
            return marker.isCurrentCycle ? L("cycle_marker_high_so_far") : L("cycle_marker_top", year)
        }
        return L("cycle_marker_bottom", year)
    }

    private var change: String {
        let pct = Self.percent(marker.marker.change)
        return marker.isTop ? L("cycle_since_halving", pct) : L("cycle_from_top", pct)
    }

    /// Prozent mit Vorzeichen und Tausendertrennung, ohne Nachkommastellen: „+1’130%“, deutsch „+1.130 %“ — Zeichen
    /// und Abstand über `PercentText.wrap` wie Android `cyclePercent`.
    static func percent(_ change: Double) -> String {
        let pct = change * 100
        let number = LocaleNumbers.decimal(abs(pct), maxDecimals: 0, grouping: true)
        // RTL: als Insel, sonst stünde das Vorzeichen hinter der Zahl
        return BidiText.ltr((pct >= 0 ? "+" : "−") + PercentText.wrap(number))
    }

    /// Kurzes Datum im Format des Geräts (`FormatStyle.SHORT`).
    static func shortDate(_ day: LocalDay) -> String {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .none
        return f.string(from: day.date)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: "\(label) · \(PriceFormat.priceWithCurrency(marker.marker.priceUsd, "USD"))")
                .font(.caption.weight(.semibold))
            Text(verbatim: "\(change) · \(Self.shortDate(marker.marker.date))")
                .font(.caption2)
        }
        .monospacedDigit()
        .foregroundStyle(AppColors.surface)
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, Spacing.sm)
        .background(AppColors.onSurface, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
        .shadow(color: AppColors.shadow.opacity(0.15), radius: 2, y: 1)
    }
}
