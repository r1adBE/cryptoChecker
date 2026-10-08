import SwiftUI

/// Ein Coin: Kennzahlen und seine Transaktionen — wie `PortfolioDetailScreen.kt`.
/// Tippen bearbeitet, nach links wischen oder lange drücken löscht (mit Rückfrage).
/// «+» wählt den Coin schon vor. Ist die letzte Transaktion weg, geht es zurück.
@MainActor
struct PortfolioCoinDetail: View {
    let coin: String

    @EnvironmentObject private var data: AppData
    @ObservedObject private var model = PortfolioModel.shared
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss

    @State private var sheet: PortfolioTxDraft?
    @State private var askDelete: PortfolioTx?

    init(coin: String) {
        self.coin = PortfolioCalculator.normalizeCoin(coin)
    }

    var body: some View {
        let all = data.portfolio
        let own = all.filter { PortfolioCalculator.normalizeCoin($0.coin) == coin }
        let position = PortfolioCalculator.position(coin, trades: all, currentPrice: model.prices.prices[coin])
        List {
            PortfolioPositionCard(position: position)
                .portfolioListRow(top: 4, bottom: 4)

            Text(L("portfolio_transactions"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.leading, 4)
                .portfolioListRow(top: 12, bottom: 2)

            ForEach(own) { tx in
                Button {
                    sheet = PortfolioTxDraft(tx: tx)
                } label: {
                    PortfolioTxRow(tx: tx)
                }
                .buttonStyle(.plain)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button {
                        askDelete = tx
                    } label: {
                        Label(L("action_delete"), systemImage: "trash")
                    }
                    .tint(AppColors.error)
                }
                .contextMenu {
                    Button {
                        sheet = PortfolioTxDraft(tx: tx)
                    } label: {
                        Label(L("portfolio_edit_tx"), systemImage: "pencil")
                    }
                    Button(role: .destructive) {
                        askDelete = tx
                    } label: {
                        Label(L("action_delete"), systemImage: "trash")
                    }
                }
                .portfolioListRow(top: 4, bottom: 4)
            }

            PortfolioDisclaimer()
                .portfolioListRow(top: 0, bottom: 0)
            // Platz für den «+»-Knopf
            Color.clear.frame(height: 72).portfolioListRow(top: 0, bottom: 0)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // «Beträge verbergen» (Einstellung bzw. Auge im Portfolio-Kopf)
        .environment(\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .background(AppColors.background.ignoresSafeArea())
        .refreshable { await model.refresh(force: true) }
        .animation(.spring(duration: 0.35), value: own.map(\.id))
        .overlay(alignment: .bottomTrailing) {
            PortfolioAddButton { sheet = PortfolioTxDraft(coin: coin) }
        }
        .navigationTitle(coin)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.refresh(force: false) }
        // Letzte Transaktion gelöscht: zurück zur Übersicht
        .onChange(of: own.isEmpty, initial: true) { _, empty in
            if empty { dismiss() }
        }
        .sheet(item: $sheet) { draft in
            PortfolioTxSheet(initial: draft)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        .alert(
            L("portfolio_tx_delete_title"),
            isPresented: Binding(get: { askDelete != nil }, set: { if !$0 { askDelete = nil } }),
            presenting: askDelete
        ) { tx in
            Button(L("action_delete"), role: .destructive) {
                withAnimation { data.deletePortfolioTx(tx.id) }
                askDelete = nil
            }
            Button(L("action_cancel"), role: .cancel) { askDelete = nil }
        } message: { _ in
            Text(L("portfolio_tx_delete_confirm"))
        }
    }
}

// MARK: Kennzahlen

/// Kennzahlen eines Coins in zwei Spalten.
private struct PortfolioPositionCard: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    let position: CoinPosition

    var body: some View {
        let p = position
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                CoinBadge(symbol: p.coin, size: 44, portfolio: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("portfolio_value"))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    Text(p.value.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
                        .displayFont(.compact)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .contentTransition(.numericText())
                }
            }
            HStack(alignment: .top, spacing: 12) {
                PortfolioMetric(label: L("portfolio_holdings"), value: PortfolioInsights.mask(PortfolioFormat.amount(p.holdings, p.coin), hidden: hideAmounts))
                PortfolioMetric(label: L("portfolio_avg_price"), value: PortfolioFormat.price(p.avgCost))
            }
            .padding(.top, 16)
            HStack(alignment: .top, spacing: 12) {
                PortfolioMetric(label: L("portfolio_current_price"), value: PortfolioFormat.price(p.currentPrice))
                PortfolioMetric(label: L("portfolio_invested"), value: p.costBasis.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
            }
            .padding(.top, 12)
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    PortfolioMetric(
                        label: L("portfolio_unrealized"),
                        value: p.unrealized.map { PortfolioInsights.mask(PortfolioFormat.signedUsdt($0), hidden: hideAmounts) } ?? "—",
                        valueColor: PortfolioFormat.plColor(p.unrealized, scheme: priceColorsDependency,
                                            highContrast: highContrastDependency, inverted: invertedDependency)
                    )
                    if p.unrealized != nil {
                        PortfolioPlPill(percent: p.unrealizedPercent)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if !PortfolioFormat.isZero(p.realized) {
                    PortfolioMetric(
                        label: L("portfolio_realized"),
                        value: PortfolioInsights.mask(PortfolioFormat.signedUsdt(p.realized), hidden: hideAmounts),
                        valueColor: PortfolioFormat.plColor(p.realized, scheme: priceColorsDependency,
                                            highContrast: highContrastDependency, inverted: invertedDependency)
                    )
                } else {
                    Color.clear.frame(maxWidth: .infinity, maxHeight: 0)
                }
            }
            .padding(.top, 12)
            if p.priceMissing {
                PortfolioHint(text: L("portfolio_price_missing_hint")).padding(.top, 8)
            }
            if p.oversold {
                PortfolioHint(text: L("portfolio_oversold"), color: AppColors.error).padding(.top, 8)
            }
        }
        .portfolioSurface(padding: 20, radius: 20)
    }
}

// MARK: Transaktion

/// Transaktion: Art und Datum, Menge × Preis, Notiz, Summe.
private struct PortfolioTxRow: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    let tx: PortfolioTx

    var body: some View {
        HStack(spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 0) {
                    Text(PortfolioFormat.typeLabel(tx.type))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PortfolioFormat.typeColor(tx.type))
                    Text(verbatim: " · " + PortfolioFormat.date(tx.time))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Text(verbatim: PortfolioInsights.mask(PortfolioFormat.amount(tx.amount, tx.coin), hidden: hideAmounts) + " × "
                    + (tx.priceUsdt.map { PortfolioFormat.price($0) } ?? L("portfolio_price_missing")))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if let note = tx.note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            Text(tx.priceUsdt.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0 * tx.amount), hidden: hideAmounts) } ?? "—")
                .font(AppFont.amount(.subheadline, weight: .medium))
                .foregroundStyle(AppColors.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

extension View {
    /// Listenzeile ohne Trenner und Hintergrund, mit Kartenabstand.
    func portfolioListRow(top: CGFloat, bottom: CGFloat) -> some View {
        self
            .listRowInsets(EdgeInsets(top: top, leading: 16, bottom: bottom, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}
