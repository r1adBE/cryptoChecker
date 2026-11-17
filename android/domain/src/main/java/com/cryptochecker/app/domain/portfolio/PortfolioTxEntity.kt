package com.cryptochecker.app.domain.portfolio

import com.cryptochecker.app.domain.portfolio.csvimport.CsvRestoreState
import java.math.BigDecimal

/**
 * Transaktion im Portfolio ([PortfolioTxType]: Kauf, Verkauf, Umbuchung, Belohnung, Ein-/Auszahlung) —
 * unabhängig von der Merkliste. Fachmodell ohne Room; die Tabellenzeile ist `PortfolioTxRow` in :data.
 * Preise in USDT; [priceUsdt] null = unbekannt (z. B. übernommener Bestand). Menge, Preis und Gebühr
 * sind exakt ([BigDecimal], als Text gespeichert): 1 Satoshi = 0.00000001 BTC bleibt genau 1 Satoshi.
 */
data class PortfolioTxEntity(
    val id: Long = 0,

    /** Basis-Symbol in Grossbuchstaben, z. B. «BTC». */
    val coin: String,

    val type: PortfolioTxType,

    /** Menge, immer > 0 — exakt (Text in der Datenbank, `PortfolioDecimalConverters` in :data). */
    val amount: BigDecimal,

    /** Preis je Coin in USDT — geschrieben mit der Regel der Preise (`PortfolioPriceConverters` in :data). */
    val priceUsdt: BigDecimal? = null,

    /** Zeitpunkt des Handels (ms). */
    val time: Long,

    val note: String? = null,

    /** Gebühr (> 0) in [feeUnit]; null = keine erfasst. */
    val fee: BigDecimal? = null,

    /** Name von [PortfolioFeeUnit] («QUOTE» = USDT, «COIN»); null mit Gebühr = USDT. */
    val feeUnit: String? = null,

    /**
     * Börse des Handels; alle Transaktionen eines Coins mit derselben Börse → Kurs dieser Börse
     * ([PortfolioExchangeRoute]). null = keine Angabe. Bei einer Umbuchung null (dort [transferFrom]/[transferTo]).
     */
    val exchange: String? = null,

    /** Umbuchung: woher (Börse oder Wallet, freier Text); null = keine Angabe. */
    val transferFrom: String? = null,

    /** Umbuchung: wohin (Börse oder Wallet, freier Text) — dort liegt der Bestand danach; null = keine Angabe. */
    val transferTo: String? = null,

    /** Gebühr in einem dritten Coin (z. B. «BNB»); null = keine. Nur zusammen mit [feeCoinAmount]. */
    val feeCoin: String? = null,

    /** Menge der Gebühr in [feeCoin] (> 0) — exakt. */
    val feeCoinAmount: BigDecimal? = null,

    /** USDT je [feeCoin] zum Zeitpunkt (≥ 0); null = unbekannt. Regel der Preise (`PortfolioPriceConverters` in :data). */
    val feeCoinPriceUsdt: BigDecimal? = null,

    /**
     * Herkunft aus einem CSV-Import (Quelle + Zeilenschlüssel, [com.cryptochecker.app.domain.portfolio.csvimport.CsvImportRow]);
     * erkennt dieselbe Zeile beim nächsten Import als doppelt. null = von Hand erfasst.
     */
    val importKey: String? = null,

    /**
     * Einzahlung: ursprüngliches Kaufdatum (ms) — FIFO-Reihenfolge und Haltedauer ab diesem Datum
     * ([PortfolioTrade.acquiredTime]); null = Datum der Einzahlung. Nur bei [PortfolioTxType.DEPOSIT].
     */
    val acquiredTime: Long? = null,

    /** Tausch ([PortfolioTxType.SWAP]): erhaltener Coin (Grossbuchstaben, ≠ [coin]); sonst null. */
    val toCoin: String? = null,

    /** Tausch: erhaltene Menge (> 0) — exakt (Text). */
    val toAmount: BigDecimal? = null,

    /** Tausch: Wert in USDT (gesamt, ≥ 0); null = unbekannt (Erlös und Einstand des neuen Postens unbekannt). */
    val swapValueUsdt: BigDecimal? = null,

    /**
     * CSV-Importlauf ([com.cryptochecker.app.domain.portfolio.PortfolioImportBatch]): alle Transaktionen eines Imports
     * haben dieselbe Kennung — «Import rückgängig» löscht genau sie. null = von Hand erfasst.
     */
    val importBatch: String? = null,

    /**
     * Schenkung ([PortfolioTxType.GIFT]) an Ehe-/Lebenspartner — UK/CA ohne Gewinn und Verlust
     * ([com.cryptochecker.app.domain.portfolio.TaxTemplate.spouseGiftNeutral]); bei anderen Arten immer false.
     */
    val giftToSpouse: Boolean = false,

    /**
     * Verknüpfte Umbuchung ([com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch]): Herkunft der Einzahlung
     * ([importKey] = die der Auszahlung) — erkennt beim nächsten Import auch diese Zeile als doppelt. Sonst null.
     */
    val linkedImportKey: String? = null,

    /**
     * Verknüpfte Umbuchung aus einer gespeicherten Transaktion: deren Zustand davor samt der darin aufgegangenen neuen Zeile
     * ([CsvRestoreState], JSON-Text über `PortfolioRestoreStateConverters` in :data) für «Import rückgängig». Sonst null.
     */
    val importRestoreState: CsvRestoreState? = null,

    /**
     * Gebühr-Zeile (CSV «Fee»): eine Veräusserung zum Tageskurs ([com.cryptochecker.app.domain.portfolio.PortfolioLedger.isFeeDisposal]),
     * gespeichert als Verkauf. Nur bei [PortfolioTxType.SELL].
     */
    val feeRow: Boolean = false,

    /**
     * Belohnung ist ein Zins (Lending) — AT: Einkunft beim Zufluss, Anschaffungskosten = Wert. CSV-Import setzt es, wo die
     * Börse Zinsen ausweist; im Erfassen-Blatt umschaltbar. Nur bei [PortfolioTxType.REWARD].
     */
    val interest: Boolean = false,

    /**
     * Staking-Umbuchung (CSV «Unstaking», «Redeem»): eigene Mittel zwischen Staking und Guthaben derselben Börse, nie eine
     * Auszahlung ([com.cryptochecker.app.domain.portfolio.csvimport.CsvTransferMatch.storedWithdrawal]). CSV-Import setzt
     * es; bleibt beim Bearbeiten erhalten, solange die Art «Umbuchung» bleibt. Nur bei [PortfolioTxType.TRANSFER].
     */
    val stakingMove: Boolean = false,
) {
    fun toTrade(): PortfolioTrade = PortfolioTrade(
        id = id,
        coin = coin,
        type = type,
        amount = amount,
        priceUsdt = priceUsdt,
        time = time,
        fee = fee,
        feeUnit = fee?.let { PortfolioFeeUnit.fromName(feeUnit) },
        feeCoin = feeCoin,
        feeCoinAmount = feeCoinAmount,
        feeCoinPrice = feeCoinPriceUsdt,
        acquiredTime = acquiredTime?.takeIf { type == PortfolioTxType.DEPOSIT },
        exchange = exchange,
        transferFrom = transferFrom,
        transferTo = transferTo,
        toCoin = toCoin?.takeIf { type == PortfolioTxType.SWAP },
        toAmount = toAmount?.takeIf { type == PortfolioTxType.SWAP },
        swapValueUsdt = swapValueUsdt?.takeIf { type == PortfolioTxType.SWAP },
        giftToSpouse = giftToSpouse && type == PortfolioTxType.GIFT,
        imported = !importKey.isNullOrBlank(),
        // Gebühr-Zeile ([com.cryptochecker.app.domain.portfolio.PortfolioLedger.isFeeDisposal]) und Zins: eigene Felder
        feeRow = feeRow && type == PortfolioTxType.SELL,
        interestIncome = interest && type == PortfolioTxType.REWARD,
    )

    /** Beteiligte Coins: [coin] und beim Tausch [toCoin] (für Kursabfragen und Zählungen). */
    fun coins(): List<String> = listOfNotNull(coin, toCoin?.takeIf { type == PortfolioTxType.SWAP && it.isNotBlank() })

    /** Eintrag für den Börsenweg ([PortfolioExchangeRoute.exchangeByLedger]). */
    fun routeEntry(): PortfolioExchangeRoute.RouteEntry =
        PortfolioExchangeRoute.RouteEntry(coin, exchange, type, transferTo, time, id)

    /** Einträge für den Börsenweg: beim Tausch auch für den erhaltenen Coin (gleiche Börse). */
    fun routeEntries(): List<PortfolioExchangeRoute.RouteEntry> {
        val to = toCoin?.takeIf { type == PortfolioTxType.SWAP && it.isNotBlank() }
            ?: return listOf(routeEntry())
        return listOf(routeEntry(), PortfolioExchangeRoute.RouteEntry(to, exchange, type, null, time, id))
    }
}
