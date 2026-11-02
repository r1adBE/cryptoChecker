import SwiftUI

/// Ersatz-Kreis ohne Logo (unbekannt, Fehler, noch nicht geladen; auch TradFi ohne Logo und DEX-Pools): farbiger Kreis
/// mit den Initialen (`CoinLogos.initials`). Die Farbe folgt fest aus dem Kürzel — dasselbe Kürzel hat überall
/// dieselbe Farbe (App, Widgets, iOS und Android), verschiedene Kürzel verteilen sich auf die Palette. Gleich in
/// Android `CoinLogoPlaceholder.kt`, gemeinsame Testfälle `testdata/parity/coin_placeholder.json`.
///
/// Kontrast: Schrift `textLight` auf jeder Füllung von `fillsLight` bzw. `textDark` auf `fillsDark` mindestens 4.5:1
/// (WCAG AA, geprüft im Test).
enum CoinLogoPlaceholder {
    /// Füllungen hell: kräftige, eher dunkle Töne (blau, petrol, grün, violett, beere, rost, braun, indigo).
    static let fillsLight: [UInt32] = [0x2B5FC8, 0x00796B, 0x2E7D32, 0x6A3FB5, 0xAD1457, 0xB3401A, 0x6D4C41, 0x3949AB]
    /// Füllungen dunkel: dieselben Töne hell und gedämpft.
    static let fillsDark: [UInt32] = [0x9DBBF5, 0x7FCBC2, 0xA5D6A7, 0xC6AEF2, 0xF4A3C0, 0xFFAE8F, 0xC9B3AA, 0xA7B0E6]
    /// Schrift hell (weiss) bzw. dunkel (fast schwarz).
    static let textLight: UInt32 = 0xFFFFFF
    static let textDark: UInt32 = 0x141414
    /// Dauer des Einblendens, wenn das Logo kommt (ohne Animation bei «Bewegung reduzieren»), in ms.
    static let fadeMillis = 220

    /// Kürzel, aus dem die Farbe folgt: wie die Initialen — Krypto ohne Hebel-Präfix (`CoinLogos.stripMultiplier`:
    /// «1000PEPE» wie «PEPE»), TradFi ohne `CoinLogos.tradFiPrefix` («TRADFI:NVDA» wie «NVDA»), gross.
    static func key(_ symbol: String) -> String {
        symbol.hasPrefix(CoinLogos.tradFiPrefix)
            ? CoinLogos.displaySymbol(symbol).trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            : CoinLogos.stripMultiplier(symbol)
    }

    /// Stelle in der Palette: FNV-1a (32 Bit) über die UTF-8-Bytes von `key`, modulo Anzahl Farben — wie Android.
    static func index(_ symbol: String) -> Int {
        var hash: UInt32 = 0x811C_9DC5
        for byte in key(symbol).utf8 {
            hash = (hash ^ UInt32(byte)) &* 0x0100_0193
        }
        return Int(hash % UInt32(fillsLight.count))
    }

    /// Füllung (RGB) des Kreises für `symbol`.
    static func fillHex(_ symbol: String, dark: Bool) -> UInt32 {
        (dark ? fillsDark : fillsLight)[index(symbol)]
    }

    /// Farbe der Initialen (RGB).
    static func textHex(dark: Bool) -> UInt32 { dark ? textDark : textLight }

    static func fill(_ symbol: String, dark: Bool) -> Color { color(fillHex(symbol, dark: dark)) }

    static func text(dark: Bool) -> Color { color(textHex(dark: dark)) }

    private static func color(_ rgb: UInt32) -> Color {
        Color(.sRGB, red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255,
              blue: Double(rgb & 0xFF) / 255, opacity: 1)
    }
}
