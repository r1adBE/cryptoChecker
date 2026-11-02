import os
import XCTest
@testable import CryptoChecker

/// Sammel-Abruf der Logos (`CoinLogoQueue`): gemeinsame Testfälle `coin_logos.json` › `queue` (Android:
/// `CoinLogoQueueParityTest.kt`) — für alle dieselbe Reihenfolge (nie nach der Merkliste), Grenzen insgesamt und je
/// Server — und der echte Ablauf (`run`):
/// nie mehr gleichzeitig als erlaubt, Start in der Reihenfolge der Liste, jeder Abruf genau einmal.
final class CoinLogoQueueTests: XCTestCase {

    private typealias S = TestSupport

    private func queue() throws -> [String: Any] {
        try XCTUnwrap(try S.fixture("coin_logos")["queue"] as? [String: Any])
    }

    func testLimitsAsFixture() throws {
        let q = try queue()
        XCTAssertEqual(S.number(q["maxParallel"]).map(Int.init), CoinLogoQueue.maxParallel)
        XCTAssertEqual(S.number(q["perHostParallel"]).map(Int.init), CoinLogoQueue.perHostParallel)
    }

    /// Für alle dieselbe Reihenfolge, nie nach Merkliste oder Portfolio: die bei Binance gelisteten Kürzel in der
    /// Reihenfolge der Symbolliste, dann die Rangliste (`CoinMeta.merge` mit `coin_meta.json`, wie Android).
    func testFixedOrderBinanceListThenRank() throws {
        let data = try S.fixture("coin_meta")
        let binance = try XCTUnwrap(CoinMeta.parseBinanceList(try CoinMetaTests.raw(data, "binanceList")))
        let gecko = try XCTUnwrap(CoinMeta.parseMarketsPage(try CoinMetaTests.raw(data, "geckoPage")))
        let first = CoinMeta.binanceFirst(binance).order
        let lists = CoinMeta.merge(geckoTop: gecko, binance: binance, alpha: nil)
        let keys = CoinLogoQueue.orderedKeys(lists.map, order: lists.order)
        XCTAssertFalse(first.isEmpty)
        XCTAssertEqual(Array(keys.prefix(first.count)), first)
        var geckoMap: [String: String] = [:]
        var geckoOrder: [String] = []
        CoinLogos.pick(gecko.map { (symbol: $0.symbol, image: $0.image) }, into: &geckoMap, order: &geckoOrder)
        let ranked = geckoOrder.filter { !first.contains($0) }
        XCTAssertFalse(ranked.isEmpty)
        XCTAssertEqual(Array(keys.dropFirst(first.count).prefix(ranked.count)), ranked)
        // Die Abrufe behalten diese Reihenfolge
        let jobs = CoinLogos.downloadJobs(CoinLogoQueue.ordered(lists.map, order: lists.order)) { _ in false }
        let files = jobs.map { $0.name }
        var seen = Set<String>()
        XCTAssertEqual(files, keys.compactMap { CoinLogos.fileName($0) }.filter { seen.insert($0).inserted })
    }

    /// Nur iOS (Android: `LinkedHashMap`): Reihenfolge aus `order`, übrige sortiert; Schlüssel der gespeicherten Datei.
    func testOrderedAndMapFile() throws {
        let q = try queue()
        let o = try XCTUnwrap(q["ordered"] as? [String: Any])
        let map = try XCTUnwrap(o["map"] as? [String: String])
        let order = try XCTUnwrap(o["order"] as? [String])
        let expected = try XCTUnwrap(o["expected"] as? [String])
        XCTAssertEqual(CoinLogoQueue.orderedKeys(map, order: order), expected)
        XCTAssertEqual(CoinLogoQueue.ordered(map, order: order).map { $0.0 }, expected)
        XCTAssertEqual(CoinLogoQueue.orderedKeys(map, order: []), map.keys.sorted())
        let f = try XCTUnwrap(q["mapFile"] as? [String: Any])
        XCTAssertEqual(CoinLogoQueue.mapFileOrder(f["text"] as? String), try XCTUnwrap(f["expected"] as? [String]))
        XCTAssertEqual(CoinLogoQueue.mapFileOrder(nil), [])
    }

    func testNextStartable() throws {
        for c in S.list(try queue()["nextStartable"]) {
            let hosts = c["hosts"] as? [String] ?? []
            let running = (c["running"] as? [String: Any] ?? [:]).compactMapValues { S.number($0).map(Int.init) }
            XCTAssertEqual(CoinLogoQueue.nextStartable(hosts: hosts, running: running),
                           S.number(c["expected"]).map(Int.init), "nextStartable \(c)")
        }
    }

    /// Zähler der laufenden Abrufe (gesamt, je Server) und ihr Höchststand.
    private struct Counts {
        var total = 0, maxTotal = 0
        var perHost: [String: Int] = [:], maxPerHost: [String: Int] = [:]
        var started: [Int] = []
    }

    func testRunNeverExceedsTheLimitsAndKeepsTheOrder() async {
        // 15 Bilder bei CoinGecko, 5 bei Binance, gemischt
        let jobs = (0..<20).map { (index: $0, host: $0 % 4 == 3 ? "b" : "g") }
        let counts = OSAllocatedUnfairLock(initialState: Counts())
        var done: [Int] = []
        await CoinLogoQueue.run(jobs, host: { $0.host }, work: { job in
            counts.withLock { c in
                c.started.append(job.index)
                c.total += 1
                c.maxTotal = max(c.maxTotal, c.total)
                c.perHost[job.host, default: 0] += 1
                c.maxPerHost[job.host] = max(c.maxPerHost[job.host] ?? 0, c.perHost[job.host] ?? 0)
            }
            try? await Task.sleep(nanoseconds: UInt64(5 + job.index % 3) * 1_000_000)
            counts.withLock { c in
                c.total -= 1
                c.perHost[job.host, default: 1] -= 1
            }
            return job.index
        }) { _, index in done.append(index) }
        let c = counts.withLock { $0 }
        XCTAssertLessThanOrEqual(c.maxTotal, CoinLogoQueue.maxParallel)
        XCTAssertLessThanOrEqual(c.maxPerHost["g"] ?? 0, CoinLogoQueue.perHostParallel)
        XCTAssertLessThanOrEqual(c.maxPerHost["b"] ?? 0, CoinLogoQueue.perHostParallel)
        XCTAssertEqual(done.sorted(), Array(0..<20))
        XCTAssertEqual(c.started.count, 20)
    }

    func testRunWithOneServerNeverExceedsItsLimit() async {
        let counts = OSAllocatedUnfairLock(initialState: Counts())
        await CoinLogoQueue.run(Array(0..<10), host: { _ in "g" }, work: { index in
            counts.withLock { c in
                c.total += 1
                c.maxTotal = max(c.maxTotal, c.total)
            }
            try? await Task.sleep(nanoseconds: 3_000_000)
            counts.withLock { $0.total -= 1 }
            return index
        }) { _, _ in }
        XCTAssertLessThanOrEqual(counts.withLock { $0.maxTotal }, CoinLogoQueue.perHostParallel)
        await CoinLogoQueue.run([Int](), host: { _ in "g" }, work: { $0 }) { _, _ in XCTFail("kein Abruf") }
    }
}
