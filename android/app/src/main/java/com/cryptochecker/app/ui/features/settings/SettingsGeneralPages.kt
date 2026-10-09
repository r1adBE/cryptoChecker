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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.settings.AppLanguages
import java.util.Locale
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BatteryOptimization

// ── Allgemein ────────────────────────────────────────────────────────────────

/**
 * Währung: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget. Liste mit Suchfeld:
 * Kürzel und Name in der App-Sprache («CHF Schweizer Franken»), die gewählte hinterlegt.
 */
@Composable
internal fun CurrencyPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selected = settings.portfolioCurrency
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var query by rememberSaveable { mutableStateOf("") }
    // Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen
    val codes = remember(selected) {
        if (selected in FxRateSource.CURRENCIES) FxRateSource.CURRENCIES else FxRateSource.CURRENCIES + selected
    }
    val options = remember(codes, locale) { codes.map { it to currencyName(it, locale) } }
    val q = query.trim()
    val visible = if (q.isEmpty()) options else options.filter { (code, name) ->
        code.contains(q, ignoreCase = true) || name.contains(q, ignoreCase = true)
    }
    SettingsSubPage(title = stringResource(R.string.settings_row_currency), onBack = onBack) {
        ChoiceSearchField(query, { query = it }, stringResource(R.string.currency_search))
        SettingsAnchor("currency.conversion") {
            GroupCard {
                if (visible.isEmpty()) Hint(stringResource(R.string.explorer_search_empty), top = 8.dp)
                Column(Modifier.selectableGroup()) {
                    visible.forEach { (code, name) ->
                        RadioRow(
                            selected = code == selected,
                            onClick = { if (code != selected) viewModel.setConversionCurrency(code) },
                            title = name,
                            leading = {
                                Text(
                                    code,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(44.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_conversion_currency_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
        )
    }
}

/** Name der Währung in der App-Sprache («Schweizer Franken»); unbekannt → Kürzel. */
private fun currencyName(code: String, locale: Locale): String =
    runCatching { java.util.Currency.getInstance(code).getDisplayName(locale) }.getOrNull()
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        ?: code

/**
 * Sprache: «Systemsprache» und die Sprachen der App (in ihrer eigenen Schreibweise), Suchfeld
 * oben, die gewählte hinterlegt. Nach der Wahl baut sich die App in der neuen Sprache auf.
 */
@Composable
internal fun LanguagePage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val selectedTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val systemLabel = stringResource(R.string.settings_language_system)
    var query by rememberSaveable { mutableStateOf("") }
    val options = listOf("" to systemLabel) + AppLanguages.ALL.map { it.tag to it.nativeName }
    val q = query.trim()
    val visible = if (q.isEmpty()) options else options.filter { (tag, name) ->
        name.contains(q, ignoreCase = true) || (tag.isNotEmpty() && tag.startsWith(q, ignoreCase = true))
    }
    SettingsSubPage(title = stringResource(R.string.settings_section_language), onBack = onBack) {
        ChoiceSearchField(query, { query = it }, stringResource(R.string.language_search))
        GroupCard {
            if (visible.isEmpty()) Hint(stringResource(R.string.explorer_search_empty), top = 8.dp)
            Column(Modifier.selectableGroup()) {
                visible.forEach { (tag, name) ->
                    RadioRow(
                        selected = tag == selectedTag,
                        onClick = { if (tag != selectedTag) viewModel.setLanguage(tag) },
                        title = name
                    )
                }
            }
        }
    }
}

/** Aktualisierung: Hintergrund und Intervall, Live-Modus, feste Mitteilung, ganz unten die Akkunutzung. */
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
        // Akkunutzung ganz unten: Hinweis und Weg in die Android-Einstellungen
        Spacer(Modifier.height(12.dp))
        SettingsAnchor("updates.battery") {
            GroupCard {
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
        }
    }
}

/** Merkliste: Namen, Mini-Chart, «≈ Umrechnung», Karte «Hier passiert gerade etwas», Futures. */
@Composable
internal fun WatchlistPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_row_watchlist), onBack = onBack) {
        GroupCard {
            SettingsAnchor("watchlist.names") {
                SwitchRow(
                    title = stringResource(R.string.settings_watchlist_names),
                    subtitle = stringResource(R.string.settings_watchlist_names_hint),
                    checked = settings.watchlistNames,
                    onCheckedChange = viewModel::setWatchlistNames
                )
            }
            RowDivider()
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
