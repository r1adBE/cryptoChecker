@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.cryptochecker.app.ui.features.watchlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.ui.components.LocalCoinSearchNameSource
import com.cryptochecker.app.ui.features.explorer.NameMatch
import com.cryptochecker.marketdata.model.FuturesContractType

/** Gemeinsame Höhe von Statuszeile und Suchfeld, damit nichts springt; auch Kreis von «+» und Lupe (wie iOS). */
internal val SearchRowHeight = 34.dp

/**
 * Trifft die Suche dieses Paar? Gross-/Kleinschreibung und Akzente egal; mehrere Wörter
 * müssen alle passen (z. B. «btc kraken», «bitcoin usdt»). Geprüft werden Basis, Quote,
 * «BASIS/QUOTE», Vertragskürzel, Börse, Notiz und der Name des Coins ([name], z. B. «Bitcoin»).
 * Für ganze Listen je Tastendruck [WatchSearchIndex] (Felder nur einmal gefaltet).
 */
internal fun WatchEntity.matchesSearch(query: String, name: String? = null): Boolean =
    searchKey(name).matches(searchTokens(query))

/** Gefaltete Suchfelder eines Paars ([NameMatch.fold]), siehe [matchesSearch]. */
internal class WatchSearchKey(val fields: List<String>) {
    /** Jedes Wort der Anfrage ([searchTokens]) steht in mindestens einem Feld; keine Wörter: true. */
    fun matches(tokens: List<String>): Boolean = tokens.all { token -> fields.any { it.contains(token) } }
}

/** Suchschlüssel: die Felder von [matchesSearch], gefaltet; [name] = Coin-Name ([searchName]). */
internal fun WatchEntity.searchKey(name: String?): WatchSearchKey = WatchSearchKey(
    listOfNotNull(
        baseAsset,
        quoteAsset,
        displayPair,
        displayName,
        FuturesContractType.getShortName(contractType),
        marketName,
        note,
        name,
    ).map { NameMatch.fold(it) },
)

/** Wörter der Anfrage, gefaltet; getrennt am Leerzeichen, leere fallen weg. */
internal fun searchTokens(query: String): List<String> = NameMatch.fold(query).split(' ').filter { it.isNotBlank() }

/**
 * Suchschlüssel der Merkliste je [WatchEntity.id], zwischengespeichert: Neu gefaltet wird nur ein Paar, dessen
 * durchsuchte Felder (Basis, Quote, Vertrag, Börse, Notiz) oder Coin-Name sich geändert haben — Kurse und andere
 * Felder zählen nicht. Paare, die nicht mehr in der Liste stehen, fallen weg; je Tastendruck wird nur die Anfrage
 * gefaltet. Ergebnis wie `filter { it.matchesSearch(query, names[it.id]) }`. Nicht threadsicher (nur aus der
 * Komposition benutzt, [rememberWatchSearchResult]); iOS: `WatchlistSearchIndex`.
 */
internal class WatchSearchIndex {
    /** Was in den Schlüssel eingeht; gleich = der Schlüssel gilt weiter. */
    private data class Source(
        val base: String,
        val quote: String,
        val contract: FuturesContractType,
        val market: String,
        val note: String?,
        val name: String?,
    )

    private class Entry(val source: Source, val key: WatchSearchKey)

    private var entries = HashMap<Long, Entry>()
    private var lastWatches: List<WatchEntity>? = null
    private var lastNames: Map<Long, String>? = null

    /** Zwei Paare mit gleicher Kennung, aber verschiedenen Feldern: dann ohne Zwischenspeicher prüfen. */
    private var ambiguous = false

    /** Gespeicherte Schlüssel. */
    val size: Int get() = entries.size

    /** Wie oft bisher ein Schlüssel gefaltet wurde (für Tests und Messungen). */
    var keysBuilt = 0
        private set

    /** Paare aus [watches], die [query] trifft, in gleicher Reihenfolge; [names] = Coin-Namen je Kennung. */
    fun filter(watches: List<WatchEntity>, query: String, names: Map<Long, String>): List<WatchEntity> {
        update(watches, names)
        if (ambiguous) return watches.filter { it.matchesSearch(query, names[it.id]) }
        val tokens = searchTokens(query)
        return watches.filter { w -> entries[w.id]?.key?.matches(tokens) ?: w.matchesSearch(query, names[w.id]) }
    }

