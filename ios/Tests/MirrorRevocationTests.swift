import XCTest
@testable import CryptoChecker

/// Sperrliste des Spiegels (F-29), gemeinsame Testfälle `mirror_signature.json` → `revocation` (Android:
/// `MirrorRevocationParityTest.kt`, `MirrorTrustRevocationTest.kt`), signiert mit zwei Test-Schlüsseln.
final class MirrorRevocationTests: XCTestCase {

    private typealias S = TestSupport

    private func keys(_ data: [String: Any]) throws -> [String] {
        let keys = try XCTUnwrap(data["revocationKeys"] as? [String: String])
        return [try XCTUnwrap(keys["a"]), try XCTUnwrap(keys["b"])]
    }

    func testKeyIds() throws {
        let data = try S.fixture("mirror_signature")
        for c in S.list(data["keyIds"]) {
            let key = try XCTUnwrap(c["publicKey"] as? String)
            XCTAssertEqual(MirrorManifest.keyId(key), c["id"] as? String)
        }
        XCTAssertNil(MirrorManifest.keyId("not base64!"))
        let active = MirrorManifest.keyId(MirrorManifest.publicKey)
        XCTAssertNotEqual(active, MirrorManifest.keyId(MirrorManifest.reservePublicKey))
    }

    func testRevocation() throws {
        let data = try S.fixture("mirror_signature")
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let testKeys = try keys(data)
        let cases = S.list(data["revocation"])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let before = Set(c["revokedBefore"] as? [String] ?? [])
            let v = MirrorManifest.verify(c["manifest"] as? String, signature: c["signature"] as? String, now: now,
                                          publicKeys: testKeys, revoked: before)
            XCTAssertEqual(v.status.rawValue, c["expected"] as? String, name)
            XCTAssertEqual(v.revoke, Set(c["revoke"] as? [String] ?? []), name)
        }
    }

    /// Gemerkte Sperren überdauern einen Neustart (neue Instanz auf derselben Datei) und werden nie entfernt.
    func testStoreKeepsRevocations() throws {
        let data = try S.fixture("mirror_signature")
        let testKeys = try keys(data)
        let a = try XCTUnwrap(MirrorManifest.keyId(testKeys[0]))
        let b = try XCTUnwrap(MirrorManifest.keyId(testKeys[1]))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent(MirrorRevocations.fileName)
        let first = MirrorRevocations(fileURL: file)
        XCTAssertTrue(first.current().isEmpty)
        first.add([a, "kein Schlüssel"])
        XCTAssertEqual(MirrorRevocations(fileURL: file).current(), [a])
        first.add([b])
        first.add([])
        XCTAssertEqual(MirrorRevocations(fileURL: file).current(), [a, b])
        XCTAssertEqual(MirrorRevocations.decode("\(a)\n\nXYZ\n\(a.uppercased())\n"), [a])
    }

    /// Ablauf: Reserve-Schlüssel sperrt den aktiven; danach nimmt auch eine neue Prüfung (gleiche Sperrliste) kein nur
    /// damit signiertes Manifest mehr an.
    func testTrustRemembersRevocation() async throws {
        let data = try S.fixture("mirror_signature")
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let testKeys = try keys(data)
        let cases = S.list(data["revocation"])
        let byActive = try XCTUnwrap(cases.first {
            $0["name"] as? String == "same manifest without stored revocation: ok"
        })
        let byReserve = try XCTUnwrap(cases.first {
            $0["name"] as? String == "reserve key revokes the active key: ok, active key revoked from now on"
        })
        let okCase = try XCTUnwrap(S.list(data["files"]).first { $0["name"] as? String == "listed and same bytes" })
        let okText = try XCTUnwrap(okCase["text"] as? String)
        let activeId = try XCTUnwrap(MirrorManifest.keyId(testKeys[0]))
        let activeManifest = try XCTUnwrap(byActive["manifest"] as? String)
        let activeSignature = try XCTUnwrap(byActive["signature"] as? String)
        let reserveManifest = try XCTUnwrap(byReserve["manifest"] as? String)
        let reserveSignature = try XCTUnwrap(byReserve["signature"] as? String)
        let store = MirrorRevocations(fileURL: nil)

        let before = MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
        let accepted = await before.verified(file: "fng.txt", text: okText,
                                             load: Self.loader(activeManifest, activeSignature))
        XCTAssertTrue(accepted)

        let revoking = MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
        let revoked = await revoking.verified(file: "fng.txt", text: okText,
                                              load: Self.loader(reserveManifest, reserveSignature))
        XCTAssertTrue(revoked)
        XCTAssertEqual(store.current(), [activeId])

        let after = MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
        let rejected = await after.verified(file: "fng.txt", text: okText,
                                            load: Self.loader(activeManifest, activeSignature))
        XCTAssertFalse(rejected)
        let status = await after.lastStatus
        XCTAssertEqual(status, .invalid)
    }

    /// Gegenseitige Sperre: A sperrt B, danach sperrt B (schon gesperrt) A → beide gesperrt, Spiegel aus.
    func testMutualRevocationTurnsMirrorOff() async throws {
        let data = try S.fixture("mirror_signature")
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let testKeys = try keys(data)
        let cases = S.list(data["revocation"])
        let aRevokesB = try XCTUnwrap(cases.first { $0["name"] as? String == "active key revokes the reserve key" })
        let bRevokesA = try XCTUnwrap(cases.first {
            $0["name"] as? String == "reserve key revokes the active key: ok, active key revoked from now on"
        })
        let okCase = try XCTUnwrap(S.list(data["files"]).first { $0["name"] as? String == "listed and same bytes" })
        let okText = try XCTUnwrap(okCase["text"] as? String)
        let aLoad = Self.loader(try XCTUnwrap(aRevokesB["manifest"] as? String),
                                try XCTUnwrap(aRevokesB["signature"] as? String))
        let bLoad = Self.loader(try XCTUnwrap(bRevokesA["manifest"] as? String),
                                try XCTUnwrap(bRevokesA["signature"] as? String))
        let idA = try XCTUnwrap(MirrorManifest.keyId(testKeys[0]))
        let idB = try XCTUnwrap(MirrorManifest.keyId(testKeys[1]))
        let store = MirrorRevocations(fileURL: nil)

        let first = await MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
            .verified(file: "fng.txt", text: okText, load: aLoad)
        XCTAssertTrue(first)
        XCTAssertEqual(store.current(), [idB])
        let second = await MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
            .verified(file: "fng.txt", text: okText, load: bLoad)
        XCTAssertFalse(second)
        XCTAssertEqual(store.current(), [idA, idB])
        let thirdA = await MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
            .verified(file: "fng.txt", text: okText, load: aLoad)
        let thirdB = await MirrorTrust(publicKeys: testKeys, now: { now }, revocations: store)
            .verified(file: "fng.txt", text: okText, load: bLoad)
        XCTAssertFalse(thirdA)
        XCTAssertFalse(thirdB)
    }

    private static func loader(_ manifest: String, _ signature: String) -> MirrorTrust.Loader {
        { url, _ in
            switch url {
            case MirrorManifest.url: return manifest
            case MirrorManifest.signatureURL: return signature
            default: return nil
            }
        }
    }
}
