import XCTest
@testable import CryptoChecker

/// Hält fest, was `ExplorerViewModel` auslöst (Haptik, Ansage, Frage nach Mitteilungen), statt es abzuspielen.
@MainActor
final class FakeExplorerEffects: ExplorerEffects {
    private(set) var haptics: [HapticEvent] = []
    private(set) var announced: [String] = []
    private(set) var notificationRequests = 0

    func haptic(_ event: HapticEvent) { haptics.append(event) }

    func announce(_ text: String) { announced.append(text) }

    func requestNotifications() { notificationRequests += 1 }
}

/// Auswahl von «Paar hinzufügen» als Verhalten von `ExplorerViewModel`: Coin, Gegenwährung, Laufzeit, Sammelaufnahme,
/// DEX-Treffer und Rückmeldungen ohne Paar. Ohne Börse bzw. mit DexScreener — kein Abruf, keine Paarliste vom Gerät.
@MainActor
final class ExplorerViewModelTests: XCTestCase {
    /// Modell ohne «Token & TradFi»-Filter (kein Ranking-Abruf beim Erstellen) samt Fälschung der Effekte.
    private func makeModel() -> (ExplorerViewModel, FakeExplorerEffects) {
        let effects = FakeExplorerEffects()
        return (ExplorerViewModel(appliesAssetScope: false, effects: effects), effects)
    }

    private func list(_ pairs: [ExchangePair]) -> ExchangePairsInfo {
        ExchangePairsInfo(lastSyncDate: 1, pairs: pairs)
    }

    private var spot: [ExchangePair] {
        [ExchangePair("BTC", "USDT", "BTCUSDT"), ExchangePair("BTC", "EUR", "BTCEUR"), ExchangePair("ETH", "USDT", "ETHUSDT")]
    }

    // MARK: Auswahl

    func testReconcileOffersCoinsQuotesAndFirstPair() {
        let (vm, _) = makeModel()
        vm.pairList = list(spot)
        vm.reconcile()
        XCTAssertEqual(vm.coins, ["BTC", "ETH"])
        XCTAssertEqual(vm.coin, "BTC")
        XCTAssertEqual(vm.quotes, ["USDT", "EUR"])
        XCTAssertEqual(vm.quote, "USDT")
        XCTAssertEqual(vm.contracts, [FuturesContractType.none])
        XCTAssertEqual(vm.contract, FuturesContractType.none)
        XCTAssertEqual(vm.selectedPair, ExchangePair("BTC", "USDT", "BTCUSDT"))
        XCTAssertTrue(vm.hasPairs)
        XCTAssertTrue(vm.pairSelected)
        XCTAssertFalse(vm.hasContracts, "Spot allein braucht kein Kontraktfeld")
        XCTAssertNil(vm.price, "ohne Börse kein Kurs")
    }

    func testEmptyListSelectsNothing() {
        let (vm, _) = makeModel()
        vm.reconcile()
        XCTAssertEqual(vm.coins, [])
        XCTAssertNil(vm.coin)
        XCTAssertNil(vm.quote)
        XCTAssertNil(vm.selectedPair)
        XCTAssertFalse(vm.pairSelected)
    }

    func testCoinChangeKeepsOfferedQuoteElseFirst() {
        let (vm, _) = makeModel()
        vm.pairList = list(spot)
        vm.reconcile()
        vm.selectQuote("EUR")
        XCTAssertEqual(vm.selectedPair?.pairId, "BTCEUR")
        // ETH gibt es nur gegen USDT: die erste angebotene rückt nach
        vm.selectCoin("ETH")
        XCTAssertEqual(vm.quotes, ["USDT"])
        XCTAssertEqual(vm.quote, "USDT")
        XCTAssertEqual(vm.selectedPair?.pairId, "ETHUSDT")
        // Zurück zu BTC: USDT wird auch dort angeboten und bleibt
        vm.selectCoin("BTC")
        XCTAssertEqual(vm.quote, "USDT")
        XCTAssertEqual(vm.selectedPair?.pairId, "BTCUSDT")
    }

    func testUnlistedRememberedValuesFallBackToFirst() {
        let (vm, _) = makeModel()
        vm.coin = "XRP"
        vm.quote = "JPY"
        vm.pairList = list(spot)
        vm.reconcile()
        XCTAssertEqual(vm.coin, "BTC")
        XCTAssertEqual(vm.quote, "USDT")
    }

