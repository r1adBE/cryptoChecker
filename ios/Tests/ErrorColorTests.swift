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
        XCTAssertEqual(ColorTokens.error.light, 0xB13419)
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

    /// Fehlertext auf der eigenen Fehlerfläche («Löschen», Fehlermeldungen: `error` · 0.08 … 0.12) über Seite,
    /// Blatt und Menü ≥ 4.5:1 in Hell und Dunkel — hell lag #B8361A dort bei 4.25:1.
    func testErrorTextReachesAAOnErrorFill() {
        for dark in [false, true] {
            let error: UInt32 = ColorTokens.error.hex(dark: dark)
            let lowest: UInt32 = dark ? 0x0E0E0E : 0xFFFDFA
            let bases: [UInt32] = [lowest, ColorTokens.background.hex(dark: dark),
                                   ColorTokens.surfaceContainerLow.hex(dark: dark),
                                   ColorTokens.surfaceContainer.hex(dark: dark)]
            for base in bases {
                for alpha in [0.08, 0.10, 0.12] {
                    let fill = over(error, alpha: alpha, on: base)
                    XCTAssertGreaterThanOrEqual(ratio(error, fill), 4.5, "dark=\(dark) \(String(fill, radix: 16))")
                }
            }
        }
    }

    /// `fg` mit Deckkraft `alpha` über dem deckenden `bg` (je Kanal, auf 8 Bit gerundet).
    private func over(_ fg: UInt32, alpha: Double, on bg: UInt32) -> UInt32 {
        var out: UInt32 = 0
        for shift: UInt32 in [16, 8, 0] {
            let f = Double((fg >> shift) & 0xFF)
            let b = Double((bg >> shift) & 0xFF)
            out |= UInt32((f * alpha + b * (1 - alpha)).rounded()) << shift
        }
        return out
    }

    func testDarkErrorReachesAAOnBackground() {
        let error = ColorTokens.error.dark
        for bg in [ColorTokens.background.dark, ColorTokens.surfaceContainerLow.dark, ColorTokens.surfaceContainer.dark] {
            XCTAssertGreaterThanOrEqual(ratio(error, bg), 4.5, String(bg, radix: 16))
        }
    }
}
