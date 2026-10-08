import SwiftUI
import UIKit

/// Über die App: Version (sieben Tipps schalten «Entwickler» frei), Zweck, Hinweis zu den Daten.
@MainActor
struct AboutSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var versionTaps = 0
    @State private var toast: String?

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_row_about")) {
            SettingsCard {
                AboutContent(onVersionTap: versionTapped)
                    .padding(.vertical, Spacing.sm)
            }
            // Was die App kann (früher im Begrüßungsblatt)
            SettingsCard {
                AboutFeatures()
                    .padding(.vertical, Spacing.md)
            }
        }
        .toast($toast)
    }

    /// Sieben Tipps auf die Version schalten die Entwickleroptionen frei.
    private func versionTapped() {
        guard !data.settings.developerUnlocked else { return }
        versionTaps += 1
        if versionTaps >= 4 && versionTaps < 7 {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        if versionTaps == 7 {
            data.settings.developerUnlocked = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            toast = L("developer_unlocked")
        }
    }
}

/// Entwickler: HTTP-Protokoll und Bericht der letzten Aktualisierung.
@MainActor
struct DeveloperSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @Environment(\.appAccent) private var accent

    init() {}

    var body: some View {
        SettingsSubPage(title: L("settings_section_developer")) {
            SwitchRow(
                title: L("settings_http_log"),
                subtitle: L("settings_http_log_hint"),
                isOn: $data.settings.showHttpLog
            )
            .settingsAnchor("developer.http_log")
            RowDivider()
            VStack(alignment: .leading, spacing: Spacing.xs) {
                HStack {
                    Text(L("watchlist_refresh_report")).font(.body)
                    Spacer()
                    if data.lastRefreshMillis > 0 {
                        Text(PriceFormat.duration(data.lastRefreshMillis))
                            .font(.footnote.weight(.semibold).monospacedDigit())
                            .foregroundStyle(accent.onContainer)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(accent.container, in: Capsule())
                    }
                }
                // Gleiche Darstellung wie das Blatt in der Merkliste (Status, Börsen)
                if let report = data.lastRefreshReport {
                    RefreshReportSummary(report: report, now: TimeUtils.nowMillis)
                        .padding(.top, 4)
                    RefreshReportMarketList(markets: report.markets)
                } else {
                    Text(L("watchlist_refresh_report_empty"))
                        .font(.footnote)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 12)
        }
    }
}
