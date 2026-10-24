import XCTest
@testable import CryptoChecker

/// 15-s-Sperre für «Aktualisieren» liest nur das Ende der letzten VOLLSTÄNDIGEN Aktualisierung
/// (`SharedStorage.lastFullRefreshAt`), nicht die Uhrzeit der Live-Takte (`lastRefreshAt`) —
/// `Parity/refresh_debounce.json`, wie `RefreshDebounceParityTest.kt`. Dazu ein Quelltext-Scan der Aufrufer.
final class RefreshDebounceParityTests: XCTestCase {
    func testParityCases() throws {
        let fixture = try TestSupport.fixture("refresh_debounce")
        let now = TestSupport.millis(fixture["now"])
        let cases = TestSupport.list(fixture["cases"])
        XCTAssertGreaterThanOrEqual(cases.count, 5)
        for c in cases {
            let name = c["name"] as? String ?? ""
            let full = TestSupport.number(c["fullAgo"]).map { now - Int64($0) } ?? 0
            let decision = RefreshDebounce.decide(running: c["running"] as? Bool ?? false, lastFinishedAt: full, now: now)
            let expected: RefreshDebounce.Decision? = switch c["expected"] as? String {
            case "start": .start
            case "running": .running
            case "recent": .recent
            default: nil
            }
            XCTAssertEqual(decision, expected, name)
            let seconds = RefreshDebounce.remainingSeconds(lastFinishedAt: full, now: now)
            XCTAssertEqual(seconds, Int(TestSupport.number(c["remainingSeconds"]) ?? -1), name)
        }
    }

    /// Eigener Wert neben der Uhrzeit der Anzeige: ein Live-Takt verschiebt die Sperre nicht.
    func testFullRefreshTimeIsSeparateFromDisplayTime() {
        let savedDisplay = SharedStorage.lastRefreshAt
        let savedFull = SharedStorage.lastFullRefreshAt
        defer {
            SharedStorage.lastRefreshAt = savedDisplay
            SharedStorage.lastFullRefreshAt = savedFull
        }
        let now = TimeUtils.nowMillis
        SharedStorage.markFullRefresh(at: now - 600_000)
        XCTAssertEqual(SharedStorage.lastRefreshAt, now - 600_000)
        // Live-Takt (`AppData.applyLive`): nur die Uhrzeit der Anzeige
        SharedStorage.lastRefreshAt = now - 5_000
        XCTAssertEqual(SharedStorage.lastFullRefreshAt, now - 600_000)
        XCTAssertEqual(RefreshDebounce.decide(running: false, lastFinishedAt: SharedStorage.lastFullRefreshAt, now: now),
                       .start)
    }

    /// Knopf, Ziehen, Menü-Countdown und Widget-Knopf lesen `lastFullRefreshAt`; Live-Kurse setzen es nie.
    func testCallersUseTheFullRefreshTime() throws {
        let ios = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func text(_ path: String) throws -> String {
            try String(contentsOf: ios.appendingPathComponent(path), encoding: .utf8)
        }
        let callers = ["App/Services/AppData+Refresh.swift", "App/Features/Watchlist/WatchlistList.swift",
                       "App/Features/Watchlist/WatchlistRefreshCountdown.swift", "Widgets/WidgetIntents.swift",
                       "Widgets/WatchlistWidget.swift"]
        for path in callers {
            let source = try text(path)
            XCTAssertTrue(source.contains("lastFinishedAt: SharedStorage.lastFullRefreshAt"), path)
            XCTAssertFalse(source.contains("lastFinishedAt: SharedStorage.lastRefreshAt"), path)
            XCTAssertFalse(source.contains("lastFinishedAt: entry.lastRefreshAt"), path)
        }
        XCTAssertTrue(try text("App/Features/Watchlist/WatchlistRefreshCountdown.swift")
            .contains("let last = SharedStorage.lastFullRefreshAt"))
        let live = try text("App/Services/AppData+Live.swift")
        XCTAssertFalse(live.contains("markFullRefresh"))
        XCTAssertFalse(live.contains("lastFullRefreshAt ="))
    }
}
