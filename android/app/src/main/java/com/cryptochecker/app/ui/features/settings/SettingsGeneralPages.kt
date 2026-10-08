package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BatteryOptimization

// ── Allgemein ────────────────────────────────────────────────────────────────

/** Währung: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget. */
@Composable
internal fun CurrencyPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_row_currency), onBack = onBack) {
        GroupCard {
            SettingsAnchor("currency.conversion") {
                ConversionCurrencyRow(selected = settings.portfolioCurrency, onSelected = viewModel::setConversionCurrency)
            }
            Hint(stringResource(R.string.settings_conversion_currency_hint))
        }
    }
}

/** Aktualisierung: Hintergrund und Intervall, Akku, Live-Modus, feste Mitteilung. */
@Composable
internal fun UpdatesPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Nach der Rückkehr aus den Android-Einstellungen neu prüfen.
    var batteryUnrestricted by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }

    SettingsSubPage(title = stringResource(R.string.settings_row_updates), onBack = onBack) {
        GroupCard {
            SettingsAnchor("updates.background") {
                SwitchRow(
                    title = stringResource(R.string.settings_background_updates),
                    subtitle = stringResource(R.string.settings_background_updates_hint),
                    checked = settings.backgroundUpdates,
                    onCheckedChange = viewModel::setBackgroundUpdates
                )
            }
            if (settings.backgroundUpdates) {
                SettingsAnchor("updates.background_interval") {
                    ChoiceRow(
                        label = stringResource(R.string.settings_background_interval),
                        options = AppSettings.BACKGROUND_INTERVAL_CHOICES,
                        selected = settings.backgroundIntervalMinutes,
                        optionLabel = { stringResource(R.string.settings_minutes, it) },
                        onSelected = viewModel::setBackgroundInterval
                    )
                }
            }
            RowDivider()
            // Akku gehört zur Hintergrund-Aktualisierung
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = Spacing.md)
            ) {
                StatusDot(ok = batteryUnrestricted)
                Text(
                    text = stringResource(
                        if (batteryUnrestricted) R.string.settings_battery_ok
                        else R.string.settings_battery_restricted
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = Spacing.sm)
                )
            }
            Hint(stringResource(R.string.settings_battery_hint))
            if (!batteryUnrestricted) {
                Hint(stringResource(R.string.battery_steps))
                FilledTonalButton(
                    onClick = { BatteryOptimization.openSettings(context) },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(stringResource(R.string.battery_open_settings))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupCard {
            SettingsAnchor("updates.live") {
                SwitchRow(
                    title = stringResource(R.string.settings_live_service),
                    subtitle = stringResource(R.string.settings_live_service_hint),
                    checked = settings.liveService,
                    onCheckedChange = viewModel::setLiveService
                )
            }
            if (settings.liveService) {
                SettingsAnchor("updates.live_interval") {
                    ChoiceRow(
                        label = stringResource(R.string.settings_live_interval),
                        options = AppSettings.LIVE_INTERVAL_CHOICES,
                        selected = settings.liveIntervalSeconds,
                        optionLabel = { liveIntervalLabel(it) },
                        onSelected = viewModel::setLiveInterval
                    )
                }
                // Bei geschlossener App läuft der Live-Modus höchstens jede Minute (LiveInterval)
                Hint(stringResource(R.string.settings_live_interval_hidden_hint))
            }
            RowDivider()
            // Live-Kurse per WebSocket, nur bei offener Merkliste (LivePriceStream)
            SettingsAnchor("updates.live_websocket") {
                SwitchRow(
                    title = stringResource(R.string.settings_live_websocket),
                    subtitle = stringResource(R.string.settings_live_websocket_hint),
                    checked = settings.liveWebSocket,
                    onCheckedChange = viewModel::setLiveWebSocket
                )
            }
            RowDivider()
            SettingsAnchor("updates.ongoing") {
                SwitchRow(
                    title = stringResource(R.string.settings_ongoing_notifications),
                    subtitle = stringResource(R.string.settings_ongoing_notifications_hint),
                    checked = settings.ongoingNotifications,
                    onCheckedChange = viewModel::setOngoingNotifications
                )
            }
        }
    }
}

/** Merkliste: Mini-Chart, «≈ Umrechnung», Karte «Hier passiert gerade etwas», Futures. */
@Composable
internal fun WatchlistPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_row_watchlist), onBack = onBack) {
        GroupCard {
            SettingsAnchor("watchlist.sparkline") {
                SwitchRow(
                    title = stringResource(R.string.settings_watchlist_sparkline),
                    subtitle = stringResource(R.string.settings_watchlist_sparkline_hint),
                    checked = settings.watchlistSparkline,
                    onCheckedChange = viewModel::setWatchlistSparkline
                )
            }
            RowDivider()
            SettingsAnchor("watchlist.converted") {
                SwitchRow(
                    title = stringResource(R.string.settings_show_converted),
                    subtitle = stringResource(R.string.settings_show_converted_hint, settings.portfolioCurrency),
                    checked = settings.showConverted,
                    onCheckedChange = viewModel::setShowConverted
                )
            }
            RowDivider()
            SettingsAnchor("watchlist.activity") {
                SwitchRow(
                    title = stringResource(R.string.settings_watchlist_activity_card),
                    subtitle = stringResource(R.string.settings_watchlist_activity_card_hint),
                    checked = settings.watchlistActivityCard,
                    onCheckedChange = viewModel::setWatchlistActivityCard
                )
            }
        }
        // Futures: welche Kontrakte in der Auswahl erscheinen und wie die Merkliste sie prüft
        SettingsSectionHeader(stringResource(R.string.settings_row_dated_futures), first = true)
        GroupCard {
            SettingsAnchor("futures.rolling") {
                SwitchRow(
                    title = stringResource(R.string.settings_rolling_futures),
                    subtitle = stringResource(R.string.settings_rolling_futures_hint),
                    checked = settings.includeRollingFutures,
                    onCheckedChange = viewModel::setIncludeRollingFutures
                )
            }
            RowDivider()
            SettingsAnchor("futures.tradfi") {
                SwitchRow(
                    title = stringResource(R.string.settings_tradfi_futures),
                    subtitle = stringResource(R.string.settings_tradfi_futures_hint),
                    checked = settings.includeTradFiFutures,
                    onCheckedChange = viewModel::setIncludeTradFiFutures
                )
            }
        }
    }
}
