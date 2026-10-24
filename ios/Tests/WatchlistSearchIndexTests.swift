import XCTest
@testable import CryptoChecker

/// Zwischengespeicherte Suchschlüssel der Merkliste — wie `WatchSearchIndexTest.kt`.
final class WatchlistSearchIndexTests: XCTestCase {
    private let watches = [
        Watch(id: 1, marketKey: "Kraken", marketName: "Kraken", baseAsset: "BTC", quoteAsset: "EUR"),
        Watch(id: 2, marketKey: "Binance", marketName: "Binance", baseAsset: "PEPE", quoteAsset: "USDT",
              note: "Pépé Einstieg"),
        Watch(id: 3, marketKey: "BinanceFutures", marketName: "Binance Futures", baseAsset: "ETH",
              quoteAsset: "USDT", contractType: .perpetual, note: "Hedge ÉTÉ"),
        Watch(id: 4, marketKey: "Bitstamp", marketName: "Bitstamp", baseAsset: "XRP", quoteAsset: "CHF"),
        Watch(id: 5, marketKey: "Coinbase", marketName: "Coinbase", baseAsset: "SOL", quoteAsset: "USDC",
              note: "langfristig"),
    ]
    private let names: [Int64: String] = [1: "Bitcoin", 2: "Pépé", 3: "Ethereum", 5: "Solana"]
    private let queries = [
        "btc", "BTC kraken", "bitcoin", "coin", "pepe", "PÉPÉ", "einstieg", "ete", "été hedge", "perp", "eth/usdt",
        "usdt", "binance futures", "chf", "xrp bitstamp", "LANGFRISTIG sol", "solana usdc", "xyz", "btc usdt", "  ",
    ]

    private func direct(_ list: [Watch], _ query: String, _ names: [Int64: String]) -> [Watch] {
        list.filter { WatchlistSearch.matches($0, query: query, name: names[$0.id]) }
    }

    func testSameResultAsMatches() {
        let index = WatchlistSearchIndex()
        for q in queries {
            let query = q.trimmingCharacters(in: .whitespacesAndNewlines)
            XCTAssertEqual(index.filter(watches, query: query, names: names), direct(watches, query, names),
                           "Anfrage «\(q)»")
        }
        // Reihenfolge bleibt die der Liste, auch umgekehrt
        let reversed = Array(watches.reversed())
        XCTAssertEqual(index.filter(reversed, query: "usdt", names: names), direct(reversed, "usdt", names))
    }

    func testFoldsEachPairOnlyOnce() {
        let index = WatchlistSearchIndex()
        for q in queries { _ = index.filter(watches, query: q.trimmingCharacters(in: .whitespaces), names: names) }
        XCTAssertEqual(index.keysBuilt, watches.count)
        // Neuer Listenstand mit nur geänderten Kursen: keine neue Faltung
        let ticked = watches.map { original -> Watch in
            var w = original
            w.lastPrice = 42
            w.change24h = 1.5
            return w
        }
        _ = index.filter(ticked, query: "btc", names: names)
        XCTAssertEqual(index.keysBuilt, watches.count)
    }

    func testNameNoteAndExchangeChangesRebuildOnlyThatPair() {
        let index = WatchlistSearchIndex()
        XCTAssertEqual(index.filter(watches, query: "bitcoin", names: names).map(\.id), [1])
        // Coin-Name geändert (z. B. neue Namensliste)
        var renamed = names
        renamed[1] = "Bitkoin"
        XCTAssertTrue(index.filter(watches, query: "bitcoin", names: renamed).isEmpty)
        XCTAssertEqual(index.filter(watches, query: "bitkoin", names: renamed).map(\.id), [1])
        XCTAssertEqual(index.keysBuilt, watches.count + 1)
        // Notiz geändert
        var noted = watches
        noted[3].note = "Ripple Café"
        XCTAssertEqual(index.filter(noted, query: "cafe", names: renamed).map(\.id), [4])
        XCTAssertEqual(index.keysBuilt, watches.count + 2)
        // Börsenname geändert
        var moved = noted
        moved[4].marketName = "Coinbase Advanced"
        XCTAssertEqual(index.filter(moved, query: "advanced", names: renamed).map(\.id), [5])
        XCTAssertEqual(index.keysBuilt, watches.count + 3)
    }

    func testRemovedPairsAreDropped() {
        let index = WatchlistSearchIndex()
        _ = index.filter(watches, query: "btc", names: names)
        XCTAssertEqual(index.count, watches.count)
        _ = index.filter(Array(watches.prefix(2)), query: "btc", names: names)
        XCTAssertEqual(index.count, 2)
        index.clear()
        XCTAssertEqual(index.count, 0)
    }

