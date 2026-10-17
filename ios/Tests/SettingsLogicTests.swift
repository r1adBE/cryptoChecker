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
/// (AA), hoher Kontrast ≥ 7:1 (AAA) auf allen hellen/dunklen Flächen (Änderungen als Schrift ohne Pille).
final class PriceColorSchemeTests: XCTestCase {

    private let lightSurfaces: [UInt32] = [0xFFFFFF, 0xF9F9F9, 0xF3F3F3, 0xEEEEEE, 0xE8E8E8, 0xE4E4E4, 0xE2E2E2]
    private let darkSurfaces: [UInt32] = [0x0E0E0E, 0x121212, 0x131313, 0x1B1B1B, 0x1F1F1F, 0x2A2A2A, 0x333333, 0x353535]

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

    func testValuesAsSpecified() {
        // Feste Börsenfarben (Entscheid 18. Oktober 2026): hell, dunkel, je normal und hoher Kontrast
        assertTones(.GREEN_RED, up: true, [0x005A30, 0x18EA96, 0x00421E, 0x81FFBA])
        assertTones(.GREEN_RED, up: false, [0xCC0015, 0xFF6F69, 0x960000, 0xFFB1AB])
        assertTones(.TRADITIONAL, up: true, [0x033C00, 0x87FF7E, 0x002100, 0xE4FFE4])
        assertTones(.TRADITIONAL, up: false, [0x990000, 0xFF9C99, 0x630000, 0xF9D2CD])
        assertTones(.BLUE_ORANGE, up: true, [0x003372, 0x96D8FF, 0x002A5A, 0xC9E7FF])
        assertTones(.BLUE_ORANGE, up: false, [0xAE4200, 0xED810C, 0x7B3300, 0xFFB469])
        // «Ruhig Farbsehschwäche»: die früheren Werte von «Farbsehschwäche»
        XCTAssertEqual(PriceColorScheme.CALM_COLOR_VISION.upHex(dark: false), 0x1460AB)
        XCTAssertEqual(PriceColorScheme.CALM_COLOR_VISION.downHex(dark: false), 0x9F4300)
        XCTAssertEqual(PriceColorScheme.CALM_COLOR_VISION.downHex(dark: false, highContrast: true), 0x6C2E00)
    }

    /// Hell, dunkel, hell hoher Kontrast, dunkel hoher Kontrast.
    private func assertTones(_ scheme: PriceColorScheme, up: Bool, _ expected: [UInt32],
                             file: StaticString = #filePath, line: UInt = #line) {
        let variants: [(dark: Bool, hc: Bool)] = [(false, false), (true, false), (false, true), (true, true)]
        for (index, v) in variants.enumerated() {
            let actual: UInt32 = up ? scheme.upHex(dark: v.dark, highContrast: v.hc) : scheme.downHex(dark: v.dark, highContrast: v.hc)
            XCTAssertEqual(actual, expected[index], "\(scheme.rawValue) up=\(up) \(v)", file: file, line: line)
        }
    }

    func testOrderAndDefault() {
        XCTAssertEqual(PriceColorScheme.allCases, [.CALM, .GREEN_RED, .TRADITIONAL, .BLUE_ORANGE, .CALM_COLOR_VISION])
        XCTAssertEqual(PriceColorScheme.default, .CALM)
        XCTAssertEqual(PriceColorScheme.CALM.upHex(dark: false), 0x396E54)
        XCTAssertEqual(PriceColorScheme.CALM.upHex(dark: true), 0x61AE87)
        XCTAssertEqual(PriceColorScheme.CALM.downHex(dark: false), 0x771D23)
        XCTAssertEqual(PriceColorScheme.CALM.downHex(dark: true), 0xFFAFA8)
        XCTAssertEqual(PriceColorScheme(rawValue: "TRADITIONAL"), .TRADITIONAL)
        XCTAssertNil(PriceColorScheme(rawValue: "BLUE_YELLOW"))
    }

