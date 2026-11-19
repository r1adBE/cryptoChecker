import SwiftUI

/// Dünner Ladebalken oben über dem Inhalt (`loading` 0–1, nil = es lädt nichts): erscheint sofort, sobald etwas lädt
/// (Inhaber: «sofort anzeigen»), rückt mit jedem fertigen Teil weich vor und blendet aus, sobald alles da ist.
/// Merkliste, Markt und Portfolio; in der Akzentfarbe (`tint`). Wie Android `LoadProgressBar`.
struct LoadProgressBar: View {
    let loading: Double?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var progress: Double? { loading }

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

/// Kreisel des Ziehens (`.refreshable`): genau `LoadProgress.pullAckMillis` als «verstanden» — auch wenn das Laden
/// früher fertig ist (überall gleich wie auf der Uhr); `.refreshable` wartet nur so lange, das Laden selbst läuft
/// weiter, danach übernimmt der Balken. Wie Android `rememberPullAck`.
enum PullAck {
    /// - Returns: das Ergebnis von `work`, wenn es innerhalb der Zeit fertig wurde; sonst nil (es läuft weiter).
    @MainActor
    @discardableResult
    static func run<T: Sendable>(_ work: @escaping @Sendable @MainActor () async -> T) async -> T? {
        let state = Finished<T>()
        Task { @MainActor in
            state.value = await work()
        }
        // Die Zeit des «Verstanden» ganz abwarten (das Laden läuft weiter, wenn es länger dauert)
        var waited: UInt64 = 0
        while waited < LoadProgress.pullAckMillis {
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
