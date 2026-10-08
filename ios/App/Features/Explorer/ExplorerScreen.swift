import SwiftUI
import UIKit

/// Seite «Paar hinzufügen»: Suche über alle Börsen, Schritt für Schritt ein Paar wählen,
/// mehrere Paare auf einmal, DEX-Pools. Wie `ExplorerScreen.kt`.
///
/// Seit Runde 31 kein Tab mehr: die Merkliste legt sie auf ihren `NavigationStack`
/// (`AppRouter.showExplorer` — «+», Shortcut, Widget, Link "add", «Heute auffällig»).
struct ExplorerScreen: View {
    @StateObject private var vm = ExplorerViewModel()
    @EnvironmentObject private var data: AppData
    @EnvironmentObject private var router: AppRouter
    @Environment(\.appAccent) private var accent

    @State private var picker: ExplorerPickerKind?
    @State private var showSyncSheet = false
    // Auf-/Zugeklappt merkt sich die Sitzung (ExplorerSectionMemory), nicht nur dieser Bildschirm
    @State private var showPrecise: Bool
    @State private var showBulk: Bool
    @State private var showBulkList = false
    @State private var confirmBulk = false
    @State private var dexQuery = ""
    @FocusState private var searchFocused: Bool
    @FocusState private var dexFocused: Bool

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

    // MARK: Suche

    private var searchField: some View {
        HStack(spacing: Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .font(.body.weight(.semibold))
                .foregroundStyle(searchFocused ? accent.primary : AppColors.onSurfaceVariant)
            TextField(L("explorer_search_hint"), text: Binding(
                get: { vm.searchQuery },
                set: { vm.setSearchQuery($0) }
            ))
            .focused($searchFocused)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .submitLabel(.search)
            .onSubmit { searchFocused = false }
            if !vm.searchQuery.isEmpty {
                Button {
                    vm.setSearchQuery("")
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("action_clear"))
                .transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 52)
        .background(AppColors.containerHigh, in: Capsule())
        .overlay(
            Capsule().strokeBorder(searchFocused ? accent.primary.opacity(0.7) : .clear, lineWidth: 1.5)
        )
        .animation(.easeOut(duration: 0.2), value: searchFocused)
        .animation(.easeOut(duration: 0.2), value: vm.searchQuery.isEmpty)
    }

    private var searchResults: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let progress = vm.searchProgress {
                VStack(alignment: .leading, spacing: Spacing.xs) {
                    if progress.total > 0 {
                        ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                            .tint(accent.primary)
                    } else {
                        ProgressView().progressViewStyle(.linear).tint(accent.primary)
                    }
                    Text(L("explorer_search_loading", progress.done, progress.total))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .monospacedDigit()
                }
                .padding(.vertical, Spacing.sm)
            }

            if vm.searchHits.isEmpty && vm.searchProgress == nil {
                HStack(spacing: Spacing.sm) {
                    Image(systemName: "magnifyingglass")
                    Text(L("explorer_search_empty"))
                }
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xxl)
            }

            // Ziel-Gruppe gilt für jeden angetippten Treffer
            if !vm.searchHits.isEmpty {
                ExplorerGroupTargetSelector()
            }

