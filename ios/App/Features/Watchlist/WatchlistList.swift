import SwiftUI

/// Die Liste unter dem festen Kopf: Puls, Aktivitätskarte, Hinweise, Paare — wie `WatchlistList.kt`.
extension WatchlistScreen {
    /// `watches`: die sichtbaren Paare (ggf. nur die der gewählten Gruppe).
    func list(_ watches: [Watch], proxy: ScrollViewProxy) -> some View {
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
        let pulse = sorting || searching ? nil : WatchlistPulseStats.make(watches, view: data.changeView(now: now))
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
        // Anker für «Zum Anfang» (Kopfzeile und Status stehen fest über der Liste): die erste
        // Zeile über den Paaren — Puls, sonst Aktivitätskarte, sonst Hinweis. Keine eigene leere
        // Zeile (eine Listenzeile ist mindestens 44 pt hoch); ohne solche Zeile das erste Paar.
        // Abschaltbar in den Einstellungen (Merkliste); die Mitteilungen dazu bleiben davon unberührt
        let showCard = data.settings.watchlistActivityCard && !hot.isEmpty && !sorting && !searching
        let showHint = sorting || !data.settings.gestureHintSeen
        let topAnchor: WatchlistTopAnchor = pulse != nil ? .pulse : showCard ? .card : showHint ? .hint : .none
        return List {
            // «▲ 7 steigen · ▼ 3 fallen · Ø +1.80%» — erste Zeile unter dem festen Kopf, scrollt mit
            if let pulse {
                WatchlistPulseLine(stats: pulse)
                    .plainRow(top: 4, bottom: 2)
                    .transition(.opacity)
                    .id(WatchlistJump.topId)
            }

            // Leere Ansicht (Gruppe ohne Paare): ruhiger Hinweis statt einer leeren Fläche
            if watches.isEmpty && !filtering {
                Text(L(WatchFilter.isFavorites(selectedGroup) ? "watchlist_favorites_empty_hint" : "watchlist_group_empty_hint"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .plainRow(top: 2, bottom: 2)
            }

            // «⚡ Hier passiert gerade etwas» — nur mit Signalen in der aktuellen Ansicht,
            // beim Suchen ausgeblendet
            if showCard {
                WatchlistActivityCard(
                    hot: hot,
                    limit: sensitivity.maxCardCoins,
                    onOpen: { watch in whyFor = WatchlistSheetTarget(id: watch.id) },
                    onAdjust: { showActivitySettings = true }
                )
                .plainRow(top: 4, bottom: 2)
                .transition(.opacity.combined(with: .move(edge: .top)))
                .id(topAnchor == .card ? WatchlistJump.topId : "watchlist-activity-card")
            }

            if sorting {
                Text(L("watchlist_sort_hint"))
                    .font(.footnote)
                    .foregroundStyle(accent.primary)
                    .padding(.horizontal, 4)
                    .plainRow(top: 2, bottom: 2)
                    .id(topAnchor == .hint ? WatchlistJump.topId : "watchlist-sort-hint")
            } else if !data.settings.gestureHintSeen {
                // Einmaliger Gesten-Hinweis, bleibt bis er weggeklickt wird.
                gestureHint
                    .plainRow(top: 2, bottom: 4)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                    .id(topAnchor == .hint ? WatchlistJump.topId : "watchlist-gesture-hint")
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
        // Fest oben (scrollt nicht mit): Kopfzeile mit Gruppen-Chips und Knöpfen, darunter
        // Status und Lupe. Puls, Aktivitätskarte und Paare scrollen darunter durch.
        .safeAreaInset(edge: .top, spacing: 0) {
            pinnedHeader(watches, groups: groups, selectedGroup: selectedGroup)
        }
        .modifier(WatchlistScrollPhaseModifier { scrolling in scrollPhaseChanged(scrolling) })
        .overlay(alignment: .bottomTrailing) {
            // Ein- und Ausblenden nur hier animiert, nicht die Liste darunter
            ZStack {
                if jumpVisible {
                    WatchlistJumpButton(down: jumpDown) { jump(rows: rows, proxy: proxy, hasTopAnchor: topAnchor != .none) }
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
        .refreshable { await refreshByUser() }
        .animation(.spring(duration: 0.35), value: watches.map(\.id))
        .animation(.spring(duration: 0.35), value: groups)
        .animation(.easeInOut(duration: 0.25), value: data.settings.gestureHintSeen)
        .animation(.easeInOut(duration: 0.25), value: sorting)
        .animation(.spring(duration: 0.35), value: hot.map(\.id))
        .animation(.easeInOut(duration: 0.25), value: searching)
        .animation(.easeInOut(duration: 0.2), value: found.map(\.id))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: pulse == nil)
    }

    /// Alles aktualisieren (nach unten ziehen, Knopf oben). Eben erst aktualisiert (unter 15 s):
    /// kein neuer Durchlauf, nur kurz «Gerade aktualisiert» — keine Fehlermeldung.
    func refreshByUser() async {
        let decision = await data.refreshAllByUser()
        if decision == .recent {
            banner = WatchlistBannerMessage(text: L("watchlist_just_refreshed"), icon: "checkmark.circle")
        }
    }

    /// `section`: sichtbare Abteilung (Favoriten bzw. übrige) für «Nach oben/unten» in VoiceOver.
    private func row(_ watch: Watch, counts: [Int64: Int], signals: [Int64: [ActivitySignal]],
                     section: [Watch]) -> some View {
        let index = section.firstIndex(where: { $0.id == watch.id })
        let canReorder = !searching && index != nil
        // Werte der Zeile hier auslesen (nicht in der Zeile), damit nur `WatchlistLiveRow` den
        // Live-Kurs beobachtet: ein Tick zeichnet genau diese eine Zeile neu
        let staleAfter = data.staleAfterMillis
        let outdatedAfter = data.outdatedAfterMillis
        let rowRefreshing = data.refreshingWatchIds.contains(watch.id)
        let allRefreshing = data.refreshing
        let sparklines = data.settings.watchlistSparkline && !watch.isNotTraded
        let target = convertTarget
        let rates = convertRates
        return WatchlistLiveRow(watch: watch, rollingBasis: !data.settings.changeBasis.isDay) { shown in
            WatchlistRow(
                watch: shown,
                alarmCount: counts[watch.id] ?? 0,
                now: now,
                staleAfter: staleAfter,
                outdatedAfter: outdatedAfter,
                loading: rowRefreshing || (allRefreshing && shown.lastPrice == nil),
                highlighted: highlightedId == watch.id,
                sorting: sorting,
                hasActivity: signals[watch.id] != nil,
                converted: WatchlistConversion.text(shown, target: target, rates: rates),
                // Nicht mehr gehandelt: kein Mini-Chart
                sparklineEnabled: sparklines,
                celebrationIndex: celebrating[watch.id],
                onTap: { actionsFor = WatchlistSheetTarget(id: watch.id) },
                onToggleFavorite: { toggleFavorite(watch) },
                onActivity: { if !sorting { whyFor = WatchlistSheetTarget(id: watch.id) } },
                // Sortieren nur über ⋯ › Sortieren (dann am Griff ziehen): langes Drücken startet
                // nichts mehr — ein zögerliches Wischen landete sonst im Sortiermodus (wie Android)
                onLongPress: nil
            )
        }
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
                .tint(AppColors.destructive)
            }
        }
        // Sprungknopf: welche Zeilen sichtbar sind (Richtung) und ob gescrollt wird
        .onAppear { rowVisibilityChanged(watch.id, visible: true) }
        .onDisappear { rowVisibilityChanged(watch.id, visible: false) }
        .id(watch.id)
        .plainRow(top: 4, bottom: 4)
    }

    /// Kurzer Hinweis zu den Gesten mit Schliessen-Knopf.
    private var gestureHint: some View {
        HStack(alignment: .top, spacing: Spacing.sm) {
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
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.sm)
        .padding(.vertical, Spacing.md)
        .background(accent.tint(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(accent.tint(0.2), lineWidth: 1)
        )
    }

    /// Zielwährung der umgerechneten Kurse; nil = ausgeschaltet.
    private var convertTarget: String? {
        data.settings.showConverted ? data.settings.portfolioCurrency : nil
    }

    /// Ändert sich nur mit der Einstellung oder der Menge der Quote-Währungen.
    var conversionKey: String {
        guard let target = convertTarget else { return "" }
        return target + "|" + WatchlistConversion.quotes(data.watches, target: target).joined(separator: ",")
    }

    /// Zuerst aus den Zwischenspeichern, dann frisch und danach alle 60 s; fehlt ein
    /// Faktor einmal, bleibt der letzte bekannte stehen.
    func refreshConversion() async {
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
}

/// Welche Zeile über den Paaren den Anker «Zum Anfang» trägt.
private enum WatchlistTopAnchor {
    case pulse, card, hint, none
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

/// Eine Zeile mit dem Live-Kurs darüber (`LivePrices`, nur Anzeige): Nur diese Ansicht liest den
/// Kurs ihres Paars, ein WebSocket-Tick zeichnet also nur sie neu — nicht die ganze Merkliste.
private struct WatchlistLiveRow<Content: View>: View {
    let watch: Watch
    let rollingBasis: Bool
    @ViewBuilder let content: (Watch) -> Content

    var body: some View {
        content(watch.withLive(LivePrices.shared.quote(for: watch.id), rollingBasis: rollingBasis))
    }
}
