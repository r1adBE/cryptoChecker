import Accessibility
import SwiftUI

/// Portfolio-Tab: Gesamtwert, Coins nach Wert, Erfassen per «+» — wie `PortfolioScreen.kt`.
/// Bedienung wie in der Merkliste: nach unten ziehen oder ⋯ › «Aktualisieren» lädt die Kurse
/// neu; einen Coin nach links wischen löscht alle seine Transaktionen (Banner mit
/// «Rückgängig»); ⋯ › «Portfolio leeren» löscht nach Rückfrage alles.
/// Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
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
    /// Banner unten («BTC entfernt» mit «Rückgängig») — wie in der Merkliste.
    @State private var banner: WatchlistBannerMessage?
    /// «Portfolio leeren» — Rückfrage.
    @State private var askClear = false
    @State private var deleteTick = 0
    /// ⋯ › App-Logo und Name: «Über».
    @State private var showAbout = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

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
        .navigationDestination(isPresented: $showAbout) {
            AboutSettingsPage()
        }
        .sheet(isPresented: $alarmOpen) {
            PortfolioAlarmSheet(currency: data.settings.portfolioCurrency, basis: data.settings.changeBasis.storage) { kind, threshold, repeating in
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
        .overlay(alignment: .bottom) {
            // Animation nur für den Banner, nicht für die Liste darunter
            WatchlistBanner(message: $banner) { _ in }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: deleteTick)
        .confirmationDialog(L("portfolio_clear"), isPresented: $askClear, titleVisibility: .visible) {
            Button(L("portfolio_clear"), role: .destructive) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { data.clearPortfolio() }
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("portfolio_clear_confirm"))
        }
    }

    /// Nach links gewischt oder VoiceOver «Löschen»: alle Transaktionen des Coins weg, Banner
    /// mit «Rückgängig» und Ansage — wie `swipeDelete` in der Merkliste.
    private func deleteCoin(_ coin: String) {
        var removed: [PortfolioTx] = []
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { removed = data.deletePortfolioCoin(coin) }
        guard !removed.isEmpty else { return }
        deleteTick += 1
        let restore = removed
        let text = L("watchlist_removed", coin)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "trash",
            action: WatchlistBannerAction(title: L("action_undo")) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { data.restorePortfolioTxs(restore) }
            }
        )
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    // MARK: Inhalt

    private func content(_ summary: PortfolioSummary, currency: String) -> some View {
        List {
            PortfolioTotalCard(
                summary: summary,
                updatedAt: model.prices.updatedAt,
                currency: currency,
                fxRate: model.rate(for: currency)
            )
            .portfolioListRow(top: 4, bottom: 8)

            // Wertverlauf über den Positionen
            PortfolioHistoryCard(history: model.history, range: $model.historyRange,
                                 expanded: $data.settings.portfolioHistoryExpanded,
                                 view: $data.settings.portfolioHistoryView)
                .portfolioListRow(top: 0, bottom: 8)

            // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
            let slices = PortfolioInsights.allocation(summary.open)
            if slices.count >= 2 {
                PortfolioAllocationCard(slices: slices)
                    .portfolioListRow(top: 0, bottom: ListSegment.spacing)
            }

            // Kursänderung je Coin über die %-Basis steht klein in der Zeile
            let basis = data.settings.changeBasis.storage
            // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
            let txCounts = Dictionary(grouping: data.portfolio, by: { PortfolioCalculator.normalizeCoin($0.coin) })
                .mapValues(\.count)
            let openIndex = Dictionary(summary.open.enumerated().map { ($1.coin, $0) }, uniquingKeysWith: { first, _ in first })
            ForEach(summary.open) { position in
                Button {
                    openCoin = position.coin
                } label: {
                    PortfolioCoinRow(
                        position: position,
                        dayChange: model.coinChanges[position.coin] ?? model.coinChanges[position.coin.uppercased()],
                        basis: basis,
                        txCount: txCounts[position.coin] ?? 0,
                        shape: ListSegment.shape(openIndex[position.coin] ?? 0, summary.open.count)
                    )
                }
                .buttonStyle(.plain)
                // VoiceOver: Löschen wie nach links wischen (mit «Rückgängig»)
                .accessibilityActions {
                    Button(L("action_delete")) { deleteCoin(position.coin) }
                }
                // Nach links wischen = löschen ohne Rückfrage (Rückgängig im Banner); kein Favorit
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    if !voiceOver {
                        Button(role: .destructive) { deleteCoin(position.coin) } label: {
                            Label(L("action_delete"), systemImage: "trash")
                        }
                        // Systemrot: weisse Schrift bleibt auch im Dunkelmodus lesbar
                        .tint(AppColors.destructive)
                    }
                }
                .portfolioListRow(top: ListSegment.gap / 2, bottom: ListSegment.gap / 2)
            }

            if !summary.closed.isEmpty {
                closedSection(summary.closed)
                    .portfolioListRow(top: ListSegment.spacing, bottom: 4)
            }

            PortfolioDisclaimer()
                .portfolioListRow(top: 0, bottom: 0)
            // Unten Platz für den «+»-Knopf
            Color.clear.frame(height: 72).portfolioListRow(top: 0, bottom: 0)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: summary.open.map(\.coin))
        .animation(.easeInOut(duration: 0.25), value: showClosed)
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
            Menu {
                // Gleicher Anfang wie Merkliste und Markt: App (→ «Über»), Aktualisieren
                AppMenuHead(
                    refreshing: model.refreshing,
                    onOpenAbout: { showAbout = true },
                    onRefresh: { Task { await model.refresh(force: true) } }
                )
                Divider()
                // «Umrechnen in: CHF» — Auswahl der Zielwährung
                Picker(selection: Binding(
                    get: { data.settings.portfolioCurrency },
                    set: { data.settings.portfolioCurrency = $0 }
                )) {
                    ForEach(FxRateSource.currencies, id: \.self) { code in
                        Text(code).tag(code)
                    }
                } label: {
                    Label(L("portfolio_convert_to_value", currency), systemImage: "arrow.left.arrow.right")
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
                        Label(L("portfolio_alarm_action"), systemImage: "bell.and.waves.left.and.right")
                    }
                    Divider()
                    // Wie «Merkliste leeren»: zuunterst, rot, mit Rückfrage
                    Button(role: .destructive) {
                        askClear = true
                    } label: {
                        Label(L("portfolio_clear"), systemImage: "trash")
                    }
                }
            } label: {
                // Läuft das Aktualisieren, dreht sich hier ein Kreis (der Knopf oben ist weg)
                if model.refreshing {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "ellipsis.circle")
                }
            }
            .accessibilityLabel(L("action_more"))
        }
    }
}
