import XCTest
@testable import CryptoChecker

/// Wirtschaftskalender nur mit SHA-256 im signierten Spiegel-Manifest (`macro_calendar_signature.json`, Android:
/// `MacroCalendarSignatureParityTest.kt`): unveränderte Datei → Termine; veränderte oder nicht gelistete Datei
/// → nil, dann nimmt `MacroCalendarSource` den Zwischenspeicher oder die mitgelieferte Datei.
final class MacroCalendarSignatureTests: XCTestCase {

    private typealias S = TestSupport

    private struct Fixture {
        let key: String
        let now: Int64
        let manifests: [String: [String: Any]]
        let cases: [[String: Any]]
    }

    private func fixture() throws -> Fixture {
        let data = try S.fixture("macro_calendar_signature")
        let key = try XCTUnwrap(data["publicKey"] as? String)
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        let manifests = try XCTUnwrap(data["manifests"] as? [String: [String: Any]])
        return Fixture(key: key, now: now, manifests: manifests, cases: S.list(data["cases"]))
    }

    private static func loader(manifest: String, signature: String) -> MirrorTrust.Loader {
        { url, _ in
            switch url {
            case MirrorManifest.url: return manifest
            case MirrorManifest.signatureURL: return signature
            default: return nil
            }
        }
    }

    private func loader(_ f: Fixture, _ which: String) throws -> MirrorTrust.Loader {
        let entry = try XCTUnwrap(f.manifests[which])
        let manifest = try XCTUnwrap(entry["manifest"] as? String)
        let signature = try XCTUnwrap(entry["signature"] as? String)
        return Self.loader(manifest: manifest, signature: signature)
    }

    private static func events(_ text: String, now: Int64, trust: MirrorTrust,
                               load: @escaping MirrorTrust.Loader) async -> [MacroEvent]? {
        await MacroCalendar.parseVerified(text, now: now) { file, body in
            await trust.verified(file: file, text: body, load: load)
        }
    }

    func testConstantsMatchFixture() throws {
        let data = try S.fixture("macro_calendar_signature")
        XCTAssertEqual(MacroCalendar.manifestFile, data["manifestFile"] as? String)
        XCTAssertEqual(MacroCalendar.url, data["url"] as? String)
    }

    func testOnlyListedUnchangedFileIsAccepted() async throws {
        let f = try fixture()
        XCTAssertGreaterThanOrEqual(f.cases.count, 4)
        for c in f.cases {
            let name = c["name"] as? String ?? "?"
            let text = try XCTUnwrap(c["text"] as? String, name)
            let which = try XCTUnwrap(c["manifest"] as? String, name)
            let load = try loader(f, which)
            let now = f.now
            let trust = MirrorTrust(publicKey: f.key, now: { now })
            let events = await Self.events(text, now: now, trust: trust, load: load)
            if c["accepted"] as? Bool == true {
                let list = try XCTUnwrap(events, name)
                XCTAssertEqual(list.count, (c["events"] as? NSNumber)?.intValue, name)
            } else {
                XCTAssertNil(events, name)
            }
        }
    }

    func testProductionKeyRejectsTestManifest() async throws {
        let f = try fixture()
        let accepted = try XCTUnwrap(f.cases.first { $0["accepted"] as? Bool == true })
        let text = try XCTUnwrap(accepted["text"] as? String)
        let load = try loader(f, "with")
        let now = f.now
        let trust = MirrorTrust(publicKeys: MirrorManifest.publicKeys, now: { now })
        let events = await Self.events(text, now: now, trust: trust, load: load)
        XCTAssertNil(events)
    }

    func testManipulatedFileIsRejected() async throws {
        let f = try fixture()
        let manipulated = try XCTUnwrap(f.cases.first { $0["name"] as? String == "event time changed" })
        let text = try XCTUnwrap(manipulated["text"] as? String)
        let load = try loader(f, "with")
        let now = f.now
        let trust = MirrorTrust(publicKey: f.key, now: { now })
        let events = await Self.events(text, now: now, trust: trust, load: load)
        XCTAssertNil(events)
        // Gleiches Manifest, unveränderte Datei: angenommen (nil oben kam also vom Hash, nicht vom Manifest)
        let original = try XCTUnwrap(f.cases.first { $0["accepted"] as? Bool == true }?["text"] as? String)
        let accepted = await Self.events(original, now: now, trust: trust, load: load)
        XCTAssertNotNil(accepted)
    }
}
