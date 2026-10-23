// Block «Hinweise» unter der Gesamtkarte (Kursquellen, fehlende Coins, ungeprüfte Kurse …).
// Eigene Datei, weil die Karte selbst nur Beträge zeigt (Regel «Gesamtkarte ist ein Bericht») und
// alle Hinweise hier gesammelt werden.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.alarm.DepegAlarm
import com.cryptochecker.app.domain.portfolio.PortfolioCostFxFigures
import com.cryptochecker.app.domain.portfolio.PortfolioSummary
import com.cryptochecker.app.domain.portfolio.TodayChange
import com.cryptochecker.app.ui.components.ExpandChevronIcon
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.util.PriceFormat

internal data class TotalHintItem(val text: String, val error: Boolean = false, val txId: Long? = null)

/**
 * Bereich «Hinweise» unter der Gesamtkarte (L-15), zugeklappt: eine Zeile «Hinweise (n)» mit
 * Pfeil (48 dp hoch, Fehlerfarbe, wenn eine Zeile ein Fehler ist — veraltet, Wallet-Bestand); offen die
 * Zeilen ([TotalHint]) eng untereinander, eingerückt wie der Inhalt der Karte. Wie iOS `hints`.
 */
@Composable
internal fun TotalCardHints(hints: List<TotalHintItem>, expanded: Boolean, onToggle: () -> Unit, onShortfall: (Long) -> Unit) {
    if (hints.isEmpty()) return
    val anyError = hints.any { it.error }
    val expandedText = stringResource(R.string.a11y_expanded)
    val collapsedText = stringResource(R.string.a11y_collapsed)
    Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) expandedText else collapsedText }
        ) {
            Text(
                stringResource(R.string.portfolio_card_hints_count, hints.size),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (anyError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false)
            )
            ExpandChevronIcon(expanded = expanded, size = 16.dp, modifier = Modifier.padding(start = 2.dp))
        }
        if (expanded) {
            hints.forEach { hint ->
                TotalHint(
                    hint.text,
                    color = if (hint.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = hint.txId?.let { id -> Modifier.clickable { onShortfall(id) } } ?: Modifier,
                )
            }
        }
    }
}

/**
 * Eine Zeile im Bereich «Hinweise»: Lesegrösse ([body], 14 sp — ganze Sätze, aufgeklappt, mit Platz),
 * onSurfaceVariant bzw. [color]. Wie iOS `hint` (`.subheadline`).
 */
@Composable
private fun TotalHint(
    text: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.body,
        color = color,
        modifier = Modifier.padding(top = 2.dp).then(modifier)
    )
}

/**
 * Zeilen unter der Gesamtkarte («Hinweise (n)», [TotalCardHints]): fehlender Devisenkurs, Kursquelle, Einstandsmethode,
 * Einstand zum Kurs der Kauftage, fehlende Kaufpreise und Kurse, Börsen, skalierte Verläufe, FIFO je Wallet, offene
 * Gebühren und Stand der Kurse.
 */
