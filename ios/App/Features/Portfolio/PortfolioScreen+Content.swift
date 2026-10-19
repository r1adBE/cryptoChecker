import Accessibility
import SwiftUI

// Inhalt des Portfolio-Tabs: Status, Gesamtwert, Verlauf, Aufteilung und Coin-Zeilen.
// Eigene Datei, damit `PortfolioScreen.swift` nur Aufbau, Reaktionen und Blätter enthält.

extension PortfolioScreen {
    // MARK: Inhalt

    /// «Einstand erfassen» im Blatt der Einträge ohne Einstand: Eintrag öffnen, sobald das Blatt zu ist.
    func openAfterSheet(_ tx: PortfolioTx) {
        costGapsOpen = false
        Task {
            try? await Task.sleep(nanoseconds: 450_000_000)
            sheet = PortfolioTxDraft(tx: tx)
        }
    }

    /// Zustand der Kurse wie in der Merkliste: aktuell / veraltet / offline mit «Stand …».
    private func priceState(_ summary: PortfolioSummary) -> PortfolioPriceState {
        PortfolioPriceFallback.state(updatedAt: model.prices.updatedAt, now: now, staleAfterMillis: model.staleAfterMillis,
                                     online: data.online, hasOpenCoins: !summary.open.isEmpty)
    }

    /// Abgeblasst: veraltet, auch offline mit altem Stand.
    private func pricesStale(_ state: PortfolioPriceState) -> Bool {
        state == .stale || (state == .offline
            && PortfolioPriceFallback.isStale(model.prices.updatedAt, now: now, staleAfterMillis: model.staleAfterMillis))
    }

