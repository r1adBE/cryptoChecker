import XCTest
@testable import CryptoChecker

/// Relative Zeit am Zeilenanfang (`WatchlistTime.lineStart`): gross beginnend nach den Regeln der Sprache, mitten im
/// Satz klein — wie Android `RelativeTimeCaseTest`.
final class RelativeTimeLineStartTests: XCTestCase {

    private let german = Locale(identifier: "de")

    func testLineStartingWithTheTimeStartsUppercase() {
        // «vor 5 Min. · Binance nicht erreichbar» → gross am Zeilenanfang, Rest unverändert
        XCTAssertEqual(WatchlistTime.lineStart("vor 5 Min. · Binance nicht erreichbar", ago: "vor 5 Min.",
                                               locale: german),
                       "Vor 5 Min. · Binance nicht erreichbar")
        XCTAssertEqual(WatchlistTime.lineStart("vor 2 Std.", ago: "vor 2 Std.", locale: german), "Vor 2 Std.")
        XCTAssertEqual(WatchlistTime.lineStart("il y a 5 min · Binance injoignable", ago: "il y a 5 min",
                                               locale: Locale(identifier: "fr")),
                       "Il y a 5 min · Binance injoignable")
        // Türkisch: «i» wird zu «İ»
        XCTAssertEqual(WatchlistTime.lineStart("i", ago: "i", locale: Locale(identifier: "tr")), "İ")
        // Schriften ohne Gross/Klein bleiben unverändert
        XCTAssertEqual(WatchlistTime.lineStart("10 分钟前 · 无法连接 Binance", ago: "10 分钟前",
                                               locale: Locale(identifier: "zh")),
                       "10 分钟前 · 无法连接 Binance")
    }

    func testTimeInsideTheLineStaysLowercase() {
        // Andere Wortstellung bzw. Zeit hinter anderem Text: nichts ändern
        XCTAssertEqual(WatchlistTime.lineStart("Binance · vor 4 Min.", ago: "vor 4 Min.", locale: german),
                       "Binance · vor 4 Min.")
        XCTAssertEqual(WatchlistTime.lineStart("bitFlyer · vor 4 Min.", ago: "vor 4 Min.", locale: german),
                       "bitFlyer · vor 4 Min.")
        XCTAssertEqual(WatchlistTime.lineStart("", ago: "", locale: german), "")
        XCTAssertEqual(WatchlistTime.lineStart("abc", ago: "", locale: german), "abc")
    }
}
