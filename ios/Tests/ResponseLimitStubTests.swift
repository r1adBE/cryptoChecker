import XCTest
@testable import CryptoChecker

/// Höchstgrösse der Antworten ausserhalb von `MarketHTTP.call`: beobachtete Adressen (2 MB wie Android `callMarket`)
/// und Logos (`CoinLogoStore.maxImageBytes`) — gegen die URLProtocol-Attrappe aus `MarketHTTPStubTests`.
final class ResponseLimitStubTests: XCTestCase {
    private var session: URLSession!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MarketHTTPStubProtocol.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 10
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    override func tearDown() {
        session.invalidateAndCancel()
        session = nil
        super.tearDown()
    }

    /// Eigene Adresse je Test; ohne Content-Length (die Grenze greift beim Lesen).
    private func respond(_ status: Int = 200, body: Data) -> String {
        let url = "https://stub.invalid/\(UUID().uuidString)"
        MarketHTTPStubReplies.shared.set(url, .respond(status: status, headers: [:], body: body))
        return url
    }

    func testWatchedAddressRejectsThreeMegabytes() async {
        let url = respond(body: Data(repeating: 0x20, count: 3 * 1024 * 1024))
        do {
            _ = try await WatchedAddressSource.load(url, post: nil, session: session)
            XCTFail("3 MB müssen scheitern")
        } catch {
            XCTAssertTrue(error is ResponseTooLargeError, "war \(error)")
        }
    }

    func testWatchedAddressReadsSmallAnswer() async throws {
        let text = try await WatchedAddressSource.load(respond(body: Data(#"{"balance":"1"}"#.utf8)), post: nil, session: session)
        XCTAssertEqual(text, #"{"balance":"1"}"#)
    }

    func testLogoAboveLimitIsRejected() async throws {
        let url = try XCTUnwrap(URL(string: respond(body: Data(count: CoinLogoStore.maxImageBytes + 1))))
        do {
            _ = try await CoinLogoStore.loadImage(url, session: session)
            XCTFail("Bild über der Grenze muss scheitern")
        } catch {
            XCTAssertTrue(error is ResponseTooLargeError, "war \(error)")
        }
    }

    /// Bild an der Grenze geht; Fehlercode → kein Körper (nichts gelesen), Status bleibt für die Bremse.
    func testLogoAtLimitAndErrorStatus() async throws {
        let ok = try XCTUnwrap(URL(string: respond(body: Data(count: CoinLogoStore.maxImageBytes))))
        let loaded = try await CoinLogoStore.loadImage(ok, session: session)
        XCTAssertEqual(loaded.body?.count, CoinLogoStore.maxImageBytes)
        let busy = try XCTUnwrap(URL(string: respond(429, body: Data(count: 3 * 1024 * 1024))))
        let paused = try await CoinLogoStore.loadImage(busy, session: session)
        XCTAssertEqual(paused.response.statusCode, 429)
        XCTAssertNil(paused.body)
    }

    /// JSON-Tiefe: JSONSerialization bricht selbst ab (Fehler statt Stacküberlauf) — darum auf iOS keine eigene
    /// Prüfung wie Android `HttpBodyLimit.tooDeep`.
    func testJSONSerializationRejectsDeepNesting() {
        let deep = Data(String(repeating: "[", count: 100_000).utf8)
        XCTAssertThrowsError(try JSONSerialization.jsonObject(with: deep))
        XCTAssertThrowsError(try JSONDecoder().decode([String].self, from: deep))
    }
}
