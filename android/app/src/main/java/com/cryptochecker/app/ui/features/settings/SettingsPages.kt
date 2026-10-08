package com.cryptochecker.app.ui.features.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.settings.PriceColorChoice
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.components.rememberAlarmTest
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.features.about.AboutContent
import com.cryptochecker.app.ui.features.about.AboutFeatures
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BatteryOptimization
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.LocaleNumbers

/*
 * Runde 23f: Unterseiten hinter den Zeilen der Hauptseite. Inhalt und Schlüssel wie
 * zuvor auf der langen Hauptseite, nur neu verteilt. Markt-Meldungen und Sprachausgabe
 * stehen weiter in SettingsSubScreens.kt (eigene Routen, auch aus der Merkliste erreichbar).
 */

/** Unterseiten der Einstellungen; der Name steht in der Route `settings/page/{page}`. */
enum class SettingsPage {
    CURRENCY, UPDATES, WATCHLIST,
    DISPLAY_MODE, THEME, PRICE_COLORS, CHANGE_BASIS,
    ALARMS,
    PORTFOLIO,
    BACKUP,
    ABOUT, DEVELOPER;

    companion object {
        fun fromName(name: String?): SettingsPage? = entries.firstOrNull { it.name == name }
    }
}

/** Zeigt eine Unterseite; ein unbekannter Name (alte Route) führt gleich zurück. */
@Composable
fun SettingsPageScreen(page: SettingsPage?, onBack: () -> Unit) {
    when (page) {
        SettingsPage.CURRENCY -> CurrencyPage(onBack)
        SettingsPage.UPDATES -> UpdatesPage(onBack)
        SettingsPage.WATCHLIST -> WatchlistPage(onBack)
        SettingsPage.DISPLAY_MODE -> DisplayModePage(onBack)
        SettingsPage.THEME -> ThemePage(onBack)
        SettingsPage.PRICE_COLORS -> PriceColorsPage(onBack)
        SettingsPage.CHANGE_BASIS -> ChangeBasisPage(onBack)
        SettingsPage.ALARMS -> AlarmsPage(onBack)
        SettingsPage.PORTFOLIO -> PortfolioPage(onBack)
        SettingsPage.BACKUP -> BackupPage(onBack)
        SettingsPage.ABOUT -> AboutPage(onBack)
        SettingsPage.DEVELOPER -> DeveloperPage(onBack)
        null -> LaunchedEffect(Unit) { onBack() }
    }
}

/** Auswahlzeile mit Radioknopf (ganze Zeile tippbar, für den Screenreader ein Radioknopf). */
@Composable
private fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.sm)
    ) {
        RadioButton(selected = selected, onClick = null)
        if (leading != null) {
            Spacer(Modifier.width(12.dp))
            leading()
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Allgemein ────────────────────────────────────────────────────────────────

/** Währung: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget. */
@Composable
private fun CurrencyPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
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
private fun UpdatesPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
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

/** Merkliste: Mini-Chart und «≈ Umrechnung» in den Zeilen. */
@Composable
private fun WatchlistPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
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

// ── Darstellung ──────────────────────────────────────────────────────────────

/** Modus: wie das System, Hell oder Dunkel (App und Widgets); dazu hoher Kontrast. */
@Composable
private fun DisplayModePage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_theme_mode), onBack = onBack) {
        GroupCard {
            Column(Modifier.selectableGroup()) {
                listOf(null, false, true).forEach { dark ->
                    RadioRow(
                        selected = settings.darkMode == dark,
                        onClick = { viewModel.setDarkMode(dark) },
                        title = stringResource(themeModeLabel(dark))
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GroupCard {
            // Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte (auch per System)
            SettingsAnchor("display.contrast") {
                SwitchRow(
                    title = stringResource(R.string.settings_high_contrast),
                    subtitle = stringResource(R.string.settings_high_contrast_hint),
                    checked = settings.highContrast,
                    onCheckedChange = viewModel::setHighContrast
                )
            }
        }
    }
}

/** Theme: Akzentfarbe der App, der Widgets und des App-Symbols. */
@Composable
private fun ThemePage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_accent), onBack = onBack) {
        GroupCard {
            AccentColorRow(selected = settings.accentColor, onSelected = viewModel::setAccentColor)
        }
    }
}

/**
 * Kursfarben: Grün steigt / Rot fällt (Standard), Rot steigt / Grün fällt (Ostasien) und
 * die Fassungen für Farbsehschwäche (Blau/Orange). Gespeichert als Schema + «getauscht».
 */
