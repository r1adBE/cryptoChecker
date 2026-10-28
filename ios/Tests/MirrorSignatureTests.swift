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
        // Reserve-Schlüssel eingebaut, verschieden vom aktiven und vom Test-Schlüssel
        XCTAssertEqual(MirrorManifest.reservePublicKey, data["productionReservePublicKey"] as? String)
        XCTAssertEqual(MirrorManifest.publicKeys, [MirrorManifest.publicKey, MirrorManifest.reservePublicKey])
        XCTAssertNotEqual(MirrorManifest.reservePublicKey, MirrorManifest.publicKey)
        XCTAssertNotEqual(data["publicKey"] as? String, MirrorManifest.reservePublicKey)
    }

    func testVerify() throws {
        let data = try S.fixture("mirror_signature")
        let testKey = try XCTUnwrap(data["publicKey"] as? String)
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let cases = S.list(data["verify"])
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let keys = c["publicKeys"] as? [String] ?? [c["publicKey"] as? String ?? testKey]
            let v = MirrorManifest.verify(c["manifest"] as? String, signature: c["signature"] as? String, now: now,
                                          publicKeys: keys)
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
        let trust = MirrorTrust(publicKey: MirrorManifest.publicKey, now: { EpochTime.nowMillis })
        let asked = AskedURLs()
        let load: MirrorTrust.Loader = { url, _ in
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
        let trust = MirrorTrust(publicKey: MirrorManifest.publicKey, now: { EpochTime.nowMillis })
        let manifest = "{\"created\":\(EpochTime.nowMillis),\"files\":{\"fng.txt\":\"\(MirrorManifest.sha256Hex("x"))\"},\"v\":1}"
        let load: MirrorTrust.Loader = { url, _ in
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

    /// Gültiges Manifest des Test-Schlüssels, Text, der dazu passt, und Text mit anderem Hash (Fixture).
    private func signedCase() throws -> (manifest: String, signature: String, key: String, now: Int64,
                                         okText: String, changedText: String) {
        let data = try S.fixture("mirror_signature")
        let valid = try XCTUnwrap(S.list(data["verify"]).first { $0["name"] as? String == "valid" })
        let files = S.list(data["files"])
        let ok = try XCTUnwrap(files.first { $0["name"] as? String == "listed and same bytes" })
        let changed = try XCTUnwrap(files.first { $0["name"] as? String == "listed but changed" })
        return (try XCTUnwrap(valid["manifest"] as? String), try XCTUnwrap(valid["signature"] as? String),
                try XCTUnwrap(data["publicKey"] as? String), try XCTUnwrap((data["now"] as? NSNumber)?.int64Value),
                try XCTUnwrap(ok["text"] as? String), try XCTUnwrap(changed["text"] as? String))
    }

    func testTrustAcceptsValidManifestWithTestKey() async throws {
        let c = try signedCase()
        let now = c.now
        let trust = MirrorTrust(publicKey: c.key, now: { now })
        let asked = AskedURLs()
        let load: MirrorTrust.Loader = { url, limit in
            await asked.add("\(url) \(limit)")
            switch url {
            case MirrorManifest.url: return c.manifest
            case MirrorManifest.signatureURL: return c.signature
            default: return nil
            }
        }
        let ok = await trust.verified(file: "fng.txt", text: c.okText, load: load)
        XCTAssertTrue(ok)
        let status = await trust.lastStatus
        XCTAssertEqual(status, .ok)
        // Grenzen wie Android: Manifest 64 KB, Signatur 1 KB
        let urls = await asked.urls
        XCTAssertEqual(urls, ["\(MirrorManifest.url) 65536", "\(MirrorManifest.signatureURL) 1024"])
        // Der eingebaute Schlüssel lehnt dasselbe Manifest ab
        let production = MirrorTrust(publicKey: MirrorManifest.publicKey, now: { now })
        let rejected = await production.verified(file: "fng.txt", text: c.okText, load: load)
        XCTAssertFalse(rejected)
    }

    func testTrustReloadsOnceOnHashMismatchAfterOneMinute() async throws {
        let c = try signedCase()
        let clock = MirrorTestClock(c.now)
        let trust = MirrorTrust(publicKey: c.key, now: { clock.value })
        let asked = AskedURLs()
        let load: MirrorTrust.Loader = { url, _ in
            await asked.add(url)
            return url == MirrorManifest.url ? c.manifest : (url == MirrorManifest.signatureURL ? c.signature : nil)
        }
        let first = await trust.verified(file: "fng.txt", text: c.okText, load: load)
        XCTAssertTrue(first)
        var count = await asked.urls.count
        XCTAssertEqual(count, 2)
        // Abweichender Hash innerhalb einer Minute: kein Neuladen
        clock.value = c.now + 59_000
        let early = await trust.verified(file: "fng.txt", text: c.changedText, load: load)
        XCTAssertFalse(early)
        count = await asked.urls.count
        XCTAssertEqual(count, 2)
        // Nach 60 s: genau ein Neuladen (Manifest und Signatur), danach wieder gemerkt
        clock.value = c.now + 60_000
        let late = await trust.verified(file: "fng.txt", text: c.changedText, load: load)
        XCTAssertFalse(late)
        let again = await trust.verified(file: "fng.txt", text: c.changedText, load: load)
        XCTAssertFalse(again)
        let urls = await asked.urls
        XCTAssertEqual(urls, [MirrorManifest.url, MirrorManifest.signatureURL, MirrorManifest.url, MirrorManifest.signatureURL])
        let stillOk = await trust.verified(file: "fng.txt", text: c.okText, load: load)
        XCTAssertTrue(stillOk)
    }

    func testTrustTreatsOversizedManifestOrSignatureAsMissing() async throws {
        let c = try signedCase()
        let now = c.now
        // Lader hält die Grenze ein wie `ExchangeHTTP.readLimited` (zu gross → nil)
        func loader(manifest: String, signature: String) -> MirrorTrust.Loader {
            { url, limit in
                let text = url == MirrorManifest.url ? manifest : (url == MirrorManifest.signatureURL ? signature : "")
                return text.utf8.count > limit ? nil : text
            }
        }
        let big = MirrorTrust(publicKey: c.key, now: { now })
        let padded = c.manifest + String(repeating: " ", count: MirrorManifest.maxBytes)
        let bigOk = await big.verified(file: "fng.txt", text: c.okText, load: loader(manifest: padded, signature: c.signature))
        XCTAssertFalse(bigOk)
        let bigStatus = await big.lastStatus
        XCTAssertEqual(bigStatus, .missing)
        let bigSig = MirrorTrust(publicKey: c.key, now: { now })
        let longSignature = c.signature + String(repeating: "\n", count: MirrorManifest.signatureMaxBytes)
        let sigOk = await bigSig.verified(file: "fng.txt", text: c.okText,
                                          load: loader(manifest: c.manifest, signature: longSignature))
        XCTAssertFalse(sigOk)
        let sigStatus = await bigSig.lastStatus
        XCTAssertEqual(sigStatus, .missing)
    }

    /// Abgebrochener erster Aufrufer: Der gemeinsame Abruf läuft weiter, der wartende zweite bekommt das gültige
    /// Manifest, kein gemerktes «fehlt» (wie Android `MirrorTrustTest`). Der Lader liefert nil, falls sein Task
    /// abgebrochen ist (wie eine abgebrochene URLSession-Anfrage).
    func testCancelledCallerDoesNotRememberMissingForWaitingCaller() async throws {
        let c = try signedCase()
        let now = c.now
        // Jede Prüfung liest zuerst die Uhr, direkt vor dem Warten auf den laufenden Abruf (ohne Unterbruch im Actor)
        let clockCalls = MirrorCallSignal()
        let trust = MirrorTrust(publicKey: c.key, now: {
            clockCalls.tick()
            return now
        })
        let gate = MirrorLoadGate()
        let load: MirrorTrust.Loader = { url, _ in
            guard url == MirrorManifest.url else { return url == MirrorManifest.signatureURL ? c.signature : nil }
            await gate.enter()
            return Task.isCancelled ? nil : c.manifest
        }
        let first = Task { await trust.verified(file: "fng.txt", text: c.okText, load: load) }
        await gate.waitUntilEntered()
        let second = Task { await trust.verified(file: "fng.txt", text: c.okText, load: load) }
        // Signal statt fester Wartezeit: der zweite hat die Uhr gelesen und wartet damit auf den laufenden Abruf
        await clockCalls.wait(for: 2)
        first.cancel()
        await gate.release()
        let secondOk = await second.value
        XCTAssertTrue(secondOk)
        _ = await first.value
        let status = await trust.lastStatus
        XCTAssertEqual(status, .ok)
    }
}

/// Hält den ersten Manifest-Abruf an, bis `release()` kommt; weitere laufen durch.
private actor MirrorLoadGate {
    private var calls = 0
    private var released = false
    private var held: CheckedContinuation<Void, Never>?
    private var enteredWaiters: [CheckedContinuation<Void, Never>] = []

    func enter() async {
        calls += 1
        guard calls == 1 else { return }
        enteredWaiters.forEach { $0.resume() }
        enteredWaiters.removeAll()
        if released { return }
        await withCheckedContinuation { held = $0 }
    }

    func waitUntilEntered() async {
        if calls > 0 { return }
        await withCheckedContinuation { enteredWaiters.append($0) }
    }

    func release() {
        released = true
        held?.resume()
        held = nil
    }
}

/// Zählt Aufrufe (aus einer @Sendable-Closure); `wait(for:)` kehrt zurück, sobald so viele erfolgt sind.
private final class MirrorCallSignal: @unchecked Sendable { // @unchecked: Zustand nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var count = 0
    private var waiters: [(target: Int, continuation: CheckedContinuation<Void, Never>)] = []

    func tick() {
        lock.lock()
        count += 1
        let current = count
        let ready = waiters.filter { $0.target <= current }
        waiters.removeAll { $0.target <= current }
        lock.unlock()
        ready.forEach { $0.continuation.resume() }
    }

    func wait(for target: Int) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if count >= target {
                lock.unlock()
                continuation.resume()
                return
            }
            waiters.append((target, continuation))
            lock.unlock()
        }
    }
}

/// Verstellbare Uhr für `MirrorTrust` (die Closure muss @Sendable sein).
private final class MirrorTestClock: @unchecked Sendable { // @unchecked: `current` nur unter `lock` (NSLock)
    private let lock = NSLock()
    private var current: Int64

    init(_ start: Int64) {
        current = start
    }

    var value: Int64 {
        get { lock.withLock { current } }
        set { lock.withLock { current = newValue } }
    }
}

/// Gefragte Adressen (für die Loader-Closure, die @Sendable sein muss).
private actor AskedURLs {
    private(set) var urls: [String] = []

    func add(_ url: String) {
        urls.append(url)
    }
}
