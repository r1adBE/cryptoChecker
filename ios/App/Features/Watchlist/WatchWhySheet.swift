import SwiftUI

// «💡 Warum bewegt sich das?» — wie `WatchWhySheet.kt`.

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
/// Kein `NavigationStack` nötig. Wie `WhySheet` in `WatchWhySheet.kt`.
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
                    CoinBadge(symbol: watch.baseAsset, size: 40, logo: CoinLogos.allowed(forMarket: watch.marketKey),
                              pair: watch.logoPairKey)
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

/// «1h» und daneben die Änderung als `ChangePill` («—» ohne Daten) — wie `ChangeBadge` in Android.
private struct WatchlistWhyChangeBadge: View {
    let label: String
    let change: Double?

    var body: some View {
        HStack(spacing: Spacing.xs) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(AppColors.onSurfaceVariant)
            ChangePill(change: change, dashWhenMissing: true)
        }
        .lineLimit(1)
        .fixedSize()
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        // «1h, gestiegen um 2.31%»
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(A11y.join([label, A11y.change(change) ?? "—"]))
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

/// Platzhalter in der Form des geladenen Inhalts — Faktorkarte mit Kopf, Sicherheit, fünf
/// Zeilen und «Kurz gesagt» sowie «Details anzeigen» —, mit echten Schriften und `.redacted`,
/// damit die Höhen auch bei grosser Schrift stimmen und beim Eintreffen nichts springt.
/// Pulsiert ruhig, bei reduzierter Bewegung stehend.
private struct WatchlistWhySkeleton: View {
    var body: some View {
        SkeletonPulse { shape }
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
