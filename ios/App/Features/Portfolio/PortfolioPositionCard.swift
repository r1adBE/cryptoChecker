import Accessibility
import SwiftUI

// Positionskarte der Coin-Detailseite (Kennzahlen des Coins) und die offenen Kauf-Posten darunter.
// Eigene Datei, weil die Karte eine eigenständige Anzeige ist; die Detailseite ordnet nur an
// (wie `PortfolioPositionCard.kt`).

// MARK: Kennzahlen

/// Kennzahlen eines Coins in zwei Spalten.
struct PortfolioPositionCard: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    @Environment(\.appAccent) private var accent
    let position: CoinPosition
    /// Teil aus beobachteten Adressen («Aus Adresse · ohne Kaufpreis»); nil = keiner.
    var addressAmount: Decimal? = nil
    /// Adresse und eigene Transaktionen im selben Coin: werden addiert — Hinweis statt stiller Doppelzählung.
    var overlap = false
    /// Eigene Einzahlung/Umbuchung = Guthaben/Zugang einer zählenden Adresse (± 1 %): deutliche Warnung wie bei der Adresse.
    var matches: [PortfolioTx] = []
    /// «ohne Kaufpreis» antippen: Einträge ohne Einstand (`PortfolioCostGapsSheet`); nil = nicht antippbar.
    var onMissingCost: (() -> Void)? = nil
    /// «Aus Adresse» antippen: Einstand für Adressguthaben (`PortfolioAddressLotsSheet`); nil = nicht antippbar.
    var onAddressCost: (() -> Void)? = nil

    var body: some View {
        let p = position
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                CoinBadge(symbol: p.coin, size: 44, portfolio: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("portfolio_value"))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                    PriceWithQuote.text(p.value.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
                        .displayFont(.compact)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .contentTransition(.numericText())
                }
            }
            HStack(alignment: .top, spacing: 12) {
                PortfolioMetric(label: L("portfolio_holdings"), value: PortfolioInsights.mask(PortfolioFormat.exactAmount(p.holdingsExact, p.coin), hidden: hideAmounts),
                                smallQuote: false)
                VStack(alignment: .leading, spacing: 2) {
                    // FIFO: Ø über die offenen Posten; Ø-Methode: gleitender Durchschnitt — wie Android
                    PortfolioMetric(label: L(p.method.isFifo ? "portfolio_avg_open_lots" : "portfolio_avg_price"),
                                    value: PortfolioFormat.price(p.avgCost))
                    // Ø nur über den Teil mit Kaufpreis: Menge nennen («Ø von 0.5 BTC mit Kaufpreis»)
                    if p.avgCostPartial {
                        Text(L("portfolio_avg_partial",
                               PortfolioInsights.mask(PortfolioFormat.exactAmount(p.pricedHoldingsExact, p.coin), hidden: hideAmounts)))
                            .font(.caption)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.top, 16)
            // Teil aus beobachteten Adressen («Aus Adresse · nicht in «Seit Kauf»: 0.5 BTC»)
            if let addressAmount {
                Text(L("portfolio_address_not_since_buy",
                       PortfolioInsights.mask(PortfolioFormat.exactAmount(addressAmount, p.coin), hidden: hideAmounts)))
                    .font(.caption)
                    .foregroundStyle(onAddressCost == nil ? AppColors.onSurfaceVariant : accent.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
                    .costLink(onAddressCost)
                if !matches.isEmpty {
                    let amounts: String = matches.map { t -> String in PortfolioFormat.exactAmount(t.amountExact, p.coin) }
                        .joined(separator: ", ")
                    Text(L("portfolio_address_overlap_tx", PortfolioInsights.mask(amounts, hidden: hideAmounts)))
                        .font(.caption)
                        .foregroundStyle(AppColors.error)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                } else if overlap {
                    Text(L("portfolio_address_overlap", p.coin))
                        .font(.caption)
                        .foregroundStyle(AppColors.error)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 2)
                }
            }
            HStack(alignment: .top, spacing: 12) {
                PortfolioMetric(label: L("portfolio_current_price"), value: PortfolioFormat.price(p.currentPrice))
                // Immer «Einstand» — die Methode steht in den Einstellungen und im Hinweis darunter
                PortfolioMetric(label: L("portfolio_invested"), value: p.costBasis.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
            }
            .padding(.top, 12)
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    PortfolioMetric(
                        label: L("portfolio_unrealized"),
                        value: p.unrealized.map { PortfolioInsights.mask(PortfolioFormat.signedUsdt($0), hidden: hideAmounts) } ?? "—",
                        valueTone: PortfolioFormat.plTone(p.unrealized)
                    )
                    if p.unrealized != nil {
                        PortfolioPlPill(percent: p.unrealizedPercent)
                    }
                    // Nur der Teil mit Kaufpreis zählt: «ohne 0.5 BTC ohne Kaufpreis» — wie Android
                    if p.unrealizedPartial {
                        Text(L("portfolio_unrealized_without_unpriced",
                               PortfolioInsights.mask(PortfolioFormat.exactAmount(p.unpricedHoldingsExact, p.coin), hidden: hideAmounts)))
                            .font(.caption)
                            .foregroundStyle(onMissingCost == nil ? AppColors.onSurfaceVariant : accent.primary)
                            .fixedSize(horizontal: false, vertical: true)
                            .costLink(onMissingCost)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if PortfolioFormat.showRealized(p.realized, incomplete: p.realizedIncomplete) {
                    PortfolioMetric(
                        label: L("portfolio_realized"),
                        value: PortfolioInsights.mask(
                            PortfolioFormat.realizedText(p.realized, incomplete: p.realizedIncomplete),
                            hidden: hideAmounts),
                        valueTone: PortfolioFormat.plTone(p.realized)
                    )
                } else {
                    Color.clear.frame(maxWidth: .infinity, maxHeight: 0)
                }
            }
            .padding(.top, 12)
            // Wie Einstand und Ø gerechnet werden (nach der gewählten Methode) — wie Android
            PortfolioHint(text: L(p.method.isFifo ? "portfolio_fifo_hint" : "portfolio_avg_price_hint")).padding(.top, 8)
            if p.priceMissing {
                // Antippbar: öffnet die Einträge ohne Einstand dieses Coins (Build 84)
                PortfolioHint(text: L("portfolio_price_missing_hint")).padding(.top, 8).costLink(onMissingCost)
            }
            // Netzwerkgebühr, deren Einstand keinen bezahlten Posten mehr fand (weder Einstand noch realisiert)
            if p.lostFeeCostUsdt != 0 {
                PortfolioHint(text: L("portfolio_lost_fee_cost",
                                      PortfolioInsights.mask(PortfolioFormat.usdtValue(PortfolioDecimal.double(p.lostFeeCostUsdt)),
                                                             hidden: hideAmounts))).padding(.top, 8)
            }
            // Gebührenanteil auf Bestand ohne Kaufpreis: offener Posten statt still entfallen (Einstand unbekannt)
            if p.unassignedFeeUsdt != 0 {
                PortfolioHint(text: L("portfolio_unassigned_fee",
                                      PortfolioInsights.mask(PortfolioFormat.usdtValue(PortfolioDecimal.double(p.unassignedFeeUsdt)),
                                                             hidden: hideAmounts))).padding(.top, 8)
            }
            if p.oversold {
                PortfolioHint(text: L("portfolio_oversold"), color: AppColors.error).padding(.top, 8)
            }
        }
        .appCard()
    }
}