    func testContractsOnlyForFuturesAndSelectableWithSeveral() {
        let (vm, _) = makeModel()
        vm.pairList = list([
            ExchangePair("BTC", "USDT", "BTCUSDT", .perpetual),
            ExchangePair("BTC", "USDT", "BTCUSDT_261225", .quarterly)
        ])
        vm.reconcile()
        XCTAssertEqual(vm.contracts, [.perpetual, .quarterly])
        XCTAssertEqual(vm.contract, .perpetual)
        XCTAssertTrue(vm.hasContracts)
        XCTAssertTrue(vm.contractSelectable)
        vm.selectContract(.quarterly)
        XCTAssertEqual(vm.selectedPair?.pairId, "BTCUSDT_261225")

        vm.pairList = list([ExchangePair("BTC", "USDT", "BTCUSDT", .perpetual)])
        vm.reconcile()
        XCTAssertEqual(vm.contract, .perpetual, "Laufzeit fällt weg: die erste rückt nach")
        XCTAssertTrue(vm.hasContracts)
        XCTAssertFalse(vm.contractSelectable, "eine Laufzeit ist nicht wählbar")
    }

    /// «Paar bearbeiten» eines DEX-Pools: Werte vorbelegt, Börse bleibt leer (Pools nur über die DEX-Suche).
    func testPrefillDexPoolLeavesExchangeEmpty() {
        let (vm, _) = makeModel()
        vm.prefill(Watch(id: 1, exchangeKey: ExplorerViewModel.dexKey, exchangeName: "DEX", baseAsset: "PEPE",
                         quoteAsset: "SOL"))
        XCTAssertEqual(vm.coin, "PEPE")
        XCTAssertEqual(vm.quote, "SOL")
        XCTAssertNil(vm.exchange)
    }

    // MARK: Kurs und Börse

    func testDexExchangeShowsNoPrice() throws {
        let (vm, _) = makeModel()
        vm.exchange = try XCTUnwrap(ExchangesConfig.exchange(ExplorerViewModel.dexKey))
        vm.pairList = list(spot)
        vm.reconcile()
        XCTAssertTrue(vm.isDex)
        XCTAssertNotNil(vm.selectedPair)
        XCTAssertNil(vm.price)
    }

    func testNewPairLoadsPriceAndClosingExchangeDropsIt() throws {
        let (vm, _) = makeModel()
        defer { vm.priceTask?.cancel() }
        vm.exchange = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        vm.pairList = list(spot)
        vm.reconcile()
        guard case .loading? = vm.price else { return XCTFail("Kurs nicht angefordert") }
        // Abbruch vor dem Ende der Wartezeit: kein Abruf
        vm.priceTask?.cancel()

        vm.closeExchange()
        XCTAssertNil(vm.exchange)
        XCTAssertNil(vm.pairList)
        XCTAssertNil(vm.price)
        XCTAssertNil(vm.priceKey)
        XCTAssertFalse(vm.canReloadPairs)
        XCTAssertEqual(vm.coins, [])
        XCTAssertNil(vm.selectedPair)
    }

    // MARK: Sammelaufnahme

    func testBulkQuotePrefersUsdtAndCollectsSortedPairs() {
        let (vm, _) = makeModel()
        vm.pairList = list([
            ExchangePair("SOL", "EUR", "SOLEUR"),
            ExchangePair("ETH", "EUR", "ETHEUR"),
            ExchangePair("BTC", "USDT", "BTCUSDT")
        ])
        vm.reconcile()
        XCTAssertEqual(vm.bulkQuotes, ["EUR", "USDT"], "meistgenutzte zuerst")
        XCTAssertEqual(vm.bulkQuote, "USDT", "USDT vorgewählt")

        vm.setBulkQuote("EUR")
        vm.collectBulkPairs()
        XCTAssertEqual(vm.bulkPairs.map(\.base), ["ETH", "SOL"])
        XCTAssertFalse(vm.bulkEmpty)
        // Das erste Paar der Liste ist gleich gewählt
        XCTAssertEqual(vm.coin, "ETH")
        XCTAssertEqual(vm.quote, "EUR")
        XCTAssertEqual(vm.selectedPair?.pairId, "ETHEUR")
    }

    func testOtherQuoteOrContractDropsCollectedList() {
        let (vm, _) = makeModel()
        vm.pairList = list(spot)
        vm.reconcile()
        vm.collectBulkPairs()
        XCTAssertEqual(vm.bulkPairs.map(\.base), ["BTC", "ETH"])
        vm.setBulkQuote("USDT")
        XCTAssertEqual(vm.bulkPairs.count, 2, "gleiche Gegenwährung behält die Liste")
        vm.setBulkQuote("EUR")
        XCTAssertEqual(vm.bulkPairs, [])
        XCTAssertFalse(vm.bulkEmpty)

        vm.collectBulkPairs()
        XCTAssertEqual(vm.bulkPairs.map(\.base), ["BTC"])
        vm.selectContract(.perpetual)
        XCTAssertEqual(vm.bulkPairs, [], "anderer Kontrakttyp verwirft die Liste")
    }

