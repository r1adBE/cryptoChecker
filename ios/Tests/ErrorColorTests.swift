import XCTest
@testable import CryptoChecker

/// Fehlerfarben: hell wie dunkel warmes Orange-Rot — wie Android `ErrorContrastTest`.
/// Liest die echten Token aus `ColorTokens`, keine Kopie der Werte für die Kontrastprüfung.
final class ErrorColorTests: XCTestCase {

    private func channel(_ c: UInt32) -> Double {
        let s = Double(c) / 255
        return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
    }

    private func luminance(_ rgb: UInt32) -> Double {
        0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF)
    }

    private func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    func testErrorTokensAsAndroid() {
        XCTAssertEqual(ColorTokens.error.light, 0xB8361A)
        XCTAssertEqual(ColorTokens.error.dark, 0xFF5833)
    }

    /// Fehlertext ≥ 4.5:1 auf jeder hellen Fläche (Papier bis dunkelste Karte), Weiss darauf ebenso.
    func testLightErrorReachesAAOnAllLightSurfaces() {
        let error = ColorTokens.error.light
        let surfaces: [UInt32] = [0xFFFDFA, ColorTokens.background.light, ColorTokens.surfaceContainerLow.light,
                                  ColorTokens.surfaceContainer.light, ColorTokens.surfaceContainerHigh.light,
                                  ColorTokens.surfaceContainerHighest.light, ColorTokens.widgetSurfaceDeep.light]
        for bg in surfaces {
            XCTAssertGreaterThanOrEqual(ratio(error, bg), 4.5, String(bg, radix: 16))
        }
        XCTAssertGreaterThanOrEqual(ratio(0xFFFFFF, error), 4.5)
    }

    func testDarkErrorReachesAAOnBackground() {
        let error = ColorTokens.error.dark
        for bg in [ColorTokens.background.dark, ColorTokens.surfaceContainerLow.dark, ColorTokens.surfaceContainer.dark] {
            XCTAssertGreaterThanOrEqual(ratio(error, bg), 4.5, String(bg, radix: 16))
        }
    }
}
