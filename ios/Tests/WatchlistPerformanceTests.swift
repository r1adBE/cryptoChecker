import XCTest
@testable import CryptoChecker

/// Leistung der Merkliste mit bis zu 2000 Paaren (Obergrenze), reine Logik ohne Darstellung: Spaltensortierung,
/// Gruppen- und Schnellfilter, Suche, Puls, Plan der Listen-Widgets und Zählung der Sammelaufnahme. Grenzen und
/// Skalierung: `PerformanceTiming` (Debug-Build, darum grosszügiger als Android). Wie `WatchlistPerformanceTest.kt`.
final class WatchlistPerformanceTests: XCTestCase {
    /// Unter Thread Sanitizer (CI setzt `CC_SKIP_PERFORMANCE=1`) übersprungen: die Zeiten wären verfälscht.
    override func setUpWithError() throws {
        try super.setUpWithError()
        try PerformanceTiming.skipIfRequested()
    }

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

    /// Gespeicherte Coin-Namen wie `CoinLogoStore.names` (Schlüssel `CoinLogos.nameKey`).
    private static let storedNames: [String: String] = Dictionary(
        uniqueKeysWithValues: PerformanceData.coinNames.map { (CoinLogos.nameKey($0.key, tradFi: false), $0.value) })

    func testSearchWithCoinNames() {
        let queries = ["btc", "usdt kraken", "bitcoin", "layer", "pépé eur", "xyz"]
        let stored = WatchlistPerformanceTests.storedNames
        // Je Tastendruck wie in der App (`WatchlistSearchIndex`): Coin-Namen (`names(for:)`) und Schlüssel schon
        // zwischengespeichert; je Anfrage der Abgleich des Listenstands mit Namen und Schlüsseln (ohne Neuaufbau) und
        // die Faltung der Anfrage. Erwartet einige 10 ms für sechs Anfragen (Debug). Grenze 1000 statt 4000 ms
        // (früher faltete jede Anfrage acht Felder je Paar)
        T.assertScales("Suche je Tastendruck (6 Anfragen, mit Coin-Namen)", limitMillis: 1_000) { n in
            let watches = PerformanceData.watches(n)
            let index = WatchlistSearchIndex()
            index.update(watches, names: index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] }))
            return T.medianMillis {
                queries.reduce(0) { total, q in
                    let names = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] })
                    return total + index.filter(watches, query: q, names: names).count
                }
            }
        }
        // Vorbereitung: Coin-Namen zusammenstellen und alle Schlüssel neu falten (Suche geöffnet) — so viel wie früher
        // jede Anfrage
        T.assertScales("Suche: Schlüssel vorbereiten (mit Coin-Namen)", limitMillis: 4_000) { n in
            let watches = PerformanceData.watches(n)
            return T.medianMillis(setup: { WatchlistSearchIndex() }) { fresh in
                fresh.update(watches, names: fresh.names(for: watches, names: stored, tradFiPairs: [],
                                                         stockNames: { [:] }))
                return fresh.count
            }
        }
        let watches = PerformanceData.watches(T.large)
        let index = WatchlistSearchIndex()
        let names = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] })
        XCTAssertEqual(names, self.names(watches), "gleich wie Paar für Paar")
        // «bitcoin» findet genau die BTC-Paare (20 Quote-Währungen), «xyz» nichts; gleich wie Paar für Paar
        XCTAssertEqual(index.filter(watches, query: "bitcoin", names: names).count, 20)
        XCTAssertEqual(index.filter(watches, query: "xyz", names: names).count, 0)
        for q in queries {
            let again = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] })
            XCTAssertEqual(index.filter(watches, query: q, names: again),
                           watches.filter { WatchlistSearch.matches($0, query: q, name: names[$0.id]) }, q)
        }
        XCTAssertEqual(index.keysBuilt, T.large)
        XCTAssertEqual(index.namesBuilt, 1, "Namen nur einmal zusammengestellt, nicht je Tastendruck")
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
