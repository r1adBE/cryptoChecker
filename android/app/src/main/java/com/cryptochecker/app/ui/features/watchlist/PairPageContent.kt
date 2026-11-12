// Inhalt der Paarseite von oben nach unten, gleich wie iOS: Kopf, Chart, «Alarm setzen», kleine Knöpfe, Alarme des
// Paars mit «Zuletzt geprüft», Marktdaten und die Notiz. Eigene Datei, damit `PairScreen` Zustand und Dialoge hält.
package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.ReadableInset
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.features.alarms.AlarmEditorHostState
import com.cryptochecker.app.ui.features.alarms.AlarmsViewModel
import com.cryptochecker.app.ui.features.alarms.PairAlarmLimits
import com.cryptochecker.app.ui.features.alarms.PairAlarmsSection
import com.cryptochecker.app.ui.theme.Spacing

/** Was der Inhalt der Paarseite auslöst (Dialoge und Schreibwege hält `PairScreen`). */
internal class PairPageCallbacks(
    val onRetry: () -> Unit,
    val onToggleDisabled: () -> Unit,
    val onAdd: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onWhy: () -> Unit,
    val onChangeExchange: () -> Unit,
    val onNotificationChange: (Boolean) -> Unit,
    val onEditNote: () -> Unit,
)

/**
 * 1. Kopf ([PairHeader]) samt Zustand, 2. Chart über die ganze Breite ([SheetPriceChart], nicht bei [off]),
 * 3. «Alarm setzen» (Vorschau: «Zur Merkliste hinzufügen»), 4. kleine Knöpfe ([PairSecondaryActions]), 5. Alarme des
 * Paars ([PairAlarmsSection]), 6. Marktdaten ([MarketDataSection], Krypto-Paare), zuletzt die Notiz (Tippen bearbeitet).
 * Vorschau: nur Kopf, Chart, Hinzufügen, «Was bewegt sich mit?» und Marktdaten.
 */
@Composable
internal fun PairPageContent(
    watch: WatchEntity,
    isPreview: Boolean,
    off: Boolean,
    now: Long,
    outdatedAfter: Long,
    viewModel: WatchlistViewModel,
    chartLine: Boolean,
    alarms: AlarmsViewModel,
    alarmHost: AlarmEditorHostState,
    limits: PairAlarmLimits,
    hasActivity: Boolean,
    adding: Boolean,
    callbacks: PairPageCallbacks,
    padding: PaddingValues,
) {
    val motion = !rememberReduceMotion()
    // Tablet/Querformat: Inhalt höchstens 640 dp breit
    ReadableInset { inset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.lg + inset, end = Spacing.lg + inset, top = Spacing.sm, bottom = Spacing.xl)
        ) {
            PairHeader(
                watch = watch,
                preview = isPreview,
                off = off,
                now = now,
                outdatedAfter = outdatedAfter,
                candleSource = if (isPreview) null else viewModel.foreignCandleSource(watch),
                // Herkunft von Änderung/Mini-Chart steht nur hier, nicht in der Zeile
                rowSource = if (isPreview) null else viewModel.rowSource(watch),
                satsRate = viewModel::satsRate,
            )
            PairStatusLine(watch, isPreview, off, onRetry = callbacks.onRetry, onEnable = callbacks.onToggleDisabled)
            Spacer(Modifier.height(Spacing.lg))
            // Kurs-Chart (24h · 7T · 30T · 1J, Kerzen/Linie); ohne Kerzenquelle (DEX) bzw. deaktiviert ausgeblendet,
            // ebenso bei stillgelegter Börse (ExchangeRetirement: keine Kerzen mehr laden)
            if (!off && !com.cryptochecker.marketdata.config.ExchangeRetirement.isMarker(watch.lastError)) {
                SheetPriceChart(
                    watch = watch,
                    line = chartLine,
                    onLineChange = viewModel::setSheetChartLine,
                    cached = viewModel::cachedSheetChart,
                    load = viewModel::loadSheetChart,
                    modifier = Modifier.padding(bottom = Spacing.lg)
                )
            }
            if (isPreview) {
                // Alarm, Favorit und alles Weitere brauchen einen gespeicherten Eintrag
                PairPrimaryButton(
                    icon = if (adding) R.drawable.ic_check else R.drawable.ic_add,
                    text = stringResource(if (adding) R.string.explorer_added_to_watchlist else R.string.explorer_add_to_watchlist),
                    onClick = callbacks.onAdd,
                    enabled = !adding,
                )
                if (!watch.isNotTraded) {
                    AssistChip(
                        onClick = callbacks.onWhy,
                        label = { Text(stringResource(R.string.watch_action_why)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_lightbulb), null, Modifier.size(18.dp)) },
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            } else {
                PairAlarmButton(enabled = limits.canAdd, onClick = alarmHost::openNew)
                Spacer(Modifier.height(Spacing.sm))
                PairSecondaryActions(
                    watch = watch,
                    // Nicht mehr gehandelt bzw. deaktiviert: kein «Was bewegt sich mit?» (es gäbe nur alte Daten)
                    showWhy = !watch.isNotTraded && !off,
                    hasActivity = hasActivity,
                    onToggleFavorite = callbacks.onToggleFavorite,
                    onWhy = callbacks.onWhy,
                    onChangeExchange = callbacks.onChangeExchange,
                    onNotificationChange = callbacks.onNotificationChange,
                )
                Spacer(Modifier.height(Spacing.xl))
                PairAlarmsSection(viewModel = alarms, host = alarmHost, watch = watch, limits = limits)
            }
            // Karte «Marktdaten» (Spot | Futures) für Krypto-Paare; nicht bei deaktivierten Paaren und DEX-Pools.
            // Kennzahlen kommen später: weich aufziehen statt springen (nicht bei reduzierter Bewegung)
            if (!off && CoinLogos.allowedFor(watch.exchangeKey)) {
                Spacer(Modifier.height(Spacing.xl))
                Box(modifier = if (motion) Modifier.animateContentSize() else Modifier) {
                    MarketDataSection(watch = watch, loadFutures = viewModel::fetchFutures, loadSpot = viewModel::fetchSpot)
                }
            }
            watch.note?.takeIf { !isPreview && it.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = Spacing.lg)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = callbacks.onEditNote)
                        .padding(Spacing.md)
                )
            }
        }
    }
}
