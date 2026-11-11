import SwiftUI
import UIKit

/// «Paar hinzufügen» (Gegenstück zu `ExplorerScreen.kt`): oben die Coin-Suche (Coin finden, dann sein
/// Paar) und die Markt-Chips, darunter ohne Suchbegriff zuletzt genutzte und alle Börsen; eine Börse
/// öffnet ihre Paare (Paar wählen, mehrere Paare auf einmal) bzw. die Suche nach DEX-Pools.
///
/// Kein eigener Tab: Die Merkliste schiebt die Seite auf ihren `NavigationStack`
/// (`AppRouter.showExplorer` – «+», Shortcut, Widget, Link "add"), «Heute auffällig» auf den Stapel
/// des Markt-Tabs (`AppRouter.showExchangeExplorer`).
struct ExplorerScreen: View {
    @StateObject var vm = ExplorerViewModel()
    @EnvironmentObject var data: AppData
    @EnvironmentObject private var router: AppRouter
    @Environment(\.appAccent) var accent
    @Environment(\.onAccent) var onAccent
    /// «Bewegung reduzieren»: Inhalte wechseln ohne Feder und Schieben (wie die Merkliste).
    @Environment(\.accessibilityReduceMotion) var reduceMotion

    @State var picker: ExplorerPickerKind?
    @State var showSyncSheet = false
    // Register gilt für die ganze Sitzung (ExplorerSectionMemory), nicht nur für diese Ansicht
    @State var showBulk: Bool
    @State var showBulkList = false
    /// Vorschau «Alle …-Paare» ganz aufgeklappt (sonst nur die ersten `bulkPreviewRows`).
    @State var showAllBulkPairs = false
    /// Aktiver Schnellfilter von «Alle …-Paare»; nil heisst normale Reihenfolge.
    @State var bulkFilter: ExplorerQuickFilter?
    @State var confirmBulk = false
    /// Rückfrage «%d Paare wieder entfernen?» nach «Rückgängig» der Sammelaufnahme.
    @State var bulkUndo = BulkUndoConfirm()
    @State var dexQuery = ""
    @FocusState var searchFocused: Bool
    @FocusState var dexFocused: Bool

    init() {
        _showBulk = State(initialValue: ExplorerSectionMemory.bulk)
    }

    /// Steht ein Suchbegriff im Feld?
    private var searching: Bool {
        !vm.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        scrollingPage
            .scrollDismissesKeyboard(.interactively)
            .background(AppColors.background.ignoresSafeArea())
            // Eigene Seite über Merkliste bzw. Markt-Tab: Titel wie der «+»-Knopf, Tableiste weg
            .navigationTitle(L("shortcut_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .tabBar)
            .overlay(alignment: .bottom) { snackbar }
            // Paar nicht übernommen (Merkliste gesperrt): kurzer Hinweis mit Warn-Haptik
            .watchlistLockedHint { vm.show(ExplorerMessage(text: $0, showView: false, long: true, kind: .error)) }
            .animation(reduceMotion ? nil : .spring(duration: 0.35), value: vm.message)
            // «Bewegung reduzieren» gilt für die ganze Seite: auch `withAnimation` und `.animation` der Unteransichten
            // (Börse öffnen, Coin wählen, Register, DEX-Suche) entfallen, ohne jede Stelle einzeln zu kennen
            .transaction { transaction in
                if reduceMotion {
                    transaction.animation = nil
                    transaction.disablesAnimations = true
                }
            }
            // Aus «Heute auffällig» (Markt-Tab): gleich mit dem Coin als Suchbegriff
            .onChange(of: router.explorerSearch, initial: true) { _, incoming in
                guard let incoming else { return }
                vm.setSearchQuery(incoming)
                router.explorerSearch = nil
            }
            .sheet(item: $picker) { kind in
                pickerSheet(kind)
                    .environment(\.appAccent, accent)
            }
            .sheet(isPresented: $showSyncSheet) {
                syncSheet
                    .environment(\.appAccent, accent)
            }
            .alert(bulkConfirmTitle, isPresented: $confirmBulk) {
                Button(L("action_cancel"), role: .cancel) {}
                Button(L("explorer_add_to_watchlist")) { vm.addBulkPairs() }
            } message: {
                Text(bulkConfirmText)
            }
            // «Rückgängig» der Sammelaufnahme: erst «Entfernen» löscht (Paare mit inzwischen gesetztem Alarm bleiben)
            .alert(bulkUndoTitle, isPresented: bulkUndoShown, presenting: bulkUndo.pending) { ids in
                Button(L("action_keep"), role: .cancel) { bulkUndo.keep() }
                    .accessibilityIdentifier(UiTestTags.bulkUndoKeep)
                Button(L("watchlist_remove_not_traded_action"), role: .destructive) {
                    // Zeitfenster abgelaufen: nichts löschen, nur sagen, dass die Paare bleiben — wie Android
                    bulkUndo.remove(ids, now: EpochTime.nowMillis, expired: {
                        vm.show(ExplorerMessage(text: L("bulk_undo_expired"), showView: false))
                    }) {
                        // Erfolgs-Haptik erst beim Entfernen, nicht beim Öffnen des Dialogs — wie Android
                        Haptics.play(.success)
                        vm.undoBulkAdd($0)
                    }
                }
                .accessibilityIdentifier(UiTestTags.bulkUndoRemove)
            } message: { _ in
                Text(L("bulk_undo_confirm_text"))
            }
    }

    // MARK: Seite

    /// Alles in einer Spalte, die als Ganzes scrollt (auch das Suchfeld).
    private var scrollingPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                searchField
                // Markt-Chips filtern Börsen und Treffer; bei offener Börse gehören sie nicht dazu (wie Android)
                if searching || vm.exchange == nil {
                    marketChips
                }

                if searching, let coin = vm.pickedCoin {
                    // Angetippter Coin: Vorschlag oben, darunter seine übrigen Paare
                    coinPairsView(coin)
                        .transition(.opacity)
                } else if searching {
                    // Mit Suchbegriff nur die Treffer, ohne den Auswahl-Ablauf
                    searchResults
                        .transition(reduceMotion ? AnyTransition.opacity
                                    : AnyTransition.opacity.combined(with: .move(edge: .top)))
                } else {
                    browseContent
                        .transition(.opacity)
                }

                // Entwickleroption (Einstellungen › Über die App › Entwickler): HTTP-Protokoll, nur im Debug-Build
                if DeveloperOptions.available && data.settings.showHttpLog && data.settings.developerUnlocked {
                    ExplorerHttpLogPanel()
                        .padding(.top, 8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 32)
            // Breite Bildschirme: höchstens 640 pt, zentriert
            .readableContentWidth()
            .animation(reduceMotion ? nil : .snappy(duration: 0.3), value: vm.searchQuery.isEmpty)
            .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: vm.pickedCoin?.id)
            .animation(reduceMotion ? nil : .snappy(duration: 0.3), value: vm.exchange?.key)
            .animation(reduceMotion ? nil : .snappy(duration: 0.3), value: vm.hasPairs)
            .animation(reduceMotion ? nil : .snappy(duration: 0.3), value: vm.syncing)
        }
    }

