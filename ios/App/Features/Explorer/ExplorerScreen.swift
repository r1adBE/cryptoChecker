import SwiftUI
import UIKit

/// Seite «Paar hinzufügen»: Suche über alle Börsen, Schritt für Schritt ein Paar wählen,
/// mehrere Paare auf einmal, DEX-Pools. Wie `ExplorerScreen.kt`.
///
/// Seit Runde 31 kein Tab mehr: die Merkliste legt sie auf ihren `NavigationStack`
/// (`AppRouter.showExplorer` — «+», Shortcut, Widget, Link "add", «Heute auffällig»).
struct ExplorerScreen: View {
    @StateObject var vm = ExplorerViewModel()
    @EnvironmentObject var data: AppData
    @EnvironmentObject private var router: AppRouter
    @Environment(\.appAccent) var accent

    @State var picker: ExplorerPickerKind?
    @State var showSyncSheet = false
    // Auf-/Zugeklappt merkt sich die Sitzung (ExplorerSectionMemory), nicht nur dieser Bildschirm
    @State private var showPrecise: Bool
    @State var showBulk: Bool
    @State var showBulkList = false
    @State var confirmBulk = false
    @State var dexQuery = ""
    @FocusState var searchFocused: Bool
    @FocusState var dexFocused: Bool

    init() {
        _showPrecise = State(initialValue: ExplorerSectionMemory.precise)
        _showBulk = State(initialValue: ExplorerSectionMemory.bulk)
    }

    var body: some View {
        ScrollView {
            // Eine Seite, die als Ganzes scrollt — das Suchfeld scrollt mit.
            VStack(alignment: .leading, spacing: 12) {
                searchField

                if !vm.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                    // Während der Suche nur die Treffer, sonst der Auswahl-Ablauf
                    searchResults
                        .transition(.opacity.combined(with: .move(edge: .top)))
                } else {
                    Group {
                        // Die Suche ist der Hauptweg; das genaue Auswählen ist eingeklappt.
                        Text(L("explorer_search_intro"))
                            .font(.footnote)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 4)

                        preciseToggle
                            .padding(.top, 8)

                        if showPrecise {
                            marketStep
                                .transition(.opacity.combined(with: .move(edge: .top)))

                            if vm.currentMarket != nil && vm.isDex {
                                dexSection
                                    .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
                            }

                            if vm.currentMarket != nil && !vm.isDex {
                                pairStep
                                    .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .top)))
                                if vm.hasPairs && !vm.bulkQuotes.isEmpty {
                                    bulkSection
                                        .transition(.opacity)
                                }
                            }
                        }

