import XCTest
@testable import CryptoChecker

/// Hinzufügen über «Paar hinzufügen» als Verhalten von `ExplorerViewModel` auf `AppData.shared` der Test-App (eigene
/// Testpaare, danach wiederhergestellt): Meldung, Haptik und die Frage nach Mitteilungen je Ausgang — neu, schon da,
/// gesperrt, mehrere auf einmal samt «Rückgängig». Effekte über `FakeExplorerEffects` (keine Systemabfrage).
/// Rückfrage «nicht gespeichert» hier aus (eigenes Tor, «gespeichert»), wie `AddWatchesBehaviorTests`.
@MainActor
final class ExplorerAddFeedbackTests: XCTestCase {
    private static let btc = Watch(id: 990_301, exchangeKey: "Binance", exchangeName: "Binance", baseAsset: "BTC",
                                   quoteAsset: "USDT", pairId: "BTCUSDT", sortOrder: 0)

    private var watchlist: AppDataTestWatchlist?

    override func setUp() async throws {
        watchlist = try await AppDataTestWatchlist()
        WatchlistNotSavedGate.appDataOverride = (gate: WatchlistNotSavedGate(), notSaved: false, canAsk: false)
    }

    override func tearDown() async throws {
        WatchlistNotSavedGate.appDataOverride = nil
        _ = AppData.shared.takeAddCelebration()
        await watchlist?.restore()
        watchlist = nil
    }

    private func pair(_ base: String) -> ExchangePair { ExchangePair(base, "USDT", "\(base)USDT") }

    private func makeModel() -> (ExplorerViewModel, FakeExplorerEffects) {
        let effects = FakeExplorerEffects()
        return (ExplorerViewModel(appliesAssetScope: false, effects: effects), effects)
    }

    private var ids: Set<Int64> { Set(AppData.shared.snapshot.watches.map(\.id)) }

    func testNewPairConfirmsAndAsksForNotifications() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc])
        let binance = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        let (vm, effects) = makeModel()

        vm.add(exchange: binance, pair: pair("ETH"))

        XCTAssertTrue(AppData.shared.contains(exchangeKey: "Binance", pair: pair("ETH")))
        XCTAssertEqual(vm.message?.text, L("explorer_added_to_watchlist"))
        XCTAssertEqual(vm.message?.kind, .success)
        XCTAssertEqual(effects.haptics, [.success])
        XCTAssertEqual(effects.notificationRequests, 1)
        XCTAssertEqual(effects.announced, [], "nur das allererste Paar wird angesagt")
    }

    func testDuplicateWarnsWithoutNotifications() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc])
        let binance = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        let (vm, effects) = makeModel()

        vm.add(exchange: binance, pair: pair("BTC"))

        XCTAssertEqual(ids, [Self.btc.id])
        XCTAssertEqual(vm.message?.text, L("explorer_already_in_watchlist"))
        XCTAssertEqual(vm.message?.kind, .info)
        XCTAssertEqual(effects.haptics, [.warning])
        XCTAssertEqual(effects.notificationRequests, 0)
    }

    /// Gesperrt: nichts angelegt, keine eigene Meldung und keine Haptik (der Hinweis kommt von `AppData`).
    func testLockedWatchlistStaysQuiet() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc])
        let binance = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        let (vm, effects) = makeModel()
        AppData.shared.watchlistLocked = true

        vm.add(exchange: binance, pair: pair("SOL"))

        XCTAssertEqual(ids, [Self.btc.id])
        XCTAssertNil(vm.message)
        XCTAssertEqual(effects.haptics, [])
        XCTAssertEqual(effects.notificationRequests, 0)
    }

    /// Allererstes Paar: Meldung bleibt länger, wird angesagt und bietet «Alarm setzen» an.
    func testFirstPairEverIsAnnouncedAndOffersAlarm() async throws {
        try await XCTUnwrap(watchlist).load([])
        AppData.shared.settings.firstPairAdded = false
        XCTAssertTrue(AppData.shared.isFirstPairAdd)
        let binance = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        let (vm, effects) = makeModel()

        vm.add(exchange: binance, pair: pair("ETH"))

        let added = try XCTUnwrap(AppData.shared.snapshot.watches.first { $0.baseAsset == "ETH" })
        let message = try XCTUnwrap(vm.message)
        XCTAssertTrue(message.long)
        XCTAssertEqual(message.alarmWatchId, added.id)
        XCTAssertEqual(message.kind, .success)
        XCTAssertEqual(effects.announced, [message.text])
        XCTAssertEqual(effects.haptics, [.success])
        XCTAssertEqual(effects.notificationRequests, 1)
    }

    func testBulkAddOffersUndoForNewPairsOnly() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc])
        let (vm, effects) = makeModel()
        vm.exchange = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        vm.bulkPairs = [pair("BTC"), pair("SOL"), pair("XRP")]

        vm.addBulkPairs()

        let message = try XCTUnwrap(vm.message)
        let undo = try XCTUnwrap(message.undoIds)
        XCTAssertEqual(undo.count, 2, "nur die neuen Paare")
        XCTAssertFalse(undo.contains(Self.btc.id))
        XCTAssertTrue(message.long)
        XCTAssertEqual(message.kind, .success)
        XCTAssertGreaterThan(message.undoFinishedAt, 0)
        XCTAssertEqual(effects.haptics, [.success])
        XCTAssertEqual(effects.notificationRequests, 1)

        vm.undoBulkAdd(undo)
        XCTAssertEqual(ids, [Self.btc.id], "«Rückgängig» entfernt genau die neuen")
    }

    func testBulkAddOfKnownPairsWarnsWithoutUndo() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc])
        let (vm, effects) = makeModel()
        vm.exchange = try XCTUnwrap(ExchangesConfig.exchange("Binance"))
        vm.bulkPairs = [pair("BTC")]

        vm.addBulkPairs()

        XCTAssertNil(vm.message?.undoIds)
        XCTAssertEqual(vm.message?.kind, .info)
        XCTAssertEqual(effects.haptics, [.warning])
        XCTAssertEqual(effects.notificationRequests, 0)
        XCTAssertEqual(ids, [Self.btc.id])
    }
}
