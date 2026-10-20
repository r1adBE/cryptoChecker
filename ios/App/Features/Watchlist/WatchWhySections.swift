import SwiftUI

// Teile des «Was bewegt sich mit?»-Blatts (`WatchlistWhySheet` in WatchWhySheet.swift): alle Werte, Signale,
// 1h/24h-Abzeichen, Details, Platzhalter beim Laden und leerer Zustand — wie die Teile in `WatchWhySheet.kt`.

/// Inhalt von «Alle Werte und Quellen» — die bisherigen Abschnitte: (1) «Beobachtung» mit Datenstand
/// (Signale, Faktoren, Herkunft je Quelle), (2) «Mögliche Einordnung»
/// («Kurz gesagt», Gleichlauf mit BTC, Details) und (3) der ausführliche Hinweis «kein
/// Ursachenbeweis» mit den Quellen. Wie `WhyAllValues` (Android).
struct WatchlistWhyAllValues: View {
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
struct WatchlistWhySignals: View {
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
struct WatchlistWhyChangeBadge: View {
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
struct WatchlistWhyDetails: View {
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
struct WatchlistWhySkeleton: View {
    var body: some View {
        // VoiceOver: «Lädt …» statt Stille — als Zeile und einmal angesagt (wie Android `liveRegion`)
        SkeletonPulse(label: L("loading_hint")) { shape }
            .onAppear { AccessibilityNotification.Announcement(L("loading_hint")).post() }
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
struct WatchlistWhyEmpty: View {
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
