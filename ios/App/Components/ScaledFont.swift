import SwiftUI

/// Feste Schriftgrösse, die trotzdem der Textgrösse des Systems folgt (Dynamic Type):
/// `size` gilt für die Standardgrösse «Large» und wächst bzw. schrumpft wie `relativeTo`.
/// Ersetzt `.font(.system(size: …))` in der App (Widgets bleiben fest).
/// Wo ein Layout sonst bricht (Pillen, enge Zeilen), begrenzt der umgebende View das
/// Wachstum mit `.dynamicTypeSize(...DynamicTypeSize.accessibility2)`.
struct ScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight
    private let design: Font.Design
    private let monospacedDigit: Bool

    init(size: CGFloat, weight: Font.Weight, design: Font.Design, relativeTo: Font.TextStyle, monospacedDigit: Bool) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: relativeTo)
        self.weight = weight
        self.design = design
        self.monospacedDigit = monospacedDigit
    }

    private var font: Font {
        let base = Font.system(size: size, weight: weight, design: design)
        return monospacedDigit ? base.monospacedDigit() : base
    }

    func body(content: Content) -> some View {
        content.font(font)
    }
}

extension View {
    /// Wie `.font(.system(size:weight:design:))`, aber mit Dynamic Type (siehe `ScaledFont`).
    func scaledFont(size: CGFloat, weight: Font.Weight = .regular, design: Font.Design = .default,
                    relativeTo: Font.TextStyle = .body, monospacedDigit: Bool = false) -> some View {
        modifier(ScaledFont(size: size, weight: weight, design: design, relativeTo: relativeTo,
                            monospacedDigit: monospacedDigit))
    }
}
