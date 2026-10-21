import SwiftUI

// «Hinweise» unter der Gesamtwert-Karte (`PortfolioTotalCard`): Zeilen sammeln und zugeklappt als «Hinweise (n)»
// zeigen — wie `TotalCardHints` (Android).

extension PortfolioTotalCard {
    /// Eine Zeile unter «Hinweise»: `error` in Fehlerfarbe; mit `txId` antippbar (öffnet die Transaktion).
    private struct HintItem {
        let text: String
        var error = false
        var txId: Int64? = nil
    }

    /// Alle Zeilen unter «Hinweise», in der Reihenfolge wie Android.
    private var hintItems: [HintItem] {
        var items: [HintItem] = []
        // Ohne Devisenkurs: Beträge in USDT statt in der Anzeigewährung
        if currency != "USD", !unit.converted {
            items.append(HintItem(text: L("portfolio_total_no_fx", currency)))
        }
        // Kursquelle: steht sie schon auf der Karte, nicht noch einmal unter «Hinweise»
        if usdtNote?.kind != .PRICE_SOURCE { items.append(HintItem(text: priceSourceText)) }
        // G-9: Modell nahe an der Zahl, nicht nur im (i); Ø ist eine bewusste Wahl: kein Hinweis «nicht FIFO»
        if summary.unrealized != nil, summary.method.isFifo {
            items.append(HintItem(text: L("ledger_fifo_cost_note")))
        }
        // Umgerechnet: Einstand zum Devisenkurs des Kauftags; ohne Kurs eines Kauftags fehlt der Coin, USDT/USD-Lücke = 1
        if unit.converted, summary.unrealized != nil {
            items.append(HintItem(text: L("ledger_cost_fx_note", unit.code)))
            if let coins = costFx?.fxMissingCoins, !coins.isEmpty {
                items.append(HintItem(text: L("ledger_cost_fx_missing", coins.joined(separator: ", "))))
            }
            if costFx?.usdtApproximate == true { items.append(HintItem(text: L("ledger_cost_fx_usdt_assumed"))) }
        }
        // Was im Gewinn seit Kauf fehlt (M3: nur diese Coins, nicht das ganze Portfolio)
        if !summary.missingCostCoins.isEmpty {
            let count: Int = summary.missingCostCoins.count
            // Antippen: Einträge ohne Einstand (Build 84)
            items.append(HintItem(text: L("ledger_without_cost", count: count, count, summary.missingCostCoins.joined(separator: ", ")),
                                  txId: PortfolioCostGapUi.hintId))
        }
        // Nur ein Teil ohne Kaufpreis: «Seit Kauf» zählt den Teil mit Kaufpreis, der Rest fehlt — wie Android
        if !summary.partialCostParts.isEmpty {
            let parts: [String] = summary.partialCostParts.map {
                PortfolioInsights.mask(PortfolioFormat.exactAmount($0.amount, $0.coin), hidden: hideAmounts)
            }
            items.append(HintItem(text: L("ledger_partial_cost", parts.joined(separator: ", ")), txId: PortfolioCostGapUi.hintId))
        }
        // Heute / 7 T / 1 J: Coins ohne passende Kurse fehlen nur dort, die übrigen zählen — wie Android
        let skipped: [String] = todaySkipped + (weekChange?.skipped ?? []) + (yearChange?.skipped ?? [])
        let periodSkipped: [String] = Array(Set(skipped)).sorted()
        if !periodSkipped.isEmpty {
            items.append(HintItem(text: L("ledger_period_without", periodSkipped.joined(separator: ", "))))
        }
        // Eingetragene Börse ohne Kurs bzw. mehrere Börsen bei einem Coin: mit Binance bewertet
        let failedExchanges: [String] = Array(Set(exchangeFailed.values)).sorted { $0.lowercased() < $1.lowercased() }
        for exchange in failedExchanges {
            let coins: [String] = exchangeFailed.filter { $0.value == exchange }.map(\.key).sorted()
            items.append(HintItem(text: L("ledger_exchange_failed", coins.joined(separator: ", "), exchange)))
        }
        if !mixedExchange.isEmpty {
            items.append(HintItem(text: L("ledger_exchange_mixed", mixedExchange.sorted().joined(separator: ", "))))
        }
        // Keine eigenen Tageskerzen der Börse: «7 T»/«1 J» aus Binance, auf den Börsenkurs skaliert
        if !periodScaled.isEmpty {
            items.append(HintItem(text: L("portfolio_period_scaled", periodScaled.joined(separator: ", "))))
        }
        // FIFO je Wallet: je Coin und Wallet der erste Abgang über den Bestand — antippen öffnet die Transaktion
        for s in firstShortfalls {
            let wallet: String = s.wallet.trimmingCharacters(in: .whitespaces).isEmpty ? L("tax_export_wallet_unspecified") : s.wallet
            items.append(HintItem(text: L("label_value", s.coin, L("portfolio_wallet_shortfall", wallet)), error: true, txId: s.txId))
        }
        // Umbuchungsgebühren auf Bestand ohne Kaufpreis: offener Posten (je Coin auf der Detailseite) — wie Android
        if summary.unassignedFeeUsdt != 0 {
            items.append(HintItem(text: L("portfolio_unassigned_fee",
                                          PortfolioInsights.mask(PortfolioFormat.usdtValue(PortfolioDecimal.double(summary.unassignedFeeUsdt)),
                                                                 hidden: hideAmounts))))
        }
        if !summary.missingCurrentPrices.isEmpty {
            items.append(HintItem(text: L("portfolio_current_missing", summary.missingCurrentPrices.joined(separator: ", "))))
        }
        // «Kurse von 14:05:12»; veraltet «Kurse von 3. Okt., 14:05 · veraltet» in Fehlerfarbe
        if updatedAt > 0 {
            let asOf: String = L("portfolio_updated", stale ? PortfolioStamp.time(updatedAt) : PriceFormat.time(updatedAt))
            items.append(HintItem(text: stale ? L("watchlist_row_outdated", asOf) : asOf, error: stale))
        }
        return items
    }

