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
                TextField(placeholder: L("dex_search_hint"), text: $dexQuery)
                    .focused($dexFocused)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit { runDexSearch() }
                    .padding(.horizontal, Spacing.md)
                    // Mindesthöhe: wächst mit grosser Schrift (AX-Grössen) mit
                    .frame(minHeight: 50)
                    .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: Radius.field, style: .continuous)
                            .strokeBorder(dexFocused ? accent.primary.opacity(0.7) : .clear, lineWidth: 1.5)
                    )
                Button(action: runDexSearch) {
                    Text(L("dex_search"))
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16)
                        .frame(minHeight: 50)
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

    /// «DexScreener – Pools von Uniswap über DexScreener» in der Coin-Suche («uniswap pepe»): Tippen öffnet die
    /// DEX-Suche, vorbelegt mit dem übrigen Suchtext. Ein Element für VoiceOver. Wie `DexSearchHitRow` (Android).
    func dexSearchHitRow(name: String, match: DexNames.Match) -> some View {
        let hint = match.dexName.map { L("dex_search_hit", $0) } ?? L("dex_search_hit_generic")
        return Button {
            Haptics.play(.selection)
            openDex(prefill: match.token)
        } label: {
            HStack(spacing: 12) {
                // Platz wie die Coin-Plakette; rein dekorativ
                Image(systemName: "arrow.left.arrow.right")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .frame(width: 38, height: 38)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.headline)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Text(hint)
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
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
        .accessibilityElement(children: .combine)
        .accessibilityHint(L("dex_search"))
    }

    /// DexScreener wählen, «Erweitert / nach Börse» aufklappen und die DEX-Suche mit `prefill` vorbelegen.
    private func openDex(prefill: String) {
        searchFocused = false
        vm.openDexSearch()
        dexQuery = prefill
        ExplorerSectionMemory.precise = true
        withAnimation(.snappy(duration: 0.3)) { showPrecise = true }
    }

    private func runDexSearch() {
        dexFocused = false
        vm.searchDex(dexQuery)
    }

    private func dexRow(_ pool: DexPool) -> some View {
        HStack(spacing: 12) {
            // DEX: Symbole frei wählbar → nie das Logo eines bekannten Coins, nur Initialen
            CoinBadge(symbol: pool.baseSymbol, size: 36, logo: false)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(pool.baseSymbol)/\(pool.quoteSymbol)")
                    .font(.headline)
                    .lineLimit(1)
                Text(dexSubtitle(pool))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(2)
                // Dünner Pool (unter `DexScreener.minAlarmLiquidityUsd` oder unbekannt): kein Alarm möglich
                if !pool.alarmCapable {
                    Text(L("dex_low_liquidity_no_alarm"))
                        .font(.caption)
                        .foregroundStyle(AppColors.error)
                        .lineLimit(2)
                }
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
        .background(AppColors.containerHigh.opacity(0.6), in: RoundedRectangle(cornerRadius: Radius.field, style: .continuous))
    }

    private func dexSubtitle(_ pool: DexPool) -> String {
        var parts = ["\(pool.dexId) · \(pool.chainId)"]
        // Vertragsadresse des Tokens: unterscheidet gleichnamige Token derselben Chain (wie Android)
        if !pool.shortBaseAddress.isEmpty { parts.append(pool.shortBaseAddress) }
        if let price = pool.priceUsd { parts.append("$" + Self.formatDexPrice(price)) }
        if let liquidity = pool.liquidityUsd { parts.append(L("dex_liquidity", "$" + LocaleNumbers.compact(liquidity))) }
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
        // Ganz kleine Preise mit höchstens vier gültigen Stellen wie die Kursanzeige («@###») —
        // eine feste Stellenzahl ergäbe für 3e-11 «0»; wie Android (DisplayFormat)
        return PriceFormat.formatDouble(value)
    }
}
