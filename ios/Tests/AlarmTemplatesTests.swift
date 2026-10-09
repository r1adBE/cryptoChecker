import XCTest
@testable import CryptoChecker

/// Wie `AlarmTemplatesTest.kt` und `NearExtremeTest.newOnlyFiresOnlyOnNewExtremes`.
final class AlarmTemplatesTests: XCTestCase {

    typealias T = AlarmTemplates.Template

    func testPercentTemplatesMeasureFromTheCurrentPrice() throws {
        let up = try XCTUnwrap(AlarmTemplates.definition(.UP_1, currentPrice: 60_000))
        XCTAssertEqual(up.condition, .CHANGE_PERCENT_UP)
        XCTAssertEqual(up.threshold, 1, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(up.referencePrice), 60_000, accuracy: 1e-9)
        XCTAssertFalse(up.repeating)

        let down = try XCTUnwrap(AlarmTemplates.definition(.DOWN_5, currentPrice: 2.5))
        XCTAssertEqual(down.condition, .CHANGE_PERCENT_DOWN)
        XCTAssertEqual(down.threshold, 5, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(down.referencePrice), 2.5, accuracy: 1e-9)

        XCTAssertEqual(try XCTUnwrap(AlarmTemplates.definition(.UP_5, currentPrice: 1)).threshold, 5, accuracy: 1e-9)
        XCTAssertEqual(AlarmTemplates.definition(.DOWN_1, currentPrice: 1)?.condition, .CHANGE_PERCENT_DOWN)
    }

    func testPercentTemplatesNeedAValidPrice() {
        XCTAssertNil(AlarmTemplates.definition(.UP_1, currentPrice: nil))
        XCTAssertNil(AlarmTemplates.definition(.UP_1, currentPrice: 0))
        XCTAssertNil(AlarmTemplates.definition(.DOWN_1, currentPrice: -3))
        XCTAssertNil(AlarmTemplates.definition(.DOWN_5, currentPrice: .nan))
        XCTAssertNil(AlarmTemplates.definition(.UP_5, currentPrice: .infinity))
    }

    func testNewExtremeTemplatesUseNearExtremeWithDistanceZero() throws {
        let high = try XCTUnwrap(AlarmTemplates.definition(.NEW_HIGH_30, currentPrice: nil))
        XCTAssertEqual(high.condition, .NEAR_HIGH)
        XCTAssertTrue(NearExtreme.isNewOnly(high.threshold))
        XCTAssertEqual(high.windowHours, 30)
        XCTAssertNil(high.referencePrice)

        let low = try XCTUnwrap(AlarmTemplates.definition(.NEW_LOW_30, currentPrice: 100))
        XCTAssertEqual(low.condition, .NEAR_LOW)
        XCTAssertTrue(NearExtreme.isNewOnly(low.threshold))
        XCTAssertEqual(NearExtreme.windowDays(low.windowHours), 30)
        XCTAssertNil(low.referencePrice)
    }

    func testVolumeTemplateUsesFactorThree() throws {
        let v = try XCTUnwrap(AlarmTemplates.definition(.VOLUME_X3, currentPrice: 5))
        XCTAssertEqual(v.condition, .VOLUME_SPIKE)
        XCTAssertEqual(v.threshold, 3, accuracy: 1e-9)
        XCTAssertNil(v.referencePrice)
    }

    func testAvailabilityHidesTemplatesWithoutData() {
        XCTAssertEqual(AlarmTemplates.available(hasPrice: true, hasDailyRange: true, hasHourlyVolume: true), T.allCases)
        // DEX-Paar ohne Kerzen: nur die Prozent-Vorlagen
        XCTAssertEqual(AlarmTemplates.available(hasPrice: true, hasDailyRange: false, hasHourlyVolume: false),
                       [.UP_1, .UP_5, .DOWN_1, .DOWN_5])
        // Noch kein Kurs, aber Kerzen
        XCTAssertEqual(AlarmTemplates.available(hasPrice: false, hasDailyRange: true, hasHourlyVolume: true),
                       [.NEW_HIGH_30, .NEW_LOW_30, .VOLUME_X3])
        XCTAssertEqual(AlarmTemplates.available(hasPrice: false, hasDailyRange: false, hasHourlyVolume: true), [.VOLUME_X3])
        XCTAssertTrue(AlarmTemplates.available(hasPrice: false, hasDailyRange: false, hasHourlyVolume: false).isEmpty)
    }

