import SwiftUI

/// Zeile im Markt-Tab unter «Jetzt», die eine Unterseite öffnet (G-10): Titel (Überschrift wie
/// die Karten), darunter eine kurze Wertzeile, rechts der Pfeil. Für VoiceOver ein Element
/// (Schaltfläche, Überschrift). Wie `MarketSectionRow` (Android).
struct MarketSectionRow: View {
    let section: MarketSection
    let summary: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Spacing.sm) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L(section.titleKey))
                        .sectionTitleStyle()
                    Text(summary)
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Spacing.lg)
            .padding(.vertical, Spacing.md)
            .appCard(padding: .none)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits([.isButton, .isHeader])
        .padding(.top, 12)
    }

    /// Wertzeile «Einordnung»: «Fear & Greed 62 · Gier»; ohne Wert eine kurze Beschreibung.
    static func fearGreedSummary(_ fearGreed: FearGreed?) -> String {
        guard let fg = fearGreed else { return L("market_context_summary") }
        return L("market_context_value", L("insights_fng_title"), LocaleNumbers.integer(fg.value),
                 L(CycleFearGreedStyle.labelKey(fg.value)))
    }

    /// Wertzeile «Daten»: nächster Wirtschaftstermin (heute/morgen), sonst eine kurze Beschreibung.
    static func macroSummary(_ events: [MacroEvent], now: Int64 = EpochTime.nowMillis) -> String {
        guard let hint = MacroCalendar.hint(events, now: now) else { return L("market_data_summary") }
        return MacroCalendar.hintText(hint)
    }
}

