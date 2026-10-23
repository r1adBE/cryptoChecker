// Listen im Vorschau-Blatt des CSV-Imports: Wahl der Auszahlungen und Umbuchungen zwischen Börsen.
// Eigene Datei: einzeln nur die ersten [PREVIEW_LIST_ROWS], alle in einem eigenen Blatt mit Lazy-Liste —
// grosse Dateien bauten sonst Tausende Zeilen auf einmal (wie `PortfolioCsvImportSheet+Choices.swift`).
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportTx
import com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferLink
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalChoice
import com.cryptochecker.app.domain.portfolio.csvimport.CsvWithdrawalProposal
import com.cryptochecker.app.ui.theme.tabularNumbers

/** So viele Auszahlungen bzw. Umbuchungen einzeln im Vorschau-Blatt; alle über «Alle anzeigen». Wie iOS. */
internal const val PREVIEW_LIST_ROWS = 10

/**
 * Auszahlungen der Datei: je Zeile «Auf eigene Wallet (Umbuchung)» / «Verlässt das Portfolio (Auszahlung)», vorgewählt
 * nur bei passender beobachteter Adresse («Vorschlag: passt zu …»); dazu «Alle …». Ohne Wahl kein «Importieren».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WithdrawalChoices(
    withdrawals: List<CsvImportTx>,
    proposals: List<CsvWithdrawalProposal>,
    choices: Map<String, CsvWithdrawalChoice>,
    onChoice: (String, CsvWithdrawalChoice) -> Unit,
    onAllChoices: (CsvWithdrawalChoice) -> Unit,
    /** false während des Neu-Lesens — Chips und Knöpfe aus, nicht nur gedimmt. */
    enabled: Boolean = true,
) {
    // Abgeleitete Werte nur neu, wenn sich ihre Eingaben ändern
    val destinations = remember(proposals) { proposals.associate { it.importKey to it.transferTo } }
    val allDecided = remember(withdrawals, choices) { CsvImportMapping.allWithdrawalsDecided(withdrawals, choices) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        stringResource(R.string.portfolio_csv_import_withdrawals_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (!allDecided) PreviewHint(stringResource(R.string.portfolio_csv_import_choose_each))
    // «Alle …» nur bei mehreren Auszahlungen
    if (withdrawals.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onAllChoices(CsvWithdrawalChoice.TRANSFER) }, enabled = enabled) {
                Text(stringResource(R.string.portfolio_csv_import_all_transfer))
            }
            TextButton(onClick = { onAllChoices(CsvWithdrawalChoice.WITHDRAWAL) }, enabled = enabled) {
                Text(stringResource(R.string.portfolio_csv_import_all_withdrawal))
            }
        }
    }
    withdrawals.take(PREVIEW_LIST_ROWS).forEach { tx ->
        WithdrawalChoiceRow(tx, destinations[tx.importKey], choices[tx.importKey], onChoice, enabled)
    }
    if (withdrawals.size > PREVIEW_LIST_ROWS) {
        TextButton(onClick = { showAll = true }, enabled = enabled) {
            Text(stringResource(R.string.explorer_bulk_show_all, withdrawals.size))
        }
    }
    if (showAll) {
        FullListSheet(stringResource(R.string.portfolio_csv_import_withdrawals_title), onDismiss = { showAll = false }) {
            // Schlüssel: Zeilen behalten ihren Zustand beim Neuordnen; importKey ist je Vorschau eindeutig
            items(withdrawals, key = { it.importKey }) { tx -> WithdrawalChoiceRow(tx, destinations[tx.importKey], choices[tx.importKey], onChoice, enabled) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WithdrawalChoiceRow(
    tx: CsvImportTx,
    destination: String?,
    choice: CsvWithdrawalChoice?,
    onChoice: (String, CsvWithdrawalChoice) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        PreviewRow(tx)
        destination?.let { PreviewHint(stringResource(R.string.portfolio_csv_import_choice_proposed, it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = choice == CsvWithdrawalChoice.TRANSFER,
                onClick = { onChoice(tx.importKey, CsvWithdrawalChoice.TRANSFER) },
                enabled = enabled,
                label = { Text(stringResource(R.string.portfolio_csv_import_choice_transfer)) },
            )
            FilterChip(
                selected = choice == CsvWithdrawalChoice.WITHDRAWAL,
                onClick = { onChoice(tx.importKey, CsvWithdrawalChoice.WITHDRAWAL) },
                enabled = enabled,
                label = { Text(stringResource(R.string.portfolio_csv_import_choice_withdrawal)) },
            )
        }
    }
}

/**
 * Umbuchungen zwischen Börsen: je Kandidat «Auszahlung → Einzahlung» mit Schalter «Als eine Umbuchung buchen»
 * (eindeutige vorgewählt), Netzwerkgebühr, Hinweis auf eine Seite aus einem früheren Import bzw. auf mehrere Treffer.
 */
@Composable
internal fun TransferLinks(
    links: List<CsvTransferLink>,
    linked: Set<String>,
    onLink: (String, Boolean) -> Unit,
    /** false während des Neu-Lesens — Schalter und Knöpfe aus, nicht nur gedimmt. */
    enabled: Boolean = true,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        stringResource(R.string.portfolio_csv_import_transfers_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    PreviewHint(stringResource(R.string.portfolio_csv_import_transfers_hint))
    links.take(PREVIEW_LIST_ROWS).forEach { link -> TransferLinkRow(link, link.key in linked, onLink, enabled) }
    if (links.size > PREVIEW_LIST_ROWS) {
        TextButton(onClick = { showAll = true }, enabled = enabled) {
            Text(stringResource(R.string.explorer_bulk_show_all, links.size))
        }
    }
    if (showAll) {
        FullListSheet(stringResource(R.string.portfolio_csv_import_transfers_title), onDismiss = { showAll = false }) {
            // Schlüssel: link.key ist je Vorschau eindeutig
            items(links, key = { it.key }) { link -> TransferLinkRow(link, link.key in linked, onLink, enabled) }
        }
    }
}

@Composable
private fun TransferLinkRow(link: CsvTransferLink, on: Boolean, onLink: (String, Boolean) -> Unit, enabled: Boolean) {
    val linkLabel = stringResource(R.string.portfolio_csv_import_transfer_link)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onLink(link.key, it) })
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
        Switch(checked = on, onCheckedChange = null, enabled = enabled, modifier = Modifier.semantics { contentDescription = linkLabel })
    }
}

/** Alle Zeilen einer Liste in einem eigenen Blatt, als Lazy-Liste (nur sichtbare Zeilen gebaut). */
@Composable
private fun FullListSheet(
    title: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}
