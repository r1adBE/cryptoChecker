import SwiftUI

// «⚡ Ungewöhnliche Aktivität» und «💡 Warum bewegt sich das?» in der Merkliste —
// wie `ActivityUi.kt`.

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

// MARK: ⚡ in der Zeile

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
                CoinBadge(symbol: watch.baseAsset, size: 18)
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

// MARK: «Warum bewegt sich das?»

/// Ladezustand des «Warum»-Blatts.
private enum WatchlistWhyState: Equatable {
    case loading
    case loaded(WhyReport)
    case failed
}

/// «Warum bewegt sich BTC?» als klare Faktorliste: Kopf mit Paar und Veränderung,
/// «Wahrscheinliche Gründe · Sicherheit», bis zu fünf Faktoren (`WhyFactors`, stärkster oben,
/// neutrale abgeblendet), «Kurz gesagt: …» als ein Satz, dann «Details anzeigen» (die Gründe
/// mit Erklärung) und die Fusszeile. Nur Marktdaten: Markt vs. Coin, Volumen, Volatilität,
/// Futures (Open Interest, Funding), Nähe zum 30-Tage-Hoch, Stimmung.
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
        if let watch = data.watch(watchId), !watch.isNotTraded {
            content(watch)
        } else {
            // Paar wurde inzwischen gelöscht — oder wird nicht mehr gehandelt (kein Urteil auf alten Daten)
            Color.clear.onAppear { dismiss() }
        }
    }

    /// Gleichlauf mit Bitcoin (Stunden-Renditen, Kerzen von «Warum?»): eng bzw. unabhängig; nil = kein Satz.
    static func btcLine(_ link: BtcLink?, base: String) -> String? {
        guard let link else { return nil }
        let symbol = BidiText.isolate(base.trimmingCharacters(in: .whitespaces).uppercased())
        switch link {
        case .tight: return L("why_btc_tight", symbol)
        case .independent: return L("why_btc_independent", symbol)
        }
    }

    private func content(_ watch: Watch) -> some View {
        let report: WhyReport? = {
            if case .loaded(let r) = state { return r }
            return nil
        }()
        let signals = WatchlistActivity.active(data.activityReports[watch.id], now: TimeUtils.nowMillis,
                                               sensitivity: data.settings.activitySensitivity)

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
                            .font(AppFont.headline)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(watch.marketName)
                            .font(.subheadline)
                            .foregroundStyle(AppColors.onSurfaceVariant)
                    }
                }
                .padding(.top, Spacing.sm)

                HStack(spacing: 8) {
                    Text(PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset))
                        .font(AppFont.amount(.title3, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    WatchlistWhyChangeBadge(label: L("why_change_1h"), change: report?.change1h)
                    WatchlistWhyChangeBadge(label: L("why_change_24h"), change: report?.change24h)
                }
                .padding(.top, 12)
                .padding(.bottom, 16)

                // Was gerade auffällt (die Signale hinter dem ⚡)
                if !signals.isEmpty {
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        ForEach(Array(signals.prefix(3).enumerated()), id: \.offset) { _, signal in
                            HStack(alignment: .firstTextBaseline, spacing: 8) {
                                Image(systemName: "bolt.fill")
                                    .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                    .foregroundStyle(AppColors.warning)
                                Text(ActivityTexts.signal(signal))
                                    .font(.subheadline.monospacedDigit())
                                    .foregroundStyle(AppColors.onSurface)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    .padding(.bottom, Spacing.md)
                }

                // «Wahrscheinliche Gründe · Sicherheit», Faktorliste, «Kurz gesagt», Details
                // (beim Laden ein form-gleicher Platzhalter an derselben Stelle)
                switch state {
                case .loading:
                    WatchlistWhySkeleton()
                        .transition(.opacity)
                case .failed:
                    WatchlistWhyEmpty(onRetry: { attempt += 1 })
                case .loaded(let r):
                    let factors = WhyFactors.rank(r)
                    VStack(alignment: .leading, spacing: Spacing.sm) {
                        if !r.hasMarketData {
                            WatchlistWhyEmpty(onRetry: nil)
                        }
                        if !factors.isEmpty {
                            WatchlistWhyFactorCard(
                                factors: factors,
                                // «Kurz gesagt»: genau ein Satz — die Einordnung, sonst der erste Zusatz
                                brief: WhySummary.keys(r.reasons).first.map { L($0) },
                                confidence: WhySummary.confidence(r.reasons, hasMarketData: r.hasMarketData),
                                btcLine: Self.btcLine(r.btcLink, base: watch.baseAsset)
                            )
                        }
                        if !r.reasons.isEmpty {
                            WatchlistWhyDetails(reasons: r.reasons)
                        }
                    }
                    // Nur überblenden: der Platzhalter hat dieselbe Form, nichts rückt nach
                    .transition(.opacity)
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
            .padding(.horizontal, Spacing.xl)
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
        HStack(spacing: Spacing.xs) {
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
        .padding(.horizontal, Spacing.sm)
        .padding(.vertical, 4)
        .background(color.opacity(0.12), in: Capsule())
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
    }
}

/// «Details anzeigen»: die einzelnen Gründe als Checkliste ✓ / – / ! mit ihrer Erklärung
/// (wie bisher unter «Details»); eingeklappt nur der Knopf.
@MainActor
private struct WatchlistWhyDetails: View {
    let reasons: [WhyReason]

    @Environment(\.appAccent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showDetails = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Button {
                WatchlistHaptics.selection()
                // Wächst weich im ScrollView; das Blatt (feste Höhe) bleibt stehen
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.22)) {
                    showDetails.toggle()
                }
            } label: {
                Text(L(showDetails ? "why_details_hide" : "why_details"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(accent.primary)
                    .padding(.vertical, Spacing.sm)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if showDetails {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(reasons.enumerated()), id: \.offset) { _, reason in
                        let factor = WatchlistWhyTexts.factor(reason)
                        FactorRow(mark: WhySummary.mark(reason), title: factor.title, value: factor.value,
                                  spokenValue: factor.spokenValue, detail: WatchlistWhyTexts.explanation(reason))
                    }
                }
                .padding(.horizontal, Spacing.md)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .transition(.opacity)
            }
        }
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
            let number = LocaleNumbers.integer(value)
            return Factor(title: L("factor_fear_greed"), value: "\(number) · \(label)",
                          spokenValue: "\(number), \(label)")
        }
    }

    /// Texte einer Faktorzeile: Titel, Wert rechts, Nebenzeile und der gesprochene Satz.
    struct FactorLine {
        let title: String
        let value: String
        let note: String
        let spoken: String
    }

    static func factorLine(_ f: WhyFactor) -> FactorLine {
        func pct(_ v: Double) -> String { ActivityTexts.percent(v, 1) }
        func spokenChange(_ v: Double) -> String { A11y.change(abs(v) < 0.05 ? 0 : v) }
        let title: String
        var value = ""
        var spokenValue = ""
        var note = f.note.key.map { L($0) } ?? ""
        switch f.kind {
        case .volume:
            let number = ActivityTexts.factor(f.value)
            title = L("factor_volume")
            value = number + "×"
            spokenValue = L("factor_spoken_volume", number)
        case .market:
            title = L("why_factor_market")
            // Bei Bitcoin selbst steht BTC oben: rechts ETH zum Vergleich
            let leader = f.note == .marketLeaderMoves || f.note == .marketLeaderCalm
            let symbol = leader ? "ETH" : "BTC"
            let change: Double? = leader ? f.secondary : f.value
            if let change, change.isFinite {
                value = symbol + " " + pct(change)
                spokenValue = A11y.join([symbol, spokenChange(change)])
            }
        case .volatility:
            let number = ActivityTexts.factor(f.value)
            title = L("factor_volatility")
            value = number + "×"
            spokenValue = L("factor_spoken_volatility", number)
        case .openInterest:
            title = L("why_factor_futures")
            value = pct(f.value)
            spokenValue = spokenChange(f.value)
        case .nearHigh:
            title = L("why_factor_near_high")
            if f.note == .highBelow {
                value = pct(-f.value)
                spokenValue = spokenChange(-f.value)
            }
        case .funding:
            title = L("why_factor_funding")
            value = ActivityTexts.percent(f.value, 3)
            spokenValue = value
        case .sentiment:
            let points = f.value.isFinite ? Int(f.value.rounded()) : 0
            title = L("factor_fear_greed")
            value = LocaleNumbers.integer(points)
            spokenValue = value
            note = fearGreedLabel(points)
        }
        let spoken = "\(title): \(note)" + (spokenValue.isEmpty ? "" : ", \(spokenValue)") + "."
        return FactorLine(title: title, value: value, note: note, spoken: spoken)
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
        if value > 0 { return "+" + LocaleNumbers.integer(value) }
        if value < 0 { return "−" + LocaleNumbers.integer(-value) }
        return "±" + LocaleNumbers.integer(0)
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

/// «Wahrscheinliche Gründe · Sicherheit: mittel», darunter die Faktoren (stärkster oben, neutrale
/// abgeblendet, höchstens fünf) und «Kurz gesagt: …» als ein Satz — nie als sichere Ursache.
/// Zeilen wie `MarketRow` (Trennlinie, Titel mit Nebenzeile, Wert rechts).
private struct WatchlistWhyFactorCard: View {
    let factors: [WhyFactor]
    let brief: String?
    var confidence: WhySummary.Confidence? = nil
    /// «SOL läuft derzeit eng mit Bitcoin.» bzw. «… unabhängig …»; nil = kein Satz.
    var btcLine: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(L("why_summary_title"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let confidence {
                        Text(L("why_confidence", L(confidence.level.key)))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AppColors.onSurface)
                    }
                }
                if let confidence {
                    Text(Self.explanation(confidence))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
            .padding(.bottom, Spacing.sm)

            ForEach(Array(factors.enumerated()), id: \.offset) { _, factor in
                WatchlistWhyFactorLine(factor: factor)
            }

            if let brief {
                Rectangle()
                    .fill(AppColors.outlineVariant)
                    .frame(height: 1)
                    .accessibilityHidden(true)
                Text("\(Text(L("why_brief_label")).fontWeight(.semibold)) \(brief)")
                    .font(AppFont.body)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.md)
            }
            if let btcLine {
                Text(btcLine)
                    .font(.footnote)
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Spacing.sm)
            }
        }
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// «2 von 4 Hinweisen deuten darauf hin», bei Lücken mit «· Daten unvollständig».
    static func explanation(_ c: WhySummary.Confidence) -> String {
        let hints = L("why_confidence_hints", count: c.agreeing, c.agreeing, c.total)
        return c.partialData ? hints + " · " + L("why_confidence_partial") : hints
    }
}

