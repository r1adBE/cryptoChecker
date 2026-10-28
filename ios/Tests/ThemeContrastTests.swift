import Foundation
import XCTest
@testable import CryptoChecker

/// WCAG-Kontrast der Themenfarben (`ColorTokens`, `AccentColor`) — wie `ThemeContrastTest.kt` (Android) und
/// android/tools/check_theme_contrast.py: Haupt-, Neben- und Warntext in Hell und Dunkel auf allen Flächen
/// ≥ 4.5:1; auf den Auswahl-Tönungen (`accent.tint`, bis 0.16 mit Schrift) Schrift ≥ 4.5:1 und Symbole in
/// primary/outline ≥ 3:1.
final class ThemeContrastTests: XCTestCase {

    /// Höchste Deckkraft einer Tönung, auf der Schrift steht (Auswahl-Listen, Hinweiskarten, Merkliste-Zeile).
    private static let textTintMax = 0.16
    /// Tönung der gewählten Zeile (Coin-Liste, Android DropdownField).
    private static let rowTint = 0.3
    private static let warningText = ColorTokens.Pair(light: 0x8A5300, dark: 0xFFC94D)

    func testTextReachesAAOnAllSurfaces() {
        for dark in [false, true] {
            let surfaces: [UInt32] = Self.surfaces(dark: dark)
            let texts: [UInt32] = [ColorTokens.onSurface.hex(dark: dark), ColorTokens.onSurfaceVariant.hex(dark: dark),
                                   Self.warningText.hex(dark: dark)]
            for bg in surfaces {
                for fg in texts {
                    let r = Self.ratio(fg, bg)
                    XCTAssertGreaterThanOrEqual(r, 4.5, "dark=\(dark) \(Self.hex(fg)) auf \(Self.hex(bg))")
                }
            }
        }
    }

    func testTextAndSymbolsOnSelectionTint() {
        for accent in AccentColor.allCases {
            for dark in [false, true] {
                checkTint(accent: accent, dark: dark)
            }
        }
    }

    private func checkTint(accent: AccentColor, dark: Bool) {
        let primary: UInt32 = accent.primaryHex(dark: dark)
        let lowest: UInt32 = dark ? 0x0E0E0E : 0xFFFDFA
        let bases: [UInt32] = [lowest, ColorTokens.background.hex(dark: dark),
                               ColorTokens.surfaceContainerLow.hex(dark: dark),
                               ColorTokens.surfaceContainer.hex(dark: dark)]
        for base in bases {
            // wie `AccentColor.tint`: hell primary · a, dunkel #E2E2E2 · a · 0.8
            let bg: UInt32 = dark
                ? Self.over(0xE2E2E2, alpha: Self.textTintMax * 0.8, on: base)
                : Self.over(primary, alpha: Self.textTintMax, on: base)
            let label = "\(accent.rawValue) dark=\(dark) auf \(Self.hex(bg))"
            for fg in [ColorTokens.onSurface.hex(dark: dark), ColorTokens.onSurfaceVariant.hex(dark: dark)] {
                XCTAssertGreaterThanOrEqual(Self.ratio(fg, bg), 4.5, "Schrift " + label)
            }
            for fg in [primary, ColorTokens.outline.hex(dark: dark)] {
                XCTAssertGreaterThanOrEqual(Self.ratio(fg, bg), 3, "Symbol " + label)
            }
        }
    }

    /// Gewählte Zeile mit Tönung 0.3 (Coin-Liste): Schrift onSurface ≥ 4.5:1, Stern voll (primary) bzw. leer
    /// (onSurfaceVariant) ≥ 3:1 — outline hielt dort unter 3:1. Wie Android `ThemeContrastTest`.
    func testSelectedRowTintWithStar() {
        for accent in AccentColor.allCases {
            for dark in [false, true] {
                let primary: UInt32 = accent.primaryHex(dark: dark)
                let lowest: UInt32 = dark ? 0x0E0E0E : 0xFFFDFA
                let bases: [UInt32] = [lowest, ColorTokens.background.hex(dark: dark),
                                       ColorTokens.surfaceContainerLow.hex(dark: dark),
                                       ColorTokens.surfaceContainer.hex(dark: dark)]
                for base in bases {
                    let bg: UInt32 = dark
                        ? Self.over(0xE2E2E2, alpha: Self.rowTint * 0.8, on: base)
                        : Self.over(primary, alpha: Self.rowTint, on: base)
                    let label = "\(accent.rawValue) dark=\(dark) auf \(Self.hex(bg))"
                    XCTAssertGreaterThanOrEqual(Self.ratio(ColorTokens.onSurface.hex(dark: dark), bg), 4.5, label)
                    for fg in [primary, ColorTokens.onSurfaceVariant.hex(dark: dark)] {
                        XCTAssertGreaterThanOrEqual(Self.ratio(fg, bg), 3, "Stern " + label)
                    }
                }
            }
        }
    }

    // MARK: - Hilfen

    /// Lowest … Highest, dazu hell die tiefe Widget-Fläche, dunkel die Auswahlfläche #333333.
    private static func surfaces(dark: Bool) -> [UInt32] {
        let lowest: UInt32 = dark ? 0x0E0E0E : 0xFFFDFA
        let extra: UInt32 = dark ? 0x333333 : ColorTokens.widgetSurfaceDeep.light
        let tokens: [ColorTokens.Pair] = [ColorTokens.background, ColorTokens.surfaceContainerLow,
                                          ColorTokens.surfaceContainer, ColorTokens.surfaceContainerHigh,
                                          ColorTokens.surfaceContainerHighest]
        return [lowest, extra] + tokens.map { $0.hex(dark: dark) }
    }

    private static func channel(_ c: UInt32) -> Double {
        let s = Double(c) / 255
        return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
    }

    private static func luminance(_ rgb: UInt32) -> Double {
        let r = 0.2126 * channel((rgb >> 16) & 0xFF)
        let g = 0.7152 * channel((rgb >> 8) & 0xFF)
        let b = 0.0722 * channel(rgb & 0xFF)
        return r + g + b
    }

    private static func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a)
        let lb = luminance(b)
        return (Swift.max(la, lb) + 0.05) / (Swift.min(la, lb) + 0.05)
    }

    /// `fg` mit Deckkraft `alpha` über dem deckenden `bg` (je Kanal, auf 8 Bit gerundet).
    private static func over(_ fg: UInt32, alpha: Double, on bg: UInt32) -> UInt32 {
        var out: UInt32 = 0
        for shift: UInt32 in [16, 8, 0] {
            let f = Double((fg >> shift) & 0xFF)
            let b = Double((bg >> shift) & 0xFF)
            let v = UInt32((f * alpha + b * (1 - alpha)).rounded())
            out |= v << shift
        }
        return out
    }

    private static func hex(_ c: UInt32) -> String { String(format: "#%06X", c) }
}
