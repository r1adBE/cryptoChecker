import SwiftUI

// «Letzte Aktualisierung» (Runde 22) — wie `RefreshReportSheet.kt`: oben der Gesamtstatus,
// darunter je Börse eine Zeile mit Status (Farbe UND Form: ✓ / ! / ×), dann der Ablauf.

/// Blatt aus der Merkliste (Menü bzw. Tipp auf den Status).
struct RefreshReportSheet: View {
    let report: RefreshReport?
    /// App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen); nil = keine Zeile.
    var appStartMillis: Int64? = nil
    /// Börsen, deren Kurse gerade per WebSocket kommen (Runde 31); leer = keine Zeile.
    var liveExchanges: [String] = []
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                // Uhr für «vor 8 Min.», alle 30 s neu
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    VStack(alignment: .leading, spacing: 0) {
                        // «Live: Binance, Bybit» — diese Paare kommen per WebSocket, die übrigen per Abfrage
                        if !liveExchanges.isEmpty {
                            Text(L("refresh_report_live", liveExchanges.joined(separator: ", ")))
                                .font(.subheadline)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                                .padding(.bottom, Spacing.sm)
                        }
                        if let report {
                            RefreshReportSummary(report: report, now: Int64(context.date.timeIntervalSince1970 * 1000))
                                .padding(.bottom, Spacing.lg)
                            if !report.markets.isEmpty {
                                RefreshReportSectionTitle(L("refresh_sheet_section_markets"))
                                SectionCard(nil) {
                                    RefreshReportMarketList(markets: report.markets)
                                }
                            }
                            if report.aborted == nil || appStartMillis != nil {
                                RefreshReportSectionTitle(L("refresh_sheet_section_steps"))
                                SectionCard(nil) {
                                    RefreshReportSteps(report: report, appStartMillis: appStartMillis)
                                }
                            }
                        } else {
                            Text(L("watchlist_refresh_report_empty"))
                                .font(.subheadline)
                                .foregroundStyle(AppColors.onSurfaceVariant)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                }
            }
            .background(AppColors.background.ignoresSafeArea())
            .navigationTitle(L("watchlist_refresh_report"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_close")) { dismiss() }
                }
            }
        }
    }
}

/// Grosse Statuszeile mit Symbol, darunter «530 Paare · 1,8 s · vor 8 Min.».
struct RefreshReportSummary: View {
    let report: RefreshReport
    let now: Int64

