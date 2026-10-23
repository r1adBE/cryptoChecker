import SwiftUI

/// Grosse Schrift in dichten Zeilen (Merkliste, Positionen des Portfolios): umbrechen statt abschneiden.
/// `scale` = Schriftfaktor (Textgrösse der Systemschrift «body» / 17 pt; Android `fontScale`).
///
///  - ab `wrapFrom` (iOS xxLarge, Android 1.3) dürfen Paar bzw. Coin und Zeitzeile zwei Zeilen nehmen,
///  - ab `stackFrom` (iOS accessibility1 …, Android 1.5 … 2.0) steht die rechte Spalte (Kurs bzw. Wert)
///    unter dem Paar statt daneben — so bleibt links genug Breite, nichts wird abgeschnitten.
///
/// Gleiche Werte wie Android `DenseRowText.kt`.
enum DenseRowText {
    static let wrapFrom: Double = 1.2
    static let stackFrom: Double = 1.45

    /// Höchstens so viele Zeilen für Paar/Coin und Zeitzeile.
    static func maxLines(_ scale: Double) -> Int { scale >= wrapFrom ? 2 : 1 }

    /// Rechte Spalte unter die linke stellen?
    static func stacksTrailing(_ scale: Double) -> Bool { scale >= stackFrom }

    /// Zeilen schmaler als das (pt/dp) gelten als schmal: Mini-Chart dort schmaler.
    static let narrowRow: Double = 360
    static let sparklineWidthRegular: Double = 56
    static let sparklineWidthNarrow: Double = 48

    /// Breite des Mini-Charts der Merkliste für eine Zeile von `rowWidth` pt: 48 statt 56 in schmalen
    /// Zeilen, damit das Paar dort mehr Platz behält (Kurs-Spalte bleibt unberührt). Wie Android.
    static func sparklineWidth(rowWidth: Double) -> Double {
        rowWidth < narrowRow ? sparklineWidthNarrow : sparklineWidthRegular
    }

    /// Höchstbreite der ≈ Umrechnung neben dem Paar: die Hälfte der Zeile. Längeres (Vermerk dahinter) wird mit «…»
    /// gekürzt — sonst drückt die Kurs-Spalte Paar, Name und Börse auf Breite 0 (Gerätetest 23.10.2026). Wie Android.
    static func convertedMaxWidth(rowWidth: Double) -> Double { rowWidth * 0.5 }

    /// Kurs der Merkliste eine Stufe grösser (iOS `.title3`, Android `titleLarge`)? Unter dem Paar
    /// (gestapelt) immer, daneben nur bei normaler Schrift in Zeilen ab `narrowRow` — sonst bliebe dem
    /// Paar neben Logo, Mini-Chart und Kurs kaum Breite. Wie Android.
    static func largePrice(scale: Double, rowWidth: Double) -> Bool {
        stacksTrailing(scale) || (scale < wrapFrom && rowWidth >= narrowRow)
    }

    /// Schriftfaktor einer Textgrösse: Punktgrösse von «body» geteilt durch 17 pt (Standard «large»).
    static func scale(_ size: DynamicTypeSize) -> Double {
        let body: Double
        switch size {
        case .xSmall: body = 14
        case .small: body = 15
        case .medium: body = 16
        case .large: body = 17
        case .xLarge: body = 19
        case .xxLarge: body = 21
        case .xxxLarge: body = 23
        case .accessibility1: body = 28
        case .accessibility2: body = 33
        case .accessibility3: body = 40
        case .accessibility4: body = 47
        case .accessibility5: body = 53
        @unknown default: body = 17
        }
        return body / 17
    }
}
