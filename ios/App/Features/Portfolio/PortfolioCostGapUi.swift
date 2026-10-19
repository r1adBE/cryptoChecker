import SwiftUI

// Einträge ohne Einstand und Einstand für Adressguthaben (Build 84): Hilfen, Merker «Unbekannt lassen» und die sanfte
// Karte im Portfolio-Tab — wie `PortfolioCostGapUi.kt`. Blätter in `PortfolioCostGapsSheet.swift` und
// `PortfolioAddressLotsSheet.swift`.

/// Gemeinsame Hilfen der Einstand-Blätter.
enum PortfolioCostGapUi {
    /// Id eines Hinweises unter der Gesamtkarte, der die Einträge ohne Einstand öffnet (keine echte Transaktion).
    static let hintId = Int64.min

    /// Schlüssel der Merker «Unbekannt lassen» (nur Ids, auf diesem Gerät; nicht in der Sicherung) — wie Android
    /// `PortfolioCostGapStore`.
    static let unknownKey = "portfolioCostGapsUnknown"

    /// Gespeicherte Ids lesen («12,15»).
    static func ids(_ raw: String) -> Set<Int64> { Set(raw.split(separator: ",").compactMap { Int64($0) }) }

    /// Ids speichern (sortiert, damit gleiche Mengen gleich aussehen).
    static func raw(_ ids: Set<Int64>) -> String { ids.sorted().map(String.init).joined(separator: ",") }

    /// Geprüfter Tagesschluss von `coin` am Tag von `millis` in USDT (gleiche Quelle wie das Erfassen-Blatt); nil ohne Kurs.
    @MainActor
    static func close(_ coin: String, millis: Int64) async -> Decimal? {
        let current = await PortfolioModel.shared.currentPrice(coin)
        let result = await HistoricPriceSource.dailyCloseUsdt(coin, day: CutoffDay(Date(millis: millis)), current: current)
        guard let p = result.price, p.isFinite, p > 0 else { return nil }
        return PortfolioDecimal.of(p)
    }
}

/// Sanfte Karte: «Einträge ohne Einstand: N» mit kurzer Erklärung; antippen öffnet das Blatt — wie `CostGapsCard` (Android).
struct PortfolioCostGapsCard: View {
    let count: Int
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: Spacing.sm) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(L("cost_gaps_card", count))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.onSurface)
                    Text(L("cost_gaps_card_text"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .appCard()
    }
}
