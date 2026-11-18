package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.ExportReference
import com.cryptochecker.app.domain.portfolio.PortfolioPriceFeed
import com.cryptochecker.app.domain.portfolio.PortfolioPrices
import javax.inject.Inject

/** [PortfolioPriceFeed] über [PortfolioPriceSource] und seine Erweiterungen (gemerkte Kurse, Coins, Börsen, Export). */
class PortfolioPriceFeedAdapter @Inject constructor(private val source: PortfolioPriceSource) : PortfolioPriceFeed {
    override suspend fun price(coin: String): Double? = source.price(coin)

    override suspend fun prices(coins: Collection<String>, force: Boolean): PortfolioPrices = source.prices(coins, force)

    override suspend fun stored(coins: Collection<String>): PortfolioPrices = source.stored(coins)

    override suspend fun coins(): List<String> = source.coins()

    override suspend fun clearStored() {
        source.clearStored()
    }

    override suspend fun spotExchangeNames(): List<String> = source.spotExchangeNames()

    override suspend fun exportReference(coins: Collection<String>): ExportReference = source.exportReference(coins)
}
