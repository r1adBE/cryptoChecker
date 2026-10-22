package com.cryptochecker.app.ui.features.settings

import androidx.compose.ui.platform.LocalResources
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.AppCard
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.WatchlistStorageLock
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.components.rememberAlarmTest
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.util.PercentText

// ── Alarme ───────────────────────────────────────────────────────────────────

/**
 * Alarme: «Wie aktuell?» (öffnet «Aktualisierung», [onOpenPage]), Kurs-Mitteilungen, Ruhezeit, Ton,
 * Alarm-Signal, Nachtruhe, Probe-Alarm.
 */
@Composable
internal fun AlarmsPage(
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Texte über LocalResources (Lint: kein getString über LocalContext in Compose)
    val resources = LocalResources.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val testAlarm = rememberAlarmTest(viewModel::testAlarm)
    val soundError by viewModel.soundError.collectAsStateWithLifecycle()
    LaunchedEffect(soundError) {
        if (soundError) {
            Toast.makeText(context, resources.getString(R.string.settings_alarm_sound_error), Toast.LENGTH_LONG).show()
            viewModel.clearSoundError()
        }
    }

    SettingsSubPage(title = stringResource(R.string.settings_row_alarms), onBack = onBack) {
        // «Wie aktuell?» zuerst: davon hängt ab, wie zeitnah Alarme melden (dieselbe Seite wie «Aktualisierung» unter «Allgemein»)
        AppCard(padding = CardPadding.None) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                SettingsNavRow(
                    title = stringResource(R.string.update_mode_title),
                    icon = R.drawable.ic_settings_updates,
                    value = updatesSummary(settings),
                    onClick = { onOpenPage(SettingsPage.UPDATES) }
                )
                // Damit Alarme ankommen: Erlaubnisse, Kanal, Akku und letzte Prüfung auf einen Blick
                com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmDelivery.current?.let { openDelivery ->
                    SettingsAnchor("alarms.delivery") {
                        SettingsNavRow(
                            title = stringResource(R.string.delivery_title),
                            icon = R.drawable.ic_notifications_active_outline,
                            onClick = openDelivery,
                        )
                    }
                }
            }
        }
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
                // Welche Paare: Zahl aktiv/alle, Tipp öffnet die Liste mit «Alle an / Alle aus»
                val watches by viewModel.watches.collectAsStateWithLifecycle()
                SettingsAnchor("alarms.price_pairs") {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_price_pairs),
                        value = stringResource(
                            R.string.settings_price_pairs_count,
                            watches.count { it.notificationEnabled }, watches.size
                        ),
                        onClick = { onOpenPage(SettingsPage.PRICE_PAIRS) }
                    )
                }
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
            // Zahl am App-Symbol (Zahl oder Punkt entscheidet der Startbildschirm)
            SettingsAnchor("alarms.badge") {
                SwitchRow(
                    title = stringResource(R.string.settings_app_badge),
                    subtitle = stringResource(R.string.settings_app_badge_hint),
                    checked = settings.appIconBadge,
                    onCheckedChange = viewModel::setAppIconBadge
                )
            }
            RowDivider()
            // USDT-Depeg melden: einmal je Abweichung; Bewertungen rechnen mit dem gemessenen Marktkurs
            SettingsAnchor("alarms.depeg") {
                SwitchRow(
                    title = stringResource(R.string.settings_depeg_alarm),
                    subtitle = stringResource(R.string.settings_depeg_alarm_hint),
                    checked = settings.depegAlarm,
                    onCheckedChange = {
                        if (it) requestNotifications()
                        viewModel.setDepegAlarm(it)
                    }
                )
            }
            if (settings.depegAlarm) {
                ChoiceRow(
                    label = stringResource(R.string.settings_depeg_threshold),
                    options = DepegAlarm.THRESHOLD_CHOICES,
                    selected = settings.depegThresholdTenths,
                    optionLabel = { PercentText.unsigned(it / 10.0, 1, minDecimals = 0) },
                    onSelected = viewModel::setDepegThreshold
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

// ── Kursmitteilungen › Paare ─────────────────────────────────────────────────

/**
 * Kursmitteilung je Paar an/aus (gleicher Schalter wie im Aktionsblatt), dazu «Alle an» / «Alle aus».
 * Wie iOS `PricePairsPage`.
 */
@Composable
internal fun PricePairsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val watches by viewModel.watches.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()
    // Merkliste gesperrt: Schalter abgelehnt (bleibt beim echten Zustand) — kurz sagen, warum; Einstellungen melden per Toast
    val context = LocalContext.current
    com.cryptochecker.app.ui.components.WatchlistLockedHint { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    SettingsSubPage(title = stringResource(R.string.settings_price_notifications), onBack = onBack) {
        Hint(stringResource(R.string.settings_price_pairs_hint), top = 4.dp)
        if (watches.isEmpty()) {
            Hint(stringResource(R.string.settings_price_pairs_empty))
        } else {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = {
                        // Merkliste gesperrt: nur der Hinweis, keine Erlaubnis-Anfrage (seit Build 104)
                        if (!WatchlistStorageLock.blocksWritesNow()) requestNotifications()
                        viewModel.setAllPairNotifications(true)
                    },
                    enabled = watches.any { !it.notificationEnabled },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.settings_price_pairs_all_on)) }
                FilledTonalButton(
                    onClick = { viewModel.setAllPairNotifications(false) },
                    enabled = watches.any { it.notificationEnabled },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.settings_price_pairs_all_off)) }
        }
        GroupCard {
            watches.forEachIndexed { index, watch ->
                if (index > 0) RowDivider()
                SwitchRow(
                    title = watch.displayName,
                    subtitle = watch.marketName,
                    checked = watch.notificationEnabled,
                    onCheckedChange = { on ->
                        if (on && !WatchlistStorageLock.blocksWritesNow()) requestNotifications()
                        viewModel.setPairNotification(watch.id, on)
                    }
                )
            }
        }
        }
    }
}
