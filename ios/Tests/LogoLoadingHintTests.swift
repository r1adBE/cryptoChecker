import XCTest
@testable import CryptoChecker

/// Hinweis «Logos werden geladen …» nur beim ersten Laden (`LogoLoadingHint`): gemeinsame Testfälle `coin_logos.json` › `loadingHint`
/// (Android: `LogoLoadingHintParityTest.kt`) und die Anbindung im Quelltext — Durchgang um die Stufen von `syncAll`,
/// fehlende Bilder aus `downloadMissing`, Zeile nur mit Schalter «Coin-Logos › In der App»; Fortschritt «· 40 %» nach
/// `coin_logos.json` › `loadingPercent`.
final class LogoLoadingHintTests: XCTestCase {

    private typealias S = TestSupport

    func testCasesAsFixture() throws {
        let fixture = try XCTUnwrap(try S.fixture("coin_logos")["loadingHint"] as? [String: Any])
        let cases = S.list(fixture["cases"])
        XCTAssertFalse(cases.isEmpty)
        XCTAssertEqual(fixture["marker"] as? String, LogoLoadingHint.doneMarker)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let steps = try XCTUnwrap(c["steps"] as? [String], name)
            let expected = try XCTUnwrap(c["visible"] as? [Bool], name)
            XCTAssertEqual(steps.count, expected.count, name)
            var hint = LogoLoadingHint()
            var markDone = false
            for (i, step) in steps.enumerated() {
                if step == "start" || step == "start done" {
                    hint.runStarted(firstLoadDone: step == "start done")
                } else if step == "finish" || step == "finish incomplete" {
                    if hint.runFinished(complete: step == "finish") { markDone = true }
                } else if step.hasPrefix("pending "), let n = Int(step.dropFirst("pending ".count)) {
                    hint.imagesPending(n)
                } else {
                    XCTFail("unbekannter Schritt \(step)")
                }
                XCTAssertEqual(hint.visible, expected[i], "\(name) › \(step)")
            }
            XCTAssertEqual(markDone, c["markDone"] as? Bool, "\(name) › markDone")
        }
    }

    /// Ein Schritt der Testfälle («start», «start done», «pending N [last]», «done N», «paused», «finish»).
    private func apply(_ step: String, to hint: inout LogoLoadingHint) -> Bool {
        let parts = step.split(separator: " ").map(String.init)
        switch parts[0] {
        case "start": hint.runStarted(firstLoadDone: parts.count > 1 && parts[1] == "done")
        case "finish": return hint.runFinished()
        case "pending": hint.imagesPending(Int(parts[1]) ?? -1, last: parts.count > 2 && parts[2] == "last")
        case "done": for _ in 0..<(Int(parts[1]) ?? 0) { hint.imageDone() }
        case "paused": hint.imagesPaused()
        default: XCTFail("unbekannter Schritt \(step)")
        }
        return false
    }

    func testPercentCasesAsFixture() throws {
        let fixture = try XCTUnwrap(try S.fixture("coin_logos")["loadingPercent"] as? [String: Any])
        let cases = S.list(fixture["cases"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let steps = try XCTUnwrap(c["steps"] as? [String], name)
            let expected = try XCTUnwrap(c["percent"] as? [Any], name).map { ($0 as? NSNumber)?.intValue }
            XCTAssertEqual(steps.count, expected.count, name)
            var hint = LogoLoadingHint()
            for (i, step) in steps.enumerated() {
                _ = apply(step, to: &hint)
                XCTAssertEqual(hint.percent, expected[i], "\(name) › \(step)")
            }
        }
    }

    func testPercentRuleAndThrottleAsFixture() throws {
        let fixture = try XCTUnwrap(try S.fixture("coin_logos")["loadingPercent"] as? [String: Any])
        XCTAssertEqual((fixture["intervalMillis"] as? NSNumber)?.int64Value, LogoLoadingHint.showIntervalMillis)
        XCTAssertEqual((fixture["step"] as? NSNumber)?.intValue, LogoLoadingHint.showStep)
        let rows = try XCTUnwrap(fixture["percentOf"] as? [[NSNumber]])
        XCTAssertFalse(rows.isEmpty)
        for row in rows {
            XCTAssertEqual(LogoLoadingHint.percentOf(done: row[0].intValue, total: row[1].intValue), row[2].intValue,
                           "percentOf \(row)")
        }
        for c in S.list(fixture["show"]) {
            let elapsed = try XCTUnwrap(c["elapsed"] as? NSNumber).int64Value
            let got = LogoLoadingHint.shouldShow(shown: (c["shown"] as? NSNumber)?.intValue, shownAt: 10_000,
                                                 next: (c["next"] as? NSNumber)?.intValue, now: 10_000 + elapsed)
            XCTAssertEqual(got, c["show"] as? Bool, "show \(c)")
        }
    }
}