                        // Runde 13b: Börse fehlt? → GitHub-Vorlage «Exchange request»
                        if let url = URL(string: AppLinks.exchangeRequest) {
                            HStack(spacing: Spacing.xs) {
                                Text(L("explorer_exchange_missing"))
                                    .foregroundStyle(AppColors.onSurfaceVariant)
                                Link(L("about_request_exchange"), destination: url)
                                    .fontWeight(.semibold)
                                    .tint(accent.primary)
                            }
                            .font(.footnote)
                            .frame(maxWidth: .infinity)
                            .padding(.top, 4)
                        }
                    }
                    .transition(.opacity)
                }

                // Entwickleroption: HTTP-Protokoll (Einstellungen → Entwickler)
                if data.settings.showHttpLog && data.settings.developerUnlocked {
                    ExplorerLogBox()
                        .padding(.top, 8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 32)
            // iPad/Querformat: Inhalt höchstens 640 pt breit, mittig
            .readableContentWidth()
            .animation(.snappy(duration: 0.3), value: vm.searchQuery.isEmpty)
            .animation(.snappy(duration: 0.3), value: vm.currentMarket?.key)
            .animation(.snappy(duration: 0.3), value: vm.hasPairs)
            .animation(.snappy(duration: 0.3), value: vm.syncing)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(AppColors.background.ignoresSafeArea())
        // Eigene Seite über der Merkliste (Runde 31): Titel wie der «+»-Knopf, ohne Tableiste
        .navigationTitle(L("shortcut_add"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .overlay(alignment: .bottom) {
            ExplorerSnackbar(
                message: $vm.message,
                onView: { router.showExplorer = false },
                onAlarm: { id in
                    router.showExplorer = false
                    router.openAlarmsWatchId = id
                }
            )
        }
        .animation(.spring(duration: 0.35), value: vm.message)
        // «Heute auffällig» im Markt-Tab: mit der Suche nach dem Coin hierher
        .onChange(of: router.explorerSearch, initial: true) { _, query in
            guard let query else { return }
            vm.setSearchQuery(query)
            router.explorerSearch = nil
        }
        .sheet(item: $picker) { kind in
            pickerSheet(kind)
                .environment(\.appAccent, accent)
        }
        .sheet(isPresented: $showSyncSheet) {
            ExplorerSyncSheet(
                marketName: vm.currentMarket?.name ?? "",
                info: vm.pairsInfo,
                state: vm.updateState,
                onSync: { vm.syncCurrencyPairs() }
            )
            .environment(\.appAccent, accent)
        }
        .alert(
            L("explorer_bulk_confirm_title", count: vm.bulkPairs.count, vm.bulkPairs.count),
            isPresented: $confirmBulk
        ) {
            Button(L("action_cancel"), role: .cancel) {}
            Button(L("explorer_add_to_watchlist")) { vm.addAllBulkPairs() }
        } message: {
            Text(L("explorer_bulk_confirm_text", count: vm.bulkPairs.count, vm.bulkPairs.count, vm.bulkQuote ?? "", vm.currentMarket?.name ?? ""))
        }
    }

    // MARK: Genau auswählen (eingeklappt)

    private var preciseToggle: some View {
        Button {
            let expanded = !showPrecise
            ExplorerSectionMemory.precise = expanded
            withAnimation(.snappy(duration: 0.3)) { showPrecise = expanded }
        } label: {
            HStack(spacing: Spacing.sm) {
                Image(systemName: "slider.horizontal.3")
                    .foregroundStyle(accent.primary)
                Text(L("explorer_precise_title"))
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .rotationEffect(.degrees(showPrecise ? 90 : 0))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, Spacing.lg)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: Auswahllisten

    @ViewBuilder
    private func pickerSheet(_ kind: ExplorerPickerKind) -> some View {
        switch kind {
        case .market:
            ExplorerPickerSheet(
                title: L("market_screen_market"),
                items: vm.markets.map(\.name),
                selected: vm.currentMarket?.name,
                favoriteKind: .MARKET,
                data: data
            ) { name in
                if let market = vm.markets.first(where: { $0.name == name }) {
                    withAnimation(.snappy(duration: 0.3)) { vm.setCurrentMarket(market) }
                }
            }
        case .base:
            ExplorerPickerSheet(
                title: L("market_screen_base"),
                items: vm.baseAssets,
                selected: vm.currentBase,
                favoriteKind: .COIN,
                badges: true,
                data: data
            ) { vm.setBase($0) }
        case .quote:
            ExplorerPickerSheet(
                title: L("market_screen_quote"),
                items: vm.quoteAssets,
                selected: vm.currentQuote,
                favoriteKind: .QUOTE,
                data: data
            ) { vm.setQuote($0) }
        case .contract:
            let types = vm.contractTypes
            ExplorerPickerSheet(
                title: L("market_screen_contract_type"),
                items: types.map(ExplorerViewModel.contractName),
                selected: vm.currentContract.map(ExplorerViewModel.contractName),
                favoriteKind: nil,
                data: data
            ) { name in
                if let type = types.first(where: { ExplorerViewModel.contractName($0) == name }) {
                    vm.setContract(type)
                }
            }
        }
    }
}

/// Welche Auswahlliste offen ist.
enum ExplorerPickerKind: String, Identifiable {
    case market, base, quote, contract
    var id: String { rawValue }
}

/// Auf-/Zuklappen von «Genau auswählen» und «Mehrere Paare auf einmal» bleibt für die
/// ganze Sitzung (bis die App beendet wird). Standard: eingeklappt — die Suche ist der
/// Hauptweg. Wie `ExplorerSections` in `ExplorerScreen.kt`.
enum ExplorerSectionMemory {
    nonisolated(unsafe) static var precise = false
    nonisolated(unsafe) static var bulk = false
}
