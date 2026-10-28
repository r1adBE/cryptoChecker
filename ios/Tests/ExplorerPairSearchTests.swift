import XCTest
@testable import CryptoChecker

/// Suche wie an der Börse — wie `PairSearchTest.kt`: «BTCUSDT», «BTCUSDT Qtly 1225», «BTCUSD_PERP».
final class ExplorerPairSearchTests: XCTestCase {

    private var exchanges: [Exchange] { [ExchangesConfig.exchange("Binance")!, ExchangesConfig.exchange("BinanceFutures")!] }

    private let cache: [String: ExchangePairsInfo] = [
        "Binance": ExchangePairsInfo(lastSyncDate: 1, pairs: [
            ExchangePair("BTC", "USDT", "BTCUSDT"),
            ExchangePair("BTC", "EUR", "BTCEUR"),
            ExchangePair("ETH", "USDT", "ETHUSDT"),
        ]),
        "BinanceFutures": ExchangePairsInfo(lastSyncDate: 1, pairs: [
            ExchangePair("BTC", "USDT", "BTCUSDT_261225", .quarterly),
            ExchangePair("BTC", "USDT", "BTCUSDT_270326", .biquarterly),
            ExchangePair("BTC", "USDT", "BTCUSDT", .perpetual),
            ExchangePair("BTC", "USD", "2:BTCUSD_PERP", .inversePerpetual),
        ]),
    ]

    private func find(_ q: String) -> [String] {
        ExplorerPairSearch.find(q, exchanges: exchanges, cache: cache).map {
            "\($0.exchange.key) \($0.pair.base)/\($0.pair.quote) \($0.pair.contractType)"
        }
    }

    func testJoinedSymbolFindsSpotPerpAndQuarters() {
        XCTAssertEqual(find("BTCUSDT"), [
            "Binance BTC/USDT none",
            "BinanceFutures BTC/USDT perpetual",
            "BinanceFutures BTC/USDT quarterly",
            "BinanceFutures BTC/USDT biquarterly",
        ])
    }

    func testQuarterWordAndDateNarrowDown() {
        XCTAssertEqual(find("BTCUSDT Qtly 1225"), ["BinanceFutures BTC/USDT quarterly"])
        XCTAssertEqual(find("BTCUSDT_261225"), ["BinanceFutures BTC/USDT quarterly"])
        XCTAssertEqual(find("btc usdt 0326"), ["BinanceFutures BTC/USDT biquarterly"])
        XCTAssertEqual(find("BTC USDT Quartal"), ["BinanceFutures BTC/USDT quarterly", "BinanceFutures BTC/USDT biquarterly"])
    }

    func testPerpWord() {
        XCTAssertEqual(find("BTCUSD_PERP"), ["BinanceFutures BTC/USD inversePerpetual"])
        XCTAssertEqual(find("BTC PERP"), ["BinanceFutures BTC/USDT perpetual", "BinanceFutures BTC/USD inversePerpetual"])
    }

    func testClassicQueriesUnchanged() {
        XCTAssertEqual(find("BTC").first, "Binance BTC/USDT none")
        XCTAssertEqual(find("eth/usdt"), ["Binance ETH/USDT none"])
        XCTAssertEqual(find("XYZ"), [])
    }

    func testParseAndDeliveryCode() throws {
        let q = try XCTUnwrap(ExplorerPairSearch.parse("BTCUSDT Qtly 1225"))
        XCTAssertEqual(q.base, "BTCUSDT")
        XCTAssertNil(q.quote)
        XCTAssertEqual(q.date, "1225")
        XCTAssertEqual(ExplorerPairSearch.parse("1000")?.base, "1000")
        XCTAssertEqual(ExplorerPairSearch.deliveryCode(ExchangePair("BTC", "USDT", "BTCUSDT_261225", .quarterly)), "261225")
        XCTAssertNil(ExplorerPairSearch.deliveryCode(ExchangePair("BTC", "USDT", "BTCUSDT", .perpetual)))
    }

