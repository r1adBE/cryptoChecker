@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.components.SectionTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.MarketRefresh
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshHeadline
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.refresh.RefreshStatus
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import java.util.Locale

/**
 * «Letzte Aktualisierung» (Runde 22): oben der Gesamtstatus, darunter je Börse eine Zeile
 * mit Status (Farbe UND Form: ✓ / ! / ×), dann der Ablauf (Netz, Datenbank, …).
 * Gleicher Aufbau wie `RefreshReportSheet.swift`.
 */
@Composable
internal fun RefreshReportSheet(
    report: RefreshReport?,
    now: Long,
    /** App-Start bis zum ersten Bild der Merkliste (zuletzt gemessen); null = keine Zeile. */
    appStartMillis: Long? = null,
    /** Börsen, deren Kurse gerade per WebSocket kommen (Runde 31); leer = keine Zeile. */
    liveExchanges: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val locale = LocalConfiguration.current.locales[0]

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Gleich in voller Höhe: kommt während des Ansehens ein neuer Bericht, springt nichts
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 12.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.watchlist_refresh_report),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.padding(end = 12.dp)) {
                // «Live: Binance, Bybit» — diese Paare kommen per WebSocket, die übrigen per Abfrage
                if (liveExchanges.isNotEmpty()) {
                    Text(
                        stringResource(R.string.refresh_report_live, liveExchanges.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
                if (report == null) {
                    Text(
                        stringResource(R.string.watchlist_refresh_report_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    ReportSummary(report, now, locale)
                    if (report.markets.isNotEmpty()) {
                        ReportSection(stringResource(R.string.refresh_sheet_section_markets)) {
                            RefreshReportLogic.sorted(report.markets).forEachIndexed { i, market ->
                                if (i > 0) RowDivider()
                                MarketRow(market, locale)
                            }
                        }
                    }
                    if (report.aborted == null) {
                        ReportSection(stringResource(R.string.refresh_sheet_section_steps)) {
                            StepRows(report, locale)
                            AppStartRow(appStartMillis, locale)
                        }
                    } else if (appStartMillis != null) {
                        ReportSection(stringResource(R.string.refresh_sheet_section_steps)) {
                            AppStartRow(appStartMillis, locale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSummary(report: RefreshReport, now: Long, locale: Locale) {
    val status = RefreshReportLogic.overall(report)
    val title = when (val h = RefreshReportLogic.headline(report)) {
        RefreshHeadline.AllUpdated -> stringResource(R.string.refresh_sheet_all_updated)
        is RefreshHeadline.PairsNotUpdated ->
            pluralStringResource(R.plurals.refresh_sheet_pairs_not_updated, h.count, h.count)
        is RefreshHeadline.MarketUnreachable -> stringResource(R.string.refresh_sheet_market_unreachable, h.name)
        is RefreshHeadline.MarketsUnreachable ->
            pluralStringResource(R.plurals.refresh_sheet_markets_unreachable, h.count, h.count)
        RefreshHeadline.Aborted -> stringResource(R.string.refresh_sheet_aborted)
    }
    val parts = buildList {
        if (report.aborted != null) {
            add(report.aborted)
        } else {
            add(pluralStringResource(R.plurals.refresh_sheet_pairs, report.pairs, report.pairs))
            add(seconds(report.totalMillis, locale))
        }
        if (report.at > 0) add(ago(report.at, now))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        StatusIcon(status, 32.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReportSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = SectionTitle.style,
        color = SectionTitle.color,
        modifier = Modifier
            .padding(top = Spacing.lg, bottom = 8.dp)
            .semantics { heading() }
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 48.dp)
    )
}

/** Eine Börse: Status, Name, Dauer; darunter Paare und Sammelabfrage, ggf. die Ursachen. */
@Composable
private fun MarketRow(market: MarketRefresh, locale: Locale) {
    val status = RefreshReportLogic.status(market)
    val duration = seconds(market.millis, locale)
    val pairsText = pluralStringResource(R.plurals.refresh_sheet_pairs_of, market.pairs, market.pairs, market.updated)
    val detail = buildList {
        add(pairsText)
        if (market.bulkTried) {
            val bulkSecs = seconds(market.bulkMillis, locale)
            add(
                if (market.bulkPrices > 0) {
                    pluralStringResource(R.plurals.refresh_sheet_bulk, market.bulkPrices, bulkSecs, market.bulkPrices)
                } else {
                    stringResource(R.string.refresh_report_bulk_failed, bulkSecs)
                }
            )
        }
        if (market.singles > 0) add(stringResource(R.string.refresh_report_singles, market.singles))
    }.joinToString(" · ")
    val issues = buildList {
        if (market.notTraded > 0) {
            add(pluralStringResource(R.plurals.refresh_report_not_traded, market.notTraded, market.notTraded))
        }
        if (market.failed > 0) {
            val errors = pluralStringResource(R.plurals.refresh_report_errors, market.failed, market.failed)
            val reason = market.reason?.let { reasonText(it) }
            add(if (reason == null) errors else stringResource(R.string.refresh_sheet_with_reason, errors, reason))
        }
        // Pause je Börse: «pausiert bis 19:45 (zu viele Anfragen)»
        market.pausedUntil?.let { until ->
            val time = PriceFormat.shortTime(until)
            val reason = market.pauseReason?.let { reasonText(it) }
            add(
                if (reason == null) stringResource(R.string.refresh_sheet_paused_until, time)
                else stringResource(R.string.refresh_sheet_paused_until_reason, time, reason)
            )
        }
    }.joinToString(" · ")
    val statusText = stringResource(
        when (status) {
            RefreshStatus.OK -> R.string.refresh_sheet_status_ok
            RefreshStatus.PARTIAL -> R.string.refresh_sheet_status_partial
            RefreshStatus.FAILED -> R.string.refresh_sheet_status_failed
        }
    )
    // Screenreader: ein Satz je Börse («Binance, alles aktualisiert, 5 von 5 Paaren, 0,5 Sekunden»)
    val spoken = listOf(
        market.name, statusText, pairsText,
        stringResource(R.string.refresh_sheet_seconds_a11y, "%.1f".format(locale, market.millis / 1000.0)),
        issues,
    ).filter { it.isNotEmpty() }.joinToString(", ")

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        StatusIcon(status, 20.dp, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    market.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    duration,
                    style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (issues.isNotEmpty()) {
                Text(
                    issues,
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = statusTextColor(status),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Ablauf: zweispaltig, links Bezeichnung (ggf. mit Zusatz), rechts Dauer. */
@Composable
private fun StepRows(report: RefreshReport, locale: Locale) {
    report.waitMillis?.let { StepRow(stringResource(R.string.refresh_sheet_wait), null, seconds(it, locale)) }
    StepRow(stringResource(R.string.refresh_sheet_network), null, seconds(report.networkMillis, locale))
    report.dbMillis?.let { StepRow(stringResource(R.string.refresh_sheet_database), null, seconds(it, locale)) }
    report.effectsMillis?.let { millis ->
        val extra = buildList {
            if (report.alarms > 0) {
                add(pluralStringResource(R.plurals.refresh_sheet_alarms_triggered, report.alarms, report.alarms))
            }
            add(pluralStringResource(R.plurals.refresh_sheet_notifications, report.notifications, report.notifications))
        }.joinToString(" · ")
        StepRow(stringResource(R.string.refresh_sheet_alarms), extra, seconds(millis, locale))
    }
    report.widgetMillis?.let { StepRow(stringResource(R.string.refresh_sheet_widgets), null, seconds(it, locale)) }
}

/** «App-Start 0,4 s»: zuletzt gemessene Zeit bis zum ersten Bild der Merkliste (nur lokal). */
@Composable
private fun AppStartRow(millis: Long?, locale: Locale) {
    if (millis == null) return
    StepRow(stringResource(R.string.refresh_sheet_app_start), null, seconds(millis, locale))
}

@Composable
private fun StepRow(label: String, extra: String?, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (extra != null) {
                Text(
                    " · $extra",
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Kreis mit ✓ / ! / × in Grün / Orange / Rot — nie nur Farbe. Der Text daneben trägt die Aussage. */
@Composable
private fun StatusIcon(status: RefreshStatus, size: Dp, modifier: Modifier = Modifier) {
    Icon(
        painterResource(
            when (status) {
                RefreshStatus.OK -> R.drawable.ic_status_ok
                RefreshStatus.PARTIAL -> R.drawable.ic_status_partial
                RefreshStatus.FAILED -> R.drawable.ic_status_failed
            }
        ),
        contentDescription = null,
        tint = statusColor(status),
        modifier = modifier.size(size)
    )
}

@Composable
@ReadOnlyComposable
private fun statusColor(status: RefreshStatus): Color = when (status) {
    RefreshStatus.OK -> PriceColors.ok
    RefreshStatus.PARTIAL -> AppColors.warning
    RefreshStatus.FAILED -> MaterialTheme.colorScheme.error
}

/** Für Text (dritte Zeile): Bernstein in der dunkleren Fassung, damit es auf hellem Grund lesbar bleibt. */
@Composable
@ReadOnlyComposable
private fun statusTextColor(status: RefreshStatus): Color = when (status) {
    RefreshStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> AppColors.warningText
}

@Composable
@ReadOnlyComposable
private fun reasonText(reason: RefreshFailure): String? = when (reason) {
    RefreshFailure.TIMEOUT -> stringResource(R.string.refresh_sheet_reason_timeout)
    RefreshFailure.OFFLINE -> stringResource(R.string.refresh_sheet_reason_offline)
    RefreshFailure.RATE_LIMIT -> stringResource(R.string.refresh_sheet_reason_rate_limit)
    RefreshFailure.SERVER -> stringResource(R.string.refresh_sheet_reason_server)
    RefreshFailure.NO_DATA -> stringResource(R.string.refresh_sheet_reason_no_data)
    RefreshFailure.UNAVAILABLE -> stringResource(R.string.refresh_sheet_reason_unavailable)
    RefreshFailure.OTHER -> null
}

/** «0,5 s» in der Sprache der App (Komma/Punkt je Sprache). */
private fun seconds(millis: Long, locale: Locale): String = "%.1f s".format(locale, millis / 1000.0)
