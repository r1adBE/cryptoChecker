// Gespeicherte und importierte Umbuchungen, Rückgängig-Plan und Verknüpfungen (CsvTransferMatch).
package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import java.math.BigDecimal

/**
 * Gespeicherte, importierte Auszahlung, Einzahlung oder Umbuchung ohne Gegenstück — mögliche zweite Seite einer neuen
 * Zeile ([CsvTransferMatch.candidates]). Nur importierte Transaktionen ([importKey]): von Hand erfasste Einzahlungen
 * tragen oft bewusst einen Kaufpreis oder ein Kaufdatum, das eine Umbuchung verwerfen würde. Die Felder ab [priceUsdt]
 * braucht nur der Zustand für «Import rückgängig» ([CsvTransferMatch.restoreState]). [linkedImportKey] gesetzt = schon
 * eine verknüpfte Umbuchung (kein Kandidat mehr). [stakingMove]: Staking-Umbuchung der Datei ([CsvTransferMatch.storedWithdrawal]).
 */
data class CsvStoredMove(
    val id: Long,
    val coin: String,
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val time: Long,
    val importKey: String,
    val transferFrom: String?,
    val transferTo: String?,
    val priceUsdt: BigDecimal? = null,
    val feeCoinPrice: BigDecimal? = null,
    val acquiredTime: Long? = null,
    val exchange: String? = null,
    val importBatch: String? = null,
    val linkedImportKey: String? = null,
    val stakingMove: Boolean = false,
)

/** Importierte Transaktion für «Import rückgängig» ([CsvTransferMatch.undoPlan]): Id, Importlauf, Zustand vor einer Verknüpfung. */
data class CsvImportedRef(val id: Long, val importBatch: String?, val restoreState: CsvRestoreState?)

/**
 * Was «Import rückgängig» tut ([CsvTransferMatch.undoPlan]): [delete] löschen, [revert] auf den Zustand vor der
 * Verknüpfung zurücksetzen, [detach] in die aufgegangene Zeile der späteren Datei zurückverwandeln (bleibt in ihrem Lauf).
 */
data class CsvUndoPlan(val delete: Set<Long>, val revert: Map<Long, CsvRestoreState>, val detach: Map<Long, CsvAbsorbedRow>)

/**
 * Vorgeschlagene Umbuchung: Auszahlung an Ort A und passende Einzahlung desselben Coins an Ort B ([CsvTransferMatch]).
 * [key] = Kennung in der Vorschau ([CsvTransferMatch.linkId], nie gespeichert). [withdrawalId] / [depositId]: gespeicherte
 * Seite (höchstens eine), sonst eine neue Zeile der Datei ([withdrawalKey] / [depositKey]).
 * [sent] = was A verlässt (Menge + Gebühr im Coin), [received] = was bei B ankommt; [fee]/[feeUnit] = Gebühr der
 * Umbuchung (Differenz im Coin, sonst eine USDT-Gebühr der Auszahlung), [feeCoin]/[feeCoinAmount] = Gebühr der
 * Auszahlung in einem dritten Coin. [unambiguous]: beide Seiten haben genau diesen einen Kandidaten (vorausgewählt).
 */
data class CsvTransferLink(
    val key: String,
    val coin: String,
    val withdrawalKey: String,
    val depositKey: String,
    val withdrawalId: Long?,
    val depositId: Long?,
    val sent: BigDecimal,
    val received: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val withdrawalTime: Long,
    val depositTime: Long,
    val from: String?,
    val to: String?,
    val unambiguous: Boolean,
    /** Gespeicherte Seite (bei [withdrawalId] bzw. [depositId]); ihr Zustand wird der Zustand für «Rückgängig». */
    val stored: CsvStoredMove? = null,
    /**
     * Ausserhalb der üblichen Grenzen (Gebühr über [CsvTransferMatch.MAX_FEE_SHARE] oder später als
     * [CsvTransferMatch.WINDOW_MILLIS]) zwischen zwei sonst unverbundenen Seiten: gezeigt, nie vorausgewählt.
     */
    val loose: Boolean = false,
    /** Neue Zeile, die gegen [stored] aufgeht; ihr Zustand kommt ebenfalls in den Zustand für «Rückgängig». */
    val absorbed: CsvImportTx? = null,
)

/**
 * Gespeicherte Transaktion [id] wird die verknüpfte Umbuchung (Felder wie die neue Umbuchung in [CsvTransferMatch.apply]):
 * Herkunft der Auszahlung in [importKey], der Einzahlung in [linkedImportKey], Zustand davor in [restoreState]; die App
 * setzt dazu den Importlauf des verknüpfenden Imports, damit «Import rückgängig» dieses Laufs die Verknüpfung löst.
 * [absorbed] = die darin aufgehende neue Zeile; ohne Verknüpfung beim Speichern eigene Zeile ([CsvTransferMatch.withFallbacks]).
 */
data class CsvStoredTransfer(
    val id: Long,
    val amount: BigDecimal,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val time: Long,
    val transferFrom: String?,
    val transferTo: String?,
    val importKey: String,
    val linkedImportKey: String,
    val restoreState: CsvRestoreState,
    val absorbed: CsvImportTx? = null,
)

/** Ergebnis der gewählten Umbuchungen: neue Transaktionen und Änderungen gespeicherter. */
data class CsvTransferPlan(val transactions: List<CsvImportTx>, val storedUpdates: List<CsvStoredTransfer>)
