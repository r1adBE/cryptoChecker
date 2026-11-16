package com.cryptochecker.app.domain.watch

import com.cryptochecker.app.domain.logos.CoinLogos
import com.cryptochecker.app.domain.model.WatchEntity
import com.cryptochecker.app.domain.watch.AssetKind
import com.cryptochecker.app.domain.watch.AutoGroup

/**
 * Art des Paars ([AssetKind.of]): TradFi aus der gespeicherten Liste der TradFi-Paare
 * ([com.cryptochecker.app.data.logos.CoinLogoRepository.tradFiPairs]) oder der Gruppe «TradFi» (die Liste
 * pflegt nur der Logo-Abgleich), Alpha aus der gespeicherten Alpha-Liste bzw. der Kennung eines
 * Binance-Alpha-Paars; [alphaNames] und [pairName] (Name des Coins an der Börse) gegen Fehltreffer.
 */
fun WatchEntity.assetKind(
    tradFiPairs: Set<String>,
    alphaSymbols: Set<String>,
    alphaNames: Map<String, String> = emptyMap(),
    pairName: String? = null,
): AssetKind =
    AssetKind.of(
        exchangeKey = exchangeKey,
        base = baseAsset,
        tradFi = groupName == AutoGroup.TRADFI ||
            CoinLogos.pairKey(exchangeKey, baseAsset, quoteAsset, contractType.name) in tradFiPairs,
        alphaSymbols = alphaSymbols,
        pairId = pairId,
        pairName = pairName,
        alphaNames = alphaNames,
    )
