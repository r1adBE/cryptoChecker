@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cryptochecker.app.domain.portfolio.CoinPosition
import com.cryptochecker.app.domain.portfolio.PortfolioChange
import com.cryptochecker.app.domain.portfolio.PortfolioWidgetMath
import com.cryptochecker.app.domain.util.BidiText
import com.cryptochecker.app.domain.watch.ChangeBasis
import com.cryptochecker.app.ui.components.withSmallQuote
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.amountNumbers
import com.cryptochecker.app.ui.theme.label
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.resources.R
import java.util.Locale

// Teile der Coin-Zeile im Portfolio ([CoinRow]): rechte Spalte und Mitte. Eigene Datei, weil PortfolioRows.kt sonst zu
// lang würde.

/** Rechte Spalte von [CoinRow]: Wert, Hinweis zum Kurs, «seit Kauf» und Gewinn/Verlust der %-Basis. */
@Composable
internal fun CoinRowTrailing(
    position: CoinPosition,
    unit: CardUnit,
    stale: Boolean,
    unchecked: Boolean,
    noMarket: Boolean,
    unavailable: Boolean,
    viaBase: String?,
    bridgedStable: String?,
    basis: ChangeBasis?,
    pnl: PortfolioChange?,
    columnModifier: Modifier,
) {
    Column(horizontalAlignment = Alignment.End, modifier = columnModifier) {
        Text(
            withSmallQuote(position.value?.let { maskAmount(PriceFormat.valueWithCurrency(unit.of(it), unit.code)) } ?: "—"),
            style = MaterialTheme.typography.titleSmall.amountNumbers(),
            fontWeight = FontWeight.SemiBold,
            // Veraltet: Sekundärfarbe wie in der Merkliste statt halber Deckkraft (Kontrast)
            color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (unchecked) {
            Text(
                stringResource(R.string.portfolio_price_unchecked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        } else if (noMarket && position.value == null) {
            Text(
                stringResource(R.string.portfolio_price_no_market),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        } else if (unavailable && position.value == null) {
            // Fehler statt «kein Kurs»: die Quelle antwortet gerade nicht
            Text(
                stringResource(R.string.portfolio_price_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        } else if (viaBase != null) {
            // Kein eigener Kurs: Wert mit dem Kurs der Basis — nie als eigener Kurs ausgeben
            Text(
                stringResource(R.string.portfolio_price_via_base, viaBase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        } else if (bridgedStable != null) {
            // Gebrückte Stablecoin-Variante mitgezählt: Menge nur ungefähr, wie WBTC markiert
            Text(
                stringResource(R.string.portfolio_amount_bridged_stable, bridgedStable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        // «Seit Kauf» des Teils mit Kaufpreis; fehlt bei einem Teil der Kaufpreis, darunter «Preis fehlt»
        if (!position.priceMissing || position.unrealizedPartial) {
            PlPill(position.unrealizedPercent, modifier = Modifier.padding(top = 3.dp))
        }
        if (position.priceMissing) {
            Text(
                stringResource(R.string.portfolio_price_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (basis != null) {
            RowPnl(pnl, basis, unit)
        }
    }
}

/**
 * Mitte von [CoinRow]: Menge und Symbol, Anteil mit Anzahl Transaktionen, Börse und «veraltet», darunter Ø und aktuell
 * (in deren Spalte).
 */
@Composable
internal fun CoinRowMain(
    position: CoinPosition,
    sharePercent: Double?,
    txCount: Int,
    exchange: String?,
    stale: Boolean,
    locale: Locale,
    lines: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Menge klein und grau vor dem Symbol («1.3 BTC»); grosse Schrift: Menge wird schrittweise kleiner
        // statt gekürzt (eine abgeschnittene Zahl wäre eine falsche Zahl, wie iOS), das Symbol bleibt
        val amountStyle = MaterialTheme.typography.bodySmall.amountNumbers()
        BasicText(
            maskAmount(PriceFormat.amount(position.holdingsExact)),
            style = amountStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            // Passt sie auch klein nicht: sichtbar gekürzt («…») wie iOS, nie still abgeschnitten
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(
                minFontSize = amountStyle.fontSize * 0.6f,
                maxFontSize = amountStyle.fontSize,
                stepSize = 0.5.sp,
            ),
            modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp)
        )
        Text(
            position.coin,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = lines
        )
        if (position.oversold) {
            Icon(
                painterResource(R.drawable.ic_error),
                contentDescription = stringResource(R.string.portfolio_oversold),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = Spacing.xs).size(14.dp)
            )
        }
    }
    // Anteil am Gesamtwert, daneben die Anzahl Transaktionen als kleine Zahl («88.9%  3»);
    // Dahinter die Börse des Kurses («· Kraken», nur mit eigener Börse) und nur bei
    // veraltetem Kurs «veraltet» in Fehlerfarbe (wie die Merkliste). Fehlt Platz, kürzt
    // zuerst die Börse, dann der Anteil; Zahl und «veraltet» bleiben ganz.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            sharePercent?.takeIf { it.isFinite() }?.let { PortfolioWidgetMath.shareText(it, locale) } ?: "—",
            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (txCount > 0) TxCountBadge(txCount)
        exchange?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                " · " + BidiText.isolate(name, locale),
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (stale) {
            Text(
                " · " + stringResource(R.string.widget_outdated),
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
    // «Ø … · aktuell …»; passt das nicht, fällt zuerst «aktuell …» weg (Ø bleibt, wie iOS ViewThatFits)
    val avgText =
        stringResource(
            R.string.portfolio_avg_and_now,
            PriceFormat.price(position.avgCost),
            PriceFormat.price(position.currentPrice)
        )
    var avgOnly by remember(avgText) { mutableStateOf(false) }
    Text(
        if (avgOnly) stringResource(R.string.portfolio_avg_only, PriceFormat.price(position.avgCost)) else avgText,
        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!avgOnly && it.hasVisualOverflow) avgOnly = true },
    )
    // Ø nur über den Teil mit Kaufpreis: benennen, über welche Menge («Ø von 0.5 BTC mit Kaufpreis»)
    if (position.avgCostPartial) {
        Text(
            stringResource(
                R.string.portfolio_avg_partial,
                maskAmount(PortfolioFormat.amount(position.pricedHoldingsExact, position.coin))
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
