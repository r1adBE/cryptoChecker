import SwiftUI

/// Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+» — wie `PortfolioScreen.kt`.
/// Oben rechts: Aktualisieren und das Menü «Umrechnen in». Nach unten ziehen lädt die
/// Kurse neu. Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
@MainActor
struct PortfolioScreen: View {
    @EnvironmentObject var data: AppData
    @Environment(\.priceColorScheme) var priceColors
    @Environment(\.priceHighContrast) var highContrast
    @Environment(\.priceColorsInverted) var inverted
    @ObservedObject private var model = PortfolioModel.shared
    @Environment(\.appAccent) var accent

    @State var sheet: PortfolioTxDraft?
    @State var openCoin: String?
    var hideAmounts: Bool { data.settings.hidePortfolioAmounts }
    @State var showClosed = false
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
