import SwiftUI

// «⚡ Ungewöhnliche Aktivität» und «💡 Warum bewegt sich das?» in der Merkliste —
// wie `ActivityUi.kt`.

/// Warmes Bernstein für ⚡ — unabhängig von der Akzentfarbe, damit es bei
/// jedem Akzent als «Achtung, hier ist etwas» lesbar bleibt.
enum WatchlistActivityColors {
    static let amber = Color.dynamic(light: 0xB26B00, dark: 0xFFC94D)
    /// Bernstein für Text-Markierungen («!» im «Warum»-Blatt): hell dunkler,
    /// damit es auch auf den Karten AA (≥ 4.5:1) erreicht.
    static let cautionText = Color.dynamic(light: 0x8A5300, dark: 0xFFC94D)
}

/// Noch gültige Signale je Paar (stärkstes zuerst); Paare ohne Signal fehlen.
enum WatchlistActivity {
    static func activeSignals(_ reports: [Int64: ActivityReport], now: Int64) -> [Int64: [ActivitySignal]] {
        var out: [Int64: [ActivitySignal]] = [:]
        for (id, report) in reports {
            let active = report.active(now: now)
            if !active.isEmpty { out[id] = active }
        }
        return out
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

// MARK: ⚡ in der Zeile

/// Kleines ⚡ neben dem Paar; Tipp öffnet «Warum».
struct WatchlistActivityBolt: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "bolt.fill")
                .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .foregroundStyle(WatchlistActivityColors.amber)
                .frame(width: 24, height: 24)
                .contentShape(Circle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(L("activity_indicator"))
    }
}

// MARK: Karte über der Liste

/// Schlanke Karte: «⚡ Hier passiert gerade etwas» mit bis zu vier Coins.
/// «+n» klappt alle übrigen umbrechend auf, «−» wieder zu. Tipp auf einen
/// Coin öffnet dessen «Warum»-Blatt.
@MainActor
struct WatchlistActivityCard: View {
    let hot: [Watch]
    let onOpen: (Watch) -> Void

    @State private var expanded = false
    private static let maxCoins = 4

    var body: some View {
        // Gleicher Coin an mehreren Börsen: einmal zeigen
        var seen = Set<String>()
        let coins = hot.filter { seen.insert($0.baseAsset.uppercased()).inserted }
        let shown = Array(coins.prefix(Self.maxCoins))
        let more = coins.count - shown.count
        let visible = expanded ? coins : shown
        let amber = WatchlistActivityColors.amber

        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
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
                            .padding(.vertical, 7)
                            .overlay(Capsule().strokeBorder(AppColors.outlineVariant, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.borderless)
                }
            }
        }
        .padding(.horizontal, 14)
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
            HStack(spacing: 6) {
                CoinBadge(symbol: watch.baseAsset, size: 18)
                Text(watch.baseAsset)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(AppColors.onSurface)
                    .lineLimit(1)
            }
            .padding(.leading, 6)
            .padding(.trailing, 12)
            .padding(.vertical, 5)
            .background(WatchlistActivityColors.amber.opacity(0.10), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("\(watch.baseAsset) · \(L("why_action"))")
    }
}

// MARK: «Warum bewegt sich das?»

/// Ladezustand des «Warum»-Blatts.
private enum WatchlistWhyState: Equatable {
    case loading
    case loaded(WhyReport)
    case failed
}

/// «Warum bewegt sich BTC?» als Erklärmoment: zuerst «Kurz gesagt» (ein, zwei
/// Sätze), dann die 2–5 Gründe als kompakte Checkliste ✓ / – / ! (gleiche Zeile
/// wie im Crypto Pulse). Erklärungen nur bei «!» oder unter «Details anzeigen».
/// Nur Marktdaten: Markt vs. Coin, Volumen, Hebel, Volatilität, Stimmung.
/// Kein `NavigationStack` nötig. Wie `WhySheet` in `ActivityUi.kt`.
struct WatchlistWhySheet: View {
    let watchId: Int64

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var state: WatchlistWhyState = .loading
    @State private var attempt = 0