    /// Wie `arabicDigitsCountAsDate` (PairSearchTest.kt).
    func testArabicDigitsCountAsDate() {
        XCTAssertEqual(ExplorerPairSearch.parse("BTCUSDT \u{0661}\u{0662}\u{0662}\u{0665}")?.date, "1225")
        XCTAssertEqual(ExplorerPairSearch.parse("BTCUSDT \u{06F2}\u{06F6}\u{06F1}\u{06F2}\u{06F2}\u{06F5}")?.date, "261225")
        let code = ExplorerPairSearch.deliveryCode(ExchangePair("BTC", "USDT", "BTCUSDT", .quarterly))
        XCTAssertTrue(code.map { $0.allSatisfy { $0.isASCII && $0.isNumber } } ?? true)
    }

    // MARK: Zuletzt gesucht — wie `ExplorerRecentAndFilterTest.kt`

    func testRecentNewestFirst() {
        let list = ExplorerRecentSearches.add(ExplorerRecentSearches.add([], "btc"), "eth usdt")
        XCTAssertEqual(list, ["ETH USDT", "BTC"])
    }

    func testRecentDuplicateMovesToFrontOnce() {
        XCTAssertEqual(ExplorerRecentSearches.add(["ETH", "SOL", "BTC"], "  btc "), ["BTC", "ETH", "SOL"])
        // Leerräume zusammengefasst: «eth   usdt» = «ETH USDT»
        XCTAssertEqual(ExplorerRecentSearches.add(["ETH USDT"], "eth   usdt"), ["ETH USDT"])
    }

    func testRecentKeepsAtMostEight() {
        var list: [String] = []
        for i in 1...12 { list = ExplorerRecentSearches.add(list, "C\(i)") }
        XCTAssertEqual(list.count, ExplorerRecentSearches.max)
        XCTAssertEqual(list.first, "C12")
        XCTAssertEqual(list.last, "C5")
    }

    func testRecentBlankIgnoredAndSanitized() {
        XCTAssertEqual(ExplorerRecentSearches.add(["BTC"], "   "), ["BTC"])
        XCTAssertNil(ExplorerRecentSearches.normalize(""))
        XCTAssertEqual(ExplorerRecentSearches.sanitize(["btc", "", "BTC", "eth"]), ["BTC", "ETH"])
    }

    func testRecentStoredOnlyInGivenDefaults() throws {
        let suite = "explorer-recent-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        ExplorerRecentSearches.save(["BTC", "ETH USDT"], defaults)
        XCTAssertEqual(ExplorerRecentSearches.load(defaults), ["BTC", "ETH USDT"])
        ExplorerRecentSearches.save([], defaults)
        XCTAssertEqual(ExplorerRecentSearches.load(defaults), [])
    }

    // MARK: Schnellfilter

    private let stats: [String: ExplorerPairStats] = [
        "A": ExplorerPairStats(change24h: 2.0, quoteVolume: 100.0),
        "B": ExplorerPairStats(change24h: -5.0, quoteVolume: 900.0),
        "C": ExplorerPairStats(change24h: 7.5, quoteVolume: nil),
        "E": ExplorerPairStats(change24h: 0.0, quoteVolume: 50.0),
    ]
    private let items = ["A", "B", "C", "D", "E"]

    private func sorted(_ filter: ExplorerQuickFilter?) -> [String] {
        ExplorerQuickFilters.sort(items, filter) { stats[$0] }
    }

    func testNoFilterKeepsOrder() {
        XCTAssertEqual(sorted(nil), items)
    }

    func testGainersDescendingMissingLast() {
        XCTAssertEqual(sorted(.gainers), ["C", "A", "E", "B", "D"])
    }

    func testLosersAscendingMissingLast() {
        XCTAssertEqual(sorted(.losers), ["B", "E", "A", "C", "D"])
    }

    func testVolumeDescendingMissingLastInOriginalOrder() {
        XCTAssertEqual(sorted(.volume), ["B", "A", "E", "C", "D"])
    }

