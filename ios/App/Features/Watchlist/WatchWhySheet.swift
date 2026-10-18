import SwiftUI

// «💡 Was bewegt sich mit?» — wie `WatchWhySheet.kt`.

/// Ladezustand des «Was bewegt sich mit?»-Blatts.
private enum WatchlistWhyState: Equatable {
    case loading
    case loaded(WhyReport)
    case failed
}

/// «Was bewegt sich mit BTC?»: Kopf mit Paar, Kurs und 1h/24h; darunter kurz die Karte «Kurz gesagt ·
/// Stand 14:00» (`WatchlistWhyBriefCard`: ein Satz, bis zu drei auffällige Faktoren als grauer Text)
/// und zugeklappt «Alle Werte und Quellen» (für die Sitzung gemerkt) mit den
/// bisherigen Teilen: (1) «Beobachtung» mit Datenstand — Signale und Messwerte als Tatsache;
/// (2) «Mögliche Einordnung» — nur als zeitliches Zusammentreffen formuliert, nie als Ursache;
/// (3) ausführlicher Hinweis «kein Ursachenbeweis» und die Quellen. Ganz unten kurz «Zeitgleich
/// heisst nicht Ursache · Keine Anlageberatung».
/// Als Seite im `NavigationStack` des Tabs geöffnet (wie «Alarme»). Wie `WhySheet` in `WatchWhySheet.kt`.
struct WatchlistWhySheet: View {
    let watchId: Int64
    /// Vorschau-Paar (nicht in der Merkliste, aus «Heute auffällig»), falls `watchId` keinen
    /// gespeicherten Eintrag meint; ohne Signale (das Paar wird nicht überwacht).
    var preview: Watch? = nil

    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var state: WatchlistWhyState = .loading
    @State private var attempt = 0
    /// 24-h-Wert (= Änderung, rollend) aus den Kerzen einer anderen Börse; nil = nicht.
    @State private var candleSource: String? = nil
    /// «Alle Werte und Quellen» aufgeklappt (Startwert: zuletzt in dieser Sitzung).
    @State private var allValues = WatchlistWhyAllValuesState.expanded

    var body: some View {
        // Nicht mehr gehandelt bzw. deaktiviert: kein «Was bewegt sich mit?»
        if let watch = data.watch(watchId) ?? preview, !watch.isNotTraded, !watch.disabled {
            content(watch)
                // Eigene Seite (Merkliste, Markt): Titel oben, Zurück schliesst sie
                .navigationTitle(L("why_title"))
                .navigationBarTitleDisplayMode(.inline)
                .background(AppColors.background.ignoresSafeArea())
        } else {
            // Paar wurde inzwischen gelöscht — oder wird nicht mehr gehandelt (kein Urteil auf alten Daten)
            Color.clear.onAppear { dismiss() }
        }
    }

    /// Gleichlauf mit Bitcoin (Stunden-Renditen, Kerzen von «Was bewegt sich mit?»): eng bzw. unabhängig; nil = kein Satz.
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
        // «Was bewegt sich mit?» zeigt bei rollender Basis die Änderung selbst; bei «seit 00:00» rechnet es eigene Kerzen
        let candleNote: String? = candleSource.flatMap { source in
            report?.change24h != nil && !data.settings.changeBasis.isDay ? L("change_candle_source", source) : nil
        }

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
                    WatchlistWhyChangeBadge(label: L("why_change_24h"), change: report?.change24h, note: candleNote)
                }
                .padding(.top, 12)
                .padding(.bottom, candleNote == nil ? 16 : 2)
                // 24-h-Wert aus fremden Kerzen (z. B. Kraken-Paar → Binance): klein darunter sagen, woher
                if let candleNote {
                    Text(candleNote)
                        .font(.caption2)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(.bottom, 16)
                }

                // Oben kurz: «Kurz gesagt · Stand …» mit Satz und auffälligen Faktoren;
                // darunter zugeklappt «Alle Werte und Quellen» (beim Laden ein form-gleicher Platzhalter)
                switch state {
                case .loading:
                    WatchlistWhySkeleton()
                        .transition(.opacity)
                case .failed:
                    VStack(alignment: .leading, spacing: Spacing.md) {
                        WatchlistWhyEmpty(onRetry: { attempt += 1 })
                        WatchlistWhySignals(signals: signals)
                    }
                case .loaded(let r):
                    loadedContent(r, watch: watch, signals: signals)
                        // Nur überblenden: der Platzhalter hat dieselbe Form, nichts rückt nach
                        .transition(.opacity)
                }

                // Fusszeile kurz; der ausführliche Hinweis steht unter «Alle Werte und Quellen»
                Text(L("why_footer_short"))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
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
        // Woher die Änderung kommt (fremde Kerzen, z. B. Kraken-Paar → Binance)
        .task(id: "\(watch.id)|\(watch.lastUpdate)") {
            candleSource = await PriceRefresher.foreignCandleSource(watch)
        }
    }

    /// Geladener Bericht: Karte «Kurz gesagt» und zugeklappt «Alle Werte und Quellen».
    /// Eigene Funktion, damit der grosse ViewBuilder oben den Type-Checker nicht überfordert.
    private func loadedContent(_ r: WhyReport, watch: Watch, signals: [ActivitySignal]) -> some View {
        let factors = WhyFactors.rank(r)
        return VStack(alignment: .leading, spacing: Spacing.md) {
            if !r.hasMarketData {
                WatchlistWhyEmpty(onRetry: nil)
            }
            WatchlistWhyBriefCard(report: r, factors: factors)
            WatchlistWhyAllValuesCard(expanded: $allValues) {
                WatchlistWhyAllValues(report: r, factors: factors, signals: signals,
                                      btcLine: Self.btcLine(r.btcLink, base: watch.baseAsset))
            }
        }
    }
}