            LazyVStack(spacing: 8) {
                ForEach(vm.searchHits) { hit in
                    searchHitRow(hit)
                }
            }
        }
    }

    private func searchHitRow(_ hit: ExplorerSearchHit) -> some View {
        let inList = vm.isInWatchlist(hit.market, hit.pair)
        return Button {
            vm.addSearchHit(hit)
        } label: {
            HStack(spacing: 12) {
                CoinBadge(symbol: hit.pair.base, size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 0) {
                        Text(hit.pair.base).font(.headline)
                        Text("/\(hit.pair.quote)")
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                    .foregroundStyle(AppColors.onSurface)
                    Text(searchSubtitle(hit))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: inList ? "checkmark.circle.fill" : "plus.circle.fill")
                    .font(.title2)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(inList ? PriceColors.ok : accent.primary)
                    .contentTransition(.symbolEffect(.replace))
                    .accessibilityLabel(L(inList ? "a11y_in_watchlist" : "explorer_add_to_watchlist"))
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(ExplorerPressStyle())
    }

    private func searchSubtitle(_ hit: ExplorerSearchHit) -> String {
        var parts = [hit.market.name]
        if hit.pair.contractType != .none { parts.append(hit.pair.contractType.backupName) }
        return parts.joined(separator: " · ")
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

    // MARK: Schritt 1: Börse

    private var marketStep: some View {
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

    private var pairStep: some View {
        ExplorerStepCard(highlighted: vm.hasPairs && !vm.pairSelected) {
            ExplorerStepHeader(
                number: 2,
                title: L("explorer_step_pair"),
                active: vm.hasPairs,
                done: vm.pairSelected
            )

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

    // MARK: Mehrere Paare

    private var bulkSection: some View {
        ExplorerStepCard {
            // Expertenfunktion: eingeklappt, bis man sie öffnet
            Button {
                let expanded = !showBulk
                ExplorerSectionMemory.bulk = expanded
                withAnimation(.snappy(duration: 0.3)) { showBulk = expanded }
            } label: {
                HStack(spacing: Spacing.sm) {
                    Image(systemName: "square.stack.3d.up.fill")
                        .foregroundStyle(accent.primary)
                    Text(L("explorer_bulk_title"))
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.bold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .rotationEffect(.degrees(showBulk ? 90 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if showBulk {
                VStack(alignment: .leading, spacing: 0) {
                    bulkQuoteChips
                        .padding(.top, Spacing.md)

                    Button {
                        vm.applyAllPairs()
                    } label: {
                        Text(L("bulk_all_pairs", vm.bulkQuote ?? ""))
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(TonalButtonStyle())
                    .disabled(vm.bulkQuote == nil || vm.syncing)
                    .padding(.top, 12)

                    if vm.bulkEmpty {
                        ExplorerHint(text: L("bulk_all_pairs_empty", vm.bulkQuote ?? ""))
                    }

                    if !vm.bulkPairs.isEmpty {
                        bulkResult
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// Gegenwährungen als Chips — Favoriten zuerst, dann die häufigsten.
    private var bulkQuoteChips: some View {
        let favs = data.favorites[.QUOTE] ?? []
        let quotes = vm.bulkQuotes.filter { favs.contains($0) } + vm.bulkQuotes.filter { !favs.contains($0) }
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(quotes, id: \.self) { quote in
                        let selected = quote == vm.bulkQuote
                        let favorite = favs.contains(quote)
                        Button {
                            UISelectionFeedbackGenerator().selectionChanged()
                            withAnimation(.snappy(duration: 0.25)) { vm.setBulkQuote(quote) }
                        } label: {
                            HStack(spacing: 4) {
                                if favorite {
                                    Image(systemName: "star.fill").font(.caption2)
                                }
                                Text(quote)
                                    .font(.subheadline.weight(selected ? .semibold : .regular))
                            }
                            .padding(.horizontal, Spacing.md)
                            .padding(.vertical, 8)
                            .foregroundStyle(selected ? accent.onPrimary : AppColors.onSurface)
                            .background(selected ? AnyShapeStyle(accent.primary.gradient) : AnyShapeStyle(AppColors.containerHigh),
                                        in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .id(quote)
                        .contextMenu {
                            Button {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                data.toggleFavorite(.QUOTE, quote)
                            } label: {
                                Label(L(favorite ? "favorite_remove" : "favorite_add"),
                                      systemImage: favorite ? "star.slash" : "star")
                            }
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            .frame(height: 40)
            .onAppear {
                if let q = vm.bulkQuote { proxy.scrollTo(q, anchor: .center) }
            }
        }
    }

    private var bulkResult: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Zusammenfassung zuerst; die Liste nur auf Wunsch.
            HStack {
                Text(L("bulk_all_pairs_applied", count: vm.bulkPairs.count, vm.bulkPairs.count, vm.bulkQuote ?? ""))
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button(L(showBulkList ? "explorer_bulk_hide_list" : "explorer_bulk_show_list")) {
                    withAnimation(.snappy(duration: 0.3)) { showBulkList.toggle() }
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(accent.primary)
                .buttonStyle(.borderless)
            }
            .padding(.top, Spacing.md)

            ExplorerGroupTargetSelector()
                .padding(.top, Spacing.sm)

            Button {
                confirmBulk = true
            } label: {
                Text(L("explorer_add_all_pairs", count: vm.bulkPairs.count))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.lg)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(vm.syncing)
            .padding(.top, 8)

            if showBulkList {
                ExplorerHint(text: L("explorer_bulk_tap_hint"))
                // Alle Paare: eigene Liste, scrollt in sich (lazy, auch bei Hunderten flüssig).
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(vm.bulkPairs, id: \.self) { pair in
                            bulkPairRow(pair)
                        }
                    }
                    .padding(4)
                }
                .frame(height: min(CGFloat(vm.bulkPairs.count) * 46 + 8, 420))
                .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .padding(.top, 8)
                .transition(.opacity)
            }
        }
    }

    private func bulkPairRow(_ pair: CurrencyPairInfo) -> some View {
        let selected = pair.base == vm.currentBase && pair.quote == vm.currentQuote && pair.contractType == vm.currentContract
        return Button {
            UISelectionFeedbackGenerator().selectionChanged()
            vm.selectBulkPair(pair)
        } label: {
            HStack(spacing: Spacing.sm) {
                CoinBadge(symbol: pair.base, size: 28)
                Text("\(pair.base)/\(pair.quote)")
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? accent.primary : AppColors.onSurface)
                Spacer()
                if pair.contractType != .none {
                    Text(pair.contractType.backupName)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                if vm.isInWatchlist(vm.currentMarket ?? MarketsConfig.unknownMarket, pair) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(PriceColors.ok)
                        .accessibilityLabel(L("a11y_in_watchlist"))
                }
            }
            .padding(.horizontal, Spacing.sm)
            .frame(height: 44)
            .background(selected ? accent.container.opacity(0.5) : .clear,
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: DEX

    /// Suche nach Token auf dezentralen Börsen. Ein Pool = ein Paar auf einer
    /// bestimmten DEX und Chain; der Kurs kommt in USD.
    private var dexSection: some View {
        ExplorerStepCard {
            HStack(spacing: Spacing.sm) {
                Image(systemName: "drop.fill")
                    .foregroundStyle(accent.primary)
                Text("DexScreener").font(.headline)
            }
            .padding(.bottom, 8)
            Text(L("dex_intro"))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, 12)

            HStack(spacing: 8) {
                TextField(L("dex_search_hint"), text: $dexQuery)
                    .focused($dexFocused)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit { runDexSearch() }
                    .padding(.horizontal, Spacing.md)
                    .frame(height: 50)
                    .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(dexFocused ? accent.primary.opacity(0.7) : .clear, lineWidth: 1.5)
                    )
                Button(action: runDexSearch) {
                    Text(L("dex_search"))
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16)
                        .frame(height: 50)
                }
                .buttonStyle(AccentButtonStyle())
                .disabled(dexQuery.trimmingCharacters(in: .whitespaces).isEmpty || vm.dexSearching)
            }

            if vm.dexSearching {
                ProgressView()
                    .tint(accent.primary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, Spacing.md)
            }

            if vm.dexNoResults {
                ExplorerHint(text: L("dex_no_results"))
            }

            if !vm.dexResults.isEmpty {
                ExplorerGroupTargetSelector()
                    .padding(.top, 12)

                // Eigene, begrenzte Liste statt Zeilen im Seiten-Scroll
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(vm.dexResults) { pool in
                            dexRow(pool)
                        }
                    }
                    .padding(.vertical, 4)
                }
                .frame(height: min(CGFloat(vm.dexResults.count) * 70, 420))
                .padding(.top, 12)
            }
        }
        .animation(.snappy(duration: 0.3), value: vm.dexResults.count)
        .animation(.snappy(duration: 0.3), value: vm.dexSearching)
    }

    private func runDexSearch() {
        dexFocused = false
        vm.searchDex(dexQuery)
    }

    private func dexRow(_ pool: DexPool) -> some View {
        HStack(spacing: 12) {
            CoinBadge(symbol: pool.baseSymbol, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(pool.baseSymbol)/\(pool.quoteSymbol)")
                    .font(.headline)
                    .lineLimit(1)
                Text(dexSubtitle(pool))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            Button {
                vm.addDexPool(pool)
            } label: {
                Text(L("dex_add"))
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, Spacing.md)
                    .padding(.vertical, 8)
            }
            .buttonStyle(TonalButtonStyle())
        }
        .padding(Spacing.sm)
        .background(AppColors.containerHigh.opacity(0.6), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func dexSubtitle(_ pool: DexPool) -> String {
        var parts = ["\(pool.dexId) · \(pool.chainId)"]
        if let price = pool.priceUsd { parts.append("$" + Self.formatDexPrice(price)) }
        if let liquidity = pool.liquidityUsd { parts.append(L("dex_liquidity", "$" + PriceFormat.compact(liquidity))) }
        return parts.joined(separator: " · ")
    }

    /// Kleinstpreise (Memecoins) mit genug Stellen, sonst zwei Nachkommastellen.
    static func formatDexPrice(_ value: Double) -> String {
        if value >= 1 {
            let f = NumberFormatter()
            f.numberStyle = .decimal
            f.minimumFractionDigits = 2
            f.maximumFractionDigits = 2
            return f.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value)
        }
        // In den Ziffern der App-Sprache (wie Android); ganz kleine Preise ohne Nullen am Ende
        if value >= 0.0001 { return LocaleNumbers.decimal(value, maxDecimals: 6) }
        return LocaleNumbers.decimal(value, maxDecimals: 10, minDecimals: 0)
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