    func testSortIsStableForEqualValues() {
        let equal = ["X": ExplorerPairStats(change24h: 1, quoteVolume: 1), "Y": ExplorerPairStats(change24h: 1, quoteVolume: 1)]
        XCTAssertEqual(ExplorerQuickFilters.sort(["X", "Y"], .gainers) { equal[$0] }, ["X", "Y"])
        XCTAssertEqual(ExplorerQuickFilters.sort(["X", "Y"], .losers) { equal[$0] }, ["X", "Y"])
    }

    func testAvailabilityFollowsData() {
        XCTAssertEqual(ExplorerQuickFilters.available([nil, nil]), [])
        XCTAssertEqual(ExplorerQuickFilters.available([ExplorerPairStats(change24h: nil, quoteVolume: 3)]), [.volume])
        XCTAssertEqual(ExplorerQuickFilters.available([ExplorerPairStats(change24h: 1, quoteVolume: nil)]), [.gainers, .losers])
    }

    func testTapAgainClears() {
        XCTAssertEqual(ExplorerQuickFilters.toggle(nil, .gainers), .gainers)
        XCTAssertEqual(ExplorerQuickFilters.toggle(.gainers, .volume), .volume)
        XCTAssertNil(ExplorerQuickFilters.toggle(.volume, .volume))
    }

    func testStatsFromTicker() {
        var t = Ticker()
        t.last = 2
        t.vol = 10
        t.change24hPercent = 1.5
        // Kein Volumen in der Gegenwährung: Menge × Kurs
        XCTAssertEqual(ExplorerQuickFilters.stats(of: t), ExplorerPairStats(change24h: 1.5, quoteVolume: 20))
        t.volQuote = 33
        XCTAssertEqual(ExplorerQuickFilters.stats(of: t).quoteVolume, 33)
        let empty = ExplorerQuickFilters.stats(of: Ticker())
        XCTAssertNil(empty.change24h)
        XCTAssertNil(empty.quoteVolume)
        t.change24hPercent = .nan
        XCTAssertNil(ExplorerQuickFilters.stats(of: t).change24h)
    }

    // MARK: Suche über Namen und Börsen — wie `PairSearchTest.kt`

    private var nameExchanges: [Exchange] {
        [ExchangesConfig.exchange("Binance")!, ExchangesConfig.exchange("Kraken")!, ExchangesConfig.exchange("BinanceFutures")!]
    }

    private let nameCache: [String: ExchangePairsInfo] = [
        "Binance": ExchangePairsInfo(lastSyncDate: 1, pairs: [
            ExchangePair("BTC", "USDT", "BTCUSDT"),
            ExchangePair("ETH", "USDT", "ETHUSDT"),
            ExchangePair("ETC", "USDT", "ETCUSDT"),
            ExchangePair("SOL", "USDT", "SOLUSDT"),
            ExchangePair("USDT", "EUR", "USDTEUR"),
            ExchangePair("SHIB", "USDT", "SHIBUSDT"),
            ExchangePair("BITCOIN", "USDT", "BITCOINUSDT"),
        ]),
        "Kraken": ExchangePairsInfo(lastSyncDate: 1, pairs: [
            ExchangePair("BTC", "EUR", "XBTEUR"),
            ExchangePair("ETH", "EUR", "ETHEUR"),
        ]),
        "BinanceFutures": ExchangePairsInfo(lastSyncDate: 1, pairs: [
            ExchangePair("NVDA", "USDT", "NVDAUSDT", .perpetual, tradFi: true),
            ExchangePair("PEPE", "USDT", "1000PEPEUSDT", .perpetual),
        ]),
    ]

    private let names = ExplorerNameIndex(names: [
        "BTC": "Bitcoin",
        "ETH": "Ethereum",
        "ETC": "Ethereum Classic",
        "SOL": "Solana",
        "USDT": "Tether",
        "SHIB": "Shiba Inu",
        "PEPE": "Pépé",
        "TRADFI:NVDA": "NVIDIA",
        // Krypto-Token gleichen Kürzels: nie für das TradFi-Paar
        "NVDA": "Nvidia Token",
    ])

