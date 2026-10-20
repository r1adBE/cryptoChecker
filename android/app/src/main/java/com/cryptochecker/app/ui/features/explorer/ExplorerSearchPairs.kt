// Paare eines gewählten Coins in der Coin-Suche: Kopf mit Zurück, Vorschlag zuerst und die Treffer je Börse.
// Eigene Datei, weil `ExplorerSearchUi` sonst zu lang würde (Suchfeld, Coins und Ergebnisliste bleiben dort).
package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.ALPHA_GLYPH
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.FuturesContractType

/**
 * Paare des angetippten Coins: Kopf mit «Alle Treffer», der Vorschlag als Karte mit einem Knopf
 * (ein Tipp genügt), darunter die übrigen Paare über alle geladenen Listen.
 */
@Composable
internal fun CoinPairs(coin: CoinHit, search: SearchUi, groupTarget: GroupTargetUi, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        TextButton(onClick = { search.onPickCoin(null) }, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.explorer_coin_back), modifier = Modifier.padding(start = Spacing.xs))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Spacing.sm)) {
            CoinBadge(coin.symbol, size = 40.dp, logo = !coin.tradFi, modifier = Modifier.padding(end = Spacing.md))
            Column(modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(coin.name ?: coin.symbol, style = MaterialTheme.typography.titleLarge)
                if (coin.name != null) {
                    Text(coin.symbol, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        val suggested = search.pickedCoinPairs.firstOrNull()
        if (suggested == null) {
            // «Kein Paar» erst, wenn alle Listen gelesen sind; solange etwas lädt oder fehlt, spricht nur der Kasten darunter
            val lists = search.lists
            if (lists.progress == null && !lists.reading && lists.missing == 0) {
                Text(
                    text = stringResource(R.string.explorer_coin_no_pairs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.sm)
                )
            }
            ListsOffer(search.lists, search.onLoadLists)
            return@Column
        }

        SectionLabel(stringResource(R.string.explorer_coin_suggested))
        SuggestedPairCard(suggested, watched = search.isWatched(suggested), onAdd = { search.onAdd(suggested) })
        GroupTargetSelector(groupTarget, modifier = Modifier.padding(top = Spacing.xs))
        ListsOffer(search.lists, search.onLoadLists)

        val others = search.pickedCoinPairs.drop(1)
        if (others.isNotEmpty()) {
            SectionLabel(stringResource(R.string.explorer_coin_other_pairs), Modifier.padding(top = Spacing.sm))
            others.forEach { hit -> PairHitRow(hit, search) }
        }
    }
}

/** Vorschlag zum Coin: Paar, Börse und ein grosser Knopf «Zur Merkliste» (bzw. Häkchen, wenn schon da). */
@Composable
private fun SuggestedPairCard(hit: SearchHit, watched: Boolean, onAdd: () -> Unit) {
    StepCard {
        Text(
            text = "${hit.pair.base}/${hit.pair.quote}" + if (hit.alpha) " $ALPHA_GLYPH" else "",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = pairSubtitle(hit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onAdd,
            enabled = !watched,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp).testTag(UiTestTags.EXPLORER_SUGGESTED_ADD)
        ) {
            Icon(
                painterResource(if (watched) R.drawable.ic_check else R.drawable.ic_add),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(if (watched) R.string.a11y_in_watchlist else R.string.explorer_add_to_watchlist),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Börse und — bei Futures — die Laufzeit. */
@Composable
private fun pairSubtitle(hit: SearchHit): String = listOfNotNull(
    hit.market.name,
    hit.pair.contractType.takeIf { it != FuturesContractType.NONE }?.let { contractName(it) },
).joinToString(" · ")

/** Ein Paar: antippen legt es direkt in die Merkliste; schon da → Häkchen statt «+». */
@Composable
internal fun PairHitRow(hit: SearchHit, search: SearchUi) {
    // Schon in der Merkliste: Häkchen statt «+» (Screenreader: «Schon in der Merkliste»)
    val watched = search.isWatched(hit)
    val action = stringResource(if (watched) R.string.a11y_in_watchlist else R.string.explorer_add_to_watchlist)
    // EIN Fokus je Treffer: die Zeile liest Paar und Börse samt Aktion vor; «+» ist nur Bild (vorher doppelt fokussierbar)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = action) { search.onAdd(hit) }
            .semantics { if (watched) stateDescription = action }
            .padding(start = 16.dp, end = 4.dp, top = Spacing.sm, bottom = Spacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Alpha-Token: «α» hinter dem Paar (wie in der Merkliste)
            Text(
                text = "${hit.pair.base}/${hit.pair.quote}" + if (hit.alpha) " $ALPHA_GLYPH" else "",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = pairSubtitle(hit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (watched) R.drawable.ic_check else R.drawable.ic_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
