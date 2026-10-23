import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `mirror_signature.json` (Android: `MirrorSignatureParityTest.kt`), signiert mit einem
/// Test-Schlüssel; dazu `MirrorTrust` ohne Netz (wie Android `MirrorTrustTest.kt`).
final class MirrorSignatureTests: XCTestCase {

    private typealias S = TestSupport

    func testConstants() throws {
        let data = try S.fixture("mirror_signature")
        XCTAssertEqual(MirrorManifest.publicKey, data["productionPublicKey"] as? String)
        XCTAssertEqual(MirrorManifest.maxAgeMillis, (data["maxAgeMillis"] as? NSNumber)?.int64Value)
        XCTAssertEqual(MirrorManifest.file, data["manifestFile"] as? String)
        XCTAssertEqual(MirrorManifest.signatureFile, data["signatureFile"] as? String)
        XCTAssertEqual(MirrorManifest.url, DataMirror.base + MirrorManifest.file)
        XCTAssertNotEqual(data["publicKey"] as? String, MirrorManifest.publicKey)
    }

    func testVerify() throws {
        let data = try S.fixture("mirror_signature")
        let testKey = try XCTUnwrap(data["publicKey"] as? String)
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let cases = S.list(data["verify"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let v = MirrorManifest.verify(c["manifest"] as? String, signature: c["signature"] as? String, now: now,
                                          publicKey: c["publicKey"] as? String ?? testKey)
            XCTAssertEqual(v.status.rawValue, c["expected"] as? String, name)
            if v.status == .ok {
                let manifest = try XCTUnwrap(v.manifest, name)
                if let created = (c["created"] as? NSNumber)?.int64Value { XCTAssertEqual(manifest.created, created, name) }
                if let count = (c["fileCount"] as? NSNumber)?.intValue { XCTAssertEqual(manifest.files.count, count, name) }
            } else {
                XCTAssertNil(v.manifest, name)
            }
        }
    }

    /// Der echte Schlüssel lässt sich lesen und lehnt Test-Signaturen ab (kein Absturz).
    func testProductionKeyRejectsTestSignature() throws {
        let data = try S.fixture("mirror_signature")
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let valid = try XCTUnwrap(S.list(data["verify"]).first { $0["expected"] as? String == "ok" })
        let v = MirrorManifest.verify(valid["manifest"] as? String, signature: valid["signature"] as? String, now: now)
        XCTAssertEqual(v.status, .invalid)
    }

    func testFiles() throws {
        let data = try S.fixture("mirror_signature")
        let files = try XCTUnwrap(data["manifestFiles"] as? [String: String])
        let manifest = MirrorManifest.Manifest(created: 1, files: files)
        for c in S.list(data["files"]) {
            let status = MirrorManifest.fileStatus(manifest, file: c["file"] as? String ?? "", text: c["text"] as? String ?? "")
            XCTAssertEqual(status.rawValue, c["expected"] as? String, c["name"] as? String ?? "?")
        }
    }

    func testSignedFiles() {
        XCTAssertTrue(MirrorManifest.isSignedFile(DataMirror.altSeason.url))
        XCTAssertTrue(MirrorManifest.isSignedFile(DataMirror.base + "coin_meta.json"))
        XCTAssertFalse(MirrorManifest.isSignedFile(MirrorManifest.url))
        XCTAssertFalse(MirrorManifest.isSignedFile(MirrorManifest.signatureURL))
        XCTAssertFalse(MirrorManifest.isSignedFile("https://api.alternative.me/fng/?limit=31"))
    }

    func testTrustRejectsMissingManifestAndRemembers() async {
        let trust = MirrorTrust()
        let asked = AskedURLs()
        let load: MirrorTrust.Loader = { url in
            await asked.add(url)
            return nil
        }
        let first = await trust.verified(file: "fng.txt", text: "CCDM1 1\n{}", load: load)
        let second = await trust.verified(file: "onchain.txt", text: "CCDM1 1\n{}", load: load)
        XCTAssertFalse(first)
        XCTAssertFalse(second)
        // Ohne Manifest wird die Signatur gar nicht erst geholt, und erst nach der Wartezeit wieder versucht
        let urls = await asked.urls
        XCTAssertEqual(urls, [MirrorManifest.url])
        let status = await trust.lastStatus
        XCTAssertEqual(status, .missing)
    }

    func testTrustRejectsInvalidSignature() async {
        let trust = MirrorTrust()
        let manifest = "{\"created\":\(TimeUtils.nowMillis),\"files\":{\"fng.txt\":\"\(MirrorManifest.sha256Hex("x"))\"},\"v\":1}"
        let load: MirrorTrust.Loader = { url in
            switch url {
            case MirrorManifest.url: return manifest
            case MirrorManifest.signatureURL: return "MEUCIQDf"
            default: return nil
            }
        }
        let ok = await trust.verified(file: "fng.txt", text: "x", load: load)
        XCTAssertFalse(ok)
        let status = await trust.lastStatus
        XCTAssertEqual(status, .invalid)
    }
}

/// Gefragte Adressen (für die Loader-Closure, die @Sendable sein muss).
private actor AskedURLs {
    private(set) var urls: [String] = []

    func add(_ url: String) {
        urls.append(url)
    }
}