/// Inhalt von «Alle Werte und Quellen» — die bisherigen Abschnitte: (1) «Beobachtung» mit Datenstand
/// (Signale, Faktoren, Herkunft je Quelle), (2) «Mögliche Einordnung»
/// («Kurz gesagt», Gleichlauf mit BTC, Details) und (3) der ausführliche Hinweis «kein
/// Ursachenbeweis» mit den Quellen. Wie `WhyAllValues` (Android).
private struct WatchlistWhyAllValues: View {
    let report: WhyReport
    let factors: [WhyFactor]
    let signals: [ActivitySignal]
    let btcLine: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            observation
            contextPart
            disclaimer
                .padding(.top, Spacing.sm)
        }
    }

    /// 1) Beobachtung: gemessene Werte als Tatsache, rechts der Datenstand.
    @ViewBuilder
    private var observation: some View {
        WatchlistWhySectionTitle(
            title: L("why_section_observation"),
            trailing: L("why_data_time", PriceFormat.time(report.dataTime))
        )
        WatchlistWhySignals(signals: signals)
        if !factors.isEmpty {
            WatchlistWhyFactorCard(factors: factors)
        }
        // Herkunft und Stand je Quelle («Kerzen: Binance · 14:00» …)
        WatchlistWhyFreshness(report: report)
    }

    /// 2) Mögliche Einordnung — nie als Ursache formuliert; dann «Details anzeigen».
    @ViewBuilder
    private var contextPart: some View {
        let context = WatchlistWhyContextCard(
            // «Kurz gesagt»: genau ein Satz — die Einordnung, sonst der erste Zusatz
            brief: WhySummary.keys(report.reasons).first.map { L($0) },
            btcLine: btcLine
        )
        if context.hasContent {
            WatchlistWhySectionTitle(title: L("why_section_context"))
                .padding(.top, Spacing.md)
            context
        }
        if !report.reasons.isEmpty {
            WatchlistWhyDetails(reasons: report.reasons)
        }
    }

    /// 3) Hinweis ausführlich: kein Ursachenbeweis, keine Prognose/Anlageberatung, Quellen.
    private var disclaimer: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L("why_disclaimer"))
                .font(.caption)
            Text(L("pulse_disclaimer"))
                .font(.caption.weight(.medium))
                .padding(.top, 2)
            // Tatsächliche Quellen des geladenen Berichts, sonst der allgemeine Satz
            Text(WatchlistWhyTexts.sourcesLine(report) ?? L("why_sources"))
                .font(.caption)
        }
        .foregroundStyle(AppColors.onSurfaceVariant)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Was gerade auffällt (die Signale hinter dem ⚡), höchstens drei; ohne Signale nichts.
private struct WatchlistWhySignals: View {
    let signals: [ActivitySignal]

    var body: some View {
        if !signals.isEmpty {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                ForEach(Array(signals.prefix(3).enumerated()), id: \.offset) { _, signal in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Image(systemName: "bolt.fill")
                            .scaledFont(size: 12, weight: .bold, relativeTo: .caption)
                            // Zeile bricht um (keine feste Höhe): Symbol bis AX2 wie der Text daneben
                            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
                            .foregroundStyle(AppColors.warning)
                        Text(ActivityTexts.signal(signal))
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(AppColors.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }
}

/// «1h» und daneben die Änderung als `ChangePill` («—» ohne Daten) — wie `ChangeBadge` in Android.
private struct WatchlistWhyChangeBadge: View {
    let label: String
    let change: Double?
    /// Hinweis für VoiceOver nach dem Wert, z. B. «Veränderung aus Binance-Kerzen».
    var note: String? = nil

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
        .accessibilityLabel(A11y.join([label, A11y.change(change) ?? "—", note]))
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
                ExpandToggleLabel(title: L(showDetails ? "why_details_hide" : "why_details"), expanded: showDetails)
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
                .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .transition(.opacity)
            }
        }
    }
}

/// Platzhalter in der Form des geladenen Inhalts — Karte «Kurz gesagt» (Kopfzeile, Satz, drei
/// Änderungen) und die zugeklappte Karte «Alle Werte und Quellen» —, mit echten Schriften
/// und `.redacted`, damit die Höhen auch bei grosser Schrift stimmen und beim Eintreffen nichts
/// springt. Pulsiert ruhig, bei reduzierter Bewegung stehend.
private struct WatchlistWhySkeleton: View {
    var body: some View {
        SkeletonPulse { shape }
    }

    private var shape: some View {
        VStack(alignment: .leading, spacing: Spacing.md) {
            briefShape
            // Zugeklappte Karte «Alle Werte und Quellen» (Titel, Untertitel)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("why_all_values"))
                    .font(AppFont.title)
                Text(L("why_all_values_hint"))
                    .font(.footnote)
            }
            .padding(.horizontal, Spacing.md)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
        .redacted(reason: .placeholder)
        .accessibilityHidden(true)
    }

    /// Karte «Kurz gesagt»: Kopfzeile, zwei Zeilen Satz, Zeile der Faktoren.
    private var briefShape: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(L("why_brief_head", "14:00"))
                    .font(.footnote)
                Text(L("why_summary_market_volume"))
                    .font(AppFont.title)
            }
            Text(Array(repeating: L("factor_volume") + " 2.4×", count: WatchlistWhyBriefCard.maxFactors)
                    .joined(separator: " · "))
                .font(.subheadline)
                .lineLimit(1)
        }
        .padding(Spacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
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
        .background(AppColors.containerHigh, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}
