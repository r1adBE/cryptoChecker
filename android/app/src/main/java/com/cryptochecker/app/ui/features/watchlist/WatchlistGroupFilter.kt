// Gruppen-Filter der Merkliste: Gruppennamen, gewählte Gruppe, Token-/Alpha-Chips und die Art je Paar
// (`assetKind`). Eigene Datei, weil es reine Daten und Regeln ohne Android-Bezug sind, die ViewModel und
// Gruppen-Chips teilen.
package com.cryptochecker.app.ui.features.watchlist

import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup

/** Gruppennamen der Paare, alphabetisch. */
internal fun groupsOf(watches: List<WatchEntity>): List<String> =
    watches.mapNotNull { it.groupName }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Gruppen der Merkliste und die aktuelle Auswahl (null = «Alle»). */
data class GroupFilter(
    val groups: List<String>,
    val selected: String?,
    /** «Token»-Chip zeigen ([WatchFilter.showsTokens]). */
    val tokens: Boolean = false,
    /** «Alpha»-Chip zeigen ([WatchFilter.showsAlpha]). */
    val alpha: Boolean = false,
    /** Art je Paar (Id → [AssetKind]); fehlt eines, gilt [AssetKind.TOKEN]. */
    val kinds: Map<Long, AssetKind> = emptyMap(),
)

/**
 * Art des Paars ([AssetKind.of]): TradFi aus der gespeicherten Liste der TradFi-Paare
 * ([com.cryptochecker.app.data.CoinLogoRepository.tradFiPairs]) oder der Gruppe «TradFi» (die Liste
 * pflegt nur der Logo-Abgleich), Alpha aus der gespeicherten Alpha-Liste bzw. der Kennung eines
 * Binance-Alpha-Paars; [alphaNames] und [pairName] (Name des Coins an der Börse) gegen Fehltreffer.
 */
internal fun WatchEntity.assetKind(
    tradFiPairs: Set<String>,
    alphaSymbols: Set<String>,
    alphaNames: Map<String, String> = emptyMap(),
    pairName: String? = null,
): AssetKind =
    AssetKind.of(
        marketKey = marketKey,
        base = baseAsset,
        tradFi = groupName == AutoGroup.TRADFI ||
            CoinLogos.pairKey(marketKey, baseAsset, quoteAsset, contractType.name) in tradFiPairs,
        alphaSymbols = alphaSymbols,
        pairId = pairId,
        pairName = pairName,
        alphaNames = alphaNames,
    )
