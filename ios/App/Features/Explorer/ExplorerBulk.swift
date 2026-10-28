import SwiftUI
import UIKit

/// Mehrere Paare auf einmal: Gegenwährung, Zusammenfassung, Liste. Wie `ExplorerBulk.kt`.
extension ExplorerScreen {
    // MARK: Mehrere Paare

    /// So viele Paare zeigt die Vorschau, bevor «Alle anzeigen» den Rest aufklappt.
    static let bulkPreviewRows = 25

    /// «Paar wählen» und «Mehrere Paare auf einmal» als zwei Register (`ExplorerModeTabs`).
    var bulkTabbed: Bool { vm.hasPairs && !vm.bulkQuotes.isEmpty }

    var bulkSection: some View {
        ExplorerStepCard {
            // Ohne Register: aufklappbar mit eigenem Kopf (unter den Registern immer offen)
            if !bulkTabbed {
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
                        // Auf-/Zuklapp-Pfeil überall in der Themenfarbe (wie `ExpandToggleLabel`)
                        Image(systemName: "chevron.down")
                            .font(.footnote.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .rotationEffect(.degrees(showBulk ? 180 : 0))
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            if showBulk || bulkTabbed {
                VStack(alignment: .leading, spacing: 0) {
                    bulkQuoteChips
                        .padding(.top, bulkTabbed ? 0 : Spacing.md)

                    Button {
                        vm.collectBulkPairs()
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
                            Haptics.play(.selection)
                            withAnimation(.snappy(duration: 0.25)) { vm.setBulkQuote(quote) }
                        } label: {
                            HStack(spacing: 4) {
                                if favorite {
                                    // Sonst liest VoiceOver den Bildnamen «star.fill» vor
                                    Image(systemName: "star.fill").font(.caption2)
                                        .accessibilityLabel(L("a11y_favorite"))
                                }
                                Text(quote)
                                    .font(.subheadline.weight(selected ? .semibold : .regular))
                            }
                            .padding(.horizontal, Spacing.md)
                            .padding(.vertical, 8)
                            .foregroundStyle(selected ? onAccent : AppColors.onSurface)
                            .background(selected ? AnyShapeStyle(accent.primary.gradient) : AnyShapeStyle(AppColors.containerHigh),
                                        in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                        .id(quote)
                        .contextMenu {
                            Button {
                                Haptics.play(.lightImpact)
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
            // Mindesthöhe: Chips mit grosser Schrift (AX-Grössen) werden nicht abgeschnitten
            .frame(minHeight: 40)
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
                Button {
                    withAnimation(.snappy(duration: 0.3)) { showBulkList.toggle() }
                } label: {
                    ExpandToggleLabel(title: L(showBulkList ? "explorer_bulk_hide_list" : "explorer_bulk_show_list"),
                                      expanded: showBulkList)
                }
                .buttonStyle(.borderless)
            }
            .padding(.top, Spacing.md)

            // Vorab gezählt: «312 neu, 434 schon in der Merkliste» (nur wenn schon welche da sind) — wie Android
            let newCount = bulkNewCount
            let total = vm.bulkPairs.count
            if newCount < total {
                ExplorerHint(text: L("explorer_bulk_counts", count: newCount, newCount, total - newCount))
            }

            ExplorerGroupTargetSelector()
                .padding(.top, Spacing.sm)

            Button {
                confirmBulk = true
            } label: {
                Text(newCount <= 0 ? L("explorer_bulk_all_present")
                     : newCount < total ? L("explorer_add_new_pairs", count: newCount)
                     : L("explorer_add_all_pairs", count: total))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.lg)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(vm.syncing || newCount <= 0)
            .padding(.top, 8)

            if showBulkList {
                ExplorerHint(text: L("explorer_bulk_tap_hint"))
                // Schnellfilter nur, wenn die Liste die Werte hat (sonst ausgeblendet)
                quickFilterChips
                // Keine eigene rollende Liste in der rollenden Seite (Scrollen im Scrollen): erst die
                // ersten Paare, «Alle anzeigen» klappt den Rest auf (wie ExplorerBulk.kt).
                let pairs = sortedBulkPairs
                let visible = showAllBulkPairs ? pairs : Array(pairs.prefix(Self.bulkPreviewRows))
                VStack(spacing: 2) {
                    ForEach(visible, id: \.self) { pair in
                        bulkPairRow(pair)
                    }
                    if !showAllBulkPairs && pairs.count > Self.bulkPreviewRows {
                        Button {
                            showAllBulkPairs = true
                        } label: {
                            Text(L("explorer_bulk_show_all", pairs.count))
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(accent.primary)
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(4)
                // Eine Sammelabfrage der Börse für die Kennzahlen (nie je Paar)
                .task(id: vm.bulkPairs) { vm.loadBulkStats() }
                // Neue Gegenwährung: wieder nur die ersten Paare
                .onChange(of: vm.bulkPairs) { _, _ in showAllBulkPairs = false }
                .background(AppColors.containerLow, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                .padding(.top, 8)
                .transition(.opacity)
            }
        }
    }

    // MARK: Schnellfilter

    private func bulkStats(_ pair: ExchangePair) -> ExplorerPairStats? {
        pair.pairId.flatMap { vm.bulkStats[$0] }
    }

    /// Filter, die die geladenen Kennzahlen tragen.
    private var availableQuickFilters: [ExplorerQuickFilter] {
        ExplorerQuickFilters.available(vm.bulkPairs.map { bulkStats($0) })
    }

    /// Gewählter Filter, sofern die Daten ihn (noch) tragen.
    private var activeQuickFilter: ExplorerQuickFilter? {
        guard let f = bulkFilter, availableQuickFilters.contains(f) else { return nil }
        return f
    }

    private var sortedBulkPairs: [ExchangePair] {
        ExplorerQuickFilters.sort(vm.bulkPairs, activeQuickFilter) { bulkStats($0) }
    }

    /// «Gewinner», «Verlierer», «Volumen»: eine Auswahl, erneutes Antippen hebt sie auf.
    @ViewBuilder
    private var quickFilterChips: some View {
        let filters = availableQuickFilters
        if !filters.isEmpty {
            HStack(spacing: 8) {
                ForEach(filters, id: \.self) { filter in
                    quickFilterChip(filter)
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 8)
        }
    }

    private func quickFilterChip(_ filter: ExplorerQuickFilter) -> some View {
        let selected = filter == activeQuickFilter
        return Button {
            Haptics.play(.selection)
            withAnimation(.snappy(duration: 0.25)) {
                bulkFilter = ExplorerQuickFilters.toggle(activeQuickFilter, filter)
            }
        } label: {
            Text(L(filter.labelKey))
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? onAccent : AppColors.onSurface)
                .background(selected ? AnyShapeStyle(accent.primary.gradient) : AnyShapeStyle(AppColors.containerHigh),
                            in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Volumen des Filters «Volumen» in der Zeile: «12.3M USDT»; sonst nil.
    private func quickFilterText(_ pair: ExchangePair) -> String? {
        guard let filter = activeQuickFilter, filter == .volume,
              let value = ExplorerQuickFilters.value(bulkStats(pair), filter) else { return nil }
        return PriceFormat.compact(value) + " " + pair.quote
    }

    /// Veränderung zu «Gewinner»/«Verlierer» (als Anzeige «+3.21%»); beim Volumen nil.
    private func quickFilterChange(_ pair: ExchangePair) -> Double? {
        guard let filter = activeQuickFilter, filter != .volume else { return nil }
        return ExplorerQuickFilters.value(bulkStats(pair), filter)
    }

    private func bulkPairRow(_ pair: ExchangePair) -> some View {
        let selected = pair.base == vm.coin && pair.quote == vm.quote && pair.contractType == vm.contract
        return Button {
            Haptics.play(.selection)
            vm.selectBulkPair(pair)
        } label: {
            HStack(spacing: Spacing.sm) {
                CoinBadge(symbol: CoinLogos.logoKey(pair.base, tradFi: pair.isTradFi), size: 28)
                Text("\(pair.base)/\(pair.quote)")
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? accent.primary : AppColors.onSurface)
                Spacer()
                if pair.contractType != .none {
                    Text(pair.contractType.backupName)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                if let change = quickFilterChange(pair) {
                    ChangePill(change: change)
                } else if let metric = quickFilterText(pair) {
                    Text(metric)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .monospacedDigit()
                }
                if vm.isInWatchlist(vm.exchange ?? ExchangesConfig.unknownExchange, pair) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(PriceColors.ok)
                        .accessibilityLabel(L("a11y_in_watchlist"))
                }
            }
            .padding(.horizontal, Spacing.sm)
            .frame(minHeight: 44)
            .background(selected ? accent.container.opacity(0.5) : .clear,
                        in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
