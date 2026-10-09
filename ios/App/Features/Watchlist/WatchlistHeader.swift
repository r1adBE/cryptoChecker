import SwiftUI
import UIKit

/// Fester Kopf über der Liste: Logo, Gruppen-Chips und Knöpfe — wie `WatchlistHeader.kt`.
extension WatchlistScreen {
    /// Fester Kopf über der Liste: Kopfzeile (Logo, Gruppen-Chips, Knöpfe) und darunter
    /// Status mit Lupe bzw. das Suchfeld. Höchstens `ReadableWidth.max` breit wie die Zeilen;
    /// eigener Hintergrund, damit gescrollte Zeilen darunter verschwinden.
    func pinnedHeader(_ watches: [Watch], groups: [String], selectedGroup: String?) -> some View {
        VStack(spacing: 4) {
            headerRow(watches, groups: groups, selectedGroup: selectedGroup)
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
    private func headerRow(_ watches: [Watch], groups: [String], selectedGroup: String?) -> some View {
        HStack(spacing: 0) {
            WatchlistGroupChips(
                groups: groups,
                selected: selectedGroup,
                hasFavorites: watches.contains { $0.favorite },
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
            // Gescrollte Chips nicht unter die Knöpfe zeichnen
            .clipped()
            .padding(.trailing, 4)
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
                        .padding(.horizontal, Spacing.sm)
                        .frame(minHeight: Self.headerHeight)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .tint(accent.primary)
            } else {
                // Glocke nur mit aktiven Alarmen und ohne Zahl (eine Zahl läse sich leicht als
                // «so oft ausgelöst»); «Alle Alarme» steht immer auch im Menü
                if hasActiveAlarms {
                    Button {
                        showOverview = true
                    } label: {
                        Image(systemName: "bell")
                            .symbolRenderingMode(.hierarchical)
                            .phaseAnimator([1.0, AlarmPulse.scale, 1.0], trigger: bellPulse) { view, scale in
                                view.scaleEffect(scale)
                            } animation: { _ in .easeInOut(duration: AlarmPulse.seconds / 2) }
                            .frame(width: Self.headerHeight, height: Self.headerHeight)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .tint(accent.primary)
                    .accessibilityLabel(L("alarms_overview_title"))
                }

                // Menü: oben Logo und App-Name («Über die App»), dann Aktualisieren, Alle Alarme, Sortieren,
                // Bericht, nicht gehandelte Paare entfernen, Merkliste leeren
                Menu {
                    // Gleicher Anfang wie Markt und Portfolio: App (→ «Über»), Aktualisieren
                    AppMenuHead(
                        refreshing: data.refreshing,
                        onOpenAbout: { showAbout = true },
                        onRefresh: {
                            WatchlistHaptics.impact(.light)
                            Task { await refreshByUser() }
                        }
                    )
                    Button {
                        showOverview = true
                    } label: {
                        Label(L("alarms_overview_title"), systemImage: "bell")
                    }
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

    /// «+»: Seite «Paar hinzufügen» (Runde 31, ersetzt den Tab «Suchen»).
    var addPairButton: some View {
        Button {
            router.openExplorer()
        } label: {
            Image(systemName: "plus")
                .frame(width: Self.headerHeight, height: Self.headerHeight)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .tint(accent.primary)
        .font(.body)
        .imageScale(.large)
        .accessibilityLabel(L("shortcut_add"))
    }

    private var hasActiveAlarms: Bool {
        data.activeAlarmCounts.values.contains { $0 > 0 }
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
