import XCTest
@testable import CryptoChecker

/// Wie `AlarmSignalTest.kt` (soweit iOS die Werte kennt), `PriceColorChoiceTest.kt` und
/// `ActivitySensitivityTest.kt`.
final class AlarmSignalTests: XCTestCase {

    func testFromNameUnknownOrMissingIsSystem() {
        XCTAssertEqual(AlarmSignal.from(name: nil), .SYSTEM)
        XCTAssertEqual(AlarmSignal.from(name: "LOUD"), .SYSTEM)
        XCTAssertEqual(AlarmSignal.from(name: "VIBRATE"), .VIBRATE)
        for signal in AlarmSignal.allCases { XCTAssertEqual(AlarmSignal.from(name: signal.rawValue), signal) }
    }

    func testAndroidValuesMapToTheNearestIosValue() {
        XCTAssertEqual(AlarmSignal.SYSTEM.ios, .SYSTEM)
        XCTAssertEqual(AlarmSignal.SOUND_VIBRATE.ios, .SYSTEM)
        XCTAssertEqual(AlarmSignal.SOUND.ios, .SYSTEM)
        XCTAssertEqual(AlarmSignal.VIBRATE.ios, .SILENT)
        XCTAssertEqual(AlarmSignal.SILENT.ios, .SILENT)
        XCTAssertEqual(AlarmSignal.iosChoices, [.SYSTEM, .SILENT])
    }

    func testOnlySoundSignalsWithAlarmSoundPlay() {
        XCTAssertTrue(AlarmSignal.SYSTEM.playsSound(alarmSound: true))
        XCTAssertFalse(AlarmSignal.SYSTEM.playsSound(alarmSound: false))
        XCTAssertTrue(AlarmSignal.SOUND_VIBRATE.playsSound())
        XCTAssertFalse(AlarmSignal.VIBRATE.playsSound())
        XCTAssertFalse(AlarmSignal.SILENT.playsSound())
    }
}

final class PriceColorChoiceTests: XCTestCase {

    func testRoundTripForEveryChoice() {
        for choice in PriceColorChoice.allCases {
            XCTAssertEqual(PriceColorChoice.of(scheme: choice.scheme, inverted: choice.inverted), choice)
        }
    }

    func testEveryStoredCombinationHasOneChoice() {
        var seen = Set<PriceColorChoice>()
        for scheme in PriceColorScheme.allCases {
            for inverted in [false, true] { seen.insert(PriceColorChoice.of(scheme: scheme, inverted: inverted)) }
        }
        XCTAssertEqual(PriceColorScheme.allCases.count * 2, PriceColorChoice.allCases.count)
        XCTAssertEqual(seen.count, PriceColorChoice.allCases.count)
    }

    func testDefaultIsGreenUp() {
        XCTAssertEqual(PriceColorChoice.of(scheme: .default, inverted: false), .GREEN_UP)
        XCTAssertFalse(PriceColorChoice.GREEN_UP.inverted)
    }

    func testRedUpSwapsTheColorsOfGreenRed() {
        let c = PriceColorChoice.RED_UP
        XCTAssertEqual(c.scheme, .GREEN_RED)
        // Steigend bekommt die Fallend-Farbe des Schemas
        XCTAssertEqual(PriceColorScheme.GREEN_RED.downHex(dark: false), c.scheme.upHex(dark: false, inverted: c.inverted))
        XCTAssertNotEqual(PriceColorChoice.GREEN_UP.scheme.upHex(dark: true), c.scheme.upHex(dark: true, inverted: c.inverted))
    }

    func testBlueAndOrangeUseTheColorBlindScheme() {
        XCTAssertEqual(PriceColorChoice.BLUE_UP.scheme, .BLUE_ORANGE)
        XCTAssertEqual(PriceColorChoice.ORANGE_UP.scheme, .BLUE_ORANGE)
        XCTAssertTrue(PriceColorChoice.ORANGE_UP.inverted)
    }
}

final class ActivitySensitivityTests: XCTestCase {

    private let now: Int64 = 1_700_000_000_000

    private func signal(_ kind: ActivitySignalKind, _ value: Double, factor: Double? = nil) -> ActivitySignal {
        ActivitySignal(kind: kind, severity: .NOTABLE, value: value, factor: factor, seenAt: now)
    }

    func testNormalIsTodaysThresholds() {
        XCTAssertEqual(SignalThresholds.of(.NORMAL), SignalThresholds.normal)
        XCTAssertEqual(SignalThresholds.normal.volumeRatio, ActivityAnalyzer.signalVolumeRatio)
        XCTAssertEqual(SignalThresholds.normal.priceZ, ActivityAnalyzer.signalPriceZ)
    }

