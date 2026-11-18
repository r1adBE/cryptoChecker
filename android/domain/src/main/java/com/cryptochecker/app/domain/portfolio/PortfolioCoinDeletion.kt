// «Coin löschen» (Wischen auf einem Coin): welche Transaktionen mitgehen und welche nur ihre Gebühr in diesem Coin
// verlieren (Teil von `PortfolioRepository`, gleiche Regel in Rückfrage, Banner und iOS `PortfolioEdits.deleteCoin`).
package com.cryptochecker.app.domain.portfolio

object PortfolioCoinDeletion {

    /** [tx] gehört zum Coin [key] (vereinheitlicht): Coin der Transaktion oder beim Tausch der erhaltene Coin. */
    fun involves(tx: PortfolioTxEntity, key: String): Boolean = tx.coins().any { PortfolioCalculator.normalizeCoin(it) == key }

    /**
     * [tx] eines anderen Coins bezahlt seine Gebühr in [key] (dritter Coin, z. B. BNB). Bliebe sie stehen, erschiene
     * [key] nach dem Löschen wieder als Position «mehr abgegeben als erfasst» — sie wird daher mit entfernt.
     */
    fun paysFeeIn(tx: PortfolioTxEntity, key: String): Boolean =
        !involves(tx, key) && tx.feeCoinAmount != null && tx.feeCoin?.let { PortfolioCalculator.normalizeCoin(it) } == key

    /** [tx] ohne Gebühr im dritten Coin («Rückgängig» legt die Originalfassung mit derselben id wieder an). */
    fun withoutThirdFee(tx: PortfolioTxEntity): PortfolioTxEntity = tx.copy(feeCoin = null, feeCoinAmount = null, feeCoinPriceUsdt = null)

    /** Zahlen für die Rückfrage: (gelöschte Transaktionen, Transaktionen anderer Coins, die die Gebühr verlieren). */
    fun counts(all: List<PortfolioTxEntity>, coin: String): Pair<Int, Int> {
        val key = PortfolioCalculator.normalizeCoin(coin)
        return all.count { involves(it, key) } to all.count { paysFeeIn(it, key) }
    }
}
