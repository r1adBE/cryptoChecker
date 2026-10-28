// Dialoge der Alarme eines Paars: Bestätigung nach dem allerersten Alarm, «Alarm löschen?», «Alarm löst sofort aus»,
// dazu der Hinweis für DEX-Pools mit zu wenig Liquidität und das Beispiel im leeren Zustand.
// Eigene Datei, weil `AlarmsScreen` sonst zu lang würde; Zustand und Aktionen bleiben dort, hier wird nur gezeichnet.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.ui.components.DialogText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.ui.components.HapticEvent
import com.cryptochecker.app.ui.components.rememberHaptics

/**
 * EIN Dialog nach dem allerersten Alarm statt mehrerer nacheinander: Bestätigung mit «Alarm testen», dazu optional
 * «Pünktlich prüfen» und, nur wenn die Akku-Optimierung die App einschränkt, die Akku-Einstellungen.
 * Hauptknopf «Prüfen, ob Alarme ankommen» öffnet gleich «Damit Alarme ankommen» ([LocalOpenAlarmDelivery]):
 * ob Alarme wirklich ankommen, soll man nach dem ersten Alarm sehen, nicht erst suchen müssen. Wie iOS.
 */
@Composable
internal fun FirstAlarmDialog(
    viewModel: AlarmsViewModel,
    exactOffer: com.cryptochecker.app.work.ExactCheckOffer.Kind?,
    testAlarm: () -> Unit,
) {
    val context = LocalContext.current
    val batteryRestricted = remember { !com.cryptochecker.app.util.BatteryOptimization.isIgnoring(context) }
    var batteryDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        com.cryptochecker.app.ui.features.about.BatteryHintSession.coveredByFirstAlarm = true
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissFirstAlarm,
        title = { Text(stringResource(R.string.alarm_first_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Derselbe eine Satz wie unter jedem Alarm (AlarmTimingHint, höchstens 160 Zeichen)
                Text(alarmTimingHintText())
                exactOffer?.let { kind ->
                    val grant = kind == com.cryptochecker.app.work.ExactCheckOffer.Kind.GRANT
                    Text(
                        stringResource(R.string.settings_exact_checks_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = {
                        viewModel.acceptExactOffer()
                        if (grant) com.cryptochecker.app.work.ExactCheckScheduler.openPermissionSettings(context)
                    }) {
                        Text(stringResource(if (grant) R.string.settings_exact_checks_grant else R.string.settings_exact_checks))
                    }
                }
                if (batteryRestricted && !batteryDone) {
                    Text(
                        stringResource(R.string.alarm_first_battery_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = {
                        com.cryptochecker.app.util.BatteryOptimization.openSettings(context)
                        viewModel.batteryHintUsed()
                        batteryDone = true
                    }) {
                        Text(stringResource(R.string.battery_open_settings))
                    }
                }
            }
        },
        confirmButton = {
            // Ohne Navigation (Vorschau): nur «OK»
            val openDelivery = LocalOpenAlarmDelivery.current
            if (openDelivery != null) {
                TextButton(onClick = {
                    viewModel.dismissFirstAlarm()
                    openDelivery()
                }) { Text(stringResource(R.string.alarm_first_check)) }
            } else {
                TextButton(onClick = viewModel::dismissFirstAlarm) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = {
            // Mehrere Knöpfe in diesem Platz: der Dialog reiht sie (wie iOS: OK, Alarm testen, Prüfen)
            if (LocalOpenAlarmDelivery.current != null) {
                TextButton(onClick = viewModel::dismissFirstAlarm) { Text(stringResource(android.R.string.ok)) }
            }
            TextButton(onClick = {
                viewModel.dismissFirstAlarm()
                testAlarm()
            }) { Text(stringResource(R.string.alarm_test)) }
        }
    )
}

/** Rückfrage «Alarm löschen?». */
@Composable
internal fun DeleteAlarmDialog(onDelete: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_delete_title)) },
        text = { Text(stringResource(R.string.alarm_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = { haptics.play(HapticEvent.DESTRUCTIVE); onDelete() }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Rückfrage «Alarm löst sofort aus»: Kursmarke von [pending] schon erreicht. [quoteAsset] gilt, wenn der Entwurf keine
 * eigene Währung hat; «Ändern» führt zurück in den Editor.
 */
@Composable
internal fun FiresNowDialog(pending: AlarmDraft, quoteAsset: String, onSave: () -> Unit, onDismiss: () -> Unit) {
    val above = pending.condition == AlarmCondition.PRICE_ABOVE
    val currency = pending.currency ?: quoteAsset
    val amount = PriceFormat.thresholdWithCurrency(pending.threshold, currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alarm_fires_now_title)) },
        text = {
            DialogText {
                val message = if (above) R.string.alarm_fires_now_above else R.string.alarm_fires_now_below
                Text(stringResource(message, amount))
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.alarm_fires_now_save)) }
        },
        dismissButton = {
            // Zurück in den Editor, Betrag anpassen
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.alarm_fires_now_change)) }
        }
    )
}

/**
 * Beispiel im leeren Alarm-Zustand (Übersicht und je Paar) im Stil der Alarm-Sätze:
 * «Zum Beispiel: Sag mir Bescheid, wenn BTC über 90’000 steigt.» — Zahl im Format der Sprache.
 * Wie iOS `AlarmsEmptyExample.text`.
 */
@Composable
internal fun alarmsEmptyExample(): String =
    stringResource(R.string.alarms_empty_example, LocaleNumbers.integer(90_000, grouping = true))

/** Hinweis für DEX-Pools mit zu wenig Liquidität: kein Alarm, bestehende Alarme ruhen ([com.cryptochecker.app.domain.watch.DexLiquidity]). */
@Composable
internal fun DexLowLiquidityNote() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.dex_low_liquidity_no_alarm),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.dex_low_liquidity_alarm_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** EZB-Referenzkurs (Forex, [com.cryptochecker.marketdata.model.Exchange.allowsAlarms] false): keine Alarme. */
@Composable
internal fun ReferenceRateNote() {
    Text(
        stringResource(R.string.alarms_reference_rate_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** «Ein Paar kann höchstens 10 Alarme haben.» (Zahl im Format der App-Sprache, [WatchlistLimits]). */
@Composable
internal fun alarmLimitText(): String =
    stringResource(R.string.alarms_limit_reached, LocaleNumbers.integer(WatchlistLimits.MAX_ALARMS_PER_PAIR))

/** Paar hat schon die Höchstzahl Alarme: statt «Neuer Alarm» dieser Hinweis über der Liste. */
@Composable
internal fun AlarmLimitNote() {
    Text(
        alarmLimitText(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
