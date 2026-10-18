import XCTest
@testable import CryptoChecker

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
        let post = PostRequestInfo(body: #"{"type":"metaAndAssetCtxs"}"#, headers: ["Content-Type": "application/json"])
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
        async let first = MarketHTTP.call(url, post: PostRequestInfo(body: #"{"type":"meta"}"#), session: session)
        async let second = MarketHTTP.call(url, post: PostRequestInfo(body: #"{"type":"allMids"}"#), session: session)
        _ = try await [first, second]
        XCTAssertEqual(MarketHTTPStubReplies.shared.hitCount(url), 2)
    }
}
