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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Gewählte Umbuchungen angewendet: so wird gespeichert (Auszahlungen darin brauchen keine Wahl mehr)
    val plan = remember(preview, links, linked) { CsvTransferMatch.apply(preview.transactions, links, linked) }
    val txs = plan.transactions
    val total = txs.size + plan.storedUpdates.size
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
            // Zähler: neu, doppelt, übersprungen (mit Grund)
            Text(
                pluralStringResource(R.plurals.portfolio_csv_import_new, txs.size, txs.size),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
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
            if (preview.missingPrice.isNotEmpty()) {
                PreviewHint(stringResource(R.string.portfolio_csv_import_missing_price, preview.missingPrice.joinToString(", ")))
            }
            // Kauf/Verkauf gegen Stablecoin als Tausch gebucht: der Stablecoin erscheint im Bestand
            if (preview.stableSwaps) PreviewHint(stringResource(R.string.portfolio_csv_import_stable_swaps))
            // Zeiten ohne Zeitzone in der Datei: verwendete Zeitzone zeigen und wählen lassen
            if (preview.localTimes) ZonePicker(preview.zone, onZone)
            // Nur mehrdeutige Daten («03/04/2024»): Tag/Monat wählen lassen statt still Tag zuerst
            if (preview.dateOrderAmbiguous) DateOrderPicker(preview.dateOrder, onDateOrder)
            // Kein Dezimalzeichen erkennbar, aber «42,000»: 42000 oder 42 wählen lassen
            preview.decimalSample?.let { DecimalPicker(it, preview.decimalMark, onDecimalMark) }
            // Auszahlung + Einzahlung desselben Coins (Datei oder früherer Import): als eine Umbuchung buchen
            if (links.isNotEmpty()) TransferLinks(links, linked, onLink)
            // Auszahlungen in der Datei: je Zeile wählen — eigene Wallet (Umbuchung) oder verlässt das Portfolio
            val withdrawals = txs.filter { it.type == PortfolioTxType.WITHDRAWAL }
            if (withdrawals.isNotEmpty()) {
                WithdrawalChoices(withdrawals, proposals, choices, onChoice, onAllChoices)
            }
            if (txs.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                txs.take(PREVIEW_ROWS).forEach { tx -> PreviewRow(tx) }
                if (txs.size > PREVIEW_ROWS) {
                    Text(
                        stringResource(R.string.portfolio_csv_import_more, txs.size - PREVIEW_ROWS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    enabled = total > 0 && CsvImportMapping.allWithdrawalsDecided(txs, choices),
                    modifier = Modifier.testTag(UiTestTags.CSV_IMPORT_CONFIRM),
                ) {
                    Text(stringResource(R.string.portfolio_csv_import_confirm, total))
                }
            }
        }
    }
}

/**
 * Auszahlungen der Datei: je Zeile «Auf eigene Wallet (Umbuchung)» / «Verlässt das Portfolio (Auszahlung)», vorgewählt
 * nur bei passender beobachteter Adresse («Vorschlag: passt zu …»); dazu «Alle …». Ohne Wahl kein «Importieren».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WithdrawalChoices(
    withdrawals: List<CsvImportTx>,
    proposals: List<CsvWithdrawalProposal>,
    choices: Map<String, CsvWithdrawalChoice>,
    onChoice: (String, CsvWithdrawalChoice) -> Unit,
    onAllChoices: (CsvWithdrawalChoice) -> Unit,
) {
    val destinations = proposals.associate { it.importKey to it.transferTo }
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    Text(
        stringResource(R.string.portfolio_csv_import_withdrawals_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (!CsvImportMapping.allWithdrawalsDecided(withdrawals, choices)) {
        PreviewHint(stringResource(R.string.portfolio_csv_import_choose_each))
    }
    // «Alle …» nur bei mehreren Auszahlungen
    if (withdrawals.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onAllChoices(CsvWithdrawalChoice.TRANSFER) }) {
                Text(stringResource(R.string.portfolio_csv_import_all_transfer))
            }
            TextButton(onClick = { onAllChoices(CsvWithdrawalChoice.WITHDRAWAL) }) {
                Text(stringResource(R.string.portfolio_csv_import_all_withdrawal))
            }
        }
    }
    withdrawals.forEach { tx ->
        val choice = choices[tx.importKey]
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            PreviewRow(tx)
            destinations[tx.importKey]?.let { PreviewHint(stringResource(R.string.portfolio_csv_import_choice_proposed, it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = choice == CsvWithdrawalChoice.TRANSFER,
                    onClick = { onChoice(tx.importKey, CsvWithdrawalChoice.TRANSFER) },
                    label = { Text(stringResource(R.string.portfolio_csv_import_choice_transfer)) },
                )
                FilterChip(
                    selected = choice == CsvWithdrawalChoice.WITHDRAWAL,
                    onClick = { onChoice(tx.importKey, CsvWithdrawalChoice.WITHDRAWAL) },
                    label = { Text(stringResource(R.string.portfolio_csv_import_choice_withdrawal)) },
                )
            }
        }
    }
}

/**
 * Umbuchungen zwischen Börsen: je Kandidat «Auszahlung → Einzahlung» mit Schalter «Als eine Umbuchung buchen»
 * (eindeutige vorgewählt), Netzwerkgebühr, Hinweis auf eine Seite aus einem früheren Import bzw. auf mehrere Treffer.
 */
