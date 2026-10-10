import SwiftUI

// «⚡ Ungewöhnliche Aktivität» in der Merkliste — wie `WatchlistActivity.kt`. «Warum bewegt sich
// das?» steht in `WatchWhySheet.swift` und `WatchWhyFactors.swift`.

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

/// Kleines ⚡ neben dem Paar; Tipp öffnet «Warum».
struct WatchlistActivityBolt: View {
    let action: () -> Void
    @Environment(\.appAccent) private var accent

    var body: some View {
        Button(action: action) {
            Image(systemName: "bolt.fill")
                .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                // In der Themenfarbe (der ⚡-Chip im Kopf ist neutral, gewählt ebenfalls in der Themenfarbe)
                .foregroundStyle(accent.primary)
                .frame(width: 24, height: 24)
                // Tippfläche 44 pt (sichtbar bleibt das kleine ⚡): ragt über den Rahmen hinaus,
                // ohne die Zeile höher zu machen — sonst öffnete ein knapper Tipp das Aktionsblatt
                .contentShape(Circle().inset(by: -10))
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L("activity_indicator"))
    }
}
