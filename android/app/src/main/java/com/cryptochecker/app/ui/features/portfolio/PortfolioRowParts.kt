// Teile einer Positionszeile im Portfolio: Gewinn/Verlust der Zeile und die kleine Zahl der Transaktionen.
// Eigene Datei, weil `CoinRow` sonst zu lang würde.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.ui.theme.label
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.A11yText
import com.cryptochecker.app.util.ChangeBasisText
import com.cryptochecker.app.util.LocaleNumbers
import com.cryptochecker.app.util.PriceFormat

/**
 * MEIN Gewinn/Verlust einer Position über die %-Basis: Zeitraum grau («24h»), daneben «+1’250.00 CHF · +1.50%»
 * (in [unit], [pnl] kommt in USDT)
 * als farbige Schrift nach dem Betrag (Betrag verborgen «•••», das Prozent bleibt); unbekannt grau «—».
 * Screenreader: «up 1’250.00 USDT, up 1.50% in 24 hours».
 */
@Composable
internal fun RowPnl(pnl: PortfolioChange?, basis: ChangeBasis, unit: CardUnit) {
    val context = LocalContext.current
    val hidden = LocalHidePortfolioAmounts.current
    val amount = pnl?.amount?.takeIf { it.isFinite() }?.let(unit::of)
    val percent = pnl?.percent?.takeIf { it.isFinite() && amount != null }
    val spoken = if (amount == null) {
        ChangeBasisText.spoken(context, basis, null)
    } else {
        val spokenAmount = when {
            hidden -> stringResource(R.string.a11y_amount_hidden)
            PortfolioFormat.isZero(amount) -> stringResource(R.string.a11y_change_flat)
            amount > 0 -> stringResource(R.string.a11y_change_up, spokenAmount(PriceFormat.valueWithCurrency(abs(amount), unit.code)))
            else -> stringResource(R.string.a11y_change_down, spokenAmount(PriceFormat.valueWithCurrency(abs(amount), unit.code)))
        }
        val phrase = listOfNotNull(spokenAmount, percent?.let { A11yText.change(context, it) }).joinToString(", ")
        ChangeBasisText.spokenPhrase(context, basis, phrase)
    }
    val text = when {
        amount == null -> "—"
        percent != null -> maskAmount(unit.signed(amount)) + " · " + PortfolioFormat.signedPercent(percent)
        else -> maskAmount(unit.signed(amount))
    }
    // Zeitraum grau wie neben der Änderung der Merkliste
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 3.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        Text(
            ChangeBasisText.shortLabel(context, basis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            color = plColor(amount),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Anzahl Transaktionen eines Coins: nur die Zahl, klein und grau in einer Kapsel. Screenreader:
 * «Transaktionen: 3».
 */
@Composable
internal fun TxCountBadge(count: Int) {
    val spoken = stringResource(R.string.portfolio_transactions) + ": " + LocaleNumbers.integer(count)
    Text(
        LocaleNumbers.integer(count),
        style = MaterialTheme.typography.label.tabularNumbers(),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .padding(start = Spacing.xs)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(horizontal = Spacing.xs, vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = spoken }
    )
}
