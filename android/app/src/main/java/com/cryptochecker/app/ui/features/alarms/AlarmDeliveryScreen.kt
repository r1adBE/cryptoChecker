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
import com.cryptochecker.app.data.portfolio.PortfolioAlarmDao
import com.cryptochecker.app.domain.alarm.AlarmReadiness
import com.cryptochecker.app.domain.alarm.OemBattery
import com.cryptochecker.app.domain.refresh.CheckRunLog
import com.cryptochecker.app.domain.refresh.CheckRunStore
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.settings.SettingsRepository
import com.cryptochecker.app.domain.watch.WatchStore
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

/** Öffnet «Damit Alarme ankommen» (gesetzt in `AppNavHost`); null = ohne Navigation (Vorschau, Widget-Einrichtung). */
val LocalOpenAlarmDelivery = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Stand für «Damit Alarme ankommen»: Einstellungen, letzte Prüfung im Hintergrund, gibt es scharfe Alarme? Nur lesen. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlarmDeliveryViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    checkRunStore: CheckRunStore,
    watchRepository: WatchStore,
    portfolioAlarmDao: PortfolioAlarmDao,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.cached)

    /** Ende der letzten vom System geplanten Prüfung (WorkManager, «Pünktlich prüfen»); Widget, App und gescheiterte Läufe zählen nicht. */
    val lastBackgroundCheck: StateFlow<Long?> = checkRunStore.runs.map(CheckRunLog::lastBackgroundCheck)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckRunLog.lastBackgroundCheck(checkRunStore.runs.value))

    /** Neuester geplanter Lauf, wenn keiner der gespeicherten gelang (Zuverlässigkeitszeile warnt dann). */
    val lastFailedBackgroundCheck: StateFlow<Long?> = checkRunStore.runs.map(CheckRunLog::lastFailedBackgroundCheck)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckRunLog.lastFailedBackgroundCheck(checkRunStore.runs.value))

    /** Neuester geplanter Lauf offline nicht versucht: die Zuverlässigkeitszeile nennt das Netz statt «Akku». */
    val offlineSkipped: StateFlow<Boolean> = checkRunStore.runs.map(CheckRunLog::offlineSkipped)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckRunLog.offlineSkipped(checkRunStore.runs.value))

    /** Ältester gespeicherter geplanter Lauf, wenn keiner gelang: seit dann scheitert jede Prüfung. */
    val failingSince: StateFlow<Long?> = checkRunStore.runs.map(CheckRunLog::failingSince)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckRunLog.failingSince(checkRunStore.runs.value))

    /** Alles, was nur eine Prüfung im Hintergrund meldet ([AlarmReadiness.hasBackgroundAlarms]), nicht nur Paar-Alarme. */
    val hasAlarms: StateFlow<Boolean> = combine(
        watchRepository.observeAllAlarms()
            .map { alarms -> alarms.any { it.alarm.enabled } }
            .catch { emit(false) },
        settingsRepository.settings
            .map { it.portfolioEnabled }
            .distinctUntilChanged()
            // Portfolio aus: Datenbank gar nicht erst öffnen (wie PriceUpdateScheduler)
            .flatMapLatest { on ->
                if (on) {
                    portfolioAlarmDao.observeAll().map { list -> list.any { it.enabled } }.catch { emit(false) }
                } else {
                    flowOf(false)
                }
            },
        settingsRepository.settings,
    ) { pairAlarm, portfolioAlarm, settings ->
        AlarmReadiness.hasBackgroundAlarms(alarmKinds(settings, pairAlarm, portfolioAlarm), AlarmReadiness.Platform.ANDROID)
    }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/** Alarm-Arten aus Einstellungen und Alarmen ([AlarmReadiness.hasBackgroundAlarms]); auch für «Diagnose teilen». */
internal fun alarmKinds(settings: AppSettings, pairAlarm: Boolean, portfolioAlarm: Boolean) = AlarmReadiness.AlarmKinds(
    pairAlarm = pairAlarm,
    portfolioEnabled = settings.portfolioEnabled,
    portfolioAlarm = portfolioAlarm,
    gasEthTenths = settings.gasAlertEthTenths,
    gasBtc = settings.gasAlertBtc,
    depeg = settings.depegAlarm,
    fearGreedBelow = settings.fearGreedBelow,
    fearGreedAbove = settings.fearGreedAbove,
    indicatorAlert = settings.indicatorAlerts.values.any { it > 0 },
    activityAlerts = settings.activityAlerts,
    macroNotices = settings.macroNotifications,
)

