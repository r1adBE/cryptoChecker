// «Coin löschen» (Wischen auf einem Coin): welche Transaktionen mitgehen und welche nur ihre Gebühr in diesem Coin
// verlieren (Teil von `PortfolioRepository`, gleiche Regel in Rückfrage, Banner und iOS `PortfolioEdits.deleteCoin`).
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioCalculator

internal object PortfolioCoinDeletion {

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

    /**
     * Löscht alle Transaktionen von [coin] (auch Tausche in diesen Coin) und entfernt bei Transaktionen anderer Coins die
     * Gebühr in [coin] ([paysFeeIn]). Gibt die gelöschten und die geänderten in ihrer Originalfassung zurück —
     * [PortfolioRepository.restore] legt beide mit ihren ids wieder an (REPLACE). Nur innerhalb einer Transaktion aufrufen.
     */
    suspend fun apply(dao: PortfolioDao, coin: String): List<PortfolioTxEntity> {
        val key = PortfolioCalculator.normalizeCoin(coin)
        val all = dao.getAll()
        val removed = all.filter { involves(it, key) }
        val feeRefs = all.filter { paysFeeIn(it, key) }
        removed.forEach { dao.delete(it.id) }
        feeRefs.forEach { dao.update(withoutThirdFee(it)) }
        return removed + feeRefs
    }

    /** Zahlen für die Rückfrage: (gelöschte Transaktionen, Transaktionen anderer Coins, die die Gebühr verlieren). */
    fun counts(all: List<PortfolioTxEntity>, coin: String): Pair<Int, Int> {
        val key = PortfolioCalculator.normalizeCoin(coin)
        return all.count { involves(it, key) } to all.count { paysFeeIn(it, key) }
    }
}
