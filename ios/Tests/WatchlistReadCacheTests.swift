import XCTest
@testable import CryptoChecker

/// Abgeleitete Listen der Merkliste je Stand zwischengespeichert (`WatchlistReadCache`, AppData+Reading.swift): Auf
/// `AppData.shared` der Test-App (eigene Testpaare, danach wiederhergestellt) liefern sie nach jeder Änderung — Paare,
/// Sortierung, Gruppe, Favorit, Alarm, Kurs — dieselben Listen wie die direkte Rechnung.
@MainActor
final class WatchlistReadCacheTests: XCTestCase {
    private var watchlist: AppDataTestWatchlist?

    override func setUp() async throws {
        watchlist = try await AppDataTestWatchlist()
        // Rückfrage F-08 aus (eigenes Tor, «gespeichert»), wie AddWatchesBehaviorTests
        WatchlistNotSavedGate.appDataOverride = (gate: WatchlistNotSavedGate(), notSaved: false, canAsk: false)
    }

    override func tearDown() async throws {
        WatchlistNotSavedGate.appDataOverride = nil
        await watchlist?.restore()
        watchlist = nil
    }

    private func pair(_ id: Int64, _ base: String, order: Int, group: String? = nil) -> Watch {
        var w = Watch(id: id, marketKey: "Binance", marketName: "Binance", baseAsset: base, quoteAsset: "USDT",
                      pairId: "\(base)USDT", sortOrder: order)
        w.groupName = group
        return w
    }

