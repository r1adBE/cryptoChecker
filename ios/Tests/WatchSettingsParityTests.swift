import XCTest
@testable import CryptoChecker

/// Uhr-Apps (`Parity/watch_settings.json`) — wie `WatchSettingsParityTest.kt`: Listen, JSON und Zusammenführen nach
/// Änderungsnummer (Gleichstand: Handy).
final class WatchSettingsParityTests: XCTestCase {

    private func strings(_ v: Any?) -> [String] { v as? [String] ?? [] }

    private func settings(_ v: Any?) -> WatchSettings {
        let o = v as? [String: Any] ?? [:]
        return WatchSettings(mode: WatchSettings.Mode(rawValue: o["mode"] as? String ?? "") ?? .own,
                             own: strings(o["own"]), phone: strings(o["phone"]),
                             revision: Int64(TestSupport.number(o["revision"]) ?? 0))
    }

    func testCoins() throws {
        let data = try TestSupport.fixture("watch_settings")
        XCTAssertEqual(WatchCoins.top, strings(data["top"]))
        for c in TestSupport.list(data["toggle"]) {
            XCTAssertEqual(WatchCoins.toggle(strings(c["list"]), c["coin"] as? String ?? ""), strings(c["expected"]))
        }
        for c in TestSupport.list(data["cleanOwn"]) {
            XCTAssertEqual(WatchCoins.cleanOwn(strings(c["stored"])), strings(c["expected"]))
        }
        for c in TestSupport.list(data["fromFavorites"]) {
            XCTAssertEqual(WatchCoins.fromFavorites(strings(c["bases"])), strings(c["expected"]))
        }
    }

    func testJson() throws {
        let data = try TestSupport.fixture("watch_settings")
        for c in TestSupport.list(data["fromJson"]) {
            let parsed = WatchSettings.fromJson(c["text"] as? String)
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(parsed)
                continue
            }
            XCTAssertEqual(parsed, settings(e))
            XCTAssertEqual(parsed?.shown, strings(e["shown"]))
            XCTAssertEqual(WatchSettings.fromJson(parsed?.toJson()), parsed)
        }
    }

    func testMerge() throws {
        let data = try TestSupport.fixture("watch_settings")
        for c in TestSupport.list(data["merge"]) {
            let merged = WatchSettings.merge(settings(c["local"]), settings(c["remote"]),
                                             remoteIsPhone: c["remoteIsPhone"] as? Bool ?? false)
            XCTAssertEqual(merged, settings(c["expected"]), c["name"] as? String ?? "")
        }
    }

    func testNextRevision() throws {
        let data = try TestSupport.fixture("watch_settings")
        for c in TestSupport.list(data["nextRevision"]) {
            let before = WatchSettings(revision: Int64(TestSupport.number(c["revision"]) ?? -1))
            XCTAssertEqual(before.nextRevision().revision, Int64(TestSupport.number(c["expected"]) ?? -1))
            var same = before.nextRevision()
            same.revision = before.revision
            XCTAssertEqual(same, before)
        }
    }
}
