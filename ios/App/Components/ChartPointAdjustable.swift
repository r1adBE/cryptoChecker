import SwiftUI

/// Punkt für Punkt durch einen Chart (VoiceOver): «Nächster Punkt» beginnt ohne Auswahl beim ersten Punkt,
/// «Vorheriger Punkt» beim letzten; an den Enden bleibt der Punkt stehen. Wie Android `ChartPointStep`.
enum ChartPointStep {
    /// Index nach «Nächster Punkt»; nil bei leerer Reihe.
    static func next(_ current: Int?, count: Int) -> Int? {
        guard count > 0 else { return nil }
        guard let current else { return 0 }
        return Swift.min(Swift.max(current + 1, 0), count - 1)
    }

    /// Index nach «Vorheriger Punkt»; nil bei leerer Reihe.
    static func previous(_ current: Int?, count: Int) -> Int? {
        guard count > 0 else { return nil }
        guard let current else { return count - 1 }
        return Swift.min(Swift.max(current - 1, 0), count - 1)
    }
}

/// Chart als einstellbares VoiceOver-Element (wie Android `chartPointSemantics`): Wischen nach oben = nächster,
/// nach unten = vorheriger Punkt; der Wert des Elements ist der gewählte Punkt («Di 14:00: 98’450 USDT»,
/// `pointText`). Das Label (Chart-Satz) bleibt. Ohne Punkte (`count` 0) unverändert. Neue Daten (`resetKey`)
/// heben die Auswahl auf.
struct ChartPointAdjustable: ViewModifier {
    let count: Int
    let resetKey: Int
    let pointText: (Int) -> String
    @State private var index: Int?

    func body(content: Content) -> some View {
        if count > 0 {
            content
                .accessibilityValue(index.flatMap { $0 < count ? pointText($0) : nil } ?? "")
                .accessibilityAdjustableAction { direction in
                    switch direction {
                    case .increment: index = ChartPointStep.next(index, count: count)
                    case .decrement: index = ChartPointStep.previous(index, count: count)
                    @unknown default: break
                    }
                }
                .onChange(of: resetKey) { _, _ in index = nil }
        } else {
            content
        }
    }
}

extension View {
    /// Siehe `ChartPointAdjustable`.
    func chartPointAdjustable(count: Int, resetKey: Int, pointText: @escaping (Int) -> String) -> some View {
        modifier(ChartPointAdjustable(count: count, resetKey: resetKey, pointText: pointText))
    }
}
