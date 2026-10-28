// Entwurf einer Transaktion im Erfassen-Blatt (`TxDraft`) und die Umwandlung einer gespeicherten Transaktion
// in einen Entwurf. Eigene Datei, weil es reine Daten ohne Android-Bezug sind (wie `PortfolioTxDraft.swift`).
package com.cryptochecker.app.ui.features.portfolio

import com.cryptochecker.app.data.portfolio.PortfolioTxEntity
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.math.BigDecimal

/** Eingabe des Erfassen-Blatts; id 0 = neue Transaktion. */
data class TxDraft(
    val id: Long = 0,
    val coin: String = "",
    val type: PortfolioTxType = PortfolioTxType.BUY,
    /** Exakt ([java.math.BigDecimal]): 0.00000001 BTC = 1 Satoshi. */
    val amount: BigDecimal? = null,
    val priceUsdt: BigDecimal? = null,
    val time: Long = System.currentTimeMillis(),
    val note: String? = null,
    /** Gebühr (> 0) in [feeUnit]; null = keine. */
    val fee: BigDecimal? = null,
    val feeUnit: PortfolioFeeUnit = PortfolioFeeUnit.QUOTE,
    /** Börse des Handels (nur Anzeige); null = keine Angabe. */
    val exchange: String? = null,
    /** Umbuchung: von/nach (Börse oder Wallet); null = keine Angabe. */
    val transferFrom: String? = null,
    val transferTo: String? = null,
    /** Gebühr in einem dritten Coin: Coin, Menge (> 0), USDT je Gebühr-Coin (optional). */
    val feeCoin: String? = null,
    val feeCoinAmount: BigDecimal? = null,
    val feeCoinPriceUsdt: BigDecimal? = null,
    /** Herkunft aus dem CSV-Import — bleibt beim Bearbeiten erhalten. */
    val importKey: String? = null,
    /** Einzahlung: ursprüngliches Kaufdatum (ms, FIFO/Haltedauer); null = Datum der Einzahlung. */
    val acquiredTime: Long? = null,
    /** Tausch: erhaltener Coin, erhaltene Menge (> 0), Wert des Tauschs in USDT (gesamt; null = unbekannt). */
    val toCoin: String? = null,
    val toAmount: BigDecimal? = null,
    val swapValueUsdt: BigDecimal? = null,
    /** CSV-Importlauf — bleibt beim Bearbeiten erhalten («Import rückgängig»). */
    val importBatch: String? = null,
    /** Schenkung an Ehe-/Lebenspartner (nur bei [PortfolioTxType.GIFT]). */
    val giftToSpouse: Boolean = false,
    /** Verknüpfte Umbuchung: zweiter Herkunftsschlüssel und Zustand für «Import rückgängig» — bleiben beim Bearbeiten erhalten. */
    val linkedImportKey: String? = null,
    val importRestoreState: CsvRestoreState? = null,
    /** Gebühr-Zeile (CSV «Fee») — bleibt beim Bearbeiten erhalten, solange die Art «Verkauf» bleibt. */
    val feeRow: Boolean = false,
    /** Belohnung ist ein Zins (Lending; nur bei [PortfolioTxType.REWARD], im Blatt umschaltbar). */
    val interest: Boolean = false,
    /** Staking-Umbuchung (CSV) — bleibt beim Bearbeiten erhalten, solange die Art «Umbuchung» bleibt; von Hand nie gesetzt. */
    val stakingMove: Boolean = false,
)

fun PortfolioTxEntity.toDraft() = TxDraft(
    id = id,
    coin = coin,
    type = type,
    amount = amount,
    priceUsdt = priceUsdt,
    time = time,
    note = note,
    fee = fee,
    feeUnit = PortfolioFeeUnit.fromName(feeUnit),
    exchange = exchange,
    transferFrom = transferFrom,
    transferTo = transferTo,
    feeCoin = feeCoin,
    feeCoinAmount = feeCoinAmount,
    feeCoinPriceUsdt = feeCoinPriceUsdt,
    importKey = importKey,
    acquiredTime = acquiredTime,
    toCoin = toCoin,
    toAmount = toAmount,
    swapValueUsdt = swapValueUsdt,
    importBatch = importBatch,
    giftToSpouse = giftToSpouse,
    linkedImportKey = linkedImportKey,
    importRestoreState = importRestoreState,
    feeRow = feeRow,
    interest = interest,
    stakingMove = stakingMove,
)
