package com.cryptochecker.app.ui.features.settings

import android.widget.Toast
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
import com.cryptochecker.app.settings.AppSettings
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.components.rememberAlarmTest
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest

// ── Alarme ───────────────────────────────────────────────────────────────────

/** Alarme: Kurs-Mitteilungen, Ruhezeit, Ton, Alarm-Signal, Nachtruhe, Probe-Alarm. */
@Composable
internal fun AlarmsPage(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
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
