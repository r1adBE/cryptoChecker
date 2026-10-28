// Vorschau-Blatt des CSV-Imports: Format, übersprungene Zeilen, Umbuchungen, Wahl der Auszahlungen,
// Zeitzone, Datumsreihenfolge, Dezimalzeichen und die ersten Zeilen.
// Eigene Datei, weil das Blatt viele eigene Bausteine hat; `PortfolioCsvImport.kt` startet nur den Ablauf
// (wie `PortfolioCsvImportSheet` in `PortfolioCsvImport.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvDateOrder
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImport
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportFormat
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportParser
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportSkipReason
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalProposal
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.theme.tabularNumbers
import java.time.ZoneId

/** Höchstens so viele Zeilen in der Vorschau. */
private const val PREVIEW_ROWS = 8

@Composable
internal fun CsvImportPreviewSheet(
    preview: CsvImportPreview,
    proposals: List<CsvWithdrawalProposal>,
    choices: Map<String, CsvWithdrawalChoice>,
    onChoice: (String, CsvWithdrawalChoice) -> Unit,
    onAllChoices: (CsvWithdrawalChoice) -> Unit,
    links: List<CsvTransferLink>,
    linked: Set<String>,
    onLink: (String, Boolean) -> Unit,
    onZone: (String) -> Unit,
    onDateOrder: (CsvDateOrder) -> Unit,
    onDecimalMark: (Char) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    /** Lesart geändert, Datei wird neu gelesen — Knopf aus, Vorschau gedimmt. */
    rereading: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Gewählte Umbuchungen angewendet: so wird gespeichert (Auszahlungen darin brauchen keine Wahl mehr)
    val plan = remember(preview, links, linked) { CsvTransferMatch.apply(preview.transactions, links, linked) }
    val txs = plan.transactions
    val total = txs.size + plan.storedUpdates.size
    // Gemerkt: bei Tausenden Zeilen nicht bei jeder Neuzeichnung filtern
    val withdrawals = remember(txs) { txs.filter { it.type == PortfolioTxType.WITHDRAWAL } }
    val allDecided = remember(txs, choices) { CsvImportMapping.allWithdrawalsDecided(txs, choices) }
    val dim = if (rereading) 0.4f else 1f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.portfolio_csv_import_action),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.portfolio_csv_import_format, preview.format.displayName()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Erkannte Spalten der Kopfzeile (hilft bei «nicht erkannt»/fehlenden Werten)
            if (preview.columns.isNotEmpty()) {
                PreviewHint(stringResource(R.string.portfolio_csv_import_columns, preview.columns.joinToString(", ")))
            }
            // Neue Lesart wird gelesen — Hinweis (auch für Screenreader), Rest gedimmt
            if (rereading) {
                Text(
                    stringResource(R.string.portfolio_csv_import_rereading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            // Zähler: neu, doppelt, übersprungen (mit Grund)
            Text(
                pluralStringResource(R.plurals.portfolio_csv_import_new, txs.size, txs.size),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(dim),
            )
            if (preview.duplicates > 0) {
                Text(pluralStringResource(R.plurals.portfolio_csv_import_duplicates, preview.duplicates, preview.duplicates))
            }
            if (preview.skipped.isNotEmpty()) {
                Text(pluralStringResource(R.plurals.portfolio_csv_import_skipped, preview.skipped.size, preview.skipped.size))
                preview.skipped.take(PREVIEW_ROWS).forEach { skip ->
                    Text(
                        stringResource(R.string.portfolio_csv_import_line, skip.line, stringResource(skip.reason.labelRes())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (preview.missingFx.isNotEmpty()) {
                PreviewHint(stringResource(R.string.portfolio_csv_import_missing_fx, preview.missingFx.joinToString(", ")))
            }
            // Fiat-Preise ohne USDT/USD-Tagesschluss: mit USDT = 1 umgerechnet (Vermerk wie im Portfolio)
            if (preview.usdtApproximate.isNotEmpty()) {
                PreviewHint(stringResource(R.string.portfolio_usdt_assumed) + " (" + preview.usdtApproximate.joinToString(", ") + ")")
            }
            if (preview.missingPrice.isNotEmpty()) {
                PreviewHint(stringResource(R.string.portfolio_csv_import_missing_price, preview.missingPrice.joinToString(", ")))
            }
            // Kauf/Verkauf gegen Stablecoin als Tausch gebucht: der Stablecoin erscheint im Bestand
            if (preview.stableSwaps) PreviewHint(stringResource(R.string.portfolio_csv_import_stable_swaps))
            // Zeiten ohne Zeitzone in der Datei: verwendete Zeitzone zeigen und wählen lassen
            // Während des Neu-Lesens sind Chips, Schalter und Knöpfe aus (nicht nur gedimmt)
            val enabled = !rereading
            if (preview.localTimes) ZonePicker(preview.zone, onZone, enabled)
            // Nur mehrdeutige Daten («03/04/2024»): Tag/Monat wählen lassen statt still Tag zuerst
            if (preview.dateOrderAmbiguous) DateOrderPicker(preview.dateOrder, onDateOrder, enabled)
            // Kein Dezimalzeichen erkennbar, aber «42,000»: 42000 oder 42 wählen lassen
            preview.decimalSample?.let { DecimalPicker(it, preview.decimalMark, onDecimalMark, enabled) }
            // Listen und Zeilen gedimmt, solange neu gelesen wird
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.alpha(dim)) {
                // Auszahlung + Einzahlung desselben Coins (Datei oder früherer Import): als eine Umbuchung buchen
                if (links.isNotEmpty()) TransferLinks(links, linked, onLink, enabled)
                // Auszahlungen in der Datei: je Zeile wählen — eigene Wallet (Umbuchung) oder verlässt das Portfolio
                if (withdrawals.isNotEmpty()) {
                    WithdrawalChoices(withdrawals, proposals, choices, onChoice, onAllChoices, enabled)
                }
                if (txs.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    txs.take(PREVIEW_ROWS).forEach { tx -> PreviewRow(tx) }
                    if (txs.size > PREVIEW_ROWS) {
                        Text(
                            stringResource(R.string.portfolio_csv_import_more, txs.size - PREVIEW_ROWS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    enabled = total > 0 && allDecided && !rereading,
                    modifier = Modifier.testTag(UiTestTags.CSV_IMPORT_CONFIRM),
                ) {
                    Text(stringResource(R.string.portfolio_csv_import_confirm, total))
                }
            }
        }
    }
}

/** «Zeiten ohne Zeitzone gelesen als: …» mit Auswahl Gerät / Europe/Zurich / UTC. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZonePicker(zone: String, onZone: (String) -> Unit, enabled: Boolean) {
    val device = ZoneId.systemDefault().id
    PreviewHint(stringResource(R.string.portfolio_csv_import_zone, zone))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(device, CsvImportParser.SWISS_ZONE, "UTC").distinct().forEach { z ->
            FilterChip(
                selected = z == zone,
                onClick = { if (z != zone) onZone(z) },
                enabled = enabled,
                label = { Text(if (z == device) stringResource(R.string.portfolio_csv_import_zone_device, z) else z) },
            )
        }
    }
}

/** «Mehrdeutige Daten (z. B. 03/04/2024) gelesen als:» mit Auswahl «Tag/Monat» / «Monat/Tag». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateOrderPicker(order: CsvDateOrder, onDateOrder: (CsvDateOrder) -> Unit, enabled: Boolean) {
    PreviewHint(stringResource(R.string.portfolio_csv_import_date_order))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CsvDateOrder.entries.forEach { o ->
            FilterChip(
                selected = o == order,
                onClick = { if (o != order) onDateOrder(o) },
                enabled = enabled,
                label = {
                    Text(
                        stringResource(
                            if (o == CsvDateOrder.DAY_FIRST) R.string.portfolio_csv_import_date_day_first
                            else R.string.portfolio_csv_import_date_month_first,
                        ),
                    )
                },
            )
        }
    }
}

/** «Mehrdeutige Zahlen (z. B. 42,000) gelesen als:» mit Auswahl «42,000 = 42000» / «42,000 = 42». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecimalPicker(sample: String, mark: Char?, onDecimalMark: (Char) -> Unit, enabled: Boolean) {
    PreviewHint(stringResource(R.string.portfolio_csv_import_decimal, sample))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Komma als Tausender (Punkt = Dezimal) zuerst, dann Komma als Dezimal; gleiche Werte nur einmal
        listOf('.', ',').mapNotNull { m -> CsvImportParser.sampleValue(sample, m)?.let { m to it } }
            .distinctBy { it.second.stripTrailingZeros() }
            .forEach { (m, value) ->
                FilterChip(
                    selected = m == mark,
                    onClick = { if (m != mark) onDecimalMark(m) },
                    enabled = enabled,
                    label = { Text("$sample = ${CsvImport.text(value)}", style = MaterialTheme.typography.bodyMedium.tabularNumbers()) },
                )
            }
    }
}

@Composable
internal fun PreviewHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * «12.06.2024 · Kauf» / «0.0028 BTC · 95’287.63 USDT» — zwei Zeilen ohne harte Begrenzung (mit
 * maxLines = 1 fehlten bei grosser Schrift Menge und Preis).
 */
@Composable
internal fun PreviewRow(tx: CsvImportTx) {
    val style = MaterialTheme.typography.bodyMedium.tabularNumbers()
    // TalkBack liest Datum und Menge als ein Element (wie iOS `.accessibilityElement(children: .combine)`)
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(listOf(PortfolioFormat.date(tx.time), stringResource(tx.type.labelRes())).joinToString(" · "), style = style)
        Text(
            listOfNotNull(PortfolioFormat.amount(tx.amount, tx.coin), tx.priceUsdt?.let { PortfolioFormat.price(it.toDouble()) })
                .joinToString(" · "),
            style = style,
        )
    }
}

internal fun CsvImportFormat.displayName(): String = exchangeName ?: "CSV"

private fun CsvImportSkipReason.labelRes(): Int = when (this) {
    CsvImportSkipReason.UNSUPPORTED_KIND -> R.string.portfolio_csv_skip_unsupported
    CsvImportSkipReason.NOT_CRYPTO -> R.string.portfolio_csv_skip_not_crypto
    CsvImportSkipReason.BAD_NUMBER -> R.string.portfolio_csv_skip_bad_number
    CsvImportSkipReason.BAD_DATE -> R.string.portfolio_csv_skip_bad_date
    CsvImportSkipReason.MISSING_FIELD -> R.string.portfolio_csv_skip_missing
    CsvImportSkipReason.FEE_EXCEEDS_AMOUNT -> R.string.portfolio_csv_skip_fee_exceeds
    CsvImportSkipReason.TWO_FEES -> R.string.portfolio_csv_skip_two_fees
    CsvImportSkipReason.INTERNAL_MOVE -> R.string.portfolio_csv_skip_internal_move
    CsvImportSkipReason.POSSIBLE_DUPLICATE -> R.string.portfolio_csv_skip_possible_duplicate
    CsvImportSkipReason.AMBIGUOUS_GROUP -> R.string.portfolio_csv_skip_ambiguous
    CsvImportSkipReason.DUPLICATE_TXID_DIFFERENT -> R.string.portfolio_csv_skip_duplicate_txid_different
    CsvImportSkipReason.STAKING_UNPAIRED -> R.string.portfolio_csv_skip_staking_unpaired
    CsvImportSkipReason.AMOUNT_TOO_SMALL -> R.string.portfolio_csv_skip_amount_too_small
    CsvImportSkipReason.INVALID_SWAP -> R.string.portfolio_csv_skip_invalid_swap
}
