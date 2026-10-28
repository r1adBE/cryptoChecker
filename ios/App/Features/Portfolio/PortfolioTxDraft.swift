import SwiftUI

// Entwurf einer Transaktion im Erfassen-Blatt (`PortfolioTxDraft`) samt Übernahme einer gespeicherten
// Transaktion. Eigene Datei, weil es reine Daten sind (wie `PortfolioTxDraft.kt`).

/// Eingabe des Erfassen-Blatts; `txId` 0 = neue Transaktion — wie `TxDraft`.
struct PortfolioTxDraft: Identifiable {
    let id = UUID()
    var txId: Int64 = 0
    var coin = ""
    var type: PortfolioTxType = .BUY
    /// Exakt (`Decimal`): 0.00000001 BTC = 1 Satoshi.
    var amount: Decimal?
    var priceUsdt: Decimal?
    var time: Int64 = EpochTime.nowMillis
    var note: String?
    /// Gebühr (> 0) in `feeUnit`; nil = keine.
    var fee: Decimal?
    var feeUnit: PortfolioFeeUnit = .QUOTE
    /// Börse des Handels (nur Anzeige); nil = keine Angabe.
    var exchange: String?
    /// Umbuchung: von/nach (Börse oder Wallet); nil = keine Angabe.
    var transferFrom: String?
    var transferTo: String?
    /// Gebühr in einem dritten Coin: Coin, Menge (> 0), USDT je Gebühr-Coin (optional).
    var feeCoin: String?
    var feeCoinAmount: Decimal?
    var feeCoinPriceUsdt: Decimal?
    /// Herkunft aus dem CSV-Import — bleibt beim Bearbeiten erhalten.
    var importKey: String?
    /// Einzahlung: ursprüngliches Kaufdatum (ms, FIFO/Haltedauer); nil = Datum der Einzahlung.
    var acquiredTime: Int64?
    /// Tausch: erhaltener Coin, erhaltene Menge (> 0), Wert des Tauschs in USDT (gesamt; nil = unbekannt).
    var toCoin: String?
    var toAmount: Decimal?
    var swapValueUsdt: Decimal?
    /// CSV-Importlauf — bleibt beim Bearbeiten erhalten («Import rückgängig»).
    var importBatch: String?
    /// Schenkung an Ehe-/Lebenspartner (nur bei `.GIFT`).
    var giftToSpouse = false
    /// Verknüpfte Umbuchung: zweiter Herkunftsschlüssel und Zustand für «Import rückgängig» — bleiben beim Bearbeiten erhalten.
    var linkedImportKey: String?
    var importRestoreState: CsvRestoreState?
    /// Gebühr-Zeile (CSV «Fee») — bleibt beim Bearbeiten erhalten, solange die Art «Verkauf» bleibt.
    var feeRow = false
    /// Belohnung ist ein Zins (Lending; nur bei `.REWARD`, im Blatt umschaltbar).
    var interest = false
    /// Staking-Umbuchung (CSV) — bleibt beim Bearbeiten erhalten, solange die Art «Umbuchung» bleibt; von Hand nie gesetzt.
    var stakingMove = false

    init(coin: String = "", priceUsdt: Double? = nil) {
        self.coin = PortfolioCalculator.normalizeCoin(coin)
        self.priceUsdt = PortfolioDecimal.of(priceUsdt)
    }

    init(tx: PortfolioTx) {
        txId = tx.id
        coin = tx.coin
        type = tx.type
        amount = tx.amountExact
        priceUsdt = tx.priceExact
        time = tx.time
        note = tx.note
        fee = tx.feeExact
        feeUnit = tx.feeUnit ?? .QUOTE
        exchange = tx.exchange
        transferFrom = tx.transferFrom
        transferTo = tx.transferTo
        feeCoin = tx.feeCoin
        feeCoinAmount = tx.feeCoinAmountExact
        feeCoinPriceUsdt = tx.feeCoinPriceExact
        importKey = tx.importKey
        acquiredTime = tx.acquiredTime
        toCoin = tx.toCoin
        toAmount = tx.toAmountExact
        swapValueUsdt = tx.swapValueExact
        importBatch = tx.importBatch
        giftToSpouse = tx.giftToSpouse
        linkedImportKey = tx.linkedImportKey
        importRestoreState = tx.importRestoreState
        feeRow = tx.feeRow
        interest = tx.interest
        stakingMove = tx.stakingMove
    }

    var isEdit: Bool { txId != 0 }
}
