import XCTest
@testable import CryptoChecker

/// Meldungen unten (`SnackbarPalette`, `snackbarSurface`): Art → Farbe, Kontrast auf `containerHighest` und die
/// gemeinsamen Testfälle `snackbar_colors.json` — wie Android `SnackbarColorsTest` und `SnackbarColorsParityTest`.
final class SnackbarColorsTests: XCTestCase {

    private typealias S = TestSupport

    private let accents: [AccentColor?] = AccentColor.allCases.map { Optional($0) } + [nil]

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

    private func hex(_ color: UInt32?) -> String? { color.map { String(format: "%06X", $0) } }

    private func kind(_ name: String) -> SnackbarKind? {
        switch name {
        case "SUCCESS": .success
        case "ERROR": .error
        case "INFO": .info
        default: nil
        }
    }

    /// Erfolg ist keine Kursrichtung: bei «Farben tauschen» (Rot = steigend) bleibt er die Steigend-Farbe.
    func testSuccessStaysRisingColourWhenSwapped() {
        for scheme in PriceColorScheme.allCases {
            for accent in accents {
                for dark in [false, true] {
                    for hc in [false, true] {
                        let success = SnackbarPalette.toneHex(.success, scheme: scheme, dark: dark, highContrast: hc,
                                                              accent: accent)
                        let key = "\(scheme.rawValue)/\(accent?.rawValue ?? "-")/dark=\(dark)/hc=\(hc)"
                        let rising = scheme.upHex(dark: dark, highContrast: hc, inverted: false, accent: accent)
                        let swapped = scheme.upHex(dark: dark, highContrast: hc, inverted: true, accent: accent)
                        XCTAssertEqual(success, rising, key)
                        XCTAssertNotEqual(success, swapped, key)
                    }
                }
            }
        }
        // «Frisch» getauscht: steigend wäre rot, Erfolg bleibt grün
        let fresh = SnackbarPalette.toneHex(.success, scheme: .GREEN_RED, dark: false, highContrast: false, accent: nil)
        XCTAssertEqual(fresh, 0x127248)
    }

    /// Fehler: Fehlerfarbe der App; mit Farbsehschwäche-Schema dessen Fallend-Ton (Orange), nie getauscht.
    func testErrorUsesErrorColourOrColourVisionTone() {
        for scheme in PriceColorScheme.allCases {
            for accent in accents {
                for dark in [false, true] {
                    for hc in [false, true] {
                        let error = SnackbarPalette.toneHex(.error, scheme: scheme, dark: dark, highContrast: hc,
                                                            accent: accent)
                        let expected = SnackbarPalette.colorVision(scheme)
                            ? scheme.downHex(dark: dark, highContrast: hc, inverted: false, accent: accent)
                            : (dark ? ColorTokens.error.dark : ColorTokens.error.light)
                        XCTAssertEqual(error, expected, "\(scheme.rawValue)/dark=\(dark)/hc=\(hc)")
                    }
                }
            }
        }
        let colorVision = PriceColorScheme.allCases.filter(SnackbarPalette.colorVision)
        XCTAssertEqual(colorVision, [.BLUE_ORANGE, .CALM_COLOR_VISION])
        // Farbsehschwäche: Blau statt Grün, Orange statt Rot
        let cvd = PriceColorScheme.BLUE_ORANGE
        let light = { (kind: SnackbarKind) in
            SnackbarPalette.toneHex(kind, scheme: cvd, dark: false, highContrast: false, accent: nil)
        }
        XCTAssertEqual(light(.success), 0x064E93)
        XCTAssertEqual(light(.error), 0xB73900)
    }

