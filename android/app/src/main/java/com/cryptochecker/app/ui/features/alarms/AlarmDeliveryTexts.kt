// Texte der Seite «Damit Alarme ankommen»: Titel, Detail, Hinweis des Herstellers, Zeit und Knopf je Zeile.
package com.cryptochecker.app.ui.features.alarms

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.data.WatchRepository
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.alarm.OemBattery
import com.cryptochecker.app.domain.refresh.CheckRunLog
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.ui.components.AppCard
import com.cryptochecker.app.ui.components.CardPadding
import com.cryptochecker.app.ui.components.NotificationAsk
import com.cryptochecker.app.ui.components.NotificationPrePrompt
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.rememberNotificationPermissionRequest
import com.cryptochecker.app.ui.features.settings.exactMinutes
import com.cryptochecker.app.ui.features.settings.formatMinuteOfDay
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.work.AlarmReadinessProbe
import com.cryptochecker.app.work.BatteryOptimization
import com.cryptochecker.app.work.ExactCheckScheduler
import com.cryptochecker.app.work.OemBatterySettings
import com.cryptochecker.resources.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Composable
internal fun deliveryTitle(row: AlarmReadiness.Row): String = stringResource(
    when (row) {
        AlarmReadiness.Row.NOTIFICATIONS -> R.string.delivery_notifications
        AlarmReadiness.Row.CHANNEL -> R.string.delivery_channel
        AlarmReadiness.Row.EXACT -> R.string.settings_exact_checks
        AlarmReadiness.Row.BATTERY -> R.string.delivery_battery
        AlarmReadiness.Row.BACKGROUND -> R.string.delivery_background
        AlarmReadiness.Row.OEM_BATTERY -> R.string.oem_battery_title
        AlarmReadiness.Row.DND -> R.string.delivery_dnd
        AlarmReadiness.Row.QUIET_HOURS -> R.string.settings_quiet_hours
        AlarmReadiness.Row.LAST_CHECK -> R.string.delivery_last_check
        // Zeilen nur auf iOS (gleiche Texte, dort aus dem Katalog)
        AlarmReadiness.Row.ALERT_SOUND -> R.string.delivery_alert_sound
        AlarmReadiness.Row.TIME_SENSITIVE -> R.string.delivery_time_sensitive
        AlarmReadiness.Row.BACKGROUND_REFRESH -> R.string.delivery_background_refresh
        AlarmReadiness.Row.LOW_POWER -> R.string.delivery_low_power
        AlarmReadiness.Row.SWIPE_AWAY -> R.string.delivery_swipe_away
        AlarmReadiness.Row.LIVE_ACTIVITY -> R.string.delivery_live_activity
    }
)

