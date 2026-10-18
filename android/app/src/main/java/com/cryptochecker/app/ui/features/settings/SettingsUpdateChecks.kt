package com.cryptochecker.app.ui.features.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.BackgroundSchedule
import com.cryptochecker.app.domain.refresh.CheckResult
import com.cryptochecker.app.domain.refresh.CheckRun
import com.cryptochecker.app.domain.refresh.CheckTrigger
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.util.BatteryOptimization
import com.cryptochecker.app.work.ExactCheckScheduler

// «Aktualisierung» (Build 46): Pünktlich prüfen, Akku-Hinweis, Letzte Prüfungen — wie iOS
// `SettingsUpdateChecks.swift` (dort ohne Wecker und Akku: iOS entscheidet allein).

/**
 * Schalter «Pünktlich prüfen» (Standard aus): exakter Wecker alle 15 Min. Fehlt «Wecker und
 * Erinnerungen», steht «Berechtigung fehlt» mit Knopf zur Android-Einstellung; WorkManager prüft dann allein.
 */
@Composable
internal fun ExactChecksRow(enabled: Boolean, permitted: Boolean, hasWork: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val state = BackgroundSchedule.exactState(enabled, permitted, hasWork)
    SwitchRow(
        title = stringResource(R.string.settings_exact_checks),
        subtitle = stringResource(
            when (state) {
                BackgroundSchedule.ExactState.PERMISSION_MISSING -> R.string.settings_exact_checks_permission_missing
                BackgroundSchedule.ExactState.NOTHING_TO_CHECK -> R.string.settings_exact_checks_nothing
                BackgroundSchedule.ExactState.OFF, BackgroundSchedule.ExactState.ACTIVE -> R.string.settings_exact_checks_hint
            }
        ),
        checked = enabled,
        onCheckedChange = onToggle,
    )
    if (state == BackgroundSchedule.ExactState.PERMISSION_MISSING) {
        FilledTonalButton(
            onClick = { ExactCheckScheduler.openPermissionSettings(context) },
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Text(stringResource(R.string.settings_exact_checks_grant))
        }
    }
}

/**
 * «Android kann die Prüfung verzögern – Akku auf ‹Nicht eingeschränkt› stellen» mit Knopf: nur bei
 * eingeschränktem Akku und wenn im Hintergrund überhaupt geprüft wird. Auch unter dem Alarm-Hinweis.
 */
@Composable
internal fun BatteryDelayHint(batteryRestricted: Boolean, hasWork: Boolean, modifier: Modifier = Modifier) {
    if (!BackgroundSchedule.showBatteryHint(batteryRestricted, hasWork)) return
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = false)
            Text(
                stringResource(R.string.battery_delay_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm)
            )
        }
        TextButton(
            onClick = { BatteryOptimization.openSettings(context) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(stringResource(R.string.battery_open_settings), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** «Letzte Prüfungen»: ehrlicher Satz, dann je Lauf Zeit, Auslöser, Ergebnis und Dauer (neueste zuerst). */
@Composable
internal fun CheckRunsCard(runs: List<CheckRun>) {
    GroupCard(title = stringResource(R.string.settings_check_runs_title)) {
        Hint(stringResource(R.string.settings_check_runs_honest_android), top = Spacing.xs)
        if (runs.isEmpty()) {
            Hint(stringResource(R.string.settings_check_runs_empty))
        }
        runs.forEach { run -> CheckRunRow(run) }
        // Daneben das Alarm-Protokoll: nicht nur wann, sondern was die Prüfung bei jedem Alarm sah
        com.cryptochecker.app.ui.features.alarms.LocalOpenAlarmLog.current?.let { openLog ->
            TextButton(
                onClick = openLog,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.alarm_log_open), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CheckRunRow(run: CheckRun) {
    val context = LocalContext.current
    // Heute nur die Uhrzeit, sonst mit Datum
    val flags = if (DateUtils.isToday(run.at)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    val time = DateUtils.formatDateTime(context, run.at, flags)
    val trigger = stringResource(run.trigger.labelRes)
    val result = stringResource(run.result.labelRes)
    val duration = if (run.result == CheckResult.SKIPPED) ""
    else stringResource(R.string.settings_seconds, ((run.durationMillis + 500) / 1000).toInt())
    val ok = run.result == CheckResult.OK || run.result == CheckResult.SKIPPED
    val description = listOf(time, trigger, result, duration).filter { it.isNotEmpty() }.joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        Text(time, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            trigger,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            listOf(result, duration).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else AppColors.warningText,
        )
    }
}

private val CheckTrigger.labelRes: Int
    get() = when (this) {
        CheckTrigger.WORKER -> R.string.check_trigger_worker
        CheckTrigger.EXACT -> R.string.check_trigger_exact
        CheckTrigger.BG_TASK -> R.string.check_trigger_bgtask
        CheckTrigger.WIDGET -> R.string.check_trigger_widget
        CheckTrigger.FOREGROUND -> R.string.check_trigger_foreground
    }

private val CheckResult.labelRes: Int
    get() = when (this) {
        CheckResult.OK -> R.string.check_result_ok
        CheckResult.PARTIAL -> R.string.check_result_partial
        CheckResult.FAILED -> R.string.check_result_failed
        CheckResult.SKIPPED -> R.string.check_result_skipped
    }
