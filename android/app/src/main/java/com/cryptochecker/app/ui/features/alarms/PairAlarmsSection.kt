// Alarme eines Paars als Abschnitt der Paarseite (`PairScreen`): dieselben Karten, Hinweise und «Zuletzt geprüft» wie
// die Seite «Alarme» ([AlarmsScreen]), aber als Spalte in der scrollenden Seite statt als eigene Liste.
// Eigene Datei, weil die Statuszeile der Karten (Mitteilungen, Uhr) hier wie dort gerechnet wird.
package com.cryptochecker.app.ui.features.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.alarm.AlarmCardStatus
import com.cryptochecker.app.domain.watch.WatchlistLimits
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.util.ExchangesConfigUtils
import kotlinx.coroutines.delay

/** Was die Paarseite über neue Alarme wissen muss: gesperrt (Referenzkurs, DEX zu dünn) bzw. Höchstzahl erreicht. */
internal data class PairAlarmLimits(val referenceRate: Boolean, val lowLiquidity: Boolean, val full: Boolean) {
    /** «Alarm setzen» bedienbar. */
    val canAdd: Boolean get() = !referenceRate && !lowLiquidity && !full
}

/** [PairAlarmLimits] des Paars von [viewModel]; [watch] = gezeigtes Paar (Referenzkurs je Börse). */
@Composable
internal fun rememberPairAlarmLimits(viewModel: AlarmsViewModel, watch: WatchEntity?): PairAlarmLimits {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val lowLiquidity by viewModel.lowLiquidity.collectAsStateWithLifecycle()
    val referenceRate = watch?.let { !ExchangesConfigUtils.getExchangeByKey(it.exchangeKey).allowsAlarms } == true
    return PairAlarmLimits(
        referenceRate = referenceRate,
        lowLiquidity = lowLiquidity == true,
        full = !WatchlistLimits.canAddAlarm(alarms.size),
    )
}

/**
 * Abschnitt «Alarme» der Paarseite: Überschrift, Hinweise (Mitteilungen aus, Referenzkurs, DEX-Pool zu dünn,
 * Höchstzahl), «Zuletzt geprüft 14:32» und die Karten des Paars. Antippen bearbeitet, der Schalter macht scharf,
 * Löschen fragt nach ([AlarmEditorHost] über [host]). Ohne Alarm ein kurzer Satz statt der Karten.
 */
@Composable
internal fun PairAlarmsSection(
    viewModel: AlarmsViewModel,
    host: AlarmEditorHostState,
    watch: WatchEntity,
    limits: PairAlarmLimits,
    modifier: Modifier = Modifier,
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val unchecked by viewModel.unchecked.collectAsStateWithLifecycle()
    val staleAfter by viewModel.staleAfterMillis.collectAsStateWithLifecycle()
    val markFallback by viewModel.markFallback.collectAsStateWithLifecycle()
    val notificationsEnabled = com.cryptochecker.app.ui.components.rememberNotificationsEnabled()
    // Statuszeile der Karten wie in «Alarme»: Mitteilungen bei jeder Rückkehr neu prüfen, die Uhr jede Minute
    var alarmsAllowed by remember { mutableStateOf(viewModel.alarmsAllowed()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(notificationsEnabled) {
        alarmsAllowed = viewModel.alarmsAllowed()
        onPauseOrDispose { }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STATUS_TICK_MILLIS)
            now = System.currentTimeMillis()
        }
    }
    val blocked = limits.referenceRate || limits.lowLiquidity
    Column(modifier = modifier.fillMaxWidth().testTag(UiTestTags.PAIR_ALARMS), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.alarms_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (alarms.isNotEmpty() && !notificationsEnabled) {
            com.cryptochecker.app.ui.components.NotificationsOffBanner()
        }
        when {
            limits.referenceRate -> ReferenceRateNote(watch.exchangeKey)
            limits.lowLiquidity -> DexLowLiquidityNote()
            limits.full -> AlarmLimitNote()
        }
        if (alarms.isEmpty()) {
            if (!blocked) {
                Text(
                    stringResource(R.string.alarms_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        // «Zuletzt geprüft 14:32» wie in «Alarme», Übersicht, Editor und iOS
        AlarmLastCheckedLine()
        alarms.forEach { alarm ->
            androidx.compose.runtime.key(alarm.id) {
                AlarmCard(
                    alarm = alarm,
                    baseAsset = watch.baseAsset,
                    quoteAsset = watch.quoteAsset,
                    unchecked = unchecked[alarm.id],
                    status = AlarmCardStatus.of(
                        alarmEnabled = alarm.enabled,
                        notificationsAllowed = alarmsAllowed,
                        dexBlocksAlarms = limits.lowLiquidity,
                        exchangeTime = watch.exchangeTime,
                        lastUpdate = watch.lastUpdate,
                        now = now,
                        staleAfterMillis = staleAfter,
                        unchecked = unchecked[alarm.id] != null,
                        markFallback = markFallback,
                    ),
                    onToggle = { viewModel.setEnabled(alarm.id, it) },
                    onEdit = { if (!blocked) host.edit(alarm) },
                    onDelete = { host.askDelete = alarm },
                )
            }
        }
    }
}

/** Takt, in dem die Statuszeilen der Karten die Uhr nachführen («Wartet auf aktuellen Kurs»). */
private const val STATUS_TICK_MILLIS = 60_000L
