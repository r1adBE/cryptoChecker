import SwiftUI

/// Eigener Tab für die Marktphase. Jetzt: Crypto Pulse, Fear & Greed, Krypto-Markt;
/// Einordnung: Marktphase, Dominanz, Halving; Daten: Coin-Analyse, Gas.
/// Nach unten ziehen lädt die Daten neu. Wie `MarketPhaseScreen.kt`.
///
/// Enthält keinen `NavigationStack` — der Aufrufer bettet den Tab ein.
/// Das ViewModel gehört `RootView`, damit es Tab-Wechsel übersteht.
struct CycleScreen: View {
    @ObservedObject var viewModel: CycleViewModel
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appAccent) private var accent
    /// «Warum?»-Blatt für einen beobachteten Coin aus «Heute auffällig».
    @State private var whyFor: CycleWhyTarget?
    /// Was beim ersten Anzeigen schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel).
    @State private var revealedOnEntry: Int
    /// Letzter gezeigter «Stand …»-Text, damit die Zeile beim Ausblenden nicht leer springt.
    @State private var lastAsOfText = " "

    init(viewModel: CycleViewModel) {
        self.viewModel = viewModel
        _revealedOnEntry = State(initialValue: viewModel.revealed)
    }

    var body: some View {
        let revealed = viewModel.revealed
        ScrollView {
            VStack(spacing: 12) {
                // Gespeicherter Stand wird gezeigt, während still neu geladen wird.
                // Der Platz bleibt immer reserviert — darunter springt nichts.
                asOfLine(viewModel.refreshingSince)
                // 1. Jetzt: «Crypto Pulse», Stimmung, Krypto-Markt (Marktkapitalisierung, Volumen).
                //    Beim Laden form-gleiche Platzhalter, bei Fehler eine kompakte Zeile.
                revealSlot(.pulse) {
                    VStack(spacing: 12) {
                        sectionHeader("market_section_now")
                        // Wichtige US-Wirtschaftsdaten heute: kompakte Zeile über dem Pulse
                        CycleMacroHintRow(events: viewModel.macroEvents)
                        CryptoPulseCard(
                            market: viewModel.pulse,
                            fearGreed: viewModel.fearGreed.value,
                            gas: viewModel.gas.value,
                            onRetry: { viewModel.loadPulse(force: true) }
                        )
                    }
                }
                // «Heute auffällig»: Tippen öffnet «Warum?» (Coin in der Merkliste) oder die Suche
                revealSlot(.unusual) {
                    CycleUnusualCard(
                        state: viewModel.unusual,
                        isWatched: { watchId(for: $0) != nil },
                        onOpen: { row in
                            if let id = watchId(for: row.symbol) {
                                whyFor = CycleWhyTarget(id: id)
                            } else {
                                router.openExplorer(search: row.symbol)
                            }
                        },
                        onRetry: { viewModel.loadUnusual(force: true) }
                    )
                }
                revealSlot(.fearGreed) {
                    CycleFearGreedCard(state: viewModel.fearGreed, onRetry: refreshAll)
                }
                revealSlot(.marketTotals) {
                    CycleMarketCapCard(
                        state: viewModel.globalMarket,
                        currency: data.settings.portfolioCurrency,
                        onRetry: refreshAll
                    )
                }
                // 2. Einordnung: Marktphase, Dominanz (mit Altcoin-Saison), Zyklus/Halving
                revealSlot(.headerContext) {
                    sectionHeader("market_section_context")
                }
                revealSlot(.phase) {
                    MarketPhaseCard(
                        cycle: viewModel.cycle,
                        state: viewModel.market,
                        onRetry: { viewModel.loadMarket() }
                    )
                }
                revealSlot(.dominance) {
                    CycleDominanceCard(dominance: viewModel.dominance, altSeason: viewModel.altSeason, onRetry: refreshAll)
                }
                revealSlot(.halving) {
                    CycleHalvingCard(cycle: viewModel.cycle, history: viewModel.history, onRetry: refreshAll)
                }
                // 3. Daten: Coin, Gas
                revealSlot(.headerData) {
                    sectionHeader("market_section_data")
                }
                revealSlot(.coin) {
                    CycleCoinCard(viewModel: viewModel)
                }
                revealSlot(.gas) {
                    CycleGasCard(
                        state: viewModel.gas,
                        ethAlertGwei: Double(data.settings.gasAlertEthTenths) / 10,
                        btcAlertSat: data.settings.gasAlertBtc,
                        onRetry: { viewModel.loadGas(force: true) }
                    )
                }
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alle stehen
                if revealed < CycleReveal.count {
                    revealLoadingRow
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
            // Nur wenn eine sichtbare Karte die Art wechselt (Platzhalter → Inhalt, weil Daten
            // spät kommen): überblenden, die Karte ändert ihre Höhe, die darunter folgen ihr.
            // Neue Werte derselben Art (Ziehen nach unten) und das Erscheinen lösen das nicht aus.
            .animation(reduceMotion ? nil : .easeInOut(duration: CycleReveal.swapSeconds), value: swapKey)
        }
        .background(AppColors.background.ignoresSafeArea())
        .refreshable { await viewModel.refreshAll() }
        .navigationTitle(L("tab_market_phase"))
        .onAppear { viewModel.onAppear() }
        // «Warum bewegt sich das?» wie in der Merkliste — gleich in voller Höhe, springt nicht
        .sheet(item: $whyFor) { target in
            WatchlistWhySheet(watchId: target.id)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
    }

    /// Paar der Merkliste zu einem Coin: Spot zuerst (wie die Karte, USDT/USD vor anderen
    /// Quotes), sonst das Perpetual; nil = nicht beobachtet. Das Blatt rechnet dann mit den
    /// Daten genau dieses Paars (Futures-Paar: Futures-Kerzen).
    private func watchId(for symbol: String) -> Int64? {
        CandleSeries.pickWatch(
            // Nicht mehr gehandelte Paare zählen nicht als beobachtet (kein «Warum?» auf alten Daten)
            data.watches.filter { !$0.isNotTraded }, symbol: symbol,
            base: { $0.baseAsset }, quote: { $0.quoteAsset },
            isSpot: { $0.contractType == .none },
            isPerpetual: { $0.contractType == .perpetual || $0.contractType == .inversePerpetual }
        )?.id
    }

    /// Art des Zustands je Karte — unabhängig davon, ob sie schon sichtbar ist: so ändert das
    /// Erscheinen einer Karte (nach der Frist, Daten lagen schon vor) den Schlüssel nicht und
    /// löst keine Layout-Animation aus. (Mit «unsichtbar = lädt» sprang er beim Erscheinen
    /// einer geladenen Karte von 0 auf 1 und animierte das Einfügen doch.)
    private var swapKey: [Int] {
        CycleRevealSlot.allCases.map { slot in
            switch slot {
            case .pulse: viewModel.pulse.phase
            case .unusual: viewModel.unusual.phase
            case .fearGreed: viewModel.fearGreed.phase
            case .marketTotals: viewModel.globalMarket.phase
            case .phase: viewModel.market.phase
            case .dominance: viewModel.dominance.phase * 3 + viewModel.altSeason.phase
            case .halving: viewModel.history.phase
            case .coin: viewModel.coin.phase
            case .gas: viewModel.gas.phase
            case .headerContext, .headerData: 0
            }
        }
    }

    /// Ein Teil des Tabs: erst da, wenn `CycleReveal` ihn freigibt — vorher weder gezeichnet
    /// noch für VoiceOver vorhanden. Was beim Betreten schon stand bzw. beim Öffnen schon
    /// bereit war, erscheint ohne Animation.
    @ViewBuilder
    private func revealSlot<Content: View>(_ slot: CycleRevealSlot, @ViewBuilder content: () -> Content) -> some View {
        if viewModel.revealed > slot.rawValue {
            let instantThrough = max(viewModel.revealInstant, revealedOnEntry)
            CycleRevealItem(animated: slot.rawValue >= instantThrough, content: content())
        }
    }

    /// Kleine Ladezeile fester Höhe unter der letzten sichtbaren Karte.
    private var revealLoadingRow: some View {
        ProgressView()
            .controlSize(.small)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L("loading_hint"))
    }

    /// Kleine Abschnittsüberschrift über einer Kartengruppe; für VoiceOver eine Überschrift.
    private func sectionHeader(_ key: String) -> some View {
        Text(L(key))
            .font(.footnote.weight(.semibold))
            .foregroundStyle(AppColors.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
            .padding(.top, 8)
            .padding(.bottom, -4)
            .accessibilityAddTraits(.isHeader)
    }

    /// «Stand 14:05 · wird aktualisiert …» — ältester gezeigter Stand. Immer eine Zeile hoch,
    /// auch ohne Stand: nur Inhalt und Deckkraft wechseln (der letzte Stand bleibt beim
    /// Ausblenden stehen, bis er unsichtbar ist).
    private func asOfLine(_ since: Int64?) -> some View {
        HStack(spacing: 6) {
            ProgressView()
                .controlSize(.mini)
                .accessibilityHidden(true)
            Text(since.map { L("cycle_data_as_of", Self.stampText($0)) } ?? lastAsOfText)
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .opacity(since == nil ? 0 : 1)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: since == nil)
        .onChange(of: since, initial: true) { _, newValue in
            if let newValue { lastAsOfText = L("cycle_data_as_of", Self.stampText(newValue)) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityHidden(since == nil)
    }

    /// Heute nur die Uhrzeit («14:05»), sonst Datum und Uhrzeit.
    private static func stampText(_ millis: Int64) -> String {
        let date = Date(millis: millis)
        let today = Calendar.current.isDateInToday(date)
        return date.formatted(date: today ? .omitted : .abbreviated, time: .shortened)
    }

    /// Wie Android: «Erneut versuchen» in den Zusatzkarten lädt alles neu.
    private func refreshAll() {
        Task { await viewModel.refreshAll() }
    }
}
