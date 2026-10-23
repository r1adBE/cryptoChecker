import XCTest
@testable import CryptoChecker

/// Marken der Merklisten-Zeile (`Parity/watchlist_row_marks.json`): nur Abweichungen vom Normalfall, dazu der
/// Zeitraum an der Änderung (entfällt nur bei «seit letzt. Abruf» unter dem Spaltenkopf).
/// Gegenstück: `WatchlistRowMarksParityTest.kt`.
final class WatchlistRowMarksTests: XCTestCase {
    private typealias S = TestSupport

    func testKindOnlyWhenNotToken() throws {
        let cases = S.list(try S.fixture("watchlist_row_marks")["kinds"])
        XCTAssertEqual(Set(AssetKind.allCases.map(\.rawValue)), Set(cases.compactMap { $0["kind"] as? String }))
        for c in cases {
            let kind = try XCTUnwrap(AssetKind(rawValue: c["kind"] as? String ?? ""))
            XCTAssertEqual(WatchlistRowMarks.showsKind(kind), c["showsKind"] as? Bool, kind.rawValue)
        }
    }

    func testPriceBellOnlyWhenOff() throws {
        for c in S.list(try S.fixture("watchlist_row_marks")["priceBell"]) {
            let on = try XCTUnwrap(c["priceNotificationOn"] as? Bool)
            XCTAssertEqual(WatchlistRowMarks.showsPriceBell(on), c["showsPriceBell"] as? Bool, "on=\(on)")
        }
    }

    func testPeriodHiddenOnlyForSinceLastUnderHeader() throws {
        for c in S.list(try S.fixture("watchlist_row_marks")["rowPeriod"]) {
            let name = c["basis"] as? String ?? ""
            let basis = try XCTUnwrap(ChangeBasis(rawValue: name))
            let header = try XCTUnwrap(c["periodInHeader"] as? Bool)
            XCTAssertEqual(WatchlistRowMarks.showsPeriod(basis, periodInHeader: header), c["showsPeriod"] as? Bool,
                           "\(name) header=\(header)")
        }
    }
}
