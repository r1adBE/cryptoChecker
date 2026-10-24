import SwiftUI
import UIKit

/// Über die App: Version (7 Tipps schalten «Entwickler» frei), Zweck, Hinweis zu den Daten.
@MainActor
struct AboutSettingsPage: View {
    @EnvironmentObject private var data: AppData
    @State private var versionTaps = 0
    @State private var toast: ToastMessage?

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_row_about")) {
            SettingsCard {
                AboutContent(onVersionTap: versionTapped)
                    .padding(.vertical, Spacing.sm)
            }
            // «Deine Daten»: das Wichtigste der Datenschutzerklärung in vier Sätzen, direkt über dem Link zur vollen
            // Fassung — wie Android. Die Sätze müssen zur Erklärung (public/docs/privacy) passen.
            SettingsCard(title: L("about_your_data_title")) {
                VStack(alignment: .leading, spacing: 0) {
                    yourDataLine("about_data_device_label", "about_data_device")
                    yourDataLine("about_data_connections_label", "about_data_connections")
                    yourDataLine("about_data_backup_label", "about_data_backup")
                    yourDataLine("about_data_device_change_label", "about_data_device_change")
                }
                .padding(.bottom, Spacing.sm)
            }
            // Links und Lizenzen von der Hauptseite hierher — wie Android
            SettingsCard {
                aboutLinkRows
            }
            // Diagnose für den Support: erst Vorschau, dann Teilen-Menü — nichts geht automatisch weg (wie Android)
            DiagnosticsCard()
            // Was die App kann (früher im Begrüssungsblatt)
            SettingsCard {
                AboutFeatures()
                    .padding(.vertical, Spacing.md)
            }
        }
        .toast($toast)
    }

    /// Eine Zeile von «Deine Daten»: Stichwort und ein Satz, vorgelesen als ein Element.
    private func yourDataLine(_ labelKey: String, _ textKey: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L(labelKey))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AppColors.onSurface)
            Text(L(textKey))
                .font(.footnote)
                .foregroundStyle(AppColors.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, Spacing.xs)
        .accessibilityElement(children: .combine)
    }

    /// Datenschutz, Börse wünschen, Quellcode (nach aussen) und Lizenzhinweise.
    @ViewBuilder
    private var aboutLinkRows: some View {
        // Pflicht für den App Store: Datenschutzerklärung auch in der App erreichbar
        SettingsButtonRow(title: L("about_privacy_policy"), trailingIcon: "arrow.up.forward", hint: L("a11y_external_link"), icon: .system("shield")) {
            Self.open(AppLinks.privacyPolicy)
        }
        .settingsAnchor("about.privacy")
        RowDivider()
        // Fehlende Börse direkt mit der GitHub-Vorlage wünschen
        SettingsButtonRow(title: L("about_request_exchange"), trailingIcon: "arrow.up.forward", hint: L("a11y_external_link"), icon: .system("storefront")) {
            Self.open(AppLinks.exchangeRequest)
        }
        .settingsAnchor("about.exchange")
        RowDivider()
        // Quellcode öffentlich auf GitHub (MIT)
        SettingsButtonRow(title: L("about_source_code"), trailingIcon: "arrow.up.forward", hint: L("a11y_external_link"), icon: .system("chevron.left.forwardslash.chevron.right")) {
            Self.open(AppLinks.sourceCode)
        }
        .settingsAnchor("about.source")
        RowDivider()
        SettingsNavRow(title: L("about_licenses"), icon: .system("doc.text"), value: nil) {
            LicensesSheet(asPage: true)
        }
        .settingsAnchor("about.licenses")
    }

    private static func open(_ string: String) {
        guard let url = URL(string: string) else { return }
        UIApplication.shared.open(url)
    }

    /// Tipps auf die Version, die «Entwickler» freischalten — wie Android (`AboutPage`).
    static let unlockTaps = 7
    /// Ab so vielen fehlenden Tipps zählt ein Hinweis herunter («Noch 3-mal tippen …»).
    static let countdownFrom = 3

    /// 7 Tipps auf die Version schalten die Entwickleroptionen frei; die letzten zählen herunter.
    private func versionTapped() {
        guard !data.settings.developerUnlocked else { return }
        versionTaps += 1
        let left = Self.unlockTaps - versionTaps
        if left == 0 {
            data.settings.developerUnlocked = true
            Haptics.play(.success)
            toast = ToastMessage(L("developer_unlocked"))
        } else if (1...Self.countdownFrom).contains(left) {
            Haptics.play(.lightImpact)
            toast = ToastMessage(L("developer_taps_left", count: left))
        }
    }
}

/// Entwickler: HTTP-Protokoll und Bericht der letzten Aktualisierung.
@MainActor
struct DeveloperSettingsPage: View {
    @EnvironmentObject private var data: AppData
    /// Dauer und Bericht der letzten Aktualisierung (eigenes Objekt neben `AppData`, beobachtet).
    @ObservedObject private var refreshState = AppData.shared.refreshState
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
                    if refreshState.lastRefreshMillis > 0 {
                        Text(PriceFormat.duration(refreshState.lastRefreshMillis))
                            .font(.footnote.weight(.semibold).monospacedDigit())
                            .foregroundStyle(accent.onContainer)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(accent.container, in: Capsule())
                    }
                }
                // Gleiche Darstellung wie das Blatt in der Merkliste (Status, Börsen)
                if let report = refreshState.lastRefreshReport {
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