@Composable
private fun PriceColorsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = PriceColorChoice.of(settings.priceColorScheme, settings.priceColorsInverted)
    SettingsSubPage(title = stringResource(R.string.settings_price_colors), onBack = onBack) {
        GroupCard {
            Column(Modifier.selectableGroup()) {
                PriceColorChoice.entries.forEach { choice ->
                    RadioRow(
                        selected = choice == current,
                        onClick = {
                            if (choice.scheme != settings.priceColorScheme) viewModel.setPriceColorScheme(choice.scheme)
                            if (choice.inverted != settings.priceColorsInverted) viewModel.setPriceColorsInverted(choice.inverted)
                        },
                        title = stringResource(priceColorChoiceLabel(choice)),
                        subtitle = if (choice == PriceColorChoice.RED_UP) stringResource(R.string.price_colors_red_up_hint) else null,
                        leading = { PriceArrows(choice) }
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_price_colors_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
        )
    }
}

/**
 * «Basis der %-Änderung» wie Binance «Change(%) & Chart Timezone»: oben der nummerierte
 * Hinweis, darunter «Letzte 24 Std.» (Standard), die Zone des Geräts («UTC+2, 00:00 (Zeitzone
 * des Geräts)», folgt der Sommerzeit) und feste Zonen UTC+14 … UTC−12 — für Pille, Puls,
 * Aktionsblatt und Widgets; Alarme rechnen unabhängig davon.
 */
@Composable
private fun ChangeBasisPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Zone des Geräts einmal beim Öffnen (Sommerzeit wechselt nicht, während die Seite offen ist)
    val now = remember { System.currentTimeMillis() }
    SettingsSubPage(title = stringResource(R.string.settings_change_basis, "%"), onBack = onBack) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = Spacing.md)) {
            NumberedHint(1, stringResource(R.string.settings_change_basis_hint_1))
            NumberedHint(2, stringResource(R.string.settings_change_basis_hint_2, stringResource(R.string.change_basis_rolling)))
            NumberedHint(3, stringResource(R.string.settings_change_basis_hint_3))
        }
        GroupCard {
            Column(Modifier.selectableGroup()) {
                ChangeBasis.entries.forEach { basis ->
                    RadioRow(
                        selected = settings.changeBasis == basis,
                        onClick = { viewModel.setChangeBasis(basis) },
                        title = ChangeBasisText.choiceLabel(context, basis, now)
                    )
                }
            }
        }
    }
}

/** Ein Punkt eines nummerierten Hinweises, Folgezeilen eingerückt («1. …»). */
@Composable
private fun NumberedHint(number: Int, text: String) {
    Row(modifier = Modifier.padding(bottom = Spacing.xs)) {
        Text(
            text = LocaleNumbers.integer(number) + ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

// ── Alarme ───────────────────────────────────────────────────────────────────

/** Alarme: Kurs-Mitteilungen, Ruhezeit, Ton, Alarm-Signal, Nachtruhe, Probe-Alarm. */
@Composable
private fun AlarmsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val testAlarm = rememberAlarmTest(viewModel::testAlarm)
    val soundError by viewModel.soundError.collectAsStateWithLifecycle()
    LaunchedEffect(soundError) {
        if (soundError) {
            Toast.makeText(context, context.getString(R.string.settings_alarm_sound_error), Toast.LENGTH_LONG).show()
            viewModel.clearSoundError()
        }
    }

    SettingsSubPage(title = stringResource(R.string.settings_row_alarms), onBack = onBack) {
        GroupCard {
            SettingsAnchor("alarms.price") {
                SwitchRow(
                    title = stringResource(R.string.settings_price_notifications),
                    subtitle = stringResource(R.string.settings_price_notifications_hint),
                    checked = settings.priceNotifications,
                    onCheckedChange = {
                        if (it) requestNotifications()
                        viewModel.setPriceNotifications(it)
                    }
                )
            }
            if (settings.priceNotifications) {
                SettingsAnchor("alarms.change") {
                    PercentChoiceRow(
                        label = stringResource(R.string.settings_notification_change),
                        selected = settings.notificationChangePercent,
                        onSelected = viewModel::setNotificationChangePercent
                    )
                }
                Hint(stringResource(R.string.settings_notification_change_hint))
            }
            RowDivider()
            SettingsAnchor("alarms.cooldown") {
                ChoiceRow(
                    label = stringResource(R.string.settings_alarm_cooldown),
                    options = AppSettings.ALARM_COOLDOWN_CHOICES,
                    selected = settings.alarmCooldownMinutes,
                    optionLabel = { stringResource(R.string.settings_minutes, it) },
                    onSelected = viewModel::setAlarmCooldown
                )
            }
            RowDivider()
            SettingsAnchor("alarms.sound") {
                AlarmSoundRow(
                    soundName = settings.alarmSoundName,
                    isCustom = settings.alarmSoundUri != null,
                    currentUri = settings.alarmSoundUri,
                    onPicked = viewModel::setAlarmSound,
                    onFile = viewModel::importAlarmSound
                )
            }
            RowDivider()
            // Alarm-Signal: System, Ton und Vibration, nur Ton, nur Vibration oder lautlos
            SettingsAnchor("alarms.signal") {
                AlarmSignalRow(
                    selected = settings.alarmSignal,
                    onSelected = viewModel::setAlarmSignal,
                    channelId = viewModel::currentAlarmChannelId
                )
            }
            RowDivider()
            // Nachtruhe: Alarme lautlos (ohne Ton, Vibration, Sprachausgabe)
            SettingsAnchor("alarms.quiet") {
                SwitchRow(
                    title = stringResource(R.string.settings_quiet_hours),
                    subtitle = stringResource(
                        R.string.settings_quiet_hours_hint,
                        formatMinuteOfDay(context, settings.quietHoursStart),
                        formatMinuteOfDay(context, settings.quietHoursEnd)
                    ),
                    checked = settings.quietHoursEnabled,
                    onCheckedChange = viewModel::setQuietHoursEnabled
                )
            }
            if (settings.quietHoursEnabled) {
                QuietTimeRow(
                    label = stringResource(R.string.settings_quiet_hours_from),
                    minute = settings.quietHoursStart,
                    onPicked = viewModel::setQuietHoursStart
                )
                QuietTimeRow(
                    label = stringResource(R.string.settings_quiet_hours_to),
                    minute = settings.quietHoursEnd,
                    onPicked = viewModel::setQuietHoursEnd
                )
            }
            RowDivider()
            // Beispiel-Alarm mit Ton, Vibration und Sprachausgabe (ohne Nachtruhe)
            SettingsAnchor("alarms.test") {
                FilledTonalButton(
                    onClick = testAlarm,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.alarm_test))
                }
            }
            Hint(stringResource(R.string.alarm_test_hint), top = 4.dp)
        }
    }
}

