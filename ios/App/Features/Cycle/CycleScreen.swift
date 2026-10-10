import Accessibility
import SwiftUI

/// Eigener Tab für die Marktphase. Jetzt (Karten): Crypto Pulse, «Heute auffällig»;
/// Einordnung (Zeilen): Fear & Greed, Marktphase, Dominanz mit Altcoin-Saison, Halving;
/// Daten (Zeilen): Krypto-Markt, Gas, Wirtschaftsdaten (ausser kurz vor/nach einem Termin,
/// dann oben), Coin-Analyse. Die drei stehen als Register nebeneinander (`RegisterTabs`), immer
/// eines sichtbar; Tippen oder waagrecht Wischen wechselt (gilt für die App-Sitzung).
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
    @State private var revealedOnEntry: Int
    /// Beim Wechsel des Registers schon erschienene Teile stehen sofort da (kein zweites Einblenden).
    @State private var registerInstant: Int
    @Environment(\.layoutDirection) private var layoutDirection

    /// Ziele für `ScrollViewProxy.scrollTo`: oben (Status) bzw. Wirtschaftsdaten-Hinweis unter «Daten».
    private static let topId = "market_top"
    private static let macroId = "market_macro"
    /// Mindestweg für einen Wechsel des Registers durch Wischen (wie Android 56 dp).
    private static let swipeThreshold: Double = 56

    init(viewModel: CycleViewModel) {
        self.viewModel = viewModel
        _revealedOnEntry = State(initialValue: viewModel.revealed)
        _registerInstant = State(initialValue: viewModel.revealed)
    }

    var body: some View {
        ScrollViewReader { proxy in
            scrollContent
                // Waagrecht wischen wechselt das Register; senkrechtes Scrollen bleibt unberührt
                .simultaneousGesture(swipeGesture)
                // Nach einem Wechsel oben beginnen (Status und Register sichtbar)
                .onChange(of: viewModel.register) { _, _ in proxy.scrollTo(Self.topId, anchor: .top) }
                // Sprung aus der Mitteilung «Wirtschaftstermine»
                .onChange(of: router.marketJump) { _, _ in handleJump(proxy) }
                .onChange(of: viewModel.revealed) { _, _ in handleJump(proxy) }
                .onAppear { handleJump(proxy) }
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
        // Aktionsblatt wie in der Merkliste; «Warum?» und «Alarme» als eigene Seiten mit Zurück
        .modifier(CycleCoinSheets(target: $coinTarget))
    }

    // MARK: - Register («Jetzt» | «Einordnung» | «Daten»)

    private var scrollContent: some View {
        ScrollView {
            // Ohne Abstand zwischen den Teilen: die Zeilen von «Einordnung» und «Daten» stossen
            // aneinander (Trennlinien); die Karten von «Jetzt» bringen ihren Abstand selbst mit.
            VStack(spacing: 0) {
                // Statt der Überschrift «Jetzt» der Zustand wie in der Merkliste: «Alles aktuell ·
                // vor 2 Min.» bzw. während des Neuladens «Stand 14:05 · wird aktualisiert…».
                // Der Platz bleibt immer reserviert — darunter springt nichts. Gilt für alle Register.
                statusPill(refreshingSince: viewModel.refreshingSince, updatedAt: viewModel.pulse.value?.time)
                    .id(Self.topId)
                // Register wie auf der Seite «Paar hinzufügen»
                RegisterTabs(titles: MarketSection.allCases.map { L($0.titleKey) },
                             selected: viewModel.register.rawValue) { index in
                    select(MarketSection(rawValue: index) ?? MarketSections.defaultSection)
                }
                .padding(.top, Spacing.xs)
                registerContent
                // Eine ruhige Ladezeile unter der letzten sichtbaren Karte des Registers, bis alle stehen
                if MarketSections.loading(viewModel.register, revealed: viewModel.revealed) {
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

    /// Inhalt des gewählten Registers; die anderen werden nicht aufgebaut (ihre Daten laden weiter).
    @ViewBuilder
    private var registerContent: some View {
        switch viewModel.register {
        case .now: nowRegister
        case .context: contextRegister
        case .data: dataRegister
        }
    }

    /// 1. Jetzt: «Crypto Pulse», «Heute auffällig» — als Karten.
    ///    Beim Laden form-gleiche Platzhalter, bei Fehler eine kompakte Zeile.
    @ViewBuilder
    private var nowRegister: some View {
        revealSlot(.pulse) {
            VStack(spacing: 12) {
                // Wirtschaftsdaten nur bei einem Termin in ±2 h hier oben, sonst unter «Daten»
                CycleMacroHintRow(events: viewModel.macroEvents, atTop: true)
                CryptoPulseCard(
                    market: viewModel.pulse,
                    fearGreed: viewModel.fearGreed.value,
                    gas: viewModel.gas.value,
                    onRetry: { viewModel.loadPulse(force: true) }
                )
            }
            .padding(.top, 4)
        }
        // «Heute auffällig»: Tippen öffnet dasselbe wie ein Tipp in der Merkliste — das
        // Aktionsblatt des Paars, sonst seine Vorschau (Binance COIN/USDT, nicht gespeichert);
        // führt Binance das Paar nicht, wie bisher die Suche auf der Seite «Paar hinzufügen»
        revealSlot(.unusual) {
            CycleUnusualCard(
                state: viewModel.unusual,
                onOpen: openUnusual,
                onRetry: { viewModel.loadUnusual(force: true) }
            )
            .padding(.top, 12)
        }
    }

    /// Tipp auf eine Zeile von «Heute auffällig».
    private func openUnusual(_ row: UnusualRow) {
        if let id = watchId(for: row.symbol) {
            coinTarget = .stored(id)
        } else {
            Task { @MainActor in
                if let preview = await WatchPreview.watch(symbol: row.symbol) {
                    coinTarget = .preview(preview)
                } else {
                    router.explorerSearch = row.symbol
                    router.openExplorer()
                }
            }
        }
    }

    /// 2. Einordnung — Zeilen ohne Karte: Fear & Greed, Marktphase, Dominanz, Altcoin-Saison,
    ///    Zyklus/Halving. Tippen klappt die Details einer Zeile auf.
    private var contextRegister: some View {
        VStack(spacing: 0) {
            revealSlot(.fearGreed) {
                CycleFearGreedRow(state: viewModel.fearGreed, onRetry: refreshAll, divider: false,
                                  stamp: viewModel.fearGreedStamp)
            }
            revealSlot(.phase) {
                MarketPhaseRow(
                    cycle: viewModel.cycle,
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
                CycleHalvingRow(cycle: viewModel.cycle, history: viewModel.history, onRetry: refreshAll)
            }
        }
    }

    /// 3. Daten — Zeilen: Krypto-Markt (Marktkapitalisierung, Volumen), Gas, Wirtschaftsdaten, Coin.
    private var dataRegister: some View {
        VStack(spacing: 0) {
            revealSlot(.marketTotals) {
                CycleMarketCapRow(
                    state: viewModel.globalMarket,
                    currency: data.settings.portfolioCurrency,
                    onRetry: refreshAll,
                    divider: false,
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

    /// Paar der Merkliste zu einem Coin: Spot zuerst (wie die Karte, USDT/USD vor anderen
    /// Quotes), sonst das Perpetual; nil = nicht beobachtet. Das Aktionsblatt zeigt dann genau
    /// dieses Paar (Futures-Paar: Futures-Kerzen).
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
    /// noch für VoiceOver vorhanden. Was beim Betreten schon stand, beim Öffnen schon bereit war
    /// bzw. beim Wechsel des Registers schon erschienen war, erscheint ohne Animation.
    @ViewBuilder
    private func revealSlot<Content: View>(
        _ slot: CycleRevealSlot,
        @ViewBuilder content: () -> Content
    ) -> some View {
        if viewModel.revealed > slot.rawValue {
            let instantThrough = max(viewModel.revealInstant, revealedOnEntry, registerInstant)
            CycleRevealItem(animated: slot.rawValue >= instantThrough, content: content())
        }
    }

    /// Register wählen (gilt für die App-Sitzung); VoiceOver sagt den neuen Inhalt an.
    private func select(_ section: MarketSection) {
        guard section != viewModel.register else { return }
        registerInstant = viewModel.revealed
        viewModel.register = section
        AccessibilityNotification.Announcement(L(section.titleKey)).post()
    }

    /// Waagrecht wischen (deutlich mehr waagrecht als senkrecht): nach links das nächste Register,
    /// nach rechts das vorige (Rechts-nach-links umgekehrt). Globale Koordinaten, damit die
    /// Richtung nicht von der gespiegelten Darstellung abhängt. Die Karten im Tab reagieren nur auf
    /// Tippen — keine Wischgesten darin, die sich in die Quere kämen.
    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 24, coordinateSpace: .global)
            .onEnded { value in
                let dx = Double(value.translation.width)
                let dy = Double(value.translation.height)
                guard abs(dx) > abs(dy) * 1.5 else { return }
                let rtl = layoutDirection == .rightToLeft
                if let target = MarketSections.swipeTarget(viewModel.register, dx: dx,
                                                           threshold: Self.swipeThreshold, rtl: rtl) {
                    select(target)
                }
            }
    }

    /// Sprung aus der Mitteilung «Wirtschaftstermine»: Register mit dem Hinweis wählen («Jetzt» bei
    /// einem Termin in ±2 h, sonst «Daten») und hinscrollen, sobald er erschienen ist.
    private func handleJump(_ proxy: ScrollViewProxy) {
        guard router.marketJump == .macro else { return }
        let now = TimeUtils.nowMillis
        let imminent = MacroCalendar.hint(viewModel.macroEvents, now: now)
            .map { MacroCalendar.isImminent($0, now: now) } ?? false
        let target = MarketSections.macroSection(imminent: imminent)
        select(target)
        if target == .now {
            proxy.scrollTo(Self.topId, anchor: .top)
            router.marketJump = nil
        } else if viewModel.revealed > CycleRevealSlot.coin.rawValue {
            router.marketJump = nil
            Task { @MainActor in
                // Erst nach dem Aufbau des Registers (und nach dem Sprung nach oben beim Wechsel)
                try? await Task.sleep(nanoseconds: 150_000_000)
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.3)) {
                    proxy.scrollTo(Self.macroId, anchor: .center)
                }
            }
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

    /// Zustand oben im Markt-Tab wie in der Merkliste (statt einer Überschrift «Jetzt»): Punkt und
    /// Satz in einer Pille — grün «Alles aktuell · vor 2 Min.» (Stand von «Was gerade auffällt»),
    /// während still neu geladen wird neutral «Stand 14:05 · wird aktualisiert…». Immer gleich hoch,
    /// auch ohne Stand — darunter springt nichts. Wie Android `MarketStatusPill`.
    private func statusPill(refreshingSince: Int64?, updatedAt: Int64?) -> some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = Int64(context.date.timeIntervalSince1970 * 1000)
            let text: String? = {
                if let since = refreshingSince { return L("cycle_data_as_of", Self.stampText(since)) }
                if let updatedAt, updatedAt > 0 { return L("watchlist_all_fresh", WatchlistTime.ago(updatedAt, now: now)) }
                return nil
            }()
            let tone = refreshingSince != nil || text == nil ? AppColors.onSurfaceVariant : PriceColors.ok
            HStack(spacing: 8) {
                Circle().fill(tone).frame(width: 8, height: 8)
                Text(text ?? " ")
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, Spacing.sm)
            .background(tone.opacity(0.12), in: Capsule())
            .opacity(text == nil ? 0 : 1)
            .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: text)
            .accessibilityElement(children: .combine)
            .accessibilityHidden(text == nil)
        }
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

// MARK: - Aktionsblatt aus «Heute auffällig»

/// Aktionsblatt und was danach kommt für «Heute auffällig» — dasselbe Blatt wie ein Tipp auf die
/// Zeile in der Merkliste (`WatchActionsSheet`), auch die Abläufe danach wie dort
/// (`WatchlistScreen.withSheets`): «Warum?» und «Alarme» als eigene Seiten, zurück öffnet das
/// Blatt wieder; Löschen sofort mit «Rückgängig» im Banner; Portfolio erst nach dem Entsperren.
/// Vorschau (Coin nicht in der Merkliste): Kurs aus einer Ticker-Abfrage ohne Speichern,
/// «Zur Merkliste hinzufügen» legt das Paar wie der Hinzufügen-Tab an. Wie Android `MarketCoinSheets`.
@MainActor
struct CycleCoinSheets: ViewModifier {
    @Binding var target: CycleCoinTarget?

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject private var lock = AppLock.shared
    /// Was nach dem Schliessen des Blatts passieren soll (wie in der Merkliste).
    @State private var pendingWhy: Int64?
    @State private var pendingWhyPreview: Watch?
    @State private var pendingAlarms: Int64?
    @State private var pendingDelete: Watch?
    @State private var pendingPortfolio: Int64?
    /// Aus dem Blatt zu «Alarme» bzw. «Warum?» gewechselt: Zurück öffnet es wieder.
    @State private var returnTo: CycleCoinTarget?
    @State private var whyFor: CycleWhyTarget?
    @State private var whyPreview: Watch?
    @State private var alarmsFor: Int64?
    @State private var portfolioDraft: PortfolioTxDraft?
    /// «… entfernt» mit «Rückgängig» nach «Löschen».
    @State private var banner: WatchlistBannerMessage?

    func body(content: Content) -> some View {
        content
            .sheet(item: $target, onDismiss: afterSheet) { target in
                sheet(for: target)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .environment(\.changeView, data.changeView())
                    // Eine feste Höhe wie in der Merkliste: Chart und Kennzahlen laden nach
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(28)
                    .presentationBackground(AppColors.background)
            }
            .navigationDestination(item: $whyFor) { target in
                WatchlistWhySheet(watchId: target.id)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
            }
            .navigationDestination(item: $whyPreview) { watch in
                WatchlistWhySheet(watchId: WatchPreview.watchId, preview: watch)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
            }
            .navigationDestination(item: $alarmsFor) { id in
                AlarmsScreen(watchId: id)
            }
            // Zurück aus «Warum?» bzw. «Alarme»: Blatt wieder öffnen, falls von dort gekommen
            .onChange(of: whyFor) { _, value in if value == nil { reopen() } }
            .onChange(of: whyPreview) { _, value in if value == nil { reopen() } }
            .onChange(of: alarmsFor) { _, value in if value == nil { reopen() } }
            // Wieder gesperrt (Hintergrund-Limit), während das Erfassen-Blatt offen ist: schliessen
            .onChange(of: lock.locked) { _, locked in
                if locked { portfolioDraft = nil }
            }
            .sheet(item: $portfolioDraft) { draft in
                PortfolioTxSheet(initial: draft)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
                    .presentationCornerRadius(28)
                    .presentationBackground(AppColors.background)
            }
            .overlay(alignment: .bottom) {
                WatchlistBanner(message: $banner) { deleted in
                    withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(deleted) }
                }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
            }
    }

    private func sheet(for target: CycleCoinTarget) -> some View {
        let storedId: Int64? = { if case .stored(let id) = target { return id } else { return nil } }()
        let preview: Watch? = { if case .preview(let watch) = target { return watch } else { return nil } }()
        let id = storedId ?? WatchPreview.watchId
        return WatchActionsSheet(
            watchId: id,
            // Wie in der Merkliste: ⚡ an «Warum?», wenn das Paar gerade Signale hat
            hasActivity: data.watch(id)?.isNotTraded == false &&
                !WatchlistActivity.active(data.activityReports[id], now: TimeUtils.nowMillis,
                                          sensitivity: data.settings.activitySensitivity).isEmpty,
            onOpenAlarms: { pendingAlarms = $0 },
            onDelete: { pendingDelete = $0 },
            onWhy: { pendingWhy = $0 },
            onAddToPortfolio: { pendingPortfolio = $0 },
            preview: preview,
            onWhyPreview: { pendingWhyPreview = $0 }
        )
    }

    /// Nach dem Schliessen des Blatts: «Warum», Alarme, Löschen oder Portfolio — wie in der Merkliste.
    private func afterSheet() {
        if let id = pendingWhy {
            pendingWhy = nil
            returnTo = .stored(id)
            whyFor = CycleWhyTarget(id: id)
        }
        if let watch = pendingWhyPreview {
            pendingWhyPreview = nil
            returnTo = .preview(watch)
            whyPreview = watch
        }
        if let id = pendingAlarms {
            pendingAlarms = nil
            returnTo = .stored(id)
            alarmsFor = id
        }
        if let watch = pendingDelete {
            pendingDelete = nil
            delete(watch)
        }
        if let id = pendingPortfolio {
            pendingPortfolio = nil
            // Portfolio-Sperre: Das Erfassen-Blatt zeigt Bestände — erst nach dem Entsperren
            Task { @MainActor in
                let open = await lock.requireUnlock(PortfolioLockPolicy.quickAddNeedsUnlock(locked:))
                guard open, let watch = data.watch(id) else { return }
                portfolioDraft = WatchlistScreen.makePortfolioDraft(for: watch)
            }
        }
    }

    /// Zurück aus «Alarme» bzw. «Warum?»: Blatt wieder öffnen (gelöschtes Paar: nicht).
    private func reopen() {
        guard let back = returnTo else { return }
        returnTo = nil
        if case .stored(let id) = back, data.watch(id) == nil { return }
        target = back
    }

    /// Wie nach links wischen in der Merkliste: sofort löschen, «Rückgängig» im Banner, Ansage.
    private func delete(_ watch: Watch) {
        var result: DeletedWatch?
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { result = data.deleteForUndo(watch) }
        guard let deleted = result else { return }
        let text = L("watchlist_removed", deleted.watch.displayPair)
        banner = WatchlistBannerMessage(text: text, icon: "trash", undo: deleted)
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }
}
