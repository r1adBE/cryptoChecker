import SwiftUI

/// Was sich über die Merkliste legt — Aktionsblatt, «Was bewegt sich mit?», Portfolio, Gruppen, Bericht,
/// Rückfragen und die Seiten «Alarme» — und was nach dem Schliessen passiert. Wie `WatchlistSheets.kt`.
extension WatchlistScreen {
    /// Blätter, Rückfragen und Seiten über `content`.
    func withSheets<Content: View>(_ content: Content, changeView: ChangeView) -> some View {
        content
            // Aktionen eines Paars als Blatt von unten
            .sheet(item: $actionsFor, onDismiss: afterSheet) { target in
                WatchActionsSheet(
                    watchId: target.id,
                    // Nicht mehr gehandelt: kein ⚡ (das Blatt zeigt dann auch kein «Was bewegt sich mit?»)
                    hasActivity: data.watch(target.id)?.isNotTraded != true &&
                        !WatchlistActivity.active(data.activityReports[target.id], now: TimeUtils.nowMillis,
                                                           sensitivity: data.settings.activitySensitivity).isEmpty,
                    onOpenAlarms: { pendingAlarms = $0 },
                    onDelete: { pendingDelete = $0 },
                    onWhy: { pendingWhy = $0 },
                    onAddToPortfolio: { pendingPortfolio = $0 },
                    onDisabledChange: { disabledChanged($0) }
                )
                .environmentObject(data)
                .environment(\.appAccent, accent)
                .environment(\.changeView, changeView)
                // Halb hoch öffnen, auf volle Höhe ziehbar (L-16, `WatchActionsDetents`)
                .modifier(WatchActionsDetents())
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(28)
                .presentationBackground(AppColors.background)
            }
            // «Was bewegt sich mit?» als eigene Seite mit Zurück wie «Alarme» (kein Blatt mehr);
            // Zurück öffnet wieder das Aktionsblatt, falls von dort gekommen
            .navigationDestination(item: $whyFor) { target in
                WatchlistWhySheet(watchId: target.id)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .toolbar(.visible, for: .navigationBar)
            }
            .onChange(of: whyFor) { _, target in
                if target == nil { reopenActions() }
            }
            // Wieder gesperrt (Hintergrund-Limit), während das Erfassen-Blatt offen ist: schliessen
            .onChange(of: lock.locked) { _, locked in
                if locked { portfolioDraft = nil }
            }
            // Erfassen-Blatt aus der Merkliste: Coin (und Kurs, falls in USD) vorbelegt
            // Erste Transaktion mit Portfolio aus: Rückfrage «Portfolio-Tab einschalten?»
            .modifier(PortfolioQuickAddPresenter(
                draft: $portfolioDraft,
                onEnabled: { portfolioEnabledBanner() },
                // «Später»: Transaktion gespeichert, Tab bleibt aus — einmal der Weg zum Schalter
                onLater: { banner = WatchlistBannerMessage(text: L("portfolio_enable_later_hint")) }
            ))
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
                RefreshReportSheet(report: data.lastRefreshReport, appStartMillis: data.appStartMillis,
                                   liveExchanges: data.liveExchanges)
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
                // Mit «Rückgängig» im Banner (wie «Nicht gehandelte entfernen»)
                Button(L("watchlist_clear"), role: .destructive) { clearAll() }
                Button(L("action_cancel"), role: .cancel) {}
            } message: {
                Text(L("watchlist_clear_confirm"))
            }
            // Glocke ohne Alarm, mehrere Paare: Paar wählen → dessen Alarm-Seite (gleiches Blatt wie die leere Übersicht).
            // Mit Suche, Favoriten oben und Lazy-Liste (`AlarmPairPicker`)
            .sheet(isPresented: $pickAlarmPair, onDismiss: {
                if let id = pickedAlarmPair { alarmsFor = id }
                pickedAlarmPair = nil
            }) {
                let input = data.alarmPairPickerInput // PERF4-R1: einmal je Stand
                AlarmPairPicker(watches: input.watches, sourceKey: input.sourceKey) { id in
                    pickedAlarmPair = id
                    pickAlarmPair = false
                }
            }
            .navigationDestination(isPresented: $showOverview) {
                AlarmsOverviewScreen()
                    // Leiste hier ausdrücklich zeigen (in der Merkliste ist sie ausgeblendet)
                    .toolbar(.visible, for: .navigationBar)
            }
            // Zurück aus «Alarme»: Aktionsblatt wieder öffnen, falls von dort gekommen
            .onChange(of: alarmsFor) { _, id in
                if id == nil { reopenActions() }
            }
            .navigationDestination(item: $alarmsFor) { id in
                AlarmsScreen(watchId: id)
                    .toolbar(.visible, for: .navigationBar)
            }
            .navigationDestination(isPresented: $showAbout) {
                AboutSettingsPage()
                    .toolbar(.visible, for: .navigationBar)
            }
            .navigationDestination(isPresented: $showActivitySettings) {
                MarketAlertsSettingsPage()
                    .toolbar(.visible, for: .navigationBar)
            }
    }

    /// Glocke (Kopf und Start-Auswahl): mit Alarmen die Übersicht, ohne einen einzigen gleich zum Anlegen —
    /// ein Paar: dessen Alarm-Seite (öffnet den Editor), mehrere: erst wählen, leere Merkliste: Coin hinzufügen.
    /// Gezählt wird, was die Übersicht zeigen würde — wie Android `WatchlistContentFlows.alarmTotal`.
    func openBell() {
        let watches = data.watches
        let alarmCount = totalAlarmCount
        switch AlarmBellRoute.of(alarmCount: alarmCount, watchCount: watches.count) {
        case .overview: showOverview = true
        case .editor: alarmsFor = watches.first?.id
        case .pickPair: pickAlarmPair = true
        case .addCoin: router.openExplorer()
        }
    }

    /// Alle Alarme, die die Übersicht zeigen würde (Paar-Alarme auch ausgeschaltet, Portfolio-Alarme nur mit
    /// eingeschaltetem Portfolio) — Glocke und Karte «Ersten Alarm setzen».
    var totalAlarmCount: Int {
        data.alarmsWithWatch.count + (data.settings.portfolioEnabled ? data.portfolioAlarms.count : 0)
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

    // MARK: Ablauf

    /// Nach dem Schliessen des Aktionsblatts: «Was bewegt sich mit?», Alarme öffnen oder löschen.
    /// Löschen wie nach links wischen: sofort, mit «Rückgängig» im Banner, ohne Rückfrage.
    private func afterSheet() {
        if let id = pendingWhy {
            pendingWhy = nil
            returnToActions = id
            whyFor = WatchlistSheetTarget(id: id)
        }
        if let id = pendingAlarms {
            pendingAlarms = nil
            returnToActions = id
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

    /// «Portfolio-Tab einschalten?» › Einschalten: kurz bestätigen.
    private func portfolioEnabledBanner() {
        banner = WatchlistBannerMessage(text: L("portfolio_enabled_banner"))
    }

    /// Zurück aus «Alarme» bzw. «Was bewegt sich mit?»: Aktionsblatt des Paars wieder öffnen, falls von dort gekommen.
    private func reopenActions() {
        guard let id = returnToActions else { return }
        returnToActions = nil
        guard data.watch(id) != nil else { return }
        actionsFor = WatchlistSheetTarget(id: id)
    }

    /// Zum Paar scrollen, kurz hervorheben und seine Aktionen öffnen.
    func focus(on id: Int64, proxy: ScrollViewProxy) {
        guard let watch = data.watch(id) else { return }
        // Paar ausserhalb der gewählten Gruppe: wieder alle zeigen
        if let group = data.selectedWatchlistGroup, !WatchFilter.matches(group, watch) {
            data.selectWatchlistGroup(nil)
        }
        showOverview = false
        returnToActions = nil
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

    /// Quote-Währungen, die praktisch USDT sind — nur dann wird der Kurs vorbelegt.
    private static let usdLikeQuotes: Set<String> = ["USDT", "USD", "USDC", "FDUSD"]

    /// Coin = Basiswährung; Kurs nur, wenn die Quote praktisch USDT ist.
    static func makePortfolioDraft(for watch: Watch) -> PortfolioTxDraft {
        let price = watch.lastPrice.flatMap { price in
            price > 0 && usdLikeQuotes.contains(watch.quoteAsset.uppercased()) ? price : nil
        }
        return PortfolioTxDraft(coin: watch.baseAsset, priceUsdt: price)
    }
}