    /// Bereich «Hinweise» unter der Karte (L-15), Build 46 zugeklappt: eine Zeile «Hinweise (n)» mit Pfeil
    /// (44 pt hoch, Fehlerfarbe, wenn eine Zeile ein Fehler ist); offen die Zeilen eng untereinander
    /// (`.subheadline`), eingerückt wie der Inhalt der Karte — wie Android `TotalCardHints`.
    @ViewBuilder
    var hints: some View {
        let items: [HintItem] = hintItems
        if !items.isEmpty {
            let anyError: Bool = items.contains { $0.error }
            VStack(alignment: .leading, spacing: 2) {
                Button {
                    showHints.toggle()
                } label: {
                    HStack(spacing: 4) {
                        Text(L("portfolio_card_hints_count", items.count))
                            .font(.caption.weight(.semibold))
                        Image(systemName: showHints ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(accent.primary)
                            .accessibilityHidden(true)
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(anyError ? AppColors.error : AppColors.onSurfaceVariant)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(L(showHints ? "a11y_expanded" : "a11y_collapsed"))
                if showHints {
                    ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                        hintRow(item)
                    }
                }
            }
            .padding(.horizontal, Spacing.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Eine Zeile im Bereich «Hinweise»; Wallet-Hinweise als Knopf.
    @ViewBuilder
    private func hintRow(_ item: HintItem) -> some View {
        if let txId = item.txId {
            Button {
                onShortfall(txId)
            } label: {
                hint(item.text, error: item.error)
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
        } else {
            hint(item.text, error: item.error)
        }
    }

    /// Je Coin und Wallet nur der erste Abgang über den Bestand.
    private var firstShortfalls: [PortfolioWalletShortfall] {
        var seen = Set<String>()
        return walletShortfalls.filter { seen.insert($0.coin + "|" + $0.wallet.trimmingCharacters(in: .whitespaces).lowercased()).inserted }
    }

    /// Eine Zeile im Bereich «Hinweise»: Lesegrösse (`.subheadline` — ganze Sätze, aufgeklappt, mit
    /// Platz), wie Android `TotalHint` (`body`).
    private func hint(_ text: String, error: Bool = false) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(error ? AppColors.error : AppColors.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}
