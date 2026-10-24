import XCTest
@testable import CryptoChecker

/// Sammelaufnahme als Verhalten auf `AppData.shared` der Test-App (eigene Testpaare, danach wiederhergestellt), wie
/// Android `BulkAddRunnerTest`: vorhandene Paare werden übersprungen, über der Obergrenze abgelehnt (`refused`),
/// `addedIds` sind genau die neuen und «Rückgängig» (`deleteForUndo(ids:)`, wie `ExplorerBulkActions.undoBulkAdd`)
/// entfernt nur diese. Einen Fortschritt meldet iOS nicht (`addWatches` legt alles in einem Schritt an).
/// Rückfrage F-08 hier aus (eigenes Tor, «gespeichert»), sie prüft `WatchlistConsentBehaviorTests`.
@MainActor
final class AddWatchesBehaviorTests: XCTestCase {
    static let btc = Watch(id: 990_201, marketKey: "Binance", marketName: "Binance", baseAsset: "BTC",
                           quoteAsset: "USDT", pairId: "BTCUSDT", sortOrder: 0)
    static let eth = Watch(id: 990_202, marketKey: "Binance", marketName: "Binance", baseAsset: "ETH",
                           quoteAsset: "USDT", pairId: "ETHUSDT", sortOrder: 1)

    private var watchlist: AppDataTestWatchlist?

    override func setUp() async throws {
        watchlist = try await AppDataTestWatchlist()
        WatchlistNotSavedGate.appDataOverride = (gate: WatchlistNotSavedGate(), notSaved: false, canAsk: false)
    }

    override func tearDown() async throws {
        WatchlistNotSavedGate.appDataOverride = nil
        await watchlist?.restore()
        watchlist = nil
    }

    private func pair(_ base: String) -> MarketPair { MarketPair(base, "USDT", "\(base)USDT") }

    private var ids: Set<Int64> { Set(AppData.shared.snapshot.watches.map(\.id)) }

    func testBulkAddSkipsDuplicatesAndUndoRemovesOnlyTheNewPairs() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc, Self.eth])
        let data = AppData.shared
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))

        let result = data.addWatches(market: market, pairs: [pair("ETH"), pair("SOL"), pair("BTC"), pair("XRP")])

        XCTAssertFalse(result.locked)
        XCTAssertEqual(result.added, 2)
        XCTAssertEqual(result.skipped, 2)
        XCTAssertEqual(result.refused, 0)
        let bases = Dictionary(uniqueKeysWithValues: data.snapshot.watches.map { ($0.id, $0.baseAsset) })
        XCTAssertEqual(result.addedIds.compactMap { bases[$0] }, ["SOL", "XRP"], "addedIds genau die neuen")
        XCTAssertEqual(ids, Set([Self.btc.id, Self.eth.id] + result.addedIds))

        let undone = data.deleteForUndo(ids: Set(result.addedIds))
        XCTAssertEqual(Set(undone.map(\.watch.id)), Set(result.addedIds))
        XCTAssertEqual(ids, [Self.btc.id, Self.eth.id], "vorhandene Paare bleiben")
    }

    func testFullWatchlistRefusesTheRest() async throws {
        let filler = (0..<(WatchlistLimits.maxPairs - 1)).map { i in
            Watch(id: 970_000 + Int64(i), marketKey: "Binance", marketName: "Binance", baseAsset: "F\(i)",
                  quoteAsset: "USDT", pairId: "F\(i)USDT", sortOrder: i)
        }
        try await XCTUnwrap(watchlist).load(filler)
        let data = AppData.shared
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))

        let result = data.addWatches(market: market, pairs: [pair("TSTA"), pair("TSTB"), pair("TSTC")])

        XCTAssertFalse(result.locked)
        XCTAssertEqual(result.added, 1)
        XCTAssertEqual(result.refused, 2)
        XCTAssertEqual(result.addedIds.count, 1)
        XCTAssertEqual(data.snapshot.watches.count, WatchlistLimits.maxPairs)

        _ = data.deleteForUndo(ids: Set(result.addedIds))
        XCTAssertEqual(data.snapshot.watches.count, WatchlistLimits.maxPairs - 1)
        XCTAssertFalse(data.snapshot.watches.contains { $0.baseAsset == "TSTA" })
    }

    func testLockedWatchlistAddsNothing() async throws {
        try await XCTUnwrap(watchlist).load([Self.btc])
        let data = AppData.shared
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))
        data.watchlistLocked = true
        let result = data.addWatches(market: market, pairs: [pair("SOL")])
        XCTAssertTrue(result.locked)
        XCTAssertEqual(result.addedIds, [])
        XCTAssertEqual(ids, [Self.btc.id])
    }
}

/// Eigene Merkliste für Verhaltenstests auf `AppData.shared` der Test-App: merkt Stand, Sperre und Einstellungen
/// vorher, legt Testpaare an und stellt danach alles wieder her (wie `WatchlistLockBehaviorTests`). Ohne Schlüsselbund
/// (unsignierter CI-Simulator) mit Test-Schlüssel (`HostStorageKey`).
@MainActor
final class AppDataTestWatchlist {
    private let original: SharedStorage.Snapshot
    private let originalSettings: AppSettings
    private let wasLocked: Bool
    private let hintAt: Int64?

    init() async throws {
        try await HostStorageKey.prepareAppStorage()
        let data = AppData.shared
        wasLocked = data.watchlistLocked
        hintAt = data.watchlistLockedHintAt
        originalSettings = data.settings
        original = try XCTUnwrap(SharedStorage.readSnapshot().snapshot, "Merkliste der Test-App nicht lesbar")
    }

    /// Merkliste der Test-App auf `watches` setzen (ohne Alarme) und ungesperrt in `AppData` lesen. Ändert die App
    /// zwischendurch etwas, gilt der Speicher: dann nach dem Abwarten der Schreibvorgänge noch einmal.
    func load(_ watches: [Watch]) async throws {
        let data = AppData.shared
        data.watchlistLocked = false
        await SharedStorage.snapshotWritesSettled()
        await data.waitForLiveApply()
        let wanted = Set(watches.map(\.id))
        for _ in 0..<3 {
            let stored = SharedStorage.updateSnapshot { s in
                s.watches = watches
                s.alarms = []
                s.nextWatchId = max(s.nextWatchId, (watches.map(\.id).max() ?? 0) + 1)
            }
            XCTAssertNotNil(stored, "Merkliste der Test-App nicht beschreibbar")
            await data.reloadFromDisk()
            if Set(data.snapshot.watches.map(\.id)) == wanted { return }
            await SharedStorage.snapshotWritesSettled()
        }
        XCTFail("Testdaten nicht übernommen")
        throw HostStorageKey.NotReady()
    }

    /// Stand der Test-App vor dem Test zurück.
    func restore() async {
        let data = AppData.shared
        data.watchlistLocked = false
        await SharedStorage.snapshotWritesSettled()
        let original = original
        _ = SharedStorage.updateSnapshot { s in
            s.watches = original.watches
            s.alarms = original.alarms
            s.nextWatchId = original.nextWatchId
            s.nextAlarmId = original.nextAlarmId
        }
        await data.reloadFromDisk()
        data.watchlistLocked = wasLocked
        data.watchlistLockedHintAt = hintAt
        if data.settings != originalSettings { data.settings = originalSettings }
    }
}
