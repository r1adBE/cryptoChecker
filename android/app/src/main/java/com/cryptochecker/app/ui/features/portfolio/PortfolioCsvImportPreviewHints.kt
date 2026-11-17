// Hinweise der CSV-Vorschau ([CsvImportPreviewSheet]). Eigene Datei, weil PortfolioCsvImportPreview.kt sonst zu lang
// würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportPreview
import com.cryptochecker.app.domain.portfolio.csvimport.CsvImportSkipReason
import com.cryptochecker.app.domain.portfolio.csvimport.CsvLineList
import com.cryptochecker.resources.R

/**
 * Hinweise der CSV-Vorschau ([CsvImportPreviewSheet]): übersprungene Zeilen, fehlende Tageskurse samt Bestätigung, USDT = 1,
 * fehlende Preise, Gebühren, Kraken-Abweichungen und Tausch gegen Stablecoins. Je Hinweis eine eigene Zeile.
 */
@Composable
internal fun CsvPreviewHints(
    preview: CsvImportPreview,
    ratesAccepted: Boolean,
    onRatesAccepted: (Boolean) -> Unit,
    onReloadRates: () -> Unit,
    rereading: Boolean,
) {
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
        // Mehr als 8 übersprungen: Rest als «+ N weitere» statt still abgeschnitten
        val moreSkipped = CsvLineList.moreAfter(preview.skipped.size, PREVIEW_ROWS)
        if (moreSkipped > 0) {
            Text(
                stringResource(R.string.portfolio_csv_import_more, moreSkipped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    // Je Hinweis eine eigene Zeile: Anzahl, die ersten Zeilennummern und «+ N weitere» (CsvLineList)
    // Ohne Tageskurs fehlten Preis bzw. Gebühr dauerhaft: erst neu laden oder ausdrücklich trotzdem importieren
    if (preview.missingFx.isNotEmpty()) {
        val retry = preview.rateRetry
        // Keine gültige Antwort beim Laden — etwas anderes als «für Währung bzw. Tag gibt es keinen Kurs»; nur wo ein
        // neuer Abruf helfen kann
        if (preview.ratesFailed && retry.retryHelps) {
            PreviewHint(stringResource(R.string.portfolio_csv_import_rates_failed))
        }
        LinesHint(preview.missingFx) { stringResource(R.string.portfolio_csv_import_missing_fx, it) }
        // Währung ohne Tageskurse (z. B. ein Coin als Preiswährung): eigener Satz statt «Erneut versuchen»
        if (retry.currencies.isNotEmpty()) {
            PreviewHint(stringResource(R.string.portfolio_csv_import_no_rates_for, retry.currencies.joinToString(", ")))
        }
        CsvRateGate(
            ratesAccepted,
            onRatesAccepted,
            onReloadRates,
            enabled = !rereading,
            canRetry = retry.retryHelps,
        )
    }
    // Kurstag in der Zeitzone der Steuer-Vorlage (Neutral: Gerät), wie der Steuer-Export
    if (preview.dayRates) PreviewHint(stringResource(R.string.portfolio_csv_import_fx_zone, preview.fxZone))
    // Fiat-Preise ohne USDT/USD-Tagesschluss: mit USDT = 1 umgerechnet (Vermerk wie im Portfolio)
    if (preview.usdtApproximate.isNotEmpty()) {
        LinesHint(preview.usdtApproximate) { stringResource(R.string.portfolio_usdt_assumed) + " ($it)" }
    }
    if (preview.missingPrice.isNotEmpty()) {
        LinesHint(preview.missingPrice) { stringResource(R.string.portfolio_csv_import_missing_price, it) }
    }
    // Fiat-Gebühr im Preis, Gebühr im Coin am Geschäft (statt eigener Gebühr-Zeile)
    if (preview.feeInPrice.isNotEmpty()) {
        LinesHint(preview.feeInPrice) { stringResource(R.string.portfolio_csv_import_fee_in_price, it) }
    }
    // Kraken: ausstehende und gebuchte Zeile mit anderer Menge — beide übernommen, Bestand prüfen
    if (preview.pendingDiffers.isNotEmpty()) {
        PreviewHint(stringResource(R.string.portfolio_csv_import_pending_differs, preview.pendingDiffers.joinToString(", ")))
    }
    // Kauf/Verkauf gegen Stablecoin als Tausch gebucht: der Stablecoin erscheint im Bestand
    if (preview.stableSwaps) PreviewHint(stringResource(R.string.portfolio_csv_import_stable_swaps))
}

/**
 * Ein Hinweis mit Zeilennummern: oben die Anzahl («12 Zeilen»), darunter der Satz mit den ersten Nummern und
 * «+ N weitere» ([CsvLineList]) — TalkBack liest beides als ein Element (wie iOS `.accessibilityElement(children: .combine)`).
 */
@Composable
private fun LinesHint(lines: List<Int>, sentence: @Composable (String) -> String) {
    val list = remember(lines) { CsvLineList.of(lines) }
    val more = if (list.more > 0) " " + stringResource(R.string.portfolio_csv_import_more, list.more) else ""
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            pluralStringResource(R.plurals.portfolio_csv_import_line_count, list.count, list.count),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PreviewHint(sentence(list.shown.joinToString(", ") + more))
    }
}

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
