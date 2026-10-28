import XCTest
@testable import CryptoChecker

/// Speicher der Uhr-Kurse (`WatchQuoteFile`, über `NSFileCoordinator`) und die Grenze der Antwortgrösse beim Lesen
/// (`WatchBodyLimit`) — wie Android `WatchStore.mergeQuotes` und `WatchPriceSource` (4 MB).
final class WatchQuoteFileTests: XCTestCase {

    private var dir: URL = FileManager.default.temporaryDirectory

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("watch-quotes-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func q(_ coin: String, _ price: Double) -> WatchQuote {
        WatchQuote(symbol: coin, price: price, change24h: nil, source: WatchQuoteParser.binanceName)
    }

    private var file: WatchQuoteFile { WatchQuoteFile(url: dir.appendingPathComponent("watch_quotes.json")) }

    func testMissingFileReadsEmpty() {
        XCTAssertEqual(file.read(), [:])
    }

    func testStaleFetchDoesNotOverwriteNewerInTheFile() {
        let keep = ["BTC", "ETH"]
        file.merge(["BTC": q("BTC", 2)], startedAt: 2_000, keep: keep)
        // Früher gestarteter Abruf (Komplikation) endet später
        let merged = file.merge(["BTC": q("BTC", 1), "ETH": q("ETH", 3)], startedAt: 1_000, keep: keep)

        let expected = ["BTC": WatchCached(quote: q("BTC", 2), time: 2_000),
                        "ETH": WatchCached(quote: q("ETH", 3), time: 1_000)]
        XCTAssertEqual(merged, expected)
        XCTAssertEqual(file.read(), expected)
    }

    /// Gleichzeitige Abrufe je eines anderen Coins: keiner führt auf einem veralteten Stand zusammen, alle Coins bleiben.
    func testConcurrentMergesLoseNoCoin() {
        let coins = ["BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "TRX"]
        let file = self.file
        DispatchQueue.concurrentPerform(iterations: coins.count) { i in
            file.merge([coins[i]: WatchQuote(symbol: coins[i], price: Double(i + 1), change24h: nil, source: "Binance")],
                       startedAt: Int64(1_000 + i), keep: coins)
        }
        let stored = file.read() ?? [:]
        XCTAssertEqual(Set(stored.keys), Set(coins))
        for (i, coin) in coins.enumerated() {
            XCTAssertEqual(stored[coin]?.time, Int64(1_000 + i), coin)
        }
    }

    func testBodyUpToTheLimitIsRead() async {
        let data = await WatchBodyLimit.collect(bytes(count: 1_024), limit: 1_024)
        XCTAssertEqual(data?.count, 1_024)
    }

    func testBodyOverTheLimitIsDropped() async {
        let data = await WatchBodyLimit.collect(bytes(count: 1_025), limit: 1_024)
        XCTAssertNil(data)
    }

    /// Endloser Strom: das Lesen bricht nach der Grenze ab, statt alles zu laden.
    func testEndlessBodyStopsAfterTheLimit() async {
        let endless = AsyncStream<UInt8>(unfolding: { 0 })
        let data = await WatchBodyLimit.collect(endless, limit: 1_024)
        XCTAssertNil(data)
    }

    func testAnnouncedLengthOverTheLimitIsDroppedWithoutReading() async {
        let data = await WatchBodyLimit.collect(AsyncStream<UInt8>(unfolding: { 0 }), limit: 1_024, expected: 1_025)
        XCTAssertNil(data)
    }

    private func bytes(count: Int) -> AsyncStream<UInt8> {
        AsyncStream { continuation in
            for _ in 0..<count { continuation.yield(7) }
            continuation.finish()
        }
    }
}