    /// Ohne Suchbegriff: letzte Suchen, Hinweis zur Coin-Suche und die Übersicht der Börsen — oder, mit geöffneter
    /// Börse, ihre Paare —, dann der Börsen-Wunsch. Wie `BrowseContent` (Android).
    private var browseContent: some View {
        Group {
            if vm.exchange != nil {
                exchangePairs
                    .transition(.opacity)
            } else {
                // Letzte Suchbegriffe als Chips (nur lokal)
                if !vm.recentSearches.isEmpty {
                    recentSearchChips
                }
                // Hauptweg ist die Coin-Suche; darunter die Börsen
                Text(L("explorer_search_intro"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                browseSections
            }

            // Fehlende Börse → GitHub-Vorlage «Exchange request»
            if let target = URL(string: AppLinks.exchangeRequest) {
                exchangeMissingRow(target)
                    .padding(.top, Spacing.sm)
            }
        }
    }

    /// Paare der geöffneten Börse: «Alle Börsen» (zurück), Kopf der Börse, dann DEX-Suche bzw. Paar-Auswahl und
    /// Sammelaufnahme. Wie `ExchangePairs` (Android).
    @ViewBuilder
    private var exchangePairs: some View {
        Button {
            withAnimation(.snappy(duration: 0.3)) { vm.closeExchange() }
        } label: {
            Label(L("explorer_all_exchanges_title"), systemImage: "chevron.backward")
                .font(.subheadline.weight(.semibold))
                .padding(.vertical, Spacing.xs)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(accent.primary)

        exchangeHeader
        if vm.isDex {
            dexSection
                .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
        } else {
            exchangeSection
        }
    }

    /// Börse mit Paaren: «Paar wählen» und – falls möglich – «Mehrere Paare auf einmal» als Register.
    @ViewBuilder
    private var exchangeSection: some View {
        let tabbed = bulkTabbed
        if tabbed {
            ExplorerModeTabs(bulk: showBulk) { wantsBulk in
                ExplorerSectionMemory.bulk = wantsBulk
                withAnimation(.snappy(duration: 0.25)) { showBulk = wantsBulk }
            }
        }
        if tabbed && showBulk {
            bulkSection
                .transition(.opacity)
        } else {
            pairStep
                .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
        }
    }

    private func exchangeMissingRow(_ target: URL) -> some View {
        HStack(spacing: Spacing.xs) {
            Text(L("explorer_exchange_missing"))
                .foregroundStyle(AppColors.onSurfaceVariant)
            // Externe Verweise sehen überall gleich aus: Themenfarbe plus ↗ (wie in den Einstellungen)
            Link(destination: target) {
                HStack(spacing: 3) {
                    Text(L("about_request_exchange"))
                    Image(systemName: "arrow.up.forward")
                        .imageScale(.small)
                }
            }
            .fontWeight(.semibold)
            .tint(accent.primary)
            .accessibilityHint(L("a11y_external_link"))
        }
        .font(.footnote)
        .frame(maxWidth: .infinity)
        .padding(.top, 4)
    }

    // MARK: Überlagerungen

    private var snackbar: some View {
        ExplorerSnackbar(
            message: $vm.message,
            onView: { router.closeExplorerToWatchlist() },
            onAlarm: { watchId in
                router.closeExplorerToWatchlist()
                router.openAlarmsWatchId = watchId
            },
            onRetryDex: { vm.searchDex($0) },
            onUndo: { bulkUndo.request($0, finishedAt: $1) }
        )
    }

    /// «746 Paare wieder entfernen?» (Zahl der neu angelegten Paare, beim Ausblenden unverändert).
    private var bulkUndoTitle: String {
        let n = bulkUndo.lastCount
        return L("bulk_undo_confirm_title", count: n, n)
    }

    /// Dialog offen, solange Paare gemerkt sind; Schliessen ohne Knopf gilt als «Behalten».
    private var bulkUndoShown: Binding<Bool> {
        Binding(get: { bulkUndo.isPresented }, set: { if !$0 { bulkUndo.keep() } })
    }

    private var syncSheet: some View {
        ExplorerSyncSheet(
            exchangeName: vm.exchange?.name ?? "",
            info: vm.pairList,
            state: vm.pairListSync,
            onSync: { vm.reloadPairList() }
        )
    }

    /// Titel nennt die neuen («312 Paare hinzufügen?»), der Text alle samt Hinweis auf vorhandene — wie Android.
    private var bulkConfirmTitle: String {
        let n = bulkNewCount
        return L("explorer_bulk_confirm_title", count: n, n)
    }

    /// Davon noch nicht in der Merkliste (`ExplorerBulkCount`); 0 = Knopf aus.
    var bulkNewCount: Int {
        ExplorerBulkCount.newCount(exchangeKey: vm.exchange?.key, pairs: vm.bulkPairs, watches: data.watches)
    }

    private var bulkConfirmText: String {
        let n = vm.bulkPairs.count
        return L("explorer_bulk_confirm_text", count: n, n, vm.bulkQuote ?? "", vm.exchange?.name ?? "")
    }

    // MARK: Auswahllisten

    @ViewBuilder
    private func pickerSheet(_ kind: ExplorerPickerKind) -> some View {
        switch kind {
        case .exchange:
            // Börsen wählt die Übersicht («Alle Börsen»), keine Auswahlliste mehr
            EmptyView()
        case .base:
            ExplorerPickerSheet(
                title: L("explorer_field_coin"),
                items: vm.coins,
                selected: vm.coin,
                favoriteKind: .COIN,
                badges: true,
                data: data
            ) { vm.selectCoin($0) }
        case .quote:
            ExplorerPickerSheet(
                title: L("explorer_field_quote"),
                items: vm.quotes,
                selected: vm.quote,
                favoriteKind: .QUOTE,
                data: data
            ) { vm.selectQuote($0) }
        case .contract:
            let kinds = vm.contracts
            ExplorerPickerSheet(
                title: L("explorer_field_contract"),
                items: kinds.map(ExplorerViewModel.contractName),
                selected: vm.contract.map(ExplorerViewModel.contractName),
                favoriteKind: nil,
                data: data
            ) { name in
                guard let chosen = kinds.first(where: { ExplorerViewModel.contractName($0) == name }) else { return }
                vm.selectContract(chosen)
            }
        }
    }
}

/// Die gerade geöffnete Auswahlliste.
enum ExplorerPickerKind: String, Identifiable {
    case exchange, base, quote, contract
    var id: String { rawValue }
}

/// Ob das Register «Mehrere Paare auf einmal» gewählt ist, bleibt bis zum Beenden der App erhalten; anfangs «Paar
/// wählen». Gegenstück zu `ExplorerSectionsState` (`rememberExplorerSections`) in `ExplorerComponents.kt`.
enum ExplorerSectionMemory {
    // Hinter einer Sperre statt `nonisolated(unsafe)`: thread-sicher, ohne Aufrufer an den MainActor zu binden
    private static let state = LockedBox(false)
    static var bulk: Bool {
        get { state.read { $0 } }
        set { state.write { $0 = newValue } }
    }
}
