import SwiftUI

/// «Symbole erklärt» (Menü der Merkliste): die kleinen Symbole unter einem Paar (`WatchlistSymbol`, Reihenfolge wie in
/// der Zeile) mit je einer Zeile Text — wie `WatchlistSymbolLegendSheet` (Android).
struct WatchlistSymbolLegendSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.md) {
                    ForEach(WatchlistSymbol.allCases, id: \.self) { symbol in
                        HStack(spacing: Spacing.lg) {
                            icon(symbol)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                                .frame(width: 24, height: 24)
                                .accessibilityHidden(true)
                            Text(L(symbol.textKey))
                                .font(.body)
                                .foregroundStyle(AppColors.onSurface)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(Spacing.lg)
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("watchlist_symbols_legend"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_close")) { dismiss() }
                }
            }
        }
    }

    /// Dasselbe Symbol wie in der Zeile (`WatchlistRowParts`), hier etwas grösser.
    @ViewBuilder
    private func icon(_ symbol: WatchlistSymbol) -> some View {
        if let kind = symbol.kind {
            AssetKindIconView(kind: kind).scaleEffect(1.5)
        } else {
            switch symbol {
            case .PRICE_NOTIFICATION_OFF: Image(systemName: "bell.slash")
            case .ACTIVE_ALARMS: Image(systemName: "bell.and.waves.left.and.right.fill")
            default: Image(systemName: "bell")
            }
        }
    }
}
