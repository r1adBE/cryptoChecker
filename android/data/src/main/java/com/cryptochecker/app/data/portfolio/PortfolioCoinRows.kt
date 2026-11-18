// «Coin löschen» in der Datenbank (Teil von `PortfolioRepository`; Regel: [PortfolioCoinDeletion]).
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioCoinDeletion
import com.cryptochecker.app.domain.portfolio.PortfolioTxEntity

/**
 * Löscht alle Transaktionen von [coin] (auch Tausche in diesen Coin) und entfernt bei Transaktionen anderer Coins die
 * Gebühr in [coin] ([PortfolioCoinDeletion.paysFeeIn]). Gibt die gelöschten und die geänderten in ihrer Originalfassung zurück —
 * [PortfolioRepository.restore] legt beide mit ihren ids wieder an (REPLACE). Nur innerhalb einer Transaktion aufrufen.
 */
suspend fun deleteCoinRows(dao: PortfolioDao, coin: String): List<PortfolioTxEntity> {
    val key = PortfolioCalculator.normalizeCoin(coin)
    val all = dao.getAll()
    val removed = all.filter { PortfolioCoinDeletion.involves(it, key) }
    val feeRefs = all.filter { PortfolioCoinDeletion.paysFeeIn(it, key) }
    removed.forEach { dao.delete(it.id) }
    feeRefs.forEach { dao.update(PortfolioCoinDeletion.withoutThirdFee(it)) }
    return removed + feeRefs
}
