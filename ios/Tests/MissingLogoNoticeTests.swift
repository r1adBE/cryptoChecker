import XCTest
@testable import CryptoChecker

/// Hinweis «2 Coins ohne Logo» (`MissingLogoNotice`): gemeinsame Testfälle `missing_logos.json` (Android:
/// `MissingLogoNoticeParityTest.kt`), «erstes Laden erledigt» aus `LogoLoadingHint` und die Anbindung im Quelltext —
/// an der Stelle des Lade-Hinweises, nur mit Schalter «Coin-Logos › In der App», ✕ mit 44 pt und eigener Bedienhilfe.
final class MissingLogoNoticeTests: XCTestCase {

    private typealias S = TestSupport

    func testCandidatesAsFixture() throws {
        let cases = S.list(try S.fixture("missing_logos")["candidates"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let rows = try XCTUnwrap(c["pairs"] as? [[Any]], name)
            let pairs = try rows.map { row in
                MissingLogoNotice.WatchedCoin(exchangeKey: try XCTUnwrap(row[0] as? String, name),
                                              base: try XCTUnwrap(row[1] as? String, name),
                                              tradFi: try XCTUnwrap(row[2] as? Bool, name))
            }
            XCTAssertEqual(MissingLogoNotice.candidates(pairs), c["keys"] as? [String], name)
        }
    }

    func testStateAsFixture() throws {
        let cases = S.list(try S.fixture("missing_logos")["state"])
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let got = MissingLogoNotice.state(missing: try XCTUnwrap(c["missing"] as? [String], name),
                                              dismissed: Set(try XCTUnwrap(c["dismissed"] as? [String], name)),
                                              loading: try XCTUnwrap(c["loading"] as? Bool, name),
                                              logosEnabled: try XCTUnwrap(c["enabled"] as? Bool, name))
            XCTAssertEqual(got.visible, c["visible"] as? Bool, "\(name) › visible")
            XCTAssertEqual(got.count, (c["count"] as? NSNumber)?.intValue, "\(name) › count")
        }
    }

    func testStoredSetAsFixture() throws {
        for c in S.list(try S.fixture("missing_logos")["stored"]) {
            let keys = Set(try XCTUnwrap(c["keys"] as? [String]))
            XCTAssertEqual(MissingLogoNotice.encode(keys), c["text"] as? String)
            XCTAssertEqual(MissingLogoNotice.decode(c["text"] as? String), keys)
        }
        XCTAssertEqual(MissingLogoNotice.decode(nil), [])
    }

    /// Erstes Laden erledigt: gleich nach dem Ende des Durchgangs mit Hinweis; ein Durchgang ohne Bilder lässt es offen.
    func testFinishedNowAfterTheFirstLoad() {
        var hint = LogoLoadingHint()
        XCTAssertFalse(hint.finishedNow)
        hint.runStarted(firstLoadDone: false)
        hint.imagesPending(3)
        XCTAssertFalse(hint.finishedNow)
        let finished = hint.runFinished()
        XCTAssertTrue(finished)
        XCTAssertTrue(hint.finishedNow)
        // Zwischenspeicher geleert: neues erstes Laden, ohne Bilder bleibt es offen
        hint.runStarted(firstLoadDone: false)
        XCTAssertFalse(hint.finishedNow)
        let empty = hint.runFinished()
        XCTAssertFalse(empty)
        XCTAssertFalse(hint.finishedNow)
        // Schon gemerkt (Merk-Datei)
        hint.runStarted(firstLoadDone: true)
        XCTAssertTrue(hint.finishedNow)
    }

    func testWiredAtTheLoadingHintWithTheLogoSwitch() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        func text(_ path: String) throws -> String {
            try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
        }
        let line = try text("App/Features/Watchlist/WatchlistMissingLogos.swift")
        // Nie während des ersten Ladens bzw. solange «Logos werden geladen» steht; Schalter aus: nie
        XCTAssertTrue(line.contains("guard enabled, !loading, CoinLogoLoading.firstLoadDone() else { return show(nil) }"))
        XCTAssertTrue(line.contains("await model.refresh(entries, enabled: coinLogosEnabled, loading: loading.visible)"))
        XCTAssertTrue(line.contains("dismissed: dismissals.keys, loading: loading.visible,"))
        XCTAssertTrue(line.contains("logosEnabled: coinLogosEnabled)"))
        XCTAssertTrue(line.contains("if await !CoinLogoStore.shared.hasLogo(entry.key)"))
        XCTAssertTrue(line.contains("L(\"watchlist_missing_logos\", count: count)"))
        XCTAssertTrue(line.contains(".accessibilityLabel(L(\"watchlist_missing_logos_dismiss\"))"))
        XCTAssertTrue(line.contains("private static let touch: CGFloat = 44"))
        XCTAssertTrue(line.contains("L(\"watchlist_missing_logos_explain\")"))
        XCTAssertTrue(line.contains("SharedStorage.defaults.set(MissingLogoNotice.encode(keys), forKey: Self.key)"))
        let header = try text("App/Features/Watchlist/WatchlistHeader.swift")
        let hint = try XCTUnwrap(header.range(of: "WatchlistLogoHint()"))
        let notice = try XCTUnwrap(header.range(of: "{ WatchlistMissingLogos() }"))
        XCTAssertTrue(hint.upperBound < notice.lowerBound)
        XCTAssertTrue(header.contains(".background { WatchlistMissingLogosCheck(watches: data.watches) }"))
        let loading = try text("Shared/Util/LogoLoadingHint.swift")
        XCTAssertTrue(loading.contains("shared.hint.finishedNow || FileManager.default.fileExists(atPath: markerPath)"))
    }
}
