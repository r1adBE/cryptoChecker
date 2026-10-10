import XCTest
@testable import CryptoChecker

/// Wie `AlarmSignalTest.kt` (soweit iOS die Werte kennt), `PriceColorSchemeTest.kt` und
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

/// Wie `PriceColorSchemeTest.kt`: Werte, Reihenfolge, Tausch und WCAG-Kontrast — normal ≥ 4.5:1
/// (AA), hoher Kontrast ≥ 7:1 (AAA) auf allen hellen/dunklen Flächen und in der 14 %-Pille.
final class PriceColorSchemeTests: XCTestCase {

    private let lightSurfaces: [UInt32] = [0xFFFFFF, 0xF9F9F9, 0xEEEEEE, 0xE4E4E4]
    private let darkSurfaces: [UInt32] = [0x121212, 0x131313, 0x1F1F1F]

    private func channel(_ c: UInt32) -> Double {
        let s = Double(c) / 255
        return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
    }

    private func luminance(_ rgb: UInt32) -> Double {
        let r = 0.2126 * channel((rgb >> 16) & 0xFF)
        let g = 0.7152 * channel((rgb >> 8) & 0xFF)
        let b = 0.0722 * channel(rgb & 0xFF)
        return r + g + b
    }

    private func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a)
        let lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// Pille: Kursfarbe mit 14 % Deckkraft über der Karte (wie ChangePill).
    private func pill(_ color: UInt32, card: UInt32) -> UInt32 {
        var result: UInt32 = 0
        for shift: UInt32 in [16, 8, 0] {
            let f = Double((color >> shift) & 0xFF)
            let b = Double((card >> shift) & 0xFF)
            result |= UInt32((0.14 * f + 0.86 * b).rounded()) << shift
        }
        return result
    }

    func testValuesAsSpecified() {
        XCTAssertEqual(PriceColorScheme.GREEN_RED.upHex(dark: false), 0x0A6D3E)
        XCTAssertEqual(PriceColorScheme.BLUE_ORANGE.downHex(dark: false), 0x9F4300)
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.upHex(dark: false), 0x4A6410)
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.upHex(dark: true), 0x8FB532)
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.downHex(dark: false), 0xAA1850)
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.downHex(dark: true), 0xFF6A96)
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.upHex(dark: false, highContrast: true), 0x334509)
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.downHex(dark: true, highContrast: true), 0xFFB8CE)
    }

    func testOrderAndDefault() {
        XCTAssertEqual(PriceColorScheme.allCases, [.GREEN_RED, .TRADITIONAL, .BLUE_ORANGE])
        XCTAssertEqual(PriceColorScheme.default, .GREEN_RED)
        XCTAssertEqual(PriceColorScheme(rawValue: "TRADITIONAL"), .TRADITIONAL)
        XCTAssertNil(PriceColorScheme(rawValue: "BLUE_YELLOW"))
    }

    func testLabelKeys() {
        XCTAssertEqual(PriceColorScheme.GREEN_RED.labelKey, "price_style_fresh")
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.labelKey, "price_style_traditional")
        XCTAssertEqual(PriceColorScheme.BLUE_ORANGE.labelKey, "price_style_color_vision")
    }

    func testSwapOnlySwapsTheColors() {
        for scheme in PriceColorScheme.allCases {
            for dark in [false, true] {
                for hc in [false, true] {
                    XCTAssertEqual(scheme.upHex(dark: dark, highContrast: hc, inverted: true), scheme.downHex(dark: dark, highContrast: hc))
                    XCTAssertEqual(scheme.downHex(dark: dark, highContrast: hc, inverted: true), scheme.upHex(dark: dark, highContrast: hc))
                    XCTAssertNotEqual(scheme.upHex(dark: dark, highContrast: hc), scheme.downHex(dark: dark, highContrast: hc))
                }
            }
        }
    }

    func testContrastOnAllSurfacesAndInThePill() {
        for scheme in PriceColorScheme.allCases {
            for hc in [false, true] {
                for dark in [false, true] {
                    let required = hc ? 7.0 : 4.5
                    let card: UInt32 = dark ? 0x1F1F1F : 0xEEEEEE
                    for color in [scheme.upHex(dark: dark, highContrast: hc), scheme.downHex(dark: dark, highContrast: hc)] {
                        let backgrounds = (dark ? darkSurfaces : lightSurfaces) + [pill(color, card: card)]
                        for bg in backgrounds {
                            let ratio = contrast(color, bg)
                            XCTAssertGreaterThanOrEqual(ratio, required,
                                                        "\(scheme.rawValue) hc=\(hc) dark=\(dark) \(String(color, radix: 16)) on \(String(bg, radix: 16))")
                        }
                    }
                }
            }
        }
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
