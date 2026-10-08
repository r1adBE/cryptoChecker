import XCTest
@testable import CryptoChecker

/// Live-Kurse (WebSocket): gemeinsame Testfälle `Parity/live_feed.json` (wie `LiveFeedTest.kt`)
/// plus Puffer und Aufteilung auf mehrere Verbindungen.
final class LiveFeedTests: XCTestCase {

    private typealias S = TestSupport

    private func pair(_ spec: [String: Any]) -> LivePair {
        LivePair(watchId: Int64(S.number(spec["watchId"]) ?? 0),
                 marketKey: spec["marketKey"] as? String ?? "",
                 marketName: spec["marketName"] as? String ?? "",
                 pairId: spec["pairId"] as? String,
                 base: spec["base"] as? String ?? "",
                 quote: spec["quote"] as? String ?? "",
                 contract: spec["contract"] as? String ?? "NONE",
                 notTraded: spec["notTraded"] as? Bool ?? false)
    }

    private func exchange(_ value: Any?) throws -> LiveExchange {
        try XCTUnwrap(LiveExchange(rawValue: value as? String ?? ""))
    }

    func testParserMatchesFixtures() throws {
        let data = try S.fixture("live_feed")
        var count = 0
        for c in S.list(data["parse"]) {
            let name = c["name"] as? String ?? ""
            let ex = try exchange(c["exchange"])
            let ticks = LiveParser.parse(ex, c["message"] as? String ?? "")
            let expected = S.list(c["ticks"])
            XCTAssertEqual(ticks.count, expected.count, "\(name): count")
            for (i, want) in expected.enumerated() where i < ticks.count {
                XCTAssertEqual(ticks[i].symbol, want["symbol"] as? String, "\(name): symbol")
                S.assertNumber(ticks[i].price, S.number(want["price"]), accuracy: 1e-9, "\(name): price")
                S.assertNumber(ticks[i].change24h, S.number(want["change24h"]), accuracy: 1e-9, "\(name): change")
                XCTAssertEqual(ticks[i].time, S.number(want["time"]).map { Int64($0) }, "\(name): time")
            }
            count += 1
        }
        XCTAssertGreaterThanOrEqual(count, 10)
    }

    func testSymbolsMatchFixtures() throws {
        let data = try S.fixture("live_feed")
        for c in S.list(data["symbols"]) {
            let ex = try exchange(c["exchange"])
            let spec = c["pair"] as? [String: Any] ?? [:]
            XCTAssertEqual(ex.symbol(for: pair(spec)), c["symbol"] as? String, "symbol for \(spec)")
            XCTAssertEqual(LiveExchange.from(marketKey: spec["marketKey"] as? String ?? ""), ex)
        }
    }

    func testSubscribeMessagesMatchFixtures() throws {
        let data = try S.fixture("live_feed")
        for c in S.list(data["subscribe"]) {
            let ex = try exchange(c["exchange"])
            let symbols = c["symbols"] as? [String] ?? []
            let messages = ex.subscribeMessages(symbols)
            XCTAssertEqual(messages, c["messages"] as? [String], ex.rawValue)
            for message in messages {
                let json = try? JSONSerialization.jsonObject(with: Data(message.utf8))
                XCTAssertNotNil(json, message)
            }
        }
    }

    func testPlanMatchesFixtures() throws {
        let data = try S.fixture("live_feed")
        for c in S.list(data["plan"]) {
            let name = c["name"] as? String ?? ""
            let paused = Set(c["paused"] as? [String] ?? [])
            let plan = LivePlanner.plan(S.list(c["pairs"]).map(pair), pausedMarketKeys: paused)
            let expected = S.list(c["connections"])
            XCTAssertEqual(plan.connections.count, expected.count, "\(name): connections")
            for (i, e) in expected.enumerated() where i < plan.connections.count {
                let ex = try exchange(e["exchange"])
                XCTAssertEqual(plan.connections[i].exchange, ex, name)
                XCTAssertEqual(plan.connections[i].symbols, e["symbols"] as? [String], name)
            }
            var ids: [LiveExchange: [String: [Int64]]] = [:]
            for (key, value) in c["watchIds"] as? [String: Any] ?? [:] {
                var bySymbol: [String: [Int64]] = [:]
                for (symbol, list) in value as? [String: Any] ?? [:] {
                    bySymbol[symbol] = (list as? [Any] ?? []).compactMap { S.number($0).map { Int64($0) } }
                }
                let ex = try exchange(key)
                ids[ex] = bySymbol
            }
            XCTAssertEqual(plan.watchIds, ids, "\(name): ids")
        }
    }

    func testBackoffMatchesFixtures() throws {
        let data = try S.fixture("live_feed")
        for c in S.list(data["backoff"]) {
            let millis = LiveBackoff.delayMillis(attempt: Int(S.number(c["attempt"]) ?? 0),
                                                 rateLimited: c["rateLimited"] as? Bool ?? false)
            XCTAssertEqual(millis, Int64(S.number(c["millis"]) ?? -1), "\(c)")
        }
    }

