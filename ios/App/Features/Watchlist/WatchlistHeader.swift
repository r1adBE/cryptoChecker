import SwiftUI
import UIKit

/// Fester Kopf über der Liste: Logo, Gruppen-Chips und Knöpfe — wie `WatchlistHeader.kt`.
extension WatchlistScreen {
    /// Fester Kopf über der Liste: Kopfzeile (Logo, Gruppen-Chips, Knöpfe) und darunter
    /// Status mit Lupe bzw. das Suchfeld. Höchstens `ReadableWidth.max` breit wie die Zeilen;
    /// eigener Hintergrund, damit gescrollte Zeilen darunter verschwinden.
    /// `watches`: gezeigte Paare; `statusWatches`: worüber der Status zählt (bei «nur veraltete» die
    /// ganze Gruppe); `hasActivity`: ⚡-Chip zeigen.
    func pinnedHeader(_ watches: [Watch], statusWatches: [Watch], groups: [String], selectedGroup: String?,
                      hasActivity: Bool) -> some View {
        VStack(spacing: 4) {
            headerRow(watches, groups: groups, selectedGroup: selectedGroup, hasActivity: hasActivity)
                // Namenshinweis unter einem Symbol-Chip liegt über der Statuszeile
                .zIndex(1)
            // Status links, Lupe rechts — beim Suchen wird die Zeile zum Suchfeld.
            ZStack {
                if searching {
                    searchField
                        .transition(.opacity.combined(with: .scale(scale: 0.97, anchor: .trailing)))
                } else {
                    // Eigene Beobachtung des Aktualisierungs-Status: Kreis, «Live», Dauer zeichnen nur hier neu
                    RefreshStateReader(state: data.refreshState) { state in
                        statusRow(statusWatches, state: state)
                    }
                    .transition(.opacity)
                }
            }
            .frame(minHeight: WatchlistSearch.rowHeight)
            // Kommen die Alarme an? Eine ruhige Zeile, nur mit Alarmen (eigene Komponente)
            if !searching && !selecting && !editMode.isEditing { AlarmReliabilityLine() }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, Spacing.xs)
        .readableContentWidth()
        .background(AppColors.background)
    }

