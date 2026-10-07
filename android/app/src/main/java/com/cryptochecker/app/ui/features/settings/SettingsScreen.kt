@file:OptIn(ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.settings.AccentColor
import com.cryptochecker.app.settings.AppLanguages
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.data.portfolio.FxRateSource
import com.cryptochecker.app.ui.components.ComboBox
import com.cryptochecker.app.ui.components.SectionCard
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.components.readableWidth
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.components.rememberAlarmTest
import com.cryptochecker.app.ui.features.about.AboutContent
import com.cryptochecker.app.lock.AppLockAuth
import com.cryptochecker.app.lock.PortfolioLockPolicy
import com.cryptochecker.app.lock.findFragmentActivity
import com.cryptochecker.app.util.BatteryOptimization
import com.cryptochecker.app.ui.theme.LocalDarkTheme
import com.cryptochecker.app.ui.theme.LocalHighContrast
import com.cryptochecker.app.ui.theme.LocalPriceColorsInverted
import com.cryptochecker.app.settings.PriceColorScheme
import com.cryptochecker.app.ui.theme.tabularNumbers
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun SettingsScreen(
    onOpenMarketAlerts: () -> Unit = {},
    onOpenSpeech: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
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

    // Nach der Rückkehr aus den Android-Einstellungen neu prüfen.
    var batteryUnrestricted by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // Tablet/Querformat: Inhalt höchstens 640 dp breit, mittig
                .readableWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Gruppen (Runde 9): Darstellung · Währung · Alarme · Daten · Portfolio · Sicherheit · Erweitert · Über
            // 1 Darstellung: Sprache, Hell/Dunkel, Akzent, Kursfarben, Kontrast, Tauschen, Mini-Chart
            SettingsGroup(stringResource(R.string.settings_section_appearance)) {
                GroupCard {
                    LanguageRow(selectedTag = languageTag, onSelected = viewModel::setLanguage)
                    RowDivider()
                    ThemeModeRow(dark = settings.darkMode, onSelected = viewModel::setDarkMode)
                    AccentColorRow(selected = settings.accentColor, onSelected = viewModel::setAccentColor)
                    // Kursfarben: Blau/Orange für Rot-Grün-Sehschwäche
                    PriceColorSchemeRow(selected = settings.priceColorScheme, onSelected = viewModel::setPriceColorScheme)
                    RowDivider()
                    // Farben tauschen: Rot = steigend (Standard nach Region des Geräts)
                    SwitchRow(
                        title = stringResource(R.string.settings_price_colors_inverted),
                        subtitle = stringResource(R.string.settings_price_colors_inverted_hint),
                        checked = settings.priceColorsInverted,
                        onCheckedChange = viewModel::setPriceColorsInverted
                    )
                    RowDivider()
                    // Hoher Kontrast: kräftigere Kursfarben, dunklere Nebentexte (auch per System)
                    SwitchRow(
                        title = stringResource(R.string.settings_high_contrast),
                        subtitle = stringResource(R.string.settings_high_contrast_hint),
                        checked = settings.highContrast,
                        onCheckedChange = viewModel::setHighContrast
                    )
                    RowDivider()
                    // Mini-Chart (24 h) in den Zeilen der Merkliste
                    SwitchRow(
                        title = stringResource(R.string.settings_watchlist_sparkline),
                        subtitle = stringResource(R.string.settings_watchlist_sparkline_hint),
                        checked = settings.watchlistSparkline,
                        onCheckedChange = viewModel::setWatchlistSparkline
                    )
                    RowDivider()
                    // Widgets direkt aus der App auf den Startbildschirm (Runde 13b)
                    com.cryptochecker.app.ui.components.WidgetsSettingsRow(portfolioEnabled = settings.portfolioEnabled)
                }
            }

            // 2 Währung & Umrechnung: die Einheit der App, kein Aussehen
            SettingsGroup(stringResource(R.string.settings_group_currency)) {
                GroupCard {
                    // Währung zuerst: gilt für Merkliste («≈»), Portfolio, Alarme, Krypto-Markt, Widget
                    ConversionCurrencyRow(
                        selected = settings.portfolioCurrency,
                        onSelected = viewModel::setConversionCurrency
                    )
                    Text(
                        text = stringResource(R.string.settings_conversion_currency_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    // «≈ Umrechnung»: Kurse der Merkliste zusätzlich in der Umrechnungswährung
                    SwitchRow(
                        title = stringResource(R.string.settings_show_converted),
                        subtitle = stringResource(R.string.settings_show_converted_hint, settings.portfolioCurrency),
                        checked = settings.showConverted,
                        onCheckedChange = viewModel::setShowConverted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // 3 Alarme & Benachrichtigungen
            SettingsGroup(stringResource(R.string.settings_group_alerts)) {
                GroupCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_price_notifications),
                        subtitle = stringResource(R.string.settings_price_notifications_hint),
                        checked = settings.priceNotifications,
                        onCheckedChange = {
                            if (it) requestNotifications()
                            viewModel.setPriceNotifications(it)
                        }
                    )
                    if (settings.priceNotifications) {
                        PercentChoiceRow(
                            label = stringResource(R.string.settings_notification_change),
                            selected = settings.notificationChangePercent,
                            onSelected = viewModel::setNotificationChangePercent
                        )
                        Hint(stringResource(R.string.settings_notification_change_hint))
                    }
                    RowDivider()
                    ChoiceRow(
                        label = stringResource(R.string.settings_alarm_cooldown),
                        options = AppSettings.ALARM_COOLDOWN_CHOICES,
                        selected = settings.alarmCooldownMinutes,
                        optionLabel = { stringResource(R.string.settings_minutes, it) },
                        onSelected = viewModel::setAlarmCooldown
                    )
                    RowDivider()
                    AlarmSoundRow(
                        soundName = settings.alarmSoundName,
                        isCustom = settings.alarmSoundUri != null,
                        currentUri = settings.alarmSoundUri,
                        onPicked = viewModel::setAlarmSound,
                        onFile = viewModel::importAlarmSound
                    )
                    RowDivider()
                    // Nachtruhe: Alarme lautlos (ohne Ton, Vibration, Sprachausgabe)
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
                    FilledTonalButton(
                        onClick = testAlarm,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.alarm_test))
                    }
                    Hint(stringResource(R.string.alarm_test_hint), top = 4.dp)
                }
                // Runde 13b: Markt-Meldungen und Sprachausgabe auf eigenen Unterseiten,
                // hier je eine Zeile mit Kurzwert («2 aktiv», «Aus»)
                GroupCard {
                    SubPageRow(
                        title = stringResource(R.string.settings_market_alerts),
                        subtitle = stringResource(R.string.settings_market_alerts_hint),
                        value = marketAlertsSummary(settings),
                        onClick = onOpenMarketAlerts
                    )
                    RowDivider()
                    SubPageRow(
                        title = stringResource(R.string.settings_tts),
                        subtitle = stringResource(R.string.settings_tts_row_hint),
                        value = speechSummary(settings),
                        onClick = onOpenSpeech
                    )
                }
            }

            // 4 Daten & Aktualisierung (Akku gehört zur Hintergrund-Aktualisierung)
            SettingsGroup(stringResource(R.string.settings_group_data)) {
                GroupCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_background_updates),
                        subtitle = stringResource(R.string.settings_background_updates_hint),
                        checked = settings.backgroundUpdates,
                        onCheckedChange = viewModel::setBackgroundUpdates
                    )
                    if (settings.backgroundUpdates) {
                        ChoiceRow(
                            label = stringResource(R.string.settings_background_interval),
                            options = AppSettings.BACKGROUND_INTERVAL_CHOICES,
                            selected = settings.backgroundIntervalMinutes,
                            optionLabel = { stringResource(R.string.settings_minutes, it) },
                            onSelected = viewModel::setBackgroundInterval
                        )
                    }
                    RowDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 10.dp)
                    ) {
                        StatusDot(ok = batteryUnrestricted)
                        Text(
                            text = stringResource(
                                if (batteryUnrestricted) R.string.settings_battery_ok
                                else R.string.settings_battery_restricted
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 10.dp)
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

            // 5 Portfolio: optionaler eigener Tab
            SettingsGroup(stringResource(R.string.portfolio_title)) {
                GroupCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_portfolio_tab),
                        subtitle = stringResource(R.string.portfolio_setting_hint),
                        checked = settings.portfolioEnabled,
                        onCheckedChange = viewModel::setPortfolioEnabled
                    )
                }
            }

            // 6 Sicherheit & Backup: Portfolio-Sperre (nur mit Portfolio), dann Sichern & Wiederherstellen
            SettingsGroup(stringResource(R.string.settings_group_security)) {
                GroupCard {
                    val unavailableText = stringResource(R.string.app_lock_unavailable)
                    val reason = stringResource(R.string.portfolio_lock_reason)
                    // Nur mit eingeschaltetem Portfolio; der Wert bleibt beim Ausblenden erhalten
                    if (PortfolioLockPolicy.showSetting(settings.portfolioEnabled)) {
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
                        RowDivider()
                    }
                    // Sichern & Wiederherstellen: eine Datei, die der Nutzer selbst ablegt
                    Text(
                        stringResource(R.string.backup_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
                    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
                    val exportLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/json")
                    ) { uri -> uri?.let(viewModel::exportBackup) }
                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> pendingRestore = uri }

                    Hint(stringResource(R.string.backup_hint), top = 2.dp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                // Mit Portfolio-Daten und gesperrt: erst entsperren
                                viewModel.startBackupExport(context.findFragmentActivity(), reason) {
                                    exportLauncher.launch("cryptochecker-backup-${java.time.LocalDate.now()}.json")
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

                    pendingRestore?.let { uri ->
                        AlertDialog(
                            onDismissRequest = { pendingRestore = null },
                            title = { Text(stringResource(R.string.backup_import)) },
                            text = { Text(stringResource(R.string.backup_restore_confirm)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.restoreBackup(uri)
                                    pendingRestore = null
                                }) { Text(stringResource(R.string.backup_import), color = MaterialTheme.colorScheme.error) }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.action_cancel)) }
                            }
                        )
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
            }

            // 7 «Erweitert», eingeklappt (Zustand nicht gespeichert): Live-Modus, feste Mitteilung,
            // Laufzeit-Futures und — nach sieben Tipps auf die Version — Entwickleroptionen.
            // Die Klappzeile ist selbst die Überschrift der Gruppe.
            SectionCard(title = null) {
                var advanced by rememberSaveable { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advanced = !advanced }
                        .semantics { heading() }
                        .padding(vertical = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(R.string.settings_section_advanced),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.settings_advanced_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { rotationZ = if (advanced) 90f else 0f }
                    )
                }
                if (advanced) {
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_live_service),
                        subtitle = stringResource(R.string.settings_live_service_hint),
                        checked = settings.liveService,
                        onCheckedChange = viewModel::setLiveService
                    )
                    if (settings.liveService) {
                        ChoiceRow(
                            label = stringResource(R.string.settings_live_interval),
                            options = AppSettings.LIVE_INTERVAL_CHOICES,
                            selected = settings.liveIntervalSeconds,
                            // Kurz: «15 s», «5 min» statt «300 Sekunden»
                            optionLabel = {
                                if (it >= 60 && it % 60 == 0) stringResource(R.string.settings_minutes, it / 60)
                                else stringResource(R.string.settings_seconds, it)
                            },
                            onSelected = viewModel::setLiveInterval
                        )
                    }
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_ongoing_notifications),
                        subtitle = stringResource(R.string.settings_ongoing_notifications_hint),
                        checked = settings.ongoingNotifications,
                        onCheckedChange = viewModel::setOngoingNotifications
                    )
                    RowDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_rolling_futures),
                        subtitle = stringResource(R.string.settings_rolling_futures_hint),
                        checked = settings.includeRollingFutures,
                        onCheckedChange = viewModel::setIncludeRollingFutures
                    )
                    // Entwickler erst nach sieben Tipps auf die Version
                    if (settings.developerUnlocked) {
                        RowDivider()
                        Text(
                            stringResource(R.string.settings_section_developer),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        SwitchRow(
                            title = stringResource(R.string.settings_http_log),
                            subtitle = stringResource(R.string.settings_http_log_hint),
                            checked = settings.showHttpLog,
                            onCheckedChange = viewModel::setShowHttpLog
                        )
                    }
                }
            }

            // 8 Über (Version, Datenschutz, GitHub)
            SettingsGroup(stringResource(R.string.settings_section_about)) {
                GroupCard {
                    var versionTaps by remember { mutableIntStateOf(0) }
                    val unlockedText = stringResource(R.string.developer_unlocked)
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        AboutContent(
                            showHeading = false,
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
            }
        }
    }
}