@Composable
internal fun totalHintItems(
    summary: PortfolioSummary,
    updatedAt: Long,
    currency: String,
    fxRate: Double?,
    costFx: PortfolioCostFxFigures?,
    todaySkipped: List<String>,
    weekChange: TodayChange?,
    yearChange: TodayChange?,
    stale: Boolean,
    exchangeFailed: Map<String, String>,
    mixedExchange: Set<String>,
    periodScaled: List<String>,
    walletShortfalls: List<com.cryptochecker.app.domain.portfolio.PortfolioWalletShortfall>,
    usdtNote: DepegAlarm.CardNote?,
    priceSourceText: String,
): List<TotalHintItem> = buildList {
    // Ohne Devisenkurs: Beträge in USDT statt in der Anzeigewährung
    if (currency != "USD" && !cardUnit(currency, fxRate).converted) {
        add(TotalHintItem(stringResource(R.string.portfolio_total_no_fx, currency)))
    }
    // Kursquelle: steht sie schon auf der Karte, nicht noch einmal unter «Hinweise»
    if (usdtNote?.kind != DepegAlarm.CardNoteKind.PRICE_SOURCE) add(TotalHintItem(priceSourceText))
    // G-9: Modell nahe an der Zahl, nicht nur im (i) — sonst wirkt die Abweichung zur Börse wie ein Fehler
    // Ø ist eine bewusste Wahl in den Einstellungen: kein Hinweis «nicht FIFO» mehr
    if (summary.unrealized != null && summary.method.isFifo) {
        add(TotalHintItem(stringResource(R.string.ledger_fifo_cost_note)))
    }
    // Umgerechnet: Einstand zum Devisenkurs des Kauftags; ohne Kurs eines Kauftags fehlt der Coin, USDT/USD-Lücke = 1
    if (cardUnit(currency, fxRate).converted && summary.unrealized != null) {
        add(TotalHintItem(stringResource(R.string.ledger_cost_fx_note, cardUnit(currency, fxRate).code)))
        costFx?.fxMissingCoins?.takeIf { it.isNotEmpty() }?.let { coins ->
            add(TotalHintItem(stringResource(R.string.ledger_cost_fx_missing, coins.joinToString(", "))))
        }
        if (costFx?.usdtApproximate == true) add(TotalHintItem(stringResource(R.string.ledger_cost_fx_usdt_assumed)))
    }
    // Was im Gewinn seit Kauf fehlt (M3: nur diese Coins, nicht das ganze Portfolio)
    if (summary.missingCostCoins.isNotEmpty()) {
        add(TotalHintItem(
            pluralStringResource(
                R.plurals.ledger_without_cost,
                summary.missingCostCoins.size,
                summary.missingCostCoins.size,
                summary.missingCostCoins.joinToString(", ")
            ),
            // Antippen: Einträge ohne Einstand
            txId = COST_GAPS_HINT_ID,
        ))
    }
    // Nur ein Teil ohne Kaufpreis: «Seit Kauf» zählt den Teil mit Kaufpreis, der Rest fehlt
    if (summary.partialCostParts.isNotEmpty()) {
        add(TotalHintItem(
            stringResource(
                R.string.ledger_partial_cost,
                summary.partialCostParts.map { maskAmount(PortfolioFormat.amount(it.amount, it.coin)) }.joinToString(", ")
            ),
            txId = COST_GAPS_HINT_ID,
        ))
    }
    // Heute / 7 T / 1 J: Coins ohne passenden Kurs am Anfang fehlen nur dort, die übrigen zählen
    val periodSkipped = (todaySkipped + weekChange?.skipped.orEmpty() + yearChange?.skipped.orEmpty()).distinct().sorted()
    if (periodSkipped.isNotEmpty()) {
        add(TotalHintItem(stringResource(R.string.ledger_period_without, periodSkipped.joinToString(", "))))
    }
    // Eingetragene Börse ohne Kurs bzw. mehrere Börsen bei einem Coin: mit Binance bewertet
    exchangeFailed.entries.groupBy({ it.value }, { it.key }).toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (exchange, coins) ->
        add(TotalHintItem(stringResource(R.string.ledger_exchange_failed, coins.sorted().joinToString(", "), exchange)))
    }
    if (mixedExchange.isNotEmpty()) {
        add(TotalHintItem(stringResource(R.string.ledger_exchange_mixed, mixedExchange.sorted().joinToString(", "))))
    }
    // Keine eigenen Tageskerzen der Börse: «7 T»/«1 J» aus Binance, auf den Börsenkurs skaliert
    if (periodScaled.isNotEmpty()) {
        add(TotalHintItem(stringResource(R.string.portfolio_period_scaled, periodScaled.joinToString(", "))))
    }
    // FIFO je Wallet: je Coin und Wallet der erste Abgang über den Bestand — antippen öffnet die Transaktion
    walletShortfalls.distinctBy { it.coin to it.wallet.trim().lowercase() }.forEach { s ->
        val wallet = s.wallet.ifBlank { stringResource(R.string.tax_export_wallet_unspecified) }
        val text = stringResource(R.string.label_value, s.coin, stringResource(R.string.portfolio_wallet_shortfall, wallet))
        add(TotalHintItem(text, error = true, txId = s.txId))
    }
    // Umbuchungsgebühren auf Bestand ohne Kaufpreis: offener Posten (je Coin auf der Detailseite)
    if (summary.unassignedFeeUsdt.signum() != 0) {
        add(TotalHintItem(stringResource(
            R.string.portfolio_unassigned_fee,
            maskAmount(PortfolioFormat.usdt(summary.unassignedFeeUsdt.toDouble())),
        )))
    }
    if (summary.missingCurrentPrices.isNotEmpty()) {
        add(TotalHintItem(stringResource(R.string.portfolio_current_missing, summary.missingCurrentPrices.joinToString(", "))))
    }
    if (updatedAt > 0) {
        val asOf = stringResource(R.string.portfolio_updated, if (stale) portfolioStampTime(updatedAt) else PriceFormat.time(updatedAt))
        add(TotalHintItem(if (stale) stringResource(R.string.watchlist_row_outdated, asOf) else asOf, error = stale))
    }
}
