// Übersicht von «Paar hinzufügen» ohne Suchbegriff: Markt-Chips, «Zuletzt genutzte Börsen» und «Alle Börsen». Eigene Datei, weil `ExplorerScreen` nur anordnet (wie `ExplorerBrowseUi.swift`).
package com.cryptochecker.app.ui.features.explorer

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.FavoriteKind
import com.cryptochecker.app.domain.model.ExchangeInfo
import com.cryptochecker.app.ui.UiTestTags
import com.cryptochecker.app.ui.components.referencePeriodText
import com.cryptochecker.app.ui.components.referenceSourceName
import com.cryptochecker.app.ui.features.watchlist.MinTouchTarget
import com.cryptochecker.app.ui.features.watchlist.fadeEnd
import com.cryptochecker.app.ui.navigation.cappedTabSize
import com.cryptochecker.app.ui.theme.ChipShape
import com.cryptochecker.app.ui.theme.Spacing
import com.cryptochecker.marketdata.model.ExchangeCategory
import com.cryptochecker.marketdata.model.ReferenceSource

/** Zustand und Aktionen der Übersicht (Markt-Chip, zuletzt genutzte Börsen). */
class BrowseUi(
    val market: MarketFilter = MarketFilter.ALL,
    val onMarket: (MarketFilter) -> Unit = {},
    /** Schlüssel der zuletzt geöffneten Börsen, neueste zuerst. */
    val recent: List<String> = emptyList(),
    val onOpenExchange: (ExchangeInfo) -> Unit = {},
)

/** Name eines Markt-Chips. */
@Composable
private fun marketLabel(filter: MarketFilter): String = stringResource(
    when (filter) {
        MarketFilter.ALL -> R.string.group_all
        MarketFilter.SPOT -> R.string.explorer_section_spot
        MarketFilter.FUTURES -> R.string.explorer_section_futures
        MarketFilter.DEX -> R.string.explorer_market_dex
        MarketFilter.FOREX -> R.string.explorer_market_forex
    }
)

/**
 * Markt-Chips unter dem Suchfeld: Alle · Spot · Futures · DEX · Devisen, im Stil der Gruppen-Chips der Merkliste
 * (Rand, gewählt in der Themenfarbe, Screenreader «ausgewählt»). Waagrecht scrollbar, bei grosser Schrift blendet die
 * Zeile am Ende aus ([fadeEnd]).
 */
@Composable
internal fun MarketChips(selected: MarketFilter, onSelect: (MarketFilter) -> Unit, modifier: Modifier = Modifier) {
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().fadeEnd { rowState.canScrollForward },
    ) {
        items(MarketFilter.entries, key = { it.name }) { filter ->
            MarketChip(marketLabel(filter), filter == selected, UiTestTags.explorerMarket(filter.name)) { onSelect(filter) }
        }
    }
}

