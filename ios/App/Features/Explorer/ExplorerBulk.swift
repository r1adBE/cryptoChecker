import SwiftUI
import UIKit

/// Mehrere Paare auf einmal: Gegenwährung, Zusammenfassung, Liste. Wie `ExplorerBulk.kt`.
extension ExplorerScreen {
    // MARK: Mehrere Paare

    var bulkSection: some View {
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
}