    var body: some View {
        if let watch = data.watch(watchId) {
            content(watch)
        } else {
            // Paar wurde inzwischen gelöscht
            Color.clear.onAppear { dismiss() }
        }
    }

    private func content(_ watch: Watch) -> some View {
        let report: WhyReport? = {
            if case .loaded(let r) = state { return r }
            return nil
        }()
        let signals = data.activityReports[watch.id]?.active(now: TimeUtils.nowMillis) ?? []

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Kopf: Frage, Paar, Kurs, 1h/24h
                HStack(spacing: 8) {
                    Image(systemName: "lightbulb.fill")
                        .scaledFont(size: 15, weight: .semibold, relativeTo: .subheadline)
                        .foregroundStyle(accent.primary)
                    Text(watch.baseAsset.isEmpty ? L("why_title") : L("why_title_coin", watch.baseAsset))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                HStack(spacing: 12) {
                    CoinBadge(symbol: watch.baseAsset, size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(watch.displayName)
                            .font(.title2.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(watch.marketName)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .padding(.top, 10)

                HStack(spacing: 8) {
                    Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                        .font(.system(.title3, design: .rounded).weight(.semibold).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    WatchlistWhyChangeBadge(label: L("why_change_1h"), change: report?.change1h)
                    WatchlistWhyChangeBadge(label: L("why_change_24h"), change: report?.change24h)
                }
                .padding(.top, 12)
                .padding(.bottom, 16)

                // «Kurz gesagt»: erstes und wichtigstes Element, aus denselben Gründen wie darunter
                if let report, report.hasMarketData {
                    let summary = WhySummary.keys(report.reasons)
                    if !summary.isEmpty {
                        WatchlistWhySummaryCard(text: summary.map { L($0) }.joined(separator: " "))
                            .padding(.bottom, 12)
                    }
                }

                // Was gerade auffällt (die Signale hinter dem ⚡)
                if !signals.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(signals.prefix(3).enumerated()), id: \.offset) { _, signal in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Image(systemName: "bolt.fill")
                                    .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                    .foregroundStyle(WatchlistActivityColors.amber)
                                Text(ActivityTexts.signal(signal))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(AppColors.onSurface)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .padding(.bottom, 14)
                }

                switch state {
                case .loading:
                    WatchlistWhySkeleton()
                        .transition(.opacity)
                case .failed:
                    WatchlistWhyEmpty(onRetry: { attempt += 1 })
                case .loaded(let r):
                    VStack(spacing: 10) {
                        if !r.hasMarketData {
                            WatchlistWhyEmpty(onRetry: nil)
                        }
                        if !r.reasons.isEmpty {
                            WatchlistWhyChecklist(reasons: r.reasons)
                        }
                    }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
                }

                // Fusszeile: Hinweis und Datenzeit
                Text(L("why_disclaimer"))
                    .font(.caption)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text(L("pulse_disclaimer"))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
                if let report {
                    Text(L("why_data_time", PriceFormat.time(report.dataTime)))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 22)
            .padding(.top, 24)
            .padding(.bottom, 16)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: state)
        }
        .scrollIndicators(.hidden)
        .background(AppColors.background.ignoresSafeArea())
        .task(id: attempt) {
            state = .loading
            if let loaded = await data.explain(watch) {
                state = .loaded(loaded)
            } else if !Task.isCancelled {
                state = .failed
            }
        }
    }
}

/// «1h +2.31 %» — grün/rot, grau bei 0.00 %; «—» ohne Daten.
private struct WatchlistWhyChangeBadge: View {
    let label: String
    let change: Double?

    var body: some View {
        let zero = change.map { abs($0) < 0.005 } ?? true
        let color = zero ? AppColors.onSurfaceVariant : PriceColors.forChange(change ?? 0)
        HStack(spacing: 5) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
            ChangeArrowIcon(change: change)
                .scaledFont(size: 9, weight: .bold, relativeTo: .caption2)
                .foregroundStyle(color)
            Text(change.map { ActivityTexts.percent($0, 2) } ?? "—")
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(color)
                .contentTransition(.numericText())
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .background(color.opacity(0.12), in: Capsule())
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }
}

/// Gründe als Checkliste. Erklärungen: bei «!» immer, sonst erst nach
/// «Details anzeigen» (nur angeboten, wenn es etwas aufzuklappen gibt).
@MainActor
private struct WatchlistWhyChecklist: View {
    let reasons: [WhyReason]

    @Environment(\.appAccent) private var accent
    @State private var showDetails = false

    var body: some View {
        let marks = reasons.map(WhySummary.mark)
        let hasHidden = marks.contains { $0 != .caution }
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(reasons.enumerated()), id: \.offset) { index, reason in
                let mark = marks[index]
                let factor = WatchlistWhyTexts.factor(reason)
                FactorRow(mark: mark, title: factor.title, value: factor.value,
                          spokenValue: factor.spokenValue,
                          detail: (showDetails || mark == .caution) ? WatchlistWhyTexts.explanation(reason) : nil)
            }
            if hasHidden {
                Button {
                    WatchlistHaptics.selection()
                    showDetails.toggle()
                } label: {
                    Text(L(showDetails ? "why_details_hide" : "why_details"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(accent.primary)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// Kurzer Titel, Wert rechts (mit gesprochener Fassung) und Erklärung eines Grundes.
private enum WatchlistWhyTexts {
    struct Factor {
        let title: String
        let value: String
        var spokenValue: String? = nil
    }

    static func factor(_ r: WhyReason) -> Factor {
        func pct(_ v: Double) -> String { ActivityTexts.percent(v, 1) }
        func ref(_ title: String, _ symbol: String, _ v: Double?) -> Factor {
            guard let v, v.isFinite else { return Factor(title: title, value: "") }
            return Factor(title: title, value: symbol + " " + pct(v),
                          spokenValue: A11y.join([symbol, A11y.change(abs(v) < 0.05 ? 0 : v)]))
        }

        switch r.kind {
        case .MARKET_WIDE:
            return ref(L("why_f_market_wide"), "BTC", r.value)
        case .COIN_ONLY:
            return ref(L("why_f_coin_only"), "BTC", r.secondary)
        case .AGAINST_MARKET:
            return ref(L("why_f_against"), "BTC", r.secondary)
        case .MARKET_CALM:
            return ref(L("why_f_market_calm"), "BTC", r.value)
        case .MARKET_LEADER:
            // Der Coin ist Bitcoin selbst (24h steht oben): rechts ETH zum Vergleich
            return ref(L("why_f_leader"), "ETH", r.secondary)
        case .VOLUME_HIGH, .VOLUME_LOW, .VOLUME_NORMAL:
            let number = r.value.isFinite ? String(format: "%.2f", locale: Locale.current, r.value) : "—"
            return Factor(title: L("factor_volume"), value: number + "×",
                          spokenValue: L("factor_spoken_volume", number))
        case .LEVERAGE_LONGS:
            return Factor(title: L("why_f_leverage_longs"), value: ActivityTexts.percent(r.value, 3))
        case .LEVERAGE_SHORTS:
            return Factor(title: L("why_f_leverage_shorts"), value: ActivityTexts.percent(r.value, 3))
        case .LEVERAGE_BALANCED:
            return Factor(title: L("why_f_leverage_balanced"), value: ActivityTexts.percent(r.value, 3))
        case .VOLATILITY_HIGH, .VOLATILITY_NORMAL:
            let number = ActivityTexts.factor(r.value)
            return Factor(title: L("factor_volatility"), value: number + "×",
                          spokenValue: L("factor_spoken_volatility", number))
        case .SENTIMENT:
            let value = r.value.isFinite ? Int(r.value.rounded()) : 0
            let label = fearGreedLabel(value)
            return Factor(title: L("factor_fear_greed"), value: "\(value) · \(label)",
                          spokenValue: "\(value), \(label)")
        }
    }

    /// Erklärung mit formatierten Zahlen (unter «Details» bzw. bei «!»).
    static func explanation(_ r: WhyReason) -> String {
        func pct(_ v: Double?) -> String { v.map { ActivityTexts.percent($0, 1) } ?? "—" }
        func withOpenInterest(_ text: String, _ change: Double?) -> String {
            guard let change else { return text }
            return text + " " + L("why_open_interest_change", ActivityTexts.percent(change, 1))
        }

        switch r.kind {
        case .MARKET_WIDE:
            return L("why_market_wide_text", pct(r.secondary))
        case .COIN_ONLY:
            return L("why_coin_only_text", pct(r.value))
        case .AGAINST_MARKET:
            return L("why_against_market_text", pct(r.value), pct(r.secondary))
        case .MARKET_CALM:
            return L("why_market_calm_text", pct(r.secondary))
        case .MARKET_LEADER:
            return r.secondary.map { L("why_market_leader_text", pct($0)) } ?? L("why_market_leader_text_no_eth")
        case .VOLUME_HIGH:
            return L("why_volume_high_text")
        case .VOLUME_LOW:
            return L("why_volume_low_text")
        case .VOLUME_NORMAL:
            return L("why_volume_normal_text")
        case .LEVERAGE_LONGS:
            return withOpenInterest(L("why_leverage_longs_text"), r.secondary)
        case .LEVERAGE_SHORTS:
            return withOpenInterest(L("why_leverage_shorts_text"), r.secondary)
        case .LEVERAGE_BALANCED:
            return withOpenInterest(L("why_leverage_balanced_text"), r.secondary)
        case .VOLATILITY_HIGH:
            return L("why_volatility_high_text", pct(r.secondary))
        case .VOLATILITY_NORMAL:
            return L("why_volatility_normal_text", pct(r.secondary))
        case .SENTIMENT:
            if let change = r.secondary, change.isFinite {
                return L("why_sentiment_text", signedInt(Int(change.rounded())))
            }
            return L("why_sentiment_text_no_change")
        }
    }

    private static func signedInt(_ value: Int) -> String {
        if value > 0 { return "+\(value)" }
        if value < 0 { return "−\(-value)" }
        return "±0"
    }

    private static func fearGreedLabel(_ value: Int) -> String {
        switch ActivityAnalyzer.fearGreedLevel(value) {
        case .extremeFear: L("fng_extreme_fear")
        case .fear: L("fng_fear")
        case .neutral: L("fng_neutral")
        case .greed: L("fng_greed")
        case .extremeGreed: L("fng_extreme_greed")
        }
    }
}

/// «Kurz gesagt»: ein, zwei Sätze aus den Gründen darunter.
private struct WatchlistWhySummaryCard: View {
    let text: String
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("why_summary_title"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(accent.onContainer)
            Text(text)
                .font(.body)
                .foregroundStyle(accent.onContainer)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(accent.container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// Drei ruhig pulsierende Platzhalter-Karten (bei reduzierter Bewegung stehend).
private struct WatchlistWhySkeleton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            rows.opacity(0.7).accessibilityHidden(true)
        } else {
            rows
                .phaseAnimator([0.45, 1.0]) { view, phase in
                    view.opacity(phase)
                } animation: { _ in
                    .easeInOut(duration: 0.9)
                }
                .accessibilityHidden(true)
        }
    }

    private var rows: some View {
        VStack(spacing: 10) {
            ForEach(0..<3, id: \.self) { _ in
                HStack(spacing: 12) {
                    Circle().fill(AppColors.containerHighest).frame(width: 36, height: 36)
                    VStack(alignment: .leading, spacing: 8) {
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .fill(AppColors.containerHighest)
                            .frame(width: 180, height: 14)
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(AppColors.containerHighest)
                            .frame(maxWidth: 230)
                            .frame(height: 10)
                    }
                    Spacer(minLength: 0)
                }
                .padding(14)
                .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
        }
    }
}

/// Leerzustand: keine Quelle führt das Paar (oder nichts erreichbar).
private struct WatchlistWhyEmpty: View {
    let onRetry: (() -> Void)?
    @Environment(\.appAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("why_no_data"))
                .font(.body.weight(.semibold))
                .foregroundStyle(AppColors.onSurface)
            Text(L("why_no_data_hint"))
                .font(.subheadline)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            if let onRetry {
                Button(L("action_retry"), action: onRetry)
                    .font(.subheadline.weight(.semibold))
                    .tint(accent.primary)
                    .padding(.top, 6)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
