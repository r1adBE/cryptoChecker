// Bereinigung einer Transaktion vor dem Speichern (Teil von `PortfolioRepository`).
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioTxType

/**
 * Bereinigte Fassung von [tx] für [PortfolioRepository.save]: Coin vereinheitlicht (Grossbuchstaben), Gebühr, Umbuchung,
 * Tausch und Herkunftsschlüssel geprüft. Mengen, Gebühren und der Wert eines Tauschs schon hier auf die gespeicherten
 * 18 Nachkommastellen gerundet ([PortfolioDecimal.amount], dieselbe Regel wie beim Speichern); Preise rundet erst
 * [PortfolioDecimalConverters] ([PortfolioDecimal.price]), damit [storable] einen Preis > 0 erkennt, der zu 0 würde. Eine
 * Menge unter 10⁻¹⁸ ist danach 0 und wird abgelehnt, statt als 0 gespeichert zu werden. Wirft bei einem ungültigen Tausch.
 */
fun cleanForSave(tx: PortfolioTxEntity): PortfolioTxEntity {
    // Gebühr nur gültig (> 0) und dann immer mit Einheit; ohne Gebühr keine Einheit
    val fee = tx.fee?.let { PortfolioDecimal.amount(it) }?.takeIf { it.signum() > 0 }
    val coin = PortfolioCalculator.normalizeCoin(tx.coin)
    // Gebühr im dritten Coin nur vollständig (Coin ≠ Coin der Transaktion, Menge > 0); Preis ≥ 0 oder keiner
    val third = PortfolioJson.cleanFeeCoin(tx.feeCoin, coin, tx.feeCoinAmount?.let { PortfolioDecimal.amount(it) })
    val transfer = tx.type == PortfolioTxType.TRANSFER
    val swap = tx.type == PortfolioTxType.SWAP
    // Tausch: erhaltener Coin ≠ Coin und Menge > 0 (sonst ungültig), Wert ≥ 0 oder unbekannt
    val toCoin = if (swap) PortfolioJson.cleanToCoin(tx.toCoin, coin) else null
    val toAmount = if (swap) tx.toAmount?.let { PortfolioDecimal.amount(it) }?.takeIf { it.signum() > 0 } else null
    require(!swap || (toCoin != null && toAmount != null)) { "Ungültiger Tausch" }
    // Verknüpfte Umbuchung: zweiter Herkunftsschlüssel nur zu einem ersten, Zustand für «Rückgängig» nur zur Verknüpfung
    val importKey = PortfolioJson.cleanImportKey(tx.importKey)
    val linkedImportKey = importKey?.let { PortfolioJson.cleanImportKey(tx.linkedImportKey) }
    val clean = tx.copy(
        coin = coin,
        amount = PortfolioDecimal.amount(tx.amount),
        note = tx.note?.trim()?.takeIf { it.isNotEmpty() },
        fee = fee,
        feeUnit = if (fee != null) PortfolioFeeUnit.fromName(tx.feeUnit).name else null,
        // Umbuchung: von/nach statt Börse
        exchange = if (transfer) null else PortfolioJson.cleanExchange(tx.exchange),
        transferFrom = if (transfer) PortfolioJson.cleanExchange(tx.transferFrom) else null,
        transferTo = if (transfer) PortfolioJson.cleanExchange(tx.transferTo) else null,
        // An Ehe-/Lebenspartner gibt es nur bei einer Schenkung
        giftToSpouse = tx.giftToSpouse && tx.type == PortfolioTxType.GIFT,
        // Staking-Umbuchung gibt es nur bei einer Umbuchung
        stakingMove = tx.stakingMove && transfer,
        feeCoin = third?.first,
        feeCoinAmount = third?.second,
        feeCoinPriceUsdt = if (third != null) tx.feeCoinPriceUsdt?.takeIf { it.signum() >= 0 } else null,
        importKey = importKey,
        linkedImportKey = linkedImportKey,
        importRestoreState = tx.importRestoreState?.takeIf { linkedImportKey != null },
        acquiredTime = PortfolioJson.cleanAcquiredTime(tx.type, tx.acquiredTime),
        toCoin = toCoin,
        toAmount = toAmount,
        swapValueUsdt = if (swap) tx.swapValueUsdt?.takeIf { it.signum() >= 0 }?.let { PortfolioDecimal.amount(it) } else null,
        importBatch = PortfolioImportBatch.clean(tx.importBatch),
    )
    return clean
}

/**
 * Bereinigte Transaktion speicherbar: Coin nicht leer, Menge > 0 und jede Zahl gespeichert wieder lesbar (unter 10³⁰,
 * [PortfolioDecimal.storableAmount] bzw. [PortfolioDecimal.storablePrice]; ein Preis > 0 wird nie zu 0) — dieselbe Regel
 * wie die CSV-Vorschau ([com.cryptochecker.app.domain.portfolio.csvimport.CsvImportMapping.unsavableReason]). Wie iOS
 * `AppData.cleanedPortfolioTx`.
 */
fun PortfolioTxEntity.storable(): Boolean {
    if (coin.isEmpty() || amount.signum() <= 0) return false
    val amounts = listOfNotNull(amount, fee, feeCoinAmount, toAmount, swapValueUsdt)
    val prices = listOfNotNull(priceUsdt, feeCoinPriceUsdt)
    return amounts.all { PortfolioDecimal.storableAmount(it) } && prices.all { PortfolioDecimal.storablePrice(it) }
}
