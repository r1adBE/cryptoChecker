package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.ALPHA_GLYPH
import com.cryptochecker.app.ui.components.CoinBadge
import com.cryptochecker.app.ui.theme.Spacing

/** Zustand und Aktionen der Coin-Suche. */
class SearchUi(
    val query: String = "",
    /** Coins zum Suchbegriff. */
    val coins: List<CoinHit> = emptyList(),
    /** Einzelne Paare zum Suchbegriff; nur mit [showPairs]. */
    val pairs: List<SearchHit> = emptyList(),
    val showPairs: Boolean = false,
    /** Angetippter Coin und seine Paare (Vorschlag zuerst). */
    val pickedCoin: CoinHit? = null,
    val pickedCoinPairs: List<SearchHit> = emptyList(),
    val lists: SearchLists = SearchLists(),
    /** Schon in der Merkliste (Schlüssel siehe [watchKey]). */
    val watched: Set<String> = emptySet(),
    val onQueryChange: (String) -> Unit = {},
    val onPickCoin: (CoinHit?) -> Unit = {},
    val onAdd: (SearchHit) -> Unit = {},
    /** Fehlende Paarlisten laden (vollständige öffentliche Listen). */
    val onLoadLists: () -> Unit = {},
    /** «Zuletzt gesucht», neueste zuerst (höchstens 8). */
    val recent: List<String> = emptyList(),
    /** Taste «Suchen»: Begriff für «Zuletzt gesucht» merken. */
    val onConfirm: () -> Unit = {},
    val onClearRecent: () -> Unit = {},
) {
    fun isWatched(hit: SearchHit): Boolean = watchKey(
        hit.market.key, hit.pair.base, hit.pair.quote, hit.pair.contractType.name
    ) in watched
}

/** Gleiche Felder wie die Dublettenprüfung beim Hinzufügen (WatchRepository.addWatch). */
fun watchKey(marketKey: String, base: String, quote: String, contractType: String): String =
    "$marketKey|$base|$quote|$contractType"

/**
 * Suchfeld oben: «Bitcoin», «btc», «ETH USDT». Findet zuerst Coins, auf Wunsch einzelne Paare;
 * alles aus den Listen auf dem Gerät.
 */
