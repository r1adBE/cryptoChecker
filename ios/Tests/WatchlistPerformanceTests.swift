import XCTest
@testable import CryptoChecker

/// Leistung der Merkliste mit bis zu 2000 Paaren (Obergrenze), reine Logik ohne Darstellung: Spaltensortierung,
/// Gruppen- und Schnellfilter, Suche, Puls, Plan der Listen-Widgets und Zählung der Sammelaufnahme. Grenzen und
/// Skalierung: `PerformanceTiming` (Debug-Build, darum grosszügiger als Android). Wie `WatchlistPerformanceTest.kt`.
final class WatchlistPerformanceTests: XCTestCase {
    private typealias T = PerformanceTiming
    private let view = ChangeView()

    /// Kurs-Spalte wie `WatchlistList.sortValue`: Kurs über CHF vergleichbar, ohne Kurs ans Ende.
    private static func sortValue(_ w: Watch) -> Double? {
        guard let price = w.lastPrice, price > 0 else { return nil }
        return price * PerformanceData.chf(perQuote: w.quoteAsset)
    }

    private func names(_ watches: [Watch]) -> [Int64: String] {
        var out: [Int64: String] = [:]
        for w in watches { if let name = PerformanceData.coinNames[w.baseAsset] { out[w.id] = name } }
        return out
    }

    func testColumnSortByNamePriceAndChange() {
        let view = self.view
        // Erwartet einige 100 ms für alle sechs Sortierungen (Debug); Grenze grosszügig
        T.assertScales("Spaltensortierung Name/Kurs/24h auf- und absteigend", limitMillis: 2_000) { n in
            let watches = PerformanceData.watches(n)
            return T.medianMillis {
                var checksum: Int64 = 0
                for key in SortKey.allCases {
                    for descending in [false, true] {
                        let sorted = ColumnSort.apply(watches, ColumnSort(key: key, descending: descending),
                                                      favorite: { $0.favorite }, name: { $0.displayName },
                                                      value: Self.sortValue, change: { view.shown($0) })
                        checksum += sorted.first?.id ?? 0
                    }
                }
                return checksum
            }
        }
        // Ergebnis stimmt: Favoriten oben, darunter nach Name
        let sorted = ColumnSort.apply(PerformanceData.watches(T.large), ColumnSort(key: .NAME, descending: false),
                                      favorite: { $0.favorite }, name: { $0.displayName },
                                      value: Self.sortValue, change: { view.shown($0) })
        XCTAssertEqual(sorted.count, T.large)
        let favorites = sorted.prefix(while: { $0.favorite })
        XCTAssertFalse(favorites.isEmpty)
        XCTAssertFalse(sorted.dropFirst(favorites.count).contains { $0.favorite })
        let favoriteNames = favorites.map { $0.displayName.lowercased() }
        XCTAssertEqual(favoriteNames, favoriteNames.sorted())
    }

    func testGroupAndQuickFilters() {
        let selections: [String?] = [nil, WatchFilter.favorites, WatchFilter.tokens, "Layer 1", "DeFi", "Memes"]
        T.assertScales("Gruppen- und Schnellfilter", limitMillis: 300) { n in
            let watches = PerformanceData.watches(n)
            return T.medianMillis {
                var shown = 0
                // Wie `AppData.visibleWatches`
                for selection in selections { shown += watches.filter { WatchFilter.matches(selection, $0) }.count }
                // Schnellansichten: nur deaktivierte, nicht gehandelte, aktive
                shown += DisabledView.only(watches) { $0.disabled }.count
                shown += watches.filter(\.isNotTraded).count
                return shown + DisabledView.active(watches) { $0.disabled }.count
            }
        }
    }

    func testSearchWithCoinNames() {
        let queries = ["btc", "usdt kraken", "bitcoin", "layer", "pépé eur", "xyz"]
        // Jede Suche faltet je Paar acht Felder (Akzente, Kleinschreibung): erwartet einige 100 ms für sechs Suchen
        T.assertScales("Suche (6 Anfragen, mit Coin-Namen)", limitMillis: 4_000) { n in
            let watches = PerformanceData.watches(n)
            let names = self.names(watches)
            return T.medianMillis {
                queries.reduce(0) { total, q in
                    total + watches.filter { WatchlistSearch.matches($0, query: q, name: names[$0.id]) }.count
                }
            }
        }
        let watches = PerformanceData.watches(T.large)
        let names = self.names(watches)
        // «bitcoin» findet genau die BTC-Paare (20 Quote-Währungen), «xyz» nichts
        XCTAssertEqual(watches.filter { WatchlistSearch.matches($0, query: "bitcoin", name: names[$0.id]) }.count, 20)
        XCTAssertEqual(watches.filter { WatchlistSearch.matches($0, query: "xyz", name: names[$0.id]) }.count, 0)
    }

    func testPulseAndBreadth() {
        let view = self.view
        T.assertScales("Puls der Merkliste und Marktbreite", limitMillis: 300) { n in
            let watches = PerformanceData.watches(n)
            return T.medianMillis {
                // Wie `WatchlistList`: deaktivierte zählen nicht mit
                let counted = DisabledView.active(watches) { $0.disabled }
                let pulse = WatchlistPulseStats.make(counted, view: view)
                return (pulse?.up ?? 0) + (CryptoPulse.breadth(counted.compactMap(\.change24h))?.total ?? 0)
            }
        }
        let counted = DisabledView.active(PerformanceData.watches(T.large)) { $0.disabled }
        let pulse = WatchlistPulseStats.make(counted, view: view)
        XCTAssertNotNil(pulse)
        XCTAssertGreaterThan(pulse?.up ?? 0, 0)
        XCTAssertGreaterThan(pulse?.down ?? 0, 0)
        XCTAssertGreaterThan(pulse?.missing ?? 0, 0)
    }

    func testListWidgetPlan() {
        // Einrichtung (wählbare Einträge) und je Eintrag die Paare des Widgets
        T.assertScales("Plan der Listen-Widgets (alle Gruppen)", limitMillis: 300) { n in
            let watches = PerformanceData.watches(n)
            return T.medianMillis {
                let options = WidgetGroupSelection.options(groupNames: watches.map(\.groupName),
                                                           alphaFlags: watches.map { _ in false })
                return options.reduce(0) { total, option in
                    total + WidgetGroupSelection.pick(option, items: watches, groupName: { $0.groupName },
                                                      favorite: { $0.favorite }).items.count
                }
            }
        }
    }

    func testBulkAddCountAgainstFullWatchlist() {
        // 2000 Binance-Paare der Auswahl gegen 2000 vorhandene (jedes fünfte davon an Binance)
        T.assertScales("Zählung der Sammelaufnahme", limitMillis: 500) { n in
            let watches = PerformanceData.watches(n)
            let pairs = PerformanceData.binancePairs(n)
            return T.medianMillis {
                ExplorerBulkCount.newCount(marketKey: PerformanceData.binance.key, pairs: pairs, watches: watches)
            }
        }
        let n = T.large
        let count = ExplorerBulkCount.newCount(marketKey: PerformanceData.binance.key,
                                               pairs: PerformanceData.binancePairs(n),
                                               watches: PerformanceData.watches(n))
        XCTAssertEqual(count, n - n / PerformanceData.markets.count)
    }
}
