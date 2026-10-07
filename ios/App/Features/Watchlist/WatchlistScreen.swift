import Accessibility
import SwiftUI
import UIKit

/// Ziel des Aktionsblatts (Paar-Id).
private struct WatchlistSheetTarget: Identifiable, Equatable {
    let id: Int64
}

/// Merkliste — wie `WatchlistScreen.kt`.
///
/// Tippen öffnet die Aktionen sofort (kein Doppeltippen), lange
/// drücken und ziehen sortiert (innerhalb Favoriten bzw. übrige). Nach links
/// wischen löscht (mit «Rückgängig»), nach rechts wischen schaltet den Favoriten. Nach unten
/// ziehen aktualisiert alles. Gibt es Gruppen, filtern Chips oben die Liste;
/// sortiert wird dann nur unter den sichtbaren Paaren. Enthält keinen `NavigationStack`.
struct WatchlistScreen: View {
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    /// Portfolio-Sperre: «Zum Portfolio hinzufügen» erst nach dem Entsperren.
    @ObservedObject private var lock = AppLock.shared
    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Mit VoiceOver keine Wisch-Aktionen — dort gibt es dieselben Aktionen im Rotor.
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    // Uhr für «vor 2 Min.» — alle 30 Sekunden neu
    @State private var now: Int64 = TimeUtils.nowMillis

    @State private var actionsFor: WatchlistSheetTarget?
    /// Paar, dessen «Warum bewegt sich das?» offen ist.
    @State private var whyFor: WatchlistSheetTarget?
    @State private var pendingWhy: Int64?
    @State private var pendingAlarms: Int64?
    @State private var pendingDelete: Watch?
    /// «Zum Portfolio hinzufügen»: Paar, dessen Erfassen-Blatt nach dem Aktionsblatt öffnet.
    @State private var pendingPortfolio: Int64?
    @State private var portfolioDraft: PortfolioTxDraft?
    @State private var askClearAll = false
    /// «Nicht gehandelte Paare entfernen»: Rückfrage offen?
    @State private var askRemoveNotTraded = false
    @State private var showReport = false
    @State private var showOverview = false
    @State private var alarmsFor: Int64?
    @State private var editMode: EditMode = .inactive
    @State private var highlightedId: Int64?
    @State private var reorderTick = 0
    /// Suche in der Merkliste (Lupe rechts neben dem Status) — wird nicht gespeichert.
    @State private var searching = false
    @State private var query = ""
    @FocusState private var searchFocused: Bool
    /// «Gruppe bearbeiten» (lange auf den Chip drücken) bzw. neue Gruppe über «+».
    @State private var groupEdit: WatchGroupEditTarget?
    @State private var askNewGroup = false
    @State private var newGroupName = ""
    /// «≈ Umrechnung»: Faktor je Quote-Währung (Grossbuchstaben) in die Umrechnungswährung.
    @State private var convertRates: [String: Double] = [:]
    /// Start-Tipp: frisch geladene Liste (Top 5); nil = Zwischenspeicher bzw. Ersatzliste.
    @State private var starterCoins: [StarterCoin]?
    /// «Erst-Hinzufügen»: Zeilen mit laufendem Moment (Watch-Id → Position für den Versatz).
    @State private var celebrating: [Int64: Int] = [:]
    /// Banner unten: «… wird jetzt überwacht», «… entfernt» (mit «Rückgängig»), Favorit.
    @State private var banner: WatchlistBannerMessage?
    /// Eine Erfolgs-Haptik je Hinzufügen (nicht je Zeile).
    @State private var firstAddHaptic = 0
    /// Haptik beim Wischen: Favorit (leicht) bzw. Löschen.
    @State private var swipeFavoriteTick = 0
    @State private var swipeDeleteTick = 0
    /// «Anpassen» in der Aktivitätskarte: Markt-Meldungen (Empfindlichkeit) öffnen.
    @State private var showActivitySettings = false
    /// Sprungknopf «Zum Anfang» / «Zum Ende»: sichtbare Zeilen, Richtung, Scrollzustand.
    @State private var jumpTracker = WatchlistJumpTracker()
    @State private var jumpDown = true
    @State private var jumpScrolled = false
    @State private var jumpHideTask: Task<Void, Never>?
    /// iOS 18: Liste wird gerade gescrollt (Scrollphase nicht «idle»).
    @State private var scrollActive = false

    init() {}

    private var sorting: Bool { editMode.isEditing }