/// «Mehr Marktdaten» im Markt-Tab unter Fear & Greed: klappt die zwei Zeilen zu den Unterseiten auf
/// bzw. zu (ganze Zeile tippbar, mind. 44 pt, Pfeil in der Themenfarbe). VoiceOver: Knopf mit Zustand
/// «aufgeklappt»/«zugeklappt». Wie `MarketMoreToggle` (Android).
struct MarketMoreToggle: View {
    @Binding var expanded: Bool
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button {
            expanded.toggle()
        } label: {
            HStack(spacing: Spacing.sm) {
                Text(L("market_more_data"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Spacer(minLength: Spacing.sm)
                // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie `ExpandToggleLabel`)
                Image(systemName: "chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Spacing.lg)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("market_more_data"))
        .accessibilityValue(L(expanded ? "a11y_expanded" : "a11y_collapsed"))
        .accessibilityAddTraits(.isHeader)
        .padding(.top, 12)
    }
}

/// Unterseite «Einordnung» bzw. «Daten» des Markt-Tabs (G-10): dieselben Karten wie zuvor im
/// Register, mit Zurück. Dasselbe ViewModel wie der Tab (`RootView`) — die Daten sind meist
/// schon geladen, die Seite steht sofort. Nach unten ziehen lädt alles neu.
/// Wie `MarketSectionScreen.kt`.
struct MarketSectionPage: View {
    @ObservedObject var viewModel: CycleViewModel
    let section: MarketSection
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Was beim Öffnen schon stand, erscheint ohne Animation.
    @State private var revealedOnEntry: Set<CycleRevealSlot>

    /// Ziel für `ScrollViewProxy.scrollTo`: Wirtschaftsdaten-Hinweis unter «Daten».
    private static let macroId = "market_macro"

    init(viewModel: CycleViewModel, section: MarketSection) {
        self.viewModel = viewModel
        self.section = section
        _revealedOnEntry = State(initialValue: viewModel.revealed)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    switch section {
                    case .context: contextCards
                    case .data: dataCards
                    case .now: EmptyView()
                    }
                    // Ruhige Ladezeile unter der letzten sichtbaren Karte, bis alle stehen
                    if MarketSections.loading(section, revealed: viewModel.revealed) {
                        ProgressView()
                            .controlSize(.small)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .padding(.top, 12)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(L("loading_hint"))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .readableContentWidth()
            }
            // Sprung aus der Mitteilung «Wirtschaftstermine»: zum Hinweis, sobald er steht
            .onChange(of: router.marketMacroOnData) { _, _ in scrollToMacro(proxy) }
            .onChange(of: viewModel.revealed) { _, _ in scrollToMacro(proxy) }
            .onAppear { scrollToMacro(proxy) }
        }
        .background(AppColors.background.ignoresSafeArea())
        .refreshable { await viewModel.refreshAll() }
        .navigationTitle(L(section.titleKey))
        .navigationBarTitleDisplayMode(.inline)
        // Nachladen bei Netz wieder da übernimmt der Tab darunter (`CycleScreen`)
    }

    /// Einordnung — je Eintrag eine Karte: Fear & Greed, Bitcoin-Kennzahlen, Dominanz,
    /// Altcoin-Saison, Zyklus/Halving. Tippen klappt die Details der Karte auf.
    private var contextCards: some View {
        VStack(spacing: 0) {
            revealSlot(.fearGreed) {
                CycleFearGreedRow(state: viewModel.fearGreed, onRetry: refreshAll,
                                  stamp: viewModel.fearGreedStamp)
            }
            revealSlot(.phase) {
                MarketPhaseRow(
                    state: viewModel.market,
                    onRetry: { viewModel.loadMarket() },
                    stamp: viewModel.marketStamp
                )
            }
            revealSlot(.dominance) {
                CycleDominanceRows(
                    dominance: viewModel.dominance,
                    altSeason: viewModel.altSeason,
                    onRetry: refreshAll,
                    altSeasonAsOf: viewModel.altSeasonAsOf,
                    altSeasonRefreshing: viewModel.altSeasonRefreshing,
                    onRefreshAltSeason: { viewModel.refreshAltSeason() },
                    dominanceStamp: viewModel.globalStamp,
                    altSeasonStamp: viewModel.altSeasonStamp
                )
            }
            revealSlot(.halving) {
                CycleHalvingRow(halving: viewModel.halving, history: viewModel.history, onRetry: refreshAll,
                                estimate: viewModel.halvingEstimate)
            }
        }
    }

    /// Daten — Karten: Krypto-Markt (Marktkapitalisierung, Volumen), Gas, Wirtschaftsdaten, Coin.
    private var dataCards: some View {
        VStack(spacing: 0) {
            revealSlot(.marketTotals) {
                CycleMarketCapRow(
                    state: viewModel.globalMarket,
                    currency: data.settings.portfolioCurrency,
                    onRetry: refreshAll,
                    stamp: viewModel.globalStamp
                )
            }
            revealSlot(.gas) {
                CycleGasRow(
                    state: viewModel.gas,
                    ethAlertGwei: Double(data.settings.gasAlertEthTenths) / 10,
                    btcAlertSat: data.settings.gasAlertBtc,
                    onRetry: { viewModel.loadGas(force: true) },
                    stamp: viewModel.gasStamp
                )
            }
            revealSlot(.coin) {
                VStack(spacing: 0) {
                    // Ziel des Sprungs aus der Mitteilung «Wirtschaftstermine»
                    CycleMacroHintRow(events: viewModel.macroEvents, atTop: false)
                        .id(Self.macroId)
                    CycleCoinRow(viewModel: viewModel)
                }
            }
        }
    }

    /// Ein Teil der Seite: erst da, wenn `CycleReveal` ihn freigibt; was schon stand, ohne Animation.
    @ViewBuilder
    private func revealSlot<Content: View>(
        _ slot: CycleRevealSlot,
        @ViewBuilder content: () -> Content
    ) -> some View {
        if viewModel.revealed.contains(slot) {
            let instant = viewModel.revealInstant.union(revealedOnEntry)
            CycleRevealItem(animated: !instant.contains(slot), content: content())
        }
    }

    /// Zum Wirtschaftsdaten-Hinweis scrollen, wenn der Tab das verlangt und der Hinweis steht.
    private func scrollToMacro(_ proxy: ScrollViewProxy) {
        guard section == .data, router.marketMacroOnData,
              viewModel.revealed.contains(.coin) else { return }
        router.marketMacroOnData = false
        Task { @MainActor in
            // Erst nach dem Aufbau der Seite (und dem Übergang)
            try? await Task.sleep(nanoseconds: 350_000_000)
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.3)) {
                proxy.scrollTo(Self.macroId, anchor: .center)
            }
        }
    }

    /// Wie Android: «Erneut versuchen» in den Zusatzkarten lädt alles neu.
    private func refreshAll() {
        Task { await viewModel.refreshAll() }
    }
}