    private func findNamed(_ q: String, _ n: ExplorerNameIndex? = nil) -> [String] {
        ExplorerPairSearch.find(q, exchanges: nameExchanges, cache: nameCache, names: n ?? names).map {
            "\($0.exchange.key) \($0.pair.base)/\($0.pair.quote)"
        }
    }

    func testNameFindsCoin() {
        XCTAssertEqual(findNamed("bitcoin").filter { $0.contains("BTC/") }, ["Binance BTC/USDT", "Kraken BTC/EUR"])
        XCTAssertEqual(findNamed("solana"), ["Binance SOL/USDT"])
        XCTAssertEqual(findNamed("SOL").first, "Binance SOL/USDT")
        // Gross/klein und Akzente egal
        XCTAssertEqual(findNamed("pepe"), ["BinanceFutures PEPE/USDT"])
        XCTAssertEqual(findNamed("PÉPÉ"), ["BinanceFutures PEPE/USDT"])
        // Ohne Namen bleibt alles wie bisher
        XCTAssertEqual(findNamed("solana", .empty), [])
    }

    func testRankingSymbolThenNamePrefixThenContains() {
        // «ether»: Name-Anfang (Ethereum, Ethereum Classic; gleicher Rang, nach Kürzel) vor «enthält» (Tether)
        XCTAssertEqual(findNamed("ether"), ["Binance ETC/USDT", "Binance ETH/USDT", "Kraken ETH/EUR", "Binance USDT/EUR"])
        // Exaktes Kürzel vor Kürzel-Anfang vor Name: «BITCOIN» ist ein eigener Coin
        let bitcoin = findNamed("bitcoin")
        XCTAssertEqual(bitcoin.first, "Binance BITCOIN/USDT")
        XCTAssertGreaterThan(bitcoin.firstIndex(of: "Binance BTC/USDT") ?? -1, 0)
        // «eth»: zuerst das Kürzel ETH, dann ETC (Name-Anfang), Tether (enthält) zuletzt
        XCTAssertEqual(findNamed("eth"), ["Binance ETH/USDT", "Kraken ETH/EUR", "Binance ETC/USDT", "Binance USDT/EUR"])
    }

    func testNameWithQuoteAndMultiWordName() {
        XCTAssertEqual(findNamed("bitcoin usdt").filter { $0.contains("BTC/") }, ["Binance BTC/USDT"])
        XCTAssertEqual(findNamed("ethereum eur"), ["Kraken ETH/EUR"])
        XCTAssertEqual(findNamed("shiba inu"), ["Binance SHIB/USDT"])
        XCTAssertEqual(findNamed("inu"), ["Binance SHIB/USDT"])
    }

    func testTradFiUsesItsOwnName() {
        XCTAssertEqual(findNamed("nvidia"), ["BinanceFutures NVDA/USDT"])
        XCTAssertEqual(findNamed("nvidia token"), [])
    }

    func testExchangeWordsFilterAndMatchLast() {
        XCTAssertEqual(findNamed("btc kraken"), ["Kraken BTC/EUR"])
        XCTAssertEqual(findNamed("bitcoin kraken"), ["Kraken BTC/EUR"])
        XCTAssertEqual(findNamed("kraken eth"), ["Kraken ETH/EUR"])
        // Nur die Börse: alle ihre Paare
        XCTAssertEqual(findNamed("krak"), ["Kraken BTC/EUR", "Kraken ETH/EUR"])
        // Zu kurze Wörter nennen keine Börse
        XCTAssertEqual(findNamed("kr"), [])
    }

    func testNameMatchRules() {
        XCTAssertEqual(ExplorerNameMatch.score("ethereum classic", "classic"), ExplorerNameMatch.prefix)
        XCTAssertEqual(ExplorerNameMatch.score("bitcoin", "bi"), ExplorerNameMatch.prefix)
        XCTAssertEqual(ExplorerNameMatch.score("tether", "eth"), ExplorerNameMatch.contains)
        // Enthält erst ab 3 Zeichen, Anfang ab 2
        XCTAssertNil(ExplorerNameMatch.score("tether", "et"))
        XCTAssertNil(ExplorerNameMatch.score("bitcoin", "b"))
        XCTAssertEqual(ExplorerNameMatch.fold(" Pépé "), "pepe")
        XCTAssertTrue(ExplorerNameMatch.matchesExchange(ExplorerNameMatch.fold("Crypto.com"), "cryptocom"))
        XCTAssertTrue(ExplorerNameMatch.matchesExchange(ExplorerNameMatch.fold("Binance Futures"), "fut"))
        XCTAssertFalse(ExplorerNameMatch.matchesExchange(ExplorerNameMatch.fold("Kraken"), "kr"))
    }

