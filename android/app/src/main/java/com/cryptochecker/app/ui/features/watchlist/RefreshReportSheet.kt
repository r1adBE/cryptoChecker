@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.ui.components.SectionTitle
import com.cryptochecker.app.ui.components.sectionTitleMarker
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.refresh.RefreshHeadline
import com.cryptochecker.app.domain.refresh.RefreshReport
import com.cryptochecker.app.domain.refresh.RefreshReportLogic
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers
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
                    if (report.exchanges.isNotEmpty()) {
                        ReportSection(stringResource(R.string.refresh_sheet_section_exchanges)) {
                            RefreshReportLogic.sorted(report.exchanges).forEachIndexed { i, exchange ->
                                if (i > 0) RowDivider()
                                MarketRow(exchange, locale)
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
        is RefreshHeadline.ExchangeUnreachable -> stringResource(R.string.refresh_sheet_exchange_unreachable, h.name)
        is RefreshHeadline.ExchangesUnreachable ->
            pluralStringResource(R.plurals.refresh_sheet_exchanges_unreachable, h.count, h.count)
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
            .sectionTitleMarker()
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
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 48.dp)
    )
}

/**
 * «0,5 s» in der Sprache der App: Zahl und Einheit über ICU (z. B. «0,5 Sek.», «0.5秒», «٠٫٥ ث»),
 * eine Nachkommastelle; ohne ICU (JVM-Tests) «0.5 s». Wie iOS `Measurement(…, unit: .seconds)`.
 */
internal fun seconds(millis: Long, locale: Locale): String {
    val value = millis / 1000.0
    return runCatching {
        val number = android.icu.text.NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT, number)
            .format(android.icu.util.Measure(value, android.icu.util.MeasureUnit.SECOND))
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "${LocaleNumbers.decimal(value, 1, locale = locale)} s"
}
