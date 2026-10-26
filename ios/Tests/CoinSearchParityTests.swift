import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `coin_search.json`: Coin-Suche und Paar-Vorschlag in «Paar hinzufügen»
/// (Android: `CoinSearchParityTest.kt`) — nur aus geladenen Listen und dem Namensverzeichnis.
final class CoinSearchParityTests: XCTestCase {

    private typealias S = TestSupport

    private struct Setup {
        let markets: [Market]
        let cache: [String: MarketPairsInfo]
        let names: ExplorerNameIndex
    }

    private func setup(_ data: [String: Any]) throws -> Setup {
        var markets: [Market] = []
        for m in S.list(data["markets"]) {
            let key = try XCTUnwrap(m["key"] as? String)
            let market = try XCTUnwrap(MarketsConfig.market(key), key)
            // Gleicher Anzeigename wie in Android (Sortierung nach Börse)
            XCTAssertEqual(market.name, m["name"] as? String, key)
            markets.append(market)
        }
        var cache: [String: MarketPairsInfo] = [:]
        for (key, rows) in try XCTUnwrap(data["cache"] as? [String: Any]) {
            var pairs: [MarketPair] = []
            for p in S.list(rows) {
                let contractName = p["contract"] as? String ?? "NONE"
                let contract = try XCTUnwrap(FuturesContractType.allCases.first { $0.backupName == contractName })
                pairs.append(MarketPair(p["base"] as? String ?? "", p["quote"] as? String ?? "", p["pairId"] as? String,
                                        contract, tradFi: p["tradFi"] as? Bool ?? false))
            }
            cache[key] = MarketPairsInfo(lastSyncDate: 1, pairs: pairs)
        }
        let names = ExplorerNameIndex(names: data["names"] as? [String: String] ?? [:])
        return Setup(markets: markets, cache: cache, names: names)
    }

    private func scope(_ c: [String: Any]) -> AssetScope {
        (c["scope"] as? String).flatMap(AssetScope.init(rawValue:)) ?? .BOTH
    }

    private func coins(_ c: [String: Any], _ s: Setup) -> [ExplorerCoinHit] {
        ExplorerCoinSearch.coins(c["query"] as? String ?? "", markets: s.markets, cache: s.cache, names: s.names,
                                 scope: scope(c), includeUnlisted: c["includeUnlisted"] as? Bool ?? false)
    }

    func testCoins() throws {
        let data = try S.fixture("coin_search")
        let s = try setup(data)
        for c in S.list(data["coins"]) {
            let actual = coins(c, s).map { "\($0.symbol)|\($0.tradFi)|\($0.exchanges)|\($0.pairs)" }
            XCTAssertEqual(actual, c["expected"] as? [String] ?? [],
                           "coins: \(c["query"] ?? "") \(c["scope"] ?? "") \(c["includeUnlisted"] ?? "")")
        }
    }

    func testNamesOfHits() throws {
        let data = try S.fixture("coin_search")
        let s = try setup(data)
        for c in S.list(data["names_of_hits"]) {
            XCTAssertEqual(coins(c, s).map { $0.name ?? "" }, c["expected"] as? [String] ?? [], "names: \(c["query"] ?? "")")
        }
    }

    func testShowPairHits() throws {
        let data = try S.fixture("coin_search")
        for c in S.list(data["showPairHits"]) {
            let query = c["query"] as? String ?? ""
            XCTAssertEqual(ExplorerCoinSearch.showPairHits(query, coinsFound: c["coinsFound"] as? Bool ?? false),
                           c["expected"] as? Bool, "showPairHits: \(query)")
        }
    }

    func testPairsFor() throws {
        let data = try S.fixture("coin_search")
        let s = try setup(data)
        for c in S.list(data["pairsFor"]) {
            let coin = ExplorerCoinHit(symbol: c["coin"] as? String ?? "", tradFi: c["tradFi"] as? Bool ?? false,
                                       name: nil, exchanges: 0, pairs: 0)
            let preference = ExplorerCoinPairPreference(defaultExchange: c["defaultExchange"] as? String,
                                                        favoriteExchanges: Set(c["favorites"] as? [String] ?? []),
                                                        displayCurrency: c["displayCurrency"] as? String,
                                                        exchangeRank: (c["exchangeRank"] as? [String: Any] ?? [:])
                                                            .compactMapValues { ($0 as? NSNumber)?.intValue })
            let actual = ExplorerCoinSearch.pairs(for: coin, markets: s.markets, cache: s.cache, preference: preference,
                                                  scope: scope(c)).map {
                "\($0.market.key) \($0.pair.base)/\($0.pair.quote) \($0.pair.contractType.backupName)"
            }
            XCTAssertEqual(actual, c["expected"] as? [String] ?? [],
                           "pairsFor: \(c["coin"] ?? "") \(c["defaultExchange"] ?? "")")
        }
    }

    func testQuotes() throws {
        let data = try S.fixture("coin_search")
        for c in S.list(data["quotes"]) {
            let currency = c["displayCurrency"] as? String
            XCTAssertEqual(ExplorerCoinPairPreference(displayCurrency: currency).quotes, c["expected"] as? [String] ?? [],
                           "quotes: \(currency ?? "nil")")
        }
    }
}