    /** Schlüssel auf den Stand von [watches] bringen (gleiche Liste und Namen wie zuletzt: nichts zu tun). */
    fun update(watches: List<WatchEntity>, names: Map<Long, String>) {
        if (watches === lastWatches && names === lastNames) return
        val next = HashMap<Long, Entry>(watches.size * 2)
        var clash = false
        for (w in watches) {
            val name = names[w.id]
            val source = Source(w.baseAsset, w.quoteAsset, w.contractType, w.marketName, w.note, name)
            val seen = next[w.id]
            if (seen != null) {
                if (seen.source != source) clash = true
                continue
            }
            val old = entries[w.id]
            next[w.id] = if (old != null && old.source == source) {
                old
            } else {
                keysBuilt++
                Entry(source, w.searchKey(name))
            }
        }
        entries = next
        ambiguous = clash
        lastWatches = watches
        lastNames = names
    }

    /** Alles vergessen (Suche zu). */
    fun clear() {
        if (entries.isNotEmpty()) entries = HashMap()
        ambiguous = false
        lastWatches = null
        lastNames = null
    }
}

/**
 * Treffer der Suche für die Merkliste: [query] schon getrimmt, leer = alle Paare. Die Suchschlüssel bleiben
 * zwischengespeichert, solange [keep] gilt (Suche offen), sonst werden sie verworfen.
 */
@Composable
internal fun rememberWatchSearchResult(
    watches: List<WatchEntity>,
    query: String,
    names: Map<Long, String>,
    keep: Boolean = true,
): List<WatchEntity> {
    val index = remember { WatchSearchIndex() }
    return remember(watches, query, names, keep) {
        if (query.isEmpty()) {
            if (!keep) index.clear()
            watches
        } else {
            index.filter(watches, query, names)
        }
    }
}

/**
 * Name des Coins bzw. der Firma eines Paars für die Suche ([CoinLogos.nameKey], TradFi beachtet);
 * null bei DEX-Pools ([CoinLogos.allowedFor]) und unbekannten Coins — wie `CoinLogoStore.name(for:)` in iOS.
 */
internal fun searchName(watch: WatchEntity, names: Map<String, String>, tradFiPairs: Set<String>): String? =
    searchName(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name, names, tradFiPairs)

/** Wie oben, aus den einzelnen Feldern (auch für die Zeilen der Paarwahl ohne [WatchEntity]). */
internal fun searchName(
    marketKey: String,
    baseAsset: String,
    quoteAsset: String,
    contractType: String,
    names: Map<String, String>,
    tradFiPairs: Set<String>,
): String? {
    if (!CoinLogos.allowedFor(marketKey)) return null
    val tradFi = CoinLogos.pairKey(marketKey, baseAsset, quoteAsset, contractType) in tradFiPairs
    return names[CoinLogos.nameKey(baseAsset, tradFi)]
}

/**
 * Namen der Paare für die Suche (Schlüssel [WatchEntity.id]), unabhängig vom Schalter «Namen
 * anzeigen»; nur gespeicherte Namen, kein Abruf. Einmal je Liste gerechnet, nicht je Tastendruck.
 */
@Composable
internal fun rememberSearchNames(watches: List<WatchEntity>): Map<Long, String> {
    val source = LocalCoinSearchNameSource.current ?: return emptyMap()
    LaunchedEffect(source) { source.loadSavedNames() }
    val names = source.names.collectAsState().value
    val tradFiPairs = source.tradFiPairs.collectAsState().value
    return remember(watches, names, tradFiPairs) {
        if (names.isEmpty()) {
            emptyMap()
        } else {
            watches.mapNotNull { w -> searchName(w, names, tradFiPairs)?.let { w.id to it } }.toMap()
        }
    }
}

/** Kompaktes Suchfeld anstelle der Statuszeile, mit Schliessen-Knopf rechts. */
@Composable
internal fun WatchSearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Mindesthöhe statt fester Höhe: wächst mit grosser Schrift (200 %), sonst 34 dp
            .heightIn(min = SearchRowHeight)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(50)
            )
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        ) {
            val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
            val hint = stringResource(R.string.watchlist_search_hint)
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    style = textStyle,
                    // Volle onSurfaceVariant (≥ 4.5:1); mit 70 % lag der Platzhalter unter AA
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Screenreader liest den Hinweis am Feld selbst, nicht doppelt
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                // Bezeichnung für Screenreader, auch wenn schon Text drinsteht
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics { contentDescription = hint }
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // Tippfläche 48 dp, sichtbar bleibt der kleine Kreis
                .minimumInteractiveComponentSize()
                .size(SearchRowHeight)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClose)
        ) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.watchlist_search_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Lupe rechts neben dem Status: öffnet die Suche. */
@Composable
internal fun SearchButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 8.dp)
            // Tippfläche 48 dp, sichtbar bleibt der kleine Kreis
            .minimumInteractiveComponentSize()
            .size(SearchRowHeight)
            .clip(RoundedCornerShape(50))
            // Gefüllter Kreis wie «+» daneben (neutral statt Akzentfarbe)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = stringResource(R.string.watchlist_search_open),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}
