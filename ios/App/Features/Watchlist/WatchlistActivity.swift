import SwiftUI

// «⚡ Ungewöhnliche Aktivität» in der Merkliste — wie `WatchlistActivity.kt`. «Was bewegt sich
// mit?» steht in `WatchWhySheet.swift` und `WatchWhyFactors.swift`.

/// Noch gültige Signale je Paar (stärkstes zuerst); Paare ohne Signal fehlen.
enum WatchlistActivity {
    /// Nach der gewählten Empfindlichkeit neu beurteilt (gleiche Schwellen wie die Mitteilungen).
    static func activeSignals(_ reports: [Int64: ActivityReport], now: Int64,
                              sensitivity: ActivitySensitivity) -> [Int64: [ActivitySignal]] {
        var out: [Int64: [ActivitySignal]] = [:]
        for (id, report) in reports {
            let signals = active(report, now: now, sensitivity: sensitivity)
            if !signals.isEmpty { out[id] = signals }
        }
        return out
    }

    /// Gültige Signale eines Paars nach der Empfindlichkeit, stärkstes zuerst.
    static func active(_ report: ActivityReport?, now: Int64, sensitivity: ActivitySensitivity) -> [ActivitySignal] {
        guard let report else { return [] }
        return ActivityAnalyzer.applySensitivity(report.active(now: now), sensitivity)
    }

    /// Paare mit Signalen (für den ⚡-Chip), starke zuerst, sonst Listenreihenfolge.
    static func hot(_ visible: [Watch], signals: [Int64: [ActivitySignal]]) -> [Watch] {
        let rank: (Watch) -> Int = { signals[$0.id]?.first?.severity.ordinal ?? 0 }
        return visible.enumerated()
            .filter { signals[$0.element.id] != nil }
            .sorted { a, b in
                let ra = rank(a.element), rb = rank(b.element)
                return ra != rb ? ra > rb : a.offset < b.offset
            }
            .map(\.element)
    }
}

/// ⚡-Chip neben dem Paar, beschriftet mit der Kurzform von «Was bewegt sich mit?» (`watch_action_why_short`); tippen
/// öffnet das Blatt «Was bewegt sich mit?». Sichtbar bleibt der 28-pt-Chip, das Layout unverändert; die Tippfläche ragt
/// je 8 pt seitlich (Build 70), aber nur je 4 pt darüber und darunter hinaus: Sie bleibt im Innenabstand der eigenen Zeile
/// und reicht nie in die Nachbarzeile (wie Android). Reicht die Breite nicht, bleibt nur der ⚡ (das Paar geht vor).
struct WatchlistActivityBolt: View {
    let action: () -> Void
    @Environment(\.appAccent) private var accent
    @Environment(\.rowHighlight) private var highlight

    var body: some View {
        // Weiss (Textfarbe); in der Themenfarbe nur, solange der ⚡-Chip gewählt ist
        let tint: Color = highlight.activity ? accent.primary : AppColors.onSurface
        Button(action: action) {
            ViewThatFits(in: .horizontal) {
                chip(tint: tint, withText: true)
                chip(tint: tint, withText: false)
            }
            // Tippfläche: 4 pt über und unter dem Chip (in der eigenen Zeile) und 8 pt seitlich; nach aussen wieder die
            // Chip-Grösse
            .padding(.vertical, Self.touchExtra)
            .padding(.horizontal, Self.touchSide)
            .contentShape(Rectangle())
            .padding(.vertical, -Self.touchExtra)
            .padding(.horizontal, -Self.touchSide)
        }
        .buttonStyle(.borderless)
        // Screenreader: «Ungewöhnliche Aktivität, Was bewegt sich mit?»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("activity_indicator") + ", " + L("why_action"))
        .accessibilityAddTraits(.isButton)
    }

    /// 28 + 2 × 4 = 36 pt hoch: bleibt im Innenabstand der Zeile, ragt nicht in die Nachbarzeile.
    private static let touchExtra: CGFloat = 4
    /// Seitlich je 8 pt (Touch-Ziel mindestens 44 pt): der Chip nur mit ⚡ ist etwa 28 pt breit, antippbar also gut 44 pt.
    private static let touchSide: CGFloat = 8

    /// Sichtbarer Chip: ⚡ mit oder ohne «Was bewegt sich mit?».
    private func chip(tint: Color, withText: Bool) -> some View {
        HStack(spacing: 2) {
            Image(systemName: "bolt.fill")
                .scaledFont(size: 11, weight: .bold, relativeTo: .caption)
            // Kurzform im Chip (eine Zeile neben dem Paar), die volle Frage liest VoiceOver (wie Android)
            if withText {
                Text(L("watch_action_why_short"))
                    .scaledFont(size: 12, weight: .semibold, relativeTo: .caption)
                    .lineLimit(1)
            }
        }
        .foregroundStyle(tint)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .padding(.horizontal, Spacing.sm)
        .frame(minHeight: 28)
        .background(tint.opacity(0.12), in: Capsule())
        .fixedSize()
    }
}