    var body: some View {
        let status = RefreshReportLogic.overall(report)
        HStack(alignment: .center, spacing: Spacing.md) {
            RefreshStatusIcon(status: status, size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(AppColors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                Text(subtitle)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        switch RefreshReportLogic.headline(report) {
        case .allUpdated: return L("refresh_sheet_all_updated")
        case .pairsNotUpdated(let n): return L("refresh_sheet_pairs_not_updated", count: n)
        case .marketUnreachable(let name): return L("refresh_sheet_market_unreachable", name)
        case .marketsUnreachable(let n): return L("refresh_sheet_markets_unreachable", count: n)
        case .aborted: return L("refresh_sheet_aborted")
        }
    }

    private var subtitle: String {
        var parts: [String] = []
        if let aborted = report.aborted {
            parts.append(aborted)
        } else {
            parts.append(L("refresh_sheet_pairs", count: report.pairs))
            parts.append(RefreshReportFormat.seconds(report.totalMillis))
        }
        if report.at > 0 { parts.append(WatchlistTime.ago(report.at, now: now)) }
        return parts.joined(separator: " · ")
    }
}

/// Börsen: rot zuerst, dann orange, dann grün; je Gruppe die langsamsten zuerst.
struct RefreshReportMarketList: View {
    let markets: [MarketRefresh]

    var body: some View {
        let sorted = RefreshReportLogic.sorted(markets)
        ForEach(sorted.indices, id: \.self) { index in
            if index > 0 { RowDivider().padding(.leading, 32) }
            RefreshReportMarketRow(market: sorted[index])
        }
    }
}

/// Eine Börse: Status, Name, Dauer; darunter Paare und Sammelabfrage, ggf. die Ursachen.
struct RefreshReportMarketRow: View {
    let market: MarketRefresh

    var body: some View {
        let status = RefreshReportLogic.status(market)
        let issues = issuesText
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            RefreshStatusIcon(status: status, size: 20)
                .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 5 }
            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(market.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AppColors.onSurface)
                    Spacer(minLength: 0)
                    Text(RefreshReportFormat.seconds(market.millis))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
                Text(detailText)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if !issues.isEmpty {
                    Text(issues)
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(RefreshReportColors.text(status))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.vertical, Spacing.md)
        // Screenreader: ein Satz je Börse («Binance, alles aktualisiert, 5 von 5 Paaren, 0,5 Sekunden»)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenText(status: status, issues: issues))
    }

    private var pairsText: String {
        L("refresh_sheet_pairs_of", count: market.pairs, market.pairs, market.updated)
    }

    private var detailText: String {
        var parts = [pairsText]
        if market.bulkTried {
            let secs = RefreshReportFormat.seconds(market.bulkMillis)
            parts.append(market.bulkPrices > 0
                ? L("refresh_sheet_bulk", count: market.bulkPrices, secs, market.bulkPrices)
                : L("refresh_report_bulk_failed", secs))
        }
        if market.singles > 0 { parts.append(L("refresh_report_singles", market.singles)) }
        return parts.joined(separator: " · ")
    }

    private var issuesText: String {
        var parts: [String] = []
        if market.notTraded > 0 { parts.append(L("refresh_report_not_traded", count: market.notTraded)) }
        if market.failed > 0 {
            let errors = L("refresh_report_errors", count: market.failed)
            if let reason = market.reason.flatMap(RefreshReportFormat.reason) {
                parts.append(L("refresh_sheet_with_reason", errors, reason))
            } else {
                parts.append(errors)
            }
        }
        // Pause je Börse: «pausiert bis 19:45 (zu viele Anfragen)»
        if let until = market.pausedUntil {
            let time = PriceFormat.shortTime(until)
            if let reason = market.pauseReason.flatMap(RefreshReportFormat.reason) {
                parts.append(L("refresh_sheet_paused_until_reason", time, reason))
            } else {
                parts.append(L("refresh_sheet_paused_until", time))
            }
        }
        return parts.joined(separator: " · ")
    }

    private func spokenText(status: RefreshStatus, issues: String) -> String {
        let statusText: String
        switch status {
        case .ok: statusText = L("refresh_sheet_status_ok")
        case .partial: statusText = L("refresh_sheet_status_partial")
        case .failed: statusText = L("refresh_sheet_status_failed")
        }
        let secs = String(format: "%.1f", locale: Locale.current, Double(market.millis) / 1000)
        return [market.name, statusText, pairsText, L("refresh_sheet_seconds_a11y", secs), issues]
            .filter { !$0.isEmpty }
            .joined(separator: ", ")
    }
}

/// Ablauf: zweispaltig, links Bezeichnung (ggf. mit Zusatz), rechts Dauer.
struct RefreshReportSteps: View {
    let report: RefreshReport
    /// «App-Start 0,4 s» (nur lokal gemessen); nil = keine Zeile.
    var appStartMillis: Int64? = nil

    var body: some View {
        VStack(spacing: 0) {
            // Abgebrochener Durchlauf: nur der App-Start
            if report.aborted == nil {
                if let wait = report.waitMillis { row(L("refresh_sheet_wait"), nil, wait) }
                row(L("refresh_sheet_network"), nil, report.networkMillis)
                if let db = report.dbMillis { row(L("refresh_sheet_database"), nil, db) }
                if let effects = report.effectsMillis { row(L("refresh_sheet_alarms"), alarmsExtra, effects) }
                if let widgets = report.widgetMillis { row(L("refresh_sheet_widgets"), nil, widgets) }
            }
            if let appStartMillis { row(L("refresh_sheet_app_start"), nil, appStartMillis) }
        }
    }

    private var alarmsExtra: String {
        var parts: [String] = []
        if report.alarms > 0 { parts.append(L("refresh_sheet_alarms_triggered", count: report.alarms)) }
        parts.append(L("refresh_sheet_notifications", count: report.notifications))
        return parts.joined(separator: " · ")
    }

    private func row(_ label: String, _ extra: String?, _ millis: Int64) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(label)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.onSurface)
                if let extra {
                    Text(" · " + extra)
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            Spacer(minLength: 0)
            Text(RefreshReportFormat.seconds(millis))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }
}

/// Abschnittstitel («Börsen», «Ablauf»), für VoiceOver als Überschrift.
struct RefreshReportSectionTitle: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppColors.onSurfaceVariant)
            .padding(.leading, 4)
            .padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Kreis mit ✓ / ! / × in Grün / Orange / Rot — nie nur Farbe; der Text daneben trägt die Aussage.
struct RefreshStatusIcon: View {
    let status: RefreshStatus
    let size: CGFloat

    var body: some View {
        Image(systemName: symbol)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(RefreshReportColors.icon(status))
            .accessibilityHidden(true)
    }

    private var symbol: String {
        switch status {
        case .ok: return "checkmark.circle.fill"
        case .partial: return "exclamationmark.circle.fill"
        case .failed: return "xmark.circle.fill"
        }
    }
}

/// Farben der App: «alles in Ordnung» (`PriceColors.ok`), Bernstein, Fehlerrot.
enum RefreshReportColors {
    static func icon(_ status: RefreshStatus) -> Color {
        switch status {
        case .ok: return PriceColors.ok
        case .partial: return AppColors.warning
        case .failed: return AppColors.error
        }
    }

    /// Für Text: Bernstein in der dunkleren Fassung (AA-Kontrast auf den Karten).
    static func text(_ status: RefreshStatus) -> Color {
        status == .failed ? AppColors.error : AppColors.warningText
    }
}

enum RefreshReportFormat {
    /// «0,5 s» in der Sprache des Geräts.
    static func seconds(_ millis: Int64) -> String {
        String(format: "%.1f s", locale: Locale.current, Double(millis) / 1000)
    }

    /// Kurze Ursache in Klammern; nil für «sonstiger Fehler».
    static func reason(_ failure: RefreshFailure) -> String? {
        switch failure {
        case .TIMEOUT: return L("refresh_sheet_reason_timeout")
        case .OFFLINE: return L("refresh_sheet_reason_offline")
        case .RATE_LIMIT: return L("refresh_sheet_reason_rate_limit")
        case .SERVER: return L("refresh_sheet_reason_server")
        case .NO_DATA: return L("refresh_sheet_reason_no_data")
        case .UNAVAILABLE: return L("refresh_sheet_reason_unavailable")
        case .OTHER: return nil
        }
    }
}
