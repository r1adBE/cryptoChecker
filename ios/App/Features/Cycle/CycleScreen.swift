import SwiftUI

/// Eigener Tab für die Marktphase. Jetzt (Karten): Crypto Pulse, «Heute auffällig»;
/// Einordnung (Zeilen): Fear & Greed, Marktphase, Dominanz mit Altcoin-Saison, Halving;
/// Daten (Zeilen): Krypto-Markt, Gas, Wirtschaftsdaten (ausser kurz vor/nach einem Termin,
/// dann oben), Coin-Analyse.
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
    /// ⋯ › App-Logo und Name: «Über».
    @State private var showAbout = false
    /// Was beim ersten Anzeigen schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel).
    @State private var revealedOnEntry: Int
    /// Letzter gezeigter «Stand …»-Text, damit die Zeile beim Ausblenden nicht leer springt.
    @State private var lastAsOfText = " "
    /// Beim Aufklappen schon erschienene Zeilen klappen nur auf (kein zweites Einblenden von unten).
    @State private var contextInstant = 0
    @State private var dataInstant = 0

    init(viewModel: CycleViewModel) {
        self.viewModel = viewModel
        _revealedOnEntry = State(initialValue: viewModel.revealed)
    }

    var body: some View {
        let revealed = viewModel.revealed
        ScrollView {
            // Ohne Abstand zwischen den Teilen: die Zeilen von «Einordnung» und «Daten» stossen
            // aneinander (Trennlinien); die Karten von «Jetzt» bringen ihren Abstand selbst mit.
            VStack(spacing: 0) {
                // Gespeicherter Stand wird gezeigt, während still neu geladen wird.
                // Der Platz bleibt immer reserviert — darunter springt nichts.
                asOfLine(viewModel.refreshingSince)
                // 1. Jetzt: «Crypto Pulse», «Heute auffällig» — als Karten.
                //    Beim Laden form-gleiche Platzhalter, bei Fehler eine kompakte Zeile.
                revealSlot(.pulse) {
                    VStack(spacing: 12) {
                        sectionHeader("market_section_now")
                        // Wirtschaftsdaten nur bei einem Termin in ±2 h hier oben, sonst unter «Daten»
                        CycleMacroHintRow(events: viewModel.macroEvents, atTop: true)
                        CryptoPulseCard(
                            market: viewModel.pulse,
                            fearGreed: viewModel.fearGreed.value,
                            gas: viewModel.gas.value,
                            onRetry: { viewModel.loadPulse(force: true) }
                        )
                    }
                    .padding(.top, 12)
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
                                router.explorerSearch = row.symbol
                                router.openExplorer()
                            }
                        },
                        onRetry: { viewModel.loadUnusual(force: true) }
                    )
                    .padding(.top, 12)
                }
                // 2. Einordnung — Zeilen ohne Karte: Fear & Greed, Marktphase, Dominanz,
                //    Altcoin-Saison, Zyklus/Halving. Tippen klappt die Details einer Zeile auf.
                //    Zugeklappt: Überschrift mit Zusammenfassung; die Zeilen werden dann gar nicht
                //    aufgebaut, ihre Daten laden aber weiter (bleiben frisch fürs Aufklappen).
                revealSlot(.headerContext) {
                    MarketSectionHeader(
                        title: L("market_section_context"),
                        summary: contextSummary,
                        expanded: contextExpanded,
                        onToggle: { toggle(.context) }
                    )
                    .padding(.top, Spacing.md)
                }
                if contextExpanded {
                    VStack(spacing: 0) {
                        revealSlot(.fearGreed, instantFrom: contextInstant) {
                            CycleFearGreedRow(state: viewModel.fearGreed, onRetry: refreshAll, divider: false,
                                              stamp: viewModel.fearGreedStamp)
                        }
                        revealSlot(.phase, instantFrom: contextInstant) {
                            MarketPhaseRow(
                                cycle: viewModel.cycle,
                                state: viewModel.market,
                                onRetry: { viewModel.loadMarket() },
                                stamp: viewModel.marketStamp
                            )
                        }
                        revealSlot(.dominance, instantFrom: contextInstant) {
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
                        revealSlot(.halving, instantFrom: contextInstant) {
                            CycleHalvingRow(cycle: viewModel.cycle, history: viewModel.history, onRetry: refreshAll)
                        }
                    }
                    .transition(sectionTransition)
                }
                // 3. Daten — Zeilen: Krypto-Markt (Marktkapitalisierung, Volumen), Gas,
                //    Wirtschaftsdaten, Coin
                revealSlot(.headerData) {
                    MarketSectionHeader(
                        title: L("market_section_data"),
                        summary: dataSummary,
                        expanded: dataExpanded,
                        onToggle: { toggle(.data) }
                    )
                    .padding(.top, Spacing.md)
                }
                if dataExpanded {
                    VStack(spacing: 0) {
                        revealSlot(.marketTotals, instantFrom: dataInstant) {
                            CycleMarketCapRow(
                                state: viewModel.globalMarket,
                                currency: data.settings.portfolioCurrency,
                                onRetry: refreshAll,
                                divider: false,
                                stamp: viewModel.globalStamp
                            )
                        }
                        revealSlot(.gas, instantFrom: dataInstant) {
                            CycleGasRow(
                                state: viewModel.gas,
                                ethAlertGwei: Double(data.settings.gasAlertEthTenths) / 10,
                                btcAlertSat: data.settings.gasAlertBtc,
                                onRetry: { viewModel.loadGas(force: true) },
                                stamp: viewModel.gasStamp
                            )
                        }
                        revealSlot(.coin, instantFrom: dataInstant) {
                            VStack(spacing: 0) {
                                CycleMacroHintRow(events: viewModel.macroEvents, atTop: false)
                                CycleCoinRow(viewModel: viewModel)
                            }
                        }
                    }
                    .transition(sectionTransition)
                }
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alle stehen
                if revealed < CycleReveal.count {
                    revealLoadingRow
                        .padding(.top, 12)
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
        .toolbar {
            // ⋯ wie in Merkliste und Portfolio: App (→ «Über»), Aktualisieren
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    AppMenuHead(
                        refreshing: !viewModel.refreshingKeys.isEmpty,
                        onOpenAbout: { showAbout = true },
                        onRefresh: refreshAll
                    )
                } label: {
                    if viewModel.refreshingKeys.isEmpty {
                        Image(systemName: "ellipsis.circle")
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
                .accessibilityLabel(L("action_more"))
            }
        }
        .navigationDestination(isPresented: $showAbout) {
            AboutSettingsPage()
        }
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
    /// `instantFrom`: beim Aufklappen eines Abschnitts schon erschienene Teile (ohne Einblenden).
    @ViewBuilder
    private func revealSlot<Content: View>(
        _ slot: CycleRevealSlot,
        instantFrom: Int = 0,
        @ViewBuilder content: () -> Content
    ) -> some View {
        if viewModel.revealed > slot.rawValue {
            let instantThrough = max(viewModel.revealInstant, revealedOnEntry, instantFrom)
            CycleRevealItem(animated: slot.rawValue >= instantThrough, content: content())
        }
    }

    // MARK: - Zuklappbare Abschnitte («Einordnung», «Daten»)

    /// Immer zugeklappt, bis der Nutzer aufklappt; in dieser App-Sitzung Gewähltes gilt weiter.
    private var contextExpanded: Bool {
        MarketSections.expanded(.context, sessionChoice: viewModel.sectionChoice[.context])
    }

    private var dataExpanded: Bool {
        MarketSections.expanded(.data, sessionChoice: viewModel.sectionChoice[.data])
    }

    /// Auf-/Zuklappen innerhalb des Scrollinhalts: Einblenden, die Teile darunter rücken nach.
    private var sectionTransition: AnyTransition {
        reduceMotion ? .identity : .opacity
    }

    /// Abschnitt auf- oder zuklappen (gilt für die Sitzung); ohne Animation bei reduzierter Bewegung.
    private func toggle(_ section: MarketSection) {
        let expanded = section == .context ? contextExpanded : dataExpanded
        if !expanded {
            if section == .context { contextInstant = viewModel.revealed } else { dataInstant = viewModel.revealed }
        }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: CycleReveal.swapSeconds)) {
            viewModel.sectionChoice[section] = !expanded
        }
    }

    /// Zugeklappt in der Überschrift von «Einordnung»: «Gier 72 · Neutral».
    private var contextSummary: String {
        MarketSections.summary([
            viewModel.fearGreed.value.map { fg in
                "\(L(CycleFearGreedStyle.labelKey(fg.value))) \(LocaleNumbers.integer(fg.value))"
            },
            viewModel.market.value.map { L($0.zone.labelKey) },
        ])
    }

    /// Zugeklappt in der Überschrift von «Daten»: «Marktkapitalisierung +1.20%».
    private var dataSummary: String {
        let change: Double? = viewModel.globalMarket.value.flatMap { $0.change24hPercent }
        return MarketSections.summary([
            change.map { "\(L("market_cap_label")) \(PriceFormat.changePercent($0) ?? PriceFormat.zeroPercent())" },
        ])
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

    /// Kleine Abschnittsüberschrift über einer Karten- bzw. Zeilengruppe; für VoiceOver eine
    /// Überschrift. Über «Einordnung» und «Daten» mehr Luft (`top`), da dort keine Karte trennt;
    /// die erste Zeile darunter bringt ihren Innenabstand mit.
    private func sectionHeader(_ key: String, top: CGFloat = 8) -> some View {
        Text(L(key))
            .sectionTitleStyle()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
            .padding(.top, top)
            .padding(.bottom, top > 8 ? 0 : -4)
            .accessibilityAddTraits(.isHeader)
    }

    /// «Stand 14:05 · wird aktualisiert …» — ältester gezeigter Stand. Immer eine Zeile hoch,
    /// auch ohne Stand: nur Inhalt und Deckkraft wechseln (der letzte Stand bleibt beim
    /// Ausblenden stehen, bis er unsichtbar ist).
    private func asOfLine(_ since: Int64?) -> some View {
        HStack(spacing: Spacing.xs) {
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
