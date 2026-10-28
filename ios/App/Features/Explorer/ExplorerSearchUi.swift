import SwiftUI
import UIKit

/// Coin-Suche: Suchfeld, Coins, Paare eines Coins und einzelne Paar-Treffer. Wie `ExplorerSearchUi.kt`.
extension ExplorerScreen {
    // MARK: Suche

    var searchField: some View {
        HStack(spacing: Spacing.sm) {
            Image(systemName: "magnifyingglass")
                .font(.body.weight(.semibold))
                .foregroundStyle(searchFocused ? accent.primary : AppColors.onSurfaceVariant)
            TextField(placeholder: L("explorer_search_hint"), text: Binding(
                get: { vm.searchQuery },
                set: { vm.setSearchQuery($0) }
            ))
            .focused($searchFocused)
            .accessibilityIdentifier(UiTestTags.explorerSearch)
            .textInputAutocapitalization(.characters)
            .autocorrectionDisabled()
            .submitLabel(.search)
            .onSubmit {
                searchFocused = false
                vm.confirmSearch()
            }
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
        // Mindesthöhe statt fester Höhe: grosse Schrift (AX-Grössen) wächst mit, statt abgeschnitten zu werden
        .frame(minHeight: 52)
        .background(AppColors.containerHigh, in: Capsule())
        .overlay(
            Capsule().strokeBorder(searchFocused ? accent.primary.opacity(0.7) : .clear, lineWidth: 1.5)
        )
        .animation(.easeOut(duration: 0.2), value: searchFocused)
        .animation(.easeOut(duration: 0.2), value: vm.searchQuery.isEmpty)
    }

    /// «Zuletzt gesucht» unter dem leeren Suchfeld: antippbare Chips (VoiceOver «Zuletzt gesucht:
    /// BTC»), rechts «Löschen». Ein Tipp setzt den Begriff ins Suchfeld — gesucht wird wie bei der
    /// Eingabe (auch mit «Token & TradFi»). Wie `RecentSearchChips` in Android.
    var recentSearchChips: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            HStack {
                Text(L("explorer_recent_title"))
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button(L("explorer_recent_clear")) {
                    withAnimation(.snappy(duration: 0.25)) { vm.clearRecentSearches() }
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(accent.primary)
                .buttonStyle(.borderless)
                .accessibilityLabel(L("explorer_recent_clear_a11y"))
            }
            .padding(.horizontal, 4)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(vm.recentSearches, id: \.self) { query in
                        recentChip(query)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private func recentChip(_ query: String) -> some View {
        Button {
            Haptics.play(.selection)
            vm.setSearchQuery(query)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                Text(query)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, 8)
            .background(AppColors.containerHigh, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("explorer_recent_a11y", query))
    }

    /// Treffer der Suche: Coins (antippen zeigt ihre Paare), bei gezielter Suche auch einzelne Paare, und das
    /// Angebot, fehlende Paarlisten zu laden. Wie `SearchResults` in Android.
    var searchResults: some View {
        let pairs = vm.showPairHits ? vm.pairHits : []
        let busy = vm.searchLists.reading || vm.searchLists.progress != nil
        return VStack(alignment: .leading, spacing: 8) {
            listsOffer

            if vm.coinHits.isEmpty && pairs.isEmpty && !busy {
                VStack(spacing: Spacing.sm) {
                    HStack(spacing: Spacing.sm) {
                        Image(systemName: "magnifyingglass")
                        Text(L("explorer_search_empty"))
                    }
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    // Der Weg nach Börse bleibt erreichbar, ohne erst das Suchfeld zu leeren (wie Android)
                    Button(L("explorer_search_choose_manually")) {
                        vm.setSearchQuery("")
                        searchFocused = false
                        ExplorerSectionMemory.precise = true
                        withAnimation(.snappy(duration: 0.3)) { showPrecise = true }
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(accent.primary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Spacing.xxl)
            }

            if !vm.coinHits.isEmpty {
                sectionLabel(L("explorer_coins_title"))
                LazyVStack(spacing: 8) {
                    ForEach(vm.coinHits) { coin in
                        coinRow(coin)
                    }
                }
            }

            if !pairs.isEmpty {
                sectionLabel(L("explorer_pairs_title"))
                    .padding(.top, Spacing.sm)
                // Ziel-Gruppe gilt für jeden angetippten Treffer
                ExplorerGroupTargetSelector()
                LazyVStack(spacing: 8) {
                    ForEach(pairs) { hit in
                        searchHitRow(hit)
                    }
                }
            }
        }
    }

    /// Paare des angetippten Coins: «Alle Treffer», Kopf mit Coin, der Vorschlag als Karte mit einem Knopf
    /// (ein Tipp genügt), darunter die übrigen Paare. Wie `CoinPairs` in Android.
    func coinPairsView(_ coin: ExplorerCoinHit) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.snappy(duration: 0.25)) { vm.pickCoin(nil) }
            } label: {
                Label(L("explorer_coin_back"), systemImage: "chevron.backward")
                    .font(.subheadline.weight(.semibold))
                    .padding(.vertical, Spacing.xs)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(accent.primary)

            HStack(spacing: Spacing.md) {
                CoinBadge(symbol: coin.symbol, size: 44, logo: !coin.tradFi)
                VStack(alignment: .leading, spacing: 2) {
                    Text(coin.name ?? coin.symbol)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(AppColors.onSurface)
                    if coin.name != nil {
                        Text(coin.symbol)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
            }
            .padding(.bottom, Spacing.sm)

            if let suggested = vm.pickedCoinPairs.first {
                sectionLabel(L("explorer_coin_suggested"))
                suggestedPairCard(suggested)
                ExplorerGroupTargetSelector()
                    .padding(.top, Spacing.xs)
                listsOffer

                let others = Array(vm.pickedCoinPairs.dropFirst())
                if !others.isEmpty {
                    sectionLabel(L("explorer_coin_other_pairs"))
                        .padding(.top, Spacing.sm)
                    LazyVStack(spacing: 8) {
                        ForEach(others) { hit in
                            searchHitRow(hit)
                        }
                    }
                }
            } else {
                // «Kein Paar» erst, wenn alle Listen gelesen sind; solange etwas lädt oder fehlt, spricht nur der Kasten darunter
                let lists = vm.searchLists
                if lists.progress == nil && !lists.reading && lists.missing == 0 {
                    Text(L("explorer_coin_no_pairs"))
                        .font(.subheadline)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.vertical, Spacing.sm)
                }
                listsOffer
            }
        }
    }

    /// Kleine Überschrift über einem Abschnitt der Treffer.
    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.horizontal, 4)
            .padding(.top, Spacing.xs)
            .accessibilityAddTraits(.isHeader)
    }

    /// Fehlende Paarlisten mit «Listen laden» (stumme Börsen mit Namen und «Erneut versuchen»), beim Laden der
    /// Fortschritt. Geladen wird nur auf Tippen, je Börse die ganze öffentliche Liste. Wie `ListsOffer` in Android.
    @ViewBuilder
    private var listsOffer: some View {
        let lists = vm.searchLists
        if let progress = lists.progress {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                    .tint(accent.primary)
                Text(L("explorer_lists_loading", progress.done, progress.total))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .monospacedDigit()
            }
            .padding(.vertical, Spacing.sm)
        } else if lists.reading {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ProgressView().progressViewStyle(.linear).tint(accent.primary)
                Text(L("explorer_loading_pairs"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .padding(.vertical, Spacing.sm)
        } else if lists.missing > 0 {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                // Nach einem Fehlschlag zuerst die Börsen ohne Antwort, dann die übrigen fehlenden Listen
                let others = lists.missing - lists.failed.count
                Text([lists.failed.isEmpty ? nil : L(lists.failed.count == 1 ? "explorer_lists_failed_one"
                                                     : "explorer_lists_failed_many", lists.failed.joined(separator: ", ")),
                      lists.failed.isEmpty || others > 0 ? L("explorer_lists_missing", count: others, others) : nil]
                    .compactMap { $0 }.joined(separator: "\n"))
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                Text(L("explorer_lists_privacy"))
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                HStack {
                    Spacer()
                    Button(L(lists.failed.isEmpty ? "explorer_lists_load" : "action_retry")) { vm.loadMissingLists() }
                        .accessibilityIdentifier(UiTestTags.explorerListsLoad)
                        .font(.subheadline.weight(.semibold))
                        .tint(accent.primary)
                        .buttonStyle(.borderless)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
    }

    /// Ein Coin: Plakette, Name und Kürzel, darunter an wie vielen Börsen (oder «noch nicht geladen»).
    private func coinRow(_ coin: ExplorerCoinHit) -> some View {
        Button {
            Haptics.play(.selection)
            withAnimation(.snappy(duration: 0.25)) { vm.pickCoin(coin) }
        } label: {
            HStack(spacing: 12) {
                CoinBadge(symbol: coin.symbol, size: 38, logo: !coin.tradFi)
                VStack(alignment: .leading, spacing: 2) {
                    // Alpha-Token: «α» hinter dem Namen (wie in der Merkliste)
                    Text((coin.name ?? coin.symbol) + (coin.alpha ? " " + AssetKindIconView.alphaGlyph : ""))
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Text(coinSubtitle(coin))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, Spacing.md)
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
        .buttonStyle(ExplorerPressStyle())
        .accessibilityHint(L("explorer_coin_show_pairs"))
        .accessibilityIdentifier(UiTestTags.explorerCoin(coin.symbol, tradFi: coin.tradFi))
    }

    private func coinSubtitle(_ coin: ExplorerCoinHit) -> String {
        var parts: [String] = []
        if coin.name != nil { parts.append(coin.symbol) }
        parts.append(coin.exchanges > 0
                     ? L("explorer_coin_exchanges", count: coin.exchanges, coin.exchanges)
                     : L("explorer_coin_not_loaded"))
        return parts.joined(separator: " · ")
    }

    /// Vorschlag zum Coin: Paar, Börse und ein grosser Knopf «Zur Merkliste» (bzw. Häkchen, wenn schon da).
    private func suggestedPairCard(_ hit: ExplorerSearchHit) -> some View {
        let inList = vm.isInWatchlist(hit.exchange, hit.pair)
        return VStack(alignment: .leading, spacing: Spacing.xs) {
            Text("\(hit.pair.base)/\(hit.pair.quote)" + (hit.alpha ? " " + AssetKindIconView.alphaGlyph : ""))
                .font(.headline)
                .foregroundStyle(AppColors.onSurface)
            Text(searchSubtitle(hit))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
            Button {
                vm.addSearchHit(hit)
            } label: {
                Label(L(inList ? "a11y_in_watchlist" : "explorer_add_to_watchlist"),
                      systemImage: inList ? "checkmark" : "plus")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.lg)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(inList)
            .accessibilityIdentifier(UiTestTags.explorerSuggestedAdd)
            .padding(.top, Spacing.sm)
        }
        .padding(Spacing.lg)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
    }

    private func searchHitRow(_ hit: ExplorerSearchHit) -> some View {
        let inList = vm.isInWatchlist(hit.exchange, hit.pair)
        return Button {
            vm.addSearchHit(hit)
        } label: {
            HStack(spacing: 12) {
                CoinBadge(symbol: CoinLogos.logoKey(hit.pair.base, tradFi: hit.pair.isTradFi), size: 38)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 0) {
                        Text(hit.pair.base).font(.headline)
                        Text("/\(hit.pair.quote)")
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                        // Alpha-Token: «α» hinter dem Paar (wie in der Merkliste)
                        if hit.alpha {
                            Text(verbatim: " " + AssetKindIconView.alphaGlyph)
                                .font(.headline)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                        }
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
            .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
        .buttonStyle(ExplorerPressStyle())
    }

    /// Börse und — bei Futures — die Laufzeit.
    private func searchSubtitle(_ hit: ExplorerSearchHit) -> String {
        var parts = [hit.exchange.name]
        if hit.pair.contractType != .none { parts.append(ExplorerViewModel.contractName(hit.pair.contractType)) }
        return parts.joined(separator: " · ")
    }
}