    func testTemplateOrderAndPercentSigns() {
        XCTAssertEqual(T.allCases.map(\.rawValue),
                       ["UP_1", "UP_5", "DOWN_1", "DOWN_5", "NEW_HIGH_30", "NEW_LOW_30", "VOLUME_X3"])
        XCTAssertEqual(T.UP_1.percent, 1)
        XCTAssertEqual(T.DOWN_5.percent, -5)
        XCTAssertNil(T.VOLUME_X3.percent)
    }

    func testSuggestedThresholdRoundsToThreeSignificantDigits() {
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(63_412.57), "63400")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(1.2345), "1.23")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(1.2345, decimalSeparator: ","), "1,23")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(0.00012345), "0.000123")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(187.34), "187")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(1.0002), "1")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(nil), "")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(0), "")
        XCTAssertEqual(AlarmTemplates.suggestedThresholdText(.nan), "")
    }

    func testSuggestedThresholdReadsBackThroughTheParser() throws {
        for price in [63_412.57, 1.2345, 0.123456, 0.00012345, 187.34, 2_345.6, 98_765_432.1] {
            for sep: Character in [".", ","] {
                let text = AlarmTemplates.suggestedThresholdText(price, decimalSeparator: sep)
                let parsed = try XCTUnwrap(ThresholdParser.parse(text, decimalSeparator: sep, priceHint: price))
                let expected = try XCTUnwrap(Double(text.replacingOccurrences(of: ",", with: ".")))
                XCTAssertEqual(parsed, expected, accuracy: 1e-12)
                // Ohne Kurs-Hinweis ebenso (Dezimalzeichen der Region)
                XCTAssertEqual(try XCTUnwrap(ThresholdParser.parse(text, decimalSeparator: sep)), parsed, accuracy: 1e-12)
            }
        }
    }

    func testAdvancedOpensForEverythingButPlainPriceAlarms() {
        XCTAssertFalse(AlarmTemplates.opensAdvanced(condition: .PRICE_ABOVE, currency: nil))
        XCTAssertFalse(AlarmTemplates.opensAdvanced(condition: .PRICE_BELOW, currency: ""))
        XCTAssertTrue(AlarmTemplates.opensAdvanced(condition: .PRICE_ABOVE, currency: "CHF"))
        for c in AlarmCondition.allCases where !c.isPriceThreshold {
            XCTAssertTrue(AlarmTemplates.opensAdvanced(condition: c, currency: nil))
        }
    }

    // MARK: Nur neue Hochs/Tiefs (Abstand 0)

    private func decide(_ side: NearExtreme.Side = .high, price: Double, threshold: Double = 0,
                        armed: Bool = true, lastLevel: Double? = nil) -> NearExtreme.Decision {
        NearExtreme.decide(side: side, price: price, range: NearExtreme.Range(high: 100, low: 50),
                           thresholdPercent: threshold, armed: armed, lastLevel: lastLevel,
                           inCooldown: false, lastTriggeredAt: 0, now: 1_000_000_000)
    }

    func testNewOnlyFiresOnlyOnNewExtremes() {
        // Knapp unter dem Hoch meldet nicht (keine Annäherung) …
        XCTAssertEqual(decide(price: 99.99), .idle)
        XCTAssertEqual(decide(price: 100), .idle)
        // … ein neues Hoch bzw. Tief schon
        if case .fire(let isNew, _, let extreme, let level) = decide(price: 100.5) {
            XCTAssertTrue(isNew)
            XCTAssertEqual(extreme, 100, accuracy: 1e-9)
            XCTAssertEqual(level, 100.5, accuracy: 1e-9)
        } else { XCTFail("kein neues Hoch") }
        if case .fire(let isNew, _, _, _) = decide(.low, price: 49) { XCTAssertTrue(isNew) } else { XCTFail("kein neues Tief") }
        // Gemeldet: weiteres Hoch erst ab 0,5 % über der Marke, wieder scharf ab 0,5 % unter dem Hoch
        XCTAssertEqual(decide(price: 101, armed: false, lastLevel: 100.8), .idle)
        if case .fire = decide(price: 101.4, armed: false, lastLevel: 100.8) {} else { XCTFail("weiteres Hoch") }
        XCTAssertEqual(decide(price: 99.6, armed: false, lastLevel: 100.5), .idle)
        XCTAssertEqual(decide(price: 99.4, armed: false, lastLevel: 100.5), .rearm)
        XCTAssertTrue(NearExtreme.isNewOnly(NearExtreme.newOnlyDistance))
        XCTAssertFalse(NearExtreme.isNewOnly(2))
        // Negativer Abstand bleibt ungültig
        XCTAssertEqual(decide(price: 120, threshold: -1), .idle)
    }
}