    /// Nie gewählt → «Ruhig»; gespeicherte Wahl bleibt (wie Android: dort ist ohne Wahl nichts gespeichert).
    func testCalmDefaultMigration() throws {
        func decode(_ json: String) throws -> AppSettings {
            try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        }
        // Alte Einstellungen ohne Merker: mitgespeichertes «Frisch» war der Standard → «Ruhig»
        XCTAssertEqual(try decode(#"{"priceColorScheme":"GREEN_RED"}"#).priceColorScheme, .CALM)
        XCTAssertEqual(try decode(#"{"priceColorScheme":"BLUE_ORANGE"}"#).priceColorScheme, .BLUE_ORANGE)
        XCTAssertEqual(try decode("{}").priceColorScheme, .CALM)
        // Mit Merker: «Frisch» ist eine Wahl und bleibt
        XCTAssertEqual(try decode(#"{"priceColorScheme":"GREEN_RED","priceColorDefaultV2":true}"#).priceColorScheme, .GREEN_RED)
        // Nach dem Speichern steht der Merker da
        var s = AppSettings()
        s.priceColorScheme = .GREEN_RED
        let round = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(round.priceColorScheme, .GREEN_RED)
    }

    func testLabelKeys() {
        XCTAssertEqual(PriceColorScheme.CALM.labelKey, "price_style_calm")
        XCTAssertEqual(PriceColorScheme.GREEN_RED.labelKey, "price_style_fresh")
        XCTAssertEqual(PriceColorScheme.TRADITIONAL.labelKey, "price_style_traditional")
        XCTAssertEqual(PriceColorScheme.BLUE_ORANGE.labelKey, "price_style_color_vision")
        XCTAssertEqual(PriceColorScheme.CALM_COLOR_VISION.labelKey, "price_style_calm_color_vision")
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

    func testContrastOnAllSurfaces() {
        // Jede Akzentfarbe (und keine): auch die Ersatzfassungen der «Ruhig»-Schemata halten den Kontrast
        let accents: [AccentColor?] = AccentColor.allCases.map { Optional($0) } + [nil]
        for scheme in PriceColorScheme.allCases {
            for hc in [false, true] {
                for dark in [false, true] {
                    let required = hc ? 7.0 : 4.5
                    let colors: [UInt32] = accents.flatMap { accent -> [UInt32] in
                        [scheme.upHex(dark: dark, highContrast: hc, accent: accent),
                         scheme.downHex(dark: dark, highContrast: hc, accent: accent)]
                    }
                    for color in colors {
                        let backgrounds: [UInt32] = dark ? ContrastMath.darkSurfaces : ContrastMath.lightSurfaces
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

    // MARK: Börsenschemata fest, wie PriceColorSchemeTest.kt

    /// «Frisch», «Traditionell» und «Farbsehschwäche»: feste Börsenfarben — bei jeder Akzentfarbe,
    /// hell/dunkel, normal/hoher Kontrast und getauscht genau der Standard (Entscheid 16. Oktober 2026).
    func testExchangeSchemesStayStandardForEveryAccent() {
        let fixed: [PriceColorScheme] = [.GREEN_RED, .TRADITIONAL, .BLUE_ORANGE]
        for scheme in fixed {
            for accent in AccentColor.allCases {
                for dark in [false, true] {
                    for hc in [false, true] {
                        for inv in [false, true] {
                            let key = "\(scheme.rawValue)/\(accent.rawValue)/\(dark)/\(hc)/\(inv)"
                            XCTAssertEqual(scheme.upHex(dark: dark, highContrast: hc, inverted: inv, accent: accent),
                                           scheme.upHex(dark: dark, highContrast: hc, inverted: inv), key)
                            XCTAssertEqual(scheme.downHex(dark: dark, highContrast: hc, inverted: inv, accent: accent),
                                           scheme.downHex(dark: dark, highContrast: hc, inverted: inv), key)
                        }
                    }
                }
                XCTAssertFalse(scheme.clashes(with: accent))
            }
            XCTAssertFalse(scheme.clashes(with: nil))
        }
    }

    // MARK: Blauer Akzent + «Ruhig Farbsehschwäche» (Petrol statt Blau), wie PriceColorSchemeTest.kt

    /// Blauer Akzent (Knöpfe): hell normal / hoher Kontrast, dunkel normal / hoher Kontrast.
    private let blueAccentLight: [UInt32] = [0x2B5FC8, 0x1F4490]
    private let blueAccentDark: [UInt32] = [0x73A3FC, 0xA8C6FD]

    func testBlueAccentPetrolContrastOnAllSurfaces() {
        let scheme: PriceColorScheme = .CALM_COLOR_VISION
        for hc in [false, true] {
            for dark in [false, true] {
                for inverted in [false, true] {
                    let required: Double = hc ? 7.0 : 4.5
                    let up: UInt32 = scheme.upHex(dark: dark, highContrast: hc, inverted: inverted, accent: .BLUE)
                    let down: UInt32 = scheme.downHex(dark: dark, highContrast: hc, inverted: inverted, accent: .BLUE)
                    XCTAssertNotEqual(up, down)
                    let surfaces: [UInt32] = dark ? ContrastMath.darkSurfaces : ContrastMath.lightSurfaces
                    for color in [up, down] {
                        for bg in surfaces {
                            let ratio: Double = contrast(color, bg)
                            XCTAssertGreaterThanOrEqual(ratio, required, "petrol hc=\(hc) dark=\(dark) \(String(color, radix: 16))")
                        }
                    }
                }
            }
        }
    }

    func testBlueAccentRiseClearlyDistinct() {
        let scheme: PriceColorScheme = .CALM_COLOR_VISION
        for hc in [false, true] {
            for dark in [false, true] {
                let rise: UInt32 = scheme.upHex(dark: dark, highContrast: hc, accent: .BLUE)
                for accent in dark ? blueAccentDark : blueAccentLight {
                    let d: Double = deltaE2000(rise, accent)
                    XCTAssertGreaterThanOrEqual(d, 15.0, "hc=\(hc) dark=\(dark) ΔE \(d)")
                }
                // Getauscht: Petrol dann für fallend
                XCTAssertEqual(scheme.downHex(dark: dark, highContrast: hc, inverted: true, accent: .BLUE), rise)
            }
        }
        // Belegt: das bisherige Blau lag zu nah am Akzent (hell ΔE ≈ 5)
        XCTAssertLessThan(deltaE2000(scheme.upHex(dark: false), blueAccentLight[0]), 15.0)
        // Werte wie Android
        XCTAssertEqual(scheme.upHex(dark: false, accent: .BLUE), 0x0D6F73)
        XCTAssertEqual(scheme.upHex(dark: true, accent: .BLUE), 0x26C6CC)
        XCTAssertEqual(scheme.upHex(dark: false, highContrast: true, accent: .BLUE), 0x095053)
        XCTAssertEqual(scheme.upHex(dark: true, highContrast: true, accent: .BLUE), 0x80E6EA)
    }

    /// «Ruhig» und «Ruhig Farbsehschwäche» × jede Akzentfarbe: steigend und fallend je ΔE2000 ≥ 24 zum Akzent (hell und dunkel,
    /// normaler Kontrast); Ausnahme fallend neben Orange (Karmin, eigene Schwelle 19 hell / 20 dunkel).
    /// Unverändert genau dann, wenn schon der Standard 24 weit weg liegt. Steigend und fallend bleiben
    /// in jedem Schema klar verschieden.
    func testPriceColorsClearlyDistinctFromAccent() {
        let minimum: Double = PriceColorScheme.minAccentDeltaE
        for scheme in PriceColorScheme.allCases {
            for accent in AccentColor.allCases {
                for up in [true, false] where scheme == .CALM || scheme == .CALM_COLOR_VISION {
                    let plainFar: Bool = [false, true].allSatisfy { dk in
                        let p: UInt32 = up ? scheme.upHex(dark: dk) : scheme.downHex(dark: dk)
                        return deltaE2000(p, accent.primaryHex(dark: dk)) >= minimum
                    }
                    for dark in [false, true] {
                        let color: UInt32 = up ? scheme.upHex(dark: dark, accent: accent) : scheme.downHex(dark: dark, accent: accent)
                        let plain: UInt32 = up ? scheme.upHex(dark: dark) : scheme.downHex(dark: dark)
                        let d: Double = deltaE2000(color, accent.primaryHex(dark: dark))
                        let floor: Double = scheme.accentFloor(up: up, dark: dark, accent: accent)
                        let relaxed: Bool = scheme == .CALM && accent == .ORANGE && !up
                        let expectedFloor: Double = relaxed ? (dark ? 20 : 19) : minimum
                        XCTAssertEqual(floor, expectedFloor, "\(scheme.rawValue)/\(accent.rawValue)/\(dark)/\(up)")
                        XCTAssertGreaterThanOrEqual(d, floor, "\(scheme.rawValue)/\(accent.rawValue)/\(dark)/\(up)")
                        if plainFar { XCTAssertEqual(color, plain, "\(scheme.rawValue)/\(accent.rawValue)/\(dark)/\(up)") }
                    }
                }
                for dark in [false, true] {
                    for hc in [false, true] {
                        let d: Double = deltaE2000(scheme.upHex(dark: dark, highContrast: hc, accent: accent),
                                                   scheme.downHex(dark: dark, highContrast: hc, accent: accent))
                        XCTAssertGreaterThanOrEqual(d, 20.0, "\(scheme.rawValue)/\(accent.rawValue)/\(dark)/\(hc)")
                    }
                }
            }
        }
        // Belegt: der frühere Standard «Ruhig» neben Orange (Standard-Akzent) lag bei ΔE ≈ 9.5
        XCTAssertLessThan(deltaE2000(0xA74340, AccentColor.ORANGE.primaryHex(dark: false)), 10.0)
        XCTAssertEqual(PriceColorScheme.CALM.downHex(dark: false, accent: .ORANGE), 0x77212F)
        XCTAssertEqual(PriceColorScheme.CALM.downHex(dark: true, accent: .ORANGE), 0xFFB1BE)
        XCTAssertEqual(PriceColorScheme.CALM.downHex(dark: false, accent: .RED), 0x672B61)
        XCTAssertEqual(PriceColorScheme.CALM.upHex(dark: false, accent: .GREEN), 0x506299)
        XCTAssertEqual(PriceColorScheme.BLUE_ORANGE.downHex(dark: false, accent: .ORANGE), 0xAE4200)
        XCTAssertEqual(PriceColorScheme.CALM_COLOR_VISION.downHex(dark: false, accent: .ORANGE), 0x786200)
        XCTAssertTrue(PriceColorScheme.CALM_COLOR_VISION.clashes(with: .BLUE))
        XCTAssertFalse(PriceColorScheme.BLUE_ORANGE.clashes(with: .BLUE))
        XCTAssertTrue(PriceColorScheme.CALM.clashes(with: .ORANGE))
        XCTAssertFalse(PriceColorScheme.GREEN_RED.clashes(with: .BLUE))
        XCTAssertFalse(PriceColorScheme.BLUE_ORANGE.clashes(with: .GREEN))
        XCTAssertFalse(PriceColorScheme.BLUE_ORANGE.clashes(with: nil))
    }

    /// «Ruhig»: steigend und fallend auch in der Helligkeit getrennt (hell fallend dunkler, dunkel
    /// heller); normal ≥ 1.5:1, hoher Kontrast ≥ 1.25:1 — wie PriceColorSchemeTest.kt.
    func testCalmRiseAndFallDifferInLightness() {
        let accents: [AccentColor?] = AccentColor.allCases.map { Optional($0) } + [nil]
        for accent in accents {
            for dark in [false, true] {
                for hc in [false, true] {
                    let up: UInt32 = PriceColorScheme.CALM.upHex(dark: dark, highContrast: hc, accent: accent)
                    let down: UInt32 = PriceColorScheme.CALM.downHex(dark: dark, highContrast: hc, accent: accent)
                    let minimum: Double = hc ? 1.25 : 1.5
                    XCTAssertGreaterThanOrEqual(contrast(up, down), minimum, "\(String(describing: accent))/\(dark)/\(hc)")
                    if dark {
                        XCTAssertGreaterThan(luminance(down), luminance(up))
                    } else {
                        XCTAssertLessThan(luminance(down), luminance(up))
                    }
                }
            }
        }
        XCTAssertLessThan(contrast(0x396E54, 0xA74340), 1.05)
    }

    /// «Ruhig» neben Orange (Standard): fallend liest sich als Rot — HSL-Farbton 345°…10°, bunt
    /// (C* ≥ 40 hell, ≥ 30 dunkel). Wie Android.
    func testCalmFallBesideOrangeIsRed() {
        for dark in [false, true] {
            for hc in [false, true] {
                let c: UInt32 = PriceColorScheme.CALM.downHex(dark: dark, highContrast: hc, accent: .ORANGE)
                let key: String = "dark=\(dark)/hc=\(hc)/\(String(format: "%06X", c))"
                let hue: Double = hslHue(c)
                XCTAssertTrue(hue >= 345 || hue <= 10, "\(key): \(hue)")
                let lab: ColorDifference.Lab = ColorDifference.lab(c)
                let chroma: Double = (lab.a * lab.a + lab.b * lab.b).squareRoot()
                let minChroma: Double = hc ? (dark ? 10 : 35) : (dark ? 30 : 40)
                XCTAssertGreaterThanOrEqual(chroma, minChroma, key)
            }
        }
        // Belegt: das frühere Weinrot war kein Rot
        XCTAssertTrue((300.0...340.0).contains(hslHue(0x701F4A)))
    }

    // MARK: Feste Börsenfarben «Frisch», «Traditionell», «Farbsehschwäche» (Entscheid 18. Oktober 2026)
    // Wie PriceColorSchemeTest.kt; nachrechnen mit android/tools/check_price_colors.py.

    private let fixedSchemes: [PriceColorScheme] = [.GREEN_RED, .TRADITIONAL, .BLUE_ORANGE]

    /// Akzente derselben Farbfamilie (steigend, fallend): für sie gilt kein Mindestabstand.
    private func sameFamily(_ scheme: PriceColorScheme) -> (rise: Set<AccentColor>, fall: Set<AccentColor>) {
        scheme == .BLUE_ORANGE ? ([.BLUE], [.ORANGE]) : ([.GREEN, .MARRS_GREEN], [.RED])
    }

    /// Machado, Oliveira, Fernandes 2009, volle Ausprägung (lineares sRGB, zeilenweise).
    private let cvd: [(name: String, m: [Double])] = [
        ("prot", [0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998]),
        ("deut", [0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881]),
        ("trit", [1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900]),
    ]

    /// Regel 3: hell ist fallend die hellere, kräftigere Farbe, dunkel die dunklere; Leuchtdichte ≥ 1.4
    /// («Farbsehschwäche» ≥ 1.5), hoher Kontrast ≥ 1.25 — auch nach Deuteranopie-Simulation, bei
    /// «Farbsehschwäche» nach allen drei Simulationen (Regel 5).
    func testExchangeColorsDifferInLightness() {
        for scheme in fixedSchemes {
            for dark in [false, true] {
                for hc in [false, true] {
                    let up: UInt32 = scheme.upHex(dark: dark, highContrast: hc)
                    let down: UInt32 = scheme.downHex(dark: dark, highContrast: hc)
                    let minimum: Double = hc ? 1.25 : (scheme == .BLUE_ORANGE ? 1.5 : 1.4)
                    let key: String = "\(scheme.rawValue)/dark=\(dark)/hc=\(hc)"
                    // Hell fallend heller, dunkel fallend dunkler
                    if dark {
                        XCTAssertLessThan(luminance(down), luminance(up), key)
                    } else {
                        XCTAssertGreaterThan(luminance(down), luminance(up), key)
                    }
                    XCTAssertGreaterThanOrEqual(contrast(up, down), minimum, key)
                    for sim in cvd where scheme == .BLUE_ORANGE || sim.name == "deut" {
                        XCTAssertGreaterThanOrEqual(simulatedContrast(up, down, sim.m), minimum, "\(key) \(sim.name)")
                    }
                }
            }
        }
    }

    /// Regeln 4/5: ΔE2000 ≥ 10 («Farbsehschwäche» ≥ 15) zu jedem Akzent (primary desselben Modus, auch
    /// für hohen Kontrast) ausser dem derselben Farbfamilie.
    func testExchangeColorsClearlyDistinctFromOtherAccents() {
        for scheme in fixedSchemes {
            let minimum: Double = scheme == .BLUE_ORANGE ? 15 : 10
            for dark in [false, true] {
                for hc in [false, true] {
                    for up in [true, false] {
                        let color: UInt32 = up ? scheme.upHex(dark: dark, highContrast: hc) : scheme.downHex(dark: dark, highContrast: hc)
                        let family: Set<AccentColor> = up ? sameFamily(scheme).rise : sameFamily(scheme).fall
                        for accent in AccentColor.allCases where !family.contains(accent) {
                            let d: Double = deltaE2000(color, accent.primaryHex(dark: dark))
                            XCTAssertGreaterThanOrEqual(d, minimum, "\(scheme.rawValue)/\(accent.rawValue)/\(dark)/\(hc)/\(up)")
                        }
                    }
                }
            }
        }
    }

    /// Regel 1: «Frisch» steigend 140–165°, fallend 350–10°; «Traditionell» 115–135° / 0–8°, je
    /// ΔE2000 ≥ 10 zur selben Farbe von «Frisch».
    func testFreshAndTraditionalTrueHuesAndDistinct() {
        for dark in [false, true] {
            for hc in [false, true] {
                let key: String = "dark=\(dark)/hc=\(hc)"
                let freshUp: UInt32 = PriceColorScheme.GREEN_RED.upHex(dark: dark, highContrast: hc)
                let freshDown: UInt32 = PriceColorScheme.GREEN_RED.downHex(dark: dark, highContrast: hc)
                let tradUp: UInt32 = PriceColorScheme.TRADITIONAL.upHex(dark: dark, highContrast: hc)
                let tradDown: UInt32 = PriceColorScheme.TRADITIONAL.downHex(dark: dark, highContrast: hc)
                XCTAssertTrue((140.0...165.0).contains(hslHue(freshUp)), key)
                XCTAssertTrue(hslHue(freshDown) >= 350 || hslHue(freshDown) <= 10, key)
                XCTAssertTrue((115.0...135.0).contains(hslHue(tradUp)), key)
                XCTAssertTrue((0.0...8.0).contains(hslHue(tradDown)), key)
                XCTAssertGreaterThanOrEqual(deltaE2000(freshUp, tradUp), 10, key)
                XCTAssertGreaterThanOrEqual(deltaE2000(freshDown, tradDown), 10, key)
            }
        }
    }

    /// Regel 4: «Farbsehschwäche» nach Protanopie-, Deuteranopie-, Tritanopie-Simulation ΔE2000 ≥ 40,
    /// hoher Kontrast ≥ 35 (dunkel erreicht Blau/Orange dort höchstens ≈ 38.7).
    func testColorVisionSimulatedClearlyApart() {
        for dark in [false, true] {
            for hc in [false, true] {
                let up: UInt32 = PriceColorScheme.BLUE_ORANGE.upHex(dark: dark, highContrast: hc)
                let down: UInt32 = PriceColorScheme.BLUE_ORANGE.downHex(dark: dark, highContrast: hc)
                for sim in cvd {
                    let d: Double = ColorDifference.deltaE2000(labLinear(simulate(up, sim.m)), labLinear(simulate(down, sim.m)))
                    XCTAssertGreaterThanOrEqual(d, hc ? 35 : 40, "dark=\(dark)/hc=\(hc)/\(sim.name)")
                }
            }
        }
        // Belegt: die früheren Werte lagen gleich hell und neben dem blauen Akzent
        XCTAssertLessThan(contrast(0x005ED6, 0xB63900), 1.05)
        XCTAssertLessThan(deltaE2000(0x005ED6, AccentColor.BLUE.primaryHex(dark: false)), 2.0)
    }

    /// Lineares sRGB nach Simulation `m` (3×3, zeilenweise), auf 0…1 begrenzt, ohne Rundung auf 8 Bit.
    private func simulate(_ rgb: UInt32, _ m: [Double]) -> [Double] {
        let r: Double = channel((rgb >> 16) & 0xFF)
        let g: Double = channel((rgb >> 8) & 0xFF)
        let b: Double = channel(rgb & 0xFF)
        return (0..<3).map { i -> Double in
            let v: Double = m[3 * i] * r + m[3 * i + 1] * g + m[3 * i + 2] * b
            return min(1, max(0, v))
        }
    }

    private func simulatedContrast(_ a: UInt32, _ b: UInt32, _ m: [Double]) -> Double {
        func lum(_ c: [Double]) -> Double { 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] }
        let la: Double = lum(simulate(a, m))
        let lb: Double = lum(simulate(b, m))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// CIELAB (D65) aus linearem sRGB, wie `ColorDifference.lab`.
    private func labLinear(_ c: [Double]) -> ColorDifference.Lab {
        func f(_ t: Double) -> Double { t > 216.0 / 24389.0 ? cbrt(t) : (24389.0 / 27.0 * t + 16.0) / 116.0 }
        let xs: Double = 0.4124 * c[0] + 0.3576 * c[1] + 0.1805 * c[2]
        let y: Double = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
        let zs: Double = 0.0193 * c[0] + 0.1192 * c[1] + 0.9505 * c[2]
        let fx: Double = f(xs / 0.95047)
        let fy: Double = f(y)
        let fz: Double = f(zs / 1.08883)
        return ColorDifference.Lab(l: 116 * fy - 16, a: 500 * (fx - fy), b: 200 * (fy - fz))
    }

    private func hslHue(_ rgb: UInt32) -> Double {
        let r: Double = Double((rgb >> 16) & 0xFF) / 255
        let g: Double = Double((rgb >> 8) & 0xFF) / 255
        let b: Double = Double(rgb & 0xFF) / 255
        let mx: Double = max(r, g, b)
        let d: Double = mx - min(r, g, b)
        if d == 0 { return 0 }
        var h: Double
        if mx == r {
            h = ((g - b) / d).truncatingRemainder(dividingBy: 6)
            if h < 0 { h += 6 }
        } else if mx == g {
            h = (b - r) / d + 2
        } else {
            h = (r - g) / d + 4
        }
        return h * 60
    }

    private func deltaE2000(_ c1: UInt32, _ c2: UInt32) -> Double {
        ColorDifference.deltaE2000(c1, c2)
    }
}

/// WCAG-Kontrast für die Tests unten (wie `WidgetContrast.ratio` in Android).
private enum ContrastMath {
    static func channel(_ c: UInt32) -> Double {
        let s = Double(c) / 255
        return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
    }

    static func luminance(_ rgb: UInt32) -> Double {
        0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF)
    }

    static func ratio(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a)
        let lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// Frühere Grautöne und (K-9) die warmen Flächen «warmes Papier».
    static let lightSurfaces: [UInt32] = [0xFFFFFF, 0xF9F9F9, 0xF3F3F3, 0xEEEEEE, 0xE8E8E8, 0xE4E4E4, 0xE2E2E2,
                                          0xFFFDFA, 0xFBF9F6, 0xF6F3EE, 0xF1EEE9, 0xEBE8E2, 0xE7E4DD, 0xE5E2DB]
    static let darkSurfaces: [UInt32] = [0x0E0E0E, 0x121212, 0x131313, 0x1B1B1B, 0x1F1F1F, 0x2A2A2A, 0x333333, 0x353535]
}

/// K-9 und M-8, wie `AccentContrastTest.kt`: warme Flächen nicht dunkler als die früheren Grautöne,
/// feste Fear-&-Greed-Skala (Puls-Widget) als Schrift ≥ 4.5:1 in Hell und Dunkel.
final class WarmSurfaceAndScaleContrastTests: XCTestCase {

    func testWarmTokensAreNotDarkerThanFormerGreys() {
        let pairs: [(UInt32, UInt32)] = [
            (0xF9F9F9, ColorTokens.background.light), (0xF3F3F3, ColorTokens.surfaceContainerLow.light),
            (0xEEEEEE, ColorTokens.surfaceContainer.light), (0xE8E8E8, ColorTokens.surfaceContainerHigh.light),
            (0xE2E2E2, ColorTokens.surfaceContainerHighest.light), (0xE4E4E4, ColorTokens.widgetSurfaceDeep.light),
            // outlineVariant bewusst dunkler (Build 58, ≈ 2.2:1): eigener Test
        ]
        for (old, warm) in pairs {
            XCTAssertGreaterThanOrEqual(ContrastMath.luminance(warm), ContrastMath.luminance(old) - 1e-9,
                                        String(warm, radix: 16))
        }
    }

    func testTokensAsAndroid() {
        XCTAssertEqual(ColorTokens.background.light, 0xFBF9F6)
        XCTAssertEqual(ColorTokens.surfaceContainer.light, 0xF1EEE9)
        XCTAssertEqual(ColorTokens.surfaceContainerHighest.light, 0xE5E2DB)
        XCTAssertEqual(ColorTokens.scaleSteps.map(\.light), [0xB42318, 0x90403A, 0x636363, 0x326B50, 0x0A7240])
        XCTAssertEqual(ColorTokens.scaleSteps.map(\.dark), [0xFF7A70, 0xD48B86, 0xA0A0A0, 0x6ABE95, 0x3DD68C])
    }

    func testTextReachesAAOnWarmSurfaces() {
        for bg in ContrastMath.lightSurfaces {
            for fg: UInt32 in [ColorTokens.onSurface.light, ColorTokens.onSurfaceVariant.light, 0x8A5300,
                               ColorTokens.error.light] {
                XCTAssertGreaterThanOrEqual(ContrastMath.ratio(fg, bg), 4.5, "\(String(fg, radix: 16)) on \(String(bg, radix: 16))")
            }
            XCTAssertGreaterThanOrEqual(ContrastMath.ratio(ColorTokens.outline.light, bg), 3)
        }
    }

    /// Widget-Nebentext (`WidgetPalette.secondary`): feste Farbe onSurfaceVariant ohne Deckkraft,
    /// ≥ 4.5:1 auf dem Widget-Grund (surfaceContainer) in Hell und Dunkel — wie Android
    /// `AccentContrastTest` «Widget-Nebentext».
    func testWidgetSecondaryTextReachesAAOnWidgetBase() {
        XCTAssertEqual(ColorTokens.onSurfaceVariant.light, 0x474747)
        XCTAssertEqual(ColorTokens.onSurfaceVariant.dark, 0xC6C6C6)
        for dark in [false, true] {
            let fg: UInt32 = ColorTokens.onSurfaceVariant.hex(dark: dark)
            let bg: UInt32 = ColorTokens.surfaceContainer.hex(dark: dark)
            XCTAssertGreaterThanOrEqual(ContrastMath.ratio(fg, bg), 4.5, "dark=\(dark)")
        }
    }

    /// Widget «unverändert» (`WidgetPalette.neutral`): #5F5F5F hell (früher outline #777777, 3.87:1),
    /// ≥ 4.5:1 auf dem Widget-Grund in Hell und Dunkel — wie Android `WidgetContrastTest`.
    func testWidgetNeutralReachesAAOnWidgetBase() {
        XCTAssertEqual(ColorTokens.widgetNeutral.light, 0x5F5F5F)
        XCTAssertLessThan(ContrastMath.ratio(ColorTokens.outline.light, ColorTokens.surfaceContainer.light), 4.5)
        for dark in [false, true] {
            let fg: UInt32 = ColorTokens.widgetNeutral.hex(dark: dark)
            let bg: UInt32 = ColorTokens.surfaceContainer.hex(dark: dark)
            XCTAssertGreaterThanOrEqual(ContrastMath.ratio(fg, bg), 4.5, "dark=\(dark)")
        }
    }

    func testFixedScaleReachesAAAsTextInLightAndDark() {
        for dark in [false, true] {
            let steps = ColorTokens.scaleSteps.map { $0.hex(dark: dark) }
            XCTAssertEqual(Set(steps).count, 5)
            for (i, step) in steps.enumerated() {
                let surfaces = dark ? ContrastMath.darkSurfaces + [0x151515] : ContrastMath.lightSurfaces
                for bg in surfaces {
                    XCTAssertGreaterThanOrEqual(ContrastMath.ratio(step, bg), 4.5,
                                                "dark=\(dark) step \(i) on \(String(bg, radix: 16))")
                }
                XCTAssertGreaterThanOrEqual(ContrastMath.ratio(ColorTokens.onScaleStep.hex(dark: dark), step), 4.5,
                                            "dark=\(dark) onStep \(i)")
            }
        }
        XCTAssertFalse(ColorTokens.scaleSteps.contains { $0.dark == 0xB42318 })
    }

    /// Build 46, wie Android: Bitcoin-Orange als Grafik ≥ 3:1 (hell dunkler, dunkel das Original);
    /// `outline` als einzige Chip-Kontur ≥ 3:1, `outlineVariant` lag darunter.
    func testBitcoinOrangeAndChipOutlineReachThreeToOne() {
        for dark in [false, true] {
            let btc = ColorTokens.bitcoin.hex(dark: dark)
            for bg in dark ? ContrastMath.darkSurfaces : ContrastMath.lightSurfaces {
                XCTAssertGreaterThanOrEqual(ContrastMath.ratio(btc, bg), 3, "dark=\(dark) btc on \(String(bg, radix: 16))")
                XCTAssertGreaterThanOrEqual(ContrastMath.ratio(ColorTokens.outline.hex(dark: dark), bg), 3,
                                            "dark=\(dark) outline on \(String(bg, radix: 16))")
            }
        }
        XCTAssertEqual(ColorTokens.bitcoin.light, 0xB86200)
        XCTAssertEqual(ColorTokens.bitcoin.dark, 0xF7931A)
        XCTAssertLessThan(ContrastMath.ratio(0xF7931A, ColorTokens.background.light), 3)
        XCTAssertLessThan(ContrastMath.ratio(ColorTokens.outlineVariant.light, ColorTokens.surfaceContainer.light), 3)
    }

    /// Build 58, wie Android: Trennlinien hell ≈ 2.2:1 auf dem Hintergrund #FBF9F6 (vorher #C9C6BF 1.6:1), unter 3:1.
    func testLightDividerReachesAboutTwoPointTwo() {
        XCTAssertEqual(ColorTokens.outlineVariant.light, 0xADA9A0)
        XCTAssertEqual(ColorTokens.outlineVariant.dark, 0x474747)
        let ratio = ContrastMath.ratio(ColorTokens.outlineVariant.light, ColorTokens.background.light)
        XCTAssertGreaterThanOrEqual(ratio, 2.15)
        XCTAssertLessThanOrEqual(ratio, 2.3)
    }

    /// Build 46, wie Android: Ethereum-Blau als Grafik ≥ 3:1 (hell dunkler, dunkel das Original).
    func testEthereumBlueReachesThreeToOne() {
        for dark in [false, true] {
            let eth = ColorTokens.ethereum.hex(dark: dark)
            for bg in dark ? ContrastMath.darkSurfaces : ContrastMath.lightSurfaces {
                XCTAssertGreaterThanOrEqual(ContrastMath.ratio(eth, bg), 3, "dark=\(dark) eth on \(String(bg, radix: 16))")
            }
        }
        XCTAssertEqual(ColorTokens.ethereum.light, 0x4C64D0)
        XCTAssertEqual(ColorTokens.ethereum.dark, 0x627EEA)
        XCTAssertLessThan(ContrastMath.ratio(0x627EEA, 0xE5E2DB), 3)
    }
}

/// J-14, wie `AccentContrastTest.kt`: Akzent ≥ 4.5:1 auf allen Flächen (hell bis #E2E2E2,
/// dunkel bis #353535), Text auf dem Akzent ≥ 4.5:1, hoher Kontrast ≥ 7:1 auf #E2E2E2 / #353535.
final class AccentContrastTests: XCTestCase {

    func testPrimaryReachesAAOnAllSurfaces() {
        for accent in AccentColor.allCases {
            for dark in [false, true] {
                let primary = accent.primaryHex(dark: dark)
                for bg in dark ? ContrastMath.darkSurfaces : ContrastMath.lightSurfaces {
                    let r = ContrastMath.ratio(primary, bg)
                    XCTAssertGreaterThanOrEqual(r, 4.5, "\(accent.rawValue) dark=\(dark) on \(String(bg, radix: 16))")
                }
                let onPrimary = ContrastMath.ratio(accent.onPrimaryHex(dark: dark), primary)
                XCTAssertGreaterThanOrEqual(onPrimary, 4.5, "onPrimary \(accent.rawValue) dark=\(dark)")
            }
        }
    }

    func testHighContrastReachesSevenToOne() {
        for accent in AccentColor.allCases {
            XCTAssertGreaterThanOrEqual(ContrastMath.ratio(accent.primaryHex(dark: false, highContrast: true), 0xE2E2E2), 7,
                                        accent.rawValue)
            XCTAssertGreaterThanOrEqual(ContrastMath.ratio(accent.primaryHex(dark: true, highContrast: true), 0x353535), 7,
                                        accent.rawValue)
        }
    }

    func testValuesAsAndroid() {
        XCTAssertEqual(AccentColor.ORANGE.primaryHex(dark: false), 0xA64827)
        XCTAssertEqual(AccentColor.RED.primaryHex(dark: false), 0xBE2B38)
        XCTAssertEqual(AccentColor.BLUE.primaryHex(dark: false), 0x2B5FC8)
        XCTAssertEqual(AccentColor.GREEN.primaryHex(dark: false), 0x107348)
        XCTAssertEqual(AccentColor.MARRS_GREEN.primaryHex(dark: false), 0x22706B)
        XCTAssertEqual(AccentColor.ORANGE.primaryHex(dark: true), 0xED835E)
    }
}

/// J-15, wie `MarketScalePaletteTest.kt`: Stufenfarben der Fear-&-Greed-Skala.
final class MarketScalePaletteTests: XCTestCase {

    func testEdgesArePriceColorsAndMiddleIsNeutral() {
        for scheme in PriceColorScheme.allCases {
            for dark in [false, true] {
                for hc in [false, true] {
                    for inv in [false, true] {
                        let s = MarketScalePalette.steps(scheme, dark: dark, highContrast: hc, inverted: inv)
                        XCTAssertEqual(s.count, 5)
                        XCTAssertEqual(s[0], scheme.downHex(dark: dark, highContrast: hc, inverted: inv))
                        XCTAssertEqual(s[4], scheme.upHex(dark: dark, highContrast: hc, inverted: inv))
                        XCTAssertEqual(s[2], dark ? MarketScalePalette.neutralDark : MarketScalePalette.neutralLight)
                    }
                }
            }
        }
    }

    func testColorVisionSchemeApplies() {
        let s = MarketScalePalette.steps(.CALM_COLOR_VISION, dark: false)
        XCTAssertEqual(s, [0x9F4300, 0x8A5732, 0x707070, 0x3D6790, 0x1460AB])
    }

    func testLevelAndColorPerValue() {
        XCTAssertEqual([0, 24, 25, 44, 45, 55, 56, 75, 76, 100].map { MarketScalePalette.level($0) },
                       [0, 0, 1, 1, 2, 2, 3, 3, 4, 4])
        XCTAssertEqual(MarketScalePalette.color(90, .GREEN_RED, dark: true), PriceColorScheme.GREEN_RED.upHex(dark: true))
        XCTAssertEqual(MarketScalePalette.color(90, .GREEN_RED, dark: true, inverted: true),
                       PriceColorScheme.GREEN_RED.downHex(dark: true))
    }

    func testEveryStepReachesThreeToOne() {
        let light: [UInt32] = [0xFFFFFF, 0xF9F9F9, 0xF3F3F3, 0xEEEEEE, 0xE8E8E8, 0xE2E2E2]
        let dark: [UInt32] = [0x0E0E0E, 0x131313, 0x1B1B1B, 0x1F1F1F, 0x2A2A2A, 0x353535]
        for scheme in PriceColorScheme.allCases {
            for isDark in [false, true] {
                for hc in [false, true] {
                    for (i, c) in MarketScalePalette.steps(scheme, dark: isDark, highContrast: hc).enumerated() {
                        for bg in isDark ? dark : light {
                            XCTAssertGreaterThanOrEqual(ContrastMath.ratio(c, bg), 3,
                                                        "\(scheme.rawValue) dark=\(isDark) hc=\(hc) step \(i)")
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

    func testHourlyFundingComparedPerEightHours() {
        // Hyperliquid meldet pro Stunde: 0,0125 %/Std. = 0,1 % je 8 Std. → auffällig, Wert bleibt der der Börse
        let hourly = ActivityAnalyzer.signals(stats: nil, fundingPercent: 0.0125, oiChangePercent: nil, oiMinutes: nil,
                                              now: now, fundingSource: FuturesSources.hyperliquid)
        XCTAssertEqual(hourly.map(\.kind), [.FUNDING_EXTREME])
        XCTAssertEqual(hourly.first?.value ?? 0, 0.0125, accuracy: 1e-12)
        XCTAssertEqual(hourly.first?.factor, 1)
        XCTAssertTrue(ActivityAnalyzer.signals(stats: nil, fundingPercent: 0.0125, oiChangePercent: nil, oiMinutes: nil,
                                               now: now, fundingSource: "Bybit").isEmpty)
        XCTAssertEqual(ActivityAnalyzer.applySensitivity(hourly, .NORMAL).count, 1)
        // «Warum?»: 0,01 %/Std. = 0,08 % je 8 Std. → viele Longs, Rang umgerechnet
        let reason = WhyReason(kind: .LEVERAGE_LONGS, tone: .warning, value: 0.01, intervalHours: 1)
        XCTAssertEqual(ActivityAnalyzer.fundingPer8h(reason.value, intervalHours: reason.intervalHours), 0.08, accuracy: 1e-12)
        XCTAssertEqual(ActivityAnalyzer.fundingIntervalHours("Bybit"), nil)
        XCTAssertEqual(ActivityAnalyzer.fundingIntervalHours(FuturesSources.hyperliquid), 1)
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

/// «Token & TradFi» — wie `AssetScopeTest.kt`.
final class AssetScopeTests: XCTestCase {

    func testDefaultIsTokensOnly() {
        XCTAssertEqual(AssetScope.default, .TOKENS)
        XCTAssertEqual(AppSettings().assetScope, .TOKENS)
    }

    func testLegacySwitchMapsOnToBothOffToTokens() {
        XCTAssertEqual(AssetScope.fromLegacy(true), .BOTH)
        XCTAssertEqual(AssetScope.fromLegacy(false), .TOKENS)
        XCTAssertEqual(AssetScope.fromLegacy(nil), .TOKENS)
    }

    func testResolvePrefersNewValueThenLegacyThenDefault() {
        // Neuer Wert gesetzt: der alte Schalter zählt nicht mehr
        XCTAssertEqual(AssetScope.resolve(stored: "TRADFI", legacyTradFi: true), .TRADFI)
        XCTAssertEqual(AssetScope.resolve(stored: "TOKENS", legacyTradFi: true), .TOKENS)
        // Update von einer älteren Version: nur der alte Schalter
        XCTAssertEqual(AssetScope.resolve(stored: nil, legacyTradFi: true), .BOTH)
        XCTAssertEqual(AssetScope.resolve(stored: nil, legacyTradFi: false), .TOKENS)
        // Neu installiert
        XCTAssertEqual(AssetScope.resolve(stored: nil, legacyTradFi: nil), .TOKENS)
        // Unbekannter Wert (neuere Version nach Downgrade): alter Schalter, sonst Standard
        XCTAssertEqual(AssetScope.resolve(stored: "STOCKS", legacyTradFi: true), .BOTH)
        XCTAssertEqual(AssetScope.resolve(stored: "STOCKS", legacyTradFi: nil), .TOKENS)
    }

    func testBackupNewKeyLegacyKeyOrKeepCurrent() {
        XCTAssertEqual(AssetScope.fromBackup("TRADFI", legacyTradFi: false), .TRADFI)
        XCTAssertEqual(AssetScope.fromBackup(" both ", legacyTradFi: nil), .BOTH)
        // Ältere Sicherung: nur «includeTradFiFutures»
        XCTAssertEqual(AssetScope.fromBackup(nil, legacyTradFi: true), .BOTH)
        XCTAssertEqual(AssetScope.fromBackup("", legacyTradFi: false), .TOKENS)
        // Weder noch: aktuelle Einstellung bleibt
        XCTAssertNil(AssetScope.fromBackup(nil, legacyTradFi: nil))
        XCTAssertNil(AssetScope.fromBackup("UNKNOWN", legacyTradFi: nil))
    }

    func testHelpersTokensAndTradFi() {
        XCTAssertTrue(AssetScope.TOKENS.allowsTokens)
        XCTAssertFalse(AssetScope.TOKENS.allowsTradFi)
        XCTAssertFalse(AssetScope.TRADFI.allowsTokens)
        XCTAssertTrue(AssetScope.TRADFI.allowsTradFi)
        XCTAssertTrue(AssetScope.BOTH.allowsTokens)
        XCTAssertTrue(AssetScope.BOTH.allowsTradFi)
    }

    func testMarketsTradFiOnlyKeepsTradFiExchanges() {
        XCTAssertTrue(AssetScope.TRADFI.allowsMarket("BinanceFutures"))
        XCTAssertFalse(AssetScope.TRADFI.allowsMarket("Binance"))
        XCTAssertFalse(AssetScope.TRADFI.allowsMarket("DexScreener"))
        XCTAssertTrue(AssetScope.TOKENS.allowsMarket("Binance"))
        XCTAssertTrue(AssetScope.BOTH.allowsMarket("DexScreener"))
    }

    func testScopedFiltersPairs() {
        let btc = MarketPair("BTC", "USDT", nil)
        let nvda = MarketPair("NVDA", "USDT", nil, tradFi: true)
        let info = MarketPairsInfo(lastSyncDate: 5, pairs: [btc, nvda])
        XCTAssertEqual(info.scoped(.TOKENS).pairs, [btc])
        XCTAssertEqual(info.scoped(.TRADFI).pairs, [nvda])
        XCTAssertEqual(info.scoped(.BOTH).pairs, [btc, nvda])
        XCTAssertEqual(info.scoped(.TRADFI).lastSyncDate, 5)
    }

    func testAlphaHiddenOnlyWhenSwitchedOff() {
        let aia = MarketPair("AIA", "USDT", nil)
        let btc = MarketPair("BTC", "USDT", nil)
        let alpha: Set<String> = ["AIA"]
        XCTAssertTrue(AssetScope.TOKENS.allows(aia, marketKey: "BinanceFutures"))
        XCTAssertEqual(AssetScope.hiddenAlphaSymbols(includeAlpha: true, alphaSymbols: alpha), [])
        let hidden = AssetScope.hiddenAlphaSymbols(includeAlpha: false, alphaSymbols: alpha)
        XCTAssertFalse(AssetScope.TOKENS.allows(aia, marketKey: "BinanceFutures", hiddenAlpha: hidden))
        XCTAssertTrue(AssetScope.BOTH.allows(btc, marketKey: "BinanceFutures", hiddenAlpha: hidden))
        // An Binance Spot regulär gelistet: kein Alpha
        XCTAssertTrue(AssetScope.TOKENS.allows(aia, marketKey: "Binance", hiddenAlpha: hidden))
        let info = MarketPairsInfo(lastSyncDate: 1, pairs: [btc, aia])
        XCTAssertEqual(info.scoped(.TOKENS, marketKey: "BinanceFutures", hiddenAlpha: hidden).pairs, [btc])
        XCTAssertEqual(info.scoped(.TOKENS, marketKey: "BinanceFutures").pairs, [btc, aia])
    }

    /// «inkl. Alpha-Tokens»: ab Build 43 für neue Installationen aus; ältere gespeicherte Einstellungen
    /// ohne den Wert behalten den bisherigen Standard an.
    func testIncludeAlphaTokensDefaultsOffForNewInstallsOnly() throws {
        XCTAssertFalse(AppSettings().includeAlphaTokens)
        let old = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"assetScope":"TOKENS"}"#.utf8))
        XCTAssertTrue(old.includeAlphaTokens)
        var s = AppSettings()
        s.includeAlphaTokens = false
        let data = try JSONEncoder().encode(s)
        XCTAssertFalse(try JSONDecoder().decode(AppSettings.self, from: data).includeAlphaTokens)
    }

    /// Portfolio-Tab: neue Installationen ab Build 44 aus; ältere gespeicherte Einstellungen (mit dem damals
    /// mitgespeicherten Standard «aus», ohne Merker) einmalig an; danach bleibt eine Wahl «aus» stehen.
    func testPortfolioEnabledDefaultsWithOneTimeMigration() throws {
        XCTAssertFalse(AppSettings().portfolioEnabled)
        XCTAssertTrue(AppSettings.legacyPortfolioEnabled)
        let legacy = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"portfolioEnabled":false}"#.utf8))
        XCTAssertTrue(legacy.portfolioEnabled)
        XCTAssertTrue(legacy.portfolioDefaultV2)
        var s = AppSettings()
        s.portfolioEnabled = false
        let data = try JSONEncoder().encode(s)
        XCTAssertFalse(try JSONDecoder().decode(AppSettings.self, from: data).portfolioEnabled)
        let chosen = try JSONDecoder().decode(AppSettings.self,
                                              from: Data(#"{"portfolioEnabled":false,"portfolioDefaultV2":true}"#.utf8))
        XCTAssertFalse(chosen.portfolioEnabled)
        // Gespeicherte Einstellungen (bestehende Installation, Build 43) ohne den Wert: bisheriger Standard an
        let build43 = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"portfolioDefaultV2":true}"#.utf8))
        XCTAssertTrue(build43.portfolioEnabled)
        // Neue Installation, gespeichert und wieder gelesen: bleibt aus
        let fresh = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(AppSettings()))
        XCTAssertFalse(fresh.portfolioEnabled)
    }

    /// Gespeicherte Einstellungen einer älteren Version: nur «includeTradFiFutures».
    func testDecodingOldSettingsMigratesTheSwitch() throws {
        let on = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"includeTradFiFutures":true}"#.utf8))
        XCTAssertEqual(on.assetScope, .BOTH)
        let off = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"includeTradFiFutures":false}"#.utf8))
        XCTAssertEqual(off.assetScope, .TOKENS)
        let newer = try JSONDecoder().decode(AppSettings.self,
                                             from: Data(#"{"assetScope":"TRADFI","includeTradFiFutures":true}"#.utf8))
        XCTAssertEqual(newer.assetScope, .TRADFI)
    }

    func testEncodingWritesTheNewKeyRoundTrip() throws {
        var s = AppSettings()
        s.assetScope = .TRADFI
        let data = try JSONEncoder().encode(s)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["assetScope"] as? String, "TRADFI")
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: data).assetScope, .TRADFI)
    }
}

// MARK: - Nachtruhe

/// Wie `QuietHoursTest.kt`: In der Nachtruhe warten Alarme (`holdsAlarms`), danach lösen sie normal aus.
final class QuietHoursTests: XCTestCase {

    private func hm(_ h: Int, _ m: Int = 0) -> Int { h * 60 + m }

    func testAlarmsWaitDuringQuietHoursAcrossMidnight() {
        let holds = { (m: Int) in QuietHours.holdsAlarms(enabled: true, start: self.hm(23), end: self.hm(7), minuteOfDay: m) }
        XCTAssertTrue(holds(hm(23)))
        XCTAssertTrue(holds(hm(3)))
        XCTAssertTrue(holds(hm(6, 59)))
        // Ende der Nachtruhe: die nächste Prüfung löst wieder normal aus
        XCTAssertFalse(holds(hm(7)))
        XCTAssertFalse(holds(hm(12)))
    }

    func testAlarmsNeverWaitWhenOffOrNeverQuiet() {
        XCTAssertFalse(QuietHours.holdsAlarms(enabled: false, start: hm(23), end: hm(7), minuteOfDay: hm(2)))
        XCTAssertFalse(QuietHours.holdsAlarms(enabled: true, start: hm(7), end: hm(7), minuteOfDay: hm(7)))
    }

    /// Wie `mayFireOnlyOutsideQuietHoursWithDeliverableNotification` (Android): ohne Mitteilung kein Auslösen.
    func testMayFireOnlyOutsideQuietHoursWithDeliverableNotification() {
        XCTAssertTrue(QuietHours.mayFire(holdAlarms: false, deliverable: true))
        XCTAssertFalse(QuietHours.mayFire(holdAlarms: false, deliverable: false))
        XCTAssertFalse(QuietHours.mayFire(holdAlarms: true, deliverable: true))
        XCTAssertFalse(QuietHours.mayFire(holdAlarms: true, deliverable: false))
    }

    func testHoldsAlarmsFollowsQuietHoursForEveryMinute() {
        for minute in 0..<QuietHours.minutesPerDay {
            XCTAssertEqual(QuietHours.holdsAlarms(enabled: true, start: hm(22, 30), end: hm(6, 15), minuteOfDay: minute),
                           QuietHours.isQuiet(enabled: true, start: hm(22, 30), end: hm(6, 15), minuteOfDay: minute))
        }
    }
}

// MARK: - Wie aktuell?

/// Wie `UpdateModeTest.kt`: Übernahme aus den früheren Schaltern, Sicherung und abgeleitete Schalter.
final class UpdateModeTests: XCTestCase {

    func testDefaultIsSparsam() {
        XCTAssertEqual(UpdateMode.default, .SPARSAM)
        XCTAssertEqual(AppSettings().updateMode, .SPARSAM)
        XCTAssertEqual(UpdateMode.iosChoices, [.SPARSAM, .LIVE_OFFEN])
    }

    func testLegacyServiceWinsThenWebSocketElseSparsam() {
        XCTAssertEqual(UpdateMode.fromLegacy(liveService: true, liveWebSocket: true, backgroundUpdates: true, serviceAvailable: true),
                       .IMMER_KURZ)
        // iOS (kein Dauerdienst) → Live, solange offen
        XCTAssertEqual(UpdateMode.fromLegacy(liveService: true, liveWebSocket: false, backgroundUpdates: true, serviceAvailable: false),
                       .LIVE_OFFEN)
        XCTAssertEqual(UpdateMode.fromLegacy(liveService: false, liveWebSocket: true, backgroundUpdates: false, serviceAvailable: true),
                       .LIVE_OFFEN)
        XCTAssertEqual(UpdateMode.fromLegacy(liveService: nil, liveWebSocket: true, backgroundUpdates: nil, serviceAvailable: false),
                       .LIVE_OFFEN)
        XCTAssertEqual(UpdateMode.fromLegacy(liveService: false, liveWebSocket: false, backgroundUpdates: true, serviceAvailable: true),
                       .SPARSAM)
        XCTAssertEqual(UpdateMode.fromLegacy(liveService: false, liveWebSocket: false, backgroundUpdates: false, serviceAvailable: false),
                       .SPARSAM)
        XCTAssertNil(UpdateMode.fromLegacy(liveService: nil, liveWebSocket: nil, backgroundUpdates: nil, serviceAvailable: true))
    }

    func testResolve() {
        XCTAssertEqual(UpdateMode.resolve(stored: "SPARSAM", legacyLiveService: true, legacyWebSocket: true, legacyBackground: true,
                                          existingInstall: true, serviceAvailable: true), .SPARSAM)
        XCTAssertEqual(UpdateMode.resolve(stored: "IMMER_KURZ", legacyLiveService: nil, legacyWebSocket: nil, legacyBackground: nil,
                                          existingInstall: false, serviceAvailable: false), .LIVE_OFFEN)
        XCTAssertEqual(UpdateMode.resolve(stored: "TURBO", legacyLiveService: true, legacyWebSocket: false, legacyBackground: true,
                                          existingInstall: true, serviceAvailable: true), .IMMER_KURZ)
        XCTAssertEqual(UpdateMode.resolve(stored: nil, legacyLiveService: nil, legacyWebSocket: nil, legacyBackground: nil,
                                          existingInstall: true, serviceAvailable: true), .SPARSAM)
        XCTAssertEqual(UpdateMode.resolve(stored: nil, legacyLiveService: nil, legacyWebSocket: nil, legacyBackground: nil,
                                          existingInstall: false, serviceAvailable: false), .SPARSAM)
    }

    func testBackupNewKeyThenLegacyElseUnchanged() {
        XCTAssertEqual(UpdateMode.fromBackup("SPARSAM", legacyLiveService: true, legacyWebSocket: true, legacyBackground: true,
                                             serviceAvailable: false), .SPARSAM)
        XCTAssertEqual(UpdateMode.fromBackup(nil, legacyLiveService: true, legacyWebSocket: false, legacyBackground: true,
                                             serviceAvailable: false), .LIVE_OFFEN)
        XCTAssertEqual(UpdateMode.fromBackup("IMMER_KURZ", legacyLiveService: nil, legacyWebSocket: nil, legacyBackground: nil,
                                             serviceAvailable: false), .LIVE_OFFEN)
        XCTAssertNil(UpdateMode.fromBackup(nil, legacyLiveService: nil, legacyWebSocket: nil, legacyBackground: nil,
                                           serviceAvailable: false))
    }

    func testLegacyFlagsRoundTrip() {
        for mode in UpdateMode.allCases {
            XCTAssertTrue(mode.legacyBackgroundUpdates)
            XCTAssertEqual(UpdateMode.fromLegacy(liveService: mode.legacyLiveService, liveWebSocket: mode.legacyLiveWebSocket,
                                                 backgroundUpdates: mode.legacyBackgroundUpdates, serviceAvailable: true), mode)
        }
    }

    /// Gespeicherte Einstellungen einer älteren Version: die früheren Schalter → eine Wahl.
    func testDecodingOldSettingsMigrates() throws {
        let service = try JSONDecoder().decode(AppSettings.self,
                                               from: Data(#"{"backgroundUpdates":true,"liveService":true,"liveWebSocket":false}"#.utf8))
        XCTAssertEqual(service.updateMode, .LIVE_OFFEN)
        let socket = try JSONDecoder().decode(AppSettings.self,
                                              from: Data(#"{"backgroundUpdates":false,"liveService":false,"liveWebSocket":true}"#.utf8))
        XCTAssertEqual(socket.updateMode, .LIVE_OFFEN)
        let plain = try JSONDecoder().decode(AppSettings.self,
                                             from: Data(#"{"backgroundUpdates":true,"liveService":false,"liveWebSocket":false}"#.utf8))
        XCTAssertEqual(plain.updateMode, .SPARSAM)
        XCTAssertFalse(plain.liveWebSocket)
        let empty = try JSONDecoder().decode(AppSettings.self, from: Data("{}".utf8))
        XCTAssertEqual(empty.updateMode, .SPARSAM)
        let newer = try JSONDecoder().decode(AppSettings.self,
                                             from: Data(#"{"updateMode":"SPARSAM","liveWebSocket":true}"#.utf8))
        XCTAssertEqual(newer.updateMode, .SPARSAM)
    }

    func testEncodingWritesTheModeRoundTrip() throws {
        var s = AppSettings()
        s.updateMode = .SPARSAM
        let data = try JSONEncoder().encode(s)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["updateMode"] as? String, "SPARSAM")
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: data).updateMode, .SPARSAM)
    }
}

// MARK: - Kurs vorlesen

/// Wie `speaksPairPricesNeedsSpeechWithoutAlarmsOnly` (Android): «Kurs vorlesen» wirkt nur mit
/// Sprachausgabe an und «Nur Alarme» aus (Standard: beides nicht).
final class SpeaksPairPricesTests: XCTestCase {
    func testSpeaksPairPricesNeedsSpeechWithoutAlarmsOnly() {
        XCTAssertFalse(AppSettings().speaksPairPrices)
        var s = AppSettings()
        s.ttsEnabled = true
        XCTAssertFalse(s.speaksPairPrices)
        s.ttsAlarmsOnly = false
        XCTAssertTrue(s.speaksPairPrices)
        s.ttsEnabled = false
        XCTAssertFalse(s.speaksPairPrices)
    }
}

/// Keine Mitteilungs-Flut beim ersten Start (Build 42) — wie `NotificationDefaultsTest.kt`.
@MainActor
final class NotificationDefaultsTests: XCTestCase {

    /// Neue Paare (Start-Auswahl und später) mit Kurs-Mitteilung (Entscheid 16. Oktober 2026); gespeicherte
    /// behalten ihren Wert.
    func testNewPairsStartWithPriceNotification() throws {
        var s = SharedStorage.Snapshot()
        let market = try XCTUnwrap(MarketsConfig.market("Binance"))
        let id = try XCTUnwrap(AppData.insert(&s, market: market, pair: MarketPair("BTC", "USDT", "BTCUSDT")))
        XCTAssertEqual(s.watches.first { $0.id == id }?.notificationEnabled, true)
        XCTAssertTrue(Watch.newPairNotificationEnabled)
        // Sicherung ohne Feld (bis Build 41 war jedes Paar an): bleibt an — wie Android
        XCTAssertTrue(Watch.legacyNotificationEnabled)

        // Bestehende Paare ohne Angabe (ältere Version): bleiben an
        let old = try JSONDecoder().decode(Watch.self, from: Data(
            #"{"id":1,"marketKey":"Kraken","marketName":"Kraken","baseAsset":"BTC","quoteAsset":"EUR"}"#.utf8))
        XCTAssertTrue(old.notificationEnabled)
        let off = try JSONDecoder().decode(Watch.self, from: Data(
            #"{"id":1,"marketKey":"Kraken","marketName":"Kraken","baseAsset":"BTC","quoteAsset":"EUR","notificationEnabled":false}"#.utf8))
        XCTAssertFalse(off.notificationEnabled)
    }

    /// «Nicht wegwischbar» (auf iOS ohne Wirkung): Standard aus, gespeicherter Wert bleibt.
    func testOngoingDefaultOffStoredValueKept() throws {
        XCTAssertFalse(AppSettings().ongoingNotifications)
        let stored = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"ongoingNotifications":true}"#.utf8))
        XCTAssertTrue(stored.ongoingNotifications)
    }
}

/// Geänderte Standards von Build 43 nur für neue Installationen — wie `InstallDefaultsTest` (Android).
/// iOS speichert alle Einstellungen als Ganzes: Gespeicherte Einstellungen ohne den Wert stammen von vor
/// dem Schalter (bestehende Installation) und behalten den bisherigen Standard.
final class InstallDefaultsTests: XCTestCase {
    func testNewDefaults() {
        let d = AppSettings()
        XCTAssertTrue(d.watchlistNames)
        XCTAssertTrue(d.showConverted)
        XCTAssertFalse(d.includeAlphaTokens)
        XCTAssertFalse(AppSettings.legacyWatchlistNames)
        XCTAssertFalse(AppSettings.legacyShowConverted)
        XCTAssertTrue(AppSettings.legacyIncludeAlphaTokens)
        XCTAssertFalse(d.portfolioEnabled)
        XCTAssertTrue(AppSettings.legacyPortfolioEnabled)
    }

    func testDecisionFunction() {
        XCTAssertTrue(AppSettings.defaultForMigration(existingInstall: true, legacy: true, current: false))
        XCTAssertFalse(AppSettings.defaultForMigration(existingInstall: false, legacy: true, current: false))
    }

    /// Ältere gespeicherte Einstellungen ohne die Schlüssel: bisherige Standards.
    func testStoredSettingsWithoutKeysKeepOldDefaults() throws {
        let old = try JSONDecoder().decode(AppSettings.self, from: Data(#"{"darkMode":true}"#.utf8))
        XCTAssertFalse(old.watchlistNames)
        XCTAssertFalse(old.showConverted)
        XCTAssertTrue(old.includeAlphaTokens)
        XCTAssertTrue(old.portfolioEnabled)
    }

    /// Gespeicherte Werte bleiben, auch nach erneutem Speichern und Lesen.
    func testStoredValuesKept() throws {
        let stored = try JSONDecoder().decode(AppSettings.self, from: Data(
            #"{"watchlistNames":true,"showConverted":false,"includeAlphaTokens":false}"#.utf8))
        XCTAssertTrue(stored.watchlistNames)
        XCTAssertFalse(stored.showConverted)
        XCTAssertFalse(stored.includeAlphaTokens)
        let fresh = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(AppSettings()))
        XCTAssertTrue(fresh.watchlistNames)
        XCTAssertTrue(fresh.showConverted)
        XCTAssertFalse(fresh.includeAlphaTokens)
    }
}
