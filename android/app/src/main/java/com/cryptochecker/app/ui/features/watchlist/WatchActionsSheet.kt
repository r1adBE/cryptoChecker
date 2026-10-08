@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.domain.convert.Sats
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.components.NoteDialog
import com.cryptochecker.app.ui.components.SwitchRow
import com.cryptochecker.app.ui.components.WidgetManualDialog
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.ui.theme.display
import com.cryptochecker.app.ui.theme.headline
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.WidgetKind
import com.cryptochecker.app.widget.WidgetPinner
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * «Mehr» im Aktionsblatt: auf- oder zugeklappt, für die Dauer der Sitzung gemerkt (Prozess),
 * Standard zu.
 */
private object SheetMoreState {
    @Volatile
    var expanded: Boolean = false
}

/**
 * Aktionen zu einem Paar: oben Kopf mit Stift, Kurs und Chart ([SheetPriceChart]), dann gross
 * Alarm, Warum? und Favorit (Notiz und Futures-Kennzahlen, falls vorhanden). Alles Weitere —
 * Gruppe, Notiz, Portfolio, Aktualisieren, Widget, Vorlesen, Meldung und zuletzt Löschen — im
 * aufklappbaren Abschnitt «Mehr» (zu; Zustand für die Sitzung gemerkt). Das Blatt steht immer
 * in voller Höhe, Aufklappen ändert nur den Inhalt der Liste.
 */
