import SwiftUI

/// Übersicht von «Paar hinzufügen» ohne Suchbegriff: Markt-Chips, «Zuletzt genutzte Börsen» und «Alle Börsen». Wie `ExplorerBrowseUi.kt`.
extension ExplorerScreen {
    // MARK: Markt-Chips

    /// Alle · Spot · Futures · DEX · Devisen, im Stil der Gruppen-Chips der Merkliste (Rand, gewählt in der
    /// Themenfarbe, VoiceOver «ausgewählt»); waagrecht scrollbar, Schrift wächst bis ≈ 1,5-fach mit.
    var marketChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.sm) {
                ForEach(ExplorerMarketFilter.allCases, id: \.self) { filter in
                    marketChip(filter)
                }
            }
            .padding(.vertical, Spacing.xs)
        }
        .scrollClipDisabled()
    }

    private func marketChip(_ filter: ExplorerMarketFilter) -> some View {
        let selected = vm.marketFilter == filter
        let tint = selected ? accent.primary : AppColors.onSurface
        return Button {
            Haptics.play(.selection)
            withAnimation(.snappy(duration: 0.25)) { vm.setMarketFilter(filter) }
        } label: {
            Text(Self.marketTitle(filter))
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .dynamicTypeSize(...AppTabBarMetrics.maxTypeSize)
                .padding(.horizontal, Spacing.lg)
                .padding(.vertical, Spacing.sm)
                .frame(minHeight: 32)
                .foregroundStyle(tint)
                .overlay(WatchlistGroupChips.chipShape.strokeBorder(selected ? accent.primary : AppColors.outline,
                                                                    lineWidth: selected ? 1.5 : 1))
                // Tippfläche höher als der Chip (44 pt), ohne die Zeile höher zu machen
                .contentShape(Rectangle().inset(by: -6))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier(UiTestTags.explorerMarket(filter.rawValue))
    }

    /// Name eines Markt-Chips (Spot und Futures wie die Abschnitte der Börsen).
    static func marketTitle(_ filter: ExplorerMarketFilter) -> String {
        switch filter {
        case .all: return L("group_all")
        case .spot: return L("explorer_section_spot")
        case .futures: return L("explorer_section_futures")
        case .dex: return L("explorer_market_dex")
        case .forex: return L("explorer_market_forex")
        }
    }

    // MARK: Abschnitte

    /// Börsen der Auswahl nach dem Markt-Chip, Favoriten (Stern) zuerst.
    private var shownExchanges: [Exchange] {
        let market = vm.marketFilter
        let all = ExchangeOrder.sections(of: vm.exchanges, volumes: vm.exchangeRanking).flatMap(\.exchanges)
            .filter { market.allows($0.category) }
        let favorites = data.favorites[.EXCHANGE] ?? []
        return all.filter { favorites.contains($0.name) } + all.filter { !favorites.contains($0.name) }
    }

    /// Börsen, die der Suchtext nennt (`ExplorerExchangeSearch`), nach dem Markt-Chip. Ohne DexScreener: dorthin führt
    /// schon der DEX-Treffer (`dexSearchHit`).
    var exchangeHits: [Exchange] {
        let market = vm.marketFilter
        let offered = vm.exchanges.filter { $0.key != ExplorerViewModel.dexKey && market.allows($0.category) }
        return ExplorerExchangeSearch.find(vm.searchQuery, offered) { $0.name }
    }

    /// «Zuletzt genutzte Börsen», «Alle Börsen»; unter «Devisen» die Gruppen «Live (Börse)» und «Referenzkurs».
    @ViewBuilder
    var browseSections: some View {
        if vm.marketFilter == .forex {
            forexSections
        } else {
            allSections
        }
    }

    /// Chip «Devisen»: «Live (Börse)» — Kraken und Bitstamp mit echten Fiat-Paaren (`ExplorerForexLive`, geöffnet nur
    /// diese Paare) — und «Referenzkurs» (EZB, NBU, NBP; Takt und Quelle in der Zeile). Wie Android `ForexSections`.
    @ViewBuilder
    private var forexSections: some View {
        let live = ExplorerForexLive.exchanges.compactMap { key in vm.exchanges.first { $0.key == key } }
        if !live.isEmpty {
            sectionLabel(L("explorer_forex_live"))
                .padding(.top, Spacing.sm)
            exchangeRows(live)
        }
        sectionLabel(L("explorer_forex_reference"))
            .padding(.top, Spacing.sm)
        exchangeRows(shownExchanges)
    }

    @ViewBuilder
    private var allSections: some View {
        let shown = shownExchanges
        let byKey = Dictionary(shown.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        let recent = vm.recentExchanges.compactMap { byKey[$0] }
        if !recent.isEmpty {
            sectionLabel(L("explorer_recent_exchanges_title"))
                .padding(.top, Spacing.sm)
            exchangeRows(recent)
        }

        sectionLabel(L("explorer_all_exchanges_title"))
            .padding(.top, Spacing.sm)
        if shown.isEmpty {
            Text(L("explorer_search_empty"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.horizontal, Spacing.xs)
        } else {
            exchangeRows(shown)
        }
    }

    /// Erster Buchstabe gross («reference rate, daily» → «Reference rate, daily»). Wie Android `titlecase`.
    private static func capitalizedFirst(_ text: String) -> String {
        guard let first = text.first else { return text }
        return String(first).uppercased() + text.dropFirst()
    }

    /// Keine LazyVStack: rund 60 Zeilen, alle sofort da (auch für VoiceOver und UI-Tests ausserhalb des Bildes).
    func exchangeRows(_ list: [Exchange]) -> some View {
        VStack(spacing: Spacing.sm) {
            ForEach(list, id: \.key) { exchange in
                exchangeRow(exchange)
            }
        }
    }

    /// Eine Börse: Zeichen, Name und — bei DexScreener die bekannten DEX, bei Referenzkursen Takt und Quelle
    /// («Referenzkurs, wöchentlich · Quelle: Polnische Nationalbank») —, rechts der Stern (Favorit) und der Pfeil. Wie
    /// `ExchangeRow` (Android).
    private func exchangeRow(_ exchange: Exchange) -> some View {
        let starred = (data.favorites[.EXCHANGE] ?? []).contains(exchange.name)
        let subtitle: String? = switch exchange.category {
        case .dex: L("dex_picker_subtitle")
        case .forex: ReferenceSource.of(exchange.key).map {
            Self.capitalizedFirst(ReferenceSourceText.period($0)) + " · " + ReferenceSourceText.name($0)
        } ?? L("explorer_section_forex")
        default: nil
        }
        return HStack(spacing: Spacing.md) {
            Button {
                Haptics.play(.selection)
                withAnimation(.snappy(duration: 0.3)) { vm.openExchange(exchange) }
            } label: {
                HStack(spacing: Spacing.md) {
                    ExplorerExchangeMarkView(key: exchange.key, name: exchange.name, size: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(exchange.name)
                            .font(.headline)
                            .foregroundStyle(AppColors.onSurface)
                            .lineLimit(1)
                        if let subtitle {
                            Text(subtitle)
                                .font(.footnote)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                                .lineLimit(2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L("explorer_coin_show_pairs"))
            .accessibilityIdentifier(UiTestTags.explorerExchange(exchange.key))

            Button {
                Haptics.play(.lightImpact)
                withAnimation(.snappy) { data.toggleFavorite(.EXCHANGE, exchange.name) }
            } label: {
                Image(systemName: starred ? "star.fill" : "star")
                    .font(.body)
                    .foregroundStyle(starred ? accent.primary : AppColors.outline)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L(starred ? "favorite_remove" : "favorite_add"))

            Image(systemName: "chevron.forward")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .accessibilityHidden(true)
        }
        .padding(.leading, Spacing.md)
        .padding(.trailing, Spacing.sm)
        .padding(.vertical, Spacing.xs)
        .frame(minHeight: 56)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}
