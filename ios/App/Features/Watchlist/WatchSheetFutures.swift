import SwiftUI

/// Funding Rate, nächste Zahlung und Open Interest eines Perpetuals — wie `FuturesSection`.
struct WatchlistFuturesSection: View {
    let info: FuturesInfo

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Label(L("futures_title"), systemImage: "chart.bar.xaxis")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)

            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("futures_funding"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    let rate = info.fundingRatePercent
                    Text(rate.map { String(format: "%+.4f %%", locale: Locale.current, $0) } ?? "—")
                        .font(.headline.monospacedDigit())
                        // Positiv: Longs zahlen an Shorts, negativ umgekehrt
                        .foregroundStyle(rate.map { PriceColors.forChange($0) } ?? AppColors.onSurface)
                    if let next = info.nextFundingTime {
                        TimelineView(.periodic(from: .now, by: 30)) { context in
                            let minutes = max(0, (next - context.date.millis) / 60_000)
                            Text(L("futures_next_funding", Int(minutes / 60), Int(minutes % 60)))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
                    }
                    explanation(L("explain_funding"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .leading, spacing: 3) {
                    Text(L("futures_open_interest"))
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    Text(info.openInterestUsd.map { "$" + Self.compactNumber($0) } ?? "—")
                        .font(.headline.monospacedDigit())
                    explanation(L("explain_open_interest"))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text(L("futures_source", info.source))
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    /// Kurze Erklärung unter dem Wert (kleiner Nebentext, umbrechend).
    private func explanation(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 2)
    }

    /// Wie `compactNumber` in Android: 1.23 B, 4.5 M, 6.7 K.
    static func compactNumber(_ value: Double) -> String {
        let l = Locale.current
        if value >= 1e9 { return String(format: "%.2f B", locale: l, value / 1e9) }
        if value >= 1e6 { return String(format: "%.1f M", locale: l, value / 1e6) }
        if value >= 1e3 { return String(format: "%.1f K", locale: l, value / 1e3) }
        return String(format: "%.0f", locale: l, value)
    }
}
