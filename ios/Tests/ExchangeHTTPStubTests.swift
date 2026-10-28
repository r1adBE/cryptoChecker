import XCTest
@testable import CryptoChecker

/// `ExchangeHTTP.call` gegen eine URLProtocol-Attrappe statt gegen das Netz: 429 mit «Retry-After» (Sekunden und
/// HTTP-Datum) → `ExchangeFailure.rateLimited` und Pause der Börse, Bremse im Text einer 200-Antwort, angekündigte zu
/// grosse Antwort, hängende Antwort → Zeitüberschreitung. Spiegel: ExchangeHttpTest.kt (MockWebServer).
final class ExchangeHTTPStubTests: XCTestCase {
    private var session: URLSession!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ExchangeHTTPStubProtocol.self]
        configuration.timeoutIntervalForRequest = 0.5
        configuration.timeoutIntervalForResource = 1
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: configuration)
    }

    override func tearDown() {
        session.invalidateAndCancel()
        session = nil
        super.tearDown()
    }

    /// Eigene Adresse je Test (keine geteilte Anfrage über `InFlightRequests`).
    private func stub(_ reply: ExchangeHTTPStubReplies.Reply) -> String {
        let url = "https://stub.invalid/\(UUID().uuidString)"
        ExchangeHTTPStubReplies.shared.set(url, reply)
        return url
    }

    private func respond(_ status: Int, headers: [String: String] = [:], body: String = "{}") -> String {
        stub(.respond(status: status, headers: headers, body: Data(body.utf8)))
    }

    /// Fehler von `ExchangeHTTP.call`; scheitert der Test, wenn die Anfrage gelingt.
    private func failure(of url: String, file: StaticString = #filePath, line: UInt = #line) async -> Error? {
        do {
            _ = try await ExchangeHTTP.call(url, session: session)
            XCTFail("Fehler erwartet", file: file, line: line)
            return nil
        } catch {
            return error
        }
    }

    func testRateLimitWithRetryAfterSecondsBecomesRateLimitedAndPauses() async throws {
        let error = await failure(of: respond(429, headers: ["Retry-After": "120"]))
        let http = try XCTUnwrap(error as? HttpExchangeError)
        XCTAssertEqual(http.httpCode, 429)
        XCTAssertEqual(http.retryAfterSeconds, 120)
        let exchangeFailure = ExchangeFailure.of(http)
        XCTAssertEqual(exchangeFailure, .rateLimited(retryAfterSeconds: 120, code: 429))

        // Pause der Börse wie nach einer Aktualisierung: «Retry-After» länger als die erste eigene Stufe (30 s)
        let now: Int64 = 1_700_000_000_000
        let outcome = ExchangeBackoff.outcome(failures: [exchangeFailure.refreshFailure].compactMap { $0 }, updated: 0)
        XCTAssertEqual(outcome, .rateLimited)
        let state = try XCTUnwrap(ExchangeBackoff.next(nil, outcome: outcome, now: now,
                                                       retryAfterMillis: ExchangeBackoff.retryAfterMillis([http.localizedDescription])))
        XCTAssertEqual(state.pausedUntil, now + 120_000)
        XCTAssertTrue(ExchangeBackoff.isPaused(state, now: now + 119_000))
        XCTAssertFalse(ExchangeBackoff.isPaused(state, now: now + 121_000))
    }

    func testRetryAfterAsHttpDateIsRead() async throws {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        let header = formatter.string(from: Date().addingTimeInterval(300))

        let error = await failure(of: respond(429, headers: ["Retry-After": header]))
        let http = try XCTUnwrap(error as? HttpExchangeError)
        let seconds = try XCTUnwrap(http.retryAfterSeconds)
        XCTAssertTrue((290...300).contains(seconds), "etwa 300 s, war \(seconds)")
    }

    func testThrottleTextWithStatus200CountsAsRateLimit() async throws {
        // Kraken meldet die Bremse mit HTTP 200 und einem Fehlertext
        let error = await failure(of: respond(200, body: #"{"error":["EGeneral:Too many requests"]}"#))
        let http = try XCTUnwrap(error as? HttpExchangeError)
        XCTAssertEqual(http.httpCode, 429)
        XCTAssertEqual(ExchangeFailure.of(http), .rateLimited(retryAfterSeconds: nil, code: 429))
    }

    func testServerErrorKeepsItsStatus() async {
        let error = await failure(of: respond(503, body: "busy"))
        XCTAssertEqual(error.map { ExchangeFailure.of($0) }, .httpStatus(503))
    }

    func testAnnouncedBodyAboveTheLimitIsRejectedWithoutReading() async {
        // Eigene Session: Grenze der Listen (16 MB); angekündigt ist ein Byte mehr
        let url = respond(200, headers: ["Content-Length": String(ExchangeHTTP.maxBulkBodyBytes + 1)], body: "[]")
        let error = await failure(of: url)
        XCTAssertTrue(error is ResponseTooLargeError, "war \(String(describing: error))")
    }

    func testSmallBodyIsReturned() async throws {
        let text = try await ExchangeHTTP.call(respond(200, body: #"{"last":"101.5"}"#), session: session)
        XCTAssertEqual(text, #"{"last":"101.5"}"#)
    }

    func testHangingResponseEndsAsTimeout() async {
        let started = Date()
        let error = await failure(of: stub(.hang))
        XCTAssertEqual(error.map { ExchangeFailure.of($0) }, .timeout, "war \(String(describing: error))")
        // Nicht unbegrenzt: die Frist der Session (bzw. spätestens die Attrappe) beendet die Anfrage
        XCTAssertLessThan(Date().timeIntervalSince(started), 10)
    }
}

/// Antworten der Attrappe je Adresse.
final class ExchangeHTTPStubReplies: @unchecked Sendable { // @unchecked: `replies` nur unter `lock` (NSLock)
    enum Reply: Sendable {
        case respond(status: Int, headers: [String: String], body: Data)
        /// Antwortet nie; nach `hangLimitSeconds` meldet die Attrappe selbst eine Zeitüberschreitung (falls die
        /// Session ihre Frist für eigene Protokolle nicht durchsetzt — der Test hängt so nie).
        case hang
        /// Antwortet nach `seconds` mit 200 und `body` (überlappende Anfragen, `InFlightRequests`).
        case delayed(seconds: TimeInterval, body: Data)
    }

    static let shared = ExchangeHTTPStubReplies()
    static let hangLimitSeconds: TimeInterval = 3

    private let lock = NSLock()
    private var replies: [String: Reply] = [:]
    private var hits: [String: Int] = [:]

    func set(_ url: String, _ reply: Reply) {
        lock.lock(); defer { lock.unlock() }
        replies[url] = reply
    }

    func reply(for url: String) -> Reply? {
        lock.lock(); defer { lock.unlock() }
        hits[url, default: 0] += 1
        return replies[url]
    }

    /// So viele Anfragen kamen bei der Attrappe an.
    func hitCount(_ url: String) -> Int {
        lock.lock(); defer { lock.unlock() }
        return hits[url, default: 0]
    }
}

/// URLProtocol der Test-Session: beantwortet Anfragen aus `ExchangeHTTPStubReplies` (unbekannte Adresse: 404).
final class ExchangeHTTPStubProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        switch ExchangeHTTPStubReplies.shared.reply(for: url.absoluteString) {
        case .hang:
            // Auf der Run-Loop des Lade-Threads (wie die übrigen Rückmeldungen), abgebrochen in `stopLoading`
            perform(#selector(failWithTimeout), with: nil, afterDelay: ExchangeHTTPStubReplies.hangLimitSeconds)
        case let .respond(status, headers, body):
            send(url, status: status, headers: headers, body: body)
        case let .delayed(seconds, body):
            delayedBody = body
            perform(#selector(sendDelayed), with: nil, afterDelay: seconds)
        case nil:
            send(url, status: 404, headers: [:], body: Data(#"{"message":"not found"}"#.utf8))
        }
    }

    override func stopLoading() {
        NSObject.cancelPreviousPerformRequests(withTarget: self)
    }

    private func send(_ url: URL, status: Int, headers: [String: String], body: Data) {
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)
        else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    private var delayedBody = Data()

    @objc private func sendDelayed() {
        guard let url = request.url else { return }
        send(url, status: 200, headers: [:], body: delayedBody)
    }

    @objc private func failWithTimeout() {
        client?.urlProtocol(self, didFailWithError: URLError(.timedOut))
    }
}
