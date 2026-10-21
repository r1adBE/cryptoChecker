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

    func testUhrzeitHeuteSonstDatum() throws {
        // Heute: fester Zeitpunkt mitten im heutigen Kalendertag (nicht «jetzt») — und falls der Test genau über
        // Mitternacht läuft, überspringen statt je nach Uhrzeit mal Uhrzeit, mal Datum zu erwarten
        let calendar = Calendar.current
        let noon = calendar.date(byAdding: .hour, value: 12, to: calendar.startOfDay(for: Date()))!
        let todayBefore = calendar.isDateInToday(noon)
        let result = TickerView.sameDayTimeOrDate(Int64(noon.timeIntervalSince1970 * 1000))
        guard todayBefore, calendar.isDateInToday(noon) else { throw XCTSkip("Tageswechsel während des Tests") }
        let time = DateFormatter()
        time.timeStyle = .short
        time.dateStyle = .none
        XCTAssertEqual(result, time.string(from: noon))
        // Fester Tag in der Vergangenheit: immer Datum statt Uhrzeit
        let day = DateFormatter()
        day.dateStyle = .short
        day.timeStyle = .none
        XCTAssertEqual(TickerView.sameDayTimeOrDate(Int64(date.timeIntervalSince1970 * 1000)), day.string(from: date))
    }
}
