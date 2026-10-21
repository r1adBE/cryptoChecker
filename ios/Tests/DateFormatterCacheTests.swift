import XCTest
@testable import CryptoChecker

/// Zwischengespeicherte Datumsformate (`DateFormatterCache`): gleiche Ausgabe wie ein frischer `DateFormatter`.
@MainActor
final class DateFormatterCacheTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_714_000_000)

    func testMittleresDatumWieFrischerFormatter() {
        let fresh = DateFormatter()
        fresh.dateStyle = .medium
        fresh.timeStyle = .none
        XCTAssertEqual(CycleFormat.mediumDate(date), fresh.string(from: date))
        // Zweiter Aufruf aus dem Zwischenspeicher: dieselbe Zahl
        XCTAssertEqual(CycleFormat.mediumDate(date), fresh.string(from: date))
    }

    func testVorlageMitEigenerSpracheJeSpracheGetrennt() {
        let de = Locale(identifier: "de_CH")
        let en = Locale(identifier: "en_US")
        func fresh(_ locale: Locale) -> String {
            let f = DateFormatter()
            f.locale = locale
            f.setLocalizedDateFormatFromTemplate("MMMyyyy")
            return f.string(from: date)
        }
        func cached(_ locale: Locale) -> String {
            DateFormatterCache.string(date, purpose: "testMonthYear", locale: locale) { f in
                f.setLocalizedDateFormatFromTemplate("MMMyyyy")
            }
        }
        XCTAssertEqual(cached(de), fresh(de))
        XCTAssertEqual(cached(en), fresh(en))
        XCTAssertEqual(cached(de), fresh(de))
    }

    func testUhrzeitHeuteSonstDatum() {
        let now = Date()
        let time = DateFormatter()
        time.timeStyle = .short
        time.dateStyle = .none
        XCTAssertEqual(TickerView.sameDayTimeOrDate(Int64(now.timeIntervalSince1970 * 1000)), time.string(from: now))
    }
}
