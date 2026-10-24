import Accessibility
import SwiftUI

/// Die Liste unter dem festen Kopf: Puls, Hinweise, Paare — wie `WatchlistList.kt`. «Hier passiert
/// gerade etwas» ist keine Karte mehr, sondern der ⚡-Chip bei den Gruppen (`WatchlistGroupChips`).
extension WatchlistScreen {
    /// `watches`: die gezeigten Paare (Gruppe bzw. ⚡ / «nur veraltete»); `statusWatches`: worüber
    /// der Status zählt; `signals`: gültige Aktivitäts-Signale je Paar; `activityChip`: ⚡ zeigen.
    func list(_ watches: [Watch], statusWatches: [Watch], signals: [Int64: [ActivitySignal]],
              activityChip: Bool, proxy: ScrollViewProxy) -> some View {
        // Aktive Suche filtert zusätzlich zur gewählten Gruppe
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtering = searching && !trimmed.isEmpty
        // Namen der Coins («bitcoin» findet BTC), einmal je Suche; Suchschlüssel je Paar einmal gefaltet,
        // solange die Suche offen ist — je Tastendruck nur die Anfrage
        let names: [Int64: String] = filtering ? WatchlistSearch.names(for: watches) : [:]
        if !searching { searchIndex.clear() }
        let found = filtering ? searchIndex.filter(watches, query: trimmed, names: names) : watches
        // Sortieren nach Spalte (nicht im Sortiermodus): Favoriten oben, unter sich sortiert; Kurse
        // über CHF verglichen (im Hintergrund umgerechnet, auch ohne «≈ Umrechnung»)
        let columnSort = sorting ? nil : self.columnSort
        let changeNow = data.changeView(now: now)
        let ordered = ColumnSort.apply(found, columnSort, favorite: { $0.favorite }, name: { $0.displayName },
                                       value: { sortValue($0) }, change: { changeNow.shown($0) })
        let favorites = ordered.filter(\.favorite)
        let others = ordered.filter { !$0.favorite }
        let counts = data.activeAlarmCounts
        // Art je Paar (Symbol vor der Glocke): Listen einmal lesen statt je Zeile
        let kinds = data.watchKinds
        let groups = data.watchGroups
        let selectedGroup = data.selectedWatchlistGroup
        // Puls ganz oben in der Liste: nur die sichtbare Gruppe; nicht beim Suchen und Sortieren;
        // deaktivierte Paare zählen nicht mit
        let counted = DisabledView.active(watches) { $0.disabled }
        // Erster Gebrauch: kein Puls und kein Gesten-Hinweis, bis es einen Alarm gibt bzw. nach einigen Tagen
        let onboarding = onboardingVisible
        let pulse = sorting || searching || !onboarding.pulse
            ? nil : WatchlistPulseStats.make(counted, view: data.changeView(now: now))
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
        // Platz jeder Zeile in der Liste: Form wie eine Einheit (`ListSegment`)
        let positions = Dictionary(rows.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { first, _ in first })
        // Kein Sprungknopf in der Mehrfachauswahl: unten steht dann die Leiste
        let jumpEligible = WatchlistJump.eligible(pairs: rows.count, sorting: sorting || selecting)
        let jumpVisible = jumpEligible && (jumpScrolled || voiceOver)
        // Anker für «Zum Anfang» (Kopfzeile und Status stehen fest über der Liste): die erste
        // Zeile über den Paaren — Puls, sonst Hinweis. Keine eigene leere Zeile (eine Listenzeile
        // ist mindestens 44 pt hoch); ohne solche Zeile das erste Paar.
        let showGestureHint = !data.settings.gestureHintSeen && onboarding.gestureHint
        let showHint = sorting || showGestureHint
        let topAnchor: WatchlistTopAnchor = pulse != nil ? .pulse : showHint ? .hint : .none
        return List {
            // «↗ 7 steigen · ↘ 3 fallen · Ø +1.80%» — erste Zeile unter dem festen Kopf, scrollt mit
            if let pulse {
                WatchlistPulseLine(stats: pulse)
                    .plainRow(top: 4, bottom: ListSegment.spacing)
                    .transition(.opacity)
                    .id(WatchlistJump.topId)
            }

            // Kein Alarm, aber Paare: bleibende Karte zusätzlich zum 6-s-Banner nach dem Hinzufügen (wie Android); sie
            // öffnet das erste Paar der gespeicherten Reihenfolge, nicht der gerade sortierten Ansicht
            if let first = FirstAlarmCard.target(stored: data.watches, disabled: { $0.disabled }),
               FirstAlarmCard.shouldShow(alarmCount: totalAlarmCount, watchCount: data.watches.count,
                                         dismissed: data.settings.firstAlarmCardDismissed,
                                         busy: searching || sorting || selecting) {
                firstAlarmCard(first: first)
                    .plainRow(top: 2, bottom: ListSegment.spacing)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }

            // «Name ⇅ · Kurs ⇅ · 24h ⇅» — nicht im Sortiermodus und erst ab zwei Paaren
            if !sorting && rows.count > 1 {
                WatchlistSortBar(sort: columnSort) { key in
                    withAnimation(reduceMotion ? nil : .spring(duration: 0.35)) {
                        columnSortRaw = ColumnSort.next(columnSort, tapped: key)?.encoded ?? ""
                    }
                }
                .plainRow(top: 2, bottom: ListSegment.spacing)
            }

            // Leere Ansicht (Gruppe ohne Paare): ruhiger Hinweis statt einer leeren Fläche
            if watches.isEmpty && !filtering {
                // Eigene Gruppe (wie die Chips mit langem Drücken; TradFi ist automatisch): Knopf «Gruppe bearbeiten»
                // unter dem Hinweis — langes Drücken ist nicht auffindbar (wie Android)
                let editable = selectedGroup.flatMap { group in
                    quickView == nil && groups.contains(group) && group != AutoGroup.tradFi ? group : nil
                }
                VStack(spacing: Spacing.xs) {
                    Text(L(WatchFilter.isFavorites(selectedGroup) ? "watchlist_favorites_empty_hint" : "watchlist_group_empty_hint"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    if let editable, !WatchFilter.isFavorites(editable) {
                        Button(L("group_edit_title")) {
                            groupEdit = WatchGroupEditTarget(name: editable, isNew: false)
                        }
                        .font(.subheadline.weight(.semibold))
                        .tint(accent.primary)
                        .buttonStyle(.borderless)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .plainRow(top: 2, bottom: 2)
            }

            if sorting {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    Text(L("watchlist_sort_hint"))
                        .font(.footnote)
                        .foregroundStyle(accent.primary)
                        .padding(.horizontal, 4)
                    // Gilt für die gezeigten Paare wie das Ziehen (gewählte Gruppe; ⚡/Suche sind im Sortiermodus aus)
                    if rows.count > 1 {
                        autoOrderButtons(rows)
                    }
                }
                .plainRow(top: 2, bottom: ListSegment.spacing)
                .id(topAnchor == .hint ? WatchlistJump.topId : "watchlist-sort-hint")
            } else if showGestureHint {
                // Einmaliger Gesten-Hinweis, bleibt bis er weggeklickt wird.
                gestureHint
                    .plainRow(top: 2, bottom: ListSegment.spacing)
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
                row(watch, counts: counts, kinds: kinds, signals: signals, section: favorites,
                    shape: ListSegment.shape(positions[watch.id] ?? 0, rows.count))
            }
            .onMove(perform: moveFavorites)

            ForEach(others) { watch in
                row(watch, counts: counts, kinds: kinds, signals: signals, section: others,
                    shape: ListSegment.shape(positions[watch.id] ?? 0, rows.count))
            }
            .onMove(perform: moveOthers)

            // Mit Sprungknopf bzw. Auswahl-Leiste unten mehr Platz, damit sie die letzte Zeile nicht verdeckt
            Color.clear
                .frame(height: jumpEligible || selecting ? 12 + WatchlistJump.buttonSize + WatchlistJump.margin : 12)
                .plainRow(top: 0, bottom: 0)
                .id(WatchlistJump.endId)
                .accessibilityHidden(true)
        }
        .listStyle(.plain)
        // Fest oben (scrollt nicht mit): Kopfzeile mit Gruppen-Chips und Knöpfen, darunter
        // Status und Lupe. Puls, Aktivitätskarte und Paare scrollen darunter durch.
        .safeAreaInset(edge: .top, spacing: 0) {
            pinnedHeader(watches, statusWatches: statusWatches, groups: groups, selectedGroup: selectedGroup,
                         hasActivity: activityChip)
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
        // Mehrfachauswahl: Favorit · Gruppe · Löschen für die angehakten Paare
        .overlay(alignment: .bottom) {
            if selecting {
                let chosen = watches.filter { selectedIds.contains($0.id) }
                let allDisabled = !chosen.isEmpty && chosen.allSatisfy(\.disabled)
                WatchlistSelectionBar(
                    count: chosen.count,
                    allFavorites: !chosen.isEmpty && chosen.allSatisfy(\.favorite),
                    onFavorite: favoriteSelected,
                    onGroup: { askGroupForSelection = true },
                    onDelete: deleteSelected,
                    allDisabled: allDisabled,
                    onToggleDisabled: { disableSelected(!allDisabled) }
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .sheet(isPresented: $askGroupForSelection) {
            let chosen = data.watches.filter { selectedIds.contains($0.id) }
            // Gleiche Gruppe bei allen: vorgewählt, sonst nichts
            let common = Set(chosen.map { $0.groupName ?? "" })
            WatchGroupSheet(current: common.count == 1 ? chosen.first?.groupName : nil,
                            groups: data.watchGroups) { group, _, _ in groupSelected(group) }
                .environment(\.appAccent, accent)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .scrollContentBackground(.hidden)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .background(AppColors.background)
        .environment(\.editMode, $editMode)
        .refreshable { await refreshByUser() }
        // «Bewegung reduzieren»: Zeilen und Hinweise springen ohne Bewegung an ihren Platz (wie die Überlagerungen)
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: watches.map(\.id))
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: groups)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: data.settings.gestureHintSeen)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: sorting)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: selecting)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: searching)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: found.map(\.id))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: pulse == nil)
    }