@Composable
internal fun deliveryDetail(item: AlarmReadiness.Item, settings: AppSettings, lastCheck: Long?): String {
    val context = LocalContext.current
    return when (item.detail) {
        AlarmReadiness.Detail.ALLOWED -> stringResource(R.string.delivery_allowed)
        AlarmReadiness.Detail.ON -> when (item.row) {
            AlarmReadiness.Row.DND -> stringResource(R.string.delivery_dnd_on)
            AlarmReadiness.Row.LOW_POWER -> stringResource(R.string.ios_low_power_mode)
            else -> stringResource(R.string.settings_summary_on)
        }
        AlarmReadiness.Detail.OFF -> when (item.row) {
            AlarmReadiness.Row.NOTIFICATIONS -> stringResource(R.string.delivery_notifications_off)
            AlarmReadiness.Row.TIME_SENSITIVE -> stringResource(R.string.delivery_time_sensitive_off)
            AlarmReadiness.Row.BACKGROUND_REFRESH -> stringResource(R.string.ios_background_refresh_off)
            else -> stringResource(R.string.option_off)
        }
        AlarmReadiness.Detail.NOT_ASKED -> stringResource(R.string.delivery_notifications_not_asked)
        AlarmReadiness.Detail.CHANNEL_HIGH -> stringResource(R.string.delivery_channel_high)
        AlarmReadiness.Detail.CHANNEL_LOW -> stringResource(R.string.delivery_channel_low)
        AlarmReadiness.Detail.CHANNEL_OFF -> stringResource(R.string.delivery_channel_off)
        AlarmReadiness.Detail.EXACT_ON -> exactMinutes(settings.backgroundIntervalMinutes)
            .let { pluralStringResource(R.plurals.delivery_exact_on, it, it) }
        AlarmReadiness.Detail.EXACT_OFF -> stringResource(R.string.delivery_exact_off)
        AlarmReadiness.Detail.EXACT_NO_PERMISSION -> stringResource(R.string.settings_exact_checks_permission_missing)
        AlarmReadiness.Detail.BATTERY_UNRESTRICTED -> stringResource(R.string.delivery_battery_unrestricted)
        AlarmReadiness.Detail.BATTERY_OPTIMIZED -> stringResource(R.string.delivery_battery_optimized)
        AlarmReadiness.Detail.RESTRICTED -> stringResource(R.string.delivery_background_restricted)
        AlarmReadiness.Detail.ALERT_OFF -> stringResource(R.string.delivery_alert_off)
        AlarmReadiness.Detail.SOUND_OFF -> stringResource(R.string.delivery_sound_off)
        AlarmReadiness.Detail.QUIET_NOW ->
            stringResource(R.string.delivery_quiet_now, formatMinuteOfDay(context, settings.quietHoursEnd))
        AlarmReadiness.Detail.QUIET_SET -> stringResource(
            R.string.delivery_quiet_set,
            formatMinuteOfDay(context, settings.quietHoursStart),
            formatMinuteOfDay(context, settings.quietHoursEnd),
        )
        AlarmReadiness.Detail.CHECK_RECENT -> lastCheck?.let { checkTime(it) } ?: ""
        AlarmReadiness.Detail.CHECK_OLD ->
            stringResource(R.string.delivery_last_check_old, lastCheck?.let { checkTime(it) } ?: "")
        AlarmReadiness.Detail.CHECK_NEVER -> stringResource(R.string.delivery_last_check_never)
        AlarmReadiness.Detail.OEM_NOTE -> stringResource(oemBatteryNote(OemBatterySettings.vendor()))
        AlarmReadiness.Detail.NOTE -> when (item.row) {
            AlarmReadiness.Row.SWIPE_AWAY -> stringResource(R.string.delivery_swipe_away_note)
            else -> stringResource(R.string.delivery_live_activity_note)
        }
    }
}

/** Weg zur Einstellung je Hersteller ([OemBattery]); ohne bekannten Hersteller erscheint die Zeile nicht. */
internal fun oemBatteryNote(vendor: OemBattery.Vendor?): Int = when (vendor) {
    OemBattery.Vendor.XIAOMI -> R.string.oem_battery_xiaomi
    OemBattery.Vendor.SAMSUNG -> R.string.oem_battery_samsung
    OemBattery.Vendor.HUAWEI, null -> R.string.oem_battery_huawei
    OemBattery.Vendor.ONEPLUS -> R.string.oem_battery_oneplus
    OemBattery.Vendor.OPPO -> R.string.oem_battery_oppo
    OemBattery.Vendor.VIVO -> R.string.oem_battery_vivo
}

/** Heute nur die Uhrzeit, sonst mit Datum — wie «Letzte Prüfungen» (auch «Zuletzt geprüft» im Alarm-Editor). */
@Composable
internal fun checkTime(at: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(at)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateTime(context, at, flags)
}

@Composable
internal fun actionLabel(action: AlarmReadiness.Action): String? = when (action) {
    AlarmReadiness.Action.NONE -> null
    AlarmReadiness.Action.NOTIFICATION_SETTINGS, AlarmReadiness.Action.CHANNEL_SETTINGS,
    AlarmReadiness.Action.APP_SETTINGS, AlarmReadiness.Action.OEM_SETTINGS ->
        stringResource(R.string.alarm_test_open_settings)
    AlarmReadiness.Action.REQUEST_PERMISSION -> stringResource(R.string.delivery_action_allow)
    AlarmReadiness.Action.EXACT_PERMISSION -> stringResource(R.string.settings_exact_checks_grant)
    AlarmReadiness.Action.UPDATE_SETTINGS -> stringResource(R.string.alarm_change_updates)
    AlarmReadiness.Action.BATTERY_SETTINGS -> stringResource(R.string.battery_open_settings)
    AlarmReadiness.Action.ALARM_SETTINGS -> stringResource(R.string.delivery_action_change)
    AlarmReadiness.Action.CHECK_RUNS -> stringResource(R.string.delivery_action_show)
}
