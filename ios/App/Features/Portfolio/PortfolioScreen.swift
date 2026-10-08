import SwiftUI

/// Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+» — wie `PortfolioScreen.kt`.
/// Oben rechts: Aktualisieren und das Menü «Umrechnen in». Nach unten ziehen lädt die
/// Kurse neu. Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
@MainActor
struct PortfolioScreen: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.priceColorScheme) private var priceColors
    @Environment(\.priceHighContrast) private var highContrast
    @Environment(\.priceColorsInverted) private var inverted
    @ObservedObject private var model = PortfolioModel.shared
    @Environment(\.appAccent) private var accent

    @State private var sheet: PortfolioTxDraft?
    @State private var openCoin: String?
    private var hideAmounts: Bool { data.settings.hidePortfolioAmounts }
    @State private var showClosed = false
    @State private var exportOpen = false
    /// Neuer Alarm «Portfolio-Wert» (erscheint in der Alarm-Übersicht).
    @State private var alarmOpen = false
    @State private var toast: String?

    init() {}

    var body: some View {
        let transactions = data.portfolio
        let currency = data.settings.portfolioCurrency
        Group {
            if transactions.isEmpty {
                emptyState
            } else {
                content(PortfolioCalculator.summarize(transactions, prices: model.prices.prices), currency: currency)
            }
        }
        // «Beträge verbergen» (Auge oben bzw. Einstellung): Beträge als «•••», Prozente bleiben
        .environment(\.hidePortfolioAmounts, data.settings.hidePortfolioAmounts)
        .background(AppColors.background.ignoresSafeArea())
        .navigationTitle(L("portfolio_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent(hasTransactions: !transactions.isEmpty, currency: currency) }
        // Beim Öffnen und bei neuen Coins Kurse auffrischen (60 s Zwischenspeicher)
        .task(id: Set(transactions.map(\.coin))) { await model.refresh(force: false) }
        .task(id: currency) { await model.loadFx(currency) }
        // Wertverlauf: geänderte Transaktionen (auch ohne neuen Coin) neu rechnen
        .onChange(of: transactions) { model.recomputeHistory() }
        .sheet(item: $sheet) { draft in
            PortfolioTxSheet(initial: draft)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        .navigationDestination(item: $openCoin) { coin in
            PortfolioCoinDetail(coin: coin)
        }
        .sheet(isPresented: $alarmOpen) {
            PortfolioAlarmSheet(currency: data.settings.portfolioCurrency, basis: data.settings.changeBasis) { kind, threshold, repeating in
                data.addPortfolioAlarm(kind: kind, threshold: threshold, currency: data.settings.portfolioCurrency,
                                       repeating: repeating)
                // Neu und scharf: gleich mit den aktuellen Kursen prüfen
                Task { await model.refresh(force: false) }
            }
            .environment(\.appAccent, accent)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $exportOpen) {
            PortfolioExportSheet(
                transactions: data.portfolio,
                currency: data.settings.portfolioCurrency,
                onExported: {
                    exportOpen = false
                    toast = L("portfolio_export_done")
                }
            )
            .environment(\.appAccent, accent)
            // Eine feste Höhe: Hinweis «heute» und grosse Schrift lassen nur den Inhalt wachsen
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        .toast($toast)
    }

    // MARK: Inhalt

    private func content(_ summary: PortfolioSummary, currency: String) -> some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                PortfolioTotalCard(
                    summary: summary,
                    updatedAt: model.prices.updatedAt,
                    currency: currency,
                    fxRate: model.rate(for: currency)
                )
                .padding(.bottom, 4)

                // Wertverlauf über den Positionen
                PortfolioHistoryCard(history: model.history, range: $model.historyRange,
                                     expanded: $data.settings.portfolioHistoryExpanded)
                    .padding(.bottom, 4)

                // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
                let slices = PortfolioInsights.allocation(summary.open)
                if slices.count >= 2 {
                    PortfolioAllocationCard(slices: slices)
                        .padding(.bottom, 4)
                }
                // Grösste Bewegungen über die %-Basis (sobald es eine Vergleichsbasis gibt)
                let movers = PortfolioInsights.movers(summary.open, changes: model.coinChanges)
                if !movers.isEmpty {
                    PortfolioMoversCard(movers: movers, basis: data.settings.changeBasis)
                        .padding(.bottom, 4)
                }

                ForEach(summary.open) { position in
                    Button {
                        openCoin = position.coin
                    } label: {
                        PortfolioCoinRow(position: position)
                    }
                    .buttonStyle(.plain)
                }

                if !summary.closed.isEmpty {
                    closedSection(summary.closed)
                }

                PortfolioDisclaimer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            // Unten Platz für den «+»-Knopf
            .padding(.bottom, 96)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
            .animation(.spring(duration: 0.35), value: summary.open.map(\.coin))
            .animation(.easeInOut(duration: 0.25), value: showClosed)
        }
        .refreshable { await model.refresh(force: true) }
        .overlay(alignment: .bottomTrailing) {
            PortfolioAddButton { sheet = PortfolioTxDraft() }
        }
    }

    /// «Geschlossene Positionen (n)» — eingeklappt, nur der realisierte Gewinn/Verlust.
    @ViewBuilder
    private func closedSection(_ closed: [CoinPosition]) -> some View {
        Button {
            showClosed.toggle()
        } label: {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .rotationEffect(.degrees(showClosed ? 90 : 0))
                Text(L("portfolio_closed", count: closed.count))
                    .font(.subheadline.weight(.medium))
                Spacer()
            }
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.horizontal, 4)
            .padding(.vertical, Spacing.md)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: showClosed)

        if showClosed {
            VStack(spacing: 0) {
                ForEach(Array(closed.enumerated()), id: \.element.coin) { index, position in
                    if index > 0 { RowDivider() }
                    Button {
                        openCoin = position.coin
                    } label: {
                        HStack(spacing: 12) {
                            CoinBadge(symbol: position.coin, size: 32)
                            Text(position.coin)
                                .font(.body)
                                .foregroundStyle(AppColors.onSurface)
                            Spacer(minLength: 8)
                            Text(PortfolioInsights.mask(PortfolioFormat.signedUsdt(position.realized), hidden: hideAmounts))
                                .font(AppFont.amount(.subheadline))
                                .foregroundStyle(PortfolioFormat.plColor(position.realized, scheme: priceColors,
                                                                    highContrast: highContrast, inverted: inverted))
                        }
                        .padding(.vertical, Spacing.md)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, 2)
            .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }

    // MARK: Leer

    private var emptyState: some View {
        ScrollView {
            VStack(spacing: 0) {
                EmptyStateView(
                    systemImage: "chart.pie",
                    title: L("portfolio_empty_title"),
                    message: L("portfolio_empty_text"),
                    actionTitle: L("portfolio_empty_action"),
                    action: { sheet = PortfolioTxDraft() }
                )
                // Beispiel mit 58 000 in der Umrechnungswährung
                Text(L("portfolio_empty_example",
                       PriceFormat.priceWithCurrency(58_000, data.settings.portfolioCurrency)))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 32)
                    .padding(.bottom, 16)
                PortfolioDisclaimer()
            }
            .padding(.top, 48)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
        }
    }

    // MARK: Leiste oben

    @ToolbarContentBuilder
    private func toolbarContent(hasTransactions: Bool, currency: String) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            // «Beträge verbergen»: alle Beträge als «•••» (Prozente bleiben), auch im Widget
            if hasTransactions {
                let hidden = data.settings.hidePortfolioAmounts
                Button {
                    data.settings.hidePortfolioAmounts.toggle()
                } label: {
                    Image(systemName: hidden ? "eye.slash" : "eye")
                }
                .accessibilityLabel(L(hidden ? "a11y_portfolio_show_amounts" : "portfolio_hide_amounts"))
                .sensoryFeedback(.selection, trigger: hidden)
            }
            if model.refreshing {
                ProgressView().controlSize(.small)
            } else if hasTransactions {
                Button {
                    Task { await model.refresh(force: true) }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .accessibilityLabel(L("action_refresh"))
            }
            Menu {
                // «Umrechnen in: CHF» — Auswahl der Zielwährung
                Picker(selection: Binding(
                    get: { data.settings.portfolioCurrency },
                    set: { data.settings.portfolioCurrency = $0 }
                )) {
                    ForEach(FxRateSource.currencies, id: \.self) { code in
                        Text(code).tag(code)
                    }
                } label: {
                    Text(L("portfolio_convert_to_value", currency))
                }
                .pickerStyle(.menu)
                if hasTransactions {
                    // Stichtag-Export (z. B. Bestand per 31.12. für die Steuererklärung)
                    Button {
                        exportOpen = true
                    } label: {
                        Label(L("portfolio_export_action"), systemImage: "doc.text")
                    }
                    // Alarm «Portfolio-Wert»
                    Button {
                        alarmOpen = true
                    } label: {
                        Label(L("portfolio_alarm_action"), systemImage: "bell")
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel(L("action_more"))
        }
    }
}

// MARK: Gesamtkarte

/// Gesamtwert, ± unrealisiert, investiert/realisiert, Umrechnung und Stand der Kurse.
private struct PortfolioTotalCard: View {
    // Kursfarben kommen aus `PriceColors`; diese Werte nur lesen, damit die Ansicht
    // beim Umstellen (Schema, Tausch, Kontrast) neu zeichnet.
    @Environment(\.priceColorScheme) private var priceColorsDependency
    @Environment(\.priceColorsInverted) private var invertedDependency
    @Environment(\.priceHighContrast) private var highContrastDependency
    let summary: PortfolioSummary
    let updatedAt: Int64
    let currency: String
    let fxRate: Double?
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.hidePortfolioAmounts) private var hideAmounts

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("portfolio_total_value"))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
            Text(PortfolioInsights.mask(PortfolioFormat.usdtValue(summary.totalValue), hidden: hideAmounts))
                .displayFont()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .contentTransition(.numericText(value: summary.totalValue))
                .padding(.top, 2)
            // «≈ 12’345.67 CHF» — nur mit Devisenkurs und nicht bei USD
            if currency != "USD", let fxRate {
                Text("≈ " + PortfolioInsights.mask(PriceFormat.valueWithCurrency(summary.totalValue * fxRate, currency), hidden: hideAmounts))
                    .font(AppFont.amount(.subheadline))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .contentTransition(.numericText())
            }

            HStack(spacing: 8) {
                Text(summary.unrealized.map { PortfolioInsights.mask(PortfolioFormat.signedUsdt($0), hidden: hideAmounts) } ?? "—")
                    .font(AppFont.amount(.headline))
                    .foregroundStyle(PortfolioFormat.plColor(summary.unrealized, scheme: priceColorsDependency,
                                            highContrast: highContrastDependency, inverted: invertedDependency))
                    .contentTransition(.numericText())
                if summary.unrealized != nil {
                    PortfolioPlPill(percent: summary.unrealizedPercent)
                }
            }
            .padding(.top, Spacing.sm)

            HStack(alignment: .top, spacing: 12) {
                PortfolioMetric(
                    label: L("portfolio_invested"),
                    value: summary.invested.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—"
                )
                if !PortfolioFormat.isZero(summary.realized) {
                    PortfolioMetric(
                        label: L("portfolio_realized"),
                        value: PortfolioInsights.mask(PortfolioFormat.signedUsdt(summary.realized), hidden: hideAmounts),
                        valueColor: PortfolioFormat.plColor(summary.realized, scheme: priceColorsDependency,
                                            highContrast: highContrastDependency, inverted: invertedDependency)
                    )
                }
            }
            .padding(.top, 12)

            // Hinweise, warum ± fehlt
            if summary.costMissing {
                PortfolioHint(text: L("portfolio_price_missing_hint")).padding(.top, 8)
            }
            if !summary.missingCurrentPrices.isEmpty {
                PortfolioHint(text: L("portfolio_current_missing", summary.missingCurrentPrices.joined(separator: ", ")))
                    .padding(.top, 8)
            }
            if updatedAt > 0 {
                Text(L("portfolio_updated", PriceFormat.time(updatedAt)))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.top, Spacing.sm)
            }
        }
        .padding(Spacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(LinearGradient(
                    colors: [accent.primary.opacity(0.16), accent.primary.opacity(0.04)],
                    startPoint: .topLeading, endPoint: .bottomTrailing))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(accent.primary.opacity(0.25), lineWidth: 1)
        )
        // Weniger Bewegung: Ziffern wechseln ohne Rollen
        .animation(reduceMotion ? nil : .snappy, value: summary.totalValue)
    }
}