@Composable
internal fun WatchActionsSheet(
    watch: WatchEntity,
    alarmCount: Int,
    onDismiss: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onTtsChange: (Boolean) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAlarms: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    loadFutures: suspend (WatchEntity) -> FuturesInfo? = { null },
    groups: List<String> = emptyList(),
    /** null = Portfolio ausgeschaltet, Eintrag verborgen. */
    onAddToPortfolio: (() -> Unit)? = null,
    onSetGroup: (String?) -> Unit = {},
    onWhy: () -> Unit = {},
    onSetNote: (String?) -> Unit = {},
    /** Chart als Linie statt Kerzen (zuletzt gewählt, für alle Paare gleich). */
    chartLine: Boolean = false,
    onChartLineChange: (Boolean) -> Unit = {},
    cachedChart: (WatchEntity, SheetChartRange) -> SheetChartResult? = { _, _ -> SheetChartResult.Unsupported },
    loadChart: suspend (WatchEntity, SheetChartRange) -> SheetChartResult = { _, _ -> SheetChartResult.Unsupported },
    /** Bitcoin-Paare: Umrechnungswährung und Faktor Quote → sie für «1 CHF = … Sats». */
    satsRate: suspend (String) -> Pair<String, Double>? = { null },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetContext = LocalContext.current
    val sheetMotion = !rememberReduceMotion()
    var showWidgetManual by remember { mutableStateOf(false) }
    if (showWidgetManual) WidgetManualDialog(onDismiss = { showWidgetManual = false })
    var editGroup by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(false) }
    // «Paar bearbeiten» (Stift neben dem Paar): Börse, Paar, Kontrakt desselben Eintrags
    var editPair by rememberSaveable(watch.id) { mutableStateOf(false) }
    if (editPair) {
        WatchEditSheet(watch = watch, alarmCount = alarmCount, onDismiss = { editPair = false })
    }
    if (editNote) {
        NoteDialog(
            title = stringResource(R.string.note_title),
            initial = watch.note,
            onSave = { onSetNote(it); editNote = false },
            onDismiss = { editNote = false }
        )
    }
    if (editGroup) {
        GroupDialog(
            current = watch.groupName,
            groups = groups,
            onSelect = { onSetGroup(it); editGroup = false },
            onDismiss = { editGroup = false }
        )
    }
    // Futures-Kennzahlen nur für Perpetuals laden (neu, wenn das Paar bearbeitet wurde)
    val futures by produceState<FuturesInfo?>(initialValue = null, watch.id, watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType) {
        value = if (watch.contractType == FuturesContractType.PERPETUAL) loadFutures(watch) else null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: ändert sich der Inhalt (Laden, Auswahl), springt das Blatt nicht
                .fillMaxHeight()
                // Mit dem Chart wird das Blatt auf kleinen Geräten höher als der Bildschirm
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            // Kopf: Paar, Börse, Kurs gross; rechts der Stift «Paar bearbeiten»
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(
                    watch.baseAsset,
                    size = 32.dp,
                    logo = CoinLogos.allowedFor(watch.marketKey),
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        watch.displayName,
                        style = MaterialTheme.typography.headline,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        watch.marketName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // IconButton: 48 dp Tippfläche
                IconButton(onClick = { editPair = true }) {
                    Icon(
                        painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.watch_edit_title),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    PriceFormat.priceWithCurrency(watch.lastPrice, watch.quoteAsset),
                    style = MaterialTheme.typography.display.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold
                )
                Box(modifier = Modifier.padding(start = 12.dp)) {
                    if (watch.lastPrice != null) DayChangePill(change = watch.shownChange(LocalChangeView.current))
                }
            }
            // Bitcoin: «1 CHF = 1’234 Sats» in der Umrechnungswährung (Kurs mit dem bestehenden Faktor)
            val bitcoin = Sats.isBitcoin(watch.baseAsset) && !watch.isNotTraded
            val satsFactor by produceState<Pair<String, Double>?>(null, watch.quoteAsset, bitcoin) {
                value = if (bitcoin) satsRate(watch.quoteAsset) else null
            }
            satsFactor?.let { (currency, rate) ->
                Sats.perUnit(watch.lastPrice, rate)?.let { sats ->
                    Text(
                        stringResource(
                            R.string.sats_per_unit,
                            LocaleNumbers.integer(1),
                            currency,
                            LocaleNumbers.decimal(sats, maxDecimals = Sats.decimals(sats), grouping = true),
                        ),
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            // Börse nicht erreichbar, aber ein älterer Kurs steht: ruhiger Satz plus «Erneut versuchen»
            val retryable = watch.lastPrice != null && isRetryableMarketError(watch.lastError)
            if (retryable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.error_market_unreachable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.try_again)) }
                }
            } else {
                Text(
                    text = watch.lastError?.let { friendlyError(it) }
                        ?: stringResource(R.string.watchlist_updated, PriceFormat.time(watch.lastUpdate)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (watch.lastError != null && !isNotTraded(watch.lastError)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }

            // Kurs-Chart (24h · 7T · 30T · 1J, Kerzen/Linie); ohne Kerzenquelle (DEX) ganz ausgeblendet
            SheetPriceChart(
                watch = watch,
                line = chartLine,
                onLineChange = onChartLineChange,
                cached = cachedChart,
                load = loadChart,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Die drei häufigsten Aktionen zuerst und gleich gross: Alarm, Warum?, Favorit
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // Gleich hoch, auch wenn ein Text umbricht
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 12.dp)
            ) {
                PrimarySheetAction(
                    icon = R.drawable.ic_notifications,
                    text = if (alarmCount > 0) pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount)
                    else stringResource(R.string.watch_action_alarm),
                    onClick = onOpenAlarms,
                    modifier = Modifier.weight(1f)
                )
                // Nicht mehr gehandelt: kein «Warum?» (es gäbe nur alte Daten)
                if (!watch.isNotTraded) {
                    PrimarySheetAction(
                        icon = R.drawable.ic_lightbulb,
                        text = stringResource(R.string.watch_action_why),
                        onClick = onWhy,
                        modifier = Modifier.weight(1f)
                    )
                }
                PrimarySheetAction(
                    icon = if (watch.favorite) R.drawable.ic_star else R.drawable.ic_star_outline,
                    text = stringResource(R.string.watch_action_favorite),
                    onClick = onToggleFavorite,
                    checked = watch.favorite,
                    modifier = Modifier.weight(1f)
                )
            }

            watch.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { editNote = true }
                        .padding(12.dp)
                )
            }

            // Kennzahlen kommen später: weich aufziehen statt springen (nicht bei reduzierter Bewegung)
            Box(modifier = if (sheetMotion) Modifier.animateContentSize() else Modifier) {
                futures?.let { FuturesSection(it) }
            }

            // Alles Weitere unter «Mehr» (zu, für die Sitzung gemerkt); weich auf- und zuklappen
            var moreExpanded by remember { mutableStateOf(SheetMoreState.expanded) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SheetMoreToggle(
                expanded = moreExpanded,
                onToggle = {
                    moreExpanded = !moreExpanded
                    SheetMoreState.expanded = moreExpanded
                }
            )
            Column(modifier = if (sheetMotion) Modifier.animateContentSize() else Modifier) {
            if (moreExpanded) {
            SheetAction(
                icon = R.drawable.ic_list,
                text = watch.groupName?.let { stringResource(R.string.group_value, it) }
                    ?: stringResource(R.string.group_title),
                onClick = { editGroup = true }
            )
            SheetAction(
                icon = R.drawable.ic_note,
                text = stringResource(if (watch.note.isNullOrBlank()) R.string.note_add else R.string.note_edit),
                onClick = { editNote = true }
            )
            onAddToPortfolio?.let { addToPortfolio ->
                SheetAction(
                    icon = R.drawable.ic_portfolio,
                    text = stringResource(R.string.portfolio_add_from_watch),
                    onClick = addToPortfolio
                )
            }
            SheetAction(
                icon = R.drawable.ic_refresh,
                text = stringResource(R.string.action_refresh),
                onClick = onRefresh
            )
            // Einzel-Widget mit diesem Paar auf den Startbildschirm (Runde 13b); kann der
            // Startbildschirm das nicht, eine kurze Anleitung
            SheetAction(
                icon = R.drawable.ic_widgets,
                text = stringResource(R.string.watch_action_add_widget),
                onClick = {
                    if (!WidgetPinner.request(sheetContext, WidgetKind.SINGLE, watch.id)) showWidgetManual = true
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SwitchRow(
                title = stringResource(R.string.watchlist_tts),
                checked = watch.ttsEnabled,
                onCheckedChange = onTtsChange
            )
            SwitchRow(
                title = stringResource(R.string.watchlist_notification),
                checked = watch.notificationEnabled,
                onCheckedChange = onNotificationChange
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SheetAction(
                icon = R.drawable.ic_delete,
                text = stringResource(R.string.action_delete),
                danger = true,
                onClick = onDelete
            )
            }
            }
        }
    }
}
