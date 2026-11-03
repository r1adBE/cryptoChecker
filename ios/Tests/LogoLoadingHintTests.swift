import XCTest
@testable import CryptoChecker

/// Hinweis «Logos werden geladen …» nur beim ersten Laden (`LogoLoadingHint`): gemeinsame Testfälle `coin_logos.json` › `loadingHint`
/// (Android: `LogoLoadingHintParityTest.kt`) und die Anbindung im Quelltext — Durchgang um die Stufen von `syncAll`,
/// fehlende Bilder aus `downloadMissing`, Zeile nur mit Schalter «Coin-Logos › In der App».
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
                } else if step == "finish" {
                    if hint.runFinished() { markDone = true }
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

    func testWiredToTheDownloadRunAndTheLogoSwitch() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func text(_ path: String) throws -> String {
            try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
        }
        let store = try text("Shared/Storage/CoinLogoStore.swift")
        let sync = store.components(separatedBy: "func syncAll(").last ?? ""
        let start = try XCTUnwrap(sync.range(of: "if images { await CoinLogoLoading.runStarted() }"))
        let rest = try XCTUnwrap(sync.range(of: "let rest = await Self.downloadMissing("))
        let finish = try XCTUnwrap(sync.range(of: "await CoinLogoLoading.runFinished()"))
        XCTAssertTrue(start.upperBound < rest.lowerBound && rest.upperBound < finish.lowerBound)
        // Erst nach «nichts zu laden»: Coins ohne Logo-Adresse lösen nie einen Hinweis aus
        let download = try text("Shared/Storage/CoinLogoStore+Download.swift")
        let empty = try XCTUnwrap(download.range(of: "guard !pending.isEmpty else { return DownloadResult() }"))
        let pending = try XCTUnwrap(download.range(of: "await CoinLogoLoading.imagesPending(pending.count)"))
        XCTAssertTrue(empty.upperBound < pending.lowerBound)
        // Erstes Laden gemerkt im Logo-Ordner (verschwindet mit ihm und beim Zurücksetzen); Ein- und Ausblenden
        let loading = try text("Shared/Util/LogoLoadingHint.swift")
        XCTAssertTrue(loading.contains("CoinLogoStore.directory.appendingPathComponent(LogoLoadingHint.doneMarker)"))
        XCTAssertTrue(loading.contains("UIAccessibility.isReduceMotionEnabled ? nil :"))
        let line = try text("App/Features/Watchlist/WatchlistLogoHint.swift")
        XCTAssertTrue(line.contains("Line().transition(.opacity)"))
        XCTAssertTrue(line.contains("if coinLogosEnabled && loading.visible"))
        XCTAssertTrue(line.contains("L(\"watchlist_logos_loading\")"))
        XCTAssertTrue(try text("App/Features/Watchlist/WatchlistHeader.swift").contains("WatchlistLogoHint()"))
    }
}