/// «Offene Kauf-Posten» (zugeklappt): FIFO je Posten Kaufdatum, Restmenge, Rest-Einstand (und je Coin), Wallet (FIFO je
/// Wallet), Posten ohne Kaufpreis markiert; Ø die zusammengefasste(n) Zeile(n) — wie `OpenLotsSection` (Android).
struct PortfolioOpenLotsSection: View {
    let lots: [PortfolioOpenLot]
    let coin: String
    @Environment(\.hidePortfolioAmounts) private var hideAmounts
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                ExpandToggleLabel(title: L("portfolio_open_lots", lots.count), expanded: expanded)
            }
            .buttonStyle(.plain)
            if expanded {
                VStack(alignment: .leading, spacing: Spacing.sm) {
                    ForEach(Array(lots.enumerated()), id: \.offset) { _, lot in
                        row(lot)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .transition(.opacity)
            }
        }
    }

    private func row(_ lot: PortfolioOpenLot) -> some View {
        let title: String
        if lot.aggregated {
            title = L(lot.costUsdt != nil ? "portfolio_open_lots_avg" : "portfolio_open_lots_no_cost")
        } else {
            title = lot.acquiredTime.map { PortfolioFormat.date($0) } ?? "—"
        }
        var detail: [String] = []
        if let cost = lot.costUsdt {
            var text = PortfolioInsights.mask(PortfolioFormat.usdtValue(PortfolioDecimal.double(cost)), hidden: hideAmounts)
            if lot.amount > 0 {
                text += " · Ø " + PortfolioFormat.price(PortfolioDecimal.double(PortfolioDecimal.divide(cost, lot.amount)))
            }
            detail.append(text)
        } else {
            detail.append(L("portfolio_open_lots_without_price"))
        }
        if let wallet = lot.wallet {
            detail.append(L("tax_export_wallet", wallet.trimmingCharacters(in: .whitespaces).isEmpty ? L("tax_export_wallet_unspecified") : wallet))
        }
        return HStack(alignment: .top, spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                    .font(.subheadline.weight(.medium).monospacedDigit())
                    .foregroundStyle(AppColors.onSurface)
                Text(verbatim: detail.joined(separator: " · "))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(lot.costUsdt == nil ? AppColors.error : AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Text(verbatim: PortfolioInsights.mask(PortfolioFormat.exactAmount(lot.amount, coin), hidden: hideAmounts))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AppColors.onSurface)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }
}

extension View {
    /// Antippbar, wenn `action` gesetzt ist (Hinweis «ohne Kaufpreis» → Blatt zum Ergänzen) — wie `Modifier.costLink`.
    @ViewBuilder
    func costLink(_ action: (() -> Void)?) -> some View {
        if let action {
            self.contentShape(Rectangle()).onTapGesture(perform: action).accessibilityAddTraits(.isButton)
        } else {
            self
        }
    }
}