    func testLessScalesAllThresholdsByOneAndAHalf() {
        let t = SignalThresholds.of(.LESS)
        let n = SignalThresholds.normal
        XCTAssertEqual(t.priceZ, n.priceZ * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.priceMinMovePercent, n.priceMinMovePercent * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.priceStrongZ, n.priceStrongZ * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.priceStrongMovePercent, n.priceStrongMovePercent * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.volumeRatio, n.volumeRatio * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.volumeStrongRatio, n.volumeStrongRatio * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.fundingPercent, n.fundingPercent * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.fundingStrongPercent, n.fundingStrongPercent * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.oiPercent, n.oiPercent * 1.5, accuracy: 1e-9)
        XCTAssertEqual(t.oiStrongPercent, n.oiStrongPercent * 1.5, accuracy: 1e-9)
        XCTAssertEqual(ActivitySensitivity.LESS.maxCardCoins, 3)
    }

    func testMoreScalesByThreeQuarters() {
        let t = SignalThresholds.of(.MORE)
        XCTAssertEqual(t.volumeRatio, SignalThresholds.normal.volumeRatio * 0.75, accuracy: 1e-9)
        XCTAssertEqual(t.oiStrongPercent, SignalThresholds.normal.oiStrongPercent * 0.75, accuracy: 1e-9)
        XCTAssertNil(ActivitySensitivity.MORE.maxCardCoins)
        XCTAssertNil(ActivitySensitivity.NORMAL.maxCardCoins)
    }

    func testVolumeDependsOnSensitivity() {
        let spike = signal(.VOLUME_SPIKE, 6)
        XCTAssertEqual(SignalThresholds.of(.NORMAL).severity(of: spike), .NOTABLE)
        XCTAssertNil(SignalThresholds.of(.LESS).severity(of: spike)) // ab 7.5×
        XCTAssertEqual(SignalThresholds.of(.MORE).severity(of: signal(.VOLUME_SPIKE, 4)), .NOTABLE) // ab 3.75×
        XCTAssertEqual(SignalThresholds.of(.MORE).severity(of: signal(.VOLUME_SPIKE, 8)), .STRONG) // ab 7.5×
    }

    func testPriceMoveNeedsZAndMove() {
        let move = signal(.PRICE_MOVE, 3, factor: 4)
        XCTAssertEqual(SignalThresholds.normal.severity(of: move), .NOTABLE)
        XCTAssertNil(SignalThresholds.of(.LESS).severity(of: move))
        XCTAssertEqual(SignalThresholds.of(.MORE).severity(of: signal(.PRICE_MOVE, -2, factor: 3)), .NOTABLE)
    }

    func testFundingAndOpenInterestBothSigns() {
        let more = SignalThresholds.of(.MORE)
        XCTAssertEqual(more.severity(of: signal(.FUNDING_EXTREME, -0.08)), .NOTABLE)
        XCTAssertNil(SignalThresholds.normal.severity(of: signal(.FUNDING_EXTREME, -0.08)))
        XCTAssertEqual(more.severity(of: signal(.OPEN_INTEREST_JUMP, -19, factor: 45)), .STRONG)
        XCTAssertNil(SignalThresholds.of(.LESS).severity(of: signal(.OPEN_INTEREST_JUMP, 20)))
    }

    func testSignalsUseTheSensitivity() {
        let stats = HourStats(movePercent: 0.1, zScore: 0.2, volumeRatio: 6, candleOpenTime: now)
        XCTAssertEqual(ActivityAnalyzer.signals(stats: stats, fundingPercent: nil, oiChangePercent: nil, oiMinutes: nil,
                                                now: now).count, 1)
        XCTAssertTrue(ActivityAnalyzer.signals(stats: stats, fundingPercent: nil, oiChangePercent: nil, oiMinutes: nil,
                                               now: now, sensitivity: .LESS).isEmpty)
    }

    func testApplySensitivityRefiltersStoredSignals() {
        let stored = [signal(.VOLUME_SPIKE, 6), signal(.FUNDING_EXTREME, 0.25)]
        let less = ActivityAnalyzer.applySensitivity(stored, .LESS)
        XCTAssertEqual(less.map(\.kind), [.FUNDING_EXTREME])
        XCTAssertEqual(less.first?.severity, .NOTABLE)
        XCTAssertEqual(ActivityAnalyzer.applySensitivity(stored, .NORMAL).count, 2)
    }

    func testFromNameUnknownOrMissingIsNormal() {
        XCTAssertEqual(ActivitySensitivity.from(name: nil), .NORMAL)
        XCTAssertEqual(ActivitySensitivity.from(name: "SOMETHING"), .NORMAL)
        XCTAssertEqual(ActivitySensitivity.from(name: "LESS"), .LESS)
        XCTAssertEqual(ActivitySensitivity.from(name: "MORE"), .MORE)
    }
}
