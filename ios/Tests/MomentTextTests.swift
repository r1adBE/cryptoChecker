import XCTest
@testable import CryptoChecker

/// «zuletzt um …»: heute nur die Uhrzeit, an einem anderen Tag mit Datum (`MomentText`). Gegenstück: `MomentTextTest.kt`.
final class MomentTextTests: XCTestCase {
    private let zone = TimeZone(identifier: "Europe/Zurich")!

    private func at(_ y: Int, _ m: Int, _ d: Int, _ h: Int, _ min: Int, _ s: Int = 0) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let date = calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min, second: s))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    func testGleicherTagNachKalender() {
        let now = at(2026, 10, 12, 0, 30)
        XCTAssertTrue(MomentText.isSameDay(at(2026, 10, 12, 0, 5), now, timeZone: zone))
        // Vor Mitternacht = gestern, auch wenn nur 40 Minuten her
        XCTAssertFalse(MomentText.isSameDay(at(2026, 10, 11, 23, 50), now, timeZone: zone))
    }

    func testHeuteNurUhrzeitSonstMitDatum() {
        let now = at(2026, 10, 12, 18, 0)
        let de = Locale(identifier: "de_DE")
        XCTAssertEqual(MomentText.moment(at(2026, 10, 12, 14, 2, 11), now: now, timeZone: zone, locale: de), "14:02:11")
        let earlier = MomentText.moment(at(2026, 10, 9, 14, 2, 11), now: now, timeZone: zone, locale: de)
        XCTAssertTrue(earlier.contains("09.10.26"), earlier)
        XCTAssertTrue(earlier.contains("14:02"), earlier)
        XCTAssertFalse(earlier.contains("14:02:11"), earlier)
        XCTAssertEqual(MomentText.moment(0, now: now, timeZone: zone, locale: de), "—")
    }
}

/// Mit zwischengespeicherten Formattern (`DateFormatterCache`): gleiche Ausgabe wie ein frischer
/// `DateFormatter` mit denselben Stilen — auch bei wechselnder Zeitzone und Sprache.
final class MomentTextCacheTests: XCTestCase {
    private func fresh(_ millis: Int64, zone: TimeZone, locale: Locale, today: Bool) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.timeZone = zone
        f.dateStyle = today ? .none : .short
        f.timeStyle = today ? .medium : .short
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    func testGleicheAusgabeWieFrischerFormatter() {
        let now: Int64 = 1_792_000_000_000
        let zones = ["Europe/Zurich", "America/New_York", "Asia/Tokyo"].compactMap(TimeZone.init(identifier:))
        let locales = ["de_CH", "en_US", "ja_JP"].map(Locale.init(identifier:))
        for zone in zones {
            for locale in locales {
                for millis in [now - 60_000, now - 3 * 86_400_000] {
                    let today = MomentText.isSameDay(millis, now, timeZone: zone)
                    // Zweimal: der zweite Aufruf nimmt den zwischengespeicherten Formatter
                    for _ in 0..<2 {
                        XCTAssertEqual(MomentText.moment(millis, now: now, timeZone: zone, locale: locale),
                                       fresh(millis, zone: zone, locale: locale, today: today), "\(zone.identifier) \(locale.identifier)")
                    }
                }
            }
        }
    }
}
