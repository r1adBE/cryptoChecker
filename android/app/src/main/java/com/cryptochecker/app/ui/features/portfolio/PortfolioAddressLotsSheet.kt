@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cryptochecker.app.ui.features.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptochecker.app.R
import com.cryptochecker.app.domain.portfolio.AddressCostLot
import com.cryptochecker.app.domain.portfolio.AddressCostLots
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.ui.theme.DisabledColors
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.app.ui.theme.body
import com.cryptochecker.app.ui.theme.label

/**
 * «Einstand für Adressguthaben» einer beobachteten Adresse ([addressId]): je Coin, wie viel des Guthabens
 * Posten mit Einstand hat (Rest «ohne Einstand»), Hinweis, wenn die Posten das Guthaben übersteigen (älteste zuerst
 * verbraucht, [AddressCostLots.cover]); Liste der Posten (antippen = bearbeiten) und «Posten hinzufügen». Die Posten
 * zählen wie Käufe in dieser Adresse ([AddressCostLots]).
 */
@Composable
internal fun PortfolioAddressLotsSheet(viewModel: PortfolioViewModel, addressId: Long, onDismiss: () -> Unit) {
    val addresses by viewModel.watchedAddresses.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val a = addresses.firstOrNull { it.id == addressId } ?: return
    // Abdeckung wie im Rechner: erfasste Bewegungen dieser Wallet stecken schon im Guthaben
    val coverage = remember(a, addresses, transactions) {
        AddressCostLots.coverage(a, transactions.orEmpty().map { it.toTrade() }, addresses)
    }
    val lots = remember(a) { AddressCostLots.clean(a.costLots) }
    // Bearbeiten: Id des Postens (0 = neuer, noch nicht gespeichert). Saveable: Drehen, Falten und Dunkelmodus
    // schliessen den Editor nicht; seine Felder sichert er selbst
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val coins = remember(a) { (listOf(a.chain.coin) + AddressCostLots.balances(a).keys).distinct() }
    // Vorbelegung eines neuen Postens (Datum = Öffnen des Blatts; im Editor änderbar)
    val blank = remember(a.id) { AddressCostLot(0, a.chain.coin, System.currentTimeMillis(), java.math.BigDecimal.ZERO) }

    editingId?.let { editId ->
        val draft = if (editId == 0L) blank else lots.firstOrNull { it.id == editId }
        if (draft != null) {
            key(editId) {
                AddressLotEditor(
                    initial = draft,
                    coins = coins,
                    viewModel = viewModel,
                    onSave = { lot ->
                        val id = if (draft.id == 0L) AddressCostLots.nextId(a.costLots) else draft.id
                        viewModel.setAddressCostLots(a.id, a.costLots.filter { it.id != id } + lot.copy(id = id))
                        editingId = null
                    },
                    // Der Editor fragt vor dem Löschen nach («Posten löschen?»)
                    onDelete = if (draft.id == 0L) null else ({
                        viewModel.setAddressCostLots(a.id, a.costLots.filter { it.id != draft.id })
                        editingId = null
                    }),
                    onDismiss = { editingId = null },
                )
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "head") { LotsHeader(a) }
            items(coverage, key = { "c:${it.coin}" }) { c ->
                Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
                    Text(
                        stringResource(
                            R.string.address_lots_status,
                            maskAmount(PortfolioFormat.amount(c.lotted, c.coin)), maskAmount(PortfolioFormat.amount(c.base, c.coin)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (c.unlotted.signum() > 0) {
                        Text(
                            stringResource(R.string.address_lots_unlotted, maskAmount(PortfolioFormat.amount(c.unlotted, c.coin))),
                            style = MaterialTheme.typography.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        a.lastBalance == null -> Text(
                            stringResource(R.string.address_lots_not_loaded),
                            style = MaterialTheme.typography.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        c.capped -> Text(
                            stringResource(R.string.address_lots_capped, maskAmount(PortfolioFormat.amount(c.excess, c.coin))),
                            style = MaterialTheme.typography.body,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            items(lots, key = { "l:${it.id}" }) { lot ->
                HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.xl))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { editingId = lot.id }
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                ) {
                    Text(PortfolioFormat.date(lot.time), style = MaterialTheme.typography.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        maskAmount(PortfolioFormat.amount(lot.amount, lot.coin)) + " · " +
                            maskAmount(lot.cost?.let { PortfolioFormat.usdt(it.toDouble()) } ?: "—"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            item(key = "add") {
                Button(
                    onClick = { editingId = 0L },
                    enabled = a.costLots.size < AddressCostLots.MAX_LOTS,
                    colors = DisabledColors.button(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                ) { Text(stringResource(R.string.address_lots_add)) }
            }
        }
    }
}

/** Titel, Adresse und kurze Erklärung. */
@Composable
private fun LotsHeader(a: WatchedAddress) {
    Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
        Text(stringResource(R.string.address_lots_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            a.label ?: WatchedAddressRules.shortAddress(a.address),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.address_lots_intro),
            style = MaterialTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)
        )
    }
}
