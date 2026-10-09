import XCTest
@testable import CryptoChecker

/// Gemeinsame Testfälle `data_mirror.json` (Android: `DataMirrorParityTest.kt`).
final class DataMirrorTests: XCTestCase {

    private typealias S = TestSupport

    func testEntries() throws {
        let data = try S.fixture("data_mirror")
        XCTAssertEqual(data["base"] as? String, DataMirror.base)
        for c in S.list(data["entries"]) {
            let entry = DataMirror.entry(for: c["url"] as? String ?? "")
            XCTAssertEqual(entry?.file, c["file"] as? String, "file: \(c)")
            if let entry {
                XCTAssertEqual(entry.maxAgeMillis, (c["maxAgeMillis"] as? NSNumber)?.int64Value, "maxAge: \(c)")
                XCTAssertEqual(entry.url, DataMirror.base + entry.file)
            }
        }
    }

    func testUnwrap() throws {
        let data = try S.fixture("data_mirror")
        let now = try XCTUnwrap((data["now"] as? NSNumber)?.int64Value)
        for c in S.list(data["unwrap"]) {
            let maxAge = (c["maxAgeMillis"] as? NSNumber)?.int64Value ?? 0
            XCTAssertEqual(DataMirror.unwrap(c["text"] as? String, now: now, maxAgeMillis: maxAge),
                           c["expected"] as? String, "unwrap: \(c)")
        }
    }

    func testAltSeason() throws {
        let data = try S.fixture("data_mirror")
        let entry = try XCTUnwrap(data["altSeasonEntry"] as? [String: Any])
        XCTAssertEqual(DataMirror.altSeason.file, entry["file"] as? String)
        XCTAssertEqual(DataMirror.altSeason.maxAgeMillis, (entry["maxAgeMillis"] as? NSNumber)?.int64Value)
        for c in S.list(data["altSeason"]) {
            let expected = (c["expected"] as? [String: Any]).map {
                DataMirror.AltSeasonValue(outperformers: ($0["outperformers"] as? NSNumber)?.intValue ?? -1,
                                          total: ($0["total"] as? NSNumber)?.intValue ?? -1,
                                          provider: $0["provider"] as? String)
            }
            XCTAssertEqual(DataMirror.parseAltSeason(c["body"] as? String), expected, "altSeason: \(c)")
        }
    }
}
