import SwiftUI

// Runde 23f: Unterseiten hinter den Zeilen der Hauptseite — wie `SettingsPages.kt`, je Gruppe eine
// Datei (Allgemein, Darstellung, Alarme, Portfolio, Daten, Über) wie in Android.

/// Währung: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget.
@MainActor
struct CurrencySettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsSubPage(title: L("settings_row_currency")) {
            ConversionCurrencyRow(selection: data.settings.portfolioCurrency) { data.settings.portfolioCurrency = $0 }
                .settingsAnchor("currency.conversion")
            SettingsHint(text: L("settings_conversion_currency_hint"))
        }
    }
}

/// Aktualisierung: Hintergrund und Intervall, Live-Modus.
@MainActor
struct UpdatesSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    private var settings: AppSettings { data.settings }

    var body: some View {
        SettingsCardsPage(title: L("settings_row_updates")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_background_updates"),
                    subtitle: L("settings_background_updates_hint"),
                    isOn: $data.settings.backgroundUpdates
                )
                .settingsAnchor("updates.background")
                if settings.backgroundUpdates {
                    ChoiceRow(
                        title: L("settings_background_interval"),
                        options: AppSettings.backgroundIntervalChoices,
                        selection: settings.backgroundIntervalMinutes,
                        label: { L("settings_minutes", $0) },
                        onSelect: { data.settings.backgroundIntervalMinutes = $0 }
                    )
                    .settingsAnchor("updates.background_interval")
                    // iOS bestimmt den Zeitpunkt im Hintergrund; bei offener App pünktlich
                    SettingsHint(text: L("settings_ios_background_hint"))
                }
            }
            SettingsCard {
                SwitchRow(
                    title: L("settings_live_service"),
                    subtitle: L("ios_settings_live_service_hint"),
                    isOn: $data.settings.liveService
                )
                .settingsAnchor("updates.live")
                if settings.liveService {
                    ChoiceRow(
                        title: L("settings_live_interval"),
                        options: AppSettings.liveIntervalChoices,
                        selection: settings.liveIntervalSeconds,
                        label: { SettingsSummary.liveIntervalText($0) },
                        onSelect: { data.settings.liveIntervalSeconds = $0 }
                    )
                    .settingsAnchor("updates.live_interval")
                }
            }
            // Live-Kurse per WebSocket, nur bei offener Merkliste (`LivePriceStream`)
            SettingsCard {
                SwitchRow(
                    title: L("settings_live_websocket"),
                    subtitle: L("settings_live_websocket_hint"),
                    isOn: $data.settings.liveWebSocket
                )
                .settingsAnchor("updates.live_websocket")
            }
        }
    }
}

/// Merkliste: Mini-Chart und «≈ Umrechnung» in den Zeilen.
@MainActor
struct WatchlistSettingsPage: View {
    @EnvironmentObject private var data: AppData

    init() {}

    var body: some View {
        SettingsCardsPage(title: L("settings_row_watchlist")) {
            SettingsCard {
                SwitchRow(
                    title: L("settings_watchlist_sparkline"),
                    subtitle: L("settings_watchlist_sparkline_hint"),
                    isOn: $data.settings.watchlistSparkline
                )
                .settingsAnchor("watchlist.sparkline")
                RowDivider()
                SwitchRow(
                    title: L("settings_show_converted"),
                    subtitle: L("settings_show_converted_hint", data.settings.portfolioCurrency),
                    isOn: $data.settings.showConverted
                )
                .settingsAnchor("watchlist.converted")
                RowDivider()
                SwitchRow(
                    title: L("settings_watchlist_activity_card"),
                    subtitle: L("settings_watchlist_activity_card_hint"),
                    isOn: $data.settings.watchlistActivityCard
                )
                .settingsAnchor("watchlist.activity")
            }
            // Futures: welche Kontrakte in der Auswahl erscheinen und wie die Merkliste sie prüft
            Text(L("settings_row_dated_futures"))
                .font(.footnote.weight(.medium))
                .foregroundStyle(AppColors.onSurfaceVariant)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)
            SettingsCard {
                SwitchRow(
                    title: L("settings_rolling_futures"),
                    subtitle: L("settings_rolling_futures_hint"),
                    isOn: $data.settings.includeRollingFutures
                )
                .settingsAnchor("futures.rolling")
                RowDivider()
                SwitchRow(
                    title: L("settings_tradfi_futures"),
                    subtitle: L("settings_tradfi_futures_hint"),
                    isOn: $data.settings.includeTradFiFutures
                )
                .settingsAnchor("futures.tradfi")
            }
        }
    }
}
