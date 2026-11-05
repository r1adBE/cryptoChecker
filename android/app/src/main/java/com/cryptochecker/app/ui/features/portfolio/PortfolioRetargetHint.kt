// Umbuchungs-Hinweis ([AddressWithdrawalMatch]) mit seiner einzigen Aktion «Ziel ändern» samt Rückfrage — an Adresszeile
// und Coin-Karte gleich. Spiegel: `PortfolioRetargetHint.swift`.
package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.AddressWithdrawalMatch
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioInsights
import com.cryptochecker.app.ui.components.DialogText

/**
 * «Gleiche Menge wie deine Umbuchung nach «Ledger» …» und daneben «Ziel ändern»: erst die Rückfrage
 * («Umbuchung 1 BTC: Ziel «Ledger» → «Cold»?», Ändern / Abbrechen), dann [onRetarget] mit der genannten Umbuchung.
 * [hideAmounts]: Menge in der Rückfrage als «•••».
 */
@Composable
internal fun RetargetHint(
    retarget: AddressWithdrawalMatch.Retarget,
    style: TextStyle,
    hideAmounts: Boolean,
    onRetarget: (AddressWithdrawalMatch.Retarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ask by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            stringResource(R.string.portfolio_address_withdrawal_match, retarget.from),
            style = style,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { ask = true }) {
            Text(stringResource(R.string.portfolio_address_retarget), style = MaterialTheme.typography.labelLarge)
        }
    }
    if (ask) {
        val t = retarget.trade
        val amount = PortfolioInsights.mask(PortfolioFormat.amount(t.amount, PortfolioCalculator.normalizeCoin(t.coin)), hideAmounts)
        AlertDialog(
            onDismissRequest = { ask = false },
            text = {
                DialogText { Text(stringResource(R.string.portfolio_address_retarget_ask, amount, retarget.from, retarget.to)) }
            },
            confirmButton = {
                TextButton(onClick = { ask = false; onRetarget(retarget) }) {
                    Text(stringResource(R.string.portfolio_address_retarget_confirm), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { ask = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
