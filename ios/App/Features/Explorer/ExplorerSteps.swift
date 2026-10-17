import SwiftUI

/// Die zwei Schritte der genauen Auswahl: 1 = Börse samt Abgleich der Paarliste, 2 = Paar mit
/// Kursvorschau und Aufnahme. Gegenstück zu `ExplorerSteps.kt`.
extension ExplorerScreen {
    // MARK: Schritt 1 – Börse

    var marketStep: some View {
        ExplorerStepCard(highlighted: vm.exchange == nil) {
            ExplorerStepHeader(
                number: 1,
                title: L("explorer_field_exchange"),
                active: true,
                done: marketReady
            )
            HStack(spacing: 8) {
                ExplorerPickerField(
                    label: L("explorer_field_exchange"),
                    value: vm.exchange?.name,
                    placeholder: L("explorer_select_placeholder")
                ) { picker = .market }

                if !vm.isDex {
                    reloadPairsButton
                }
            }

            marketStatusLine
        }
    }

    /// Schritt 1 erledigt: Börse gewählt und Paare da (bzw. DEX, dort gibt es keine Liste).
    private var marketReady: Bool {
        guard vm.exchange != nil else { return false }
        return vm.isDex || vm.hasPairs
    }

    /// Neu laden geht erst mit gewählter Börse, die das kann, und nicht doppelt.
    private var canReloadPairs: Bool {
        vm.exchange != nil && vm.canReloadPairs && !vm.syncing
    }

    /// Runder Knopf rechts neben der Börse: Paarliste neu holen; langes Drücken zeigt die Details.
    private var reloadPairsButton: some View {
        let dim = !canReloadPairs
        return Button {
            vm.reloadPairList()
        } label: {
            Image(systemName: "arrow.clockwise")
                .font(.title3.weight(.semibold))
                .symbolEffect(.pulse, isActive: vm.syncing)
                .frame(width: 56, height: 56)
                .background(accent.container.opacity(dim ? 0.4 : 1),
                            in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .foregroundStyle(accent.onContainer.opacity(dim ? 0.5 : 1))
        }
        .buttonStyle(ExplorerPressStyle())
        .disabled(dim)
        .accessibilityLabel(L("explorer_reload_pairs"))
        .contextMenu {
            if vm.exchange != nil {
                Button {
                    showSyncSheet = true
                } label: {
                    Label(L("explorer_sync_sheet_title"), systemImage: "info.circle")
                }
            }
        }
    }

    /// Zeile unter der Börse: Hinweis, Ladebalken, Fehler oder Anzahl Paare mit Datum.
    @ViewBuilder
    private var marketStatusLine: some View {
        if vm.exchange == nil {
            ExplorerHint(text: L("explorer_select_market_hint"))
        } else if vm.isDex {
            EmptyView()
        } else if vm.syncing {
            pairsLoadingBar
        } else if let failure = vm.pairListSync.error {
            ExplorerErrorRow(message: failure) { vm.reloadPairList() }
        } else if vm.hasPairs, let info = vm.pairList {
            pairsSummaryButton(info)
        } else if !vm.canReloadPairs {
            ExplorerHint(text: L("explorer_no_pair_list"))
        }
    }

    private var pairsLoadingBar: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            ProgressView().progressViewStyle(.linear).tint(accent.primary)
            Text(L("explorer_loading_pairs"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.top, 12)
    }

    private func pairsSummaryButton(_ info: MarketPairsInfo) -> some View {
        Button {
            showSyncSheet = true
        } label: {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundStyle(accent.primary)
                Text(L("explorer_pairs_status", count: info.count, info.count, syncDateText(info)))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .multilineTextAlignment(.leading)
                Image(systemName: "info.circle")
                    .foregroundStyle(AppColors.outline)
            }
            .font(.footnote)
        }
        .buttonStyle(.borderless)
        .padding(.top, Spacing.sm)
    }

    /// «Heute 14:05» bzw. Datum; nie geladen → eigener Text.
    private func syncDateText(_ info: MarketPairsInfo) -> String {
        guard info.lastSyncDate > 0 else { return L("explorer_sync_never") }
        return TickerView.sameDayTimeOrDate(info.lastSyncDate)
    }