/**
 * Gruppe der Einstellungen: ruhige Überschrift (wie `SectionCard`, für den
 * Screenreader als Überschrift markiert), darunter eine oder mehrere Karten.
 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() }
        )
        content()
    }
}

/** Karte innerhalb einer Gruppe — gleiche Fläche wie `SectionCard`, ohne eigene Überschrift. */
@Composable
internal fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

@Composable
internal fun Hint(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = top, bottom = 10.dp)
    )
}

/** Minuten seit Mitternacht im kurzen Zeitformat des Geräts (12/24 h wie eingestellt). */
private fun formatMinuteOfDay(context: android.content.Context, minute: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minute / 60)
        set(java.util.Calendar.MINUTE, minute % 60)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

/** «Von»/«Bis» der Nachtruhe; ein Tipp öffnet die Zeitauswahl des Systems. */
@Composable
private fun QuietTimeRow(label: String, minute: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, min -> onPicked(hour * 60 + min) },
                    minute / 60,
                    minute % 60,
                    android.text.format.DateFormat.is24HourFormat(context)
                ).show()
            }
            .padding(vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            formatMinuteOfDay(context, minute),
            style = MaterialTheme.typography.bodyLarge.tabularNumbers(),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}


/** Grüner bzw. oranger Punkt als Status. */
@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ok) Color(0xFF2FA36B) else Color(0xFFE5A23C))
    )
}