// MARK: Coin-Zeile

/// Zeile je Coin: Plakette, Symbol, Menge, Ø/aktuell, Wert und ± %.
private struct PortfolioCoinRow: View {
    let position: CoinPosition
    @Environment(\.hidePortfolioAmounts) private var hideAmounts

    var body: some View {
        HStack(spacing: 12) {
            CoinBadge(symbol: position.coin, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: Spacing.xs) {
                    Text(position.coin)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    if position.oversold {
                        Image(systemName: "exclamationmark.circle.fill")
                            .scaledFont(size: 13, relativeTo: .footnote)
                            .foregroundStyle(AppColors.error)
                            .accessibilityLabel(L("portfolio_oversold"))
                    }
                }
                Text(PortfolioInsights.mask(PortfolioFormat.amount(position.holdings, position.coin), hidden: hideAmounts))
                    .font(AppFont.amount(.caption))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                Text(L("portfolio_avg_and_now", PriceFormat.price(position.avgCost), PriceFormat.price(position.currentPrice)))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 4) {
                Text(position.value.map { PortfolioInsights.mask(PortfolioFormat.usdtValue($0), hidden: hideAmounts) } ?? "—")
                    .font(AppFont.amount(.subheadline, weight: .semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .contentTransition(.numericText())
                if position.priceMissing {
                    Text(L("portfolio_price_missing"))
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                } else {
                    PortfolioPlPill(percent: position.unrealizedPercent)
                }
            }
        }
        .padding(.horizontal, Spacing.md)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(AppColors.outlineVariant.opacity(0.45), lineWidth: 1)
        )
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