/// Eine Faktorzeile: Pfeil im Kreis, Titel mit kurzer Nebenzeile, Wert rechts; neutrale
/// abgeblendet. VoiceOver: ein Satz («Volumen: höher als üblich, 3,4-mal so viel wie üblich»).
private struct WatchlistWhyFactorLine: View {
    let factor: WhyFactor

    @Environment(\.appAccent) private var accent
    @ScaledMetric(relativeTo: .headline) private var iconSize: CGFloat = 32

    var body: some View {
        let texts = WatchlistWhyTexts.factorLine(factor)
        let tint = factor.neutral ? AppColors.onSurfaceVariant : accent.primary
        VStack(alignment: .leading, spacing: 0) {
            Rectangle()
                .fill(AppColors.outlineVariant)
                .frame(height: 1)
                .accessibilityHidden(true)
            HStack(spacing: Spacing.md) {
                // Akzentfarbe, neutral grau — nie Kursfarben (Volumen ↑ ist kein Kursanstieg)
                Text(factor.direction.glyph)
                    .font(AppFont.title.weight(.bold))
                    .foregroundStyle(tint)
                    .frame(width: iconSize, height: iconSize)
                    .background(tint.opacity(0.12), in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(texts.title)
                        .font(AppFont.body)
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                    Text(texts.note)
                        .font(AppFont.label)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if !texts.value.isEmpty {
                    Text(texts.value)
                        .font(AppFont.title.monospacedDigit())
                        .foregroundStyle(AppColors.onSurface)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            .padding(.vertical, Spacing.sm)
            .opacity(factor.neutral ? 0.6 : 1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(texts.spoken)
        }
    }
}

/// Platzhalter in der Form des geladenen Inhalts — Faktorkarte mit Kopf, Sicherheit, fünf
/// Zeilen und «Kurz gesagt» sowie «Details anzeigen» —, mit echten Schriften und `.redacted`,
/// damit die Höhen auch bei grosser Schrift stimmen und beim Eintreffen nichts springt.
/// Pulsiert ruhig, bei reduzierter Bewegung stehend.
private struct WatchlistWhySkeleton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            shape.opacity(0.7).accessibilityHidden(true)
        } else {
            shape
                .phaseAnimator([0.45, 1.0]) { view, phase in
                    view.opacity(phase)
                } animation: { _ in
                    .easeInOut(duration: 0.9)
                }
                .accessibilityHidden(true)
        }
    }

    private static let placeholders: [WhyFactor] = (0..<WhyFactors.maxFactors).map { _ in
        WhyFactor(kind: .volume, direction: .up, note: .volumeHigher, value: 1.5, strength: 1, neutral: false)
    }

    private var shape: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            WatchlistWhyFactorCard(
                factors: Self.placeholders,
                brief: L("why_summary_market"),
                confidence: .init(level: .medium, agreeing: 2, total: 4, partialData: false)
            )
            // Höhe des Knopfs «Details anzeigen»
            Text(L("why_details"))
                .font(.subheadline.weight(.semibold))
                .padding(.vertical, Spacing.sm)
        }
        .redacted(reason: .placeholder)
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
                    .padding(.top, Spacing.xs)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