    /// Erster Gebrauch (`WatchlistOnboarding`): Gruppen-Hinweis, Puls und Gesten-Hinweis erst nach dem ersten Alarm
    /// (gleiche Zählung wie die Karte «Ersten Alarm setzen») oder an einigen verschiedenen Tagen — wie Android
    /// `WatchlistUiState.onboarding`.
    var onboardingVisible: WatchlistOnboarding.Visible {
        WatchlistOnboarding.visible(alarmCount: totalAlarmCount, usageDays: data.settings.usageDays,
                                    hasGroupsOrFavorites: !data.watchGroups.isEmpty || data.watches.contains { $0.favorite })
    }

    /// Öffnen heute zählen (lokaler Kalendertag), für `WatchlistOnboarding`. Nicht zugängliche Einstellungen (vor
    /// dem ersten Entsperren) nicht anfassen — sie würden ohnehin nicht gespeichert.
    func recordUsageDay() {
        guard data.settingsAccessible else { return }
        let current = UsageDays(count: data.settings.usageDays, lastDay: data.settings.usageLastDay)
        let next = current.next(today: LocalDay.today().epochDay)
        guard next != current else { return }
        // Beide Felder in einem Schritt: ein Speichern statt zwei
        var settings = data.settings
        settings.usageDays = next.count
        settings.usageLastDay = next.lastDay
        data.settings = settings
    }

