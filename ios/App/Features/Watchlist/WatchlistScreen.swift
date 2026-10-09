import SwiftUI

/// Ziel des Aktionsblatts (Paar-Id).
struct WatchlistSheetTarget: Identifiable, Equatable {
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
    @EnvironmentObject var data: AppData
    @EnvironmentObject var router: AppRouter
    /// Portfolio-Sperre: «Zum Portfolio hinzufügen» erst nach dem Entsperren.
    @ObservedObject var lock = AppLock.shared
    @Environment(\.appAccent) var accent
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @Environment(\.colorScheme) var colorScheme
    /// Mit VoiceOver keine Wisch-Aktionen — dort gibt es dieselben Aktionen im Rotor.
    @Environment(\.accessibilityVoiceOverEnabled) var voiceOver

    // Uhr für «vor 2 Min.» — alle 30 Sekunden neu
    @State var now: Int64 = TimeUtils.nowMillis

    @State var actionsFor: WatchlistSheetTarget?
    /// Paar, dessen «Warum bewegt sich das?» offen ist.
    @State var whyFor: WatchlistSheetTarget?
    @State var pendingWhy: Int64?
    @State var pendingAlarms: Int64?
    @State var pendingDelete: Watch?
    /// «Zum Portfolio hinzufügen»: Paar, dessen Erfassen-Blatt nach dem Aktionsblatt öffnet.
    @State var pendingPortfolio: Int64?
    @State var portfolioDraft: PortfolioTxDraft?
    @State var askClearAll = false
    /// «Nicht gehandelte Paare entfernen»: Rückfrage offen?
    @State var askRemoveNotTraded = false
    @State var showReport = false
    /// Alarm löst bei offener App aus: Glocke pulsiert einmal (`AlarmPulse`).
    @State var bellPulse = 0
    @State var showOverview = false
    /// Logo mit App-Namen im Menü: «Über die App».
    @State var showAbout = false
    @State var alarmsFor: Int64?
    @State var editMode: EditMode = .inactive
    @State var highlightedId: Int64?
    @State var reorderTick = 0
    /// Suche in der Merkliste (Lupe rechts neben dem Status) — wird nicht gespeichert.
    @State var searching = false
    @State var query = ""
    @FocusState var searchFocused: Bool
    /// «Gruppe bearbeiten» (lange auf den Chip drücken) bzw. neue Gruppe über «+».
    @State var groupEdit: WatchGroupEditTarget?
    @State var askNewGroup = false
    @State var newGroupName = ""
    /// «≈ Umrechnung»: Faktor je Quote-Währung (Grossbuchstaben) in die Umrechnungswährung.
    @State var convertRates: [String: Double] = [:]
    /// Start-Tipp: frisch geladene Liste (Top 5); nil = Zwischenspeicher bzw. Ersatzliste.
    @State var starterCoins: [StarterCoin]?
    /// «Erst-Hinzufügen»: Zeilen mit laufendem Moment (Watch-Id → Position für den Versatz).
    @State var celebrating: [Int64: Int] = [:]
    /// Banner unten: «… wird jetzt überwacht», «… entfernt» (mit «Rückgängig»), Favorit.
    @State var banner: WatchlistBannerMessage?
    /// Eine Erfolgs-Haptik je Hinzufügen (nicht je Zeile).
    @State var firstAddHaptic = 0
    /// Haptik beim Wischen: Favorit (leicht) bzw. Löschen.
    @State var swipeFavoriteTick = 0
    @State var swipeDeleteTick = 0
    /// «Anpassen» in der Aktivitätskarte: Markt-Meldungen (Empfindlichkeit) öffnen.
    @State var showActivitySettings = false
    /// Sprungknopf «Zum Anfang» / «Zum Ende»: sichtbare Zeilen, Richtung, Scrollzustand.
    @State var jumpTracker = WatchlistJumpTracker()
    @State var jumpDown = true
    @State var jumpScrolled = false
    @State var jumpHideTask: Task<Void, Never>?
    /// iOS 18: Liste wird gerade gescrollt (Scrollphase nicht «idle»).
    @State var scrollActive = false

    init() {}

    var sorting: Bool { editMode.isEditing }

    var body: some View {
        // %-Basis für Pillen, Puls und Aktionsblatt; passt der Stempel der gespeicherten Werte nicht
        // (Basis gewechselt, neuer Tag — die 30-s-Uhr prüft das), «—» bis neu gerechnet ist
        let changeView = data.changeView(now: now)
        return withSheets(screen(changeView: changeView), changeView: changeView)
    }

    /// Die Merkliste (bzw. die Start-Auswahl) mit Banner, Haptik, Uhr, Umrechnung, Live-Kursen und
    /// den Sprüngen aus Mitteilungen; Blätter und Rückfragen legt `withSheets` darüber.
    private func screen(changeView: ChangeView) -> some View {
        let watches = data.watches
        // Sichtbar: alle Paare oder nur die der gewählten Gruppe
        let visible = data.visibleWatches
        return ScrollViewReader { proxy in
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
        .onChange(of: router.showExplorer) { _, _ in takeCelebration() }
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
        // stehen rechts in der Gruppen-Zeile im festen Kopf über der Liste (`headerRow`). Der Titel
        // bleibt für den Zurück-Knopf und VoiceOver; den Abstand zur Statusleiste gibt die
        // sichere Zone der Liste.
        .navigationTitle(L("tab_watchlist"))
        .toolbar(.hidden, for: .navigationBar)
        // «Paar hinzufügen» als Seite über der Merkliste («+», Shortcut, Widget, Link "add")
        .navigationDestination(isPresented: $router.showExplorer) {
            ExplorerScreen()
        }
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
        // Erster Stand gilt als gesehen; nur spätere Auslösungen lassen die Glocke pulsieren
        .onChange(of: data.alarms.map(\.lastTriggeredAt).max() ?? 0) { old, new in
            if !reduceMotion && AlarmPulse.isNew(previous: old, current: new) { bellPulse += 1 }
        }
        .onChange(of: router.showAlarmsOverview, initial: true) { _, show in
            guard show else { return }
            router.showAlarmsOverview = false
            actionsFor = nil
            showOverview = true
        }
        // «Warum?» aus einer Alarm-Mitteilung: Erklärung des Paars öffnen (nur gehandelte Paare)
        .onChange(of: router.openWhyWatchId, initial: true) { _, id in
            guard let id else { return }
            router.openWhyWatchId = nil
            guard data.watch(id)?.isNotTraded == false else { return }
            actionsFor = nil
            showOverview = false
            whyFor = WatchlistSheetTarget(id: id)
        }
        // «Alarm setzen» von der Seite «Paar hinzufügen»: Alarme des Paars öffnen (wie aus dem Aktionsblatt)
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
        .environment(\.changeView, changeView)
        .sensoryFeedback(.impact(weight: .medium), trigger: reorderTick)
        // Live-Kurse (WebSocket) für die Paare der Ansicht, solange die Merkliste zu sehen ist
        .onChange(of: data.visibleWatches.map(\.livePair), initial: true) { _, pairs in
            Task { await LivePriceStream.shared.setPairs(pairs) }
        }
        .onAppear { Task { await LivePriceStream.shared.setScreenVisible(true) } }
        .onDisappear { Task { await LivePriceStream.shared.setScreenVisible(false) } }
        // App-Start messen: erstes Bild der Merkliste (nächster Durchlauf nach dem Erscheinen)
        .onAppear {
            Task { @MainActor in
                if let millis = AppStartClock.onFirstWatchlistFrame() { data.recordAppStart(millis) }
            }
        }
    }
}
