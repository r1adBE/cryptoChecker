import SwiftUI

/// Hülle für alle form-gleichen Platzhalter (Merkliste, Starter, «Warum?», Chart, Markt-Tab) —
/// wie `SkeletonPulse` in Android: pulsiert ruhig, bei reduzierter Bewegung stehend. Einziger Puls.
/// Für VoiceOver ausgeblendet (die echten Titel daneben bleiben lesbar).
///
/// Die Deckkraft kommt aus der Zeit (`TimelineView`), nicht aus einer Animation: so
/// läuft keine animierte Transaktion durch den Platzhalter, und verschiebt sich die Karte
/// (Inhalt darüber ändert sich), gleitet der Platzhalter nicht hinterher.
struct SkeletonPulse<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Für VoiceOver statt nichts eine Zeile (z. B. «Wird geladen …»); nil = ausgeblendet.
    let label: String?
    @ViewBuilder let content: () -> Content

    init(label: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.label = label
        self.content = content
    }

    var body: some View {
        Group {
            if reduceMotion {
                content()
                    .opacity(0.7)
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: false)) { context in
                    content()
                        .opacity(Self.pulse(at: context.date))
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label ?? "")
        .accessibilityHidden(label == nil)
    }

    /// 0,45 … 1 … 0,45 in 1,8 s (wie Android: 900 ms hin, 900 ms zurück, sanft).
    private static func pulse(at date: Date) -> Double {
        let period = 1.8
        let phase = date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period
        return 0.45 + 0.55 * (0.5 - 0.5 * cos(2 * Double.pi * phase))
    }
}

/// Grauer Block fester Grösse (z. B. statt einer Zahl) — wie `SkeletonBlock` in Android.
/// Pulsiert nicht selbst; dafür in `SkeletonPulse` legen.
struct SkeletonBlock: View {
    var width: CGFloat
    var height: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
            .fill(AppColors.containerHighest)
            .frame(width: width, height: height)
    }
}
