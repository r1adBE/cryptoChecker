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

    /// Paare der aktuellen Ansicht mit Signalen, starke zuerst, sonst Listenreihenfolge.
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

    var body: some View {
        Button(action: action) {
            Image(systemName: "bolt.fill")
                .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(AppColors.warning)
                .frame(width: 24, height: 24)
                // Tippfläche 44 pt (sichtbar bleibt das kleine ⚡): ragt über den Rahmen hinaus,
                // ohne die Zeile höher zu machen — sonst öffnete ein knapper Tipp das Aktionsblatt
                .contentShape(Circle().inset(by: -10))
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L("activity_indicator"))
    }
}

/// Schlanke Karte: «⚡ Hier passiert gerade etwas» mit bis zu vier Coins.
/// «+n» klappt alle übrigen umbrechend auf, «−» wieder zu. Tipp auf einen
/// Coin öffnet dessen «Warum»-Blatt.
@MainActor
struct WatchlistActivityCard: View {
    let hot: [Watch]
    /// Höchstens so viele Coins (Empfindlichkeit «Weniger»: die 3 stärksten); nil = alle.
    var limit: Int? = nil
    let onOpen: (Watch) -> Void
    /// «Anpassen»: öffnet die Einstellung «Empfindlichkeit»; nil = ohne Link.
    var onAdjust: (() -> Void)? = nil

    @Environment(\.appAccent) private var accent
    @State private var expanded = false
    private static let maxCoins = 4

    var body: some View {
        // Gleicher Coin an mehreren Börsen: einmal zeigen
        var seen = Set<String>()
        let distinct = hot.filter { seen.insert($0.baseAsset.uppercased()).inserted }
        // `hot` ist nach Stärke sortiert
        let coins = limit.map { Array(distinct.prefix($0)) } ?? distinct
        let shown = Array(coins.prefix(Self.maxCoins))
        let more = coins.count - shown.count
        let visible = expanded ? coins : shown
        let amber = AppColors.warning

        return VStack(alignment: .leading, spacing: Spacing.sm) {
            HStack(spacing: Spacing.sm) {
                Image(systemName: "bolt.fill")
                    .scaledFont(size: 13, weight: .bold, relativeTo: .footnote)
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .foregroundStyle(amber)
                    .frame(width: 28, height: 28)
                    .background(amber.opacity(0.16), in: Circle())
                Text(L("activity_card_title"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                    .frame(maxWidth: .infinity, alignment: .leading)
                // Kleiner Link zur Empfindlichkeit (zu viele/zu wenige Coins markiert?)
                if let onAdjust {
                    Button(L("activity_adjust")) {
                        WatchlistHaptics.selection()
                        onAdjust()
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .buttonStyle(.borderless)
                    .lineLimit(1)
                    .accessibilityLabel(L("activity_adjust_a11y"))
                }
            }

            FlowLayout(spacing: 8) {
                ForEach(visible) { watch in
                    coinChip(watch)
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                }
                if more > 0 {
                    Button {
                        WatchlistHaptics.selection()
                        withAnimation(.spring(duration: 0.35)) { expanded.toggle() }
                    } label: {
                        Text(expanded ? "−" : L("activity_more", more))
                            .font(.subheadline.weight(.semibold).monospacedDigit())
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .frame(minWidth: 22)
                            .padding(.horizontal, 12)
                            .padding(.vertical, Spacing.sm)
                            .overlay(Capsule().strokeBorder(AppColors.outlineVariant, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.borderless)
                }
            }
        }
        .padding(.horizontal, Spacing.md)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.container, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(amber.opacity(0.35), lineWidth: 1)
        )
    }

    private func coinChip(_ watch: Watch) -> some View {
        Button {
            WatchlistHaptics.selection()
            onOpen(watch)
        } label: {
            HStack(spacing: Spacing.xs) {
                CoinBadge(symbol: watch.baseAsset, size: 18, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                          pair: watch.logoPairKey)
                Text(watch.baseAsset)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
            }
            .padding(.leading, Spacing.xs)
            .padding(.trailing, 12)
            .padding(.vertical, Spacing.xs)
            .background(AppColors.warning.opacity(0.10), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("\(watch.baseAsset) · \(L("why_action"))")
    }
}