    /// Alles aktualisieren (nach unten ziehen, Menü). Eben erst aktualisiert (unter 15 s): kein neuer
    /// Durchlauf — der Menüpunkt ist dann grau mit Countdown (`WatchlistMenuHead`); wer zieht, sieht den
    /// Kreisel kurz drehen (`.refreshable` wartet so lange) und dann «Gerade aktualisiert · vor N s».
    func refreshByUser() async {
        let decision = await data.refreshAllByUser()
        guard let ago = RefreshDebounce.pullEchoSeconds(decision, lastFinishedAt: SharedStorage.lastRefreshAt,
                                                        now: TimeUtils.nowMillis) else { return }
        try? await Task.sleep(nanoseconds: UInt64(RefreshDebounce.pullEchoMillis) * 1_000_000)
        let text = L("watchlist_refresh_just_done", ago)
        banner = WatchlistBannerMessage(text: text)
        // Einmal ansagen wie das Banner unter Android (TalkBack liest Snackbars selbst)
        AccessibilityNotification.Announcement(text).post()
    }
}

// MARK: Automatisch ordnen

extension WatchlistScreen {
    /// «Nach Coin ordnen» · «Nach Börse ordnen» im Sortiermodus — wie `AutoOrderButtons` (Android); bei langen
    /// Übersetzungen untereinander statt abgeschnitten.
    func autoOrderButtons(_ shown: [Watch]) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Spacing.sm) {
                autoOrderButton(.coin, shown)
                autoOrderButton(.exchange, shown)
            }
            VStack(alignment: .leading, spacing: 0) {
                autoOrderButton(.coin, shown)
                autoOrderButton(.exchange, shown)
            }
        }
    }

    private func autoOrderButton(_ order: AutoOrder, _ shown: [Watch]) -> some View {
        Button(L(order == .coin ? "watchlist_sort_by_coin" : "watchlist_sort_by_exchange")) {
            autoOrder(order, shown)
        }
        .font(.subheadline.weight(.semibold))
        .tint(accent.primary)
        // In der Liste (Sortiermodus) sonst ganze Zeile als ein Knopf
        .buttonStyle(.borderless)
        .padding(.horizontal, 4)
        .frame(minHeight: 44)
    }
}

/// Welche Zeile über den Paaren den Anker «Zum Anfang» trägt.
private enum WatchlistTopAnchor {
    case pulse, hint, none
}

// MARK: Zeilen-Stil

extension View {
    /// Listenzeile ohne Trenner und Hintergrund, mit Kartenabstand.
    func plainRow(top: CGFloat, bottom: CGFloat) -> some View {
        self
            .listRowInsets(EdgeInsets(top: top, leading: 16, bottom: bottom, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}