/** Ein Text-Chip wie `GroupChip` der Merkliste: Tippfläche 48 dp, sichtbar mindestens 32 dp, Rolle «Tab». */
@Composable
private fun MarketChip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val chipSelected = selected
    val interaction = remember { MutableInteractionSource() }
    val label = MaterialTheme.typography.labelLarge
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = chipSelected }
            .testTag(tag)
            .wrapContentSize()
            .heightIn(min = 32.dp)
            .clip(ChipShape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outline, ChipShape)
            .indication(interaction, LocalIndication.current)
            .padding(horizontal = Spacing.lg),
    ) {
        Text(
            text = text,
            style = label.copy(fontSize = cappedTabSize(label.fontSize), lineHeight = cappedTabSize(label.lineHeight)),
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Ohne Suchbegriff: «Zuletzt genutzte Börsen» und «Alle Börsen» — Börsen nach dem Markt-Chip gefiltert, mit Favoriten
 * (Stern) vorne. Eine Börse antippen öffnet ihre Paare.
 */
@Composable
internal fun BrowseSections(
    browse: BrowseUi,
    selection: ExplorerSelection,
    favorites: FavoritesUi,
) {
    val categories = remember(selection) { selection.exchanges.zip(selection.exchangeCategories).toMap() }
    if (browse.market == MarketFilter.FOREX) return ForexSections(browse, selection, categories, favorites)
    val shown = selection.exchanges.filter { browse.market.allows(categories[it] ?: ExchangeCategory.SPOT) }

    val byKey = shown.associateBy { it.key }
    val recent = browse.recent.mapNotNull { byKey[it] }
    if (recent.isNotEmpty()) {
        SectionLabel(stringResource(R.string.explorer_recent_exchanges_title), Modifier.padding(top = Spacing.md))
        ExchangeRows(recent, categories, favorites, browse.onOpenExchange)
    }

    SectionLabel(stringResource(R.string.explorer_all_exchanges_title), Modifier.padding(top = Spacing.md))
    if (shown.isEmpty()) {
        Text(
            text = stringResource(R.string.explorer_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        )
    } else {
        // Favoriten zuerst (wie früher in der Auswahlliste), sonst die Reihenfolge der Abschnitte (Volumen)
        val (favored, others) = shown.partition { it.name in favorites.exchanges }
        ExchangeRows(favored + others, categories, favorites, browse.onOpenExchange)
    }
}

/**
 * Chip «Devisen» in zwei Gruppen: «Live (Börse)» — Kraken und Bitstamp mit echten Fiat-Paaren ([ForexLive], geöffnet nur
 * diese Paare) — und «Referenzkurs» (EZB, NBU, NBP; Takt und Quelle in der Zeile). Wie iOS `ForexSections`.
 */
@Composable
private fun ForexSections(
    browse: BrowseUi,
    selection: ExplorerSelection,
    categories: Map<ExchangeInfo, ExchangeCategory>,
    favorites: FavoritesUi,
) {
    val live = ForexLive.EXCHANGES.mapNotNull { key -> selection.exchanges.firstOrNull { it.key == key } }
    if (live.isNotEmpty()) {
        SectionLabel(stringResource(R.string.explorer_forex_live), Modifier.padding(top = Spacing.md))
        ExchangeRows(live, categories, favorites, browse.onOpenExchange)
    }
    SectionLabel(stringResource(R.string.explorer_forex_reference), Modifier.padding(top = Spacing.md))
    ExchangeRows(selection.exchanges.filter { categories[it] == ExchangeCategory.FOREX }, categories, favorites, browse.onOpenExchange)
}

/** Liste von Börsen-Zeilen mit kleinem Abstand. */
@Composable
internal fun ExchangeRows(
    exchanges: List<ExchangeInfo>,
    categories: Map<ExchangeInfo, ExchangeCategory>,
    favorites: FavoritesUi,
    onOpen: (ExchangeInfo) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
        exchanges.forEach { exchange ->
            ExchangeRow(
                exchange = exchange,
                category = categories[exchange] ?: ExchangeCategory.SPOT,
                favorite = exchange.name in favorites.exchanges,
                onToggleFavorite = { favorites.onToggle(FavoriteKind.EXCHANGE, exchange.name) },
                onClick = { onOpen(exchange) },
            )
        }
    }
}

/**
 * Eine Börse: Logo bzw. Zeichen ([ExchangeMarkBadge]), Name und — bei DexScreener die bekannten DEX («Uniswap, PancakeSwap …»),
 * bei Referenzkursen Takt und Quelle («Referenzkurs, wöchentlich · Quelle: Polnische Nationalbank») —, rechts der Stern
 * (Favorit) und der Pfeil. Ein Fokus für die Zeile.
 */
@Composable
private fun ExchangeRow(
    exchange: ExchangeInfo,
    category: ExchangeCategory,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    val subtitle = when (category) {
        ExchangeCategory.DEX -> stringResource(R.string.dex_picker_subtitle)
        ExchangeCategory.FOREX -> ReferenceSource.of(exchange.key)?.let { source ->
            referencePeriodText(source).replaceFirstChar { it.titlecase() } + " · " + referenceSourceName(source)
        } ?: stringResource(R.string.explorer_section_forex)
        else -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag(UiTestTags.explorerExchange(exchange.key))
            .clickable(onClickLabel = stringResource(R.string.explorer_coin_show_pairs), onClick = onClick)
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
    ) {
        ExchangeMarkBadge(exchange.key, exchange.name, size = 36.dp, modifier = Modifier.padding(end = Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(exchange.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline),
                contentDescription = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Börse → Art (Spot · Futures · DEX · Forex) der geladenen Auswahl. */
internal fun categoriesOf(selection: ExplorerSelection): Map<ExchangeInfo, ExchangeCategory> =
    selection.exchanges.zip(selection.exchangeCategories).toMap()

/**
 * Börsen, die der Suchtext nennt ([ExchangeSearch]), nach dem Markt-Chip gefiltert. Ohne DexScreener: dorthin führt
 * schon der DEX-Treffer ([dexSearchHit]).
 */
internal fun exchangeHits(query: String, selection: ExplorerSelection, market: MarketFilter): List<ExchangeInfo> {
    if (query.isBlank()) return emptyList()
    val categories = categoriesOf(selection)
    val offered = selection.exchanges
        .filter { it.key != DEX_EXCHANGE_KEY && market.allows(categories[it] ?: ExchangeCategory.SPOT) }
    return ExchangeSearch.find(query, offered) { it.name }
}
