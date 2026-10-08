import SwiftUI

/// «LIVE» in der Status-Pille, solange Kurse per WebSocket kommen (`LivePriceStream`) — wie
/// `WatchlistLiveBadge.kt`: kleiner pulsierender Punkt und Schriftzug in der Farbe der Pille.
/// «Bewegung reduzieren»: Punkt steht still.
struct WatchlistLiveBadge: View {
    let color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
                .phaseAnimator(reduceMotion ? [1.0] : [1.0, 0.3]) { view, phase in
                    view.opacity(phase)
                } animation: { _ in .easeInOut(duration: 0.9) }
            Text(L("watchlist_live_badge"))
                .font(.caption2.weight(.bold))
                .foregroundStyle(color)
                .lineLimit(1)
                .fixedSize()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("watchlist_live_a11y"))
    }
}