    func testDuplicateIdsFallBackToDirectMatching() {
        var twin = watches[0]
        twin.baseAsset = "DOGE"
        twin.quoteAsset = "USD"
        let list = watches + [twin]
        let index = WatchlistSearchIndex()
        for q in ["doge", "btc", "kraken"] {
            XCTAssertEqual(index.filter(list, query: q, names: names), direct(list, q, names))
        }
    }

    /// Coin-Namen der Suche: einmal je Listenstand bzw. Namensliste, nicht je Tastendruck (wie `rememberSearchNames`);
    /// Kurse zählen nicht, ein anderes Paar, eine neue Namensliste und «Suche zu» schon.
    func testNamesAreCachedPerListAndNameList() {
        let stored = ["BTC": "Bitcoin", "SOL": "Solana"]
        let index = WatchlistSearchIndex()
        let first = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] })
        XCTAssertEqual(first, WatchlistSearch.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] }))
        XCTAssertEqual(first[1], "Bitcoin")
        for _ in 0..<5 { _ = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { [:] }) }
        XCTAssertEqual(index.namesBuilt, 1)
        // Nur Kurse geändert: weiter aus dem Zwischenspeicher
        let ticked = watches.map { original -> Watch in
            var w = original
            w.lastPrice = 7
            return w
        }
        _ = index.names(for: ticked, names: stored, tradFiPairs: [], stockNames: { [:] })
        XCTAssertEqual(index.namesBuilt, 1)
        // Anderes Paar an derselben Stelle bzw. neue Namensliste: neu
        var swapped = watches
        swapped[0].baseAsset = "SOL"
        XCTAssertEqual(index.names(for: swapped, names: stored, tradFiPairs: [], stockNames: { [:] })[1], "Solana")
        XCTAssertEqual(index.namesBuilt, 2)
        let renamed = index.names(for: swapped, names: ["SOL": "Sol"], tradFiPairs: [], stockNames: { [:] })
        XCTAssertEqual(renamed[1], "Sol")
        XCTAssertEqual(index.namesBuilt, 3)
        index.clear()
        _ = index.names(for: swapped, names: ["SOL": "Sol"], tradFiPairs: [], stockNames: { [:] })
        XCTAssertEqual(index.namesBuilt, 4)
    }

    /// Geänderte TradFi-Paare bzw. Aktiennamen: neu zusammengestellt (wie direkt gerechnet); unverändert aus dem
    /// Zwischenspeicher. Aktiennamen zählen nur mit TradFi-Paaren.
    func testNamesReactToTradFiPairsAndStockNames() {
        let stored = ["BTC": "Bitcoin"]
        let index = WatchlistSearchIndex()
        _ = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { ["XRP": "Ripple AG"] })
        XCTAssertEqual(index.namesBuilt, 1)
        // Ohne TradFi-Paare werden Aktiennamen nicht gelesen: andere zählen nicht
        _ = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { ["XRP": "Andere AG"] })
        XCTAssertEqual(index.namesBuilt, 1)
        let pairs: Set<String> = [watches[3].logoPairKey]
        let stocks = ["XRP": "Ripple AG"]
        let withPairs = index.names(for: watches, names: stored, tradFiPairs: pairs, stockNames: { stocks })
        XCTAssertEqual(index.namesBuilt, 2, "neue TradFi-Paare")
        XCTAssertEqual(withPairs, WatchlistSearch.names(for: watches, names: stored, tradFiPairs: pairs,
                                                        stockNames: { stocks }))
        _ = index.names(for: watches, names: stored, tradFiPairs: pairs, stockNames: { stocks })
        XCTAssertEqual(index.namesBuilt, 2, "unverändert: Zwischenspeicher")
        let renamed = ["XRP": "Ripple Labs"]
        let fresh = index.names(for: watches, names: stored, tradFiPairs: pairs, stockNames: { renamed })
        XCTAssertEqual(index.namesBuilt, 3, "neue Aktiennamen")
        XCTAssertEqual(fresh, WatchlistSearch.names(for: watches, names: stored, tradFiPairs: pairs,
                                                    stockNames: { renamed }))
        _ = index.names(for: watches, names: stored, tradFiPairs: [], stockNames: { renamed })
        XCTAssertEqual(index.namesBuilt, 4, "TradFi-Paare wieder weg")
    }
}
