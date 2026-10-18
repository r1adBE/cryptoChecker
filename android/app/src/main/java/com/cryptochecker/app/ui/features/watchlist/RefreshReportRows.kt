// Zeilen des Berichts «Letzte Aktualisierung» (Teil von `RefreshReportSheet`): Börsen mit Status und Grund,
// Schritte des Ablaufs, App-Start, Status-Symbol und -Farben. Eigene Datei, weil das Blatt sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.MarketRefresh
import com.cryptochecker.app.domain.refresh.RefreshFailure
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.domain.refresh.RefreshStatus
import com.cryptochecker.app.ui.theme.AppColors
import com.cryptochecker.app.ui.theme.PriceColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import java.util.Locale

/** Eine Börse: Status, Name, Dauer; darunter Paare und Sammelabfrage, ggf. die Ursachen. */
@Composable
internal fun MarketRow(market: MarketRefresh, locale: Locale) {
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
    // Meldung der Börse zur häufigsten Ursache (gekürzt, ohne Adressen) — nur hier; die Zeile der Merkliste
    // zeigt weiterhin den allgemeinen Text
    val exchangeDetail = market.detail?.takeIf { market.failed > 0 }
        ?.let { stringResource(R.string.refresh_sheet_exchange_detail, it) }
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
        exchangeDetail.orEmpty(),
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
            if (exchangeDetail != null) {
                Text(
                    exchangeDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Ablauf: zweispaltig, links Bezeichnung (ggf. mit Zusatz), rechts Dauer. */
@Composable
internal fun StepRows(report: RefreshReport, locale: Locale) {
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
internal fun AppStartRow(millis: Long?, locale: Locale) {
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
internal fun StatusIcon(status: RefreshStatus, size: Dp, modifier: Modifier = Modifier) {
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
