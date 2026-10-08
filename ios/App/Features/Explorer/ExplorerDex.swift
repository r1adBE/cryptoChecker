import SwiftUI

/// DEX-Pools: Suche und Trefferzeilen. Wie `ExplorerDex.kt`.
extension ExplorerScreen {
    // MARK: DEX

    /// Suche nach Token auf dezentralen Börsen. Ein Pool = ein Paar auf einer
    /// bestimmten DEX und Chain; der Kurs kommt in USD.
    var dexSection: some View {
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
}
