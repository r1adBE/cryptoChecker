package com.cryptochecker.app.ui.features.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cryptochecker.app.R
import com.cryptochecker.app.data.settings.SettingsRepository
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.settings.AppSettings
import com.cryptochecker.app.domain.util.IoDispatcher
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BatteryOptimization
import com.cryptochecker.app.wearable.PhoneWatchUsage
import com.cryptochecker.app.work.ExactCheckScheduler
import com.cryptochecker.app.work.PriceUpdateScheduler
import com.cryptochecker.marketdata.usage.UsageDriver
import com.cryptochecker.marketdata.usage.UsageLevel
import com.cryptochecker.marketdata.usage.UsageLog
import com.cryptochecker.marketdata.usage.UsageMeter
import com.cryptochecker.marketdata.usage.UsageReport
import com.cryptochecker.marketdata.usage.UsageTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * «Akku & Aktivität»: eigene Zähler der letzten 24 h ([UsageMeter.shared]) und die der Uhr ([PhoneWatchUsage]), dazu
 * ob die Hintergrund-Prüfung geplant ist und «Pünktlich prüfen» wirkt (Satz «Am meisten zählt …», [UsageLog.driver]).
 */
@HiltViewModel
class UsageViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    scheduler: PriceUpdateScheduler,
    exactChecks: ExactCheckControl,
    private val watchUsage: PhoneWatchUsage,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /** Periodische Hintergrund-Prüfung geplant (WorkManager)? */
    val periodicPlanned: StateFlow<Boolean> = scheduler.observePeriodicPlanned()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Etwas zu prüfen (aktive Paare, Portfolio-, Gas- oder Depeg-Alarm) — Bedingung für «Pünktlich prüfen». */
    val hasWork: StateFlow<Boolean> = exactChecks.hasWork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _phone = MutableStateFlow(UsageTotals(0, 0, 0))
    val phone: StateFlow<UsageTotals> = _phone.asStateFlow()

    private val _watch = MutableStateFlow<UsageReport?>(null)
    val watch: StateFlow<UsageReport?> = _watch.asStateFlow()

    /** Zahlen neu lesen (beim Öffnen und nach der Rückkehr aus den Android-Einstellungen). */
    fun refresh() {
        viewModelScope.launch {
            _phone.value = withContext(ioDispatcher) { UsageMeter.shared.totals() }
            _watch.value = withContext(ioDispatcher) { watchUsage.load() }
            _watch.value = watchUsage.pull()
        }
    }
}

/**
 * Seite «Akku & Aktivität»: ehrlicher Satz (Apps sehen ihren Akkuverbrauch nicht), je eine Karte «Handy» und «Uhr»
 * ([UsageCard]; ohne Bericht der Uhr «Keine Uhr verbunden»), dann der Weg in die Akku-Einstellungen von Android
 * ([BatteryOptimization.openUsageSummary]). Wie iOS `UsageSettingsPage`.
 */
@Composable
internal fun UsagePage(onBack: () -> Unit, viewModel: UsageViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val planned by viewModel.periodicPlanned.collectAsStateWithLifecycle()
    val hasWork by viewModel.hasWork.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var exactPermitted by remember { mutableStateOf(ExactCheckScheduler.canScheduleExact(context)) }
    LifecycleResumeEffect(Unit) {
        exactPermitted = ExactCheckScheduler.canScheduleExact(context)
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val exactActive = BackgroundSchedule.exactState(settings.exactChecks, exactPermitted, hasWork) ==
        BackgroundSchedule.ExactState.ACTIVE
    val interval = stringResource(R.string.settings_minutes, settings.backgroundIntervalMinutes)
    val driver = when (UsageLog.driver(backgroundPlanned = planned || exactActive, exactActive = exactActive)) {
        UsageDriver.NONE -> stringResource(R.string.usage_driver_none)
        UsageDriver.INTERVAL -> stringResource(R.string.usage_driver_interval, interval)
        UsageDriver.INTERVAL_EXACT ->
            stringResource(R.string.usage_driver_exact, interval, stringResource(R.string.settings_exact_checks))
    }
    SettingsSubPage(title = stringResource(R.string.settings_row_usage), onBack = onBack) {
        Hint(stringResource(R.string.usage_intro), top = Spacing.xs)
        SettingsAnchor("usage.phone") {
            UsageCard(stringResource(R.string.usage_phone), phone, driver, asOf = null)
        }
        Spacer(Modifier.height(12.dp))
        SettingsAnchor("usage.watch") {
            val report = watch
            if (report == null) {
                GroupCard(title = stringResource(R.string.usage_watch)) {
                    Hint(stringResource(R.string.usage_watch_none))
                }
            } else {
                UsageCard(stringResource(R.string.usage_watch), report.totals, stringResource(R.string.usage_driver_watch),
                    asOf = report.at)
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsAnchor("usage.battery") {
            GroupCard {
                Hint(stringResource(R.string.usage_battery_android), top = Spacing.sm)
                FilledTonalButton(
                    onClick = { BatteryOptimization.openUsageSummary(context) },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(stringResource(R.string.battery_open_settings))
                }
            }
        }
    }
}

/** Eine Karte: Einstufung mit Punkt, die drei Zahlen der letzten 24 h, der Treiber und bei der Uhr der Stand. */
@Composable
private fun UsageCard(title: String, totals: UsageTotals, driver: String, asOf: Long?) {
    val context = LocalContext.current
    val level = UsageLog.level(totals)
    GroupCard(title = title) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.xs)) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(level.color)
            )
            Text(
                stringResource(level.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
        Text(pluralStringResource(R.plurals.usage_runs, totals.runs, totals.runs), style = MaterialTheme.typography.bodyMedium)
        Text(
            pluralStringResource(R.plurals.usage_requests, totals.requests, totals.requests),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            stringResource(R.string.usage_received, Formatter.formatShortFileSize(context, totals.bytes)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = Spacing.xs)
        )
        Hint(driver)
        if (asOf != null) {
            val flags = if (DateUtils.isToday(asOf)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            Hint(stringResource(R.string.usage_as_of, DateUtils.formatDateTime(context, asOf, flags)))
        }
    }
}

private val UsageLevel.labelRes: Int
    get() = when (this) {
        UsageLevel.LOW -> R.string.usage_level_low
        UsageLevel.MEDIUM -> R.string.usage_level_medium
        UsageLevel.HIGH -> R.string.usage_level_high
    }

private val UsageLevel.color: androidx.compose.ui.graphics.Color
    @Composable get() = when (this) {
        UsageLevel.LOW -> AppColors.positive
        UsageLevel.MEDIUM -> AppColors.warning
        UsageLevel.HIGH -> AppColors.negative
    }