    func testRulesMatchFixtures() throws {
        let data = try S.fixture("live_feed")
        for c in S.list(data["chooseChange"]) {
            let result = LiveRules.chooseChange(
                rollingBasis: c["rolling"] as? Bool ?? false, stampCurrent: c["stampCurrent"] as? Bool ?? false,
                exchangeRolling: c["exchangeRolling"] as? Bool ?? false,
                live: S.number(c["live"]), existing: S.number(c["existing"]))
            S.assertNumber(result, S.number(c["expected"]), accuracy: 1e-12, "chooseChange \(c)")
        }
        for c in S.list(data["skipRest"]) {
            let lastTickAt = S.number(c["lastTickAt"]).map { Int64($0) }
            let result = LiveRules.skipRest(
                lastTickAt: lastTickAt, now: Int64(S.number(c["now"]) ?? 0),
                rollingBasis: c["rolling"] as? Bool ?? false, stampUnchanged: c["stampUnchanged"] as? Bool ?? false,
                exchangeRolling: c["exchangeRolling"] as? Bool ?? false)
            XCTAssertEqual(result, c["expected"] as? Bool, "skipRest \(c)")
        }
    }

    func testManySymbolsAreSplitAcrossConnections() {
        let pairs = (0..<450).map { i in
            LivePair(watchId: Int64(i), marketKey: "Binance", marketName: "Binance",
                     pairId: String(format: "C%03dUSDT", i), base: "C\(i)", quote: "USDT", contract: "NONE")
        }
        let plan = LivePlanner.plan(pairs)
        XCTAssertEqual(plan.connections.map(\.symbols.count), [200, 200, 50])
        XCTAssertEqual(plan.connections.map(\.key), ["BINANCE#0", "BINANCE#1", "BINANCE#2"])
        XCTAssertEqual(plan.watchIds[.BINANCE]?.count, 450)

        let many = (0..<900).map { i in
            LivePair(watchId: Int64(i), marketKey: "Bybit", marketName: "Bybit",
                     pairId: String(format: "S%03d", i), base: "S\(i)", quote: "USDT", contract: "NONE")
        }
        let capped = LivePlanner.plan(many)
        XCTAssertEqual(capped.connections.count, LivePlanner.maxConnectionsPerExchange)
        XCTAssertEqual(capped.watchIds[.BYBIT]?.count, 600)
        XCTAssertEqual(LiveExchange.BYBIT.subscribeMessages(capped.connections[0].symbols).count, 20)
    }

    func testBufferMergesDeltasAndTracksUnsaved() {
        var buffer = LiveBuffer()
        XCTAssertFalse(buffer.offer([1], LiveTick(symbol: "BTCUSDT", price: nil, change24h: nil, time: 10), now: 100))
        XCTAssertNil(buffer.takeForUi())

        XCTAssertTrue(buffer.offer([1, 2], LiveTick(symbol: "BTCUSDT", price: 100, change24h: 1.5, time: 10), now: 100))
        XCTAssertEqual(buffer.takeForUi()?[1], LiveQuote(price: 100, change24h: 1.5, time: 10))
        XCTAssertNil(buffer.takeForUi())

        XCTAssertTrue(buffer.offer([1], LiveTick(symbol: "BTCUSDT", price: 101, change24h: nil, time: nil), now: 200))
        XCTAssertEqual(buffer.takeForUi()?[1], LiveQuote(price: 101, change24h: 1.5, time: 200))

        let batch = buffer.takeForDb()
        XCTAssertEqual(Set(batch.keys), [1, 2])
        XCTAssertEqual(batch[1]?.price, 101)
        XCTAssertTrue(buffer.takeForDb().isEmpty)

        // Gleicher Kurs, nur neue Zeit: Anzeige ja, Datenbank nein
        XCTAssertTrue(buffer.offer([1], LiveTick(symbol: "BTCUSDT", price: 101, change24h: 1.5, time: 300), now: 300))
        XCTAssertTrue(buffer.takeForDb().isEmpty)

        buffer.offer([2], LiveTick(symbol: "BTCUSDT", price: 99, change24h: nil, time: 400), now: 400)
        let failed = buffer.takeForDb()
        XCTAssertEqual(Set(failed.keys), [2])
        buffer.markUnsaved(failed.keys)
        XCTAssertEqual(Set(buffer.takeForDb().keys), [2])

        buffer.offer([2], LiveTick(symbol: "BTCUSDT", price: 98, change24h: nil, time: 500), now: 500)
        buffer.retain([1])
        XCTAssertTrue(buffer.takeForDb().isEmpty)
        XCTAssertEqual(buffer.takeForUi().map { Set($0.keys) }, [1])
        XCTAssertEqual(buffer.count, 1)

        buffer.clear()
        XCTAssertEqual(buffer.takeForUi()?.isEmpty, true)
    }

    func testOverlayKeepsChangeOutsideRollingBasis() {
        var watch = Watch(id: 7, marketKey: "Kraken", marketName: "Kraken", baseAsset: "BTC", quoteAsset: "USD")
        watch.change24h = 2.0
        watch.lastPrice = 50
        let quote = LiveQuote(price: 51, change24h: 9.9, time: 1_000)
        // Kraken: kein gleitender Wert im Strom → bisherige Veränderung bleibt
        let kraken = watch.withLive(quote, rollingBasis: true)
        XCTAssertEqual(kraken.lastPrice, 51)
        XCTAssertEqual(kraken.change24h, 2.0)
        watch.marketKey = "Binance"
        XCTAssertEqual(watch.withLive(quote, rollingBasis: true).change24h, 9.9)
        XCTAssertEqual(watch.withLive(quote, rollingBasis: false).change24h, 2.0)
        XCTAssertEqual(watch.withLive(nil, rollingBasis: true), watch)
    }
}