    func testCollectWithoutListReportsEmpty() {
        let (vm, _) = makeModel()
        vm.collectBulkPairs()
        XCTAssertEqual(vm.bulkPairs, [])
        XCTAssertTrue(vm.bulkEmpty)
    }

    func testSelectBulkPairSelectsIt() {
        let (vm, _) = makeModel()
        vm.pairList = list(spot)
        vm.reconcile()
        vm.selectBulkPair(ExchangePair("ETH", "USDT", "ETHUSDT"))
        XCTAssertEqual(vm.coin, "ETH")
        XCTAssertEqual(vm.quote, "USDT")
        XCTAssertEqual(vm.selectedPair?.pairId, "ETHUSDT")
    }

    // MARK: Rückmeldung ohne Paar

    func testAddWithoutSelectedPairWarns() {
        let (vm, effects) = makeModel()
        vm.addSelectedPair()
        XCTAssertEqual(vm.message?.text, L("explorer_no_pair_selected"))
        XCTAssertEqual(vm.message?.showView, false)
        XCTAssertEqual(effects.haptics, [.warning])
        XCTAssertEqual(effects.notificationRequests, 0)
    }

    func testBulkAddWithoutPairsOnlyHints() {
        let (vm, effects) = makeModel()
        vm.addBulkPairs()
        XCTAssertEqual(vm.message?.text, L("explorer_no_pair_selected"))
        XCTAssertEqual(vm.message?.showView, false)
        XCTAssertEqual(effects.haptics, [])
        XCTAssertEqual(effects.notificationRequests, 0)
    }

    // MARK: Suche und DEX

    func testDexHitFollowsNameAndMarketChip() {
        let (vm, _) = makeModel()
        vm.searchQuery = "uniswap pepe"
        XCTAssertEqual(vm.dexSearchHit?.name, "DexScreener")
        XCTAssertEqual(vm.dexSearchHit?.match, DexNames.Match(dexName: "Uniswap", token: "pepe"))

        vm.searchQuery = "pepe"
        XCTAssertNil(vm.dexSearchHit, "ohne DEX-Namen unter «Alle» kein Treffer")
        vm.marketFilter = .dex
        XCTAssertEqual(vm.dexSearchHit?.match, DexNames.Match(dexName: nil, token: "pepe"), "Chip «DEX»: ganzer Text")
        vm.searchQuery = "  "
        XCTAssertNil(vm.dexSearchHit)

        vm.searchQuery = "uniswap pepe"
        vm.marketFilter = .spot
        XCTAssertNil(vm.dexSearchHit, "andere Chips zeigen DexScreener nicht")
    }

    func testEmptyQueryClearsHitsAndMarketChipSwitches() {
        let (vm, _) = makeModel()
        vm.setSearchQuery("")
        vm.setMarketFilter(.futures)
        XCTAssertEqual(vm.marketFilter, .futures)
        XCTAssertEqual(vm.coinHits.count, 0)
        XCTAssertEqual(vm.pairHits.count, 0)
        XCTAssertEqual(vm.pickedCoinPairs.count, 0)
        XCTAssertNil(vm.searchTask, "ohne Begriff keine Suche")
    }

    func testMissingListsWithoutMissingExchangesStartNothing() {
        let (vm, _) = makeModel()
        vm.loadMissingLists()
        XCTAssertNil(vm.missingListsTask)
        XCTAssertEqual(vm.searchLists, ExplorerSearchLists())
    }

    func testUnloadedCountsOnlyExchangesWithPairListAndNoList() throws {
        let (vm, _) = makeModel()
        let binance = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        let kraken = try XCTUnwrap(ExchangesConfig.exchange("Kraken"))
        let dex = try XCTUnwrap(ExchangesConfig.exchange(ExplorerViewModel.dexKey))
        vm.searchExchanges = [binance, kraken, dex]
        vm.searchCache = ["Binance": list(spot)]
        vm.updateUnloaded()
        XCTAssertEqual(vm.unloadedExchanges.map(\.key), ["Kraken"], "DexScreener hat keine Paarliste")
        XCTAssertEqual(vm.searchLists, ExplorerSearchLists(missing: 1))
    }
}