    /// Alle zwischengespeicherten Listen gegen die direkte Rechnung (wie vor dem Zwischenspeicher).
    private func assertSameAsDirect(_ step: String, file: StaticString = #filePath, line: UInt = #line) {
        let data = AppData.shared
        let s = data.snapshot
        let sorted = s.watches.sorted {
            if $0.favorite != $1.favorite { return $0.favorite }
            if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
            return $0.id < $1.id
        }
        // Zweimal lesen: der zweite Zugriff kommt aus dem Zwischenspeicher
        for _ in 0..<2 {
            XCTAssertEqual(data.watches, sorted, "\(step) watches", file: file, line: line)
            XCTAssertEqual(data.alarms, s.alarms.sorted { $0.id < $1.id }, "\(step) alarms", file: file, line: line)
            XCTAssertEqual(data.watchGroups, AppData.groups(of: s.watches), "\(step) groups", file: file, line: line)
            let visible: [Watch]
            if let group = data.selectedWatchlistGroup {
                visible = sorted.filter { WatchFilter.matches(group, $0) }
            } else {
                visible = sorted
            }
            XCTAssertEqual(data.visibleWatches, visible, "\(step) visible", file: file, line: line)
            XCTAssertEqual(data.activeAlarmCounts,
                           Dictionary(grouping: s.alarms.filter(\.enabled), by: \.watchId).mapValues(\.count),
                           "\(step) counts", file: file, line: line)
            XCTAssertEqual(data.alarmFiredAt,
                           Dictionary(grouping: s.alarms.filter { $0.lastTriggeredAt > 0 }, by: \.watchId)
                               .mapValues { list in list.map(\.lastTriggeredAt).max() ?? 0 },
                           "\(step) fired", file: file, line: line)
            XCTAssertEqual(data.lastAlarmTriggeredAt, s.alarms.map(\.lastTriggeredAt).max() ?? 0,
                           "\(step) last", file: file, line: line)
            for w in s.watches {
                XCTAssertEqual(data.alarms(for: w.id), s.alarms.sorted { $0.id < $1.id }.filter { $0.watchId == w.id },
                               "\(step) alarms(for:)", file: file, line: line)
            }
            let byId = Dictionary(s.watches.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            XCTAssertEqual(data.alarmsWithWatch,
                           s.alarms.sorted { $0.id < $1.id }.compactMap { a in
                               byId[a.watchId].map { AlarmWithWatch(alarm: a, watch: $0) }
                           }, "\(step) alarmsWithWatch", file: file, line: line)
            var kinds: [Int64: AssetKind] = [:]
            for w in s.watches {
                kinds[w.id] = w.assetKind(tradFiPairs: CoinLogoStore.tradFiPairs, alphaSymbols: CoinLogoStore.alphaSymbols,
                                          alphaNames: CoinLogoStore.alphaNames,
                                          pairName: TokenIdentityStore.shared.pairName(marketKey: w.marketKey,
                                                                                        base: w.baseAsset))
            }
            XCTAssertEqual(data.watchKinds, kinds, "\(step) kinds", file: file, line: line)
        }
    }

    func testCachedListsFollowEveryChange() async throws {
        let ids: [Int64] = [990_301, 990_302, 990_303, 990_304]
        try await XCTUnwrap(watchlist).load([
            pair(ids[0], "BTC", order: 2, group: "Gruppe A"), pair(ids[1], "ETH", order: 0),
            pair(ids[2], "SOL", order: 1, group: "Gruppe B"), pair(ids[3], "XRP", order: 3, group: "Gruppe A"),
        ])
        let data = AppData.shared
        assertSameAsDirect("geladen")
        // Favorit
        data.mutate { s in
            if let i = s.watches.firstIndex(where: { $0.id == ids[3] }) { s.watches[i].favorite = true }
        }
        assertSameAsDirect("Favorit")
        // Sortierung
        data.mutate { s in
            for i in s.watches.indices { s.watches[i].sortOrder = 10 - s.watches[i].sortOrder }
        }
        assertSameAsDirect("Sortierung")
        // Gruppe gewählt, dann umbenannt (Auswahl folgt) und ein Paar umgruppiert
        data.selectWatchlistGroup("Gruppe A")
        assertSameAsDirect("Gruppe gewählt")
        data.applyGroupEdit(oldName: "Gruppe A", newName: "Gruppe C", memberIds: [ids[0], ids[1]])
        assertSameAsDirect("Gruppe bearbeitet")
        data.selectWatchlistGroup(WatchFilter.favorites)
        assertSameAsDirect("Favoriten")
        data.selectWatchlistGroup(nil)
        // Alarme: neu, ausgelöst, abgeschaltet
        data.mutate { s in
            s.alarms.append(Alarm(id: s.nextAlarmId, watchId: ids[1], condition: .PRICE_ABOVE, threshold: 1e15))
            s.alarms.append(Alarm(id: s.nextAlarmId + 1, watchId: ids[2], condition: .PRICE_BELOW, threshold: 1e-15))
            s.nextAlarmId += 2
        }
        assertSameAsDirect("Alarme neu")
        data.mutate { s in
            if let i = s.alarms.firstIndex(where: { $0.watchId == ids[2] }) {
                s.alarms[i].lastTriggeredAt = 1_700_000_000_000
                s.alarms[i].enabled = false
            }
        }
        assertSameAsDirect("Alarm ausgelöst")
        // Reine Kurse (still gespeichert): Paare tragen den neuen Kurs
        data.mutate({ s in
            for i in s.watches.indices { s.watches[i].lastPrice = Double(i + 1) * 100 }
        }, reloadWidgets: false, quiet: true)
        assertSameAsDirect("Kurse")
        XCTAssertEqual(data.watches.compactMap(\.lastPrice).count, ids.count)
        // Paar entfernt
        data.mutate { s in s.watches.removeAll { $0.id == ids[0] } }
        assertSameAsDirect("Paar entfernt")
        data.discardPendingPriceSave()
    }

    func testAlignForgetsOtherState() {
        var cache = WatchlistReadCache()
        cache.align(1)
        cache.watches = []
        cache.lastTriggered = 5
        cache.align(1)
        XCTAssertNotNil(cache.watches, "gleicher Stand: bleibt")
        cache.align(2)
        XCTAssertNil(cache.watches)
        XCTAssertNil(cache.lastTriggered)
        XCTAssertEqual(cache.generation, 2)
    }
}