// ── Portfolio ────────────────────────────────────────────────────────────────

/** Portfolio als eigener Tab und die Portfolio-Sperre (nur mit eingeschaltetem Portfolio). */
@Composable
private fun PortfolioPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsSubPage(title = stringResource(R.string.portfolio_title), onBack = onBack) {
        GroupCard {
            SettingsAnchor("portfolio.tab") {
                SwitchRow(
                    title = stringResource(R.string.settings_portfolio_tab),
                    subtitle = stringResource(R.string.portfolio_setting_hint),
                    checked = settings.portfolioEnabled,
                    onCheckedChange = viewModel::setPortfolioEnabled
                )
            }
            // Nur mit eingeschaltetem Portfolio; der Wert bleibt beim Ausblenden erhalten
            if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
                val unavailableText = stringResource(R.string.app_lock_unavailable)
                val reason = stringResource(R.string.portfolio_lock_reason)
                RowDivider()
                SettingsAnchor("portfolio.lock") {
                    SwitchRow(
                        title = stringResource(R.string.settings_portfolio_lock),
                        subtitle = stringResource(R.string.settings_portfolio_lock_hint),
                        checked = settings.appLock,
                        onCheckedChange = { on ->
                            val activity = context.findFragmentActivity()
                            if (!on) {
                                // Solange gesperrt, erst entsperren — sonst wäre die Sperre hier zu umgehen
                                viewModel.disableAppLock(activity, reason)
                            } else if (activity == null || !AppLockAuth.canAuthenticate(context)) {
                                Toast.makeText(context, unavailableText, Toast.LENGTH_LONG).show()
                            } else {
                                // Einschalten erst nach einer erfolgreichen Entsperrung
                                viewModel.enableAppLock(activity, reason)
                            }
                        }
                    )
                }
                RowDivider()
                // «Beträge verbergen»: wie das Auge im Portfolio-Kopf (Portfolio und Widget)
                SettingsAnchor("portfolio.hide") {
                    SwitchRow(
                        title = stringResource(R.string.portfolio_hide_amounts),
                        subtitle = stringResource(R.string.portfolio_hide_amounts_hint),
                        checked = settings.hidePortfolioAmounts,
                        onCheckedChange = viewModel::setHidePortfolioAmounts
                    )
                }
            }
        }
        // Ehrlich: Die Sperre schützt die Anzeige; die Daten schützt die Geräteverschlüsselung
        if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
            Text(
                text = stringResource(R.string.settings_portfolio_lock_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = Spacing.md)
            )
        }
    }
}

