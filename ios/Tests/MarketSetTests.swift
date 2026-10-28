import XCTest
@testable import CryptoChecker

// Kleine Testklassen dieses Themas in einer Datei (R, Build 147); jede Klasse unverändert.

// ---- aus MarketHTTPLimitTests

/// Höchstgrösse von Antworten (wie Android `HttpBodyLimitTest.kt`).
final class MarketHTTPLimitTests: XCTestCase {

    func testSingleSessionUsesSmallLimit() {
        XCTAssertNoThrow(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes, session: MarketHTTP.session))
        XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes + 1, session: MarketHTTP.session)) {
            XCTAssertEqual($0.localizedDescription, ResponseTooLargeError.message)
        }
    }

    func testListSessionsUseLargeLimit() {
        for session in [MarketHTTP.bulkSession, MarketHTTP.tickerBulkSession] {
            XCTAssertNoThrow(try MarketHTTP.checkBodySize(MarketHTTP.maxBodyBytes + 1, session: session))
            XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxBulkBodyBytes + 1, session: session))
        }
        XCTAssertEqual(MarketHTTP.maxBodyBytes, 2 * 1024 * 1024)
        XCTAssertEqual(MarketHTTP.maxBulkBodyBytes, 16 * 1024 * 1024)
    }

    /// Widget-Erweiterung: Listen höchstens 3 MB (vorher 8 MB), Einzelabfragen weiter 2 MB. Jede
    /// aufgenommene Sammelabfrage ist höchstens 2 MB (die Aufnahme verwirft Grösseres) — passt also weiter.
    func testExtensionUsesLowerListLimit() {
        XCTAssertEqual(MarketHTTP.maxExtensionBodyBytes, 3 * 1024 * 1024)
        XCTAssertNoThrow(try MarketHTTP.checkBodySize(2 * 1024 * 1024, session: MarketHTTP.tickerBulkSession, inExtension: true))
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.bulkSession, inExtension: true), MarketHTTP.maxExtensionBodyBytes)
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.session, inExtension: true), MarketHTTP.maxBodyBytes)
        XCTAssertEqual(MarketHTTP.bodyLimit(session: MarketHTTP.bulkSession, inExtension: false), MarketHTTP.maxBulkBodyBytes)
        XCTAssertThrowsError(try MarketHTTP.checkBodySize(MarketHTTP.maxExtensionBodyBytes + 1,
                                                          session: MarketHTTP.tickerBulkSession, inExtension: true))
        // Tests laufen in der App, nicht in der Erweiterung
        XCTAssertFalse(MarketHTTP.isAppExtension)
    }

    /// Der feste Text bleibt beim Speichern erhalten (`StoredError`).
    func testMessageIsKeptWhenStored() {
        XCTAssertEqual(StoredError.sanitize(ResponseTooLargeError().localizedDescription), ResponseTooLargeError.message)
    }

    // MARK: Blockweises Lesen (`collectLimited`)

    private func stream(_ count: Int) -> AsyncStream<UInt8> {
        AsyncStream { continuation in
            for i in 0..<count { continuation.yield(UInt8(truncatingIfNeeded: i)) }
            continuation.finish()
        }
    }

    /// Genau an der Grenze geht es noch, auch über mehrere Blöcke und mit Rest; Inhalt bleibt in Reihenfolge.
    func testChunkedReadKeepsBytesUpToLimit() async throws {
        let data = try await MarketHTTP.collectLimited(stream(10), limit: 10, expected: -1, chunkSize: 4)
        XCTAssertEqual([UInt8](data), (0..<10).map { UInt8($0) })
        let empty = try await MarketHTTP.collectLimited(stream(0), limit: 10, expected: 0, chunkSize: 4)
        XCTAssertTrue(empty.isEmpty)
    }

    /// Ein Byte über der Grenze → Fehler — mitten in einem Block wie im letzten Rest.
    func testChunkedReadRejectsOneByteOverLimit() async {
        for (count, chunk) in [(11, 4), (11, 64), (9, 8)] {
            do {
                _ = try await MarketHTTP.collectLimited(stream(count), limit: count - 1, expected: -1, chunkSize: chunk)
                XCTFail("\(count) Bytes bei Grenze \(count - 1) müssen scheitern")
            } catch {
                XCTAssertTrue(error is ResponseTooLargeError)
            }
        }
    }

    /// Angekündigt zu gross: gar nicht erst lesen.
    func testChunkedReadRejectsAnnouncedLength() async {
        do {
            _ = try await MarketHTTP.collectLimited(stream(1), limit: 10, expected: 11)
            XCTFail("angekündigte Länge über der Grenze muss scheitern")
        } catch {
            XCTAssertTrue(error is ResponseTooLargeError)
        }
    }
}

// ---- aus MarketHTTPSharedPostTests

