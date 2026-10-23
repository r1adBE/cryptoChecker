import XCTest
@testable import CryptoChecker

/// Eigene URLSessions ohne Cookies und ohne Antwort-Cache (`PrivateSessionConfig`): Die
/// Datenschutzerklärung verspricht «keine Kennungen». Ein Server setzt per Set-Cookie eine Kennung — die nächste
/// Anfrage trägt keinen Cookie-Kopf, und der geteilte Cookie-Speicher bleibt leer.
final class PrivateSessionConfigTests: XCTestCase {
    private static let host = "cookie-\(UUID().uuidString.lowercased()).invalid"

    func testFactoryConfigHasNoCookiesAndNoCache() {
        for config in [PrivateSessionConfig.make(timeout: 8, resource: 10), PrivateSessionConfig.make(timeout: 120)] {
            XCTAssertEqual(config.httpCookieAcceptPolicy, .never)
            XCTAssertFalse(config.httpShouldSetCookies)
            XCTAssertNil(config.httpCookieStorage)
            XCTAssertNil(config.urlCache)
        }
        let c = PrivateSessionConfig.make(timeout: 8, resource: 10)
        XCTAssertEqual(c.timeoutIntervalForRequest, 8)
        XCTAssertEqual(c.timeoutIntervalForResource, 10)
        // Ohne `resource`: Vorgabe der Konfiguration bleibt
        XCTAssertEqual(PrivateSessionConfig.make(timeout: 120).timeoutIntervalForResource,
                       URLSessionConfiguration.default.timeoutIntervalForResource)
    }

    func testSetCookieIsNeitherStoredNorSentAgain() async throws {
        let config = PrivateSessionConfig.make(timeout: 5, resource: 5)
        config.protocolClasses = [CookieRecordingProtocol.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        let first = try XCTUnwrap(URL(string: "https://\(Self.host)/set"))
        let second = try XCTUnwrap(URL(string: "https://\(Self.host)/next"))

        _ = try await session.data(from: first)
        _ = try await session.data(from: second)

        XCTAssertEqual(CookieRecordingProtocol.recorded.count(second), 1)
        XCTAssertNil(CookieRecordingProtocol.recorded.cookieHeader(second), "kein Cookie-Kopf in der zweiten Anfrage")
        XCTAssertTrue((HTTPCookieStorage.shared.cookies(for: first) ?? []).isEmpty, "nichts im geteilten Speicher")
    }
}

/// Merkt sich den Cookie-Kopf jeder Anfrage; antwortet immer 200 mit Set-Cookie.
final class CookieRecordingProtocol: URLProtocol {
    final class Recorded: @unchecked Sendable { // @unchecked: Zustand nur unter `lock` (NSLock)
        private let lock = NSLock()
        private var cookies: [URL: String] = [:]
        private var counts: [URL: Int] = [:]

        func record(_ url: URL, cookie: String?) {
            lock.lock(); defer { lock.unlock() }
            counts[url, default: 0] += 1
            if let cookie { cookies[url] = cookie }
        }

        func cookieHeader(_ url: URL) -> String? {
            lock.lock(); defer { lock.unlock() }
            return cookies[url]
        }

        func count(_ url: URL) -> Int {
            lock.lock(); defer { lock.unlock() }
            return counts[url] ?? 0
        }
    }

    static let recorded = Recorded()

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                             headerFields: ["Set-Cookie": "tracker=abc123; Path=/; Max-Age=3600"]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        Self.recorded.record(url, cookie: request.value(forHTTPHeaderField: "Cookie"))
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
