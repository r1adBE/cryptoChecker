import SwiftUI

/// «Paar bearbeiten» (Stift neben dem Paar im Aktionsblatt), volle Höhe — wie
/// `WatchEditSheet.kt`: Börse, Coin, Gegenwert und Kontrakt mit der Logik von «Genau
/// auswählen» (`ExplorerViewModel`, eigene Instanz), vorbelegt mit dem Eintrag. «Sichern»
/// ändert denselben Eintrag (`AppData.changePair`); steht das Paar schon in der Merkliste,
/// bleibt das Blatt offen und sagt es. Hat das Paar Alarme und ist etwas anderes gewählt:
/// «Alarme bleiben bestehen – Schwellen prüfen».
@MainActor
struct WatchEditSheet: View {
    let watch: Watch
    let alarmCount: Int

    @EnvironmentObject private var data: AppData
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appAccent) private var accent
    @StateObject private var vm = ExplorerViewModel()
    @State private var picker: ExplorerPickerKind?
    @State private var duplicate = false
    @State private var prefilled = false

    /// Börsen ohne DEX (Pools findet nur die DEX-Suche beim Hinzufügen).
    private var markets: [Market] { vm.markets.filter { $0.key != ExplorerViewModel.dexKey } }

    private var current: WatchEdit.Key { WatchEdit.Key(watch) }

    private var target: WatchEdit.Key? {
        guard let market = vm.currentMarket, let pair = vm.currentPair else { return nil }
        return WatchEdit.Key(marketKey: market.key, pair: pair)
    }

    private var canSave: Bool {
        guard let target else { return false }
        return target != current && !vm.syncing && !duplicate
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(verbatim: "\(BidiText.isolate(watch.displayName)) · \(BidiText.isolate(watch.marketName))")
                        .font(.title3.weight(.semibold))
                        .padding(.bottom, 4)
                    form
                    if WatchEdit.warnAlarms(current: current, target: target, alarmCount: alarmCount) {
                        Label(L("watch_edit_alarms_warning"), systemImage: "alarm")
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if duplicate {
                        Text(L("explorer_already_in_watchlist"))
                            .font(.subheadline)
                            .foregroundStyle(AppColors.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, Spacing.lg)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .animation(.snappy(duration: 0.25), value: vm.hasPairs)
                .animation(.snappy(duration: 0.25), value: duplicate)
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("watch_edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) { save() }
                        .fontWeight(.semibold)
                        .disabled(!canSave)
                }
            }
            .sheet(item: $picker) { kind in
                pickerSheet(kind)
                    .environment(\.appAccent, accent)
            }
        }
        .tint(accent.primary)
        .task {
            // Nur beim ersten Erscheinen vorbelegen (nicht nach einer Auswahlliste)
            guard !prefilled else { return }
            prefilled = true
            vm.prefill(watch)
        }
        // Andere Auswahl: Hinweis «steht schon in der Merkliste» zurücknehmen
        .onChange(of: target) { _, _ in duplicate = false }
    }

    private func save() {
        guard let market = vm.currentMarket, let pair = vm.currentPair else { return }
        switch data.changePair(watch.id, market: market, pair: pair) {
        case .duplicate:
            WatchlistHaptics.selection()
            duplicate = true
            AccessibilityNotification.Announcement(L("explorer_already_in_watchlist")).post()
        case .changed:
            WatchlistHaptics.impact(.light)
            dismiss()
        case .unchanged, nil:
            dismiss()
        }
    }

    // MARK: Formular

    private var form: some View {
        ExplorerStepCard {
            ExplorerPickerField(
                label: L("market_screen_market"),
                value: vm.currentMarket?.name,
                placeholder: L("explorer_select_placeholder")
            ) { picker = .market }

            if vm.currentMarket == nil {
                ExplorerHint(text: L("explorer_select_market_hint"))
            } else if vm.syncing || (vm.pairsInfo == nil && vm.updateState.error == nil) {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    ProgressView().progressViewStyle(.linear).tint(accent.primary)
                    Text(L("explorer_loading_pairs"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .padding(.top, 12)
            } else if let error = vm.updateState.error {
                ExplorerErrorRow(message: error) { vm.syncCurrencyPairs() }
            } else if vm.hasPairs {
                VStack(spacing: Spacing.sm) {
                    ExplorerPickerField(
                        label: L("market_screen_base"),
                        value: vm.currentBase,
                        badge: true
                    ) { picker = .base }
                    ExplorerPickerField(
                        label: L("market_screen_quote"),
                        value: vm.currentQuote
                    ) { picker = .quote }
                    // Kontrakt nur bei Futures; auswählbar nur bei mehreren Laufzeiten
                    if vm.hasContractTypes {
                        ExplorerPickerField(
                            label: L("market_screen_contract_type"),
                            value: vm.currentContract.map(ExplorerViewModel.contractName),
                            enabled: vm.contractSelectable
                        ) { picker = .contract }
                    }
                }
                .padding(.top, Spacing.sm)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    ExplorerHint(text: L("explorer_no_pairs_yet"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button(L("market_screen_sync")) { vm.syncCurrencyPairs() }
                        .font(.footnote.weight(.semibold))
                        .tint(accent.primary)
                        .buttonStyle(.borderless)
                        .disabled(!vm.canUpdatePairs)
                }
            }
        }
    }

    // MARK: Auswahllisten

    @ViewBuilder
    private func pickerSheet(_ kind: ExplorerPickerKind) -> some View {
        switch kind {
        case .market:
            let list = markets
            ExplorerPickerSheet(
                title: L("market_screen_market"),
                items: list.map(\.name),
                selected: vm.currentMarket?.name,
                favoriteKind: .MARKET,
                data: data
            ) { name in
                if let market = list.first(where: { $0.name == name }) {
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
