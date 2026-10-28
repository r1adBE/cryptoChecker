import XCTest
@testable import CryptoChecker

/// Grössengrenzen von Manifest und Signatur über den echten Ladeweg (`ExchangeHTTP.loadMirrorMeta` → URLSession mit der
/// URLProtocol-Attrappe aus `ExchangeHTTPStubTests`): zu gross (angekündigt oder beim Lesen) zählt wie fehlend, genau an
/// der Grenze wird gelesen. Wie Android `MirrorLoadLimitTest.kt`.
final class MirrorLoadLimitTests: XCTestCase {
    private typealias S = TestSupport
    private var session: URLSession!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ExchangeHTTPStubProtocol.self]
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

    private struct Signed {
        let manifest: String
        let signature: String
        let key: String
        let now: Int64
        let okText: String
    }

    private func signed() throws -> Signed {
        let data = try S.fixture("mirror_signature")
        let valid = try XCTUnwrap(S.list(data["verify"]).first { $0["name"] as? String == "valid" })
        let ok = try XCTUnwrap(S.list(data["files"]).first { $0["name"] as? String == "listed and same bytes" })
        return try Signed(manifest: XCTUnwrap(valid["manifest"] as? String),
                          signature: XCTUnwrap(valid["signature"] as? String),
                          key: XCTUnwrap(data["publicKey"] as? String),
                          now: XCTUnwrap((data["now"] as? NSNumber)?.int64Value),
                          okText: XCTUnwrap(ok["text"] as? String))
    }

    /// Antworten der Attrappe für Manifest und Signatur (eigene Adressen je Test); `announced` setzt Content-Length.
    private struct Stubs {
        let manifestURL = "https://stub.invalid/\(UUID().uuidString)/manifest.json"
        let signatureURL = "https://stub.invalid/\(UUID().uuidString)/manifest.json.sig"

        func set(manifest: String, signature: String, announced: Bool = false) {
            ExchangeHTTPStubReplies.shared.set(manifestURL, Self.reply(manifest, announced: announced))
            ExchangeHTTPStubReplies.shared.set(signatureURL, Self.reply(signature, announced: announced))
        }

        private static func reply(_ text: String, announced: Bool) -> ExchangeHTTPStubReplies.Reply {
            let body = Data(text.utf8)
            return .respond(status: 200, headers: announced ? ["Content-Length": String(body.count)] : [:], body: body)
        }
    }

    /// Echter Ladeweg; nur die GitHub-Adressen zeigen auf die Attrappe.
    private func verify(_ c: Signed, _ stubs: Stubs) async -> (ok: Bool, status: MirrorManifest.Status?) {
        let stubSession: URLSession = self.session
        let manifestURL = stubs.manifestURL
        let signatureURL = stubs.signatureURL
        let now = c.now
        let trust = MirrorTrust(publicKey: c.key, now: { now })
        let ok = await trust.verified(file: "fng.txt", text: c.okText) { address, limit in
            let target = address == MirrorManifest.url ? manifestURL
                : (address == MirrorManifest.signatureURL ? signatureURL : address)
            return await ExchangeHTTP.loadMirrorMeta(target, limit: limit, session: stubSession)
        }
        return (ok, await trust.lastStatus)
    }

    func testValidManifestOverHTTPIsAccepted() async throws {
        let c = try signed()
        let stubs = Stubs()
        stubs.set(manifest: c.manifest, signature: c.signature)
        let result = await verify(c, stubs)
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.status, .ok)
    }

    func testAnnouncedManifestAboveTheLimitCountsAsMissing() async throws {
        let c = try signed()
        let stubs = Stubs()
        stubs.set(manifest: c.manifest + String(repeating: " ", count: MirrorManifest.maxBytes), signature: c.signature,
                  announced: true)
        let result = await verify(c, stubs)
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.status, .missing)
        // Ohne Manifest keine Signatur-Anfrage
        XCTAssertEqual(ExchangeHTTPStubReplies.shared.hitCount(stubs.signatureURL), 0)
    }

    func testStreamedManifestAboveTheLimitCountsAsMissing() async throws {
        let c = try signed()
        let stubs = Stubs()
        stubs.set(manifest: c.manifest + String(repeating: " ", count: MirrorManifest.maxBytes), signature: c.signature)
        let result = await verify(c, stubs)
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.status, .missing)
    }

    /// Gelesen, aber durch die Auffüllung passt die Signatur nicht mehr: invalid statt missing.
    func testManifestExactlyAtTheLimitIsRead() async throws {
        let c = try signed()
        let stubs = Stubs()
        let padding = String(repeating: " ", count: MirrorManifest.maxBytes - c.manifest.utf8.count)
        stubs.set(manifest: c.manifest + padding, signature: c.signature)
        let result = await verify(c, stubs)
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.status, .invalid)
    }

    func testSignatureAboveTheLimitCountsAsMissing() async throws {
        let c = try signed()
        let stubs = Stubs()
        let padding = String(repeating: "\n", count: MirrorManifest.signatureMaxBytes - c.signature.utf8.count + 1)
        stubs.set(manifest: c.manifest, signature: c.signature + padding)
        let result = await verify(c, stubs)
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.status, .missing)
    }

    /// Zeilenumbrüche am Ende fallen beim Prüfen weg.
    func testSignatureExactlyAtTheLimitIsAccepted() async throws {
        let c = try signed()
        let stubs = Stubs()
        let padding = String(repeating: "\n", count: MirrorManifest.signatureMaxBytes - c.signature.utf8.count)
        stubs.set(manifest: c.manifest, signature: c.signature + padding)
        let result = await verify(c, stubs)
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.status, .ok)
    }
}
