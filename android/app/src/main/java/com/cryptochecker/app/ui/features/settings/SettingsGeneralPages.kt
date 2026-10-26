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
import com.cryptochecker.app.settings.UpdateMode
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
    // Sprache der Konfiguration (beobachtbar); Locale.ROOT nur als Notnagel
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
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

/**
 * Aktualisierung: «Wie aktuell?» (eine Wahl: Sparsam oder Live, solange offen), darunter die
 * Feinheiten (Hintergrund-Intervall, Pünktlich prüfen, feste Mitteilung), dann die Akkunutzung und ganz
 * unten «Letzte Prüfungen». Wie iOS
 * `UpdatesPage`. «Immer kurz» (Vordergrunddienst) gibt es seit 1.0 nicht mehr.
 */
@Composable
internal fun UpdatesPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasWork by viewModel.hasActiveWatches.collectAsStateWithLifecycle()
    val checkRuns by viewModel.checkRuns.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Nach der Rückkehr aus den Android-Einstellungen neu prüfen (Akku, «Wecker und Erinnerungen»).
    var batteryUnrestricted by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    var exactPermitted by remember { mutableStateOf(com.cryptochecker.app.work.ExactCheckScheduler.canScheduleExact(context)) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = BatteryOptimization.isIgnoring(context)
        exactPermitted = com.cryptochecker.app.work.ExactCheckScheduler.canScheduleExact(context)
        viewModel.syncExactChecks()
        onPauseOrDispose { }
    }

    SettingsSubPage(title = stringResource(R.string.settings_row_updates), onBack = onBack) {
        SettingsAnchor("updates.mode") {
            UpdateModeCard(settings, onSelect = viewModel::setUpdateMode)
        }
        Spacer(Modifier.height(12.dp))
        GroupCard {
            SettingsAnchor("updates.background_interval") {
                ChoiceRow(
                    label = stringResource(R.string.settings_background_interval),
                    options = AppSettings.BACKGROUND_INTERVAL_CHOICES,
                    selected = settings.backgroundIntervalMinutes,
                    optionLabel = { stringResource(R.string.settings_minutes, it) },
                    onSelected = viewModel::setBackgroundInterval
                )
            }
            RowDivider()
            // «Pünktlich prüfen»: exakter Wecker alle 15 Min., Standard aus
            SettingsAnchor("updates.exact") {
                Column {
                    ExactChecksRow(
                        enabled = settings.exactChecks,
                        permitted = exactPermitted,
                        hasWork = hasWork,
                        onToggle = viewModel::setExactChecks,
                    )
                }
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
        // Akkunutzung über «Letzte Prüfungen»: Hinweis und Weg in die Android-Einstellungen
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
                // Eingeschränkt und es wird im Hintergrund geprüft: deutlicher Satz
                Hint(
                    stringResource(
                        if (com.cryptochecker.app.domain.refresh.BackgroundSchedule.showBatteryHint(!batteryUnrestricted, hasWork)) R.string.battery_delay_hint
                        else R.string.settings_battery_hint
                    )
                )
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
        // «Letzte Prüfungen»: wann Android wirklich geprüft hat
        Spacer(Modifier.height(12.dp))
        SettingsAnchor("updates.check_runs") {
            CheckRunsCard(checkRuns)
        }
    }
}

/**
 * «Wie aktuell?»: zwei Wahlen ([UpdateMode.selectable]) als Radiozeilen mit ehrlichem Satz
 * (Intervall aus den Feinheiten). «Sparsam» ist empfohlen (Standard), «Live, solange offen» für Profis. Wie iOS
 * `UpdateModeCard`.
 */
@Composable
private fun UpdateModeCard(settings: AppSettings, onSelect: (UpdateMode) -> Unit) {
    val background = stringResource(R.string.settings_minutes, settings.backgroundIntervalMinutes)
    GroupCard(title = stringResource(R.string.update_mode_title)) {
        Column(Modifier.selectableGroup()) {
            UpdateMode.selectable.forEach { mode ->
                val sparsam = mode == UpdateMode.SPARSAM
                // Sparsam ist Standard und empfohlen; Live nur für Profis
                val title = if (sparsam) stringResource(R.string.update_mode_sparsam) + " · " + stringResource(R.string.update_mode_recommended)
                else stringResource(R.string.update_mode_live) + " · " + stringResource(R.string.update_mode_for_pros)
                val subtitle = if (sparsam) stringResource(R.string.update_mode_sparsam_hint, background)
                else stringResource(R.string.update_mode_live_hint, background)
                RadioRow(
                    selected = settings.updateMode == mode,
                    onClick = { if (settings.updateMode != mode) onSelect(mode) },
                    title = title,
                    subtitle = subtitle
                )
            }
        }
    }
}
