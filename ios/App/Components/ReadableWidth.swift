import SwiftUI

/// Lesbare Höchstbreite des Inhalts auf breiten Bildschirmen (iPad, Querformat):
/// Karten laufen nicht mehr über die ganze Breite, sondern stehen mittig.
/// Hintergründe und die Scroll-Fläche bleiben voll breit. Wie Android `ReadableWidth.kt`.
enum ReadableWidth {
    static let max: CGFloat = 640
}

extension View {
    /// Inhalt höchstens `ReadableWidth.max` breit und mittig. Innerhalb einer `ScrollView`
    /// auf den Inhalt anwenden — die ScrollView selbst bleibt voll breit und überall scrollbar.
    /// Auf dem iPhone hochkant ändert sich nichts.
    func readableContentWidth() -> some View {
        frame(maxWidth: ReadableWidth.max)
            .frame(maxWidth: .infinity)
    }
}

extension View {
    /// Für `List`: seitlicher Rand der Scroll-Inhalte, damit die Zeilen höchstens
    /// `ReadableWidth.max` breit sind. Die Liste selbst bleibt voll breit und überall
    /// scrollbar; Wischaktionen bleiben an der Zeile.
    func readableListMargins() -> some View {
        modifier(ReadableListMargins())
    }
}

/// Misst die Breite der Liste (ohne das Layout zu ändern) und setzt daraus den Rand.
private struct ReadableListMargins: ViewModifier {
    @State private var width: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, max(0, (width - ReadableWidth.max) / 2), for: .scrollContent)
            .background {
                GeometryReader { geo in
                    Color.clear
                        .onAppear { width = geo.size.width }
                        .onChange(of: geo.size.width) { _, newWidth in width = newWidth }
                }
            }
    }
}