    func testInfoHasNoTone() {
        for scheme in PriceColorScheme.allCases {
            XCTAssertNil(SnackbarPalette.toneHex(.info, scheme: scheme, dark: false, highContrast: false, accent: nil))
            XCTAssertNil(SnackbarPalette.toneHex(.info, scheme: scheme, dark: true, highContrast: true, accent: nil))
        }
        XCTAssertNil(SnackbarKind.info.symbol)
        XCTAssertEqual(SnackbarKind.success.symbol, "checkmark.circle.fill")
        XCTAssertEqual(SnackbarKind.error.symbol, "exclamationmark.circle.fill")
    }

    /// Text `onSurface` und Aktion (Akzent, auch hoher Kontrast) ≥ 4.5:1 auf `containerHighest`; Rahmen und Symbol von
    /// Erfolg/Fehler als Grafik ≥ 3:1 (WCAG 1.4.11).
    func testContrastOnSurface() {
        for dark in [false, true] {
            let bg = dark ? ColorTokens.surfaceContainerHighest.dark : ColorTokens.surfaceContainerHighest.light
            let text = dark ? ColorTokens.onSurface.dark : ColorTokens.onSurface.light
            XCTAssertGreaterThanOrEqual(ratio(text, bg), 4.5, "onSurface dark=\(dark)")
            for accent in AccentColor.allCases {
                for hc in [false, true] {
                    let primary = accent.primaryHex(dark: dark, highContrast: hc)
                    XCTAssertGreaterThanOrEqual(ratio(primary, bg), 4.5, "\(accent.rawValue) dark=\(dark) hc=\(hc)")
                    for scheme in PriceColorScheme.allCases {
                        for kind in [SnackbarKind.success, .error] {
                            let tone = SnackbarPalette.toneHex(kind, scheme: scheme, dark: dark, highContrast: hc,
                                                               accent: accent) ?? 0
                            let key = "\(scheme.rawValue) \(kind) \(accent.rawValue) dark=\(dark) hc=\(hc)"
                            XCTAssertGreaterThanOrEqual(ratio(tone, bg), 3.0, key)
                        }
                    }
                }
            }
        }
        // Fläche passend zum Thema: dunkel dunkel, hell hell
        XCTAssertEqual(ColorTokens.surfaceContainerHighest.dark, 0x353535)
        XCTAssertEqual(ColorTokens.surfaceContainerHighest.light, 0xE5E2DB)
    }

    func testBulkAddedKind() {
        XCTAssertEqual(SnackbarKind.bulkAdded(added: 3, refused: 2), .success)
        XCTAssertEqual(SnackbarKind.bulkAdded(added: 0, refused: 2), .error)
        XCTAssertEqual(SnackbarKind.bulkAdded(added: 0, refused: 0), .info)
    }

    /// Gemeinsame Testfälle `snackbar_colors.json` (Android: `SnackbarColorsParityTest.kt`).
    func testParityCases() throws {
        let data = try S.fixture("snackbar_colors")
        let cases = S.list(data["cases"])
        XCTAssertEqual(cases.count, 3 * PriceColorScheme.allCases.count * (AccentColor.allCases.count + 1) * 4)
        for c in cases {
            let kind = try XCTUnwrap(kind(c["kind"] as? String ?? ""))
            let scheme = try XCTUnwrap(PriceColorScheme(rawValue: c["scheme"] as? String ?? ""))
            let accent: AccentColor? = (c["accent"] as? String).flatMap { AccentColor(rawValue: $0) }
            let dark = c["dark"] as? Bool ?? false
            let hc = c["highContrast"] as? Bool ?? false
            let key = "\(kind)/\(scheme.rawValue)/\(accent?.rawValue ?? "-")/dark=\(dark)/hc=\(hc)"
            let tone = SnackbarPalette.toneHex(kind, scheme: scheme, dark: dark, highContrast: hc, accent: accent)
            XCTAssertEqual(hex(tone), c["tone"] as? String, key)
        }
        let names = data["colorVisionSchemes"] as? [String] ?? []
        XCTAssertEqual(names, PriceColorScheme.allCases.filter(SnackbarPalette.colorVision).map(\.rawValue).sorted())
    }
}
