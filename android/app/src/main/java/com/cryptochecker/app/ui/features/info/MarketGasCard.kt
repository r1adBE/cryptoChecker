package com.cryptochecker.app.ui.features.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.market.DataStamp
import com.cryptochecker.app.domain.market.GasFees
import com.cryptochecker.app.domain.market.GasNetwork
import com.cryptochecker.app.domain.market.GasReport
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.tabularNumbers
import com.cryptochecker.app.util.LocaleNumbers

// ───────────────────────── Netzwerkgebühren (#167) ─────────────────────────

/**
 * Netzwerkgebühren als Zeile unter «Daten»: rechts die normale Ethereum-Gebühr, darunter
 * Bitcoin. Tippen klappt alle Netze (Ethereum und Bitcoin mit langsam/normal/schnell, die
 * L2-/Seitennetze mit der normalen Gebühr, rechts die Kosten einer einfachen Überweisung),
 * den Hinweis auf aktive Gas-Alarme und die Quelle auf.
 */
@Composable
internal fun GasSummaryRow(
    state: LoadState<GasReport>,
    ethAlertGwei: Double,
    btcAlertSat: Int,
    onRetry: () -> Unit,
    divider: Boolean = true,
    /** Herkunft (Ethereum-Knoten, mempool.space) und Stand für die Nebenzeile. */
    stamp: DataStamp? = null,
) {
    // Aktive Gas-Alarme stehen in den Einstellungen fest
    val alerts = listOfNotNull(
        ethAlertGwei.takeIf { it > 0 }?.let { "Ethereum < ${GasFees.formatGwei(it)} gwei" },
        btcAlertSat.takeIf { it > 0 }?.let { "Bitcoin < ${LocaleNumbers.integer(it)} sat/vB" },
    )
    val report = (state as? LoadState.Loaded)?.value
    val eth = report?.evm?.firstOrNull { it.network == GasNetwork.ETHEREUM }
    val btcText = report?.btc?.let { "Bitcoin ${GasFees.formatGwei(it.normal)} sat/vB" }
    var expanded by rememberSaveable { mutableStateOf(false) }
    MarketRow(
        title = stringResource(R.string.gas_title),
        secondary = listOfNotNull(eth?.network?.title, btcText).joinToString(" · "),
        value = eth?.let { "${GasFees.formatGwei(it.normalGwei)} gwei" }
            ?: report?.btc?.let { "${GasFees.formatGwei(it.normal)} sat/vB" },
        divider = divider,
        loading = state is LoadState.Loading,
        failure = if (state is LoadState.Failed) stringResource(R.string.something_went_wrong) else null,
        onRetry = onRetry,
        stamp = stamp,
        expanded = expanded,
        onToggle = if (report != null) ({ expanded = !expanded }) else null,
    ) {
        if (report != null) {
            report.evm.forEach { gas ->
                GasNetworkRow(
                    name = gas.network.title,
                    value = GasFees.formatGwei(gas.normalGwei),
                    unit = "gwei",
                    cost = gas.transferUsd,
                    detail = if (gas.network == GasNetwork.ETHEREUM && gas.fastGwei > gas.slowGwei) stringResource(
                        R.string.gas_slow_fast,
                        GasFees.formatGwei(gas.slowGwei),
                        GasFees.formatGwei(gas.fastGwei)
                    ) else null
                )
            }
            report.btc?.let { btc ->
                GasNetworkRow(
                    name = "Bitcoin",
                    value = GasFees.formatGwei(btc.normal),
                    unit = "sat/vB",
                    cost = btc.transferUsd,
                    detail = if (btc.fast > btc.slow) stringResource(
                        R.string.gas_slow_fast,
                        GasFees.formatGwei(btc.slow),
                        GasFees.formatGwei(btc.fast)
                    ) else null
                )
            }
            GasAlertText(alerts)
            SourceText(stringResource(R.string.gas_source))
        }
    }
}

@Composable
private fun GasAlertText(alerts: List<String>) {
    if (alerts.isNotEmpty()) {
        Text(
            text = stringResource(R.string.gas_alert_active, alerts.joinToString(" · ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun GasNetworkRow(name: String, value: String, unit: String, cost: Double?, detail: String?) {
    // Netz, Gebühr und Kosten als ein Element für den Screenreader
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics(mergeDescendants = true) { }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "$value $unit",
                style = MaterialTheme.typography.bodyLarge.tabularNumbers(),
                fontWeight = FontWeight.SemiBold
            )
            cost?.let {
                Text(
                    stringResource(R.string.gas_transfer_cost, GasFees.formatUsd(it)),
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