@Composable
internal fun SearchField(search: SearchUi, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = search.query,
        onValueChange = search.onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.explorer_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (search.query.isNotEmpty()) {
                IconButton(onClick = { search.onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(onSearch = { search.onConfirm() }),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(UiTestTags.EXPLORER_SEARCH)
    )
}

/**
 * «Zuletzt gesucht» unter dem leeren Suchfeld: antippbare Chips (Screenreader «Zuletzt gesucht:
 * BTC»), rechts «Löschen». Ein Tipp setzt den Begriff ins Suchfeld — gesucht wird wie bei der
 * Eingabe (auch mit «Token & TradFi»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentSearchChips(search: SearchUi, modifier: Modifier = Modifier) {
    if (search.recent.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.explorer_recent_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }
            )
            val clearLabel = stringResource(R.string.explorer_recent_clear_a11y)
            TextButton(
                onClick = search.onClearRecent,
                modifier = Modifier.semantics { contentDescription = clearLabel }
            ) {
                Text(stringResource(R.string.explorer_recent_clear))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            search.recent.forEach { query ->
                val spoken = stringResource(R.string.explorer_recent_a11y, query)
                SuggestionChip(
                    onClick = { search.onQueryChange(query) },
                    label = { Text(query, maxLines = 1) },
                    modifier = Modifier.semantics { contentDescription = spoken }
                )
            }
        }
    }
}

/**
 * Treffer der Suche: Coins (antippen zeigt ihre Paare), bei gezielter Suche auch einzelne Paare, und das
 * Angebot, fehlende Paarlisten zu laden. Als normale Spalte, damit alles mit der Seite scrollt.
 */
@Composable
internal fun SearchResults(
    search: SearchUi,
    groupTarget: GroupTargetUi,
    modifier: Modifier = Modifier,
    /** Kein Treffer: Suche leeren und «Erweitert / nach Börse» aufklappen (Börse und Paar selbst wählen). */
    onChooseManually: (() -> Unit)? = null,
) {
    val pairs = if (search.showPairs) search.pairs else emptyList()
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        ListsOffer(search.lists, search.onLoadLists)
        val busy = search.lists.reading || search.lists.progress != null
        if (search.coins.isEmpty() && pairs.isEmpty() && !busy) {
            Text(
                text = stringResource(R.string.explorer_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = if (onChooseManually != null) 0.dp else 16.dp)
            )
            // Der Weg nach Börse bleibt erreichbar, ohne erst das Suchfeld zu leeren
            if (onChooseManually != null) {
                TextButton(onClick = onChooseManually) {
                    Text(stringResource(R.string.explorer_search_choose_manually))
                }
            }
        }
        if (search.coins.isNotEmpty()) {
            SectionLabel(stringResource(R.string.explorer_coins_title))
            search.coins.forEach { coin -> CoinRow(coin, onClick = { search.onPickCoin(coin) }) }
        }
        if (pairs.isNotEmpty()) {
            SectionLabel(stringResource(R.string.explorer_pairs_title), Modifier.padding(top = Spacing.sm))
            // Ziel-Gruppe gilt für jeden angetippten Treffer
            GroupTargetSelector(groupTarget, modifier = Modifier.padding(bottom = 2.dp))
            pairs.forEach { hit -> PairHitRow(hit, search) }
        }
    }
}

/** Kleine Überschrift über einem Abschnitt der Treffer. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = Spacing.xs, bottom = 2.dp).semantics { heading() }
    )
}

/**
 * Hinweis auf fehlende Paarlisten mit «Listen laden» — und, solange sie laden, der Fortschritt. Datenschutz:
 * geladen wird nur auf Tippen, je Börse die ganze öffentliche Liste (für alle gleich).
 */
@Composable
internal fun ListsOffer(lists: SearchLists, onLoad: () -> Unit) {
    val progress = lists.progress
    when {
        progress != null -> Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.done / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
            )
            Text(
                text = stringResource(R.string.explorer_lists_loading, progress.done, progress.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        lists.reading -> Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
            Text(
                text = stringResource(R.string.explorer_loading_pairs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
        }
        lists.missing > 0 -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 16.dp, end = 8.dp, top = Spacing.md, bottom = Spacing.xs)
        ) {
            Text(
                text = pluralStringResource(R.plurals.explorer_lists_missing, lists.missing, lists.missing),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.explorer_lists_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, end = 8.dp)
            )
            TextButton(onClick = onLoad, modifier = Modifier.align(Alignment.End).testTag(UiTestTags.EXPLORER_LISTS_LOAD)) {
                Text(stringResource(R.string.explorer_lists_load))
            }
        }
    }
}

/** Ein Coin: Plakette, Name und Kürzel, darunter an wie vielen Börsen (oder «noch nicht geladen»). */
@Composable
private fun CoinRow(coin: CoinHit, onClick: () -> Unit) {
    val title = coin.name ?: coin.symbol
    val subtitle = listOfNotNull(
        coin.symbol.takeIf { coin.name != null },
        if (coin.exchanges > 0) pluralStringResource(R.plurals.explorer_coin_exchanges, coin.exchanges, coin.exchanges)
        else stringResource(R.string.explorer_coin_not_loaded),
    ).joinToString(" · ")
    val showPairs = stringResource(R.string.explorer_coin_show_pairs)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag(UiTestTags.explorerCoin(coin.symbol, coin.tradFi))
            .clickable(onClickLabel = showPairs, onClick = onClick)
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm)
    ) {
        CoinBadge(coin.symbol, size = 36.dp, logo = !coin.tradFi, modifier = Modifier.padding(end = Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            // Alpha-Token: «α» hinter dem Namen (wie in der Merkliste)
            Text(
                text = title + if (coin.alpha) " $ALPHA_GLYPH" else "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Pfeil wie bei anderen Zeilen, die weiterführen; rein dekorativ (die Zeile sagt «Paare zeigen»)
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
