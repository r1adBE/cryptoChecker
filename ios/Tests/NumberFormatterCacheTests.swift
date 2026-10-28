import XCTest
@testable import CryptoChecker

/// Zwischengespeicherte Zahlenformate (`NumberFormatterCache`, `IsoDates`): gleiche Ausgabe wie ein frischer Formatter,
/// je Sprache/Region getrennt, nach einem Sprachwechsel (Verwerfen) unverändert richtig.
final class NumberFormatterCacheTests: XCTestCase {
    private let locales = ["de_CH", "en_US", "fr_FR", "hi_IN", "ar_EG@numbers=arab", "fa_IR@numbers=arabext"]
        .map { Locale(identifier: $0) }

    private func freshDecimal(_ value: Double, max: Int, min: Int, grouping: Bool, _ locale: Locale) -> String {
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.roundingMode = .halfUp
        f.usesGroupingSeparator = grouping
        f.minimumFractionDigits = min
        f.maximumFractionDigits = max
        return f.string(from: NSNumber(value: value)) ?? ""
    }

    private func freshInteger(_ value: Int64, grouping: Bool, _ locale: Locale) -> String {
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.usesGroupingSeparator = grouping
        f.maximumFractionDigits = 0
        return f.string(from: NSNumber(value: value)) ?? ""
    }

    private func freshThreshold(_ value: Decimal, _ locale: Locale) -> String {
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.usesGroupingSeparator = true
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = PriceFormat.thresholdMaxDecimals
        f.generatesDecimalNumbers = true
        return f.string(from: NSDecimalNumber(decimal: value)) ?? ""
    }

    func testGleicheTexteWieFrischerFormatter() {
        for locale in locales {
            for _ in 0..<2 { // zweiter Durchgang aus dem Zwischenspeicher
                for value in [0.0, 0.25, 58.44, 1_234_567.891, -42.5] {
                    XCTAssertEqual(LocaleNumbers.decimal(value, maxDecimals: 2, locale: locale, grouping: true),
                                   freshDecimal(value, max: 2, min: 2, grouping: true, locale), locale.identifier)
                    XCTAssertEqual(LocaleNumbers.decimal(value, maxDecimals: 3, minDecimals: 0, locale: locale),
                                   freshDecimal(value, max: 3, min: 0, grouping: false, locale), locale.identifier)
                }
                for value: Int64 in [0, 72, 2024, 1_000_000] {
                    XCTAssertEqual(LocaleNumbers.integer(value, locale: locale), freshInteger(value, grouping: false, locale))
                    XCTAssertEqual(LocaleNumbers.integer(value, locale: locale, grouping: true),
                                   freshInteger(value, grouping: true, locale))
                }
                for value in [1.0005, 65_000, 0.00000712] {
                    let exact = Decimal(string: DecimalText.plain(value), locale: Locale(identifier: "en_US_POSIX"))!
                    XCTAssertEqual(PriceFormat.threshold(value, locale: locale), freshThreshold(exact, locale),
                                   locale.identifier)
                }
            }
        }
    }

    func testEinstellungenJeZweckGetrennt() {
        // Gleiche Sprache, verschiedene Einstellungen: nie der Formatter des anderen Zwecks
        let de = Locale(identifier: "de_CH")
        XCTAssertEqual(LocaleNumbers.integer(1_000_000, locale: de, grouping: true),
                       freshInteger(1_000_000, grouping: true, de))
        XCTAssertEqual(LocaleNumbers.integer(1_000_000, locale: de, grouping: false), "1000000")
        XCTAssertEqual(LocaleNumbers.decimal(1.5, maxDecimals: 1, locale: de), freshDecimal(1.5, max: 1, min: 1,
                                                                                           grouping: false, de))
        XCTAssertEqual(LocaleNumbers.decimal(1.5, maxDecimals: 3, locale: de), freshDecimal(1.5, max: 3, min: 3,
                                                                                           grouping: false, de))
    }

    func testSprachwechselVerwirftUndRechnetGleich() {
        let de = Locale(identifier: "de_DE")
        let en = Locale(identifier: "en_US")
        let before = LocaleNumbers.decimal(1234.5, maxDecimals: 2, locale: de, grouping: true)
        XCTAssertGreaterThan(NumberFormatterCache.count, 0)
        // Wie nach `NSLocale.currentLocaleDidChangeNotification`
        NumberFormatterCache.clear()
        XCTAssertEqual(NumberFormatterCache.count, 0)
        // Andere Sprache: eigener Formatter, eigene Schreibweise; die erste bleibt richtig
        let english = LocaleNumbers.decimal(1234.5, maxDecimals: 2, locale: en, grouping: true)
        XCTAssertEqual(english, "1,234.50")
        XCTAssertEqual(LocaleNumbers.decimal(1234.5, maxDecimals: 2, locale: de, grouping: true), before)
        XCTAssertEqual(before, "1.234,50")
    }

    func testExakteMengeWieFrischerFormatter() {
        let f = NumberFormatter()
        f.locale = LocaleNumbers.appLocale
        f.numberStyle = .decimal
        f.usesGroupingSeparator = true
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = PortfolioDecimal.maxScale
        f.generatesDecimalNumbers = true
        for text in ["0.00000001", "1234567.123456789012345678", "0", "42"] {
            let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))!
            XCTAssertEqual(PriceFormat.exactAmount(value), f.string(from: NSDecimalNumber(decimal: value)))
            XCTAssertEqual(PriceFormat.exactAmount(value), f.string(from: NSDecimalNumber(decimal: value)))
        }
    }

    func testIsoZeitpunkteMitUndOhneBruchteil() {
        XCTAssertEqual(IsoDates.date("2026-10-11T12:34:56.789Z")?.timeIntervalSince1970 ?? 0, 1_791_722_096.789,
                       accuracy: 0.0005)
        XCTAssertEqual(IsoDates.date("2026-10-11T12:30:00Z")?.timeIntervalSince1970 ?? 0, 1_791_721_800, accuracy: 0.0005)
        XCTAssertNil(IsoDates.date("garbage"))
        XCTAssertEqual(EpochTime.isoToMillis("2026-10-11T12:30:00Z"), 1_791_721_800_000)
    }
}
