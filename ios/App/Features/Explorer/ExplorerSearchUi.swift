import SwiftUI

/// Suche über alle Börsen: Suchfeld, Treffer und ihre Zeilen. Wie `ExplorerSearchUi.kt`.
extension ExplorerScreen {
    // MARK: Suche

    var searchField: some View {
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

    var searchResults: some View {
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
                CoinBadge(symbol: CoinLogos.logoKey(hit.pair.base, tradFi: hit.pair.isTradFi), size: 38)
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
}
