// Einstand im Erfassen-Blatt eines Zugangs ohne Preis: Vorschlag Tagesschluss zum Übernehmen bzw. Hinweis
// «Wert bei Zufluss» bei Belohnungen. Eigene Datei, damit das Blatt nur den Aufruf trägt.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.CostGapKind
import com.cryptochecker.app.domain.portfolio.PortfolioCostGaps
import com.cryptochecker.app.domain.portfolio.PortfolioTrade
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.util.PriceFormat
import java.math.BigDecimal

/**
 * Bearbeiten eines gespeicherten Zugangs ohne Preis (Einzahlung, Kauf, auch aus einem CSV-Import): unter dem Preisfeld
 * «Vorschlag: Tagesschluss <Datum>: …» mit «Übernehmen» — setzt den geprüften Tagesschluss als Preis je Coin (markiert,
 * änderbar); gespeichert erst mit «Speichern». Belohnung ohne Preis: nur der Hinweis «Einstand = Wert bei Zufluss
 * (Einkommen)» (AT ohne Zins: Anschaffungskosten 0) — kein Vorschlag, den Einstand zu ändern. Eingabe in der
 * Anzeigewährung: kein USDT-Vorschlag im Feld.
 */
@Composable
internal fun TxCostSuggestion(state: TxFormState, coin: String, coinValid: Boolean, amount: BigDecimal?, viewModel: PortfolioViewModel) {
    if (!state.isEdit || state.initial.priceUsdt != null || state.priceText.isNotBlank() || !coinValid) return
    val template = rememberTaxTemplate(viewModel)
    val probe = PortfolioTrade(state.initial.id, coin, state.type, amount ?: BigDecimal.ONE, null, state.time,
        interestIncome = state.interest)
    if (!PortfolioCostGaps.isGap(probe)) return
    val kind = PortfolioCostGaps.kind(probe, template)
    if (kind != CostGapKind.EDITABLE) {
        Text(
            stringResource(if (kind == CostGapKind.INCOME_ZERO) R.string.cost_gap_income_zero else R.string.cost_gap_income),
            style = MaterialTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs)
        )
        return
    }
    if (state.entryFiat || state.type == PortfolioTxType.REWARD) return
    var close by remember(coin, state.time) { mutableStateOf<BigDecimal?>(null) }
    LaunchedEffect(coin, state.time) { close = viewModel.historicClose(coin, localDate(state.time)) }
    val price = close ?: return
    val day = PortfolioFormat.date(state.time)
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        val value = amount?.let { PortfolioCostGaps.suggestion(probe, price) }
        Text(
            stringResource(
                R.string.cost_gap_suggestion, day,
                maskAmount(value?.let { PortfolioFormat.usdt(it.toDouble()) } ?: PortfolioFormat.price(price.toDouble())),
            ),
            style = MaterialTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = {
            state.priceText = PriceFormat.amountForInput(price, state.decimalSeparator)
            state.priceHint = price.toDouble()
            state.priceAuto = false
            state.priceHistoricDay = day
        }) { Text(stringResource(R.string.cost_gap_apply), fontWeight = FontWeight.SemiBold) }
    }
}