/** Zeigt die gewählte Sprache; ein Tipp öffnet die Auswahl mit Suchfeld. */
@Composable
private fun LanguageRow(selectedTag: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)
    val current = AppLanguages.ALL.firstOrNull { it.tag == selectedTag }?.nativeName ?: systemLabel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Ohne eigene Karte mehr: die Zeile nennt «Sprache» selbst, die Wahl rechts
        Text(
            stringResource(R.string.settings_section_language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Text(
            current,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (open) {
        var query by remember { mutableStateOf("") }
        val options = listOf("" to systemLabel) + AppLanguages.ALL.map { it.tag to it.nativeName }
        val q = query.trim()
        val visible = if (q.isEmpty()) options else options.filter { (tag, name) ->
            name.contains(q, ignoreCase = true) || tag.startsWith(q, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_language_choose)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.language_search)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(visible, key = { it.first }) { (tag, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        open = false
                                        if (tag != selectedTag) onSelected(tag)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = tag == selectedTag,
                                    onClick = {
                                        open = false
                                        if (tag != selectedTag) onSelected(tag)
                                    }
                                )
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private fun parsePercent(text: String): Double? =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.0..100.0 }

private fun formatPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Umrechnungswährung als Aufklappliste (mit Suche). Gilt für die umgerechneten
 * Kurse der Merkliste, Alarme in eigener Währung und das Portfolio.
 */
@Composable
private fun ConversionCurrencyRow(selected: String, onSelected: (String) -> Unit) {
    // Eine früher gesetzte, nicht mehr gelistete Währung trotzdem anzeigen
    val codes = remember(selected) {
        if (selected in FxRateSource.CURRENCIES) FxRateSource.CURRENCIES else FxRateSource.CURRENCIES + selected
    }
    ComboBox(
        selectedIndex = codes.indexOf(selected),
        itemList = codes,
        onValueChange = { index -> codes.getOrNull(index)?.let { if (it != selected) onSelected(it) } },
        label = stringResource(R.string.settings_conversion_currency),
        searchable = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
    )
}

/** Wie das System, Hell oder Dunkel — gilt für App und Widgets. */
@Composable
private fun ThemeModeRow(dark: Boolean?, onSelected: (Boolean?) -> Unit) {
    val options = listOf(
        null to R.string.theme_system,
        false to R.string.theme_light,
        true to R.string.theme_dark,
    )
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
        Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == dark,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                ) {
                    Text(stringResource(label))
                }
            }
        }
    }
}