    func content(_ summary: PortfolioSummary, currency: String) -> some View {
        let state = priceState(summary)
        let stale = pricesStale(state)
        return List {
            if state != .none {
                PortfolioStatusPill(state: state, updatedAt: model.prices.updatedAt, now: now)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .portfolioListRow(top: 4, bottom: 4)
            }
            PortfolioTotalCard(
                summary: summary,
                updatedAt: model.prices.updatedAt,
                currency: currency,
                // Faktor USDT → Anzeigewährung: Devisenkurs × USDT/USD (USDT zum Marktkurs)
                fxRate: model.displayRate(for: currency),
                // «Seit Kauf»/Einstand in der Anzeigewährung zum Kurs der Erwerbstage
                costFx: model.costFx(for: currency),
                today: model.today,
                todaySkipped: model.todaySkipped,
                weekChange: model.weekChange,
                yearChange: model.yearChange,
                stale: stale,
                priceSources: model.prices.sources,
                exchangeFailed: model.prices.exchangeFailed,
                mixedExchange: model.prices.mixedExchange,
                periodScaled: model.periodScaled,
                walletShortfalls: PortfolioCalculator.walletShortfalls(model.calcPortfolio, method: data.settings.costMethod),
                onShortfall: { id in
                    if id == PortfolioCostGapUi.hintId {
                        costGapsOpen = true
                    } else if let tx = data.portfolio.first(where: { $0.id == id }) {
                        sheet = PortfolioTxDraft(tx: tx)
                    }
                },
                // «USDT = 1 angenommen»: Messung 6 Std. gültig, mit der Uhr der Ansicht (`DepegAlarm.cardNote` in der Karte)
                depegReading: DepegAlarmCheck.lastReading,
                depegThresholdTenths: data.settings.depegThresholdTenths,
                now: now
            )
            .portfolioListRow(top: 4, bottom: 8)

            // Einträge ohne Einstand: sanfte Karte zum Ergänzen (Build 84)
            let pendingGaps = PortfolioCostGaps.pending(PortfolioCostGaps.gaps(data.portfolio, template: data.settings.taxTemplate),
                                                        acknowledged: PortfolioCostGapUi.ids(costGapsUnknown)).count
            if pendingGaps > 0 {
                PortfolioCostGapsCard(count: pendingGaps) { costGapsOpen = true }
                    .portfolioListRow(top: 0, bottom: 8)
            }

            // Wertverlauf über den Positionen
            PortfolioHistoryCard(history: model.history, range: $model.historyRange,
                                 expanded: $data.settings.portfolioHistoryExpanded,
                                 view: $data.settings.portfolioHistoryView,
                                 candles: $data.settings.portfolioHistoryCandles,
                                 pnl: $data.settings.portfolioHistoryPnl)
                .portfolioListRow(top: 0, bottom: 8)

            // Regel der Adress-Guthaben für Heute/7 T/1 J und Verlauf offen nennen (ab der ersten Beobachtung)
            if data.watchedAddresses.contains(where: { !($0.lastBalance ?? "").isEmpty }) {
                Text(L("portfolio_address_period_observed"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                    .portfolioListRow(top: 0, bottom: 8)
            }

            // Aufteilung (vier grösste Coins + «Andere»), erst ab zwei Teilen
            let slices = PortfolioInsights.allocation(summary.open)
            if slices.count >= 2 {
                PortfolioAllocationCard(slices: slices)
                    .portfolioListRow(top: 0, bottom: ListSegment.spacing)
            }

            // Kursänderung je Coin über die %-Basis steht klein in der Zeile
            let basis = data.settings.changeBasis.storage
            // Anzahl Transaktionen je Coin (kleine Zahl neben der Menge)
            // Tausch zählt bei beiden Coins
            let txCounts = Dictionary(grouping: data.portfolio.flatMap { tx in Set(tx.coins.map(PortfolioCalculator.normalizeCoin)) },
                                      by: { $0 })
                .mapValues(\.count)
            let openIndex = Dictionary(summary.open.enumerated().map { ($1.coin, $0) }, uniquingKeysWith: { first, _ in first })
            // Stablecoins mit gebrückten Varianten (USDC.e …): Zeile mit «≈» (`AddressBalance.bridgedStables`)
            let bridgedStables = AddressBalance.bridgedStables(data.watchedAddresses)
            ForEach(summary.open) { position in
                Button {
                    openCoin = position.coin
                } label: {
                    PortfolioCoinRow(
                        position: position,
                        pnl: model.coinPnl[position.coin] ?? model.coinPnl[position.coin.uppercased()],
                        basis: basis,
                        sharePercent: position.value.map { PortfolioWidgetStore.sharePercent($0, total: summary.totalValue) },
                        txCount: txCounts[position.coin] ?? 0,
                        shape: ListSegment.shape(openIndex[position.coin] ?? 0, summary.open.count),
                        stale: stale,
                        unchecked: model.prices.unchecked.contains(position.coin),
                        unavailable: model.prices.unavailable.contains(position.coin),
                        // Wert und 24h wie im Widget in der Anzeigewährung (Detail bleibt USDT)
                        unit: PortfolioCardUnit(currency: currency, fxRate: model.displayRate(for: currency)),
                        // Build 46: Börse des Kurses klein in der Zeile (nicht nur unter «Hinweise»)
                        exchange: model.prices.sources[position.coin],
                        viaBase: model.prices.viaBase[position.coin],
                        bridgedStable: bridgedStables[position.coin]?.joined(separator: ", ")
                    )
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(UiTestTags.portfolioCoin(position.coin))
                // VoiceOver: Löschen wie nach links wischen (Rückfrage, dann «Rückgängig»)
                .accessibilityActions {
                    if (txCounts[position.coin] ?? 0) > 0 {
                        Button(L("action_delete")) { askDeleteCoin = position.coin }
                    }
                }
                // Nach links wischen = löschen nach Rückfrage (Rückgängig im Banner); kein Favorit.
                // Ohne Rolle «destructive»: sonst nimmt die Liste die Zeile schon vor der Antwort heraus;
                // so federt sie zurück und bleibt bei «Abbrechen» stehen.
                // Coin nur aus einer Adresse: nichts zu löschen (Adresse über ⋯ › «Adressen» entfernen)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    if !voiceOver && (txCounts[position.coin] ?? 0) > 0 {
                        Button { askDeleteCoin = position.coin } label: {
                            Label(L("action_delete"), systemImage: "trash")
                        }
                        // Systemrot: weisse Schrift bleibt auch im Dunkelmodus lesbar
                        .tint(AppColors.destructive)
                    }
                }
                .portfolioListRow(top: ListSegment.gap / 2, bottom: ListSegment.gap / 2)
            }

            if !summary.closed.isEmpty {
                closedSection(summary.closed)
                    .portfolioListRow(top: ListSegment.spacing, bottom: 4)
            }

            PortfolioDisclaimer()
                .portfolioListRow(top: 0, bottom: 0)
            // Unten Platz für den «+»-Knopf (verdeckt nichts — wie Android)
            Color.clear.frame(height: Spacing.fabClearance).portfolioListRow(top: 0, bottom: 0)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // iPad/Querformat: Zeilen höchstens 640 pt breit, mittig
        .readableListMargins()
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: summary.open.map(\.coin))
        .animation(.easeInOut(duration: 0.25), value: showClosed)
        .refreshable { await model.refresh(force: true) }
        .overlay(alignment: .bottomTrailing) {
            PortfolioAddButton { sheet = PortfolioTxDraft() }
        }
    }
}
