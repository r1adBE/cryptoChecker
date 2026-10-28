import Accessibility
import SwiftUI

/// Eigener Tab für die Marktphase. Aufbau von «Jetzt» (gleich wie Android): oben EINE
/// Heldenkarte — Crypto Pulse mit «Kurz gesagt» als Körper (`CryptoPulseCard`, `MarketPlainSummaryBlock`);
/// darüber nur die Status-Pille. Darunter ruhig und kompakt Fear & Greed (`CycleFearGreedRow`, compact) und
/// «Gewinner und Verlierer 24 h» (`CycleMoversCard`, je drei, «Alle zeigen»). Dann «Mehr Marktdaten»
/// (`MarketMoreToggle`, Standard zu, gemerkt) mit «Heute auffällig», dem Wirtschaftsdaten-Hinweis (Termin in
/// ±2 h) und zwei Zeilen (`MarketSectionRow`) zu den Unterseiten (`MarketSectionPage`, G-10):
/// Einordnung (Fear & Greed, Marktphase, Dominanz mit Altcoin-Saison, Halving) und Daten
/// (Krypto-Markt, Gas, Wirtschaftsdaten — ausser kurz vor/nach einem Termin —, Coin-Analyse).
/// Deren Daten laden schon beim Öffnen des Tabs, die Unterseite steht sofort.
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
    /// Aktionsblatt (bzw. Vorschau) für einen Coin aus «Heute auffällig».
    @State private var coinTarget: CycleCoinTarget?
    /// ⋯ › App-Logo und Name: «Über».
    @State private var showAbout = false
    /// Was beim ersten Anzeigen schon stand, erscheint ohne Animation (kein Schauspiel je Tab-Wechsel).
    @State private var revealedOnEntry: Set<CycleRevealSlot>
    /// Offene Unterseite «Einordnung» bzw. «Daten» (G-10).
    @State private var openSection: MarketSection?
    /// «Mehr Marktdaten» nur für diesen Besuch offen (Mitteilung «Wirtschaftstermine»); die gemerkte
    /// Einstellung bleibt, wie sie ist. Wie Android `moreForJump`.
    @State private var moreForJump = false
    /// Sprung zum Wirtschaftsdaten-Hinweis offen: bleibt, bis der Hinweis wirklich steht.
    @State private var macroFocus = false
    /// Der Hinweis ist gezeichnet (onAppear/onDisappear) — erst dann kann `scrollTo` ihn finden.
    @State private var macroHintOnScreen = false

    /// Ziel für `ScrollViewProxy.scrollTo`: oben (Status).
    private static let topId = "market_top"
    /// Ziel des Sprungs aus der Mitteilung «Wirtschaftstermine» (Hinweis unter «Mehr Marktdaten»).
    private static let macroId = "market_macro"

    init(viewModel: CycleViewModel) {
        self.viewModel = viewModel
        // Auf «Jetzt» zählt nur, was dort schon stand (Fear & Greed erst unter den Karten darüber)
        let shown = viewModel.revealed
        _revealedOnEntry = State(initialValue: shown.filter { CycleReveal.isShownOnNow($0, revealed: shown) })
    }

    var body: some View {
        ScrollViewReader { proxy in
            scrollContent
                // Sprung aus der Mitteilung «Wirtschaftstermine»: entscheiden, sobald die Daten stehen, und
                // scrollen, sobald der Hinweis steht (kein fester Wartezeit-Takt)
                .onChange(of: router.marketJump) { _, _ in handleJump() }
                .onChange(of: viewModel.revealed) { _, _ in
                    handleJump()
                    focusMacro(proxy)
                }
                .onChange(of: macroFocus) { _, _ in focusMacro(proxy) }
                .onChange(of: macroHintOnScreen) { _, _ in focusMacro(proxy) }
                .onAppear {
                    handleJump()
                    focusMacro(proxy)
                }
                // Tab «Markt» noch einmal getippt: nach oben
                .onChange(of: router.scrollToTop) { _, request in
                    guard request?.tab == .cycle else { return }
                    withAnimation { proxy.scrollTo(Self.topId, anchor: .top) }
                }
        }
        .background(AppColors.background.ignoresSafeArea())
        .refreshable { await viewModel.refreshAll() }
        .tabTitle(L("tab_market_phase"), icon: .cycle)
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
        // Unterseiten «Einordnung» und «Daten»: dasselbe ViewModel, zurück führt hierher
        .navigationDestination(item: $openSection) { section in
            MarketSectionPage(viewModel: viewModel, section: section)
        }
        // «Heute auffällig» → Coin nicht bei Binance: «Paar hinzufügen» über diesem Tab (G-13)
        .navigationDestination(isPresented: $router.showExchangeExplorer) {
            ExplorerScreen()
        }
        .onAppear { viewModel.onAppear() }
        // Tab bzw. Seite verlassen: das Aufklappen aus der Mitteilung gilt nur für diesen Besuch (wie Android)
        .onDisappear {
            moreForJump = false
            macroFocus = false
        }
        // Netz wieder da: Abgelaufenes nachladen (wie die Merkliste)
        .onChange(of: data.online) { _, online in
            if online { viewModel.onAppear() }
        }
        // Aktionsblatt wie in der Merkliste; «Was bewegt sich mit?» und «Alarme» als eigene Seiten mit Zurück
        .modifier(CycleCoinSheets(target: $coinTarget))
    }

    // MARK: - Inhalt («Jetzt» und die Zeilen zu den Unterseiten)

    private var scrollContent: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Statt der Überschrift «Jetzt» der Zustand wie in der Merkliste: «Alles aktuell ·
                // vor 2 Min.» bzw. während des Neuladens «Stand 14:05 · wird aktualisiert…».
                // Der Platz bleibt immer reserviert — darunter springt nichts.
                statusPill(refreshingSince: viewModel.refreshingSince, updatedAt: viewModel.pulse.value?.time)
                    .id(Self.topId)
                nowRegister
                // 3. «Mehr Marktdaten» (Standard zu, gemerkt): «Heute auffällig», der Wirtschaftsdaten-Hinweis
                // (nur bei einem Termin in ±2 h, sonst unter «Daten») und die zwei Unterseiten — deren Daten
                // laden schon hier (Öffnen sofort)
                if CycleReveal.isShownOnNow(.fearGreed, revealed: viewModel.revealed) {
                    MarketMoreToggle(expanded: moreBinding)
                    if moreShown {
                        moreContent
                    }
                }
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte, bis alles Sichtbare auf
                // «Jetzt» steht (verborgenes «Heute auffällig» zählt nicht)
                if CycleReveal.nowLoading(moreExpanded: moreShown, revealed: viewModel.revealed) {
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
    }

    /// 1. Eine Heldenkarte: Crypto Pulse mit «Kurz gesagt» als Körper (einzige betonte Karte). Beim Laden ein
    ///    form-gleicher Platzhalter, bei Fehler die kompakte Zeile — der Rest darunter erscheint trotzdem.
    /// 2. Darunter ruhig und kompakt: Fear & Greed (dieselbe Quelle wie auf «Stimmung & Zyklus») und
    ///    «Gewinner und Verlierer 24 h» (je drei, «Alle zeigen»; Tippen wie bei «Heute auffällig»).
    @ViewBuilder
    private var nowRegister: some View {
        revealSlot(.pulse) {
            // «Kurz gesagt» rechnet alle 30 s neu (wie Android): ein Satz verschwindet, sobald seine Daten veraltet sind
            TimelineView(.periodic(from: .now, by: 30)) { context in
                CryptoPulseCard(
                    market: viewModel.pulse,
                    fearGreed: viewModel.fearGreed.value,
                    gas: viewModel.gas.value,
                    plain: MarketPlainSummaryBlock.summary(viewModel,
                                                           now: Int64(context.date.timeIntervalSince1970 * 1000)),
                    onRetry: { viewModel.loadPulse(force: true) }
                )
            }
            .padding(.top, 4)
        }
        revealSlot(.fearGreed) {
            VStack(spacing: 0) {
                CycleFearGreedRow(state: viewModel.fearGreed, onRetry: refreshAll,
                                  first: false, stamp: viewModel.fearGreedStamp, compact: true)
                CycleMoversCard(market: viewModel.pulse, onOpen: { openCoin($0.symbol) })
                    .padding(.top, 12)
            }
        }
    }

    /// Inhalt von «Mehr Marktdaten». «Heute auffällig»: Tippen öffnet dasselbe wie ein Tipp in der Merkliste —
    /// das Aktionsblatt des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert); führt Binance das
    /// Paar nicht, die Suche auf der Seite «Paar hinzufügen» über diesem Tab.
    @ViewBuilder
    private var moreContent: some View {
        revealSlot(.unusual) {
            CycleUnusualCard(
                state: viewModel.unusual,
                onOpen: { openCoin($0.symbol) },
                onRetry: { viewModel.loadUnusual(force: true) }
            )
            .padding(.top, 12)
        }
        CycleMacroHintRow(events: viewModel.macroEvents, atTop: true)
            .id(Self.macroId)
            .onAppear { macroHintOnScreen = true }
            .onDisappear { macroHintOnScreen = false }
        MarketSectionRow(section: .context,
                         summary: MarketSectionRow.fearGreedSummary(viewModel.fearGreed.value)) {
            openSection = .context
        }
        TimelineView(.everyMinute) { context in
            MarketSectionRow(section: .data,
                             summary: MarketSectionRow.macroSummary(
                                viewModel.macroEvents,
                                now: Int64(context.date.timeIntervalSince1970 * 1000))) {
                openSection = .data
            }
        }
    }

    /// Tipp auf einen Coin aus «Heute auffällig» bzw. «Gewinner und Verlierer 24 h».
    private func openCoin(_ symbol: String) {
        if let id = watchId(for: symbol) {
            coinTarget = .stored(id)
        } else {
            Task { @MainActor in
                if let preview = await WatchPreview.watch(symbol: symbol) {
                    coinTarget = .preview(preview)
                } else {
                    // Über dem Markt-Tab, nicht über der Merkliste: zurück führt hierher (G-13)
                    router.explorerSearch = symbol
                    router.showExchangeExplorer = true
                }
            }
        }
    }

    /// Paar der Merkliste zu einem Coin: Spot zuerst (wie die Karte, USDT/USD vor anderen
    /// Quotes), sonst das Perpetual; nil = nicht beobachtet. Das Aktionsblatt zeigt dann genau
    /// dieses Paar (Futures-Paar: Futures-Kerzen).
    private func watchId(for symbol: String) -> Int64? {
        CandleSeries.pickWatch(
            // Nicht mehr gehandelte Paare zählen nicht als beobachtet (kein «Was bewegt sich mit?» auf alten Daten)
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

    /// Ein Teil des Tabs: erst da, wenn `CycleReveal` ihn freigibt und alles darüber auf «Jetzt»
    /// steht — vorher weder gezeichnet noch für VoiceOver vorhanden. Was beim Betreten schon stand
    /// bzw. beim Öffnen schon bereit war, erscheint ohne Animation.
    @ViewBuilder
    private func revealSlot<Content: View>(
        _ slot: CycleRevealSlot,
        @ViewBuilder content: () -> Content
    ) -> some View {
        if CycleReveal.isShownOnNow(slot, revealed: viewModel.revealed) {
            let instant = viewModel.revealInstant.union(revealedOnEntry)
            CycleRevealItem(animated: !instant.contains(slot), content: content())
        }
    }

    /// «Mehr Marktdaten» offen: gemerkte Einstellung oder nur für diesen Besuch (`moreForJump`).
    private var moreShown: Bool { data.settings.marketMoreExpanded || moreForJump }

    /// Auf-/Zuklappen schreibt die gemerkte Einstellung; Zuklappen beendet auch das Aufklappen aus der Mitteilung.
    private var moreBinding: Binding<Bool> {
        Binding(get: { moreShown }, set: { expanded in
            moreForJump = false
            data.settings.marketMoreExpanded = expanded
        })
    }

    /// Sprung aus der Mitteilung «Wirtschaftstermine», sobald die Daten stehen: Hinweis auf «Jetzt»
    /// (Termin in ±2 h; er steht unter «Mehr Marktdaten», das dafür nur für diesen Besuch aufgeht) — das
    /// Scrollen folgt in `focusMacro` —, sonst die Unterseite «Daten», die zum Hinweis scrollt (`MarketSectionPage`).
    private func handleJump() {
        guard router.marketJump == .macro, viewModel.revealed.contains(.coin) else { return }
        router.marketJump = nil
        let now = EpochTime.nowMillis
        let imminent = MacroCalendar.hint(viewModel.macroEvents, now: now)
            .map { MacroCalendar.isImminent($0, now: now) } ?? false
        if MarketSections.macroSection(imminent: imminent) == .now {
            openSection = nil
            moreForJump = true
            macroFocus = true
        } else {
            router.marketMacroOnData = true
            openSection = .data
        }
    }

    /// Offener Sprung: erst wenn der Hinweis wirklich steht (aufgeklappt, alles darüber erschienen, gezeichnet —
    /// auch nach dem Schliessen einer Unterseite), ihn an den oberen Rand scrollen; dann ist der Sprung erledigt.
    private func focusMacro(_ proxy: ScrollViewProxy) {
        guard macroFocus, macroHintOnScreen,
              CycleReveal.macroHintReady(moreExpanded: moreShown, revealed: viewModel.revealed) else { return }
        macroFocus = false
        withAnimation(reduceMotion ? nil : .default) { proxy.scrollTo(Self.macroId, anchor: .top) }
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

    /// Zustand oben im Markt-Tab wie in der Merkliste (statt einer Überschrift «Jetzt»): Punkt und
    /// Satz in einer Pille — grün «Alles aktuell · vor 2 Min.» (Stand von «Was gerade auffällt»),
    /// während still neu geladen wird neutral «Stand 14:05 · wird aktualisiert…». Ohne Netz (wie die
    /// Merkliste, `NetworkStatus`) neutral «Offline · Stand 14:05» — die Karten zeigen weiter den letzten
    /// Stand. Immer gleich hoch, auch ohne Stand — darunter springt nichts. Wie Android `MarketStatusPill`.
    private func statusPill(refreshingSince: Int64?, updatedAt: Int64?) -> some View {
        let online = data.online
        return TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int64(context.date.timeIntervalSince1970 * 1000)
            let text = Self.statusText(refreshingSince: refreshingSince, updatedAt: updatedAt, online: online, now: now)
            let tone: DataStatusTone = !online || refreshingSince != nil || text == nil ? .neutral : .fresh
            DataStatusPill(text: text ?? " ", tone: tone)
                .opacity(text == nil ? 0 : 1)
                .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: text)
                .accessibilityHidden(text == nil)
        }
    }

    /// Satz der Status-Pille; nil = noch kein Stand.
    private static func statusText(refreshingSince: Int64?, updatedAt: Int64?, online: Bool, now: Int64) -> String? {
        let asOf = (updatedAt ?? 0) > 0 ? updatedAt : refreshingSince
        if !online {
            if let asOf { return L("offline_status_since", stampText(asOf)) }
            return L("offline_status")
        }
        if let since = refreshingSince { return L("cycle_data_as_of", stampText(since)) }
        if let updatedAt, updatedAt > 0 { return L("watchlist_all_fresh", WatchlistTime.ago(updatedAt, now: now)) }
        return nil
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
