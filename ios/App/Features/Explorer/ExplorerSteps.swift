import SwiftUI

/// Schritt 1 (Börse, Abgleich) und Schritt 2 (Paar, Kursvorschau). Wie `ExplorerSteps.kt`.
extension ExplorerScreen {
    // MARK: Schritt 1: Börse

    var marketStep: some View {
        ExplorerStepCard(highlighted: vm.currentMarket == nil) {
            ExplorerStepHeader(
                number: 1,
                title: L("market_screen_market"),
                active: true,
                done: vm.currentMarket != nil && (vm.hasPairs || vm.isDex)
            )
            HStack(spacing: 8) {
                ExplorerPickerField(
                    label: L("market_screen_market"),
                    value: vm.currentMarket?.name,
                    placeholder: L("explorer_select_placeholder")
                ) { picker = .market }

                if !vm.isDex {
                    // Paare neu laden — erst aktiv, wenn eine Börse gewählt ist.
                    Button {
                        vm.syncCurrencyPairs()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.title3.weight(.semibold))
                            .symbolEffect(.pulse, isActive: vm.syncing)
                            .frame(width: 56, height: 56)
                            .background(accent.container.opacity(syncEnabled ? 1 : 0.4),
                                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .foregroundStyle(accent.onContainer.opacity(syncEnabled ? 1 : 0.5))
                    }
                    .buttonStyle(ExplorerPressStyle())
                    .disabled(!syncEnabled)
                    .accessibilityLabel(L("market_screen_sync"))
                    .contextMenu {
                        if vm.currentMarket != nil {
                            Button {
                                showSyncSheet = true
                            } label: {
                                Label(L("checker_add_dynamic_currency_pairs_dialog_title"), systemImage: "info.circle")
                            }
                        }
                    }
                }
            }

            marketStatus
        }
    }

    private var syncEnabled: Bool { vm.currentMarket != nil && vm.canUpdatePairs && !vm.syncing }

    @ViewBuilder
    private var marketStatus: some View {
        if vm.currentMarket == nil {
            ExplorerHint(text: L("explorer_select_market_hint"))
        } else if vm.isDex {
            EmptyView()
        } else if vm.syncing {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ProgressView().progressViewStyle(.linear).tint(accent.primary)
                Text(L("explorer_loading_pairs"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.top, 12)
        } else if let error = vm.updateState.error {
            ExplorerErrorRow(message: error) { vm.syncCurrencyPairs() }
        } else if vm.hasPairs, let info = vm.pairsInfo {
            Button {
                showSyncSheet = true
            } label: {
                HStack(spacing: Spacing.xs) {
                    Image(systemName: "checkmark.seal.fill")
                        .foregroundStyle(accent.primary)
                    Text(L("explorer_pairs_status", count: info.count, info.count, lastSyncText(info)))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .multilineTextAlignment(.leading)
                    Image(systemName: "info.circle")
                        .foregroundStyle(AppColors.outline)
                }
                .font(.footnote)
            }
            .buttonStyle(.borderless)
            .padding(.top, Spacing.sm)
        } else if !vm.canUpdatePairs {
            ExplorerHint(text: L("checker_add_check_currency_empty_warning_title"))
        }
    }

    private func lastSyncText(_ info: MarketPairsInfo) -> String {
        info.lastSyncDate > 0
            ? TickerView.sameDayTimeOrDate(info.lastSyncDate)
            : L("checker_add_dynamic_currency_pairs_dialog_last_sync_never")
    }

    // MARK: Schritt 2: Paar

    var pairStep: some View {
        ExplorerStepCard(highlighted: vm.hasPairs && !vm.pairSelected) {
            // Unter den Registern steht der Titel schon im Register
            if !bulkTabbed {
                ExplorerStepHeader(
                    number: 2,
                    title: L("explorer_step_pair"),
                    active: vm.hasPairs,
                    done: vm.pairSelected
                )
            }

            if vm.hasPairs {
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
                    // Kontrakt nur bei Futures; auswählbar nur bei mehreren Laufzeiten.
                    if vm.hasContractTypes {
                        ExplorerPickerField(
                            label: L("market_screen_contract_type"),
                            value: vm.currentContract.map(ExplorerViewModel.contractName),
                            enabled: vm.contractSelectable
                        ) { picker = .contract }
                        .transition(.opacity)
                    }
                }
                .animation(.snappy(duration: 0.25), value: vm.hasContractTypes)
                ExplorerHint(text: L("hint_favorites_list"))
            } else if !vm.syncing {
                // Antippbar ist nur der Knopf — der Text sagt nicht mehr «hier tippen»
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

            // Kurs lädt automatisch, sobald ein Paar gewählt ist — über dem Knopf
            if vm.pairSelected {
                tickerPreview
                    .padding(.top, Spacing.md)
                    .transition(.opacity)
            }

            // Ziel-Gruppe für das neue Paar
            if !vm.baseAssets.isEmpty {
                ExplorerGroupTargetSelector()
                    .padding(.top, Spacing.md)
            }

            Button {
                vm.addCurrentPair()
            } label: {
                Label(L("explorer_add_to_watchlist"), systemImage: "plus")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.lg)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(!(vm.pairSelected && !vm.syncing))
            .padding(.top, vm.baseAssets.isEmpty ? Spacing.md : Spacing.sm)
        }
    }

    @ViewBuilder
    private var tickerPreview: some View {
        Group {
            switch vm.ticker {
            case .none, .some(.loading):
                HStack(spacing: Spacing.sm) {
                    ProgressView().controlSize(.small).tint(accent.primary)
                    Text(L("explorer_price_loading"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
                .padding(Spacing.md)
            case .some(.failed(let error)):
                ExplorerErrorRow(message: error) { vm.retryTicker() }
                    .padding(.top, -10)
            case .some(.loaded(let ticker, let pair)):
                TickerView(ticker: ticker, base: pair.base, quote: pair.quote)
                    .padding(Spacing.md)
            }
        }
        .background(
            LinearGradient(
                colors: [accent.container.opacity(0.35), AppColors.containerHigh.opacity(0.6)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .animation(.easeInOut(duration: 0.25), value: tickerStateKey)
    }

    private var tickerStateKey: String {
        switch vm.ticker {
        case .none: return "none"
        case .some(.loading): return "loading"
        case .some(.failed(let e)): return "failed:\(e)"
        case .some(.loaded(let t, let p)): return "loaded:\(p.description):\(t.last)"
        }
    }
}
