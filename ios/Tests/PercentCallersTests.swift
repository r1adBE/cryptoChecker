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

    /// Open-Interest-Schwellen bis 1000 %: mit Tausendertrennung wie früher `NumberFormatter.percent` («1,000%»),
    /// wie Android `AlarmSentenceTest.alarmProzentMitTausendertrennung`.
    func testAlarmProzentMitTausendertrennung() {
        XCTAssertEqual(AlarmTexts.percent(1000, locale: us), "1,000%")
        XCTAssertEqual(AlarmTexts.percent(1000, locale: de), "1.000\u{00A0}%")
        XCTAssertEqual(AlarmTexts.percent(1000, locale: tr), "%1.000")
        XCTAssertEqual(AlarmTexts.signedPercent(1234.5, locale: us), "+1,234.5%")
        XCTAssertEqual(AlarmTexts.fundingPercent(-1500, locale: de), "\u{2212}1.500\u{00A0}%")
        // ohne grouping (Standard) wie bisher ohne Trennung
        XCTAssertEqual(PercentText.unsigned(1000, decimals: 2, minDecimals: 0, locale: us), "1000%")
    }

    func testOhneNullenAmEndeMitMinDecimals() {
        XCTAssertEqual(PercentText.unsigned(5, decimals: 2, minDecimals: 0, locale: us), "5%")
        XCTAssertEqual(PercentText.unsigned(2.5, decimals: 4, minDecimals: 0, locale: de), "2,5\u{00A0}%")
        XCTAssertEqual(PercentText.unsigned(0.0125, decimals: 4, minDecimals: 0, locale: tr), "%0,0125")
        XCTAssertEqual(PercentText.signed(12.34, decimals: 1, minDecimals: 0, locale: us), "+12.3%")
        // ohne minDecimals wie bisher genau `decimals` Stellen
        XCTAssertEqual(PercentText.unsigned(5, decimals: 2, locale: us), "5.00%")
    }

    /// Zyklus-Chart für VoiceOver seit Build 96 wie Android `A11yText.change(…, decimals = 0)`: ganze Prozent ohne
    /// Tausendertrennung, «unverändert» erst unter 0.005 % (vorher «1’130 %» und schon unter 0.5 %).
    func testZyklusChartSprichtWieAndroid() {
        XCTAssertEqual(PercentText.unsigned(1130, decimals: 0, locale: us), "1130%")
        XCTAssertEqual(A11y.change(1130, decimals: 0), L("a11y_change_up", PercentText.unsigned(1130, decimals: 0)))
        XCTAssertEqual(A11y.change(-40, decimals: 0), L("a11y_change_down", PercentText.unsigned(40, decimals: 0)))
        // 0.3 % ist noch eine Veränderung (gerundet «0%»), erst unter 0.005 % «unverändert»
        XCTAssertEqual(A11y.change(0.3, decimals: 0), L("a11y_change_up", PercentText.unsigned(0.3, decimals: 0)))
        XCTAssertEqual(A11y.change(0.004, decimals: 0), L("a11y_change_flat"))
        // ohne decimals wie bisher zwei Stellen
        XCTAssertEqual(A11y.change(1.5), L("a11y_change_up", PercentText.unsigned(1.5, decimals: 2)))
    }

    /// Portfolio-Alarm: auch gerundet 0 («0.00%») als links-nach-rechts-Insel wie Android
    /// `PortfolioSnapshotMath.signedPercent` (vorher ohne Insel); ausserhalb von RTL unverändert.
    func testPortfolioProzentImmerAlsInsel() {
        let lri = BidiText.lri, pdi = BidiText.pdi
        XCTAssertEqual(PortfolioAlarmTexts.signedPercent(0, language: "ar"),
                       lri + PercentText.unsigned(0, decimals: 2) + pdi)
        XCTAssertEqual(PortfolioAlarmTexts.signedPercent(-0.001, language: "ar"),
                       lri + PercentText.unsigned(0, decimals: 2) + pdi)
        XCTAssertEqual(PortfolioAlarmTexts.signedPercent(-5.2, language: "ar"),
                       lri + "\u{2212}" + PercentText.unsigned(5.2, decimals: 2) + pdi)
        XCTAssertEqual(PortfolioAlarmTexts.signedPercent(5.2, language: "en"), "+" + PercentText.unsigned(5.2, decimals: 2))
        XCTAssertEqual(PortfolioAlarmTexts.signedPercent(0, language: "en"), PercentText.unsigned(0, decimals: 2))
    }

    func testSpotProzentJeSprache() {
        XCTAssertEqual(SpotMarket.percent(2.728915662650602, locale: us), "2.73%")
        XCTAssertEqual(SpotMarket.percent(2.728915662650602, locale: de), "2,73\u{00A0}%")
        XCTAssertEqual(SpotMarket.percent(2.728915662650602, locale: tr), "%2,73")
        XCTAssertEqual(SpotMarket.percent(nil, locale: de), "—")
    }
}
