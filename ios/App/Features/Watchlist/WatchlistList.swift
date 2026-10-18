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
        // Namen der Coins («bitcoin» findet BTC), einmal je Suche
        let names: [Int64: String] = filtering ? WatchlistSearch.names(for: watches) : [:]
        let found = filtering ? watches.filter { WatchlistSearch.matches($0, query: trimmed, name: names[$0.id]) } : watches
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
        let pulse = sorting || searching ? nil : WatchlistPulseStats.make(counted, view: data.changeView(now: now))
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
        let showHint = sorting || !data.settings.gestureHintSeen
        let topAnchor: WatchlistTopAnchor = pulse != nil ? .pulse : showHint ? .hint : .none
        return List {
            // «↗ 7 steigen · ↘ 3 fallen · Ø +1.80%» — erste Zeile unter dem festen Kopf, scrollt mit
            if let pulse {
                WatchlistPulseLine(stats: pulse)
                    .plainRow(top: 4, bottom: ListSegment.spacing)
                    .transition(.opacity)
                    .id(WatchlistJump.topId)
            }

            // Kein Alarm, aber Paare: bleibende Karte zusätzlich zum 6-s-Banner nach dem Hinzufügen (wie Android)
            if let first = rows.first ?? data.watches.first,
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
                Text(L("watchlist_sort_hint"))
                    .font(.footnote)
                    .foregroundStyle(accent.primary)
                    .padding(.horizontal, 4)
                    .plainRow(top: 2, bottom: ListSegment.spacing)
                    .id(topAnchor == .hint ? WatchlistJump.topId : "watchlist-sort-hint")
            } else if !data.settings.gestureHintSeen {
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
                            groups: data.watchGroups) { group in groupSelected(group) }
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
        .animation(.spring(duration: 0.35), value: watches.map(\.id))
        .animation(.spring(duration: 0.35), value: groups)
        .animation(.easeInOut(duration: 0.25), value: data.settings.gestureHintSeen)
        .animation(.easeInOut(duration: 0.25), value: sorting)
        .animation(.easeInOut(duration: 0.25), value: selecting)
        .animation(.easeInOut(duration: 0.25), value: searching)
        .animation(.easeInOut(duration: 0.2), value: found.map(\.id))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: pulse == nil)
    }

    /// Alles aktualisieren (nach unten ziehen, Menü). Eben erst aktualisiert (unter 15 s): kein
    /// neuer Durchlauf und keine Meldung — der Menüpunkt ist dann grau (`AppData.canRefreshNow`).
    func refreshByUser() async {
        _ = await data.refreshAllByUser()
    }

    /// `section`: sichtbare Abteilung (Favoriten bzw. übrige) für «Nach oben/unten» in VoiceOver.
    private func row(_ watch: Watch, counts: [Int64: Int], kinds: [Int64: AssetKind],
                     signals: [Int64: [ActivitySignal]],
                     section: [Watch], shape: UnevenRoundedRectangle) -> some View {
        let index = section.firstIndex(where: { $0.id == watch.id })
        let canReorder = !searching && index != nil
        // Werte der Zeile hier auslesen (nicht in der Zeile), damit nur `WatchlistLiveRow` den
        // Live-Kurs beobachtet: ein Tick zeichnet genau diese eine Zeile neu
        // Am Live-Strom: veraltet schon nach 2 Min. ohne Kurs (wie die Status-Pille, K-1)
        let staleAfter = data.staleAfterMillis(for: watch.id)
        let outdatedAfter = data.staleAfterMillis(for: watch.id)
        let firedAt = data.alarmFiredAt[watch.id] ?? 0
        let rowRefreshing = data.refreshingWatchIds.contains(watch.id)
        let allRefreshing = data.refreshing
        let sparklines = data.settings.watchlistSparkline && !watch.isNotTraded
        let target = convertTarget
        let rates = convertRates
        let fxTimes = convertFxTimes
        return WatchlistLiveRow(watch: watch, rollingBasis: !data.settings.changeBasis.isDay) { shown in
            WatchlistRow(
                watch: shown,
                alarmCount: counts[watch.id] ?? 0,
                alarmFiredAt: firedAt,
                kind: kinds[watch.id] ?? .TOKEN,
                now: now,
                staleAfter: staleAfter,
                outdatedAfter: outdatedAfter,
                loading: rowRefreshing || (allRefreshing && shown.lastPrice == nil),
                highlighted: highlightedId == watch.id || (selecting && selectedIds.contains(watch.id)),
                sorting: sorting,
                shape: shape,
                selecting: selecting,
                selected: selectedIds.contains(watch.id),
                hasActivity: signals[watch.id] != nil,
                converted: WatchlistConversion.text(shown, target: target, rates: rates, fxTimes: fxTimes, now: now),
                // Nicht mehr gehandelt: kein Mini-Chart
                sparklineEnabled: sparklines,
                celebrationIndex: celebrating[watch.id],
                onTap: {
                    if selecting { toggleSelected(watch.id) } else { actionsFor = WatchlistSheetTarget(id: watch.id) }
                },
                onToggleFavorite: { toggleFavorite(watch) },
                onActivity: {
                    if selecting { toggleSelected(watch.id) } else if !sorting { whyFor = WatchlistSheetTarget(id: watch.id) }
                },
                // Lange drücken: Mehrfachauswahl mit dieser Zeile (wie ⋯ › «Auswählen»), in der
                // Auswahl an-/abhaken wie Tippen. Sortieren weiter nur über ⋯ › Sortieren (dann am
                // Griff ziehen); im Sortiermodus tut langes Drücken nichts (wie Android).
                onLongPress: sorting ? nil : {
                    if selecting { toggleSelected(watch.id) } else { startSelection(with: watch.id) }
                }
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
            if !sorting && !selecting {
                Button(L("action_delete")) { swipeDelete(watch) }
                // «Auswählen» wie langes Drücken: Mehrfachauswahl mit dieser Zeile
                Button(L("watchlist_select")) { startSelection(with: watch.id) }
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
        // Paare ohne Fuge als eine Karte (`ListSegment`); die Trennlinie zeichnet die Zeile
        .plainRow(top: ListSegment.gap / 2, bottom: ListSegment.gap / 2)
    }

    /// Karte «Ersten Alarm setzen» (`FirstAlarmCard`): ganze Karte antippbar (Alarme des ersten Paars, ohne
    /// Alarm gleich der Editor), ✕ schliesst sie für immer — wie Android `FirstAlarmCardView`.
    private func firstAlarmCard(first: Watch) -> some View {
        HStack(alignment: .center, spacing: Spacing.sm) {
            Button {
                alarmsFor = first.id
            } label: {
                HStack(alignment: .top, spacing: Spacing.sm) {
                    Image(systemName: "bell.badge")
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .foregroundStyle(accent.primary)
                        .padding(.top, 1)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("watchlist_first_alarm_title"))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(accent.primary)
                        Text(L("watchlist_first_alarm_text"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurface)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            Button {
                withAnimation { data.settings.firstAlarmCardDismissed = true }
            } label: {
                Image(systemName: "xmark")
                    .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .frame(width: 26, height: 26)
                    .background(AppColors.containerHigh, in: Circle())
                    // Sichtbar klein, Tippfläche 44 × 44 pt (Apple-Richtlinie)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .padding(.vertical, -9)
            .accessibilityLabel(L("action_close"))
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.sm)
        .padding(.vertical, Spacing.md)
        .background(accent.tint(0.12), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    /// Kurzer Hinweis zu den Gesten mit Schliessen-Knopf. Zuerst eine Zeile, was ein Tipp bringt («Was bewegt
    /// sich mit?», sonst kaum zu entdecken), darunter die Gesten — wie Android `GestureHint`.
    private var gestureHint: some View {
        HStack(alignment: .top, spacing: Spacing.sm) {
            Image(systemName: "hand.tap")
                .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                .foregroundStyle(accent.primary)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("watch_hint_tap_why"))
                    .font(.footnote.weight(.semibold))
                Text(L("watch_gesture_hint_short"))
                    .font(.footnote)
            }
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
                    // Sichtbar klein, Tippfläche 44 × 44 pt (Apple-Richtlinie)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            // Die grössere Tippfläche ragt in den Rand des Hinweises, statt ihn höher zu machen
            .padding(.vertical, -9)
            .accessibilityLabel(L("action_close"))
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.sm)
        .padding(.vertical, Spacing.md)
        .background(accent.tint(0.08), in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
                .strokeBorder(accent.tint(0.2), lineWidth: 1)
        )
    }

    /// Gespeicherte Spalten-Sortierung; nil = eigene Reihenfolge.
    var columnSort: ColumnSort? { ColumnSort.decode(columnSortRaw) }

    /// Kurs in CHF fürs Sortieren; nil ohne Kurs oder Umrechnungsfaktor (dann ans Ende).
    func sortValue(_ watch: Watch) -> Double? {
        guard let price = watch.lastPrice, price > 0 else { return nil }
        let quote = CurrencyConversion.normalize(watch.quoteAsset)
        if CurrencyConversion.sameCurrency(quote, Self.sortCurrency) { return price }
        guard let rate = sortRates[quote], rate > 0 else { return nil }
        return price * rate
    }

    /// Gemeinsame Währung fürs Sortieren nach Kurs (nur intern, nie angezeigt) — wie Android.
    static let sortCurrency = "CHF"

    /// Ändert sich nur mit der Sortierung nach Kurs oder der Menge der Quote-Währungen.
    var sortRatesKey: String {
        guard columnSort?.key == .PRICE else { return "" }
        return WatchlistConversion.quotes(data.watches, target: Self.sortCurrency).joined(separator: ",")
    }

    /// Faktoren in CHF, zuerst aus den Zwischenspeichern, dann frisch und danach alle 60 s.
    func refreshSortRates() async {
        guard columnSort?.key == .PRICE else {
            sortRates = [:]
            return
        }
        let quotes = WatchlistConversion.quotes(data.watches, target: Self.sortCurrency)
        guard !quotes.isEmpty else {
            sortRates = [:]
            return
        }
        var known = CurrencyConverter.cachedRates(quotes: quotes, target: Self.sortCurrency)
        sortRates = known
        while !Task.isCancelled {
            let fresh = await CurrencyConverter.rates(quotes: quotes, target: Self.sortCurrency)
            if Task.isCancelled { return }
            known.merge(fresh) { _, new in new }
            sortRates = known
            try? await Task.sleep(nanoseconds: WatchlistConversion.refreshNanos)
        }
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
            convertFxTimes = [:]
            return
        }
        let quotes = WatchlistConversion.quotes(data.watches, target: target)
        guard !quotes.isEmpty else {
            convertRates = [:]
            convertFxTimes = [:]
            return
        }
        var known = CurrencyConverter.cachedRates(quotes: quotes, target: target)
        convertRates = known
        convertFxTimes = CurrencyConverter.staleFxTimes(quotes: quotes, target: target)
        while !Task.isCancelled {
            let fresh = await CurrencyConverter.rates(quotes: quotes, target: target)
            if Task.isCancelled { return }
            known.merge(fresh) { _, new in new }
            convertRates = known
            // Alter der Devisenkurse nach dem Auffrischen (älter als 3 h: Uhrzeit in der Zeile)
            convertFxTimes = CurrencyConverter.staleFxTimes(quotes: quotes, target: target)
            try? await Task.sleep(nanoseconds: WatchlistConversion.refreshNanos)
        }
    }
}

/// Welche Zeile über den Paaren den Anker «Zum Anfang» trägt.
private enum WatchlistTopAnchor {
    case pulse, hint, none
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