    var body: some View {
        let watches = data.watches
        // Sichtbar: alle Paare oder nur die der gewählten Gruppe
        let visible = data.visibleWatches
        ScrollViewReader { proxy in
            Group {
                if watches.isEmpty {
                    emptyState
                } else {
                    list(visible, proxy: proxy)
                }
            }
            .onChange(of: router.focusWatchId, initial: true) { _, id in
                guard let id else { return }
                router.focusWatchId = nil
                focus(on: id, proxy: proxy)
            }
        }
        // «Erst-Hinzufügen»: abholen, sobald die Merkliste sichtbar ist
        .onChange(of: data.addCelebration?.id, initial: true) { _, _ in takeCelebration() }
        .onChange(of: router.tab) { _, _ in takeCelebration() }
        .overlay(alignment: .bottom) {
            // Animation nur für den Banner, nicht für die Liste darunter
            WatchlistBanner(message: $banner) { deleted in undoDelete(deleted) }
                .animation(reduceMotion ? nil : .spring(duration: 0.35), value: banner)
        }
        .sensoryFeedback(.success, trigger: firstAddHaptic)
        .sensoryFeedback(.impact(weight: .light), trigger: swipeFavoriteTick)
        .sensoryFeedback(.impact(weight: .medium), trigger: swipeDeleteTick)
        .background(AppColors.background.ignoresSafeArea())
        // Keine Leiste mit Logo und App-Namen mehr (kostete eine ganze Zeile): ihre Knöpfe
        // stehen rechts in der Gruppen-Zeile oben in der Liste (`headerRow`). Der Titel
        // bleibt für den Zurück-Knopf und VoiceOver; den Abstand zur Statusleiste gibt die
        // sichere Zone der Liste.
        .navigationTitle(L("tab_watchlist"))
        .toolbar(.hidden, for: .navigationBar)
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                now = TimeUtils.nowMillis
            }
        }
        // Umrechnungsfaktoren: neu bei geänderter Einstellung oder anderen Quote-Währungen,
        // sonst höchstens alle 60 s.
        .task(id: conversionKey) {
            await refreshConversion()
        }
        .onChange(of: data.refreshing) { _, _ in now = TimeUtils.nowMillis }
        .onChange(of: data.lastRefreshMillis) { _, _ in now = TimeUtils.nowMillis }
        .onChange(of: router.showAlarmsOverview, initial: true) { _, show in
            guard show else { return }
            router.showAlarmsOverview = false
            actionsFor = nil
            showOverview = true
        }
        // «Alarm setzen» aus dem Hinzufügen-Tab: Alarme des Paars öffnen (wie aus dem Aktionsblatt)
        .onChange(of: router.openAlarmsWatchId, initial: true) { _, id in
            guard let id else { return }
            router.openAlarmsWatchId = nil
            actionsFor = nil
            showOverview = false
            alarmsFor = id
        }
        .onChange(of: visible.count) { _, count in
            if count < 2 { editMode = .inactive }
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: reorderTick)
        // Aktionen eines Paars als Blatt von unten
        .sheet(item: $actionsFor, onDismiss: afterSheet) { target in
            WatchActionsSheet(
                watchId: target.id,
                // Nicht mehr gehandelt: kein ⚡ (das Blatt zeigt dann auch kein «Warum?»)
                hasActivity: data.watch(target.id)?.isNotTraded != true &&
                    !WatchlistActivity.active(data.activityReports[target.id], now: TimeUtils.nowMillis,
                                                       sensitivity: data.settings.activitySensitivity).isEmpty,
                onOpenAlarms: { pendingAlarms = $0 },
                onDelete: { pendingDelete = $0 },
                onWhy: { pendingWhy = $0 },
                onAddToPortfolio: { pendingPortfolio = $0 }
            )
            .environmentObject(data)
            .environment(\.appAccent, accent)
            // Eine feste Höhe: Chart und Kennzahlen laden nach, das Blatt wechselt nie die Stufe
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        // «Warum bewegt sich das?» — gleich in voller Höhe: Laden und «Details» lassen
        // nur den Inhalt im ScrollView wachsen, das Blatt springt nicht
        .sheet(item: $whyFor) { target in
            WatchlistWhySheet(watchId: target.id)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        // Wieder gesperrt (Hintergrund-Limit), während das Erfassen-Blatt offen ist: schliessen
        .onChange(of: lock.locked) { _, locked in
            if locked { portfolioDraft = nil }
        }
        // Erfassen-Blatt aus der Merkliste: Coin (und Kurs, falls in USD) vorbelegt
        .sheet(item: $portfolioDraft) { draft in
            PortfolioTxSheet(initial: draft)
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
        }
        // «Gruppe bearbeiten»: alle Paare mit Häkchen
        .sheet(item: $groupEdit) { target in
            WatchGroupEditSheet(
                groupName: target.name,
                isNew: target.isNew,
                initialMembers: Set(data.watches.filter { $0.groupName == target.name }.map(\.id))
            )
            .environmentObject(data)
            .environment(\.appAccent, accent)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            .presentationBackground(AppColors.background)
        }
        .alert(L("group_add"), isPresented: $askNewGroup) {
            TextField(L("group_name"), text: $newGroupName)
                .textInputAutocapitalization(.words)
            Button(L("group_next")) { startNewGroup() }
            Button(L("action_cancel"), role: .cancel) {}
        }
        .sheet(isPresented: $showReport) {
            WatchlistReportSheet(report: data.lastRefreshReport)
                .environment(\.appAccent, accent)
                // Eine feste Höhe: der Bericht kann lang sein, kein Stufenwechsel
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        // Nicht mehr gehandelte Paare (ganze Merkliste) samt Alarmen entfernen, mit «Rückgängig»
        .alert(L("watchlist_remove_not_traded_title"), isPresented: $askRemoveNotTraded) {
            Button(L("watchlist_remove_not_traded_action"), role: .destructive) { removeNotTraded() }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("watchlist_remove_not_traded_confirm", count: data.notTradedCount))
        }
        .alert(L("watchlist_clear"), isPresented: $askClearAll) {
            Button(L("watchlist_clear"), role: .destructive) {
                withAnimation { data.deleteAll() }
            }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("watchlist_clear_confirm"))
        }
        .navigationDestination(isPresented: $showOverview) {
            AlarmsOverviewScreen()
                // Leiste hier ausdrücklich zeigen (in der Merkliste ist sie ausgeblendet)
                .toolbar(.visible, for: .navigationBar)
        }
        .navigationDestination(item: $alarmsFor) { id in
            AlarmsScreen(watchId: id)
                .toolbar(.visible, for: .navigationBar)
        }
        .navigationDestination(isPresented: $showActivitySettings) {
            MarketAlertsSettingsPage()
                .toolbar(.visible, for: .navigationBar)
        }
    }

    /// Nach dem Namen das Blatt öffnen; gibt es die Gruppe schon, wird sie bearbeitet.
    private func startNewGroup() {
        guard let clean = WatchGroupNames.clean(newGroupName) else { return }
        let groups = data.watchGroups
        let name = WatchGroupNames.canonical(clean, in: groups)
        let target = WatchGroupEditTarget(name: name, isNew: !groups.contains(name))
        // Erst nach dem Schliessen der Eingabe öffnen
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            groupEdit = target
        }
    }

    // MARK: Liste

    /// `watches`: die sichtbaren Paare (ggf. nur die der gewählten Gruppe).
    private func list(_ watches: [Watch], proxy: ScrollViewProxy) -> some View {
        // Aktive Suche filtert zusätzlich zur gewählten Gruppe
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtering = searching && !trimmed.isEmpty
        let found = filtering ? watches.filter { WatchlistSearch.matches($0, query: trimmed) } : watches
        let favorites = found.filter(\.favorite)
        let others = found.filter { !$0.favorite }
        let counts = data.activeAlarmCounts
        let groups = data.watchGroups
        let selectedGroup = data.selectedWatchlistGroup
        // Ungewöhnliche Aktivität: nur noch gültige Signale, je Paar stärkstes zuerst —
        // nach der gewählten Empfindlichkeit (gleiche Schwellen wie die Mitteilungen)
        let sensitivity = data.settings.activitySensitivity
        // Nicht mehr gehandelte Paare: kein ⚡, nicht in der Karte (auch bevor die Auswertung aufräumt)
        let reports = NotTraded.withoutIds(data.activityReports, NotTraded.ids(data.watches))
        let signals = WatchlistActivity.activeSignals(reports, now: now, sensitivity: sensitivity)
        let hot = WatchlistActivity.hot(watches, signals: signals)
        // Puls ganz oben in der Liste: nur die sichtbare Gruppe; nicht beim Suchen und Sortieren
        let pulse = sorting || searching ? nil : WatchlistPulseStats.make(watches)
        // Während der Suche kein Sortieren — die Reihenfolge wäre mehrdeutig.
        let moveFavorites: ((IndexSet, Int) -> Void)? = searching ? nil : { from, to in
            var moved = favorites
            moved.move(fromOffsets: from, toOffset: to)
            commitOrder(moved + others)
        }
        let moveOthers: ((IndexSet, Int) -> Void)? = searching ? nil : { from, to in
            var moved = others
            moved.move(fromOffsets: from, toOffset: to)
            commitOrder(favorites + moved)
        }
        // Sprungknopf: nur bei mehr als 30 sichtbaren (ggf. gesuchten) Paaren, nicht beim Sortieren;
        // mit VoiceOver immer da, damit er erreichbar bleibt
        let rows = favorites + others
        let jumpEligible = WatchlistJump.eligible(pairs: rows.count, sorting: sorting)
        let jumpVisible = jumpEligible && (jumpScrolled || voiceOver)
        return List {
            // Kopfzeile der Liste (ersetzt die frühere Leiste mit Logo und App-Namen):
            // kleines Logo links, Gruppen-Chips scrollen dahinter, Knöpfe fest rechts.
            headerRow(watches, groups: groups, selectedGroup: selectedGroup)
                .plainRow(top: 0, bottom: 0)
                .id(WatchlistJump.topId)

            // «▲ 7 steigen · ▼ 3 fallen · Ø +1.80%» — direkt unter der Kopfzeile
            if let pulse {
                WatchlistPulseLine(stats: pulse)
                    .plainRow(top: 4, bottom: 2)
                    .transition(.opacity)
            }

            // Status links, Lupe rechts — beim Suchen wird die Zeile zum Suchfeld.
            ZStack {
                if searching {
                    searchField
                        .transition(.opacity.combined(with: .scale(scale: 0.97, anchor: .trailing)))
                } else {
                    statusRow(watches)
                        .transition(.opacity)
                }
            }
            .frame(minHeight: WatchlistSearch.rowHeight)
            .plainRow(top: 4, bottom: 2)

            // «⚡ Hier passiert gerade etwas» — nur mit Signalen in der aktuellen Ansicht,
            // beim Suchen ausgeblendet
            if !hot.isEmpty && !sorting && !searching {
                WatchlistActivityCard(
                    hot: hot,
                    limit: sensitivity.maxCardCoins,
                    onOpen: { watch in whyFor = WatchlistSheetTarget(id: watch.id) },
                    onAdjust: { showActivitySettings = true }
                )
                .plainRow(top: 4, bottom: 2)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            if sorting {
                Text(L("watchlist_sort_hint"))
                    .font(.footnote)
                    .foregroundStyle(accent.primary)
                    .padding(.horizontal, 4)
                    .plainRow(top: 2, bottom: 2)
            } else if !data.settings.gestureHintSeen {
                // Einmaliger Gesten-Hinweis, bleibt bis er weggeklickt wird.
                gestureHint
                    .plainRow(top: 2, bottom: 4)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }

            // Keine Treffer für die Suche
            if filtering && found.isEmpty {
                Text(L("watchlist_search_empty", trimmed))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .plainRow(top: 2, bottom: 2)
            }

            // Zwei Abteilungen: Gezogen wird nur innerhalb der eigenen Abteilung,
            // Favoriten bleiben oben. In der gefilterten Ansicht tauschen nur die
            // sichtbaren Paare ihre Plätze (`AppData.reorder`).
            ForEach(favorites) { watch in
                row(watch, counts: counts, signals: signals, section: favorites)
            }
            .onMove(perform: moveFavorites)

            ForEach(others) { watch in
                row(watch, counts: counts, signals: signals, section: others)
            }
            .onMove(perform: moveOthers)

            // Mit Sprungknopf unten mehr Platz, damit er die letzte Zeile nicht verdeckt
            Color.clear
                .frame(height: jumpEligible ? 12 + WatchlistJump.buttonSize + WatchlistJump.margin : 12)
                .plainRow(top: 0, bottom: 0)
                .id(WatchlistJump.endId)
                .accessibilityHidden(true)
        }
        .listStyle(.plain)
        .modifier(WatchlistScrollPhaseModifier { scrolling in scrollPhaseChanged(scrolling) })
        .overlay(alignment: .bottomTrailing) {
            // Ein- und Ausblenden nur hier animiert, nicht die Liste darunter
            ZStack {
                if jumpVisible {
                    WatchlistJumpButton(down: jumpDown) { jump(rows: rows, proxy: proxy) }
                        .transition(.opacity)
                }
            }
            .padding(.trailing, WatchlistJump.margin)
            // Über dem Banner («… entfernt», «Rückgängig»), solange er steht
            .padding(.bottom, banner == nil ? WatchlistJump.margin : WatchlistJump.margin + 64)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: jumpVisible)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: banner == nil)
        }
        .scrollContentBackground(.hidden)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .background(AppColors.background)
        .environment(\.editMode, $editMode)
        .refreshable { await data.refreshAll() }
        .animation(.spring(duration: 0.35), value: watches.map(\.id))
        .animation(.spring(duration: 0.35), value: groups)
        .animation(.easeInOut(duration: 0.25), value: data.settings.gestureHintSeen)
        .animation(.easeInOut(duration: 0.25), value: sorting)
        .animation(.spring(duration: 0.35), value: hot.map(\.id))
        .animation(.easeInOut(duration: 0.25), value: searching)
        .animation(.easeInOut(duration: 0.2), value: found.map(\.id))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: pulse == nil)
    }

    /// `section`: sichtbare Abteilung (Favoriten bzw. übrige) für «Nach oben/unten» in VoiceOver.
    private func row(_ watch: Watch, counts: [Int64: Int], signals: [Int64: [ActivitySignal]],
                     section: [Watch]) -> some View {
        let index = section.firstIndex(where: { $0.id == watch.id })
        let canReorder = !searching && index != nil
        return WatchlistRow(
            watch: watch,
            alarmCount: counts[watch.id] ?? 0,
            now: now,
            staleAfter: data.staleAfterMillis,
            outdatedAfter: data.outdatedAfterMillis,
            loading: data.refreshingWatchIds.contains(watch.id) || (data.refreshing && watch.lastPrice == nil),
            highlighted: highlightedId == watch.id,
            sorting: sorting,
            hasActivity: signals[watch.id] != nil,
            converted: WatchlistConversion.text(watch, target: convertTarget, rates: convertRates),
            // Nicht mehr gehandelt: kein Mini-Chart
            sparklineEnabled: data.settings.watchlistSparkline && !watch.isNotTraded,
            celebrationIndex: celebrating[watch.id],
            onTap: { actionsFor = WatchlistSheetTarget(id: watch.id) },
            onToggleFavorite: { toggleFavorite(watch) },
            onActivity: { if !sorting { whyFor = WatchlistSheetTarget(id: watch.id) } }
        )
        // VoiceOver: verschieben wie per Ziehen (gleiche Abteilung, Reihenfolge gespeichert);
        // Löschen wie nach links wischen (mit «Rückgängig»). «Favorit» hat die Zeile selbst.
        .accessibilityActions {
            if canReorder, let index {
                if index > 0 {
                    Button(L("a11y_move_up")) { moveByAccessibility(watch, .up) }
                }
                if index < section.count - 1 {
                    Button(L("a11y_move_down")) { moveByAccessibility(watch, .down) }
                }
            }
            if !sorting {
                Button(L("action_delete")) { swipeDelete(watch) }
            }
        }
        // Wischen: rechts (führend) = Favorit, schnappt zurück; links = Löschen ohne
        // Rückfrage. Rechts-nach-links-Sprachen spiegeln das von selbst. Nicht im
        // Sortiermodus und nicht mit VoiceOver (dort die Aktionen oben).
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            if swipeEnabled {
                Button { swipeFavorite(watch) } label: {
                    Label(L(watch.favorite ? "favorite_remove" : "favorite_add"),
                          systemImage: watch.favorite ? "star" : "star.fill")
                }
                .tint(accent.primary)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if swipeEnabled {
                Button(role: .destructive) { swipeDelete(watch) } label: {
                    Label(L("action_delete"), systemImage: "trash")
                }
                // Systemrot: weisse Schrift bleibt auch im Dunkelmodus lesbar (AppColors.error ist dort hell)
                .tint(.red)
            }
        }
        // Sprungknopf: welche Zeilen sichtbar sind (Richtung) und ob gescrollt wird
        .onAppear { rowVisibilityChanged(watch.id, visible: true) }
        .onDisappear { rowVisibilityChanged(watch.id, visible: false) }
        .id(watch.id)
        .plainRow(top: 4, bottom: 4)
    }

    // MARK: Sprungknopf

    /// Sichtbare Paare in Listenreihenfolge (Gruppe, Suche, Favoriten zuerst) — wie `list(_:proxy:)`.
    private func currentRows() -> [Watch] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtering = searching && !trimmed.isEmpty
        let watches = data.visibleWatches
        let found = filtering ? watches.filter { WatchlistSearch.matches($0, query: trimmed) } : watches
        return found.filter(\.favorite) + found.filter { !$0.favorite }
    }

    /// Erste und letzte sichtbare Position in `rows`; nil, wenn keine Zeile sichtbar ist.
    private func visibleRange(in rows: [Watch]) -> ClosedRange<Int>? {
        let visible = jumpTracker.visible
        let indices = rows.indices.filter { visible.contains(rows[$0].id) }
        guard let first = indices.first, let last = indices.last else { return nil }
        return first...last
    }

    /// Zeile kommt oder geht: Richtung nachführen; geht eine Zeile, die noch in der Liste
    /// steht, wird gescrollt (gelöschte oder weggefilterte Zeilen zählen nicht).
    private func rowVisibilityChanged(_ id: Int64, visible: Bool) {
        if visible {
            jumpTracker.visible.insert(id)
        } else {
            jumpTracker.visible.remove(id)
        }
        let rows = currentRows()
        guard WatchlistJump.eligible(pairs: rows.count, sorting: sorting) else { return }
        if let range = visibleRange(in: rows) {
            let down = WatchlistJump.pointsDown(firstVisible: range.lowerBound, lastVisible: range.upperBound,
                                                total: rows.count)
            if down != jumpDown { jumpDown = down }
        }
        if !visible, rows.contains(where: { $0.id == id }) {
            showJump()
            if !scrollActive { scheduleJumpHide() }
        }
    }

    /// iOS 18: Scrollphase — sichtbar ab Beginn, ausblenden 2 s nach dem Ende.
    private func scrollPhaseChanged(_ scrolling: Bool) {
        scrollActive = scrolling
        guard WatchlistJump.eligible(pairs: currentRows().count, sorting: sorting) else { return }
        if scrolling {
            showJump()
        } else {
            scheduleJumpHide()
        }
    }

    private func showJump() {
        jumpHideTask?.cancel()
        jumpHideTask = nil
        if !jumpScrolled { jumpScrolled = true }
    }

    private func scheduleJumpHide() {
        jumpHideTask?.cancel()
        jumpHideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: WatchlistJump.hideDelayNanos)
            guard !Task.isCancelled else { return }
            jumpScrolled = false
        }
    }

    /// Oben → ans Ende, unten → an den Anfang. Kurze Strecken sanft, lange sofort
    /// (keine lange Animation); mit «Bewegung reduzieren» immer sofort.
    private func jump(rows: [Watch], proxy: ScrollViewProxy) {
        guard !rows.isEmpty else { return }
        WatchlistHaptics.impact(.light)
        let down = jumpDown
        let range = visibleRange(in: rows)
        let distance = down ? rows.count - 1 - (range?.upperBound ?? 0) : (range?.lowerBound ?? rows.count)
        let target = down ? WatchlistJump.endId : WatchlistJump.topId
        let anchor: UnitPoint = down ? .bottom : .top
        if WatchlistJump.animate(distance: distance, reduceMotion: reduceMotion) {
            withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(target, anchor: anchor) }
        } else {
            proxy.scrollTo(target, anchor: anchor)
        }
        // Richtung gleich umstellen (die Zeilen melden sich erst nach dem Sprung)
        jumpDown = !down
        if !scrollActive { scheduleJumpHide() }
    }

    /// Wisch-Aktionen: nicht beim Sortieren (Ziehgriffe) und nicht mit VoiceOver.
    private var swipeEnabled: Bool { !sorting && !voiceOver }

    /// Nach rechts gewischt: Favorit an/aus, leichte Haptik, kurzer Banner.
    private func swipeFavorite(_ watch: Watch) {
        swipeFavoriteTick += 1
        let nowFavorite = !watch.favorite
        withAnimation(reduceMotion ? nil : .spring(duration: 0.4)) { data.toggleFavorite(watch) }
        banner = WatchlistBannerMessage(
            text: L(nowFavorite ? "favorite_added" : "favorite_removed", watch.displayPair),
            icon: nowFavorite ? "star.fill" : "star"
        )
    }

    /// Nach links gewischt, VoiceOver «Löschen» oder «Löschen» im Aktionsblatt: sofort löschen (samt
    /// Alarmen), Banner mit «Rückgängig» und Ansage. Ein weiteres Löschen ersetzt den
    /// Banner — das vorige bleibt dann gelöscht.
    private func swipeDelete(_ watch: Watch) {
        swipeDeleteTick += 1
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

    /// «Nicht gehandelte Paare entfernen» bestätigt: alle auf einmal löschen, Banner mit
    /// «Rückgängig» (holt alle zurück) und Ansage.
    private func removeNotTraded() {
        var deleted: [DeletedWatch] = []
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { deleted = data.deleteNotTradedForUndo() }
        guard !deleted.isEmpty else { return }
        let removed = deleted
        swipeDeleteTick += 1
        let text = L("watchlist_removed_not_traded", count: removed.count)
        banner = WatchlistBannerMessage(
            text: text,
            icon: "trash",
            action: WatchlistBannerAction(title: L("action_undo")) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(removed) }
            }
        )
        Task { @MainActor in
            // Nach dem Fokuswechsel ansagen, sonst geht es unter
            try? await Task.sleep(nanoseconds: 300_000_000)
            AccessibilityNotification.Announcement(text).post()
        }
    }

    /// «Rückgängig»: Paar mit Id, Platz, Gruppe, Notiz, Mitteilung, Favorit und Alarmen zurück.
    private func undoDelete(_ deleted: DeletedWatch) {
        withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) { _ = data.restore(deleted) }
    }

    /// Zielwährung der umgerechneten Kurse; nil = ausgeschaltet.
    private var convertTarget: String? {
        data.settings.showConverted ? data.settings.portfolioCurrency : nil
    }

    /// Ändert sich nur mit der Einstellung oder der Menge der Quote-Währungen.
    private var conversionKey: String {
        guard let target = convertTarget else { return "" }
        return target + "|" + WatchlistConversion.quotes(data.watches, target: target).joined(separator: ",")
    }

    /// Zuerst aus den Zwischenspeichern, dann frisch und danach alle 60 s; fehlt ein
    /// Faktor einmal, bleibt der letzte bekannte stehen.
    private func refreshConversion() async {
        guard let target = convertTarget else {
            convertRates = [:]
            return
        }
        let quotes = WatchlistConversion.quotes(data.watches, target: target)
        guard !quotes.isEmpty else {
            convertRates = [:]
            return
        }
        var known = CurrencyConverter.cachedRates(quotes: quotes, target: target)
        convertRates = known
        while !Task.isCancelled {
            let fresh = await CurrencyConverter.rates(quotes: quotes, target: target)
            if Task.isCancelled { return }
            known.merge(fresh) { _, new in new }
            convertRates = known
            try? await Task.sleep(nanoseconds: WatchlistConversion.refreshNanos)
        }
    }

    /// Quote-Währungen, die praktisch USDT sind — nur dann wird der Kurs vorbelegt.
    private static let usdLikeQuotes: Set<String> = ["USDT", "USD", "USDC", "FDUSD"]

    /// Coin = Basiswährung; Kurs nur, wenn die Quote praktisch USDT ist.
    static func makePortfolioDraft(for watch: Watch) -> PortfolioTxDraft {
        let price = watch.lastPrice.flatMap { price in
            price > 0 && usdLikeQuotes.contains(watch.quoteAsset.uppercased()) ? price : nil
        }
        return PortfolioTxDraft(coin: watch.baseAsset, priceUsdt: price)
    }

    private func moveByAccessibility(_ watch: Watch, _ move: WatchMove) {
        reorderTick += 1
        withAnimation(.spring(duration: 0.35)) {
            data.move(watch, move, group: data.selectedWatchlistGroup)
        }
    }

    private func commitOrder(_ list: [Watch]) {
        reorderTick += 1
        data.reorder(list.map(\.id))
    }

    private func toggleFavorite(_ watch: Watch) {
        WatchlistHaptics.impact()
        withAnimation(.spring(duration: 0.4)) { data.toggleFavorite(watch) }
    }

    // MARK: Status

    /// Ehrlicher Status: wie viele Kurse sind veraltet? Daneben die Dauer des
    /// letzten Durchlaufs, ein Tipp zeigt die Aufschlüsselung.
    private func statusRow(_ watches: [Watch]) -> some View {
        let staleAfter = data.staleAfterMillis
        // «Nicht mehr gehandelt» ist kein Fehler: zählt weder als veraltet noch als gescheitert
        let traded = watches.filter { !ConnectionErrors.isNotTraded($0.lastError) }
        let staleCount = traded.filter { WatchlistTime.isStale($0, now: now, staleAfter: staleAfter) }.count
        let newest = traded.map(\.lastUpdate).max() ?? 0
        // Keine Verbindung: der letzte Durchlauf scheiterte bei ALLEN Paaren am Netz
        let offline = !traded.isEmpty && traded.allSatisfy { ConnectionErrors.isOffline($0.lastError) }
        let failed = traded.filter { $0.lastError != nil }.count
        let warn = staleCount > 0 || offline || failed > 0
        let tone = warn ? AppColors.error : PriceColors.ok
        let text: String
        if offline {
            text = newest > 0 ? L("watchlist_offline_since", WatchlistTime.ago(newest, now: now)) : L("watch_error_offline")
        } else if staleCount > 0 {
            text = L("watchlist_stale_count", count: staleCount, staleCount, traded.count)
        } else if failed > 0 {
            text = L("watchlist_failed_count", count: failed, failed, traded.count)
        } else if newest > 0 {
            text = L("watchlist_all_fresh", WatchlistTime.ago(newest, now: now))
        } else {
            text = L("watchlist_pull_to_refresh")
        }
        return HStack(spacing: 8) {
            HStack(spacing: 8) {
                Circle()
                    .fill(tone)
                    .frame(width: 8, height: 8)
                    .shadow(color: tone.opacity(0.7), radius: 4)
                    .phaseAnimator(data.refreshing ? [0.35, 1.0] : [1.0]) { view, phase in
                        view.opacity(phase)
                    } animation: { _ in .easeInOut(duration: 0.6) }
                Text(text)
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(tone.opacity(0.12), in: Capsule())

            Spacer(minLength: 4)

            if data.lastRefreshMillis > 0 {
                Button { showReport = true } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "timer").scaledFont(size: 11, weight: .semibold, relativeTo: .caption)
                        Text(L("watchlist_last_run", PriceFormat.duration(data.lastRefreshMillis)))
                            .font(.caption.monospacedDigit())
                            .lineLimit(1)
                    }
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay(Capsule().strokeBorder(AppColors.outlineVariant.opacity(0.5), lineWidth: 0.5))
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("watchlist_refresh_report"))
            }

            // Lupe: in der Sortieransicht ausgeblendet
            if !sorting {
                Button { openSearch() } label: {
                    Image(systemName: "magnifyingglass")
                        .scaledFont(size: 12, weight: .semibold, relativeTo: .caption)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(width: WatchlistSearch.buttonSize, height: WatchlistSearch.buttonSize)
                        .background(.ultraThinMaterial, in: Circle())
                        .overlay(Circle().strokeBorder(AppColors.outlineVariant.opacity(0.5), lineWidth: 0.5))
                        .contentShape(Circle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("watchlist_search_open"))
            }
        }
    }

    // MARK: Suche

    /// Kompaktes Suchfeld anstelle der Statuszeile, mit Schliessen-Knopf rechts.
    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .scaledFont(size: 13, weight: .semibold, relativeTo: .footnote)
                .foregroundStyle(searchFocused ? accent.primary : AppColors.onSurfaceVariant)
            TextField(L("watchlist_search_hint"), text: $query)
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurface)
                .focused($searchFocused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { searchFocused = false }
            Button { closeSearch() } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 26, height: 26)
                    .background(AppColors.containerHigh, in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("watchlist_search_close"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .frame(height: WatchlistSearch.rowHeight)
        .background(AppColors.container, in: Capsule())
        .overlay(Capsule().strokeBorder(AppColors.outlineVariant.opacity(0.6), lineWidth: 1))
        .onAppear {
            // Kurz warten, bis das Feld in der Liste steht — dann Tastatur auf
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 80_000_000)
                searchFocused = true
            }
        }
    }

    private func openSearch() {
        WatchlistHaptics.impact(.light)
        editMode = .inactive
        withAnimation(.easeInOut(duration: 0.25)) { searching = true }
    }

    /// Schliessen leert die Suche und zeigt wieder den Status.
    private func closeSearch() {
        searchFocused = false
        withAnimation(.easeInOut(duration: 0.25)) {
            searching = false
            query = ""
        }
    }

    /// Kurzer Hinweis zu den Gesten mit Schliessen-Knopf.
    private var gestureHint: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "hand.tap")
                .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                .foregroundStyle(accent.primary)
                .padding(.top, 1)
            Text(L("watch_gesture_hint_short"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                withAnimation { data.settings.gestureHintSeen = true }
            } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 26, height: 26)
                    .background(AppColors.containerHigh, in: Circle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("action_close"))
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(accent.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(accent.primary.opacity(0.2), lineWidth: 1)
        )
    }

    // MARK: Erst-Hinzufügen

    /// Holt den ausstehenden Moment ab, sobald die Merkliste der aktive Tab ist.
    /// Start-Tipp: Banner, eine Haptik und die VoiceOver-Ansage hier; beim
    /// Hinzufügen-Tab hat das der Tab schon gemacht — hier nur die Zeilen.
    private func takeCelebration() {
        guard router.tab == .watchlist, let celebration = data.takeAddCelebration() else { return }
        var positions: [Int64: Int] = [:]
        for (index, id) in celebration.watchIds.enumerated() { positions[id] = index }
        celebrating = positions
        if celebration.announceInWatchlist {
            firstAddHaptic += 1
            // Noch kein Alarm auf den neuen Paaren: «Alarm setzen» öffnet die Alarme des
            // ersten (wie im Aktionsblatt). Kein weiterer Banner danach.
            let counts = data.activeAlarmCounts
            let noAlarms = celebration.watchIds.allSatisfy { (counts[$0] ?? 0) == 0 }
            let action: WatchlistBannerAction? = noAlarms ? celebration.watchIds.first.map { id in
                WatchlistBannerAction(title: L("add_alarm_action")) { alarmsFor = id }
            } : nil
            banner = WatchlistBannerMessage(text: celebration.message, long: true, action: action)
            let text = celebration.message
            Task { @MainActor in
                // Erst nach dem Wechsel zur Liste ansagen, sonst geht es im Fokuswechsel unter
                try? await Task.sleep(nanoseconds: 400_000_000)
                AccessibilityNotification.Announcement(text).post()
            }
        }
        // Moment vorbei: Zeilen wieder im Normalzustand (Versatz + Einblenden + Häkchen)
        let ids = Set(celebration.watchIds)
        let nanos = UInt64(max(celebration.watchIds.count - 1, 0)) * 90_000_000 + 2_600_000_000
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: nanos)
            if Set(celebrating.keys) == ids { celebrating = [:] }
        }
    }

    // MARK: Leer

    /// Start-Tipp: frische Liste, sonst Zwischenspeicher (auch älter), sonst Ersatzliste —
    /// so steht sofort etwas da.
    private var shownStarterCoins: [StarterCoin] {
        starterCoins ?? StarterCoins.initial(us: StarterCoins.isUS())
    }

    /// Top 5 nach Marktkapitalisierung höchstens alle 24 h neu holen; kommt die Liste,
    /// bevor jemand tippt, wird still getauscht.
    private func refreshStarterCoins() async {
        let us = StarterCoins.isUS()
        guard !StarterCoins.isFresh(us: us) else { return }
        guard let fresh = await StarterCoins.load(us: us), !Task.isCancelled else { return }
        if data.watches.isEmpty { starterCoins = fresh }
    }

    /// Leere Merkliste: die fünf grössten Coins zur Auswahl (alle vorgewählt) und in
    /// einem Schritt hinzufügen; darunter der Weg über «Hinzufügen».
    private var emptyState: some View {
        let coins = shownStarterCoins
        return GeometryReader { geo in
            ScrollView {
                VStack(spacing: 14) {
                    WatchlistLogo(size: 64)
                        .padding(16)
                        .background(accent.container.opacity(0.5), in: Circle())
                        .accessibilityHidden(true)
                    Text(L("starter_title"))
                        .font(.title3.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    Text(L("starter_text"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)

                    WatchlistStarterPicker(coins: coins, us: StarterCoins.isUS()) { symbols in
                        addStarter(symbols)
                    }

                    Text(L("watchlist_empty_hint"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                    Button {
                        router.tab = .add
                    } label: {
                        Text(L("starter_custom"))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(accent.primary)
                            .multilineTextAlignment(.center)
                            .padding(.vertical, 8)
                            .padding(.horizontal, 12)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 28)
                .frame(maxWidth: 480)
                // Mittig, solange es passt; sonst scrollbar (grosse Schrift, kleine Geräte)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .refreshable { await data.refreshAll() }
        }
        .task { await refreshStarterCoins() }
    }

    /// Haptik, Banner und Ansage kommen mit dem «Erst-Hinzufügen»-Moment (`takeCelebration`).
    private func addStarter(_ symbols: [String]) {
        withAnimation(.spring(duration: 0.35)) {
            _ = data.addStarterCoins(symbols)
        }
        // Gleich abholen, damit die neuen Zeilen schon im ersten Bild verborgen sind
        takeCelebration()
    }

    // MARK: Kopfzeile

    /// Erste Zeile der Liste: ganz links das kleine Logo (ohne App-Namen, für VoiceOver
    /// nur Zierde), dahinter scrollen die Gruppen-Chips (mit mindestens einer Gruppe, oder
    /// ab zwei Paaren nur «+»), die Knöpfe stehen fest rechts. Ohne Chips: Logo links,
    /// Knöpfe rechts. Tippflächen 44 pt; Knöpfe mit `.borderless`, sonst löste ein Tipp
    /// in der Listenzeile alle Knöpfe zugleich aus.
    private func headerRow(_ watches: [Watch], groups: [String], selectedGroup: String?) -> some View {
        HStack(spacing: 0) {
            WatchlistLogo(size: 24)
                .padding(.trailing, 10)
                .accessibilityHidden(true)
            if !groups.isEmpty || data.watches.count >= 2 {
                WatchlistGroupChips(
                    groups: groups,
                    selected: selectedGroup,
                    onSelect: { group in
                        withAnimation(.spring(duration: 0.35)) { data.selectWatchlistGroup(group) }
                    },
                    onEdit: { group in
                        groupEdit = WatchGroupEditTarget(name: group, isNew: false)
                    },
                    onAdd: {
                        newGroupName = ""
                        askNewGroup = true
                    }
                )
                // Gescrollte Chips weder unter das Logo noch unter die Knöpfe zeichnen
                .clipped()
                .padding(.trailing, 4)
            } else {
                Spacer(minLength: 0)
            }
            headerActions(watches)
                // Symbole bündig mit dem Kartenrand; die Tippfläche ragt in den Seitenrand
                .padding(.trailing, -10)
        }
        .frame(minHeight: Self.headerHeight)
        // Bildschirmtitel für VoiceOver (die Navigationsleiste ist ausgeblendet)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L("tab_watchlist"))
    }

    /// Mindest-Tippfläche der Knöpfe in der Kopfzeile.
    private static let headerHeight: CGFloat = 44

    private func headerActions(_ watches: [Watch]) -> some View {
        HStack(spacing: 0) {
            if sorting {
                Button {
                    withAnimation { editMode = .inactive }
                } label: {
                    Text(L("action_sort_done"))
                        .font(.body.weight(.semibold))
                        .padding(.horizontal, 10)
                        .frame(minHeight: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
            } else {
                // Glocke: alle Alarme, mit Zahl der aktiven
                Button {
                    showOverview = true
                } label: {
                    bellIcon
                        .frame(width: Self.headerHeight, height: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
                .accessibilityLabel(L("alarms_overview_title"))

                if data.refreshing {
                    ProgressView()
                        .controlSize(.small)
                        .tint(accent.primary)
                        .frame(width: Self.headerHeight, height: Self.headerHeight)
                } else {
                    Button {
                        WatchlistHaptics.impact(.light)
                        Task { await data.refreshAll() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .frame(width: Self.headerHeight, height: Self.headerHeight)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .accessibilityLabel(L("action_refresh"))
                }

                // Seltenes im Menü: Sortieren, Bericht, Alle löschen
                Menu {
                    if watches.count > 1 {
                        Button {
                            if searching { closeSearch() }
                            withAnimation { editMode = .active }
                        } label: {
                            Label(L("action_sort"), systemImage: "arrow.up.arrow.down")
                        }
                    }
                    Button {
                        showReport = true
                    } label: {
                        Label(L("watchlist_refresh_report"), systemImage: "info.circle")
                    }
                    let notTraded = data.notTradedCount
                    if !watches.isEmpty || notTraded > 0 {
                        Divider()
                    }
                    // Nur wenn es nicht gehandelte Paare gibt; direkt vor «Merkliste leeren»
                    if notTraded > 0 {
                        Button {
                            askRemoveNotTraded = true
                        } label: {
                            Label(L("watchlist_remove_not_traded_menu", notTraded), systemImage: "xmark.bin")
                        }
                    }
                    if !watches.isEmpty {
                        Button(role: .destructive) {
                            askClearAll = true
                        } label: {
                            Label(L("watchlist_clear"), systemImage: "trash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .frame(width: Self.headerHeight, height: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .menuStyle(.button)
                .buttonStyle(.borderless)
                .tint(accent.primary)
                .accessibilityLabel(L("action_more"))
            }
        }
        .font(.body)
        .imageScale(.large)
    }

    private var bellIcon: some View {
        let active = data.activeAlarmCounts.values.reduce(0, +)
        return Image(systemName: active > 0 ? "bell.badge" : "bell")
            .symbolRenderingMode(.hierarchical)
            .overlay(alignment: .topTrailing) {
                if active > 0 {
                    Text("\(active)")
                        .scaledFont(size: 10, weight: .bold, relativeTo: .caption2, monospacedDigit: true)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .foregroundStyle(accent.onPrimary)
                        .padding(.horizontal, 4)
                        .frame(minWidth: 16, minHeight: 16)
                        .background(accent.primary, in: Capsule())
                        .offset(x: 9, y: -7)
                }
            }
    }

    // MARK: Ablauf

    /// Nach dem Schliessen des Aktionsblatts: «Warum», Alarme öffnen oder löschen.
    /// Löschen wie nach links wischen: sofort, mit «Rückgängig» im Banner, ohne Rückfrage.
    private func afterSheet() {
        if let id = pendingWhy {
            pendingWhy = nil
            whyFor = WatchlistSheetTarget(id: id)
        }
        if let id = pendingAlarms {
            pendingAlarms = nil
            alarmsFor = id
        }
        if let watch = pendingDelete {
            pendingDelete = nil
            swipeDelete(watch)
        }
        if let id = pendingPortfolio {
            pendingPortfolio = nil
            // Portfolio-Sperre: Das Erfassen-Blatt zeigt Bestände — erst nach dem Entsperren
            Task { @MainActor in
                let open = await lock.requireUnlock(PortfolioLockPolicy.quickAddNeedsUnlock(locked:))
                guard open, let watch = data.watch(id) else { return }
                portfolioDraft = Self.makePortfolioDraft(for: watch)
            }
        }
    }

    /// Zum Paar scrollen, kurz hervorheben und seine Aktionen öffnen.
    private func focus(on id: Int64, proxy: ScrollViewProxy) {
        guard let watch = data.watch(id) else { return }
        // Paar ausserhalb der gewählten Gruppe: wieder alle zeigen
        if let group = data.selectedWatchlistGroup, watch.groupName != group {
            data.selectWatchlistGroup(nil)
        }
        showOverview = false
        alarmsFor = nil
        whyFor = nil
        withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(id, anchor: .center) }
        highlightedId = id
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            actionsFor = WatchlistSheetTarget(id: id)
            try? await Task.sleep(nanoseconds: 1_650_000_000)
            if highlightedId == id { highlightedId = nil }
        }
    }
}

// MARK: Logo

/// App-Logo in der Akzentfarbe; ohne Bild im Asset-Katalog ein Symbol.
struct WatchlistLogo: View {
    let size: CGFloat
    @Environment(\.appAccent) private var accent
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let name = accent.logoName(dark: colorScheme == .dark)
        if UIImage(named: name) != nil {
            Image(name)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
        } else {
            Image(systemName: "chart.line.uptrend.xyaxis.circle.fill")
                .resizable()
                .scaledToFit()
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(accent.primary)
                .frame(width: size, height: size)
        }
    }
}

// MARK: Bericht

/// Aufschlüsselung des letzten Durchlaufs.
private struct WatchlistReportSheet: View {
    let report: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(report.isEmpty ? L("watchlist_refresh_report_empty") : report)
                    .font(.footnote.monospaced())
                    .foregroundStyle(AppColors.onSurface)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .padding(16)
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("watchlist_refresh_report"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_close")) { dismiss() }
                }
            }
        }
    }
}

// MARK: Suche

/// Suche in der Merkliste — wie `matchesSearch` in `WatchlistScreen.kt`.
enum WatchlistSearch {
    /// Gemeinsame Höhe von Statuszeile und Suchfeld, damit nichts springt.
    static let rowHeight: CGFloat = 34
    static let buttonSize: CGFloat = 28

    /// Gross-/Kleinschreibung egal; mehrere Wörter müssen alle passen
    /// (z. B. «btc kraken»). Geprüft werden Basis, Quote, «BASIS/QUOTE»,
    /// Vertragskürzel, Börse und Notiz.
    static func matches(_ watch: Watch, query: String) -> Bool {
        let fields = [
            watch.baseAsset,
            watch.quoteAsset,
            watch.displayPair,
            watch.displayName,
            watch.contractType.shortName ?? "",
            watch.marketName,
            watch.note ?? "",
        ].map { $0.lowercased() }
        let tokens = query.lowercased().split(whereSeparator: { $0.isWhitespace }).map(String.init)
        return tokens.allSatisfy { token in fields.contains { $0.contains(token) } }
    }
}

// MARK: Zeilen-Stil

private extension View {
    /// Listenzeile ohne Trenner und Hintergrund, mit Kartenabstand.
    func plainRow(top: CGFloat, bottom: CGFloat) -> some View {
        self
            .listRowInsets(EdgeInsets(top: top, leading: 16, bottom: bottom, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}
