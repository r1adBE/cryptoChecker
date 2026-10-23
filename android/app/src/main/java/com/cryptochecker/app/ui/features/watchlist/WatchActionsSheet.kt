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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.data.remote.FuturesInfo
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.spot.SpotData
import com.cryptochecker.app.domain.watch.SheetChartRange
import com.cryptochecker.app.domain.watch.SheetChartResult
import com.cryptochecker.app.domain.watch.isNotTraded
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.NoteDialog
import com.cryptochecker.app.ui.components.WidgetManualDialog
import com.cryptochecker.app.ui.components.rememberReduceMotion
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.app.widget.WidgetKind
import com.cryptochecker.app.widget.WidgetPinner

/**
 * Aktionen zu einem Paar: oben Kopf mit Stift, Kurs und darunter «↗ +2.35% · 24h · Aktualisiert …»,
 * gleich danach gross Alarm, «Was bewegt sich mit?» und Favorit, dann Chart ([SheetPriceChart]) mit Zeitraum und
 * Kerzen/Linie (Notiz und Marktdaten, falls vorhanden). Alles Weitere —
 * Gruppe, Notiz, Portfolio, Aktualisieren, Widget, Vorlesen, Meldung und zuletzt Löschen — im
 * aufklappbaren Abschnitt «Mehr» (zu; Zustand für die Sitzung gemerkt). Das Blatt öffnet halb
 * hoch (Kopf mit Kurs, Aktionen, Anfang des Charts) und lässt sich auf volle Höhe ziehen (L-16).
 *
 * [preview]: Paar steht (noch) nicht in der Merkliste (Coin aus «Heute auffällig», nur im Speicher):
 * Kopf, Kurs, Chart und «Was bewegt sich mit?» wie sonst, dazu gross «Zur Merkliste hinzufügen»
 * ([onAddToWatchlist]). Alles, was einen gespeicherten Eintrag braucht — Paar bearbeiten, Alarm,
 * Favorit, Notiz und der ganze Abschnitt «Mehr» (Gruppe, Portfolio, Widget, Vorlesen, Meldung,
 * Löschen) —, fehlt. Nach dem Hinzufügen zeigt der Aufrufer dasselbe Blatt für den neuen Eintrag.
 */