    /// Erste Zeile des festen Kopfs: links scrollen die Gruppen-Chips («Alle», Gruppen, «+» für
    /// eine neue Gruppe), rechts die Glocke (nur mit aktiven Alarmen) und fest das Menü (darin oben Logo und
    /// App-Name). «Paar hinzufügen» steht neben der Lupe (`statusRow`). Tippflächen 44 pt; Knöpfe
    /// mit `.borderless`, sonst löste ein Tipp in der Listenzeile alle Knöpfe zugleich aus.
    /// Gibt es noch keine Chips, steht dort ein grauer Hinweis mit «Neue Gruppe» (`WatchlistGroupChips.showsHint`) —
    /// ab dem ersten Start, damit die Zeile nie leer wirkt.
    private func headerRow(_ watches: [Watch], groups: [String], selectedGroup: String?,
                           hasActivity: Bool) -> some View {
        let hasFavorites = data.watches.contains { $0.favorite }
        let hasAlpha = data.hasAlphaFilter
        let showsHint = WatchlistGroupChips.showsHint(
            groups: groups, selected: selectedGroup, hasFavorites: hasFavorites,
            hasActivity: hasActivity && !sorting, quickView: quickView,
            searching: searching, sorting: sorting, hasAlpha: hasAlpha
        )
        return HStack(spacing: 0) {
            if selecting {
                // Mehrfachauswahl: statt der Chips «n ausgewählt»
                Text(L("watchlist_selected_count", count: selectedIds.count))
                    .font(.headline)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.updatesFrequently)
            } else if showsHint {
                groupsHint
            } else {
                WatchlistGroupChips(
                    groups: groups,
                    selected: selectedGroup,
                    hasFavorites: hasFavorites,
                    onSelect: { group in
                        withAnimation(.spring(duration: 0.35)) {
                            quickView = nil
                            data.selectWatchlistGroup(group)
                        }
                    },
                    onEdit: { group in
                        groupEdit = WatchGroupEditTarget(name: group, isNew: false)
                    },
                    onAdd: {
                        newGroupName = ""
                        askNewGroup = true
                    },
                    hasActivity: hasActivity && !sorting,
                    quickView: quickView,
                    onToggleActivity: { toggleQuickView(.activity) },
                    hasTokens: data.hasTokenFilter,
                    hasAlpha: hasAlpha
                )
                // Gescrollte Chips nicht unter die Knöpfe zeichnen — nur waagrecht abschneiden,
                // damit der Namenshinweis unter einem Chip sichtbar bleibt
                .mask { Rectangle().padding(.vertical, -60) }
                .padding(.trailing, 4)
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

    /// Grauer Hinweis in der leeren Chip-Zeile, einzeilig, daneben «Neue Gruppe» (wie Android) — sonst fände
    /// man Gruppen nur über langes Drücken bzw. das Aktionsblatt.
    private var groupsHint: some View {
        HStack(spacing: Spacing.sm) {
            Text(L("watchlist_groups_empty_hint"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .lineLimit(1)
                .truncationMode(.tail)
            Button(L("group_add")) {
                newGroupName = ""
                askNewGroup = true
            }
            .font(.subheadline.weight(.semibold))
            .tint(accent.primary)
            .buttonStyle(.borderless)
            .lineLimit(1)
            .fixedSize()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.trailing, 4)
    }

    /// Mindest-Tippfläche der Knöpfe in der Kopfzeile.
    private static let headerHeight: CGFloat = 44

    private func headerActions(_ watches: [Watch]) -> some View {
        HStack(spacing: 0) {
            if selecting {
                if selectedIds.count < watches.count {
                    Button {
                        selectedIds = Set(watches.map(\.id))
                    } label: {
                        Text(L("watchlist_select_all"))
                            .padding(.horizontal, Spacing.sm)
                            .frame(minHeight: Self.headerHeight)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                }
                Button {
                    endSelection()
                } label: {
                    Text(L("action_sort_done"))
                        .font(.body.weight(.semibold))
                        .padding(.horizontal, Spacing.sm)
                        .frame(minHeight: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
            } else if sorting {
                Button {
                    withAnimation { editMode = .inactive }
                } label: {
                    Text(L("action_sort_done"))
                        .font(.body.weight(.semibold))
                        .padding(.horizontal, Spacing.sm)
                        .frame(minHeight: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
            } else {
                // Glocke immer (ohne einen einzigen Alarm: gleich zum Anlegen statt in die leere Übersicht,
                // `openBell`), ohne Zahl (eine Zahl läse sich leicht als «so oft ausgelöst»); immer die
                // Alarm-Glocke mit Wellen wie die Alarme in der Zeile (die schlichte Glocke heisst dort
                // «Kurs-Mitteilung an»), mit aktiven Alarmen gefüllt
                Button {
                    openBell()
                } label: {
                    Image(systemName: hasActiveAlarms ? "bell.and.waves.left.and.right.fill" : "bell.and.waves.left.and.right")
                        .symbolRenderingMode(.hierarchical)
                        .phaseAnimator([1.0, AlarmPulse.scale, 1.0], trigger: bellPulse) { view, scale in
                            view.scaleEffect(scale)
                        } animation: { _ in .easeInOut(duration: AlarmPulse.seconds / 2) }
                        .frame(width: Self.headerHeight, height: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
                .accessibilityLabel(bellLabel)
                .largeContentLabel(bellLabel, systemImage: "bell.and.waves.left.and.right")
                // Volle 44 pt Platz — die Tippflächen von Glocke und Menü überlappen nicht mehr
                // (früher 30 pt Platz, 14 pt der Glocke lagen unter dem Menü). Wie Android

                // Menü: oben Logo und App-Name («Über die App»), dann Aktualisieren, Alle Alarme, Sortieren,
                // Bericht, nicht gehandelte Paare entfernen, Merkliste leeren
                Menu {
                    // Gleicher Anfang wie Markt und Portfolio: App (→ «Über»), Aktualisieren
                    // Eben erst aktualisiert: grau mit Countdown «Aktualisieren (5)» statt einer Meldung
                    WatchlistMenuHead(
                        state: data.refreshState,
                        countdown: refreshCountdown,
                        onOpenAbout: { showAbout = true },
                        onRefresh: {
                            Haptics.play(.lightImpact)
                            Task { await refreshByUser() }
                        }
                    )
                    Button {
                        showOverview = true
                    } label: {
                        Label(L("alarms_overview_title"), systemImage: "bell.and.waves.left.and.right")
                    }
                    if watches.count > 1 {
                        Button {
                            if searching { closeSearch() }
                            quickView = nil
                            // Manuelles Sortieren zeigt die eigene Reihenfolge: Spalten-Sortierung aus
                            columnSortRaw = ""
                            withAnimation { editMode = .active }
                        } label: {
                            Label(L("action_sort"), systemImage: "arrow.up.arrow.down")
                        }
                    }
                    // Mehrfachauswahl: Favorit, Gruppe oder Löschen für mehrere Paare auf einmal
                    if !watches.isEmpty {
                        Button {
                            startSelection()
                        } label: {
                            Label(L("watchlist_select"), systemImage: "checkmark.circle")
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
                .largeContentLabel(L("action_more"), systemImage: "ellipsis.circle")
            }
        }
        .font(.body)
        .imageScale(.large)
    }

    /// «+ Paar hinzufügen»: Seite «Paar hinzufügen» — gefüllte Kapsel in der Themenfarbe mit Symbol
    /// und Text (neben der Lupe), wie Android `AddPairButton`. `labelled: false`: nur der Kreis mit
    /// «+» (oben rechts in der Start-Auswahl, die darunter einen eigenen Textknopf hat).
    func addPairButton(labelled: Bool = true) -> some View {
        Button { router.openExplorer() } label: {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "plus")
                    .scaledFont(size: WatchlistSearch.buttonSymbolSize, weight: .bold, relativeTo: .caption)
                if labelled {
                    Text(L("shortcut_add"))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
            }
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .foregroundStyle(onAccent)
            .padding(.horizontal, labelled ? 12 : 0)
            .frame(minWidth: WatchlistSearch.buttonSize, minHeight: WatchlistSearch.buttonSize)
            .background(accent.primary, in: Capsule())
            // Tippfläche mindestens 44 pt, sichtbar bleibt die kleine Kapsel
            .contentShape(Capsule().inset(by: -(44 - WatchlistSearch.buttonSize) / 2))
        }
        .buttonStyle(.borderless)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("shortcut_add"))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(UiTestTags.watchlistAddPair)
        // Bei `.xxxLarge` gedeckelt: darüber Symbol und Text gross beim langen Drücken
        .largeContentLabel(L("shortcut_add"), systemImage: "plus")
    }

    private var hasActiveAlarms: Bool {
        data.activeAlarmCounts.values.contains { $0 > 0 }
    }

    /// Beschriftung der Glocke nach ihrem Ziel (`openBell`): ohne Alarm «Neuer Alarm», sonst «Alarme (3)» — gleiche
    /// Zählung wie `openBell` (wie Android `AlarmBell`).
    private var bellLabel: String {
        let count = data.alarmsWithWatch.count + (data.settings.portfolioEnabled ? data.portfolioAlarms.count : 0)
        return count == 0 ? L("alarms_add") : L("watchlist_alarms_count", count: count)
    }
}

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