/** Farbkreise zur Auswahl der Akzentfarbe; die gewählte hat einen Ring. */
@Composable
private fun AccentColorRow(selected: AccentColor, onSelected: (AccentColor) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyMedium)
        // Fünf Farben: gleich breite Spalten über die ganze Zeile, damit sie auch auf
        // schmalen Geräten (360 dp) und bei grosser Schrift nebeneinander passen;
        // lange Namen («Marrs Green») brechen in die zweite Zeile um.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AccentColor.entries.forEach { accent ->
                val isSelected = accent == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelected(accent) }
                        .padding(4.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(Color(accent.seed))
                    ) {}
                    Text(
                        text = stringResource(accent.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Kursfarben als Segmentleiste. Jede Option zeigt ihre beiden Farben als
 * kleine Punkte, damit die Wahl ohne Ausprobieren verständlich ist.
 */
@Composable
private fun PriceColorSchemeRow(selected: PriceColorScheme, onSelected: (PriceColorScheme) -> Unit) {
    val dark = LocalDarkTheme.current
    val highContrast = LocalHighContrast.current
    val inverted = LocalPriceColorsInverted.current
    val options = PriceColorScheme.entries
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(stringResource(R.string.settings_price_colors), style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, scheme ->
                SegmentedButton(
                    selected = scheme == selected,
                    onClick = { onSelected(scheme) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {}
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(scheme.up(dark, highContrast, inverted))))
                        Box(
                            Modifier.padding(start = 3.dp, end = 6.dp).size(8.dp).clip(CircleShape)
                                .background(Color(scheme.down(dark, highContrast, inverted)))
                        )
                        Text(
                            stringResource(
                                when (scheme) {
                                    PriceColorScheme.GREEN_RED -> R.string.price_colors_green_red
                                    PriceColorScheme.BLUE_ORANGE -> R.string.price_colors_blue_orange
                                }
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_price_colors_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
        )
    }
}

/**
 * Melde-Schwelle als Segmente: 0 / 3 / 5 / 7 % und als letztes ein eigener
 * Wert, der sich frei eingeben lässt (z. B. 2,5).
 */
@Composable
private fun PercentChoiceRow(label: String, selected: Double, onSelected: (Double) -> Unit) {
    val isCustom = selected !in PERCENT_CHOICES
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val count = PERCENT_CHOICES.size + 1
            PERCENT_CHOICES.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = count),
                    icon = {}
                ) {
                    Text(formatPercent(option) + "%", maxLines = 1)
                }
            }
            // Letztes Segment: eigener Wert
            SegmentedButton(
                selected = isCustom,
                onClick = { editing = true },
                shape = SegmentedButtonDefaults.itemShape(index = count - 1, count = count),
                icon = {}
            ) {
                Text(
                    if (isCustom) formatPercent(selected) + "%" else stringResource(R.string.settings_custom_value),
                    maxLines = 1
                )
            }
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(if (isCustom) formatPercent(selected) else "") }
        val parsed = parsePercent(text)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
                    singleLine = true,
                    suffix = { Text("%") },
                    isError = text.isNotEmpty() && parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let(onSelected)
                        editing = false
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private val PERCENT_CHOICES = listOf(0.0, 3.0, 5.0, 7.0)