// ── Daten ────────────────────────────────────────────────────────────────────

/** Sichern & Wiederherstellen: eine Datei, die der Nutzer selbst ablegt. */
@Composable
private fun BackupPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val reason = stringResource(R.string.portfolio_lock_reason)
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val restoreStep by viewModel.restoreStep.collectAsStateWithLifecycle()
    // Dialog «Sichern» offen: Vorwahl «Mit Passwort schützen» (true, wenn Portfolio-Daten dabei sind)
    var exportDialog by remember { mutableStateOf<Boolean?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) else viewModel.cancelExport() }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restorePicked) }

    SettingsSubPage(title = stringResource(R.string.backup_title), onBack = onBack) {
        GroupCard {
            Hint(stringResource(R.string.backup_hint), top = 10.dp)
            SettingsAnchor("backup.actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)
                ) {
                    FilledTonalButton(
                        onClick = {
                            // Mit Portfolio-Daten und gesperrt: erst entsperren
                            viewModel.startBackupExport(context.findFragmentActivity(), reason) { hasPortfolio ->
                                exportDialog = hasPortfolio
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.backup_export), maxLines = 1) }
                    FilledTonalButton(
                        onClick = {
                            viewModel.startRestore(context.findFragmentActivity(), reason) {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.backup_import), maxLines = 1) }
                }
            }
        }
    }

    exportDialog?.let { defaultProtect ->
        BackupExportDialog(
            defaultProtect = defaultProtect,
            onConfirm = { password ->
                exportDialog = null
                // Gleicher Weg wie bisher (Dateiauswahl des Systems), nur mit gewähltem Schutz
                viewModel.prepareExport(password)
                exportLauncher.launch("cryptochecker-backup-${java.time.LocalDate.now()}.json")
            },
            onDismiss = { exportDialog = null }
        )
    }

    when (val step = restoreStep) {
        is RestoreStep.Password -> BackupPasswordDialog(
            wrong = step.wrong,
            checking = step.checking,
            onSubmit = { password -> viewModel.submitRestorePassword(step.uri, password) },
            onDismiss = viewModel::cancelRestore
        )
        is RestoreStep.Confirm -> AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(stringResource(R.string.backup_import)) },
            text = { Text(stringResource(R.string.backup_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreBackup(step.uri, step.password) }) {
                    Text(stringResource(R.string.backup_import), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text(stringResource(R.string.action_cancel)) }
            }
        )
        null -> Unit
    }

    val exportedText = stringResource(R.string.backup_exported)
    val failedText = stringResource(R.string.backup_failed)
    val restoredFormat = stringResource(R.string.backup_restored)
    LaunchedEffect(backupMessage) {
        val text = when (val m = backupMessage) {
            BackupMessage.Exported -> exportedText
            BackupMessage.Failed -> failedText
            is BackupMessage.Restored -> restoredFormat.format(
                context.resources.getQuantityString(R.plurals.backup_restored_pairs, m.watches, m.watches),
                context.resources.getQuantityString(R.plurals.backup_restored_alarms, m.alarms, m.alarms)
            )
            null -> return@LaunchedEffect
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.clearBackupMessage()
    }
}

// ── Über ─────────────────────────────────────────────────────────────────────

/** Über die App: Zweck, Version (sieben Tipps schalten «Entwickler» frei), Hinweis zu den Daten. */
@Composable
private fun AboutPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var versionTaps by remember { mutableIntStateOf(0) }
    val unlockedText = stringResource(R.string.developer_unlocked)
    SettingsSubPage(title = stringResource(R.string.settings_row_about), onBack = onBack) {
        GroupCard {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                AboutContent(
                    showHeading = false,
                    showLinks = false,
                    onVersionTap = {
                        if (!settings.developerUnlocked) {
                            versionTaps++
                            if (versionTaps == 7) {
                                viewModel.unlockDeveloper()
                                Toast.makeText(context, unlockedText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
        // Was die App kann (früher im Begrüßungsblatt)
        GroupCard {
            AboutFeatures(modifier = Modifier.padding(16.dp))
        }
    }
}

/** Entwickler: HTTP-Protokoll unten auf der Seite «Paar hinzufügen». */
@Composable
private fun DeveloperPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsSubPage(title = stringResource(R.string.settings_section_developer), onBack = onBack) {
        GroupCard {
            SettingsAnchor("developer.http_log") {
                SwitchRow(
                    title = stringResource(R.string.settings_http_log),
                    subtitle = stringResource(R.string.settings_http_log_hint),
                    checked = settings.showHttpLog,
                    onCheckedChange = viewModel::setShowHttpLog
                )
            }
        }
    }
}
