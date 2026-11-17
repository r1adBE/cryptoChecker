// Ergebnis des CSV-Imports: Buchung, Wahl bei Auszahlungen und Vorschlag (CsvImportMapping).
package com.cryptochecker.app.domain.portfolio.csvimport

import com.cryptochecker.app.domain.portfolio.AddressBalanceHistory
import com.cryptochecker.app.domain.portfolio.PortfolioCalculator
import com.cryptochecker.app.domain.portfolio.PortfolioDecimal
import com.cryptochecker.app.domain.portfolio.PortfolioFeeUnit
import com.cryptochecker.app.domain.portfolio.PortfolioTxType
import com.cryptochecker.app.domain.portfolio.TaxEngines
import com.cryptochecker.app.domain.portfolio.WatchedAddress
import com.cryptochecker.app.domain.portfolio.WatchedAddressRules
import com.cryptochecker.app.domain.util.TextLimit
import java.math.BigDecimal

/**
 * Importierte Zeile als Portfolio-Transaktion (vor dem Speichern). Preis und USDT-Gebühr schon umgerechnet.
 * [importKey] = [CsvImportRow.key] (erkennt die Zeile beim nächsten Import als doppelt).
 */
data class CsvImportTx(
    val line: Int,
    val coin: String,
    val type: PortfolioTxType,
    val amount: BigDecimal,
    val priceUsdt: BigDecimal?,
    val time: Long,
    val fee: BigDecimal?,
    val feeUnit: PortfolioFeeUnit?,
    val feeCoin: String?,
    val feeCoinAmount: BigDecimal?,
    val note: String?,
    val importKey: String,
    /**
     * Umbuchung: wohin ([CsvImportMapping.applyWithdrawalChoices]: passende beobachtete Adresse; verknüpfte Umbuchung
     * [CsvTransferMatch]: Börse der Einzahlung); null = keine Angabe.
     */
    val transferTo: String? = null,
    /** Verknüpfte Umbuchung ([CsvTransferMatch]): Börse der Auszahlung; null = keine Angabe. */
    val transferFrom: String? = null,
    /** Tausch ([PortfolioTxType.SWAP]): erhaltener Coin und Menge, Wert in USDT (null = unbekannt). */
    val toCoin: String? = null,
    val toAmount: BigDecimal? = null,
    val swapValueUsdt: BigDecimal? = null,
    /** Verknüpfte Umbuchung zweier neuer Zeilen ([CsvTransferMatch.apply]): Herkunft der Einzahlung; sonst null. */
    val linkedImportKey: String? = null,
    /** Gebühr-Zeile (CSV «Fee»): Veräusserung zum Tageskurs, gespeichert als Verkauf mit Merker. */
    val feeRow: Boolean = false,
    /** Belohnung, die die Börse als Zins (Lending) ausweist ([TaxEngines.interestReward] auf der Art der Datei). */
    val interest: Boolean = false,
    /** Staking-Umbuchung ([CsvImportKind.TRANSFER]: «Unstaking», «Redeem»): nie eine Auszahlung ([CsvTransferMatch.storedWithdrawal]). */
    val stakingMove: Boolean = false,
    /** Preis bzw. Gebühr fehlt, weil der Tageskurs fehlt ([CsvImportRateGate.missing]); beim Speichern Vermerk in der Notiz. */
    val missingRate: CsvMissingRate? = null,
    /**
     * Wallet der Zeile (FIFO je Wallet, Steuer-Vorlagen DE/AT/US): die Börse der Datei ([CsvTransferMatch.placeOf]; freie
     * Vorlage: keine) — dieselbe, die eine verknüpfte Umbuchung als Quelle bzw. Ziel trägt. Umbuchungen: [transferFrom].
     */
    val exchange: String? = null,
)

/** Wahl je Auszahlung der Vorschau ([CsvImportMapping.proposeWithdrawals]). */
enum class CsvWithdrawalChoice {
    /** «Auf eigene Wallet (Umbuchung)»: bleibt im Portfolio. */
    TRANSFER,

    /** «Verlässt das Portfolio (Auszahlung)». */
    WITHDRAWAL,
}

/**
 * Vorschlag für eine Auszahlung ([CsvImportTx.importKey]): [choice] null = keine Vorauswahl (muss gewählt werden);
 * [transferTo] = Ziel der Umbuchung (Bezeichnung bzw. gekürzte Adresse der passenden beobachteten Adresse).
 */
data class CsvWithdrawalProposal(val importKey: String, val choice: CsvWithdrawalChoice?, val transferTo: String?)
