import SwiftUI

/// Build 53 (Review 13, Weg 1): Die Hauptseite zeigt nur die häufigen Einstellungen; alles für
/// Fortgeschrittene steht hier, in Karten ohne Überschrift — Märkte und Portfolio (Token & TradFi,
/// Portfolio mit Steuer), Meldungen (Markt-Meldungen, Sprachausgabe), Anzeige (%-Änderung, Coin-Logos)
/// und, falls freigeschaltet, «Entwickler». Kurs-Aktualisierung und Widgets stehen seit Build 55 wieder
/// auf der Hauptseite — wie `AdvancedPage` (Android).
@MainActor
struct AdvancedSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsCardsPage(title: L("settings_row_advanced")) {
            SettingsCard {
                SettingsNavRow(title: L("settings_asset_scope_title"), icon: .system("building.columns"),
                               value: L(AssetScopePicker.titleKey(settings.assetScope))) {
                    MarketsSettingsPage()
                }
                .settingsAnchor("advanced.markets")
                RowDivider()
                // Gleiches Symbol wie der Tab «Portfolio»
                SettingsNavRow(title: L("portfolio_title"), icon: .asset("PortfolioIcon"), value: SettingsSummary.portfolioText(settings)) {
                    PortfolioSettingsPage()
                }
                .settingsAnchor("advanced.portfolio")
            }
            SettingsCard {
                // Blitz wie «Ungewöhnliche Aktivität»
                SettingsNavRow(title: L("settings_market_alerts"), icon: .system("bolt.fill"), value: SettingsSummary.marketAlertsText(settings)) {
                    MarketAlertsSettingsPage()
                }
                .settingsAnchor("advanced.market_alerts")
                RowDivider()
                SettingsNavRow(title: L("settings_tts"), icon: .system("speaker.wave.2"), value: SettingsSummary.speechText(settings)) {
                    SpeechSettingsPage()
                }
                .settingsAnchor("advanced.speech")
            }
            SettingsCard {
                // Basis der %-Änderung steht seit Review D13 auf «Merkliste» (`WatchlistSettingsPage`)
                SettingsNavRow(title: L("settings_coin_logos"), icon: .system("circle.circle"),
                               value: SettingsSummary.coinLogosText(settings)) {
                    CoinLogosSettingsPage()
                }
                .settingsAnchor("advanced.coin_logos")
            }
            // «Entwickler» erst nach 7 Tipps auf die Version (Seite «Über die App»)
            if settings.developerUnlocked {
                SettingsCard {
                    SettingsNavRow(title: L("settings_section_developer"), icon: .system("hammer"),
                                   value: SettingsSummary.onOff(settings.showHttpLog)) {
                        DeveloperSettingsPage()
                    }
                }
            }
        }
    }
}