/**
 * «Damit Alarme ankommen»: der echte Zustand je Punkt (✓ / ⚠ / Hinweis) mit Knopf zur passenden
 * Systemseite. Erreichbar aus «Alle Alarme», dem Hinweis im Alarm-Editor und Einstellungen › Alarme. Wie
 * `AlarmDeliveryScreen` (iOS). Nach der Rückkehr aus den Android-Einstellungen neu gelesen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDeliveryScreen(
    onBack: () -> Unit,
    onOpenAlarmSettings: () -> Unit,
    onOpenUpdateSettings: () -> Unit,
    viewModel: AlarmDeliveryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastCheck by viewModel.lastBackgroundCheck.collectAsStateWithLifecycle()
    val hasAlarms by viewModel.hasAlarms.collectAsStateWithLifecycle()
    // Zurück aus den Systemeinstellungen: alles neu lesen
    var readAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        readAt = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    val items = remember(settings, lastCheck, hasAlarms, readAt) {
        AlarmReadiness.rows(
            AlarmReadinessProbe.read(
                context,
                settings,
                lastCheck,
                hasAlarms,
                readAt,
                notificationsNotDetermined = NotificationPrePrompt.notDetermined(context),
            )
        )
    }
    // Noch nie gefragt (Android 13+): gleich die System-Abfrage, wie iOS; danach liest die Rückkehr (Resume) neu
    val requestPermission = rememberNotificationPermissionRequest(NotificationAsk.DIRECT)
    val act: (AlarmReadiness.Action) -> Unit = { action ->
        when (action) {
            AlarmReadiness.Action.REQUEST_PERMISSION -> requestPermission()
            AlarmReadiness.Action.NOTIFICATION_SETTINGS -> AlarmReadinessProbe.openAppNotificationSettings(context)
            AlarmReadiness.Action.CHANNEL_SETTINGS ->
                AlarmReadinessProbe.openChannelSettings(context, AlarmReadinessProbe.alarmChannelId(settings))
            AlarmReadiness.Action.EXACT_PERMISSION -> ExactCheckScheduler.openPermissionSettings(context)
            // Nur die Akku-Seite der App öffnen — nie um die Ausnahme bitten (Projektentscheid)
            AlarmReadiness.Action.BATTERY_SETTINGS -> BatteryOptimization.openSettings(context)
            AlarmReadiness.Action.APP_SETTINGS -> AlarmReadinessProbe.openAppDetails(context)
            AlarmReadiness.Action.OEM_SETTINGS -> OemBatterySettings.open(context)
            AlarmReadiness.Action.UPDATE_SETTINGS, AlarmReadiness.Action.CHECK_RUNS -> onOpenUpdateSettings()
            AlarmReadiness.Action.ALARM_SETTINGS -> onOpenAlarmSettings()
            AlarmReadiness.Action.NONE -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.delivery_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        ReadableInset { inset ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp + inset, top = 8.dp, end = 16.dp + inset, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // Derselbe ehrliche Satz wie unter jedem Alarm
                Text(
                    alarmTimingHintText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppCard(padding = CardPadding.None) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DeliveryRow(item, settings, lastCheck, onAction = act)
                    }
                }
                // Immer erreichbar, auch wenn jede Zeile «in Ordnung» zeigt (der Alarm-Editor verweist nur noch hierher)
                TextButton(onClick = onOpenUpdateSettings) { Text(stringResource(R.string.alarm_change_updates)) }
            }
        }
    }
}

@Composable
private fun DeliveryRow(
    item: AlarmReadiness.Item,
    settings: AppSettings,
    lastCheck: Long?,
    onAction: (AlarmReadiness.Action) -> Unit,
) {
    val title = deliveryTitle(item.row)
    val detail = deliveryDetail(item, settings, lastCheck)
    val stateLabel = stringResource(
        when (item.state) {
            AlarmReadiness.State.OK -> R.string.delivery_state_ok
            AlarmReadiness.State.WARN -> R.string.delivery_state_warn
            AlarmReadiness.State.INFO -> R.string.delivery_state_info
        }
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = "$title, $stateLabel, $detail" },
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == AlarmReadiness.State.WARN) {
                    AppColors.warningText
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Icon(
            painterResource(
                when (item.state) {
                    AlarmReadiness.State.OK -> R.drawable.ic_check
                    AlarmReadiness.State.WARN -> R.drawable.ic_warning
                    AlarmReadiness.State.INFO -> R.drawable.ic_info
                }
            ),
            contentDescription = null,
            tint = when (item.state) {
                AlarmReadiness.State.OK -> AppColors.positive
                AlarmReadiness.State.WARN -> AppColors.warning
                AlarmReadiness.State.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = Spacing.md).size(22.dp),
        )
    }
    actionLabel(item.action)?.let { label ->
        TextButton(
            onClick = { onAction(item.action) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = Modifier.padding(start = 0.dp, bottom = 4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
