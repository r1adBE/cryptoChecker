import SwiftUI

// Nebenzeile(n) des Widgets «Was gerade auffällt» mit Farben (wie Android `PulseSideLine.kt`).

/// Nebenzeile mit Farben wie in der App (wie Android `PulseWidgetRenderer.sideLine`): die Fear-&-Greed-Zahl
/// in der Textfarbe (wie die Karte im Markt-Tab), davor ein Stufenpunkt in der Skala aus den Kursfarben, «↗ 21» steigend
/// und «↘ 9» fallend in der Kursfarbe; der Rest grau. Nur Farben — die Breite bleibt gleich.
enum PulseSideLine {
    /// Stufenpunkt vor Fear & Greed (wie Android `PulseWidgetRenderer.FNG_DOT`).
    static let dot = "\u{25CF}"

    /// Farbe der Stufe aus den Kursfarben des Widgets (`WidgetPalette.scaleSteps`, ≥ 3:1 als Grafik) — passt so
    /// genau zu den Pfeilen.
    static func stepColor(_ value: Int, palette: WidgetPalette) -> Color {
        Color(hex: palette.scaleSteps[MarketScalePalette.level(value)])
    }

    /// «● Fear & Greed 72 · Gier»: Punkt in der Stufenfarbe, Zahl in der Textfarbe.
    static func fearGreed(_ value: Int, palette: WidgetPalette) -> Text {
        let line = FearGreedShared.line(value)
        let number = LocaleNumbers.integer(value)
        let dotText = Text(verbatim: dot + " ").foregroundStyle(stepColor(value, palette: palette))
        guard let range = line.range(of: number) else { return dotText + Text(line) }
        let head = Text(verbatim: String(line[..<range.lowerBound]))
        let tail = Text(verbatim: String(line[range.upperBound...]))
        // Zahl in Textfarbe statt Stufenfarbe (AA, Build 43, wie die Karte); die Stufe zeigt der Punkt
        return dotText + head + Text(verbatim: number).foregroundStyle(palette.text) + tail
    }

    /// Punkt vor «Top 30» in der zweiten Zeile: Kursfarbe der Mehrheit (steigend/fallend), gleich viele → grau.
    static func breadthDot(_ b: PulseBreadth, palette: WidgetPalette) -> Text {
        let color = b.up > b.down ? palette.up : (b.down > b.up ? palette.down : palette.secondary)
        return Text(verbatim: dot + " ").foregroundStyle(color)
    }

    /// «Top 30 · 24h  ↗ 21  ↘ 9»: Marktbreite über 24 Stunden.
    static func breadth(_ b: PulseBreadth, palette: WidgetPalette) -> Text {
        let label = L("pulse_breadth_label", b.total) + " · " + L("widget_range_short_24h") + "  "
        let up = Text(verbatim: "\(PriceFormat.arrowUp) \(LocaleNumbers.integer(b.up))").foregroundStyle(palette.up)
        let down = Text(verbatim: "\(PriceFormat.arrowDown) \(LocaleNumbers.integer(b.down))").foregroundStyle(palette.down)
        return Text(verbatim: label) + up + Text(verbatim: "  ") + down
    }
}
