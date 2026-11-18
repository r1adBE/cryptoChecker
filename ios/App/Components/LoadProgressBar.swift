import SwiftUI

/// Dünner Ladebalken oben über dem Inhalt (`progress` 0–1, nil = keiner): rückt mit jedem fertigen Teil weich vor
/// und blendet aus, sobald alles da ist. Ersetzt beim Öffnen den Kreisel. In der Akzentfarbe (`tint`).
/// Wie Android `LoadProgressBar`.
struct LoadProgressBar: View {
    let progress: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(.tint.opacity(0.15))
                // Fertig (nil): voll, während er ausblendet
                Capsule().fill(.tint).frame(width: geo.size.width * min(max(progress ?? 1, 0), 1))
            }
        }
        .frame(height: 3)
        .opacity(progress == nil ? 0 : 1)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.35), value: progress)
        .allowsHitTesting(false)
        .accessibilityElement()
        .accessibilityLabel(L("loading_hint"))
        .accessibilityHidden(progress == nil)
    }
}
