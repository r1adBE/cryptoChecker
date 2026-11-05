import SwiftUI

/// Paare einer geöffneten Börse: Kopf der Börse samt Abgleich der Paarliste, dann Paar mit Kursvorschau und
/// Aufnahme. Gegenstück zu `ExplorerSteps.kt`.
extension ExplorerScreen {
    // MARK: Kopf der Börse

    /// Zeichen und Name (Überschrift), rechts «Paare neu laden»; darunter Ladebalken, Fehler oder Zahl der Paare und
    /// bei den EZB-Kursen der Hinweis «Tageskurs …». Wie `ExchangeHeaderCard` (Android).
    var exchangeHeader: some View {
        ExplorerStepCard {
            HStack(spacing: Spacing.md) {
                ExplorerExchangeMarkView(key: vm.exchange?.key ?? "", name: vm.exchange?.name ?? "", size: 40)
                Text(vm.exchange?.name ?? "")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityAddTraits(.isHeader)
                if !vm.isDex {
                    reloadPairsButton
                }
            }

            exchangeStatusLine
            // Referenzkurse: nicht live, keine Alarme (EZB «Tageskurs …», NBU/NBP «Zentralbank»)
            if vm.isReferenceRate {
                let ecb = ReferenceSource.of(vm.exchange?.key).map { $0 == .ecb } ?? true
                ExplorerHint(text: L(ecb ? "explorer_forex_hint" : "explorer_forex_hint_bank"))
            }
        }
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
                .background(dim ? AppColors.disabledContainer : accent.container,
                            in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .foregroundStyle(dim ? AppColors.disabledContent : accent.onContainer)
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

    /// Zeile unter der Börse: Ladebalken, Fehler oder Anzahl Paare mit Datum.
    @ViewBuilder
    private var exchangeStatusLine: some View {
        if vm.exchange == nil || vm.isDex {
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

    private func pairsSummaryButton(_ info: ExchangePairsInfo) -> some View {
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
    private func syncDateText(_ info: ExchangePairsInfo) -> String {
        guard info.lastSyncDate > 0 else { return L("explorer_sync_never") }
        return TickerView.sameDayTimeOrDate(info.lastSyncDate)
    }

    // MARK: Paar

    var pairStep: some View {
        ExplorerStepCard(highlighted: vm.hasPairs && !vm.pairSelected) {
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
                .appDisabled(!vm.canReloadPairs)
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
            VStack(alignment: .leading, spacing: Spacing.xs) {
                TickerView(ticker: price, base: pair.base, quote: pair.quote)
                // Referenzkurs: «Quelle: …», bei Kreuzkurs «· umgerechnet» (die Vorschau zeigt keine Veränderung)
                if let source = ReferenceSource.of(vm.exchange?.key) {
                    ReferenceSourceLine(source: source, base: pair.base, hasChange: false)
                }
            }
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