    func testStockNamesOnlyForTradFiBasesWithoutName() {
        let index = ExplorerNameIndex(names: ["TRADFI:NVDA": "NVIDIA"], stockNames: ["NVDA": "Nvidia Corp", "CAT": "Caterpillar"],
                                      tradFiBases: ["NVDA", "CAT"])
        XCTAssertEqual(index.folded("TRADFI:NVDA"), "nvidia")
        XCTAssertEqual(index.folded("TRADFI:CAT"), "caterpillar")
        XCTAssertNil(index.folded("CAT"))
        // Die Coin-Suche zeigt den Namen wie geliefert und geht alle Einträge durch (wie Android)
        XCTAssertEqual(index.name("TRADFI:CAT"), "Caterpillar")
        XCTAssertEqual(index.entries.count, 2)
        XCTAssertTrue(ExplorerNameIndex(names: ["X": "  "]).isEmpty)
    }

    // MARK: Suche in der Merkliste — wie `WatchSearchTest.kt`

    func testWatchlistSearchMatchesName() {
        let btc = Watch(id: 1, exchangeKey: "Kraken", exchangeName: "Kraken", baseAsset: "BTC", quoteAsset: "EUR")
        XCTAssertTrue(WatchlistSearch.matches(btc, query: "btc"))
        XCTAssertTrue(WatchlistSearch.matches(btc, query: "btc kraken"))
        XCTAssertFalse(WatchlistSearch.matches(btc, query: "bitcoin"))
        XCTAssertTrue(WatchlistSearch.matches(btc, query: "bitcoin", name: "Bitcoin"))
        XCTAssertTrue(WatchlistSearch.matches(btc, query: "BITCOIN eur", name: "Bitcoin"))
        XCTAssertTrue(WatchlistSearch.matches(btc, query: "coin", name: "Bitcoin"))
        XCTAssertFalse(WatchlistSearch.matches(btc, query: "bitcoin usdt", name: "Bitcoin"))
        let pepe = Watch(id: 2, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "PEPE", quoteAsset: "USDT")
        XCTAssertTrue(WatchlistSearch.matches(pepe, query: "pépé", name: "Pepe"))
        XCTAssertTrue(WatchlistSearch.matches(pepe, query: "PEPE", name: "Pépé"))
    }

    func testWatchlistSearchNameUsesTradFiAndSkipsDex() {
        let btc = Watch(id: 1, exchangeKey: "Kraken", exchangeName: "Kraken", baseAsset: "BTC", quoteAsset: "EUR")
        let nvda = Watch(id: 2, exchangeKey: "BinanceFutures", exchangeName: "Binance Futures", baseAsset: "NVDA", quoteAsset: "USDT")
        let dex = Watch(id: 3, exchangeKey: "DexScreener", exchangeName: "DEX", baseAsset: "BTC", quoteAsset: "SOL")
        let names = ["BTC": "Bitcoin", "NVDA": "Nvidia Token", "TRADFI:NVDA": "NVIDIA"]
        let tradFi: Set<String> = [nvda.logoPairKey]
        XCTAssertEqual(WatchlistSearch.searchName(btc, names: names, tradFiPairs: tradFi), "Bitcoin")
        XCTAssertEqual(WatchlistSearch.searchName(nvda, names: names, tradFiPairs: tradFi), "NVIDIA")
        XCTAssertEqual(WatchlistSearch.searchName(nvda, names: names, tradFiPairs: []), "Nvidia Token")
        XCTAssertNil(WatchlistSearch.searchName(dex, names: names, tradFiPairs: tradFi))
    }
}
