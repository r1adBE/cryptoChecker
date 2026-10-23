import SwiftUI

/// Die Hauptseite zeigt nur die häufigen Einstellungen; alles für
/// Fortgeschrittene steht hier, in Karten ohne Überschrift — Märkte (Token & TradFi), Meldungen
/// (Markt-Meldungen, Sprachausgabe), Anzeige (Coin-Logos) und, falls freigeschaltet, «Entwickler».
/// Kurs-Aktualisierung und Widgets stehen wieder auf der Hauptseite, Portfolio
/// (nur dort, unter «Allgemein») — wie `AdvancedPage` (Android).
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
            // «Entwickler» erst nach 7 Tipps auf die Version (Seite «Über die App»); nur im Debug-Build
            if settings.developerUnlocked && DeveloperOptions.available {
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

/// Entwickleroptionen (HTTP-Protokoll) gibt es nur im Debug-Build, wie auf Android (BuildConfig.DEBUG):
/// im Release bleiben Schalter, Suchtreffer und Protokollfeld verborgen.
enum DeveloperOptions {
    #if DEBUG
    static let available = true
    #else
    static let available = false
    #endif
}
