package com.cryptochecker.app.data.remote.candles

import com.cryptochecker.app.data.CoinLogoRepository
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.domain.watch.AutoGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CandlePair] eines Paars der Merkliste — mit TradFi-Kennung aus der gespeicherten Liste der
 * TradFi-Paare ([CoinLogoRepository.isTradFi]) oder der Gruppe «TradFi» (wie `WatchEntity.assetKind`).
 */
@Singleton
class CandlePairs @Inject constructor(
    private val coinLogoRepository: CoinLogoRepository,
) {
    fun of(watch: WatchEntity): CandlePair = CandlePair.of(
        watch,
        tradFi = watch.groupName == AutoGroup.TRADFI ||
            coinLogoRepository.isTradFi(watch.marketKey, watch.baseAsset, watch.quoteAsset, watch.contractType.name),
    )
}