/** Auswahl aus wenigen Werten als Segmentleiste. */
@Composable
internal fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    optionLabel: @Composable (Int) -> String,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {}
                ) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
    }
}

/**
 * Alarmton (#202): Systemton aus der Android-Auswahl, eigene Audiodatei
 * (ab Android 10) oder zurück zum Standardton.
 */
@Composable
private fun AlarmSoundRow(
    soundName: String?,
    isCustom: Boolean,
    currentUri: String?,
    onPicked: (android.net.Uri?) -> Unit,
    onFile: (android.net.Uri) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val pickRingtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri: android.net.Uri? = androidx.core.content.IntentCompat.getParcelableExtra(
            result.data ?: return@rememberLauncherForActivityResult,
            android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
            android.net.Uri::class.java
        )
        // «Standard» in der Auswahl = Standard-Mitteilungston
        val isDefault = uri == null || android.media.RingtoneManager.isDefault(uri)
        onPicked(if (isDefault) null else uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onFile)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menu = true }
                .padding(vertical = 14.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_alarm_sound), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isCustom) soundName ?: stringResource(R.string.settings_alarm_sound_custom)
                    else stringResource(R.string.settings_alarm_sound_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_alarm_sound_system)) },
                onClick = {
                    menu = false
                    val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                            android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_ALARM
                        )
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            currentUri?.let { android.net.Uri.parse(it) }
                                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                        )
                    }
                    runCatching { pickRingtone.launch(intent) }
                }
            )
            if (com.cryptochecker.app.notification.AlarmSoundStore.supportsCustomFile) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alarm_sound_file)) },
                    onClick = {
                        menu = false
                        runCatching { pickFile.launch(arrayOf("audio/*")) }
                    }
                )
            }
            if (isCustom) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_alarm_sound_reset)) },
                    onClick = { menu = false; onPicked(null) }
                )
            }
        }
    }
}