@Composable
private fun TransferLinks(links: List<CsvTransferLink>, linked: Set<String>, onLink: (String, Boolean) -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    Text(
        stringResource(R.string.portfolio_csv_import_transfers_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    PreviewHint(stringResource(R.string.portfolio_csv_import_transfers_hint))
    val linkLabel = stringResource(R.string.portfolio_csv_import_transfer_link)
    links.forEach { link ->
        val on = link.key in linked
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = on, role = Role.Switch, onValueChange = { onLink(link.key, it) })
                .padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    listOf(
                        PortfolioFormat.date(link.withdrawalTime),
                        "${PortfolioFormat.amount(link.sent, link.coin)} → ${PortfolioFormat.amount(link.received, link.coin)}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                )
                // Börsen nur, wenn beide bekannt sind (eigene CSV hat keine)
                if (link.from != null && link.to != null) PreviewHint("${link.from} → ${link.to}")
                link.fee?.let { fee ->
                    val text = if (link.feeUnit == PortfolioFeeUnit.COIN) PortfolioFormat.amount(fee, link.coin)
                    else PortfolioFormat.usdt(fee.toDouble())
                    PreviewHint(stringResource(R.string.portfolio_csv_import_transfer_fee, text))
                }
                if (link.withdrawalId != null || link.depositId != null) {
                    PreviewHint(stringResource(R.string.portfolio_csv_import_transfer_stored))
                }
                if (!link.unambiguous) PreviewHint(stringResource(R.string.portfolio_csv_import_transfer_ambiguous))
            }
            Spacer(Modifier.width(12.dp))
            // Zustand trägt die Zeile (toggleable); der Schalter zeigt ihn nur, Screenreader liest «Als eine Umbuchung buchen»
            Switch(checked = on, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = linkLabel })
        }
    }
}

/** «Zeiten ohne Zeitzone gelesen als: …» mit Auswahl Gerät / Europe/Zurich / UTC. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZonePicker(zone: String, onZone: (String) -> Unit) {
    val device = ZoneId.systemDefault().id
    PreviewHint(stringResource(R.string.portfolio_csv_import_zone, zone))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(device, CsvImportParser.SWISS_ZONE, "UTC").distinct().forEach { z ->
            FilterChip(
                selected = z == zone,
                onClick = { if (z != zone) onZone(z) },
                label = { Text(if (z == device) stringResource(R.string.portfolio_csv_import_zone_device, z) else z) },
            )
        }
    }
}

/** «Mehrdeutige Daten (z. B. 03/04/2024) gelesen als:» mit Auswahl «Tag/Monat» / «Monat/Tag». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateOrderPicker(order: CsvDateOrder, onDateOrder: (CsvDateOrder) -> Unit) {
    PreviewHint(stringResource(R.string.portfolio_csv_import_date_order))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CsvDateOrder.entries.forEach { o ->
            FilterChip(
                selected = o == order,
                onClick = { if (o != order) onDateOrder(o) },
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
private fun DecimalPicker(sample: String, mark: Char?, onDecimalMark: (Char) -> Unit) {
    PreviewHint(stringResource(R.string.portfolio_csv_import_decimal, sample))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Komma als Tausender (Punkt = Dezimal) zuerst, dann Komma als Dezimal; gleiche Werte nur einmal
        listOf('.', ',').mapNotNull { m -> CsvImportParser.sampleValue(sample, m)?.let { m to it } }
            .distinctBy { it.second.stripTrailingZeros() }
            .forEach { (m, value) ->
                FilterChip(
                    selected = m == mark,
                    onClick = { if (m != mark) onDecimalMark(m) },
                    label = { Text("$sample = ${CsvImport.text(value)}", style = MaterialTheme.typography.bodyMedium.tabularNumbers()) },
                )
            }
    }
}

@Composable
private fun PreviewHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** «12.06.2024 · Kauf · 0.0028 BTC · 95’287.63 USDT». */
@Composable
private fun PreviewRow(tx: CsvImportTx) {
    val parts = listOfNotNull(
        PortfolioFormat.date(tx.time),
        stringResource(tx.type.labelRes()),
        PortfolioFormat.amount(tx.amount, tx.coin),
        tx.priceUsdt?.let { PortfolioFormat.price(it.toDouble()) },
    )
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
        maxLines = 1,
    )
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
}
