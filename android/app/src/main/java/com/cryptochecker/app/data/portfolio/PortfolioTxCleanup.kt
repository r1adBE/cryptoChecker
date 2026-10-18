// Bereinigung einer Transaktion vor dem Speichern und Einmal-Merker in `portfolio_meta` (Teil von `PortfolioRepository`).
package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioImportBatch
import com.cryptochecker.app.domain.portfolio.PortfolioTxType

/**
 * Bereinigte Fassung von [tx] für [PortfolioRepository.save]: Coin vereinheitlicht (Grossbuchstaben), Gebühr, Umbuchung,
 * Tausch und Herkunftsschlüssel geprüft. Wirft bei einem ungültigen Tausch.
 */
internal fun cleanForSave(tx: PortfolioTxEntity): PortfolioTxEntity {
    // Gebühr nur gültig (> 0) und dann immer mit Einheit; ohne Gebühr keine Einheit
    val fee = tx.fee?.takeIf { it.signum() > 0 }
    val coin = PortfolioCalculator.normalizeCoin(tx.coin)
    // Gebühr im dritten Coin nur vollständig (Coin ≠ Coin der Transaktion, Menge > 0); Preis ≥ 0 oder keiner
    val third = PortfolioJson.cleanFeeCoin(tx.feeCoin, coin, tx.feeCoinAmount)
    val transfer = tx.type == PortfolioTxType.TRANSFER
    val swap = tx.type == PortfolioTxType.SWAP
    // Tausch: erhaltener Coin ≠ Coin und Menge > 0 (sonst ungültig), Wert ≥ 0 oder unbekannt
    val toCoin = if (swap) PortfolioJson.cleanToCoin(tx.toCoin, coin) else null
    val toAmount = if (swap) tx.toAmount?.takeIf { it.signum() > 0 } else null
    require(!swap || (toCoin != null && toAmount != null)) { "Ungültiger Tausch" }
    // Verknüpfte Umbuchung: zweiter Herkunftsschlüssel nur zu einem ersten, Zustand für «Rückgängig» nur zur Verknüpfung
    val importKey = PortfolioJson.cleanImportKey(tx.importKey)
    val linkedImportKey = importKey?.let { PortfolioJson.cleanImportKey(tx.linkedImportKey) }
    val clean = tx.copy(
        coin = coin,
        note = tx.note?.trim()?.takeIf { it.isNotEmpty() },
        fee = fee,
        feeUnit = if (fee != null) PortfolioFeeUnit.fromName(tx.feeUnit).name else null,
        // Umbuchung: von/nach statt Börse
        exchange = if (transfer) null else PortfolioJson.cleanExchange(tx.exchange),
        transferFrom = if (transfer) PortfolioJson.cleanExchange(tx.transferFrom) else null,
        transferTo = if (transfer) PortfolioJson.cleanExchange(tx.transferTo) else null,
        // An Ehe-/Lebenspartner gibt es nur bei einer Schenkung
        giftToSpouse = tx.giftToSpouse && tx.type == PortfolioTxType.GIFT,
        feeCoin = third?.first,
        feeCoinAmount = third?.second,
        feeCoinPriceUsdt = if (third != null) tx.feeCoinPriceUsdt?.takeIf { it.signum() >= 0 } else null,
        importKey = importKey,
        linkedImportKey = linkedImportKey,
        importRestoreState = tx.importRestoreState?.takeIf { linkedImportKey != null },
        acquiredTime = PortfolioJson.cleanAcquiredTime(tx.type, tx.acquiredTime),
        toCoin = toCoin,
        toAmount = toAmount,
        swapValueUsdt = if (swap) tx.swapValueUsdt?.takeIf { it.signum() >= 0 } else null,
        importBatch = PortfolioImportBatch.clean(tx.importBatch),
    )
    return clean
}

/**
 * Setzt [key] in `portfolio_meta` (nur innerhalb einer Portfolio-Transaktion aufrufen).
 * @return false, wenn der Merker schon gesetzt war.
 */
internal fun markOnce(database: PortfolioDatabase, key: String): Boolean {
    val db = database.openHelper.writableDatabase
    // Tabelle legt sonst PortfolioLegacyImport beim Öffnen an; hier sicherheitshalber auch
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `${PortfolioLegacyImport.META_TABLE}` " +
            "(`key` TEXT NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)"
    )
    val done = db.query("SELECT 1 FROM `${PortfolioLegacyImport.META_TABLE}` WHERE `key` = ?", arrayOf<Any?>(key))
        .use { it.moveToFirst() }
    if (done) return false
    db.execSQL(
        "INSERT INTO `${PortfolioLegacyImport.META_TABLE}` (`key`, `value`) VALUES (?, ?)",
        arrayOf<Any?>(key, System.currentTimeMillis().toString()),
    )
    return true
}
