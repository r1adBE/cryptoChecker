@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.GasFees
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.SettingsSummary
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest

/*
 * Runde 13b: Unterseiten der Einstellungen. «Alarme & Benachrichtigungen» hatte 17
 * Bedienelemente; Markt-Meldungen und Sprachausgabe stehen jetzt je auf einer eigenen
 * Seite, erreichbar über eine Zeile mit Kurzwert. Schlüssel und Verhalten unverändert.
 */

/** Anzahl eingeschalteter Themen der Markt-Meldungen (siehe [SettingsSummary]). */
internal fun AppSettings.activeMarketAlerts(): Int = SettingsSummary.marketAlertCount(
    zone = zoneAlerts,
    fearGreedBelow = fearGreedBelow,
    fearGreedAbove = fearGreedAbove,
    gasEthTenths = gasAlertEthTenths,
    gasBtc = gasAlertBtc,
    activity = activityAlerts,
    macro = macroNotifications,
)

/** Kurzwert der Zeile «Markt-Meldungen»: «Aus» oder «2 aktiv». */
@Composable
internal fun marketAlertsSummary(settings: AppSettings): String {
    val count = settings.activeMarketAlerts()
    return if (count == 0) stringResource(R.string.option_off)
    else pluralStringResource(R.plurals.settings_market_alerts_active, count, count)
}

/** Kurzwert der Zeile «Sprachausgabe»: «Aus», «Ein» oder «Nur Alarme». */
@Composable
internal fun speechSummary(settings: AppSettings): String =
    when (SettingsSummary.speech(settings.ttsEnabled, settings.ttsAlarmsOnly)) {
        SettingsSummary.Speech.OFF -> stringResource(R.string.option_off)
        SettingsSummary.Speech.ON -> stringResource(R.string.settings_summary_on)
        SettingsSummary.Speech.ALARMS_ONLY -> stringResource(R.string.settings_tts_alarms_only)
    }

/**
 * Zeile, die zu einer Unterseite führt: Titel, kurzer Hinweis, rechts der Kurzwert
 * und ein Pfeil. Für den Screenreader eine Schaltfläche mit Titel, Hinweis und Wert.
 */
@Composable
internal fun SubPageRow(title: String, subtitle: String?, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = 4.dp)
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Gerüst einer Unterseite: Kopfzeile mit Zurück, darunter scrollbarer Inhalt (lesbare Breite). */
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            content = content
        )
    }
}

/** Markt-Meldungen: Marktphase, Fear & Greed, Gas, ungewöhnliche Aktivität, Wirtschaftstermine. */
@Composable
fun MarketAlertsSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()

    SettingsSubPage(title = stringResource(R.string.settings_market_alerts), onBack = onBack) {
        GroupCard {
            SwitchRow(
                title = stringResource(R.string.settings_zone_alerts),
                subtitle = stringResource(R.string.settings_zone_alerts_hint),
                checked = settings.zoneAlerts,
                onCheckedChange = {
                    if (it) requestNotifications()
                    viewModel.setZoneAlerts(it)
                }
            )
            // Fear & Greed: Meldung unter/über einer Grenze (0 = aus)
            ChoiceRow(
                label = stringResource(R.string.settings_fng_below),
                options = AppSettings.FEAR_GREED_BELOW_CHOICES,
                selected = settings.fearGreedBelow,
                optionLabel = { if (it == 0) stringResource(R.string.option_off) else it.toString() },
                onSelected = {
                    if (it > 0) requestNotifications()
                    viewModel.setFearGreedBelow(it)
                }
            )
            ChoiceRow(
                label = stringResource(R.string.settings_fng_above),
                options = AppSettings.FEAR_GREED_ABOVE_CHOICES,
                selected = settings.fearGreedAbove,
                optionLabel = { if (it == 0) stringResource(R.string.option_off) else it.toString() },
                onSelected = {
                    if (it > 0) requestNotifications()
                    viewModel.setFearGreedAbove(it)
                }
            )
            RowDivider()
            // Gas-Alarm (#167): normale Gebühr fällt unter die Grenze
            ChoiceRow(
                label = stringResource(R.string.settings_gas_eth_below),
                options = AppSettings.GAS_ETH_CHOICES,
                selected = settings.gasAlertEthTenths,
                optionLabel = {
                    if (it == 0) stringResource(R.string.option_off)
                    else GasFees.formatGwei(it / 10.0)
                },
                onSelected = {
                    if (it > 0) requestNotifications()
                    viewModel.setGasAlertEth(it)
                }
            )
            ChoiceRow(
                label = stringResource(R.string.settings_gas_btc_below),
                options = AppSettings.GAS_BTC_CHOICES,
                selected = settings.gasAlertBtc,
                optionLabel = { if (it == 0) stringResource(R.string.option_off) else it.toString() },
                onSelected = {
                    if (it > 0) requestNotifications()
                    viewModel.setGasAlertBtc(it)
                }
            )
            Hint(stringResource(R.string.settings_gas_alert_hint))
            RowDivider()
            // Ungewöhnliche Aktivität: höchstens stündlich je Paar
            SwitchRow(
                title = stringResource(R.string.settings_activity_alerts),
                subtitle = stringResource(R.string.settings_activity_alerts_hint),
                checked = settings.activityAlerts,
                onCheckedChange = {
                    if (it) requestNotifications()
                    viewModel.setActivityAlerts(it)
                }
            )
            RowDivider()
            // Wirtschaftstermine: Morgen-Meldung um 08:00 an Tagen mit US-Daten (CPI, Fed …)
            SwitchRow(
                title = stringResource(R.string.settings_macro_notifications),
                subtitle = stringResource(R.string.settings_macro_notifications_hint),
                checked = settings.macroNotifications,
                onCheckedChange = {
                    if (it) requestNotifications()
                    viewModel.setMacroNotifications(it)
                }
            )
        }
    }
}

/** Sprachausgabe: ein/aus, nur Alarme, Tempo, Probe. */
@Composable
fun SpeechSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    SettingsSubPage(title = stringResource(R.string.settings_tts), onBack = onBack) {
        GroupCard {
            SwitchRow(
                title = stringResource(R.string.settings_tts),
                subtitle = stringResource(R.string.settings_tts_hint_silent),
                checked = settings.ttsEnabled,
                onCheckedChange = viewModel::setTtsEnabled
            )
            if (settings.ttsEnabled) {
                RowDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_tts_alarms_only),
                    subtitle = stringResource(R.string.settings_tts_alarms_only_hint),
                    checked = settings.ttsAlarmsOnly,
                    onCheckedChange = viewModel::setTtsAlarmsOnly
                )
                Text(
                    text = stringResource(R.string.settings_speech_rate, settings.ttsSpeechRate),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = settings.ttsSpeechRate,
                    onValueChange = viewModel::setSpeechRate,
                    valueRange = 0.5f..2.0f,
                    steps = 5
                )
                FilledTonalButton(
                    onClick = viewModel::testSpeech,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(stringResource(R.string.settings_tts_test))
                }
            }
        }
    }
}