@Composable
internal fun WatchActionsSheet(
    watch: WatchEntity,
    alarmCount: Int,
    onDismiss: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    /** «Kurs vorlesen» umschalten; true = die Sprachausgabe wurde dafür eingeschaltet (kurzer Hinweis). */
    onTtsChange: (Boolean) -> Boolean,
    onToggleFavorite: () -> Unit,
    onOpenAlarms: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    loadFutures: suspend (WatchEntity) -> FuturesInfo? = { null },
    /** Spot-Kennzahlen (CoinGecko) für die Karte «Marktdaten»; null = keine. */
    loadSpot: suspend (WatchEntity) -> SpotData? = { null },
    groups: List<String> = emptyList(),
    /** null = Eintrag verborgen (Vorschau); sonst immer, auch mit Portfolio aus ([PortfolioOff]). */
    onAddToPortfolio: (() -> Unit)? = null,
    /**
     * IOS5-R02: `close` schliesst den Dialog — erst nach bestätigter Rückfrage F-08 aufrufen; `cancelled`
     * bei «Abbrechen» (Dialog bleibt, wieder bereit). Bis dahin zählt kein weiterer Tipp (wie iOS `saving`).
     */
    onSetGroup: (String?, close: () -> Unit, cancelled: () -> Unit) -> Unit = { _, close, _ -> close() },
    onWhy: () -> Unit = {},
    onSetNote: (String?, close: () -> Unit, cancelled: () -> Unit) -> Unit = { _, close, _ -> close() },
    /** Chart als Linie statt Kerzen (zuletzt gewählt, für alle Paare gleich). */
    chartLine: Boolean = false,
    onChartLineChange: (Boolean) -> Unit = {},
    cachedChart: (WatchEntity, SheetChartRange) -> SheetChartResult? = { _, _ -> SheetChartResult.Unsupported },
    loadChart: suspend (WatchEntity, SheetChartRange) -> SheetChartResult = { _, _ -> SheetChartResult.Unsupported },
    /** Bitcoin-Paare: Umrechnungswährung und Faktor Quote → sie für «1 CHF = … Sats». */
    satsRate: suspend (String) -> Pair<String, Double>? = { null },
    /** Änderung aus den Kerzen einer anderen Börse (z. B. «Binance» bei einem Kraken-Paar); null = nicht. */
    candleSource: String? = null,
    /**
     * Was in der Zeile von einer anderen Börse kommt (Änderung, Mini-Chart). L-14: steht nicht mehr
     * in der Zeile, sondern hier unter dem Kurs («Änderung & Chart: Binance»); null = [candleSource].
     */
    rowSource: com.cryptochecker.app.domain.watch.DayChange.RowSource? = null,
    /** Vorschau eines Paars, das nicht in der Merkliste steht (siehe oben). */
    preview: Boolean = false,
    /** Vorschau: «Zur Merkliste hinzufügen». */
    onAddToWatchlist: () -> Unit = {},
    /** Vorschau: wird gerade hinzugefügt (Knopf gesperrt, «Zur Merkliste hinzugefügt.»). */
    adding: Boolean = false,
    /** Ungewöhnliche Aktivität (⚡): «Was bewegt sich mit?» mit gefüllter Glühbirne, sonst als Umriss (wie iOS). */
    hasActivity: Boolean = false,
    /** «Deaktivieren» (unter «Mehr») bzw. «Aktivieren» (Hinweis unter dem Kurs und unter «Mehr»). */
    onToggleDisabled: () -> Unit = {},
    /** Jetzt (Epoch-ms) und Grenze «veraltet» ([com.cryptochecker.app.domain.refresh.OutdatedRule]); 0 = nie veraltet. */
    now: Long = 0L,
    outdatedAfter: Long = 0L,
) {
    // Deaktiviert: keine Abfragen — kein Chart, keine Futures-Kennzahlen, kein «Was bewegt sich mit?», kein «Aktualisieren»
    val off = watch.disabled && !preview
    // L-16: halb hoch öffnen (Kurs, Aktionen, Chart-Anfang), auf volle Höhe ziehbar
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val sheetContext = LocalContext.current
    val sheetMotion = !rememberReduceMotion()
    var showWidgetManual by remember { mutableStateOf(false) }
    if (showWidgetManual) WidgetManualDialog(onDismiss = { showWidgetManual = false })
    var editGroup by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(false) }
    // Notiz bzw. Gruppe wird gespeichert (Rückfrage F-08 offen): weitere Tipps zählen nicht (ARC6-R02, wie iOS)
    var savingEdit by remember { mutableStateOf(false) }
    // «Paar bearbeiten» (Stift neben dem Paar): Börse, Paar, Kontrakt desselben Eintrags
    var editPair by rememberSaveable(watch.id) { mutableStateOf(false) }
    if (editPair && !preview) {
        WatchEditSheet(watch = watch, alarmCount = alarmCount, onDismiss = { editPair = false })
    }
    if (editNote) {
        NoteDialog(
            title = stringResource(R.string.note_title),
            initial = watch.note,
            // «Abbrechen» in der Rückfrage F-08: Dialog bleibt mit dem Text (wie der Alarm-Editor)
            onSave = { note ->
                if (!savingEdit) {
                    savingEdit = true
                    onSetNote(note, { editNote = false; savingEdit = false }, { savingEdit = false })
                }
            },
            onDismiss = { editNote = false; savingEdit = false }
        )
    }
    if (editGroup) {
        GroupDialog(
            current = watch.groupName,
            groups = groups,
            onSelect = { group ->
                if (!savingEdit) {
                    savingEdit = true
                    onSetGroup(group, { editGroup = false; savingEdit = false }, { savingEdit = false })
                }
            },
            onDismiss = { editGroup = false; savingEdit = false }
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Kein fillMaxHeight mehr (L-16): halb offen bleibt die Höhe fest (halber Bildschirm),
                // nach oben ziehen zeigt alles
                // Mit dem Chart wird das Blatt auf kleinen Geräten höher als der Bildschirm
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            // Eine Zeile unter dem Kurs: «↗ +41.71% · 24h · Aktualisiert 01:42:19» — Änderung farbig,
            // Zeitraum und Stand klein und grau. Mit Fehler, Vorschau ohne Kurs oder deaktiviert
            // steht der Stand wie bisher in der eigenen Zeile weiter unten.
            val updatedInline = watch.lastPrice != null && watch.lastError == null && !off &&
                !(preview && watch.lastUpdate <= 0)
            WatchActionsHeader(
                watch = watch,
                preview = preview,
                off = off,
                updatedInline = updatedInline,
                now = now,
                outdatedAfter = outdatedAfter,
                candleSource = candleSource,
                rowSource = rowSource,
                satsRate = satsRate,
                onEditPair = { editPair = true },
            )
            // Börse nicht erreichbar, aber ein älterer Kurs steht: ruhiger Satz plus «Erneut versuchen»
            val retryable = watch.lastPrice != null && isRetryableMarketError(watch.lastError)
            if (off) {
                // Deaktiviert: ruhiger Hinweis mit Stand des letzten Kurses und gleich «Aktivieren»
                DisabledNotice(updatedAt = watch.lastUpdate, onEnable = onToggleDisabled)
            } else if (retryable) {
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
            } else if (updatedInline) {
                // Stand steht schon in der Zeile der Änderung unter dem Kurs
                Spacer(modifier = Modifier.height(12.dp))
            } else if (watch.lastError != null && !isNotTraded(watch.lastError)) {
                // Jeder andere Fehler (auch ohne älteren Kurs): Fehlertext plus «Erneut versuchen» als nächster
                // Schritt — nur «nicht gehandelt» bleibt ohne Knopf (gleich wie iOS)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = friendlyError(watch.lastError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.try_again)) }
                }
            } else {
                val updatedText = stringResource(R.string.watchlist_updated, PriceFormat.time(watch.lastUpdate))
                Text(
                    text = watch.lastError?.let { friendlyError(it) }
                        // Vorschau: Kurs wird noch geholt
                        ?: if (preview && watch.lastUpdate <= 0) stringResource(R.string.loading_hint)
                        else updatedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
            }

            // Vorschau: gross «Zur Merkliste hinzufügen», daneben nur «Was bewegt sich mit?» (Alarm und Favorit
            // brauchen einen gespeicherten Eintrag)
            if (preview) {
                PreviewSheetActions(
                    showWhy = !watch.isNotTraded,
                    adding = adding,
                    onAdd = onAddToWatchlist,
                    onWhy = onWhy,
                )
            } else {
                // Die drei häufigsten Aktionen zuerst und gleich gross: Alarm, «Was bewegt sich mit?», Favorit
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // Gleich hoch, auch wenn ein Text umbricht
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 12.dp)
                ) {
                    // Glocke nur mit Alarm gefüllt, ohne Alarm als Umriss (wie iOS)
                    PrimarySheetAction(
                        icon = if (alarmCount > 0) R.drawable.ic_notifications_active
                        else R.drawable.ic_notifications_active_outline,
                        text = if (alarmCount > 0) pluralStringResource(R.plurals.watchlist_alarms_count, alarmCount, alarmCount)
                        else stringResource(R.string.watch_action_alarm),
                        onClick = onOpenAlarms,
                        modifier = Modifier.weight(1f).testTag(UiTestTags.WATCH_ACTION_ALARM)
                    )
                    // Nicht mehr gehandelt bzw. deaktiviert: kein «Was bewegt sich mit?» (es gäbe nur alte Daten)
                    if (!watch.isNotTraded && !off) {
                        PrimarySheetAction(
                            // Gefüllt, solange beim Paar gerade etwas passiert (⚡)
                            icon = if (hasActivity) R.drawable.ic_lightbulb_filled else R.drawable.ic_lightbulb,
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
            }

            // Kurs-Chart (24h · 7T · 30T · 1J, Kerzen/Linie); ohne Kerzenquelle (DEX) ganz ausgeblendet,
            // bei deaktivierten Paaren ebenso (keine Abfrage)
            if (!off) {
                SheetPriceChart(
                    watch = watch,
                    line = chartLine,
                    onLineChange = onChartLineChange,
                    cached = cachedChart,
                    load = loadChart,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            watch.note?.takeIf { !preview }?.let { note ->
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

            // Karte «Marktdaten» (Spot | Futures) für Krypto-Paare; nicht bei deaktivierten Paaren
            // (keine Abfrage) und DEX-Pools (fremde Token dürfen gleich heissen). Kennzahlen kommen
            // später: weich aufziehen statt springen (nicht bei reduzierter Bewegung)
            if (!off && CoinLogos.allowedFor(watch.marketKey)) {
                Box(modifier = if (sheetMotion) Modifier.animateContentSize() else Modifier) {
                    MarketDataSection(watch = watch, loadFutures = loadFutures, loadSpot = loadSpot)
                }
            }

            // Vorschau: kein «Mehr» (alles darin braucht einen gespeicherten Eintrag)
            if (!preview) {
                // Alles Weitere unter «Mehr» (zu, für die Sitzung gemerkt); weich auf- und zuklappen
                // K1: «Mehr» gilt nur für dieses Blatt — jedes Paar öffnet zugeklappt
                var moreExpanded by remember(watch.id) { mutableStateOf(false) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SheetMoreToggle(
                    expanded = moreExpanded,
                    onToggle = { moreExpanded = !moreExpanded }
                )
                Column(modifier = if (sheetMotion) Modifier.animateContentSize() else Modifier) {
                    if (moreExpanded) {
                        SheetMoreActions(
                            watch = watch,
                            off = off,
                            sheetContext = sheetContext,
                            onEditGroup = { editGroup = true },
                            onEditNote = { editNote = true },
                            onAddToPortfolio = onAddToPortfolio,
                            onRefresh = onRefresh,
                            onAddWidget = {
                                if (!WidgetPinner.request(sheetContext, WidgetKind.SINGLE, watch.id)) showWidgetManual = true
                            },
                            onTtsChange = onTtsChange,
                            onNotificationChange = onNotificationChange,
                            onToggleDisabled = onToggleDisabled,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}
