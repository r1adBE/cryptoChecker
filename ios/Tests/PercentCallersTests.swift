import XCTest
@testable import CryptoChecker

/// Seit Build 96 schreiben auch Alarm-Sätze, Funding, Spot-Kennzahlen und Schwellen ihre Prozente über `PercentText`
/// (vorher `NumberFormatter.percent` oder « %» von Hand) — Zeichen für Zeichen wie Android
/// (`AlarmSentenceTest`, `PercentTextTest`, `PortfolioWidgetMathTest`).
final class PercentCallersTests: XCTestCase {
    private let us = Locale(identifier: "en_US")
    private let de = Locale(identifier: "de_DE")
    private let ch = Locale(identifier: "de_CH")
    private let tr = Locale(identifier: "tr_TR")

    func testAlarmProzentInDerSchreibweiseDerSprache() {
        XCTAssertEqual(AlarmTexts.percent(0.5, locale: tr), "%0,5")
        XCTAssertEqual(AlarmTexts.percent(0.5, locale: de), "0,5\u{00A0}%")
        XCTAssertEqual(AlarmTexts.percent(0.5, locale: us), "0.5%")
        XCTAssertEqual(AlarmTexts.percent(0.5, locale: ch), "0.5%")
        // höchstens zwei Nachkommastellen, Nullen am Ende fallen weg, kaufmännisch gerundet
        XCTAssertEqual(AlarmTexts.percent(5, locale: us), "5%")
        XCTAssertEqual(AlarmTexts.percent(1.255, locale: us), "1.26%")
    }

    func testFundingUndVeraenderungMitEchtemMinus() {
        XCTAssertEqual(AlarmTexts.fundingPercent(0.0125, locale: us), "0.0125%")
        XCTAssertEqual(AlarmTexts.fundingPercent(-0.0125, locale: de), "\u{2212}0,0125\u{00A0}%")
        XCTAssertEqual(AlarmTexts.fundingPercent(-0.05, locale: tr), "\u{2212}%0,05")
        // gerundet 0: nie «−0%»
        XCTAssertEqual(AlarmTexts.fundingPercent(-0.00001, locale: us), "0%")
        XCTAssertEqual(AlarmTexts.signedPercent(12.34, locale: tr), "+%12,3")
        XCTAssertEqual(AlarmTexts.signedPercent(-4, locale: de), "\u{2212}4\u{00A0}%")
        XCTAssertEqual(AlarmTexts.signedPercent(0.04, locale: us), "0%")
    }

    func testOhneNullenAmEndeMitMinDecimals() {
        XCTAssertEqual(PercentText.unsigned(5, decimals: 2, minDecimals: 0, locale: us), "5%")
        XCTAssertEqual(PercentText.unsigned(2.5, decimals: 4, minDecimals: 0, locale: de), "2,5\u{00A0}%")
        XCTAssertEqual(PercentText.unsigned(0.0125, decimals: 4, minDecimals: 0, locale: tr), "%0,0125")
        XCTAssertEqual(PercentText.signed(12.34, decimals: 1, minDecimals: 0, locale: us), "+12.3%")
        // ohne minDecimals wie bisher genau `decimals` Stellen
        XCTAssertEqual(PercentText.unsigned(5, decimals: 2, locale: us), "5.00%")
    }

    func testSpotProzentJeSprache() {
        XCTAssertEqual(SpotMarket.percent(2.728915662650602, locale: us), "2.73%")
        XCTAssertEqual(SpotMarket.percent(2.728915662650602, locale: de), "2,73\u{00A0}%")
        XCTAssertEqual(SpotMarket.percent(2.728915662650602, locale: tr), "%2,73")
        XCTAssertEqual(SpotMarket.percent(nil, locale: de), "—")
    }
}
