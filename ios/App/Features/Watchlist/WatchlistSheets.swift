import SwiftUI

/// Was sich über die Merkliste legt — Paarseite, «Was bewegt sich mit?», Gruppen, Bericht, Rückfragen und die Seiten
/// «Alarme». Wie `WatchlistSheets.kt` und `PairDestination.kt`.
extension WatchlistScreen {
    /// Blätter, Rückfragen und Seiten über `content`.
    func withSheets<Content: View>(_ content: Content, changeView: ChangeView) -> some View {
        content
            // Paarseite (Tipp auf eine Zeile, Mitteilung, Widget-Zeile) als eigene Seite mit Zurück
            .navigationDestination(item: $pairFor) { target in
                PairScreen(watchId: target.id, onDelete: { swipeDelete($0) })
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .environment(\.changeView, changeView)
                    .toolbar(.visible, for: .navigationBar)
            }
            // «Was bewegt sich mit?» aus der Zeile bzw. einer Alarm-Mitteilung als eigene Seite mit Zurück
            .navigationDestination(item: $whyFor) { target in
                WatchlistWhySheet(watchId: target.id)
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .toolbar(.visible, for: .navigationBar)
            }
            // Vergleichsansicht als eigene Seite mit Zurück
            .navigationDestination(item: $compareFor) { target in
                CompareScreen(watches: target.ids.compactMap { id in data.watches.first { $0.id == id } })
                    .environmentObject(data)
                    .environment(\.appAccent, accent)
                    .environment(\.changeView, changeView)
                    .toolbar(.visible, for: .navigationBar)
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
                TextField(placeholder: L("group_name"), text: $newGroupName)
                    .textInputAutocapitalization(.words)
                Button(L("group_next")) { startNewGroup() }
                Button(L("action_cancel"), role: .cancel) {}
            }
            .sheet(isPresented: $showReport) {
                // Offen während einer Aktualisierung: neuer Bericht und Live-Börsen erscheinen (eigene Beobachtung)
                RefreshStateReader(state: data.refreshState) { state in
                    RefreshReportSheet(report: state.lastRefreshReport, appStartMillis: data.appStartMillis,
                                       liveExchanges: state.liveExchanges)
                }
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
                let input = data.alarmPairPickerInput // einmal je Stand
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

    /// Zum Paar scrollen, kurz hervorheben und seine Seite öffnen.
    func focus(on id: Int64, proxy: ScrollViewProxy) {
        guard let watch = data.watch(id) else { return }
        // Paar ausserhalb der gewählten Gruppe: wieder alle zeigen
        if let group = data.selectedWatchlistGroup, !WatchFilter.matches(group, watch) {
            data.selectWatchlistGroup(nil)
        }
        showOverview = false
        alarmsFor = nil
        whyFor = nil
        withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(id, anchor: .center) }
        highlightedId = id
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            pairFor = WatchlistSheetTarget(id: id)
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