/// Gleiche gleichzeitige POST-Abfragen gehen nur einmal ins Netz (`InFlightRequests`), etwa Hyperliquid
/// «metaAndAssetCtxs» (~100 KB, für jedes Paar dieselbe Antwort). Andere Körper bleiben getrennt. Wie
/// MarketHttpSharedPostTest.kt.
final class MarketHTTPSharedPostTests: XCTestCase {
    private var session: URLSession!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MarketHTTPStubProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    override func tearDown() {
        session.invalidateAndCancel()
        session = nil
        super.tearDown()
    }

    private func delayedURL(_ body: String) -> String {
        let url = "https://stub.invalid/\(UUID().uuidString)"
        MarketHTTPStubReplies.shared.set(url, .delayed(seconds: 0.3, body: Data(body.utf8)))
        return url
    }

    func testIdenticalConcurrentPostsShareOneRequest() async throws {
        let url = delayedURL("[1]")
        let post = PostRequest(body: #"{"type":"metaAndAssetCtxs"}"#, headers: ["Content-Type": "application/json"])
        let session = self.session!
        async let first = MarketHTTP.call(url, post: post, session: session)
        async let second = MarketHTTP.call(url, post: post, session: session)
        let bodies = try await [first, second]
        XCTAssertEqual(bodies, ["[1]", "[1]"])
        XCTAssertEqual(MarketHTTPStubReplies.shared.hitCount(url), 1)
    }

    func testDifferentBodiesStaySeparate() async throws {
        let url = delayedURL("[2]")
        let session = self.session!
        async let first = MarketHTTP.call(url, post: PostRequest(body: #"{"type":"meta"}"#), session: session)
        async let second = MarketHTTP.call(url, post: PostRequest(body: #"{"type":"allMids"}"#), session: session)
        _ = try await [first, second]
        XCTAssertEqual(MarketHTTPStubReplies.shared.hitCount(url), 2)
    }
}

// ---- aus MarketMoversTests

/// «Gewinner und Verlierer 24 h» aus `market_movers.json` (Android: `MarketMoversParityTest.kt`).
final class MarketMoversTests: XCTestCase {

    private typealias S = TestSupport

    private func data() throws -> [String: Any] { try S.fixture("market_movers") }

    private func coins(_ raw: Any?) -> [MoverCoin] {
        S.list(raw).map {
            MoverCoin(symbol: $0["symbol"] as? String ?? "", name: $0["name"] as? String ?? "",
                      rank: ($0["rank"] as? NSNumber)?.intValue, change24h: S.number($0["change"]))
        }
    }

    private func expected(_ raw: Any?) -> [String] {
        S.list(raw).map { "\($0["symbol"] as? String ?? "")|\($0["name"] as? String ?? "")|\(S.number($0["change"]) ?? .nan)" }
    }

    private func actual(_ list: [MoverCoin]) -> [String] {
        list.map { "\($0.symbol)|\($0.name)|\($0.change24h ?? .nan)" }
    }

    func testConstants() throws {
        let d = try data()
        XCTAssertEqual((d["universe"] as? NSNumber)?.intValue, MarketMovers.universe)
        XCTAssertEqual((d["count"] as? NSNumber)?.intValue, MarketMovers.count)
        XCTAssertEqual((d["minCoins"] as? NSNumber)?.intValue, MarketMovers.minCoins)
    }

    func testCases() throws {
        for c in S.list(try data()["cases"]) {
            let note = c["note"] as? String ?? ""
            let result = MarketMovers.select(coins(c["coins"]))
            XCTAssertEqual(actual(result.gainers), expected(c["gainers"]), "gainers: \(note)")
            XCTAssertEqual(actual(result.losers), expected(c["losers"]), "losers: \(note)")
            // Nie derselbe Coin in beiden Listen, nie mehr als `count`
            XCTAssertTrue(result.gainers.allSatisfy { g in !result.losers.contains { $0.symbol == g.symbol } }, note)
            XCTAssertTrue(result.gainers.count <= MarketMovers.count && result.losers.count <= MarketMovers.count, note)
        }
    }

    func testEmptyInput() {
        XCTAssertTrue(MarketMovers.select([]).isEmpty)
    }

    /// Gemeinsamer Stand (App Group): hin und zurück gleich; ältere Stände ohne «movers» = nil.
    func testStoredRoundTrip() throws {
        let first = try XCTUnwrap(S.list(try data()["cases"]).first)
        let movers = MarketMovers.select(coins(first["coins"]))
        let market = PulseMarketData(btc: 1, eth: -2, sol: 3, volumeRatio: nil, fundingPercent: nil, time: 5,
                                     topChanges: [1, -1], movers: movers)
        let raw = try JSONEncoder().encode(market)
        XCTAssertEqual(try JSONDecoder().decode(PulseMarketData.self, from: raw), market)
        let old = PulseMarketData(btc: 1, eth: -2, sol: 3, volumeRatio: nil, fundingPercent: nil, time: 5)
        var dict = try XCTUnwrap(try JSONSerialization.jsonObject(with: try JSONEncoder().encode(old)) as? [String: Any])
        dict.removeValue(forKey: "movers")
        let decoded = try JSONDecoder().decode(PulseMarketData.self, from: try JSONSerialization.data(withJSONObject: dict))
        XCTAssertNil(decoded.movers)
    }
}

// ---- aus MarketTabCacheTests

/// Markt-Tab: Zwischenspeicher langsamer Daten, «Aktualisieren» mit Mindestabstand,
/// Reihenfolge der Abschnitte, Platz des Wirtschaftsdaten-Hinweises und Fear & Greed im
/// Widget — wie `CycleCachePolicyTest.kt`, `MarketRevealTest.kt`, `MacroCalendarTest.kt`
/// und `PulseWidgetMathTest.kt`.
final class MarketTabCacheTests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000
    private let minute = CycleCachePolicy.minute
    private let hour = CycleCachePolicy.hour

    func testSlowSourcesHaveLongerTtls() {
        XCTAssertEqual(CycleCachePolicy.altSeason, 3 * hour)
        XCTAssertEqual(CycleCachePolicy.fearGreed, hour)
        XCTAssertEqual(CycleCachePolicy.global, 30 * minute)
        XCTAssertEqual(CycleCachePolicy.history, 12 * hour)
        XCTAssertFalse(CycleCachePolicy.needsRefresh(savedAt: now - 2 * hour, now: now, ttl: CycleCachePolicy.altSeason, force: false))
        XCTAssertTrue(CycleCachePolicy.needsRefresh(savedAt: now - 3 * hour, now: now, ttl: CycleCachePolicy.altSeason, force: false))
    }

    func testManualReloadAtMostEveryFiveMinutes() {
        let floor = CycleCachePolicy.manualMinInterval
        XCTAssertEqual(floor, 5 * minute)
        let ttl = CycleCachePolicy.altSeason
        XCTAssertFalse(CycleCachePolicy.needsRefresh(savedAt: now - 4 * minute, now: now, ttl: ttl, force: true, minForce: floor))
        XCTAssertTrue(CycleCachePolicy.needsRefresh(savedAt: now - 5 * minute, now: now, ttl: ttl, force: true, minForce: floor))
        XCTAssertTrue(CycleCachePolicy.canManualRefresh(savedAt: nil, now: now, minInterval: floor))
        XCTAssertTrue(CycleCachePolicy.canManualRefresh(savedAt: now + minute, now: now, minInterval: floor))
        // Ohne Mindestabstand: erzwungen lädt immer
        XCTAssertTrue(CycleCachePolicy.needsRefresh(savedAt: now - 1000, now: now, ttl: ttl, force: true))
    }

    func testTabOrderNowContextData() {
        let order: [CycleRevealSlot] = [.pulse, .unusual, .headerContext, .fearGreed, .phase, .dominance, .halving,
                                        .headerData, .marketTotals, .gas, .coin]
        XCTAssertEqual(CycleRevealSlot.allCases, order)
    }

    func testMacroHintAtTopOnlyWithinTwoHours() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Zurich")!
        let cpiTime: Int64 = 1_791_980_000_000
        let events = [MacroEvent(type: .CPI, time: cpiTime)]
        func imminent(_ at: Int64) -> Bool {
            guard let hint = MacroCalendar.hint(events, now: at, calendar: calendar) else { return false }
            return MacroCalendar.isImminent(hint, now: at)
        }
        XCTAssertFalse(imminent(cpiTime - 3 * hour))
        XCTAssertTrue(imminent(cpiTime - 2 * hour))
        XCTAssertTrue(imminent(cpiTime))
        XCTAssertTrue(imminent(cpiTime + 2 * hour))
        XCTAssertFalse(imminent(cpiTime + 2 * hour + minute))
    }

    func testWidgetFearGreedOnlyWithin24Hours() {
        XCTAssertEqual(FearGreedShared.showable(.init(value: 72, time: now - hour), now: now), 72)
        XCTAssertEqual(FearGreedShared.showable(.init(value: 72, time: now - 24 * hour), now: now), 72)
        XCTAssertNil(FearGreedShared.showable(.init(value: 72, time: now - 24 * hour - 1), now: now))
        XCTAssertNil(FearGreedShared.showable(.init(value: 72, time: now + hour), now: now))
        XCTAssertNil(FearGreedShared.showable(.init(value: 140, time: now - hour), now: now))
        XCTAssertNil(FearGreedShared.showable(nil, now: now))
    }
}