    // MARK: Schritt 2 – Paar

    var pairStep: some View {
        ExplorerStepCard(highlighted: vm.hasPairs && !vm.pairSelected) {
            // Mit Registern steht der Titel bereits im Register
            if !bulkTabbed {
                ExplorerStepHeader(
                    number: 2,
                    title: L("explorer_step_pair"),
                    active: vm.hasPairs,
                    done: vm.pairSelected
                )
            }

            if vm.hasPairs {
                pairFields
                ExplorerHint(text: L("hint_favorites_list"))
            } else if !vm.syncing {
                noPairsRow
            }

            // Kurs kommt von selbst, sobald ein Paar feststeht – über dem Knopf
            if vm.pairSelected {
                priceCard
                    .padding(.top, Spacing.md)
                    .transition(.opacity)
            }

            // In welche Gruppe das neue Paar kommt
            if !vm.coins.isEmpty {
                ExplorerGroupTargetSelector()
                    .padding(.top, Spacing.md)
            }

            addButton
        }
    }

    private var pairFields: some View {
        VStack(spacing: Spacing.sm) {
            ExplorerPickerField(
                label: L("explorer_field_coin"),
                value: vm.coin,
                badge: true
            ) { picker = .base }
            ExplorerPickerField(
                label: L("explorer_field_quote"),
                value: vm.quote
            ) { picker = .quote }
            // Kontraktfeld nur bei Futures; antippbar nur mit mehreren Laufzeiten
            if vm.hasContracts {
                ExplorerPickerField(
                    label: L("explorer_field_contract"),
                    value: vm.contract.map(ExplorerViewModel.contractName),
                    enabled: vm.contractSelectable
                ) { picker = .contract }
                .transition(.opacity)
            }
        }
        .animation(.snappy(duration: 0.25), value: vm.hasContracts)
    }

    /// Noch keine Paare: Hinweis und daneben der Knopf zum Laden (nur dieser ist antippbar).
    private var noPairsRow: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            ExplorerHint(text: L("explorer_no_pairs_yet"))
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(L("explorer_reload_pairs")) { vm.reloadPairList() }
                .font(.footnote.weight(.semibold))
                .tint(accent.primary)
                .buttonStyle(.borderless)
                .disabled(!vm.canReloadPairs)
        }
    }

    private var addButton: some View {
        let ready = vm.pairSelected && !vm.syncing
        return Button {
            vm.addSelectedPair()
        } label: {
            Label(L("explorer_add_to_watchlist"), systemImage: "plus")
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.lg)
        }
        .buttonStyle(AccentButtonStyle())
        .disabled(!ready)
        .padding(.top, vm.coins.isEmpty ? Spacing.md : Spacing.sm)
    }

    // MARK: Kursvorschau

    private var priceCard: some View {
        priceCardContent
            .background(
                LinearGradient(
                    colors: [accent.container.opacity(0.35), AppColors.containerHigh.opacity(0.6)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                ),
                in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
            )
            .animation(.easeInOut(duration: 0.25), value: priceCardPhase)
    }

    @ViewBuilder
    private var priceCardContent: some View {
        switch vm.price {
        case .some(.loaded(let price, let pair)):
            TickerView(ticker: price, base: pair.base, quote: pair.quote)
                .padding(Spacing.md)
        case .some(.failed(let reason)):
            ExplorerErrorRow(message: reason) { vm.retryPrice() }
                .padding(.top, -10)
        case .none, .some(.loading):
            HStack(spacing: Spacing.sm) {
                ProgressView().controlSize(.small).tint(accent.primary)
                Text(L("explorer_price_loading"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
            .padding(Spacing.md)
        }
    }

    /// Wechselt mit jedem sichtbaren Zustand der Vorschau (für die Überblendung).
    private var priceCardPhase: String {
        guard let state = vm.price else { return "none" }
        switch state {
        case .loading: return "loading"
        case .failed(let reason): return "failed:\(reason)"
        case .loaded(let price, let pair): return "loaded:\(pair.description):\(price.last)"
        }
    }
}
