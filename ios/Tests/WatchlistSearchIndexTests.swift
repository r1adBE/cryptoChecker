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
}
