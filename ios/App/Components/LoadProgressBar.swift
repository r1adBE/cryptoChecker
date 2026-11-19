import SwiftUI

/// Dünner Ladebalken oben über dem Inhalt (`loading` 0–1, nil = es lädt nichts): erscheint erst, wenn das Laden länger
/// als `LoadProgress.showAfterMillis` dauert, rückt mit jedem fertigen Teil weich vor und blendet aus, sobald alles da
/// ist. Merkliste, Markt und Portfolio; in der Akzentfarbe (`tint`). Wie Android `LoadProgressBar`.
struct LoadProgressBar: View {
    let loading: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Läuft schon länger als `showAfterMillis` ohne Unterbrechung.
    @State private var due = false

    private var progress: Double? { due ? loading : nil }

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
        .task(id: loading != nil) {
            due = false
            guard loading != nil else { return }
            try? await Task.sleep(nanoseconds: LoadProgress.showAfterMillis * 1_000_000)
            if !Task.isCancelled { due = true }
        }
    }
}

/// Kreisel des Ziehens (`.refreshable`): nur kurz als «verstanden» (`LoadProgress.pullAckMillis`) bzw. bis das Laden
/// fertig ist — `.refreshable` wartet nur so lange, das Laden selbst läuft weiter, danach übernimmt der Balken.
/// Wie Android `rememberPullAck`.
enum PullAck {
    /// - Returns: das Ergebnis von `work`, wenn es innerhalb der Zeit fertig wurde; sonst nil (es läuft weiter).
    @MainActor
    @discardableResult
    static func run<T: Sendable>(_ work: @escaping @Sendable @MainActor () async -> T) async -> T? {
        let state = Finished<T>()
        Task { @MainActor in
            state.value = await work()
        }
        // In kleinen Schritten warten: fertig oder die Zeit des «Verstanden» ist um (das Laden läuft weiter)
        var waited: UInt64 = 0
        while state.value == nil && waited < LoadProgress.pullAckMillis {
            try? await Task.sleep(nanoseconds: step * 1_000_000)
            waited += step
        }
        return state.value
    }

    private static let step: UInt64 = 100

    @MainActor
    private final class Finished<T> {
        var value: T?
    }
}
